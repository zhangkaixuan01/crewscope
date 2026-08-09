# M2：Conversation 与 Personal Agent 执行清单

> 对应总计划：[CrewScope 实施计划](../CrewScope-实施计划.md) M2<br>
> 前置条件：M1 Release Gate 通过<br>
> 目标周期：2 周，多工作流并行推进<br>
> 目标结果：成员可在 Web 与自己的 Personal Agent 持续对话，Agent 可澄清目标、生成结构化 TaskIntent，并在成员确认后创建带责任关系的 WorkItem<br>
> 当前进度：`M2-D01` 至 `M2-D07`、`M2-S01` 至 `M2-S03`、`M2-I01` 至 `M2-I07` 已完成，下一项为 `M2-A01`（2026-08-09）

## 1. 出口结果

M2 完成后具备：

- Conversation、Participant、Message、ConversationWorkItemLink 和 TaskIntent 真实业务事实；
- PRIVATE 与 TEAM Conversation 可见性、参与者资格、消息顺序和历史 Cursor；
- Personal Agent 的 AgentRuntimeSession、Redis AgentStateStore 和稳定 Session Key；
- AgentScope HarnessAgent 原生流式调用、Structured Output、Interrupt/Resume、Middleware 和 RuntimeContext；
- 受 CrewScope 身份与 Scope 保护的 AG-UI/SSE 入口；
- ProviderDefinition、ProviderImplementation、Connection、ConnectionGrant、ProviderBinding 和只读 BindingResolver；
- TaskIntent 澄清、预览、确认、拒绝、过期及原子创建 WorkItem 的闭环；
- Conversation 与 WorkItem 的双向跳转，以及对话式入口与传统管理入口共享的业务事实；
- 断线续传、事件去重、模型 Usage/Error/Retry/Fallback 和安全审计证据。

`ConversationTaskLink` 与 Task 聚合在 M3 一起落库。M2 使用 `ConversationWorkItemLink` 保存已确认 TaskIntent 与 WorkItem 的真实关系，避免 V7 对尚未建立的 Task 表产生悬空引用。

## 2. 依赖顺序

```text
M2-D01 -> M2-D02 -> M2-D03
M2-D01 -> M2-D04
M2-D01 + M2-D03 + M2-D04 + M2-D05 -> M2-D06 -> M2-D07

M2-S01 -> M2-I04
M2-S02 -> M2-I05
M2-S03 -> M2-I03
M2-D05 + M2-D07 -> M2-I01
M2-I02 -> M2-I03 -> M2-I04
M2-I03 + M2-I04 -> M2-I05
M2-I03 + M2-I04 -> M2-I06 -> M2-I07

M2-D02 + M2-D07 -> M2-A01 -> M2-A02
M2-A02 + M2-I04 + M2-I05 + M2-I06 -> M2-A03 -> M2-A04
M2-D03 + M2-A02 + M2-I06 -> M2-A05
M2-I01 + M1-A01 -> M2-A06
M2-A05 + M2-A06 + M2-D07 -> M2-A07

M2-A01 -> M2-F01
M2-A02 + M2-F01 -> M2-F02
M2-A03 + M2-A04 + M2-F02 -> M2-F03
M2-A05 + M2-F03 -> M2-F04
M2-A07 + M2-F04 -> M2-F05 -> M2-F06

安全与越权能力完成 -> M2-Q01
全部能力 -> M2-Q02
```

`D`、`S/I` 和前端契约准备可以并行推进。正式实现从 `M2-D01` 开始；Spike 只验证 M2 新增的安全、并发和恢复边界，不重复 M0 已完成的 AgentScope 基础能力验证。

## 3. Spike 与架构验证

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M2-S01` | SPIKE | M0-S02 | agentscope/server | 验证 CrewScope 受控 AG-UI Bridge：服务端解析 Agent、Conversation、Session 和 Principal，固定 `ToolMergeMode.AGENT_ONLY`，关闭 Reasoning 输出，拒绝客户端 Agent/Thread/Tool 注入 | [受控 AG-UI Bridge 验证记录](../spikes/M2-S01-受控AG-UI-Bridge验证记录.md)与 9 个专项测试证明通用 path/header Agent 路由关闭，未知控制字段、非 USER 消息、客户端 Tool/ForwardedProps 被拒绝，服务端 Agent/Thread/Run/RuntimeContext Session 不可替换，Thinking 不输出 |
| `M2-S02` | SPIKE | M0-S01 | agentscope/infrastructure | 验证 HarnessAgent 同 Session FIFO、不同 Session 并行、取消、进程中断与 Redis State 恢复；明确 `SessionTurnGate` 的单 JVM 边界和 M2 部署约束 | [会话并发与 Redis 恢复验证记录](../spikes/M2-S02-会话并发与Redis恢复验证记录.md)、[ADR-009](../adr/ADR-009-会话执行所有权与恢复协议.md)与 8 个专项测试证明 FIFO、跨 Session 并行、取消/异常清理、JVM Gate 边界、新进程恢复、User 隔离和最后成功检查点恢复；测试使用受控 Publisher/Latch，不依赖时间等待猜测 |
| `M2-S03` | SPIKE | M0-S02 | agentscope/application | 使用 M2 Schema 验证 `TaskIntentV1`、`ClarificationRequestV1` Structured Output、Bean Validation、Interrupt/Resume 和重复 Resume | [结构化意图与澄清恢复验证记录](../spikes/M2-S03-结构化意图与澄清恢复验证记录.md)与 7 个专项测试覆盖合法嵌套输出、Schema 修正、Bean/Domain 校验、澄清中断、回答绑定恢复、重复/冲突确认、过期和错配输入；固定 Resume 在 AgentScope 前幂等裁决 |

Spike 结论直接约束正式 Adapter。验证发现框架默认行为与平台安全边界不一致时，由 CrewScope Bridge 收紧入口并保留 AgentScope 内部运行能力。

## 4. 领域与数据

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M2-D01` | TASK | M1 | domain | 实现 Conversation、ConversationParticipant、Message、ConversationWorkItemLink、ID/枚举和值对象；明确成员、Personal Agent、消息作者和关联来源 | [Conversation 领域模型](../testing/M2-D01-Conversation领域模型.md)与 22 个新增单元测试覆盖完整初始化、稳定参与者身份、作者资格、不可变消息、关联幂等、Scope 一致性、审计和终态时间 |
| `M2-D02` | TASK | D01 | domain/application | 实现 PRIVATE/TEAM 可见性、参与者加入/退出、Conversation 状态、单调消息序号、列表与历史 Cursor 规则 | [Conversation 可见性与 Cursor](../testing/M2-D02-Conversation可见性与Cursor.md)与 15 个新增测试覆盖 Owner/TeamMember/Agent 可见性、退出后的历史边界、归档、单调序号、同时间排序、Cursor 续页和跨 Conversation Cursor 拒绝 |
| `M2-D03` | TASK | D01,D02 | domain/application | 实现 `TaskIntentV1`、`ClarificationRequestV1`、Intent 版本及 DRAFT/READY/CONFIRMED/REJECTED/EXPIRED 生命周期 | [TaskIntent 与澄清契约](../testing/M2-D03-TaskIntent与澄清契约.md)与 18 个新增测试覆盖 Schema、Bean Validation、必填目标、WorkProject、Owner、Executor、Gate Reviewer、Revision、期望版本、重复决策与职责分离 |
| `M2-D04` | TASK | D01 | domain/application | 实现 AgentRuntimeSession，绑定 Personal Agent、Conversation、AgentScope userId/sessionId、配置版本、状态引用和生命周期 | [AgentRuntimeSession 绑定与生命周期](../testing/M2-D04-AgentRuntimeSession绑定与生命周期.md)与 12 个新增测试覆盖稳定 Session Key、Team 隔离、Personal Agent 所有权、并发初始化、停用/归档和不可伪造绑定 |
| `M2-D05` | TASK | M1-D03 | domain/application | 实现 ProviderDefinition、ProviderImplementation、Connection、ConnectionGrant、ProviderBinding 最小只读模型及状态、版本、Owner Scope 和能力约束 | [Provider、Connection 与 Binding 领域契约](../testing/M2-D05-Provider与Binding领域契约.md)与 18 个新增测试覆盖 USER/TEAM/ORGANIZATION 所有权、实现兼容、Grant 范围交集、撤销、过期、版本与跨 Team 拒绝 |
| `M2-D06` | TASK | D01,D03,D04,D05 | infrastructure | 新增 `V7__conversation_agent_and_provider_binding.sql`，包含 Conversation、Participant、Message、ConversationWorkItemLink、TaskIntent、AgentRuntimeSession 和 Provider/Connection/Binding 最小表 | [V7 Conversation、Agent 与 Provider 数据迁移](../testing/M2-D06-V7-Conversation-Agent与Provider数据迁移.md)与 7 个 PostgreSQL 测试覆盖空库、V6→V7、非默认 `search_path`、复合 Scope 外键、审计字段、部分唯一索引、消息序号和 Session 并发约束 |
| `M2-D07` | TASK | D06 | infrastructure | 实现 M2 JPA Entity、Mapper 与 Repository Adapter；为消息追加、Intent 决策、Session 初始化和 Binding 解析提供明确的锁与查询 Port | [M2 JPA 持久化适配](../testing/M2-D07-M2-JPA持久化适配.md)与 8 个 PostgreSQL 测试覆盖 CRUD、JSONB 映射、Keyset 分页、乐观锁、客户端幂等、并发消息序号、Intent 单次确认、Session 并发初始化、Binding Scope 查询计划和错误 Scope 失败关闭 |

V7 的数据库约束至少覆盖：

- 同一 Conversation 中唯一 active Participant；
- 同一 Conversation 中唯一消息序号和客户端消息幂等键；
- 同一 TeamMember、Personal Agent 与 Conversation 的唯一 active AgentRuntimeSession；
- 一个 TaskIntent 只能产生一个确认结果和一个 WorkItem 关联；
- Connection、ConnectionGrant、ProviderBinding 的 Owner、Workspace、ProviderDefinition 与 ProviderImplementation Scope 完整性；
- 成员或 Agent 可修改的业务表统一保存创建/修改 Principal、创建/修改时间、版本与状态化生命周期事实；不可变 Message 保存创建事实和撤回/脱敏状态，不覆盖原始审计链。

Message、TaskIntent 和 DomainEvent 分层保存：Message 是用户可见的会话事实；TaskIntent 是可确认的结构化业务提案；DomainEvent/Outbox 是事务事件事实。M2 不创建 TaskExecution、ExecutionLease、Task Token 或耐久 AgentRun。

## 5. AgentScope Runtime 与 Provider 基础设施

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M2-I01` | TASK | D05,D07 | application/infrastructure | 实现 ADR-006 的只读 BindingResolver，按执行身份、能力、Workspace 和 WorkProject 解析候选；同级多匹配返回歧义并失败关闭 | [BindingResolver 验证记录](../testing/M2-I01-BindingResolver.md)；Application 契约与 PostgreSQL 测试覆盖优先级、身份隔离、Grant 交集、暂停/撤销、Scope 收口、显式 Binding 收窄和歧义结果 |
| `M2-I02` | TASK | M0-S01,D03,D04 | application/agentscope | 将现有 `AgentRuntime` 演进为 `ExecutionRuntime` Port，定义 Conversation 调用、流式事件、Structured Output、Interrupt、Resume、Cancel 和能力描述 | [ExecutionRuntime Port 验证记录](../testing/M2-I02-ExecutionRuntime-Port.md)；11 个专项测试覆盖可信输入、Structured Output、背压、单订阅、传输/业务取消、终态、恢复、错误分类和 Conversation Invocation 边界 |
| `M2-I03` | FEATURE | S03,I02 | agentscope | 实现 `AgentScopeNativeRuntime`、PersonalAgentFactory 和按 AgentProfile/模型配置创建的 HarnessAgent；接入 Structured Output 与中断恢复 | [AgentScopeNativeRuntime 验证记录](../testing/M2-I03-AgentScopeNativeRuntime.md)；16 个专项测试覆盖实例复用、版本隔离、多轮对话、Session 隔离、TaskIntent、连续澄清、恢复校验、传输断开、精确取消、安全失败、异常流闭合和终态淘汰 |
| `M2-I04` | TASK | S01,I01,I03 | application/agentscope/server | 实现 `PlatformExecutionContext` 到 AgentScope `RuntimeContext` 的类型化注入，以及 Team/Workspace/Principal/Conversation/ProviderBinding 安全中间件和 Audit Middleware | [PlatformExecutionContext 与 Middleware 验证记录](../testing/M2-I04-PlatformExecutionContext与Middleware.md)；所有可信事实来自服务端，客户端字段无法覆盖，缺少 Membership、Scope 或 Binding 时调用在模型执行前失败 |
| `M2-I05` | TASK | S02,I03,I04 | agentscope/infrastructure/server | 装配 `RedisDistributedStore`/`RedisAgentStateStore`、稳定 Session Key、同 Session FIFO、跨 Session 并行和取消清理；执行 Redis 读写预检并固化 M2 单活动执行实例约束 | [Redis AgentStateStore 与单活动实例验证记录](../testing/M2-I05-Redis-AgentStateStore与单活动实例.md)；Redis Testcontainers 与确定性并发测试覆盖状态保存、重启恢复、FIFO、公平性、并行、重复提交、取消、显式状态清理、Redis 不可用失败关闭、崩溃租约过期和双实例启动拒绝 |
| `M2-I06` | TASK | I03,I04 | agentscope/application/server | 实现 AgentScope Event 到 AG-UI 瞬时事件、Message、TaskIntent 与 CrewScope 实时事件信封的映射；过滤原始 Thinking/Reasoning 与敏感参数 | [AgentScope 事件映射与脱敏验证记录](../testing/M2-I06-AgentScope事件映射与脱敏.md)；Fixture 契约测试覆盖事件映射、DomainEvent 关联、未知事件、乱序、重复、脱敏、终态和协议兼容 |
| `M2-I07` | TASK | I06 | agentscope/infrastructure | 记录 Model、Token Usage、Latency、Error、Retry、Fallback、Conversation/Session/Trace 关联和低基数指标，不生成 M3 AgentRun 事实 | [Agent 调用可观测性验证记录](../testing/M2-I07-Agent调用可观测性.md)；测试覆盖成功、流中断、真实重试、Fallback、取消、脱敏、指标标签基数和 correlationId 完整调用定位 |

M2 使用 AgentScope 2.0.0 的 HarnessAgent、RuntimeContext、Structured Output、Interrupt/Resume、Middleware、Hook、流式事件和 Redis DistributedStore。Plan Mode、Workspace 文件操作、Skill、Subagent、Async Tool、Sandbox 与 Coding Agent 在 M3/M4 按耐久执行和最小权限边界接入。

### 5.1 Session 与安全契约

服务端生成 AgentScope 状态隔离键：

```text
userId    = organizationId + teamMemberId + personalAgentPrincipalId
sessionId = conversationId + agentRuntimeSessionId
```

原始 ID 使用规范化、带版本的编码，禁止直接拼接可歧义字符串。每次 Call、Resume 和 Cancel 都从当前认证、PostgreSQL 与 BindingResolver 重建 `PlatformExecutionContext`，并注入：

```text
Organization / Team / Workspace / Conversation
USER Principal / TeamMember / Personal Agent Principal / AgentProfile
Conversation visibility / Participant membership
ProviderBinding resolution / ConnectionGrant scope
Correlation / Causation / Domain Event metadata
```

AG-UI 仅承担 Agent 调用的流式传输和渲染。Conversation、Message、TaskIntent、WorkItem 与责任关系以 CrewScope PostgreSQL 事实为准。客户端提交的 `agentId`、`threadId`、`runId`、Principal、Role、ProviderBinding 和 Tool 定义不参与授权裁决。

AgentScope `ReActAgent` 通过 `AgentBase` 会话尾链按 `(userId, sessionId)` 串行直接 HarnessAgent 调用，Harness Gateway 再通过 `SessionTurnGate` 提供公平 Turn Gate；两者都是单 JVM 能力。M2 Agent 调用固定由一个活动 CrewScope Server 实例执行，滚动发布先排空或中断保存旧实例，再由新实例接管。Redis AgentStateStore 负责每轮重载、成功保存和进程恢复。横向执行进入后续带 fencing token 的分布式 Session Lease，详细决策见 [ADR-009](../adr/ADR-009-会话执行所有权与恢复协议.md)。

## 6. 应用用例与 API

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M2-A01` | FEATURE | D02,D07 | application/server | 实现 Conversation 创建、列表、详情、参与者和消息历史 Query/API；从当前 TeamMember 解析默认 Personal Agent | 应用与 HTTP 测试覆盖 PRIVATE/TEAM、默认参与者、Membership、分页、归档、Scope 隐藏和缓存策略 |
| `M2-A02` | FEATURE | A01 | application/server | 实现追加用户消息 Command，提交 Message、DomainEvent、Outbox 和 CommandReceipt；服务端分配消息序号 | 测试覆盖 `Idempotency-Key`、内容校验、作者资格、并发顺序、归档拒绝、同键冲突、事务回滚和安全 Markdown |
| `M2-A03` | FEATURE | A02,I04,I05,I06 | application/agentscope/server | 实现受控 Personal Agent Invocation/Resume/Cancel 入口和 AG-UI SSE；在用户消息提交后调用 AgentScope 并持久化可见回复 | 测试覆盖认证解析、受控 Session、流式文本、TaskIntent/Interrupt、取消、背压、模型失败、客户端注入拒绝和最终 Message 一致性 |
| `M2-A04` | FEATURE | A03 | application/infrastructure/server | 实现 Conversation Event 历史 API、SSE `Last-Event-ID`/Cursor 补发、投影追平和 AG-UI/Conversation/Team 跨流去重契约 | 集成测试覆盖断开、重连、重复、乱序、Cursor 过期、慢消费者、跨 Conversation Cursor 和历史后实时无缝切换 |
| `M2-A05` | FEATURE | D03,A02,I06 | application/server | 实现 Clarification、TaskIntent 查询/修订/拒绝和确认预检 API；定义确认 Command Port，最终确认路由由 A07 接入同一原子事务 | 测试覆盖多轮澄清、Schema 错误、期望版本、重复修订/拒绝、过期、Owner/Reviewer 资格、职责分离与服务端事实覆盖 |
| `M2-A06` | TASK | I01,D07,M1-A01 | application/integration/server | 注册内置 NativeWorkItem ProviderDefinition/Implementation，为新旧 Team 默认 Workspace 幂等初始化 connectionless ProviderBinding，并提供只读能力与 Binding 查询 API | 应用、迁移后补全与 HTTP 测试覆盖新 Team、既有 Team、并发初始化、重复执行、Scope 权限、唯一默认 Binding、停用和歧义返回 |
| `M2-A07` | FEATURE | A05,A06,D07,M1-A06 | application/integration/server | 实现 TaskIntent 确认路由；同一事务完成 READY→CONFIRMED、解析 NativeWorkItem ProviderBinding、创建 Native WorkItem、Owner、可选 Executor/Gate Reviewer、ConversationWorkItemLink、DomainEvent、Outbox 和 CommandReceipt，并提供双向关联查询 | PostgreSQL 与 HTTP 测试覆盖完整成功、幂等重放、If-Match、WorkItem Key 并发、Binding 歧义、责任失败全回滚、关联唯一性、越权和双向读取 |

### 6.1 M2 API 契约

Conversation 使用 Team Scope 路径，避免从全局 ID 推断租户：

```text
POST /api/v1/organizations/{organizationId}/teams/{teamId}/conversations
GET  /api/v1/organizations/{organizationId}/teams/{teamId}/conversations
GET  /api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}
POST /api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/participants
DELETE /api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/participants/{participantId}
GET  /api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/messages
POST /api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/messages
GET  /api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/events
POST /api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/agent-invocations
POST /api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/agent-invocations/{invocationId}/resume
POST /api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/agent-invocations/{invocationId}/cancel
GET  /api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/task-intents/{taskIntentId}
POST /api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/task-intents/{taskIntentId}/revisions
POST /api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/task-intents/{taskIntentId}/confirmation-previews
POST /api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/task-intents/{taskIntentId}/confirmations
POST /api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/task-intents/{taskIntentId}/rejections
GET  /api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/work-items
GET  /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/conversations
GET  /api/v1/organizations/{organizationId}/teams/{teamId}/provider-bindings
```

`agent-invocations` 表达一次流式调用资源，M2 使用临时 `invocationId` 关联协议事件和 Trace；耐久 `AgentRun` 聚合、状态机与表由 M3 建立。响应只返回已发生的调用与协议事实，不构造 TaskExecution、Step、进度、耗时或制品状态。

Conversation、Message、TaskIntent 决策和 WorkItem 创建 Command 返回：

```text
commandId / domainEventId / committedVersion / correlationId
```

PRIVATE Conversation 仅对 Owner USER、其当前 Team 的 Personal Agent 和显式有效参与者可见。TEAM Conversation 对当前 Team ACTIVE Member 可发现，消息写入要求有效 Participant。参与者增删要求 Team Scope 权限；成员停用后立即失去读取与写入权限。

## 7. 前端

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M2-F01` | FEATURE | A01 | web | 实现 Conversation Gateway/Store、会话列表、创建入口、Team/Conversation 深链接和刷新恢复；替换 M1 Conversation 蓝图数据 | Vitest 与 Playwright 覆盖加载、空态、创建、Scope 切换、URL 恢复、竞态取消、权限跳转和窄屏列表 |
| `M2-F02` | FEATURE | A02,F01 | web | 实现真实消息历史、Cursor 加载、消息气泡、Composer、发送状态和安全 Markdown 渲染 | 测试覆盖历史续页、幂等发送、optimistic 合并、失败重试、重复消息、长文本、键盘发送与输入保留 |
| `M2-F03` | FEATURE | A03,A04,F02 | web | 实现 AG-UI 流式回复、Conversation Event 合并、断线恢复、跨流去重、取消和投影版本追平 | Fixture、Vitest 与 Playwright 覆盖 token stream、重连、重复/乱序、慢网、刷新、取消、终态和无 Reasoning 泄露 |
| `M2-F04` | FEATURE | A05,F03 | web | 实现 Clarification 卡、TaskIntent 结构化预览、字段修订、确认/拒绝、过期和版本冲突刷新 | 测试覆盖完整键盘操作、责任资格提示、服务端错误定位、重复点击、冲突与确认后的事实刷新 |
| `M2-F05` | FEATURE | A07,F04 | web | 实现 Conversation 与 WorkItem 双向跳转；Conversation Mode 展示已确认结果，Control Mode 展示关联会话和责任事实 | Playwright 覆盖确认后跳转、刷新恢复、跨入口返回、无权链接隐藏、深链接与桌面/窄屏布局 |
| `M2-F06` | HARDENING | F05 | web | 完成 Loading/Empty/Error/Offline/Reconnecting/Cancelled 状态、ARIA Live、Focus 管理、Reduced Motion、窄屏 Composer 和浅绿色视觉回归 | Axe、键盘、慢网/离线、桌面与窄屏截图回归通过；与 vibe-kanban、multica 在布局、Token、组件和任务流上保持明确差异 |

前端继续采用浅绿色 Design Token。Conversation Mode 提供自然语言协作入口，Control Mode 提供 Conversation 列表、参与者、关联 WorkItem 和审计事实的传统管理入口；两个入口共享同一 Gateway、Store、权限和服务端事实。

## 8. 质量与验收

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M2-Q01` | HARDENING | S01,I01,I04,A01-A07 | 全模块 | 建立 Prompt 注入、客户端 Tool 注入、Principal/Role/Binding 伪造、跨 Team/Conversation 越权、撤权、敏感信息和资源耗尽安全专项 | 自动化安全矩阵证明请求在服务端授权边界失败；日志、SSE、Message 和错误响应不暴露凭证、原始 Reasoning 或内部 Prompt |
| `M2-Q02` | HARDENING | 全部 | 全模块 | 建立 M2 Release Gate，汇总 PostgreSQL、Redis、AgentScope 可控 Model、SSE、浏览器、Axe、视觉、迁移、文档和构建检查 | 纵向 E2E、并发/恢复测试、`./mvnw clean verify`、前端检查、Playwright 与文档链接检查全部通过并形成 Release Gate 记录 |

M2-Q02 至少覆盖：

1. 成员创建 PRIVATE Conversation 后自动加入本人和默认 Personal Agent；
2. TEAM Conversation 的发现与写入遵循 Membership 和 Participant 规则；
3. 同一 Session 的 Agent 调用严格 FIFO，不同 Session 可以并行；
4. Personal Agent 使用服务端 RuntimeContext，无法采用客户端伪造的 Principal、TeamRole、Session 或 ProviderBinding；
5. Agent 通过多轮澄清产生通过 Schema 和业务校验的 TaskIntent；
6. 用户确认 TaskIntent 后原子创建 WorkItem、Owner、可选 Executor/Gate Reviewer 和 ConversationWorkItemLink；
7. 确认事务任何一步失败时不留下 WorkItem、责任或关联孤儿；
8. SSE 中断和页面刷新后从 Cursor 恢复，历史与实时事件不重复、不遗漏；
9. 模型失败、Retry、Fallback、Usage、取消和恢复均可关联到 Conversation、Session、Principal 和 Trace；
10. ProviderBinding 同级歧义、撤销或越权时失败关闭；
11. Conversation Mode 与 Control Mode 展示相同业务事实并支持对象级双向跳转；
12. 桌面与窄屏的流式消息、澄清卡、TaskIntent、离线状态、键盘和可访问性符合前端规范；
13. 页面不展示虚构 TaskExecution、AgentRun、进度、工具结果、Diff 或 Artifact；
14. V7 空库、V6→V7 和非默认 `search_path` 迁移全部通过。

## 9. M2 非目标

- Task、TaskExecution、StepExecution、ExecutionLease、Task Token 和耐久 AgentRun；
- Coding Agent、Git Worktree、Sandbox、仓库修改、测试执行和 DiffArtifact；
- GitHub、飞书真实 Connection 授权、Webhook 和写操作；
- 外部副作用 Tool、PlannedAction、Confirmation、Review Gate 和 ActionReceipt；
- Plan Mode、Skill、Subagent、Async Tool 与多 Agent 编排的产品化；
- Team Agent 主动调度、跨成员协作、Handoff、Takeover、Contribution 和 Inbox。

这些能力按 M3–M6 的耐久执行、安全授权和团队协作边界接入。M2 的 Personal Agent 完成对话、澄清和工作建档，实际执行继续由人通过 WorkItem 管理。
