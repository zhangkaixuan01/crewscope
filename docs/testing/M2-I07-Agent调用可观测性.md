# M2-I07：Agent 调用可观测性

> 日期：2026-08-09<br>
> 状态：已完成<br>
> 模块：`crewscope-agentscope`、`crewscope-server`

## 目标

记录 AgentScope Java 2.0.0 模型调用的 Model、Token Usage、Latency、Error、Retry、Fallback 和 Conversation/Session/Trace 关联；提供低基数指标和安全日志；保持 M3 AgentRun 事实边界。

## 实现链路

```text
PlatformAuditMiddleware
  -> AgentCallObservationScope
  -> STARTED / COMPLETED / FAILED / CANCELED
  -> ModelCallEndEvent.ChatUsage

PersonalAgentFactory
  -> ObservableAgentScopeModel(PRIMARY/FALLBACK)
  -> 真实 Retry / Fallback
  -> SafeModelExecutionException

StructuredLoggingAgentCallObservationSink
  -> correlationId 可检索结构化日志
  -> AgentCallObservabilityMetrics
```

Middleware 负责逻辑调用、Usage、终态和可信执行关联。Model 装饰器把内部请求限制为单次 attempt，并按 AgentScope 有效 `ExecutionConfig` 在可观测层执行同一有限重试。主模型耗尽后仍由 AgentScope `ReActAgent` 选择 Fallback。

## 安全边界

- Provider 原始异常先分类为稳定错误码；
- `SafeModelExecutionException` 不保存 Cause、原始消息或响应体；
- Runtime 使用 `SafeModelExecutionException` 的稳定代码恢复限流、超时、认证、请求拒绝和 Provider 不可用分类；
- 日志字段固定且经过控制字符与长度清理；
- Prompt、Reasoning、Tool 参数/结果、Credential 和 Provider 原始错误不进入记录；
- M2 关闭 Compaction，后续按 Workspace Memory、摘要披露和保留策略显式启用；
- 基础执行 Audit 失败关闭，观测 Sink 尽力而为；
- 观测记录不生成 DomainEvent、AuditEvent 或 AgentRun。

## 指标

```text
crewscope.agent.model.calls      tags: outcome, fallback
crewscope.agent.model.tokens     tags: type
crewscope.agent.model.retries    tags: role
crewscope.agent.model.fallbacks  tags: none
crewscope.agent.model.errors     tags: code, role
```

所有标签来自固定枚举或稳定错误码。租户、Conversation、Session、Invocation、Correlation、Trace、Span、Model、Binding、Connection 和原始错误不进入指标标签。

## 专项验证

```text
AgentCallObservabilityM2I07Test (agentscope)  4 tests passed
AgentCallObservabilityM2I07Test (server)      2 tests passed
ApplicationCompositionConfigurationTest      1 test passed
专项合计                                      7 tests passed
```

覆盖成功 Usage、Conversation/Session/Invocation/Correlation/Trace 关联、流中断、真实 Retry、Fallback、取消、Provider 异常脱敏、安全码到 Runtime 终态的无损分类、观测 Sink 故障隔离、结构化日志字段和指标标签基数。

## 全仓验证

```text
mvn clean verify                  通过（605 tests，0 failures，0 errors，0 skipped）
node scripts/check-doc-links.mjs  通过（87 个 Markdown 文件）
git diff --check                  通过
```

各模块测试数：

```text
crewscope-domain          198
crewscope-application     140
crewscope-agentscope       66
crewscope-integration       0
crewscope-infrastructure  133
crewscope-server           68
合计                      605
```
