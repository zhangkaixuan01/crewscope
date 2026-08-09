# ADR-014：Agent 模型调用可观测与安全重试协议

> 状态：ACCEPTED<br>
> 日期：2026-08-09<br>
> 影响里程碑：M2–M6<br>
> 关联决策：[ADR-008](ADR-008-可观测性与日志安全协议.md)、[ADR-011](ADR-011-AgentScopeNativeRuntime实例与恢复协议.md)、[ADR-012](ADR-012-PlatformExecutionContext与AgentScope安全中间件.md)、[ADR-013](ADR-013-AgentScope事件映射与披露协议.md)

## 背景

AgentScope Java 2.0.0 的 `ModelCallStartEvent`、`ModelCallEndEvent` 和 `ChatUsage` 提供逻辑模型调用与 Token Usage。具体 Model 在 `ModelUtils` 内部完成重试，`ReActAgent` 在主模型重试耗尽后切换 Fallback。Middleware 可以观察逻辑调用和最终 Usage，无法直接获得每次真实重试。AgentScope 的重试、Fallback、摘要等内部日志会记录原始异常，Provider 响应可能包含凭证、响应体或业务内容。

CrewScope M2 需要记录模型、Usage、延迟、错误、重试、Fallback 和 Conversation/Session/Trace 关联，同时保持低基数、安全日志和 M3 事实边界。

## 决策

### 两层观测

```text
PlatformAuditMiddleware
  -> 创建调用级 AgentCallObservationScope
  -> 记录 STARTED / COMPLETED / FAILED / CANCELED
  -> 读取 ModelCallEndEvent.ChatUsage
  -> 关联 Conversation / RuntimeSession / Invocation / Correlation / Trace

ObservableAgentScopeModel
  -> 包装 PRIMARY / FALLBACK Model
  -> 记录真实 RETRYING 与 FALLBACK_SELECTED
  -> 保留 AgentScope ExecutionConfig 的尝试次数、退避、超时与重试谓词
  -> 终态异常转换为 SafeModelExecutionException
```

Model 装饰器只在 `AgentCallObservationScope` 存在时接管重试。装饰器把下层 Model 的单次请求 `maxAttempts` 固定为 1，再按原有效 `ExecutionConfig` 在外层执行相同的有限重试，使每次真实 attempt 可观测并避免下层重复重试。缺少平台观测 Scope 的测试或独立调用继续使用 AgentScope Model 原有行为。

主模型的安全终态异常进入 AgentScope Fallback 切换。Fallback Model 使用相同装饰器和自身有限重试。原始 Provider 异常先映射为稳定代码，再转换为无 Cause、无原始消息、无响应体的 `SafeModelExecutionException`，因此 AgentScope 内部 Logger 只能接触安全代码。Runtime 继续按该稳定代码生成 `MODEL_RATE_LIMITED`、`TIMEOUT`、`CAPABILITY_UNAVAILABLE` 或 `MODEL_UNAVAILABLE` 等安全终态，脱敏不能损失原始失败分类。

M2 Personal Agent 关闭 Compaction。M2 的 Conversation 和 Redis AgentState 已提供对话恢复，Compaction 在 Workspace Memory、摘要披露、原始 Session Log 和保留策略落地后显式启用。

### 观测记录

`AgentCallObservationRecord` 只包含：

- Organization、Team、Workspace、Conversation、RuntimeSession、Invocation 和 Correlation 标识；
- 可选 Trace ID 与 Span ID；
- 安全 Model 名称和 `LOGICAL/PRIMARY/FALLBACK` 角色；
- 生命周期事件、Attempt、最大尝试次数、Retry 次数和 Fallback 状态；
- Input、Output、Cached、Total Token 和延迟；
- 稳定错误码。

记录排除 Prompt、Reasoning、Message 内容、Tool 参数与结果、Credential、ProviderBinding/Connection 标识、Provider 原始异常消息与响应体。记录属于日志和指标遥测，不持久化为 DomainEvent、AuditEvent、AgentRun 或其他业务事实。M3 `AgentRun` 由耐久执行事务单独建立。

基础 `AgentExecutionAuditSink` 继续执行失败关闭。`AgentCallObservationSink` 执行尽力而为，日志或指标故障不改变模型调用结果。

### 指标基数

M2 暴露：

```text
crewscope.agent.model.calls
crewscope.agent.model.tokens
crewscope.agent.model.retries
crewscope.agent.model.fallbacks
crewscope.agent.model.errors
```

指标标签只使用受控的 outcome、fallback、role、code 和 token type。Organization、Team、Workspace、Conversation、Session、Invocation、Correlation、Trace、Span、ToolCall、ProviderBinding、Connection、Model 名称和原始错误不进入标签。

## 结果

- 一条模型调用可由 Correlation ID 定位完整生命周期，并与 Conversation、Session、Invocation 和 Trace 对齐；
- Usage、延迟、真实 Retry 和 Fallback 具有统一类型化记录；
- Provider 原始异常不会进入 CrewScope 观测记录或 AgentScope 后续内部日志；
- 脱敏异常的稳定错误码贯穿观测、Fallback 与 Runtime 终态，限流、超时、认证和 Provider 不可用不会退化为同一种错误；
- Prometheus 时间序列仅由有限平台枚举扩展；
- M2 遥测不提前建立 M3 AgentRun 事实。

## 验证

实现与测试结果见 [M2-I07 Agent 调用可观测性](../testing/M2-I07-Agent调用可观测性.md)。

## 重新评估条件

- AgentScope 提供公开的 Model attempt/retry/fallback 事件或拦截接口；
- M3 AgentRun 需要从实时遥测写入耐久运行事实；
- M4 启用 Compaction、Tool Result Eviction、Memory、Subagent 或 Coding Agent；
- 模型 Provider 需要独立区域、成本或配额标签预算；
- OTLP Collector 和 AgentScope `OtelTracingMiddleware` 正式启用。
