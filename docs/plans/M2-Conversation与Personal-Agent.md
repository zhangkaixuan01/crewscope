# M2：Conversation 与 Personal Agent 执行清单

> 对应总计划：[CrewScope 实施计划](../CrewScope-实施计划.md) M2<br>
> 前置条件：M1 Release Gate 通过<br>
> 目标周期：2 周，多工作流并行推进<br>
> 目标结果：成员可在 Web 与自己的 Personal Agent 持续对话，Agent 可澄清目标、生成结构化 TaskIntent，并在成员确认后创建带责任关系的 WorkItem<br>
> 当前进度：M2 全部完成，Release Gate 已通过（2026-08-12）

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
| `M2-A01` | FEATURE | D02,D07 | application/server | 实现 Conversation 创建、列表、详情、参与者和消息历史 Query/API；从当前 TeamMember 解析默认 Personal Agent | [Conversation 应用与 API](../testing/M2-A01-Conversation应用与API.md)与 25 项专项测试覆盖 PRIVATE/TEAM、默认参与者、Membership、分页、归档、Scope 隐藏、并发变更收口和缓存策略 |
| `M2-A02` | FEATURE | A01 | application/server | 实现追加用户消息 Command，提交 Message、DomainEvent、Outbox 和 CommandReceipt；服务端分配消息序号 | [用户消息追加](../testing/M2-A02-用户消息追加.md)与 38 项专项测试覆盖 `Idempotency-Key`、内容校验、作者资格、并发顺序、归档拒绝、同键冲突、事务回滚和安全 Markdown |
| `M2-A03` | FEATURE | A02,I04,I05,I06 | application/agentscope/server | 实现受控 Personal Agent Invocation/Resume/Cancel 入口和 AG-UI SSE；在用户消息提交后调用 AgentScope 并持久化可见回复 | [Personal Agent 调用](../testing/M2-A03-Personal-Agent调用.md)与 9 项新增测试覆盖 Owner、受控 Session/Context、流式重放、Interrupt/Resume、Cancel、背压、断开、客户端注入、最终 Message 原子提交与回滚 |
| `M2-A04` | FEATURE | A03 | application/infrastructure/server | 实现 Conversation Event 历史 API、SSE `Last-Event-ID`/Cursor 补发、投影追平和 AG-UI/Conversation/Team 跨流去重契约 | [Conversation Event 与断线补发](../testing/M2-A04-Conversation-Event与断线补发.md)与 9 项新增测试覆盖稳定事件 ID、历史分页、断线重连、Cursor 过期、跨 Conversation 防护、长连接身份复验、事务回滚、历史可见截止和 V7→V8 回填 |
| `M2-A05` | FEATURE | D03,A02,I06 | application/agentscope/infrastructure/server | 实现 Clarification、TaskIntent 查询/修订/拒绝和确认预检 API；定义确认 Command Port，最终确认路由由 A07 接入同一原子事务 | [TaskIntent 与确认预检](../testing/M2-A05-TaskIntent与确认预检.md)与 18 项新增测试覆盖生产澄清 Tool、结构化回答、Schema、Candidate 幂等、期望版本、修订/拒绝、Owner/Reviewer 当前资格、职责分离、HTTP 和 PostgreSQL 原子回滚 |
| `M2-A06` | TASK | I01,D07,M1-A01 | application/integration/server | 注册内置 NativeWorkItem ProviderDefinition/Implementation，为新旧 Team 默认 Workspace 幂等初始化 connectionless ProviderBinding，并提供只读能力与 Binding 查询 API | [NativeWorkItem Provider 初始化与 Binding 查询](../testing/M2-A06-NativeWorkItem-Provider.md)与 13 项新增测试覆盖新 Team、既有 Team、并发初始化、重复执行、Scope 权限、唯一默认 Binding、停用、歧义返回和事务回滚 |
| `M2-A07` | FEATURE | A05,A06,D07,M1-A06 | application/integration/server | 实现 TaskIntent 确认路由；同一事务完成 READY→CONFIRMED、解析 NativeWorkItem ProviderBinding、创建 Native WorkItem、Owner、可选 Executor/Gate Reviewer、ConversationWorkItemLink、DomainEvent、Outbox 和 CommandReceipt，并提供双向关联查询 | [TaskIntent 确认与 Native WorkItem 原子创建](../testing/M2-A07-TaskIntent确认与Native-WorkItem.md)与 14 项新增测试覆盖完整成功、空请求体、非空请求体拒绝、幂等重放、If-Match、WorkItem Key 并发、Binding 歧义、责任失败全回滚、越权和双向读取 |

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

### 6.2 M2-A01 Conversation 应用契约

M2-A01 使用当前认证 USER 和 TeamMember 建立服务端可信的 Conversation 上下文。创建命令只接收标题与 `PRIVATE/TEAM` 可见性，应用层从 Team 默认 Workspace、当前 TeamMember 的 ACTIVE 默认 Personal Agent Profile 和对应 Principal 重建 `PersonalConversationInitialization`，在同一事务提交 Conversation、OWNER Participant、AGENT Participant、DomainEvent、Outbox 与 CommandReceipt。客户端不能指定 Owner、Workspace、Personal Agent 或初始 Participant。

Conversation 详情在同一事务快照中批量解析 Participant Principal 及其 Owner Principal，返回 `displayName`、`principalType`、`ownerPrincipalId` 和 `ownerDisplayName`。初始 AGENT Participant 因而可稳定表达为“Agent 名称 / A 的 Personal Agent”；TEAM 可见性不改变 Agent 所有权，其他成员加入时也不会自动加入自己的 Personal Agent。Principal 缺失或 Agent Owner 关系断裂时详情失败关闭，不由浏览器依据 ID 猜测身份。

Conversation 列表在持久化 Keyset 查询中按当前 USER Principal 约束可见范围：`TEAM` 或存在该 Principal 的 Participant；应用层再使用 `ConversationVisibilityPolicy` 复验当前 ACTIVE Membership 与 Participant 生命周期。详情和消息历史将不可见的 PRIVATE Conversation 映射为资源不存在，跨 Team 的 Conversation ID 和跨 Conversation 的 Message Cursor 同样失败关闭。

参与者管理采用以下规则：

- 添加参与者只接受当前 Team 的 ACTIVE TeamMember/USER；Conversation Owner 必须具有有效 `COLLABORATION_REQUEST` Team Permission；
- 已退出的同一 Participant 使用稳定 ID 重新激活，ACTIVE Participant 的重复添加由 Idempotency-Key 重放或业务冲突裁决；
- Conversation Owner 可以移除普通 MEMBER Participant，普通 Participant 可以退出自身；OWNER 与 AGENT Participant 不允许退出；
- Participant 变更先锁定 Conversation 行，串行化同一 Conversation 的加入、重新加入和退出；
- 归档 Conversation 只读，不接受 Participant 变更；成员或 Principal 停用后立即失去发现和读取资格。

消息历史使用 `(conversationId, sequence)` 版本化 Base64URL Cursor，并把 LEFT Participant 的 `leftAt` 作为数据库查询截止条件。Conversation 列表、详情、参与者和消息响应统一返回 `Cache-Control: no-store`；所有 POST/DELETE 命令继续遵循 `/api/v1` 的 `Idempotency-Key`、CommandReceipt 和安全错误信封。

### 6.3 M2-A02 用户消息追加契约

用户消息命令只接收 Markdown `content`。USER Principal、TeamMember、ConversationParticipant、MessageType、MessageId 和 Sequence 全部由服务端解析或生成，客户端不能覆盖作者、角色、Scope 或排序事实。当前 USER 必须是同一 Team 的 ACTIVE TeamMember，并在目标 ACTIVE Conversation 中具有 ACTIVE USER Participant；TEAM Conversation 的可发现权限不自动获得消息写入权限。

`Idempotency-Key` 同时作为 CommandReceipt 预留键和 Message `clientMessageKey`。相同键与相同规范化内容返回原回执且不新增事实；相同键与不同内容返回 `idempotency_conflict`。请求哈希包含 Organization 隔离下的 Actor、Team、Conversation、Causation 和规范化 Markdown，避免同键跨命令或跨资源复用。

追加过程在一个事务内完成：预留 CommandReceipt，锁定 Conversation 行，复验 TeamMember 与 Participant，分配下一个 Message Sequence，更新 Conversation，插入不可变 USER_MESSAGE，追加 `CONVERSATION_MESSAGE_POSTED` DomainEvent，写入 Outbox，最后完成 CommandReceipt。事件以 Conversation 为聚合引用并使用更新后的 Conversation Version，保证消息事件与单调序号一致。任一步失败时全部回滚。

Markdown 作为不可变文本事实保存，服务端只执行非空、长度、控制字符和 Unicode 规范边界校验，不解释或执行 HTML、链接和代码。HTTP 始终通过 JSON 传输内容，不把消息正文写入日志或错误详情。M2-F02 渲染时禁用原始 HTML，并对链接协议和生成节点执行白名单清理；安全策略不以有损改写持久化原文为代价。

### 6.4 M2-A03 Personal Agent 调用契约

Invocation 与 Resume 请求只接收 Markdown `message` 和 `Idempotency-Key`。服务端先复用 M2-A02 提交 USER Message，再以已提交 Message、当前 Owner USER、Personal Agent、ACTIVE AgentRuntimeSession 和重新解析的 `PlatformExecutionContext` 构造 `ExecutionRuntime` 请求。客户端不能提交 Agent、Principal、Participant、RuntimeSession、Thread、Run、InterruptToken、Tool、Context、State、ProviderBinding 或 Structured Output 类型。

Personal Agent 由 Conversation Owner 独占驱动；TEAM Conversation 的普通 Participant 可以追加消息，但不能调用 Owner 的 Personal Agent。Invocation ID 从已提交 USER Message 稳定派生，同一消息只对应一个逻辑 Invocation。Invoke、Resume 与 Cancel 每次都从当前 PostgreSQL 事实重验 Membership、Role、Participant、AgentProfile 与 Scope。M2-A03 默认使用自然对话文本模式，TaskIntent Structured Output 的领域落库与管理由 M2-A05 接入既有 Candidate 边界。

应用层只订阅一次 `ExecutionRuntime` 流，经 `ConversationExecutionEventMapper` 生成安全 AG-UI 信封并写入有界内存重放流。相同幂等请求返回原 Segment，不再次进入 Model；HTTP 断开只取消当前重放订阅，不取消 AgentScope 业务调用。M2-A04 在此基础上增加持久事件历史、Cursor 补发与跨进程恢复。

M2-A04 使用事务内 `conversation_event` 投影索引保存单调 Position、稳定 Conversation Stream Event ID 和源 DomainEvent ID。历史 JSON 与 SSE 共用升序 Keyset 查询；SSE `id` 是绑定 Organization、Team、Conversation、Position 和 Event ID 的版本化 Cursor，`Last-Event-ID` 与 `after` 均可恢复。服务端先补历史再串行轮询耐久水位，慢消费者只合并空 tick，不丢业务事件。首页和每轮读取都重新解析认证主体，并复验 Principal、Membership、Participant 与 Conversation 的当前可见性；任一授权事实失效都会终止该长连接。跨持久流按 DomainEvent ID 合并，单流按 Event ID 去重；过期 Cursor 返回 `410 cursor_expired`，非法或跨 Conversation Cursor 返回 `400 invalid_cursor`。验证见 [Conversation Event 与断线补发](../testing/M2-A04-Conversation-Event与断线补发.md)。

Agent `COMPLETED` 产生的 Message Candidate 必须先锁定 Conversation，并在一个事务内提交 Conversation Sequence、AGENT Message、`CONVERSATION_MESSAGE_POSTED` DomainEvent 与 Outbox，随后才发布 `RUN_FINISHED`。回复提交失败时发布安全 `RUN_ERROR`；中断、取消和失败不创建 AGENT Message。Resume 回答继续作为 USER Message 提交，应用层使用服务端保存的 Pending Interrupt Token；Cancel 返回真实 Runtime 结果，不构造 DomainEvent 或 CommandReceipt。

### 6.5 M2-A05 TaskIntent 与确认预检契约

生产 Personal Agent Toolkit 注册只读 `request_clarification` Tool。Resume HTTP 只接收 `answers: fieldKey -> value`，Bridge 以 AgentScope 保存的 Pending Tool 为基线绑定回答；内置 Tool 验证 Field Key 属于原始 `ClarificationRequestV1`，并要求所有 Required 问题获得回答。客户端不能提交 Tool Name、Tool Input、ConfirmResult、PermissionRule、replyId、toolCallId 或 Session。相同 Resume Key 与相同规范回答重放原 Segment，同 Key 不同回答返回幂等冲突。

Agent 完成并通过 Bean Validation 的 `TaskIntentV1` 在 `RUN_FINISHED` 前进入应用事务。服务端重新解析当前 WorkProject、Principal、TeamMember、Conversation Scope 和职责分离事实，使用 Invocation 与 Segment 派生稳定 TaskIntent ID，依次提交 DRAFT 与 READY、`TASK_INTENT_PROPOSED`、Conversation Event 和 Outbox。相同 Candidate 不重复副作用；稳定 ID 对应不同内容时失败关闭。

TaskIntent GET、完整修订、拒绝和确认预检均通过嵌套 Conversation 可见性。修订、拒绝和预检只允许 Proposal 中的人类 Owner；修订执行 `READY -> DRAFT -> READY`，Proposal Revision 增加 1，Aggregate Version 增加 2。确认预检要求强 `If-Match`，从当前事实重建 Proposal，再调用未落库的领域确认迁移验证 READY、版本、Proposal 一致性和 Owner，不创建 WorkItem、不写事件。

修订与拒绝要求 `Idempotency-Key` 和 `If-Match`，返回 CommandReceipt；GET 与预检返回 `Cache-Control: no-store` 和强 ETag。最终确认闭环见 6.7。完整预检契约与验证见 [TaskIntent 与确认预检](../testing/M2-A05-TaskIntent与确认预检.md)。

### 6.6 M2-A06 NativeWorkItem Provider 初始化与查询契约

内置 NativeWorkItem 注册为 Organization 级 `work-item` Definition 与 `native-work-item` Implementation，接口和实现版本均为 `1.0.0`。能力全集固定为读取、创建、更新、评论和资源关联。Implementation 的 Connection Requirement 为 `NONE`。

每个 READY Team 的默认 Workspace 获得一个 TEAM Owner、`defaultUsage=true` 的 connectionless Binding。Binding 的资源范围精确为 `workspace:{workspaceId}`，不保存 Connection、ConnectionGrant 或外部执行身份。Definition、Implementation 与 Binding ID 从 Organization、Team 和稳定产品 Key 派生；Java 与 Flyway 使用相同 raw MD5 UUID 算法。

新 Team 在 Team foundation 的 REQUIRED 事务中完成初始化；Provider 初始化任一步失败时 Team、Workspace、Membership、Role、Personal Agent 和 Provider 事实一起回滚。Organization 级 PostgreSQL advisory transaction lock 串行化并发注册。重复执行只验证已提交事实与产品契约，不重复写入，也不自动恢复被停用的 Binding。V9 为迁移前已有的完整 ACTIVE Team 补齐同一组稳定事实，不处理未完成 Team。

只读 API 为 `GET /api/v1/organizations/{organizationId}/teams/{teamId}/provider-bindings`。服务端从当前 ACTIVE Membership、Team 默认 Workspace 和 TEAM Owner 解析，返回 `RESOLVED`、`NOT_FOUND` 或 `AMBIGUOUS`，并携带注册能力、固化版本、connectionless 状态、有效能力与资源。响应使用 `Cache-Control: no-store`，不执行隐式修复。完整契约与验证见 [NativeWorkItem Provider 初始化与 Binding 查询](../testing/M2-A06-NativeWorkItem-Provider.md)。

### 6.7 M2-A07 TaskIntent 确认与 Native WorkItem 原子创建契约

确认 API 为：

```text
POST /api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/task-intents/{taskIntentId}/confirmations
```

请求要求 `Idempotency-Key` 和强 `If-Match`，请求体为空。客户端不能提交 WorkItem ID、Key、ProviderBinding、Owner、Executor、Gate Reviewer 或关联事实。服务端锁定 TaskIntent 后复用 A05 确认预检，重新验证当前 Proposal、确认人、WorkProject、Principal、TeamMember、职责分离和 `WORK_CREATE` 权限。

确认事务按以下顺序执行：预留 CommandReceipt；锁定 READY TaskIntent；复验当前事实；锁定 WorkProject；解析唯一可用的内置 connectionless `native-work-item` Binding；按项目内最大数字后缀分配下一个 WorkItem Key；创建 `TASK/MEDIUM` Native WorkItem；创建 Owner、可选 Executor 和可选 Gate Reviewer；以当前 `GateReviewerPolicyProvider` 重新验证 Reviewer；执行 `READY -> CONFIRMED` 并写入 `confirmed_work_item_id`；创建 `TASK_INTENT_CONFIRMATION` 来源的 ConversationWorkItemLink；提交 DomainEvent、Conversation Event、Outbox 和 CommandReceipt。任一步失败时完整回滚。

WorkItem Key 分配要求 WorkProject 行锁、数字后缀读取和插入处于同一事务，数据库 `(project_id, item_key)` 唯一约束兜底。WorkItem 标题由 Objective 规范化并安全截断至 500 字符，描述保存完整 Objective 与 Acceptance Criteria；Type、Priority、Labels 和 DueAt 分别固定为 `TASK`、`MEDIUM`、空集合和空值。

复合命令可以产生 `WORK_ITEM_CREATED`、可选责任事件和根 `TASK_INTENT_CONFIRMED`。只有根确认事件携带命令 `Idempotency-Key`，所有事件共享 Correlation/Causation；CommandReceipt 指向根确认事件。幂等重放直接返回原回执，不重新读取事实、分配 Key 或创建业务图。

双向查询为：

```text
GET /api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/work-items
GET /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/conversations
```

Conversation 方向先验证 Conversation 可见性，再逐项验证 WorkItem 可见性；WorkItem 方向先验证 WorkItem 可见性，再过滤调用者不可发现的 PRIVATE Conversation。Repository 返回跨 Organization、Team、Workspace、Conversation、WorkProject 或 WorkItem Scope 的关联时失败关闭。响应只返回两端摘要和关联事实，并使用 `Cache-Control: no-store`。完整契约与验证见 [TaskIntent 确认与 Native WorkItem 原子创建](../testing/M2-A07-TaskIntent确认与Native-WorkItem.md)。

## 7. 前端

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M2-F01` | FEATURE | A01 | web | 实现 Conversation Gateway/Store、会话列表、创建入口、Team/Conversation 深链接和刷新恢复；替换 M1 Conversation 蓝图数据 | [Conversation 集合与深链接前端](../testing/M2-F01-Conversation集合与深链接前端.md)；Vitest 与 Playwright 覆盖加载、空态、创建、Scope 切换、URL 恢复、竞态取消、权限跳转和窄屏列表 |
| `M2-F02` | FEATURE | A02,F01 | web | 实现真实消息历史、Cursor 加载、消息气泡、Composer、发送状态和安全 Markdown 渲染 | [消息历史与 Composer 前端](../testing/M2-F02-消息历史与Composer前端.md)；Vitest 与 Playwright 覆盖历史续页、幂等发送、optimistic 收口、失败重试、重复消息、长文本、键盘发送与输入保留 |
| `M2-F03` | FEATURE | A03,A04,F02 | web | 实现 AG-UI 流式回复、Conversation Event 合并、断线恢复、跨流去重、取消和投影版本追平 | [AG-UI 流式回复与 Conversation Event 恢复](../testing/M2-F03-AG-UI流式回复与Conversation-Event恢复.md)；Fixture、Vitest 与 Playwright 覆盖 token stream、重连、重复/乱序、慢网、刷新、取消、终态和无 Reasoning 泄露 |
| `M2-F04` | FEATURE | A05,F03 | web | 实现 Clarification 卡、TaskIntent 结构化预览、字段修订、确认/拒绝、过期和版本冲突刷新 | [Clarification 与 TaskIntent 前端](../testing/M2-F04-Clarification与TaskIntent前端.md)；Vitest、Playwright 与 AgentScope 集成测试覆盖结构化问题、同键恢复、完整修订、责任资格、强 ETag、空 Body 确认、重复点击、冲突刷新和安全披露 |
| `M2-F05` | FEATURE | A07,F04 | web | 实现 Conversation 与 WorkItem 双向跳转；Conversation Mode 展示已确认结果，Control Mode 展示关联会话和责任事实 | [Conversation 与 WorkItem 双向跳转](../testing/M2-F05-Conversation与WorkItem双向跳转.md)；Playwright 覆盖确认后跳转、刷新恢复、跨入口返回、无权链接隐藏、深链接与桌面/窄屏布局 |
| `M2-F06` | HARDENING | F05 | web | 完成 Loading/Empty/Error/Offline/Reconnecting/Cancelled 状态、ARIA Live、Focus 管理、Reduced Motion、窄屏 Composer 和浅绿色视觉回归 | [前端状态与可访问性硬化](../testing/M2-F06-前端状态与可访问性硬化.md)；Axe、键盘、慢网/离线、桌面与窄屏截图回归通过；与 vibe-kanban、multica 在布局、Token、组件和任务流上保持明确差异 |

前端继续采用浅绿色 Design Token。Conversation Mode 提供自然语言协作入口，Control Mode 提供 Conversation 列表、参与者、关联 WorkItem 和审计事实的传统管理入口；两个入口共享同一 Gateway、Store、权限和服务端事实。

### 7.1 M2-F01 Conversation 前端契约

M2-F01 只读取和展示 M2-A01 已持久化的 Conversation 与 ACTIVE Participant 事实。列表调用 Team Scope 下的 Conversation 集合 API，详情调用同一 Team 下的稳定 Conversation ID；创建命令只提交标题与可见范围，并使用独立 `Idempotency-Key`。创建响应只包含 CommandReceipt 时，Store 强制刷新集合并从新增服务端事实中恢复新 Conversation ID，不生成客户端临时业务身份。

当前 URL 契约为 `?team=<teamId>&project=<projectId>&conversation=<conversationId>`。刷新恢复选中的 Team、WorkProject 和 Conversation；Team 规范化或切换时清除旧 Conversation ID。Collection 与 Detail 使用独立 AbortController、请求版本和同步版本，慢请求、旧深链接与被规范化的 Query 不能覆盖当前 Scope。客户端路由权限拒绝和服务端 `403` 都进入 Access Denied 边界。

桌面端使用 Conversation 列表、当前详情和 Participant 观察面；窄屏端在列表与详情之间切换并提供显式返回入口。M2-F01 不构造 Message、Composer、AgentRun、TaskIntent、执行进度、工具结果或 Artifact；真实消息历史与输入属于 M2-F02，AG-UI 流式回复属于 M2-F03。

### 7.2 M2-F02 Message 与 Composer 前端契约

M2-F02 只展示 M2-A01/A02 已提交的 Message 事实和本地待确认发送状态。历史 API 返回的服务端倒序页按 `sequence` 转为正序展示；`after` 作为不透明 Cursor 原样回传，续页按 Message ID 去重后合并。USER、AGENT 和 SYSTEM 消息使用独立语义与视觉样式，作者、时间和 Sequence 均来自服务端事实。

Composer 支持 Enter 发送、Shift+Enter 换行和 50,000 字符边界。每次新发送生成独立 `Idempotency-Key`；CommandReceipt 后重读最新历史，以新增 Sequence、作者和内容将 Pending 收口到已提交事实。失败消息留在页面，重试复用原 `Idempotency-Key`；发送失败恢复原输入，用户后续编辑的新草稿不被重试覆盖。Scope 切换会取消旧历史和发送请求，不把本地 Pending 带入其他 Conversation。

Markdown 解析禁用原始 HTML，渲染结果再经 DOM 节点与属性白名单清理；链接仅允许 `http`、`https`、`mailto` 和页内锚点，外部链接强制 `noopener noreferrer`。M2-F02 不调用 Personal Agent，不解释 AG-UI 流事件，不生成 AgentRun、TaskIntent、工具调用或执行结果；这些能力从 M2-F03 开始进入界面。

### 7.3 M2-F03 AG-UI 与 Conversation Event 前端契约

Conversation Owner 的 Composer 提交直接调用 M2-A03 `agent-invocations`，由服务端在运行 Agent 前提交 USER Message；非 Owner ACTIVE Participant 继续调用 M2-A02 Message 追加命令，不获得驱动他人 Personal Agent 的权限。Invocation 请求只携带 `message` 和 `Idempotency-Key`，不接受 Agent、Run、Tool、Context、State 或 Binding 等客户端运行控制。

前端使用 Fetch 读取 POST SSE，分块解析支持 CRLF、多行 Data 和任意网络切片。AG-UI 仅处理 `RUN_STARTED`、`TEXT_MESSAGE_CONTENT`、`RUN_INTERRUPTED`、`RUN_FINISHED` 和 `RUN_ERROR`；Reasoning、Tool、State、Custom 及未知事件均不进入前端状态。AG-UI 与 Conversation Event 分别维护有界 `eventId` 去重集合，跨流不共享瞬时坐标。每个 Segment 只接受第一个终态，达到终态后停止消费该响应的后续帧。公开文本累计不超过 `MessageContent.MAX_LENGTH` 对应的 50,000 字符；非法 Clarification、非法终态和超限文本在客户端失败关闭。

断线后使用原请求与原 `Idempotency-Key` 重放 Segment；活动请求的最小恢复坐标保存于 SessionStorage，页面刷新后仍使用同键重建公开文本。恢复前重新校验调用类型、公开文本、Principal、Sequence、Invocation、Answers 和幂等键的结构与长度，异常记录立即清除并安全关闭。取消始终调用显式 Cancel API，HTTP 断开只停止当前订阅。

Conversation Event SSE 的不透明 Cursor 按 Conversation Scope 保存并通过 `after` 恢复。单流按 `eventId` 去重，跨持久流按 `domainEventId` 合并，Aggregate Version 使用单聚合坐标检测缺口。`CONVERSATION_MESSAGE_POSTED`、Agent 终态或投影缺口触发最新 Message 回读；流式文本只是瞬时展示，最终以持久 AGENT Message 收口。M2-F03 不在客户端创建 AgentRun、TaskIntent 或工具事实；Clarification 回答和 TaskIntent 交互属于 M2-F04。

### 7.4 M2-F04 Clarification 与 TaskIntent 前端契约

`RUN_INTERRUPTED` 在 `kind=CLARIFICATION` 时携带经过 AgentScope Tool Schema、CrewScope 字段边界和唯一 Field Key 校验的公开 `ClarificationRequestV1`。公开对象只包含 SchemaVersion、Summary、Question、Context、Required 和 Choices。ToolCallId、ReplyId、Permission、Session、Tool Result 和原始 Tool Input 不进入 Web 状态。前端按声明问题生成原生表单控件，只提交非空 `fieldKey -> answer`；Required、Choice 和长度在客户端先行校验，服务端继续执行最终校验。Pending Clarification 与 Resume 最小坐标按 Conversation Scope 保存，刷新和断线使用原 `Idempotency-Key` 恢复同一 Segment。

TaskIntent 由 Conversation Event 的 `aggregateId` 定位，页面始终通过 GET 读取当前事实并保留强 ETag。修订提交完整 `TaskIntentV1`，命令完成后重新 GET，不根据 CommandReceipt 推断 Revision 或状态。拒绝原因限制为 1–1000 字符。确认先以当前版本执行 Confirmation Preview，逐字段比对 Proposal、Revision、Version 和 ETag，再发送无请求体的 Confirmation 命令。`409/412` 和预检事实不一致都会自动回读最新 TaskIntent 并要求用户重新检查；`403` 进入统一 Access Denied。命令执行期间禁用重复操作，Owner 提示只改善可用性，授权仍由服务端裁决。

### 7.5 M2-F05 Conversation 与 WorkItem 双向跳转前端契约

双向入口只读取 M2-A07 返回的 `ConversationWorkItemAssociation`。共享 Gateway 分别按 Conversation Scope 和 WorkProject/WorkItem Scope 请求，Store 使用资源键、请求版本和 AbortController 阻止旧 Scope 覆盖当前事实。离开 Conversation 或 Work 路由时清理关联查询 Store，重新进入后读取当前服务端事实。客户端不根据 TaskIntent、CommandReceipt 或 URL 构造关联，不推断 WorkItem ID、Project ID 或 Key。

Conversation Mode 在本地 TaskIntent 确认成功或收到其他成员的确认事件后强制回读关联 API，只有服务端返回可见关联时才展示“已确认工作项”。跳转使用关联摘要中的 WorkItem ID、Project ID 和 Key，并在 URL 保留 Team、Conversation、WorkItem 和 Focus，刷新后仍能恢复同一对象。

Control Mode 的 WorkItem 详情同时读取关联 Conversation 和当前 Owner、Executor、Reviewer 责任事实。WorkItem 方向只渲染服务端返回的可发现 Conversation；被策略过滤的 PRIVATE Conversation 不生成链接或本地占位身份。“返回对话”使用同一关联事实恢复 Conversation 深链接。双向页面共享读模型、权限语义和浅绿色组件，不新增客户端授权边界。

### 7.6 M2-F06 状态、离线与可访问性契约

页面统一展示 Loading、Empty、Error、Offline、Reconnecting 和 Cancelled，状态同时使用文字、图标和颜色。Loading 与 Reconnecting 标记 `aria-busy`，可恢复状态使用 `aria-live="polite"`，错误使用 `role="alert"` 和 `aria-live="assertive"`。Message 历史本身不是 Live Region，独立状态节点只播报最新变化，避免重复朗读整段历史。

`navigator.onLine` 只是交互提示，HTTP 响应继续是事实边界。离线时保留已加载事实和按 Conversation 分区的 Composer 草稿，Textarea 保持可编辑，只禁止发送和网络恢复操作。联网后就地恢复提交，不自动发送草稿，不生成本地业务事实。

对话列表选中后焦点进入详情标题；窄屏返回列表后恢复原对话按钮。新建对话弹窗打开后聚焦标题输入，Tab 限定在弹窗内，Escape、取消和关闭恢复触发元素，创建成功后聚焦新 Conversation 标题。AppShell 提供跳过导航入口。

离开 Conversation 路由时关闭浏览器 SSE 订阅并清理 Conversation/Message 查询 Store，保留服务端正在执行的 Invocation。重新进入时依据 URL 与服务端当前事实重建集合、详情和消息，不展示离开前的长期内存快照。

全局尊重 `prefers-reduced-motion: reduce`，缩短或停止动画、Transition 和平滑滚动。窄屏 Composer 使用 16px 输入字号、42px 最小发送触控区、压缩高度和 `safe-area-inset-bottom`。桌面与 390px 窄屏共享浅绿色 Token 和状态语义。

## 8. 质量与验收

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M2-Q01` | HARDENING | S01,I01,I04,A01-A07 | 全模块 | 建立 Prompt 注入、客户端 Tool 注入、Principal/Role/Binding 伪造、跨 Team/Conversation 越权、撤权、敏感信息和资源耗尽安全专项 | [M2 安全硬化](../testing/M2-Q01-Security-Hardening.md)；自动化安全矩阵证明请求在服务端授权边界失败；日志、SSE、Message 和错误响应不暴露凭证、原始 Reasoning 或内部 Prompt |
| `M2-Q02` | HARDENING | 全部 | 全模块 | 建立 M2 Release Gate，汇总 PostgreSQL、Redis、AgentScope 可控 Model、SSE、浏览器、Axe、视觉、迁移、文档和构建检查 | [M2 Release Gate](../testing/M2-Q02-Release-Gate.md)；纵向 E2E、并发/恢复测试、`./mvnw clean verify`、前端检查、Playwright 与文档链接检查全部通过 |

### 8.1 M2-Q01 安全硬化契约

M2 将用户消息和澄清回答作为不可信业务内容交给 Personal Agent。Prompt 内容不参与身份、角色、会话、ProviderBinding、Toolkit、RuntimeContext 和披露策略解析。上述控制面数据每次调用、恢复和取消都由服务端依据当前持久化事实重新生成。M2 不使用自然语言关键词拦截，安全结果由结构化输入边界、服务端可信上下文、最小 Toolkit、领域校验和当前授权事实共同保证。

Invocation、Resume 和 Cancel DTO 拒绝未知字段，客户端提交的 Tool、Runtime、Principal、Role、Session、Binding、Context 和 State 字段在进入应用服务前失败。Personal Agent 仅装配 M2 已发布的服务端 Tool，高风险 Filesystem、Shell、Subagent、Memory、Dynamic Skill、Workspace Context 和 Tools Config 能力保持关闭。

授权在 HTTP 入口和 AgentScope Middleware 内分别重验。Organization、Team、Workspace、Conversation、Participant、AgentProfile、Session、Role 与 ProviderBinding 必须组成同一条当前有效关系；成员停用、参与者退出、角色过期和 Binding 撤销在下一次模型或 Tool 边界生效。跨 Team、跨 Conversation、错 Session 和错 Binding 请求统一失败关闭。

Runtime 原生事件传输、应用层 AG-UI 重放、单 Segment 事件数、公开文本、澄清字段、答案数量和并发订阅者都使用固定上限。AG-UI Replay 预算预留一个终态位置，溢出时保留已经公开的事件并追加稳定安全失败，保证在线与重放序列一致。浏览器主动断开只移除对应传输订阅者并释放活动名额，不取消业务调用。

AgentScope 原始事件只映射公开 Text、Clarification、TaskIntent Candidate 和稳定终态。日志、SSE、Message 与错误响应禁止包含 Credential、Provider 原始错误、System Prompt、Prompt Template、Reasoning、Thinking、Tool Input、Tool Arguments、Tool Result 和 Tool Output。未知异常转换为固定公开错误码与消息。

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
