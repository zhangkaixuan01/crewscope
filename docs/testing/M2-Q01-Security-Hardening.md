# M2-Q01 安全硬化验证记录

## 1. 结论

M2 对话式 Personal Agent 已建立输入控制面、可信执行上下文、当前授权事实、公开事件映射、日志脱敏和资源预算六层边界。用户自然语言可以进入模型，不能改变 Principal、Role、Session、ProviderBinding、Toolkit、RuntimeContext 和披露策略。

Prompt 注入采用信任分区和服务端授权，不使用关键词拦截。客户端 Tool 与 Runtime 控制字段在 HTTP DTO 或受控 AG-UI DTO 边界失败。成员、参与者、角色、会话和 ProviderBinding 由服务端按当前持久化事实解析，运行中撤权在下一次模型、恢复或 Tool 边界生效。

## 2. 安全矩阵

| 攻击面 | 攻击入口 | 服务端边界 | 自动化证据 | 公开结果 |
|---|---|---|---|---|
| Prompt 注入 | USER Message、Clarification Answer | 文本只作为 User Content；Context、Toolkit 和身份由服务端生成 | `ControlledAguiBridgeM2S01IntegrationTest.promptInjectionRemainsUserContentAndCannotExpandTrustedControls` | 保留合法用户文本，控制面不变 |
| 客户端 Tool/Runtime 注入 | Invocation JSON、AG-UI Input | 安全 DTO 拒绝未知字段；Bridge 使用 `AGENT_ONLY` 和空客户端控制载荷 | `PersonalAgentInvocationControllerTest.rejectsClientRuntimeControlFieldsBeforeServiceInvocation`、`ControlledAguiBridgeM2S01IntegrationTest.clientControlFieldsAreRejectedDuringMessageOnlyDtoParsing` | `400 invalid_request` |
| Principal、Role、Session、Binding 伪造 | JSON 字段、RuntimeContext、Resume | 当前 Principal、Membership、Role、Session、Profile 和 Binding 由仓储事实重建 | `PlatformExecutionContextResolverTest`、`PlatformMiddlewareM2I04Test`、`ExecutionRuntimeContractTest` | 固定授权错误码，模型调用不发生 |
| 跨 Team/Conversation 越权 | 路径 ID、Cursor、Message、Session | Scope、Participant、Conversation 与 Session 必须一致 | `ConversationControllerTest.rejectsInvalidIdentifiersAndCrossConversationMessageCursors`、`ConversationEventControllerTest.rejectsCrossConversationAndMapsCompactedPositionToGone`、`ExecutionRuntimeContractTest.closesInputMessageToTheSessionOwnerAndBoundConversation` | `400/403`，不返回目标资源事实 |
| 运行中撤权 | Membership、Participant、Role、ProviderBinding、AgentState | HTTP 长流持续复验；模型与恢复前执行当前状态预检；Binding 无可用结果时失败关闭 | `ConversationEventControllerTest.resolvesCurrentIdentityAgainBeforeEachSsePoll`、`AgentScopeNativeRuntimeIntegrationTest.runsStatePreflightAgainBeforeResumeAndDoesNotCallTheModelAfterFailure`、`ProviderBindingResolverTest` | 当前 Segment 安全失败，后续调用停止 |
| 敏感信息披露 | Provider 异常、Reasoning、Thinking、Tool 输入输出、未知 Structured Output | 原始事件白名单、固定失败映射、结构化日志字段脱敏 | `ConversationExecutionEventMapperM2I06Test`、`ControlledAguiBridgeM2S01IntegrationTest.outboundSanitizerDropsSensitiveProtocolFamiliesAndRedactsToolResults`、`StructuredLogSanitizerTest` | 日志、SSE、Message 和错误响应只含公开字段 |
| 资源耗尽 | 高频 Runtime Event、重复 SSE 订阅、超长公开文本 | Runtime Queue 10,000；AG-UI Replay 10,000 并预留一个终态位置；活跃订阅者 32；公开 Message 50,000 字符 | `AgentScopeNativeRuntimeIntegrationTest.boundsTheInternalTransportBeforeAConsumerSubscribes`、`ReplayableExecutionSegmentTest`、`ConversationExecutionEventMapperM2I06Test` | 容量耗尽后固定 `RUN_ERROR` 或传输失败闭合 |

## 3. 固定预算

| 资源 | 上限 | 处理 |
|---|---:|---|
| AgentScope Runtime Event Queue | 10,000 | 停止当前 Source Subscription，Invocation 标记失败，传输使用固定错误闭合 |
| 单 Segment AG-UI Replay Event | 10,000 | 始终预留一个终态位置；达到非终态预算时取消 Runtime 传输并追加固定 `RUN_ERROR`，不删除已经公开的事件 |
| 单 Segment 活跃 HTTP Subscriber | 32 | 超限订阅立即拒绝；取消、错误或完成后释放名额，不重复订阅 Runtime |
| 公开 Agent Message | 50,000 字符 | Mapper 累计校验，超限进入安全失败 |
| Invocation Message | 50,000 字符 | DTO、Bean 和领域边界共同校验 |
| Clarification | 最多 10 个回答、每题最多 5 个选项 | Schema、Bean 和领域边界共同校验 |

HTTP Subscriber 主动取消只移除对应订阅，AgentScope 调用继续到逻辑终态。Runtime 内部容量耗尽属于执行安全失败，会停止当前 Source Subscription。在线 Subscriber 与后续重放 Subscriber 观察同一条已保留事件序列；容量失败不会撤回已经公开的事件。

## 4. 披露规则

允许进入公开 SSE 和 Message 的数据族：

- Text Message Content；
- `ClarificationRequestV1` 公开字段；
- 通过 Schema、Bean 和领域校验的 TaskIntent Candidate；
- `RUN_STARTED`、`RUN_INTERRUPTED`、`RUN_FINISHED`、`RUN_ERROR` 固定字段。

禁止进入日志、SSE、Message 和错误响应的数据族：

- Authorization、Cookie、Password、Token、API Key、Private Key、Credential 和 Ciphertext；
- System Prompt、Prompt Template、Reasoning 和 Thinking；
- Tool Input、Tool Arguments、Tool Result 和 Tool Output；
- Provider 原始异常、响应体与内部堆栈；
- 未知 Structured Output 和 AgentScope 内部状态事件。

## 5. 验证命令

```bash
./mvnw -pl crewscope-application,crewscope-agentscope,crewscope-server -am \
  -Dtest=ReplayableExecutionSegmentTest,AgentScopeNativeRuntimeIntegrationTest,ControlledAguiBridgeM2S01IntegrationTest,ConversationExecutionEventMapperM2I06Test,PlatformExecutionContextResolverTest,PlatformMiddlewareM2I04Test,ProviderBindingResolverTest,StructuredLogSanitizerTest,PersonalAgentInvocationControllerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw clean verify
git diff --check
```
