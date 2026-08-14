# ADR-010：ExecutionRuntime 调用与流协议

> 状态：ACCEPTED<br>
> 日期：2026-08-09<br>
> 更新：2026-08-14（M3-D07 固化耐久 AgentRun、Segment 与 Resume 事实）<br>
> 影响里程碑：M2–M6<br>
> 关联决策：[ADR-009 会话执行所有权与恢复协议](ADR-009-会话执行所有权与恢复协议.md)

## 背景

M0 的 `AgentRuntime` 只提供运行时名称、版本和字符串能力描述。M2 需要通过稳定 Application Port 调用 AgentScope Personal Agent，传输流式文本和结构化输出，表达澄清中断、恢复、取消与可分类失败。M2 的耐久业务事实是 Conversation、Message、TaskIntent 和 AgentRuntimeSession，执行目标限定为 Conversation Invocation。M3 在相同 Port 族上扩展 TaskExecution、Lease、Workspace 和耐久 AgentRun。

AgentScope Java 2.0.0 提供 `HarnessAgent.call`、`streamEvents`、Structured Output Class/Schema、`RequireUserConfirmEvent`、`RequestStopEvent`、第二次 `call` 恢复和按精确 `RuntimeContext` 的 `interrupt`。Reactor 负责框架内背压与取消，CrewScope Application Port 使用 JDK `Flow.Publisher` 和 `CompletionStage` 保持框架无关。

## 决策

### Port 形态

```java
public interface ExecutionRuntime {
    RuntimeDescriptor descriptor();
    RuntimeCapabilities capabilities();
    ExecutionHandle invokeConversation(ConversationExecutionRequest request);
    ExecutionHandle resumeConversation(ConversationResumeRequest request);
    CompletionStage<ExecutionCancelResult> cancel(ConversationCancelRequest request);
}
```

初始调用携带服务端生成的 Invocation ID、ACTIVE AgentRuntimeSession、已提交 USER Message、可选 Structured Output 类型、Correlation ID 和 `PlatformExecutionContext`。请求构造时闭合 Organization、Team、Workspace、Conversation、Session Owner、消息类型、作者与当前授权快照。运行时只消费这些可信事实；上下文重建与注入遵循 [ADR-012](ADR-012-PlatformExecutionContext与AgentScope安全中间件.md)。

恢复请求携带相同 Invocation ID、相同 AgentRuntimeSession、服务端保存的 Interrupt Token、Resume Request ID 和已提交 USER 回答 Message。恢复继续同一个逻辑 Invocation，并返回新的有限事件流。

取消请求携带相同 Invocation ID、AgentRuntimeSession 和安全原因。`cancel` 返回 `ACCEPTED`、`ALREADY_TERMINAL` 或 `NOT_FOUND`，适配器使用精确 `userId/sessionId` 传播 AgentScope Interrupt。

### 有限事件流

每个 `ExecutionHandle` 暴露一个 JDK `Flow.Publisher<ExecutionEvent>`。事件序号从 1 开始并严格递增，第一项为 `STARTED`，流以一个终态事件结束并随后调用 `onComplete`：

```text
COMPLETED
INTERRUPTED
CANCELED
FAILED
```

运行阶段事件包括 `TEXT_DELTA` 和 `STRUCTURED_OUTPUT`。Structured Output 事件携带版本化 Schema ID、Java 类型和值，并在构造时执行类型闭合。业务、模型、工具、状态存储和超时失败映射为 `FAILED` 终态；Publisher/Adapter 协议损坏使用 `onError`。

`ExecutionStreamValidator` 在 Runtime Port 消费边界验证 Invocation、序号、首事件、唯一终态和终态后无事件。I06 的状态化映射 Session 在相同不变式上增加精确重放吸收：完全相同的历史事件不重复产生副作用，相同序号不同内容仍失败关闭。映射与披露遵循 [ADR-013](ADR-013-AgentScope事件映射与披露协议.md)。

### 背压、断线与取消

- Publisher 只按 Subscriber demand 发送事件；非法 demand 使用 Reactive Streams 规则失败；
- `Flow.Subscription.cancel()` 只停止当前订阅和下游传输，用于 Web 断线与消费者退出；
- 业务取消通过 `ExecutionRuntime.cancel` 发起，并向仍连接的事件流发送 `CANCELED` 终态；
- 订阅断开后的运行状态和补发由 AgentRuntimeSession、Redis State、Message 与后续 Conversation Event Cursor 协同恢复；
- 每个 Handle 支持一个订阅者，避免同一底层 AgentScope 流被重复执行。

### 能力描述

`RuntimeCapabilities` 使用稳定枚举，供 Application 路由和 PolicySnapshot 固化。M2 AgentScope Runtime 声明：

```text
CONVERSATION
STREAMING
STRUCTURED_OUTPUT
INTERRUPT_RESUME
CANCEL
SESSION_STATE
```

Plan、Sandbox、Worktree、Subagent、Memory、External Tool 与 Distributed State 在相应生产边界完成后逐项加入可用能力。运行时描述固定实现 ID、显示名和版本，AgentScope Profile 固定为 `agentscope-java-native / AgentScope Java / 2.0.0`。

M3 将 `RuntimeCapabilities` 下沉到 Domain，增加语言与构建系统维度，M2 调用 Port、M3 Registry/Worker 和后续 Scheduler/Policy 共用同一能力词汇。`io.crewscope.application.execution.ExecutionRuntime` 仍是调用型 Application Port；`io.crewscope.domain.runtime.ExecutionRuntime` 是 Organization 和环境隔离的可持久 Registry 事实。两者通过稳定 runtime key、实现版本和能力快照关联，保持调用协议与部署注册生命周期独立。

### M3 耐久运行事实

M2 Conversation Invocation 继续使用进程内 Registry 和 Conversation Session。M3 Task Runtime 增加独立的耐久事实：

- Task-side AgentRuntimeSession 使用 `TASK/STEP/SPECIALIST` 目的闭合 Task、TaskExecution、可选 StepExecution、Agent Principal 和版本化 AgentProfile；
- AgentRun 表示跨初始调用与多次 Resume 的逻辑运行，每个有限 `ExecutionHandle` 映射为一个连续编号的 Segment；
- 初始调用使用 `INVOKE` Segment，Resume 使用携带原 Interrupt ID 的 `RESUME` Segment，无法精确续接的新 Run 使用携带 continuity gap 的 `RECOVERY` Segment；
- AgentInterrupt 保存 Pending 状态、Interrupt Token Hash 和幂等 Resume Receipt，不保存 Token 明文或回答正文；
- AgentRun 终态与最后 Segment 同时提交，完成、失败和取消终态不可再次修改；
- 大结果和 AgentState 通过 RuntimeArtifact 引用，PostgreSQL 运行事实不保存大正文。

同一 AgentRun 只有一个 Pending AgentInterrupt。Resume Request ID 全局唯一；相同 Request ID 和相同规范回答 Hash 返回已提交 Resolution，相同 Request ID 对应不同 Hash 失败关闭。D07 只固定领域与 Repository Port，Task Execution Request、Handle 和耐久事件映射在 M3-I05/I07 接入。

## 结果

- Conversation 调用具备框架无关、类型化和可测试的执行 Port；
- 流断开与业务取消使用独立语义；
- Structured Output、中断、恢复和错误分类进入稳定平台协议；
- M2 调用不依赖 TaskExecution、ExecutionLease 或耐久 AgentRun；
- M3 可以在相同 ExecutionRuntime 族上增加 TaskExecution 请求和耐久执行控制。

## 验证

1. 请求拒绝跨 Scope、跨 Conversation、非 USER、错误作者和非 ACTIVE Session；
2. Structured Output 类型与 Schema 描述精确匹配；
3. Publisher 按 demand 发送事件并正确处理订阅取消；
4. 完成、中断、取消和失败各产生唯一终态；
5. Resume 继续同一 Invocation 并使用精确 Interrupt Token；
6. Cancel 返回稳定幂等结果并传播取消终态；
7. 错误事件保存安全分类、可重试性和运行时错误码；
8. AgentScope Profile 只声明 M2 已接通能力。

实现与验证结果见 [M2-I02 ExecutionRuntime Port](../testing/M2-I02-ExecutionRuntime-Port.md)和 [M2-I06 AgentScope 事件映射与脱敏](../testing/M2-I06-AgentScope事件映射与脱敏.md)。

## 重新评估条件

- M3 引入 TaskExecution、ExecutionLease 与耐久 AgentRun；
- Conversation Invocation 需要多订阅者实时扇出；
- 运行时迁移到独立 Worker 或跨进程双向流；
- Resume 需要一个 Invocation 内并行存在多个 Pending Interrupt；
- JDK Flow 与 Reactor Bridge 需要独立的性能或兼容层。
