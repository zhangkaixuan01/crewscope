# ADR-013：AgentScope 事件映射与披露协议

> 状态：ACCEPTED<br>
> 日期：2026-08-09<br>
> 影响里程碑：M2–M6<br>
> 关联决策：[ADR-005](ADR-005-事件与投影协议.md)、[ADR-010](ADR-010-ExecutionRuntime调用与流协议.md)、[ADR-012](ADR-012-PlatformExecutionContext与AgentScope安全中间件.md)

## 背景

AgentScope Java 2.0.0 的细粒度事件包含公开文本、Thinking、Tool 参数、Tool Result、Data、State、Custom、模型生命周期和控制信号。CrewScope 还需要把有限 `ExecutionEvent` 流转换为 AG-UI 瞬时事件、可提交的 Agent Message、TaskIntent 模型输出和统一实时事件信封。直接透传框架事件会把推理、Provider 原始结果、Tool 参数或内部状态带到 Web 协议，并使重复与乱序事件产生重复业务副作用。

## 决策

### 三层映射边界

```text
AgentScope AgentEvent
  -> AgentScopeEventMapper
     -> 只允许顶层 TextBlockDelta 和内部控制/终态信号
     -> 忽略 Thinking、Tool 参数/结果、Data、Metadata、Custom 和子 Agent 内容
  -> ExecutionEvent
  -> ConversationExecutionEventMapper.Session
     -> 验证 Invocation、首事件、连续序号和唯一终态
     -> 精确重复只返回 duplicate，不重复下游副作用
     -> 缺口、冲突重放和终态后事件失败关闭
  -> RealtimeEventEnvelope<AguiTransientPayload>
  -> AgentMessageCandidate / TaskIntentOutputCandidate
```

`ExecutionEventMappingContext` 使用服务端 `PlatformExecutionContext`、一次有限调用段的非 Nil `segmentId` 和可选触发 DomainEvent ID。`threadId` 固定为 Conversation ID，`runId` 固定为 RuntimeInvocationId，AG-UI Message ID 固定为 Segment ID。瞬时 Event ID 由 `segmentId + sequence` 稳定生成，使同一调用段重建时保持一致去重身份。

### 瞬时事件与业务事实

M2 对外瞬时事件为：

```text
RUN_STARTED
TEXT_MESSAGE_CONTENT
RUN_INTERRUPTED
RUN_FINISHED
RUN_ERROR
```

瞬时事件使用 `StreamType.AG_UI`，不携带 `domainEventId`、Aggregate 或 Aggregate Version；保留 Correlation ID，并可用 Causation ID 关联触发调用的已提交用户消息事件。取消原因不进入实时 Payload。失败只携带 `ExecutionFailure` 已脱敏的安全消息、稳定 Runtime Code 和可重试标记。

`RUN_INTERRUPTED` 的 Clarification 分支携带经过约束归一化的公开 `ClarificationRequestV1`。AgentScope Adapter 只提取 SchemaVersion、Summary、FieldKey、Question、Context、Required 和 Choices，并验证文本边界、唯一 FieldKey 与问题数量。非 Clarification 中断不携带该对象。原始 Tool Input、ToolCallId、ReplyId、ConfirmResult、Permission、Session 和 Tool Result 不进入瞬时 Payload。

公开文本只在 `COMPLETED` 后生成 `AgentMessageCandidate`。Candidate 包含服务端 Agent Principal、Agent Participant、Conversation、内容和时间，但不分配 Message ID、Message Sequence，不表示已提交事实。`task-intent/v1` 经过 Jakarta Bean Validation 后只在 `COMPLETED` 生成 `TaskIntentOutputCandidate`，随后仍必须通过当前 PostgreSQL 事实和 Domain Validation。未知 Structured Output Schema 不进入实时协议或业务 Candidate。

Application 映射器不写 PostgreSQL、不创建 DomainEvent。后续 Conversation 事务提交 Message 或 TaskIntent 后，`RealtimeDomainEventProjector` 使用 `RealtimeEventEnvelope.fromDomain` 投影到 Conversation/Team 流，并保持原 `domainEventId`、Aggregate Version、Correlation 和 Causation。持久 DomainEvent 禁止伪装为 AG-UI 瞬时事件。

### AG-UI 出站披露

`ControlledAguiBridge` 固定：

```text
ToolMergeMode.AGENT_ONLY
emitStateEvents(false)
emitToolCallArgs(false)
enableReasoning(false)
```

官方 Adapter 的最终事件仍通过 `AguiEventSanitizer` 白名单。允许服务端构造的 Run Start、公开文本、Tool 名称与生命周期、Run Finish 和安全 Run Error。Tool Call ID 与 Message ID 转换为调用内稳定的不透明 ID；Tool Result Content、Message ID 和原始 Run Result 被移除。Tool Args、State、Raw、Custom、Snapshot、Chunk、Activity、Step 和全部 Reasoning 事件被丢弃。

## 结果

- Web 只接收公开回答、受控中断和安全运行状态；
- Thinking、Tool 参数/结果、Provider 原始错误、State、Custom 与子 Agent 事件不会跨越 M2 披露边界；
- Exact Replay 不重复 SSE 或数据库写入候选；
- Message 与 TaskIntent 只有在运行段成功完成后进入后续事务边界；
- AG-UI 瞬时事件和持久 DomainEvent 投影使用同一实时信封但保持不同事实语义。

## 验证

实现与 Fixture 结果见 [M2-I06 AgentScope 事件映射与脱敏](../testing/M2-I06-AgentScope事件映射与脱敏.md)。

## 重新评估条件

- M2-A03 接入正式 Invocation SSE 和 Message 提交事务；
- M2-A04 增加历史 Cursor 补发与跨流合并；
- M3/M4 需要公开经过 Policy 处理的步骤、Tool 或 Artifact 摘要；
- AgentScope 或 AG-UI 协议升级引入新的安全事件类型。
