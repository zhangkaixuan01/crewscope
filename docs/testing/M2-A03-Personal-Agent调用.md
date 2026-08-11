# M2-A03：Personal Agent 调用

> 日期：2026-08-10<br>
> 状态：已完成<br>
> 模块：`crewscope-application`、`crewscope-agentscope`、`crewscope-server`

## 目标

交付 Conversation Scope 下的 Personal Agent Invocation、Resume 与 Cancel 入口，把已提交 USER Message、AgentScope 流式执行和最终可见 AGENT Message 连接成一条受控纵向链路。

## HTTP 契约

```text
POST /api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/agent-invocations
POST /api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/agent-invocations/{invocationId}/resume
POST /api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/agent-invocations/{invocationId}/cancel
```

Invoke 请求只接收：

```json
{"message":"请继续整理发布风险。"}
```

Invoke 与 Resume 都要求 `Idempotency-Key`。M2-A05 已将 Resume 请求收口为 `answers: fieldKey -> value`，并继续把回答形成 USER Message 后作为 AgentScope 恢复输入；完整契约见 [TaskIntent 与确认预检](M2-A05-TaskIntent与确认预检.md)。Cancel 只接收安全 `reason` 和 `Idempotency-Key`。客户端不能提交 Agent、Principal、Participant、RuntimeSession、Thread、Run、InterruptToken、Tool、Context、State、ProviderBinding 或 Structured Output 类型。

Invoke 与 Resume 返回 `text/event-stream`，每项 SSE data 是统一 `RealtimeEventEnvelope<AguiTransientPayload>`。`event` 使用安全事件类型，`id` 使用服务端事件 ID。响应返回 `X-CrewScope-Invocation-Id`，所有响应使用 `Cache-Control: no-store`。

## 调用与权限契约

- Personal Agent Invocation 只允许 Conversation Owner USER 发起；普通 TEAM Participant 可以发消息，但不能驱动其他成员的 Personal Agent；
- 服务端从当前 Conversation、Workspace、TeamMember、USER、Personal Agent、AgentProfile 与 Participant 懒初始化或复用 ACTIVE `AgentRuntimeSession`；
- 每次 Invoke、Resume 和 Cancel 都通过 `PlatformExecutionContextResolver` 重新验证当前 Membership、Role、Participant、Profile 与 Scope；
- Invocation ID 由已提交 USER Message 稳定派生，同一个消息不能启动两个逻辑 Invocation；
- M2-A03 的默认调用保持自然对话文本模式；TaskIntent Structured Output 的领域落库与修订入口由 M2-A05 接入现有 Candidate 边界。

## 流与一致性契约

- `ExecutionRuntime` 的单订阅事件流由应用层只消费一次，并转换为有界内存重放流；HTTP 断开不等于业务取消；
- 同一 Invoke/Resume 幂等请求返回原 Segment 的缓存事件，不再次调用 Model；不同内容复用相同键返回 `idempotency_conflict`；
- 公开文本只来自 `ConversationExecutionEventMapper` 白名单，Reasoning、Tool 参数/结果、State、Provider 原始错误与内部上下文不进入 SSE；
- `COMPLETED` 产生的 `AgentMessageCandidate` 必须先在一个事务内锁定 Conversation、分配 Sequence，并提交 Message、`CONVERSATION_MESSAGE_POSTED`、Outbox，随后才能发布 `RUN_FINISHED`；
- 回复提交失败时不发布成功终态，改为安全 `RUN_ERROR`；模型失败、取消和中断不创建 AGENT Message；
- Agent 回复的稳定客户端键由 Invocation 与 Segment 派生，重复 Candidate 不创建第二条 Message；
- M2 仅保留有界进程内 Invocation/Segment 协调与重放，不创建 M3 的 AgentRun、TaskExecution、Lease 或进度事实；M2-A04 补充持久 Conversation Event 历史与跨连接 Cursor 恢复。

## Resume 与 Cancel 契约

- 应用层保存当前 Invocation 的唯一 Pending Interrupt Token，Resume 请求只提交回答；
- Resume 在提交回答前原子预留当前 Pending Interrupt，同一 Interrupt 只允许一个新的 Resume Key 进入 Runtime；
- 相同 Resume Key 与相同规范化回答返回原 Segment，不重复执行 AgentScope；
- Cancel 精确匹配 Organization、Team、Conversation、Owner、Invocation、Session 和当前可信上下文；
- 相同 Cancel Key 与相同原因重放首次结果，不重复传播中断；
- Cancel 返回 `ACCEPTED`、`ALREADY_TERMINAL` 或 `NOT_FOUND`，不伪造 DomainEvent 或 CommandReceipt。

## 实现结果

- `PersonalAgentInvocationService` 统一编排 Invoke、Resume 与 Cancel；Invoke 在创建消息事实前先验证 Owner，随后从已提交 Message 稳定派生 Invocation ID；
- `RepositoryPersonalAgentExecutionContextResolver` 每次从当前 Conversation、Workspace、TeamMember、Principal、AgentProfile 和 Participant 事实建立或复用 `AgentRuntimeSession`，再生成完整 `PlatformExecutionContext`；
- `ReplayableExecutionSegment` 只订阅一次 AgentScope 流，逐项请求上游事件，为每个 HTTP Subscriber 维护独立 demand 与 cursor；HTTP cancel 只取消当前订阅者，进程内缓存支持相同幂等请求重放；
- `ConversationApplicationService.commitAgentMessage` 使用 Conversation 行锁提交 AGENT Message、Sequence、DomainEvent 和 Outbox；稳定的 Invocation/Segment 消息键吸收重复回调，不创建 CommandReceipt；
- `RUN_FINISHED` 在 Agent Message 提交后进入 SSE 缓存；提交或协议失败转换为不含内部异常的 `RUN_ERROR`，中断、取消和模型失败不创建 Agent Message；
- `PersonalAgentInvocationController` 提供正式 Team/Conversation Scope 路由、SSE `id/event/data`、`X-CrewScope-Invocation-Id`、`Idempotency-Replayed` 和 `Cache-Control: no-store`；请求 DTO 拒绝未知运行时控制字段；
- Spring Boot 已装配属性化 Personal Agent 配置、`PersonalAgentFactory`、`AgentScopeNativeRuntime`、Session Service、上下文解析器与 Invocation Service。模型、回退模型、系统提示词、迭代次数、重试次数和运行目录均支持环境变量配置。

## 验证结果

- 应用专项测试覆盖 Invoke 单次执行与重放、服务端 Pending Interrupt、Resume 重放、Cancel 重放、背压、HTTP 断开、最终 Message 顺序和安全提交失败；
- HTTP 专项测试覆盖 SSE 信封、Invocation/重放响应头、Cancel 结果和客户端 Run 字段注入拒绝；
- Spring 装配测试确认 `AgentRuntimeSessionService`、`PersonalAgentFactory`、`ExecutionRuntime`、上下文解析器和 Invocation Service 均只有一个生产 Bean；
- PostgreSQL 专项测试确认 Agent 回复的 Message、Conversation Sequence、DomainEvent 和 Outbox 原子提交、稳定重放、无 CommandReceipt，以及 Outbox 故障时全部回滚；
- 新增 9 项 M2-A03 测试；全仓 `clean verify` 覆盖 639 项测试；文档链接与源码卫生检查通过。

验证命令：

```text
/Users/zhangkaixuan/Downloads/apache-maven-3.9.6/bin/mvn clean verify
node scripts/check-doc-links.mjs
git diff --check
```
