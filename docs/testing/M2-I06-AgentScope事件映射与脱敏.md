# M2-I06：AgentScope 事件映射与脱敏

> 日期：2026-08-09<br>
> 状态：已完成<br>
> 模块：`crewscope-application`、`crewscope-agentscope`、`crewscope-server`

## 目标

把 AgentScope Java 2.0.0 事件映射为 CrewScope 安全的 `ExecutionEvent`、AG-UI 瞬时实时信封、Agent Message Candidate 和 TaskIntent Candidate；固化顺序、重复、终态、DomainEvent 关联、版本兼容与敏感内容披露策略。

## 映射链路

```text
AgentScope AgentEvent
  -> AgentScopeEventMapper 原始事件白名单
  -> ExecutionEvent
  -> ConversationExecutionEventMapper.Session
     -> Invocation / Sequence / Terminal 校验
     -> Exact Replay 去重
     -> RealtimeEventEnvelope<AguiTransientPayload>
     -> COMPLETED 后生成 AgentMessageCandidate / TaskIntentOutputCandidate

Committed Message / TaskIntent DomainEvent
  -> RealtimeDomainEventProjector
  -> Conversation / Team RealtimeEventEnvelope
```

AG-UI 瞬时信封不声明 DomainEvent 或 Aggregate。持久业务事件投影保留源 DomainEvent ID、Aggregate Version、Correlation ID 和 Causation ID。Candidate 不是已提交事实，后续 M2-A03/A05 在事务内分配聚合版本、消息序号和 DomainEvent。

## 顺序与重复

- 每个 Invoke/Resume Segment 使用独立非 Nil `segmentId`；
- 第一项必须为 `STARTED`，序号从 1 连续增长，只接受一个终态；
- 完全相同的历史序号重放返回 `duplicate=true`，不重复瞬时事件或落库 Candidate；
- 相同序号不同内容、序号缺口、错误 Invocation 和终态后新事件失败关闭；
- Event ID 由 `segmentId + sequence` 稳定生成；
- Structured Output 在终态前只保留于映射 Session，不发送到 AG-UI。

## 披露策略

| 输入 | 处理 |
|---|---|
| 顶层公开 `TextBlockDeltaEvent` | 映射为文本增量 |
| Require Confirm / External Execution / Stop / Result | 仅供 Adapter 生成安全中断或终态 |
| Thinking/Reasoning | 丢弃 |
| Tool 参数 Delta、Tool Result Text/Data | 丢弃 |
| Data、Metadata、State、Raw、Custom、Snapshot、Chunk | 丢弃 |
| 子 Agent 转发事件 | M2 丢弃 |
| Tool 名称与生命周期 | 官方 AG-UI 出站允许，ID 不透明化 |
| Tool Result | 保留完成信号，移除 Content 和原 Message ID |
| Provider/Runtime Error | 替换为安全消息和稳定代码 |
| 取消原因 | 不进入 AG-UI Payload |

`ControlledAguiBridge` 同时关闭 State、Tool Args 与 Reasoning，最终仍经过 `AguiEventSanitizer`，不依赖第三方 Adapter 配置作为唯一安全边界。

## Candidate 边界

公开文本累计长度遵循 `MessageContent.MAX_LENGTH`，只在 `COMPLETED` 生成带可信 Agent Principal 和 Participant 的 `AgentMessageCandidate`。`task-intent/v1` 必须精确携带 `TaskIntentV1` 并通过 Jakarta Bean Validation，只在 `COMPLETED` 生成 `TaskIntentOutputCandidate`。模型校验错误使用固定安全消息，不回显模型字段值。未知 Structured Output Schema 被安全忽略。

## Fixture 验证

```text
ConversationExecutionEventMapperM2I06Test         9 tests passed
AgentScopeEventMapperM2I06Test                     2 tests passed
ControlledAguiBridgeM2S01IntegrationTest           9 tests passed
ExecutionRuntimeContractTest                      10 tests passed
AgentScopeNativeRuntimeIntegrationTest            14 tests passed
ApplicationCompositionConfigurationTest            1 test passed
专项合计                                           45 tests passed
```

覆盖公开文本、Message/TaskIntent Candidate、DomainEvent 关联、未知事件、未知 Schema、乱序、冲突重放、精确重复、Bean Validation、全部终态、安全错误、JSON 新增字段兼容、Reasoning/Tool/State 脱敏和 Spring 单 Bean 装配。

## 全仓验证

```text
mvn clean verify                  通过（597 tests，0 failures，0 errors，0 skipped）
node scripts/check-doc-links.mjs  通过（85 个 Markdown 文件）
git diff --check                  通过
```

各模块测试数：

```text
crewscope-domain          198
crewscope-application     140
crewscope-agentscope       60
crewscope-integration       0
crewscope-infrastructure  133
crewscope-server           66
合计                      597
```
