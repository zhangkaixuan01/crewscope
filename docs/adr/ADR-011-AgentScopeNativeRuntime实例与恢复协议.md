# ADR-011：AgentScopeNativeRuntime 实例与恢复协议

> 状态：ACCEPTED<br>
> 日期：2026-08-09<br>
> 影响里程碑：M2–M4<br>
> 关联决策：[ADR-009](ADR-009-会话执行所有权与恢复协议.md)、[ADR-010](ADR-010-ExecutionRuntime调用与流协议.md)

## 背景

M2-I02 已建立 Conversation `ExecutionRuntime` Port。M2-I03 需要把该 Port 接到 AgentScope Java 2.0.0 `HarnessAgent`，同时保持 AgentProfile 配置版本、Conversation 状态隔离、Structured Output、中断恢复、显式取消和 Web 传输断线之间的稳定边界。

AgentScope 的一个 HarnessAgent 可以通过 `RuntimeContext.userId/sessionId` 服务多个状态槽。`streamEvents` 提供文本与生命周期事件，`call(..., Class<?>)` 提供 Structured Output，Permission ASK 通过 Pending Tool、`RequireUserConfirmEvent` 与再次 `call` 恢复，精确取消通过 `ReActAgent.interrupt(RuntimeContext)` 传播。

## 决策

### 配置解析与 Agent 实例

`PersonalAgentFactory` 使用以下固化键解析配置并缓存 HarnessAgent：

```text
AgentProfileId + AgentProfileVersion
```

配置包含主模型 ID、可选备用模型 ID、System Prompt、最大 ReAct 轮数和模型重试次数。`PersonalAgentConfigurationSource` 必须返回与 AgentRuntimeSession 固化 ID、版本完全一致的配置；`AgentScopeModelResolver` 把模型 ID 解析为 AgentScope `Model`。配置缺失、版本错配或模型不可用时调用以安全 `FAILED` 终态结束。

同一 Profile 版本跨 Conversation 复用一个 HarnessAgent。Conversation 状态继续由持久化 `AgentRuntimeSession.agentScopeKey` 的 `userId/sessionId` 隔离。Profile 版本推进后创建新的 Agent 实例，既有 Session 在显式刷新配置前继续使用固化版本。Factory 关闭时统一关闭缓存实例。

M2 Factory 注入共享 Redis `AgentStateStore`、每实例新建的 `Toolkit` 和 [ADR-012](ADR-012-PlatformExecutionContext与AgentScope安全中间件.md) 定义的有序安全 Middleware。`AgentScopeNativeRuntime` 在 Invoke 和 Resume 前通过 [ADR-009](ADR-009-会话执行所有权与恢复协议.md) 的状态预检验证 Redis 与单活动实例所有权。M2-I03 使用 AgentScope Permission Pending Tool 原生恢复链路，并关闭文件、Shell、Subagent、Memory、动态 Skill、Workspace Context 与客户端 Tools 配置。

### Conversation 调用

普通文本调用使用：

```java
agent.streamEvents(userMessage, runtimeContext)
```

`TextBlockDeltaEvent` 映射为 `TEXT_DELTA`，`AgentResultEvent` 依据 `GenerateReason` 映射唯一终态。Structured Output 调用使用：

```java
agent.call(userMessage, structuredOutputClass, runtimeContext)
```

返回值先通过 `Msg.getStructuredData` 转换，再生成 `STRUCTURED_OUTPUT` 与 `COMPLETED`。转换失败生成 `MODEL_OUTPUT_INVALID`，模型、状态和内部异常映射为固定安全分类与 Runtime Code，异常对象和 Provider 原始消息保留在 Adapter 内部。

每段 Invoke/Resume 从序号 1 的 `STARTED` 开始。Runtime 内部订阅 AgentScope Reactor 流，并把映射后的事件写入单订阅缓冲 Publisher。Runtime 自身维护唯一终态：AgentScope 流缺少 Result/终态时补充安全 `FAILED`，终态后的多余事件或异常不再产生第二个终态。下游 demand 控制事件发送；下游 Subscription Cancel 只断开该缓冲流的传输订阅，AgentScope 调用和 Runtime 状态推进继续运行。

### Interrupt 与 Resume

Permission ASK、外部执行请求和 Middleware Stop 生成随机不透明 `ExecutionInterruptToken`。Token 只作为服务端引用，Pending Tool、replyId、Session Key 和原始 AgentScope 状态保存在 Runtime 侧，不进入客户端或日志。

恢复按以下事实匹配：

```text
InvocationId
AgentRuntimeSessionId + AgentScopeSessionKey
ExecutionInterruptToken
ResumeRequestId
```

Permission ASK 恢复时，Adapter 从服务端 Pending Tool 复制原始受控输入，把已提交 USER 回答 Message 绑定到 `answer` 字段，构造 `ConfirmResult` 后再次调用同一 HarnessAgent 和刷新后的 RuntimeContext。Structured Output 请求在恢复段继续使用初始 Schema 与 Java 类型。

Resume 使用“只读校验与快照、本地消息和 RuntimeContext 准备、同步提交消费”三步协议。只有本地准备成功且提交时 Invocation 仍处于同一个 Pending Interrupt，Runtime 才写入 `ResumeRequestId` 并切换为 `RUNNING`；并发 Resume 或 Cancel 会在提交点重新校验。提交后的同步建流失败转换为当前 Resume 段的安全 `FAILED` 终态，不留下无法再次处理的半消费 Pending 状态。

一个 Pending Interrupt 只接受一次 Resume；同一 Invocation 可以依次产生并恢复多个不同 Interrupt。持久幂等、过期和冲突重放由 M2-A03/M2-A05 的业务事实完成。

### Cancel 与 Invocation Registry

Runtime 使用进程内 Invocation Registry 保存 M2 活动调用、Pending Interrupt 和短期终态。显式 Cancel 精确匹配 Invocation 与 AgentRuntimeSession：

- 活动或中断等待中的调用返回 `ACCEPTED`；
- 已完成、已取消或已失败调用返回 `ALREADY_TERMINAL`；
- 不存在或 Session 不匹配返回 `NOT_FOUND`。

活动调用通过同一 RuntimeContext 调用 AgentScope 精确 Interrupt，并映射 `CANCELED` 终态。中断等待中的调用直接关闭逻辑 Invocation。Registry 只承担 M2 进程内协调，按容量淘汰最早的逻辑终态；Conversation、Message、TaskIntent、AgentRuntimeSession 和后续 Interrupt 记录继续承担耐久事实。

## 结果

- Personal Agent 的模型和 Prompt 按 AgentProfile 固化版本解析；
- 一个 Agent 实例可安全服务多个 Conversation 状态槽；
- 普通流式文本、Structured Output、Interrupt、Resume、Cancel 和安全失败进入同一 ExecutionRuntime 协议；
- Web 断线与业务取消保持独立语义；
- M2 保持 Conversation Invocation 边界，不创建 TaskExecution、ExecutionLease 或 AgentRun。

## 验证

1. 同一 Profile 版本复用 Agent，不同版本创建独立 Agent；
2. 两个 Conversation 使用稳定 Session Key 保持上下文隔离；
3. 多轮普通对话产生增量文本与唯一完成终态；
4. Structured Output 转换为精确 Schema/Java 类型事件；
5. Permission ASK 产生不透明 Token，Resume 使用同一 Invocation 和 Pending Tool；
6. 重复、错 Token、错 Session 和并发冲突 Resume 在 AgentScope 前失败，本地准备失败不消费 Pending Interrupt；
7. 模型错误、输出转换错误和缺失上游终态映射安全失败，终态后信号不会生成第二个终态；
8. Subscription Cancel 不调用业务 Cancel，显式 Cancel 使用精确 RuntimeContext；
9. Runtime 与 Factory 关闭后拒绝新调用并释放缓存实例。

实现与验证结果见 [M2-I03 AgentScopeNativeRuntime](../testing/M2-I03-AgentScopeNativeRuntime.md)。

## 重新评估条件

- M2-A03/M2-A05 建立耐久 Interrupt、Resume 幂等和过期事实；
- M3 引入 TaskExecution、ExecutionLease、Task Token 和耐久 AgentRun；
- Runtime 独立部署为 Worker 或需要跨进程事件流；
- AgentProfile 模型、Prompt 与 Tool 配置进入独立版本表或 PolicySnapshot。
