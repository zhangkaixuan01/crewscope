# M2-I05：Redis AgentStateStore 与单活动实例

> 日期：2026-08-09<br>
> 状态：已完成<br>
> 模块：`crewscope-application`、`crewscope-agentscope`、`crewscope-infrastructure`、`crewscope-server`

## 目标

把 [ADR-009](../adr/ADR-009-会话执行所有权与恢复协议.md) 和 [M2-S02](../spikes/M2-S02-会话并发与Redis恢复验证记录.md) 的验证结论接入正式运行时：装配 AgentScope 2.0.0 `RedisDistributedStore`/`RedisAgentStateStore`，保持稳定 Session Key、单 Session FIFO 与跨 Session 并行，并在模型前执行状态读写预检和 M2 单活动实例所有权校验。

## 实现边界

```text
AgentRuntimeSession.agentScopeKey
  -> AgentScopeNativeRuntime Invoke / Resume
  -> AgentScope same-Session FIFO Gate
  -> AgentStatePreflightMiddleware onReasoning / onActing
  -> AgentStatePreflight
     -> active execution owner check
     -> Redis PING
     -> exact agent_state read + decode + identity check
     -> isolated write/read/delete probe
     -> active execution owner recheck
  -> HarnessAgent
  -> RedisAgentStateStore whole-state checkpoint
```

Redis Keyspace 固定为：

```text
crewscope:{environment}:agentscope:v1:
  session:{userId}/{sessionId}:agent_state
  ownership:active-instance
  health:write-probe:{randomToken}
```

AgentState 不设置 TTL。Conversation 归档或业务生命周期判定过期后，通过 `AgentStateLifecycle` 显式删除完整状态槽。写探针使用随机隔离 Key 和短 TTL，正常完成立即删除，连接在删除前失败时由 TTL 回收。

## 单活动实例

Spring Boot 启动时使用 Redis `SET NX PX` 获取环境级执行所有权。所有权值包含实例标识和随机进程 Token：

- 第二个实例无法取得所有权时拒绝启动；
- 活动实例定期通过 compare-and-`PEXPIRE` Lua 原子续期；
- 正常关闭通过 compare-and-`DEL` Lua 原子释放；
- 进程崩溃后有限 TTL 清理旧租约，新实例随后接管；
- 续期异常、Token 丢失或 Redis 无法确认所有权时，当前实例永久失败关闭，等待进程重启重新取得所有权。

该租约保护整个 M2 Agent 执行拓扑，不替代后续带 fencing token 的 Session Lease。

## 失败语义

以下情况在模型执行前统一产生可重试 `STATE_UNAVAILABLE / AGENT_STATE_UNAVAILABLE`：

- Redis 不可连接、不可读或不可写；
- 已登记状态槽缺少完整 `agent_state`；
- `agent_state` 无法解码；
- 状态内部 `userId/sessionId` 与可信 Slot 不一致；
- 当前实例没有或失去环境级执行所有权。

Provider 原始错误、Redis 地址、当前 Owner Token、Session 内容和状态 JSON 不进入 Runtime Failure。传入 AgentScope 和 Spring 启动边界的状态异常不保留底层 Cause；所有权续期与释放的后台日志只记录稳定失败代码，不附加可能包含 Redis 地址或连接细节的原始异常。

## 验证矩阵

| 场景 | 证据 | 结果 |
|---|---|---|
| 状态保存与重启恢复 | 新 Jedis Client、Store 和 Owner 读取相同稳定 Slot | 通过 |
| 显式状态清理 | 删除完整 Session 后 `exists/listSessionIds` 均为空 | 通过 |
| 同 Session FIFO 与公平性 | 三个 Turn 按订阅顺序进入 Model | 通过 |
| 跨 Session 并行 | 两个 Slot 在任一响应完成前同时进入 Model | 通过 |
| 取消/异常清理 | 当前 Turn 取消或失败后下一 Turn 立即进入 | 通过 |
| 重复 Invocation | 第二次相同 InvocationId 在预检和 Model 前拒绝 | 通过 |
| Invoke/Resume 预检 | 两段分别预检，失败后 Model 调用次数不增加 | 通过 |
| 状态损坏与错配 | 损坏 JSON、缺值和内部身份错配均失败关闭 | 通过 |
| Redis 不可用 | 连接关闭后预检返回安全状态失败 | 通过 |
| 双实例启动 | 第二个 Spring Context 因现有 Owner 拒绝启动 | 通过 |
| 正常释放与崩溃过期 | compare-and-delete 释放；过期旧租约允许新 Owner | 通过 |
| Spring 装配 | Store、Preflight、Lifecycle 和 Owner 均为单 Bean | 通过 |

## 专项验证

```text
AgentScopeNativeRuntimeIntegrationTest           14 tests passed（其中 4 个 M2-I05 用例）
HarnessAgentM2S02ConcurrencyIntegrationTest       5 tests passed
RedisAgentStateM2S02IntegrationTest               3 tests passed
RedisAgentStateM2I05IntegrationTest               9 tests passed
RedisAgentStateConfigurationIntegrationTest       2 tests passed
CredentialActuatorConfigurationTest               1 test passed
```

## 全仓验证

```text
mvn clean verify                  通过（585 tests，0 failures，0 errors，0 skipped）
node scripts/check-doc-links.mjs  通过（83 个 Markdown 文件）
git diff --check                  通过
```

各模块测试数：

```text
crewscope-domain          198
crewscope-application     131
crewscope-agentscope       57
crewscope-integration       0
crewscope-infrastructure  133
crewscope-server           66
合计                      585
```
