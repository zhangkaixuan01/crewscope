# M2-S02：会话并发与 Redis 恢复验证记录

> 状态：VERIFIED
> 日期：2026-08-09
> AgentScope 基线：`v2.0.0`（`44c304ec`）
> 关联决策：[ADR-009](../adr/ADR-009-会话执行所有权与恢复协议.md)

## 1. 验证目标

M2-S02 验证一个 HarnessAgent 服务多个 Conversation 时的顺序、并行、取消、异常和状态恢复语义，并固定 M2 的 Session 执行所有权与部署约束。

验收场景包括：

- 同一 `(userId, sessionId)` 按订阅顺序 FIFO 进入 Model；
- 不同 Session 在任一 Turn 完成前并行进入 Model；
- 活动 Turn 取消或失败后释放后续同 Session 调用；
- Harness Gateway 的 `SessionTurnGate` 保持单 JVM 边界；
- 新 Redis Client、新 AgentStateStore 和新 HarnessAgent 恢复已完成 Turn；
- 相同 Session ID 在不同 User ID 下保持隔离；
- 未完成 Turn 丢失后从最后一个成功保存的检查点恢复；
- 测试推进只使用受控 Publisher、Latch、订阅事件和原子记录。

## 2. AgentScope 2.0.0 源码结论

### 2.1 HarnessAgent 直接调用

`ReActAgent.callSerializationKey(RuntimeContext)` 使用：

```text
(userId or __anon__) + "/" + sessionId
```

`AgentBase.serializeOnKey` 为每个 Key 维护最近一次调用的完成信号。后续调用先等待前一信号，再执行自己的生命周期；完成、错误和取消都在 `doFinally` 中释放信号并删除当前尾节点。相同 Key 串行，不同 Key 使用独立尾链。

状态槽在通过尾链后激活。配置 AgentStateStore 时，每轮开始重新读取状态，避免当前 Agent 实例长期使用本地旧副本；成功结果保存后才释放下一个同 Session 调用。

### 2.2 Harness Gateway

`SessionTurnGate` 使用 `ConcurrentHashMap<String, Semaphore>` 和公平 `Semaphore(1, true)`。`HarnessGateway.withGatedTurn/withGatedStream` 在 `doOnSubscribe` 获取许可，在 `doFinally` 释放许可，并把阻塞获取调度到 `boundedElastic`。

每个 Gateway 持有独立 Gate 实例，多个 JVM 之间没有共享许可。Gate Map 保留已见过 Key 的 `Semaphore`，后续 Gateway 生命周期管理需要限制或清理高基数 Session Key。

### 2.3 Redis AgentStateStore

`RedisDistributedStore.fromJedis(client, prefix)` 创建以下 CrewScope 相关能力：

```text
AgentStateStore = RedisAgentStateStore(prefix + "session:")
BaseStore       = RedisStore(prefix + "store:")
SnapshotSpec    = RedisSnapshotSpec(prefix + "snapshot:")
SandboxGuard    = RedisSandboxExecutionGuard(prefix + "guard:")
```

M2 只装配 AgentStateStore。单值状态 Key 为：

```text
{prefix}session:{userId-or-__anon__}/{sessionId}:agent_state
```

状态保存使用 Redis `SET`，再用 `SADD` 登记 Session Key。实现没有 TTL、CAS、版本比较或分布式锁。两个实例并发写同一状态槽时采用最后写入覆盖，因此 Redis 只承担状态存储和重启恢复。

状态读取异常会被 `ReActAgent` 捕获并降级为全新 AgentState。M2-I05 在模型执行前增加 Redis 可用性与状态槽读取预检，对既有 Session 使用 `AGENT_STATE_UNAVAILABLE` 失败关闭。

## 3. CrewScope 执行与恢复契约

```text
PostgreSQL AgentRuntimeSession
  -> stable versioned userId/sessionId
  -> single active CrewScope Server owner in M2
  -> AgentBase per-session FIFO
  -> Redis whole-state checkpoint
```

- PostgreSQL 保存 Conversation、Message、TaskIntent、Invocation 和 Session 绑定事实；
- AgentScope Redis 保存可重建的 Agent 运行上下文；
- M2 只有一个活动 Server 实例执行 Agent 调用；
- 发布切换先停止新调用，再排空或中断保存，旧实例退出后由新实例接管；
- 正常完成的 Turn 跨新 Client、Store 和 Agent 实例恢复完整上下文；
- 未完成 Turn 回到最后一个成功保存的 Redis 检查点，应用层依据 PostgreSQL Message 与 Invocation 事实决定继续方式；
- 多执行实例能力进入带 fencing token 的 Session Lease，不使用 Redis AgentStateStore 代替 Lease。

## 4. 确定性测试设计

并发测试为每个输入注册一个 `ControlledTurn`：

```text
subscribe -> model entered latch -> controlled response/error/cancel -> terminal latch
```

FIFO 测试先阻塞第一条调用，再依次订阅第二、第三条调用。订阅返回表示调用已经安装到会话尾链；断言后两条尚未进入 Model。随后逐个完成前序 Publisher，并用各自 Entered Latch 证明严格顺序。

跨 Session 测试在第一条 Publisher 保持未完成时订阅第二条 Session，并要求第二个 Entered Latch 到达。取消和异常测试把第二条调用排在活动调用后，通过 Cancel/Error 信号直接证明后续调用进入。

Redis 测试使用 `redis:7.4-alpine` Testcontainer。恢复测试关闭第一套 AgentStateStore 及其 Jedis Client，重新创建 Client、Store 和 HarnessAgent；未完成 Turn 测试在 Model 已进入且保存检查点尚未发生时终止订阅，再从新进程对象恢复。

测试中的 10 秒超时只作为死锁保护，不参与顺序、并行或清理判断。

## 5. 验证矩阵

| 场景 | 证据 | 结果 |
|---|---|---|
| 同 Session 三 Turn | 进入顺序 `one -> two -> three`，最终上下文 6 条 | 通过 |
| 不同 Session 并行 | 两个 Entered Latch 在任一响应完成前到达 | 通过 |
| 活动 Turn 取消 | Model 收到 Cancel，下一 Turn 进入，取消内容未保存 | 通过 |
| Model 异常 | Error 终止后下一同 Session Turn 进入 | 通过 |
| Gate JVM 边界 | 两个 SessionTurnGate 同时获取相同 Key | 通过 |
| Redis 新进程恢复 | 新 Client、Store、Agent 读取前一 Turn 的用户消息与回复 | 通过 |
| User 隔离 | 相同 Session ID、不同 User ID 各自保持 2 条上下文 | 通过 |
| 未完成 Turn 恢复 | 新进程只读取最后一个成功保存的完整 Turn | 通过 |

## 6. 实现范围

M2-S02 交付架构决策、验证 Fixture 和并发/Redis 集成测试。M2-I05 已在该结论上实现生产 Redis 装配、环境化 Key Prefix、读写预检、单活动实例启动保护和状态清理，验证见 [M2-I05 Redis AgentStateStore 与单活动实例](../testing/M2-I05-Redis-AgentStateStore与单活动实例.md)。低基数状态运行指标与完整调用观测由 M2-I07 统一接入。M2-A03 使用精确 Session 身份实现 Invocation Cancel，并协调 Message 与 AgentState 提交结果。

## 7. 验证结果

专项验证命令：

```bash
/Users/zhangkaixuan/Downloads/apache-maven-3.9.6/bin/mvn \
  -pl crewscope-agentscope -am \
  -Dtest=HarnessAgentM2S02ConcurrencyIntegrationTest,RedisAgentStateM2S02IntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：8 个专项测试通过，其中 5 个确定性会话调度测试、3 个 Redis Testcontainers 恢复测试。测试没有使用 `Thread.sleep`、延迟 Publisher 或时间窗口猜测顺序。

全仓回归：`mvn clean verify` 通过，共 522 个 Java 测试；`node scripts/check-doc-links.mjs` 通过，共检查 74 个 Markdown 文件；`git diff --check` 通过。

结论：AgentScope 2.0.0 可以直接提供 CrewScope M2 单实例内的同 Session FIFO、跨 Session 并行和 Redis 状态恢复。M2 使用单活动 Agent 执行实例；Redis 读失败由 CrewScope 正式 Runtime 失败关闭；横向执行以带 fencing token 的分布式 Session Lease 为前置能力。
