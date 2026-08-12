# M3：耐久 Task Runtime 执行清单

> 对应总计划：[CrewScope 实施计划](../CrewScope-实施计划.md) M3<br>
> 前置条件：M2 Release Gate 通过<br>
> 目标周期：3–4 周，多工作流并行推进<br>
> 目标结果：成员可从 WorkItem 或 Conversation 创建 Task，Worker 可安全领取并驱动 TaskExecution，成员可观察、暂停、恢复、取消和重试，进程或 Redis 故障后执行可收敛<br>
> 当前进度：已完成任务细化，下一项为 `M3-S01`（2026-08-12）

## 1. 出口结果

M3 完成后具备：

- Task、TaskExecution、StepExecution、PlanVersion、PolicySnapshot、Task/Step AgentRuntimeSession 和责任快照真实业务事实；
- ExecutionRuntime、RuntimeWorker、RuntimeCapabilities、Claim、ExecutionLease 和 Heartbeat；
- 一次性 Claim Token、单调 Fencing Token、Task Token 和范围化 TaskCredentialGrant；
- AgentScope Task Orchestrator、Plan/Todo、Interrupt/Resume 和耐久 AgentRun；
- PostgreSQL 执行检查点、Redis AgentState 和 ArtifactStore AgentStateSnapshot 分层恢复；
- Pause、Resume、Cancel、Retry、Lease Sweeper 和进程重启对账；
- WorkItem、Conversation 与 Task 双向关联；
- Conversation Mode 的 Task 卡片和 Control Mode 的 Task 管理、执行详情、时间线与控制操作；
- 并发、越权、撤权、故障注入、断线恢复和前后端 Release Gate 证据。

M3 不创建 ExecutionWorkspace，不修改真实代码，不运行 Git Worktree 或 Sandbox，不创建 Review、PlannedAction、ActionReceipt 或 GitHub Draft PR。上述能力分别在 M4 和 M5 交付。

## 2. 依赖顺序

```text
M3-S01 -> M3-D05
M3-S02 -> M3-I05 -> M3-I06
M3-S03 -> M3-I08

M3-D01 -> M3-D02 -> M3-D03
M3-D04 -> M3-D05 -> M3-D06
M3-D02 + M3-D03 + M3-D06 -> M3-D07
M3-D01..D07 -> M3-D08 -> M3-D09

M3-D04 + M3-D09 -> M3-I01 -> M3-I02
M3-D05 + M3-I02 -> M3-I03
M3-D06 + M3-D09 -> M3-I04
M3-D07 + M3-I05 -> M3-I07
M3-D07 + M3-I07 -> M3-I08
M3-I01..I08 -> M3-I09

M3-D01 + M3-D02 + M3-D09 -> M3-A01
M3-A01 + M3-D09 -> M3-A02
M3-I02 + M3-I03 + M3-I04 -> M3-A03
M3-A03 + M3-I05 + M3-I08 -> M3-A04
M3-I07 + M3-A02 -> M3-A05
M3-A01 + M3-A02 -> M3-A06
M3-I01 + M3-I09 -> M3-A07

M3-A02 + M3-A06 -> M3-F01 -> M3-F02
M3-A02 + M3-A05 + M3-A07 -> M3-F03
M3-A01 + M3-A05 + M3-F01 -> M3-F04
M3-A04 + M3-F03 -> M3-F05
M3-A05 + M3-F03 -> M3-F06
M3-F02..F06 -> M3-F07

安全与授权能力完成 -> M3-Q01
运行、恢复与控制能力完成 -> M3-Q02
全部能力 -> M3-Q03
```

领域与数据、Spike、前端契约准备可以并行推进。正式开发从 `M3-S01` 开始；`M3-D01` 可在 Spike 执行期间并行启动，`M3-D08` 必须在 D01–D07 契约稳定后实施。

## 3. 固定执行契约

### 3.1 状态与所有权

- Task 表达业务目标生命周期，TaskExecution 表达一次执行尝试，两者状态严格分离；
- Retry 创建新的 TaskExecution，并通过 `parentExecutionId` 关联旧尝试，旧终态保持不可变；
- MVP 只有 TaskExecution Lease，StepExecution 在有效 Lease 内串行执行，不创建 Step Lease；
- 所有 Worker 写入同时使用 `taskExecutionId + attempt + claimToken + fencingToken + expectedVersion` 校验；
- Claim Token 明文只在成功领取时返回一次，PostgreSQL 只保存安全 Hash；
- 失效 Lease、旧 Fencing Token 或错误 Worker 不能提交 Step、AgentRun 或 TaskExecution 结果；
- Complete 与 Lease Sweeper 竞争时只有一个状态更新和一组有效终态事件成功；
- Pause 与 Cancel 先进入请求态，在安全点收敛，不把强制中断伪造成正常完成。

### 3.2 身份与授权

- Task Token 绑定 Organization、Team、Workspace、Task、TaskExecution、attempt、Runtime、Worker、执行 Principal、ProviderBinding、Tool、资源范围、JTI 和过期时间；
- Agent、浏览器和前端不能提交或替换 Runtime、Worker、Principal、责任、Binding、Claim Token 或 Task Token Claims；
- Provider、Connection、Grant、Binding、TeamMember 或责任撤销通过当前事实复验和 SafetyEnforcementOverlay 立即收紧已签发权限；
- M3 不执行 Provider 写操作。Task Token 只验证受控 Runtime/Tool 访问边界，外部写操作继续等待 M5 的 PlannedAction 与精确确认。

### 3.3 Agent 与恢复

- AgentRun 是 Task 执行的耐久事实；M2 的有界 Invocation Registry 不承担 M3 的跨进程恢复；
- Redis 保存 AgentScope 运行态，PostgreSQL 保存业务事实和检查点，ArtifactStore 保存 AgentStateSnapshot 与大结果；
- Redis 丢失时从最近有效 Snapshot 和已提交领域事实重建；无法精确续接时创建新 AgentRun，并保存 continuity gap；
- AgentScope Plan/Todo 是运行时计划输入和进度工具，CrewScope 校验并固化的 PlanVersion 才是领域事实；
- M3 的 ExecutionRequest 不创建 ExecutionWorkspace，相关字段使用 Optional/Absent，不产生悬空标识。

### 3.4 策略快照

- Task 创建时生成初始 PolicySnapshot，至少固化责任快照、AgentProfile/模型/Prompt 版本、Runtime 能力、ProviderBinding/Grant、Tool 范围、预算和授权证据；
- PlanVersion 改变能力范围、责任主体或 ProviderBinding 时生成带父版本的新 PolicySnapshot，原快照保持不可变；
- 每次模型调用、Tool 边界、恢复和 Task Token 签发都使用当前 PolicySnapshot，并叠加只能收紧权限的 SafetyEnforcementOverlay；
- M3 只实现耐久执行所需的最小 PolicySnapshot，不提前实现 M5 的 ActionDigest、Confirmation 或外部写授权。

### 3.5 责任快照

- WorkItem 的 Owner、Executor 和 Reviewer 继续由 M1 `ResponsibilityAssignment` 管理；
- 创建 Task 时读取当前有效责任，生成不可变 `TaskResponsibilitySnapshot`；
- TaskExecution 与 StepExecution 保存执行 Principal、来源 Assignment ID、Assignment Version 和快照 Hash；
- M3 不把既有 Responsibility 表泛化为多 Subject 模型；通用 Task/Step Responsibility 在团队协作需求验证后通过独立 ADR 评估。

## 4. Spike 与架构验证

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M3-S01` | SPIKE | M2-Q02, ADR-001 | application/infrastructure | 使用 PostgreSQL 验证 `FOR UPDATE SKIP LOCKED` Claim、配额检查、Claim Token Hash、单调 Fencing Token、Lease 续期和条件终态更新；覆盖两个 Worker、旧 Owner 和 Complete/Sweeper 竞争 | 可复现 Spike 记录与 Testcontainers 测试证明唯一 Claim、旧 Token 全部失败、租约续期不换 Owner、终态竞争仅一个提交成功，并固定事务隔离级别、锁顺序、更新谓词和索引 |
| `M3-S02` | SPIKE | M2-I03, AgentScope 2.0.0 | agentscope/application | 验证 AgentScope Task Agent 与 CrewScope Task Orchestrator 的映射；覆盖 Plan Mode、TodoTools、RuntimeContext、Interrupt/Resume、进程内恢复和新的 Agent 实例续接 | Spike 记录、最小适配代码和受控 Model 测试明确 ProposedPlan、Todo、PlanVersion、AgentRun、Interrupt Token 与安全检查点的映射；未接通的 AgentScope 能力不写入 RuntimeCapabilities |
| `M3-S03` | SPIKE | M0-D04, M2-I05, ADR-003 | agentscope/infrastructure | 验证 Redis AgentState、ArtifactStore AgentStateSnapshot 和 PostgreSQL 检查点的二级恢复；注入 Redis 清空、状态损坏、写入中断与进程退出 | Spike 记录与集成测试证明 Snapshot Hash/身份校验、Redis 重建、最近完整快照选择、损坏快照失败关闭和 continuity gap；固定快照一致性点、大小上限、TTL 与清理责任 |

Spike 结论直接约束 D/I 工作流。结论改变已接受 ADR 时先更新 ADR，再进入正式 Adapter。

## 5. 领域与数据

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M3-D01` | TASK | M2-A07 | domain/application | 实现 Task、ConversationTaskLink、TaskSource 和 `TaskResponsibilitySnapshot`；Task 保存受约束的来源 WorkItem 和可复现输入引用，定义从 WorkItem/Conversation 创建、当前有效尝试与业务状态生命周期 | 单元测试覆盖 Scope 闭合、来源唯一性、一个 WorkItem 的多个 Task、双向关联、责任快照完整性、当前尝试切换、取消和不可变创建事实；M3 不引入未被首条闭环使用的 TaskDefinition 表 |
| `M3-D02` | TASK | D01, ADR-001 | domain/application | 实现 TaskExecution、attempt/maxAttempts、parentExecutionId、优先级、notBefore、失败分类和状态机；分离业务状态、领取状态、等待原因与终态 | 状态机测试覆盖 READY、WAITING_RUNTIME、CLAIMED、PREPARING、RUNNING、WAITING、PAUSE/CANCEL 请求、PAUSED、RECOVERING、完成、失败和新尝试；非法回退与终态修改被拒绝 |
| `M3-D03` | TASK | D02, S02 | domain/application | 实现 StepExecution、PlanVersion、PlanStep、PolicySnapshot、SafetyEnforcementOverlay 版本引用、执行 Principal、检查点和 Todo 摘要；明确 Plan 候选、校验、发布、父版本和当前版本切换 | 测试覆盖 Step 串行状态、计划/策略版本不可变、父版本、快照 Hash、权限扩大重新生成快照、实时撤权收紧、Executor 闭合、检查点单调性、Todo 与 Plan 映射、失败重试和无 Step Lease 约束 |
| `M3-D04` | TASK | M2-I02 | domain/application | 实现 ExecutionRuntime、RuntimeWorker、RuntimeCapabilities、RuntimeProfile、Worker 状态、容量、支持能力和心跳事实 | 测试覆盖 Runtime/Worker 注册、唯一稳定 Key、能力匹配、容量上下界、启停、Drain、过期心跳和跨 Organization/环境隔离 |
| `M3-D05` | TASK | D02,D04,S01 | domain/application | 实现 ExecutionLease、ClaimReceipt、Claim Token/Fencing Token、Prepare/Run Lease、Heartbeat 和释放原因值对象 | 测试覆盖领取、续期、阶段切换、显式释放、过期、旧 Fencing Token、错误 attempt/Worker/Runtime、终态互斥和只存在 TaskExecution Lease |
| `M3-D06` | TASK | D04,D05 | domain/application | 实现 TaskCredentialGrant、TaskTokenClaims、JTI Hash、授权资源范围、签发、使用、撤销和过期生命周期 | 测试覆盖 Claims 闭合、最小范围、过期、撤销、错误 Lease/Worker/Binding/Tool/资源、重复 JTI 和安全字段不进入字符串输出 |
| `M3-D07` | TASK | D02,D03,D06 | domain/application | 扩展 TASK/STEP/SPECIALIST AgentRuntimeSession 绑定，并实现 AgentRun、AgentInterrupt、AgentStateSnapshot 元数据和 RuntimeArtifact 元数据；定义运行、流 Segment、中断、恢复、continuity gap 与终态 | 测试覆盖 Session 与 Task/Execution/Step/AgentProfile 闭合、同一 Step 多次 AgentRun、Run/Segment 序号、Pending Interrupt 唯一性、Resume 幂等、快照身份与 Hash、终态不可变、大结果仅保存 Artifact 引用 |
| `M3-D08` | TASK | D01..D07 | infrastructure | 新增 `V10__durable_task_runtime.sql`，建立 Task、链接、Execution、Step、Plan、PolicySnapshot、责任快照、Runtime、Worker、Lease、CredentialGrant、AgentRun、Interrupt、RuntimeArtifact、AgentStateSnapshot 表及 Task/Step AgentRuntimeSession 扩展 | PostgreSQL 测试覆盖空库 V1→V10、V9→V10、非默认 `search_path`、复合 Scope 外键、审计字段、状态约束、READY/Lease 索引、Plan/Policy 父版本、Session Scope、部分唯一索引和禁止 Step Lease |
| `M3-D09` | TASK | D08 | infrastructure | 实现 M3 JPA Entity、Mapper、Repository Adapter 和 Claim/Heartbeat/Sweeper JDBC Adapter；为队列、锁、Keyset 查询和条件更新提供明确 Port | Testcontainers 测试覆盖领域往返映射、JSONB/快照、乐观锁、队列排序、`SKIP LOCKED`、条件更新、Cursor、查询计划、跨 Scope 拒绝和事务回滚 |

V10 的数据库约束至少覆盖：

- Task 的 Organization、Team、Workspace、WorkProject、WorkItem、Conversation 和当前 TaskExecution Scope 一致；
- 同一 Task 的 attempt 唯一，同一 Task 只有一个当前有效 TaskExecution；
- 同一 TaskExecution 只有一个活动 Lease，Fencing Token 单调递增；
- 同一 Runtime 中 Worker 稳定 Key 唯一，Worker 能力与容量非负；
- Claim Token 和 Task Token 只保存 Hash，不保存明文；
- 同一 TaskExecution 的 PlanVersion 版本号、Step 序号、AgentRun 序号和 Snapshot 序号唯一；
- Pending AgentInterrupt、活动 TaskCredentialGrant 和当前有效 Snapshot 使用部分唯一约束；
- 业务事实保存创建/修改时间、创建/修改 Principal 和乐观锁版本；Lease、Heartbeat 等技术事实保存自身发生时间、状态与不可伪造 Owner 信息。

## 6. Runtime 与基础设施

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M3-I01` | TASK | D04,D09 | infrastructure/server | 实现 Runtime Registry、JVM RuntimeWorker 注册、能力发布、容量、Heartbeat、Drain 和过期判定；提供 `all` 与 `worker` Profile 的稳定 Worker Identity | Spring Context 与集成测试覆盖单实例、双实例、重启沿用 Worker Identity、能力变更、Heartbeat 失联、Drain 不领取新任务和 Profile 配置失败关闭 |
| `M3-I02` | TASK | D02,D04,D05,D09,I01 | application/infrastructure | 实现 Claim Scheduler：按优先级/notBefore 排队，匹配 RuntimeCapabilities、Team/Runtime 并发配额，在单事务中领取并返回一次性 Claim Token 与单调 Fencing Token | 并发集成测试覆盖公平排序、能力不匹配进入 WAITING_RUNTIME、配额、双 Worker 抢占、批量 Claim 上限、Claim Token 明文不落库、Fencing 单调性及低基数 Claim 指标 |
| `M3-I03` | TASK | I02,D05 | application/infrastructure | 实现 Prepare/Run Lease 续期、Heartbeat、显式释放、过期 Sweeper 和 Fencing 条件提交；Sweeper 先进入 RECOVERING，再执行对账决策 | 故障测试覆盖阶段 TTL、抖动容忍、Heartbeat 丢失、旧 Owner 回写、Complete/Sweeper 竞争、重复 Sweep、时钟来源和唯一恢复事件 |
| `M3-I04` | TASK | D06,D09,I03 | application/server | 实现 Task Token 签发、验证、轮换、撤销与请求中间件；从有效 Lease 和当前授权事实生成最小 Claims，并将 Principal/Scope 注入可信执行上下文 | 安全测试覆盖签名、JTI、expiry、audience、attempt、Runtime/Worker、ProviderBinding、Tool/资源范围、撤权即时生效、日志脱敏和无 Worker 长期凭证回退 |
| `M3-I05` | TASK | S02,D02,D03,D07 | application/agentscope | 在 ADR-010 的 Port 族扩展 Task Execution Request、Task Execution Handle、Pause/Resume/Cancel 和耐久事件协议；保留 Conversation Invocation 兼容 | 契约测试覆盖服务端事实闭合、单订阅有限流、流断开与业务控制分离、唯一终态、背压、错误分类、旧 M2 Conversation API 回归和 RuntimeCapabilities 精确披露 |
| `M3-I06` | TASK | I05,S02,D03 | agentscope/application | 实现 AgentScope Task Orchestrator、版本化 Task Agent Factory、Plan Mode/Todo 适配、计划校验与 PlanVersion 发布；M3 使用无外部副作用的受控 Step Fixture | 可控 Model 测试覆盖计划生成、非法计划修正、Todo 进度、Step 编排、预算、中断、恢复、取消、快照版本固定和禁止 Provider 写工具；Agent Todo 不直接修改领域事实 |
| `M3-I07` | TASK | D07,I05 | agentscope/application/infrastructure | 将 AgentScope 调用、事件 Segment、中断、Usage、Retry/Fallback、错误和终态映射为耐久 AgentRun/AgentInterrupt/RuntimeArtifact 与 DomainEvent | 集成测试覆盖原子序号、精确重放、重复事件吸收、同序号冲突失败、受控公开事件、敏感字段脱敏、提交失败不伪造终态和 M2 Invocation 到 M3 Run 的边界 |
| `M3-I08` | TASK | S03,D07,I07 | agentscope/infrastructure | 实现 AgentStateSnapshot Writer/Reader、Redis 重建、快照 Hash 与身份校验、Artifact 生命周期和 continuity gap；在安全点协调 Redis、Snapshot 与 PostgreSQL 检查点 | Redis/ArtifactStore 故障测试覆盖定期/中断/关闭快照、Redis 清空、损坏和缺失快照、回退最近完整版本、跨 Task 注入拒绝、并发写裁决和清理 Tombstone |
| `M3-I09` | FEATURE | I01..I08 | infrastructure/server | 实现 JVM Worker 执行循环和启动对账：Claim、Prepare、Token、Run、Heartbeat、Checkpoint、Complete/Fail；支持 `all` Profile 与独立 `worker` Profile | 端到端测试在两个进程拓扑运行受控任务，覆盖正常完成、优雅关闭、CLAIMED/PREPARING/RUNNING 退出、启动扫描、租约接管、无孤立 Run/Step 和 Actuator 健康状态 |

M3 Runtime 只运行受控的计划与步骤 Fixture，用于验证耐久调度和 AgentScope 恢复。真实仓库访问、Shell、文件修改、Git、Sandbox 与 Coding Specialist 在 M4 接入。

## 7. 应用、API 与服务端

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M3-A01` | FEATURE | D01,D02,D09 | application/server | 实现“交给 Agent 处理”：从可见 WorkItem 和可选 Conversation 原子创建 Task、首个 TaskExecution、责任快照、初始 PolicySnapshot 和链接；固化目标、验收标准、执行 Principal、来源消息/WorkItem 版本与授权证据 | 应用/API 测试覆盖 Owner/Executor 权限、Scope、责任和 ProviderBinding 当前事实、`Idempotency-Key`、Request Hash、重复提交、事务回滚、DomainEvent/Outbox/Audit，以及无有效 Executor 或策略闭合失败时失败关闭 |
| `M3-A02` | TASK | A01,D03,D07,D09 | application/server | 实现 Task 集合、详情、TaskExecution attempts、Step、PlanVersion、AgentRun、Interrupt、Snapshot 摘要和 Runtime facts 查询 API；使用 Keyset Cursor 与可见性策略 | API 测试覆盖 Team/成员可见性、状态筛选、稳定排序、Cursor、当前/历史 attempt、敏感 Token/内部 State 不披露、跨 Scope 404 和查询数量上界 |
| `M3-A03` | TASK | I02,I03,I04 | application/server | 实现受信 Worker Command Port：Claim、Prepare、Start、Heartbeat、Progress、Complete 和 Fail；每个 mutation 校验 attempt、Claim/Fencing Token、expectedVersion 与 Task Token | 契约测试覆盖合法全链路、旧 Token、错误 Worker、Lease 失效、版本冲突、重复终态、Body/Header 伪造、幂等回执、Audit 和统一安全错误码；外部用户路由不可调用 |
| `M3-A04` | FEATURE | A03,I05,I08 | application/server | 实现成员 Pause、Resume、Cancel 和 Retry 命令；请求态传播到 Worker/AgentScope，在安全点提交，Retry 创建新 attempt 并重新校验责任与授权 | API/集成测试覆盖角色权限、强 ETag、幂等、等待/运行/终态、重复命令、取消与完成竞争、暂停后恢复、失败重试、maxAttempts 和当前执行切换 |
| `M3-A05` | TASK | A02,I07 | application/server | 实现 Task Event 耐久历史、统一公开事件映射和 SSE Cursor；关联 Task、Execution、Step、AgentRun、Lease、控制命令和恢复事实 | API 测试覆盖历史追平、断线补发、Last-Event-ID、跨流 DomainEvent 去重、投影缺口、慢订阅者上限、权限持续复验、终态关闭和内部 Token/Reasoning 不披露 |
| `M3-A06` | TASK | A01,A02 | application/server | 实现 WorkItem/Conversation/Task 双向关联查询与对象级深链接；按每个对象当前可见性过滤反向结果 | API 测试覆盖一个 WorkItem 多 Task、一个 Conversation 多 Task、PRIVATE Conversation 隐藏、已取消/历史 Task、关联 Cursor、跨 Team 隔离和无 N+1 查询退化 |
| `M3-A07` | TASK | I01,I09 | application/server | 实现 Runtime/Worker 健康、能力、容量和等待原因查询；提供面向成员的安全摘要与面向运维的受权明细 | API、Security 与 Actuator 测试覆盖健康/失联/Drain、容量、WAITING_RUNTIME 原因、角色差异、低基数指标、Trace/Audit 关联和配置敏感信息不披露 |

所有用户命令继续使用 `Idempotency-Key`、当前身份、当前 Scope 和显式 expected version。所有 Worker 命令只从 Task Token 与服务端 Lease 解析执行身份，不能借用浏览器 Session。

## 8. 前端

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M3-F01` | TASK | A02,A06 | web | 建立 Task Gateway、类型、Store、路由与 Scope 隔离；支持列表、详情、attempt、事件 Cursor 和关联对象缓存 | Vitest 覆盖 DTO 映射、过期请求隔离、Scope 切换、深链接恢复、Cursor、错误信封、缓存失效和内部安全字段不进入前端类型 |
| `M3-F02` | FEATURE | F01,A01 | web | 在 Control Mode 交付 Task 列表、状态/负责人筛选、当前 attempt、等待原因和 WorkItem“交给 Agent 处理”入口 | 组件与 E2E 覆盖创建、幂等重试、责任预览、列表刷新、WorkItem/Conversation 深链接、无权限隐藏、Loading/Empty/Error 和窄屏列表 |
| `M3-F03` | FEATURE | F01,A02,A07 | web | 交付 Task 详情抽屉/页面：Task、责任快照、当前与历史 attempt、Plan、Step、AgentRun、Lease 和 Runtime 安全事实 | 组件测试覆盖状态语义、attempt 切换、计划版本、步骤进度、Worker 失联、WAITING_RUNTIME、敏感字段缺失、桌面双栏和窄屏顺序阅读 |
| `M3-F04` | FEATURE | F01,A01,A05 | web | 在 Conversation Mode 交付 Task 卡片和实时状态；从 TaskIntent/WorkItem 进入 Task，并提供 Conversation、WorkItem、Task 双向跳转 | E2E 覆盖创建后卡片出现、刷新恢复、多个 Task、PRIVATE 可见性、运行/等待/终态更新、对象跳转和对话内容不被瞬时流覆盖 |
| `M3-F05` | FEATURE | F03,A04 | web | 交付 Pause、Resume、Cancel、Retry 控件、影响说明和冲突事实刷新；仅在状态与权限允许时展示操作 | Vitest/E2E 覆盖请求态、重复点击、离线、409/412、终态竞态、取消焦点恢复、Retry 新 attempt、只读成员和无乐观伪更新 |
| `M3-F06` | FEATURE | F03,A05 | web | 交付 Task Timeline、实时 Progress、恢复标识和 SSE Cursor 续传；合并 Task Event 与受控 AgentRun 公开事件 | 测试覆盖去重、乱序、断线重连、历史追平、慢流降级、RECOVERING/continuity gap、终态收口、ARIA Live 节流和 Reduced Motion |
| `M3-F07` | HARDENING | F02..F06 | web | 完成 Task 页面 Loading、Empty、Error、Forbidden、Conflict、Offline、Reconnecting、Cancelled、Recovering 状态，以及桌面/窄屏、键盘、Axe 和视觉回归 | Vitest 覆盖率达门槛；Playwright 覆盖 Conversation/Control 双入口；Axe 无严重问题；关键桌面与窄屏截图通过；文档记录视觉来源和 CrewScope 差异 |

M3 UI 展示任务、计划、步骤、运行、租约、等待与恢复事实。界面不展示 M4 才存在的 Diff、Worktree、Sandbox、文件修改或真实代码执行结果，也不展示 Claim Token、Task Token、原始 AgentState 和内部 Reasoning。

## 9. 测试、质量与发布

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M3-Q01` | HARDENING | D06,I04,A01..A07 | all | 完成 Task/Worker API 越权、Task Token、撤权传播、Prompt 信任分区、资源与并发预算、日志/事件/Artifact 脱敏和依赖安全检查 | 固定攻击集证明跨 Organization/Team/Task/attempt/Runtime/Binding/Tool/资源阻断率 100%；撤权后旧 Token 立即失效；Token、Claim、State、Reasoning 和敏感 Provider 数据泄漏数为 0 |
| `M3-Q02` | HARDENING | I03,I08,I09,A04,A05 | all | 完成故障注入：进程在 CLAIMED/PREPARING/RUNNING 退出、Complete/Sweeper 竞争、Heartbeat 丢失、Redis 丢失、Snapshot 损坏、事件断线和重复控制命令 | 受控故障样本证明唯一终态、旧 Owner 无回写、无孤立 Step/Run、Redis 可从二级快照恢复、重复外部写操作为 0；记录样本量、超时、恢复率和 Artifact |
| `M3-Q03` | HARDENING | Q01,Q02,F07 | all/docs/ci | 执行 M3 Release Gate，审查领域、迁移、Spring 装配、Runtime、API、前端、文档与 M2 回归；形成版本化验收报告 | `./mvnw clean verify`、前端 test/coverage/build、Playwright、Axe、视觉、V1→V10 与 V9→V10 迁移、文档链接和格式检查全部通过；可重复演示创建、领取、暂停、恢复、取消、重试与故障接管 |

## 10. Release Gate

M3 完成需要同时满足：

1. 两个 Worker 并发 Claim 同一 TaskExecution 时只有一个成功；
2. Heartbeat 停止后 Lease Sweeper 将执行收敛到 RECOVERING，并由有效 Worker 接管或进入明确终态；
3. Complete 与 Sweeper 竞争只产生一个有效终态；
4. 失效 Lease、旧 Claim Token 或旧 Fencing Token 不能提交任何 Step、AgentRun 或 TaskExecution 结果；
5. Retry 创建新 attempt，旧 attempt 的终态、事件和证据保持不变；
6. Task Token 不能访问其他 Organization、Team、Task、attempt、Runtime、ProviderBinding、Tool 或资源；
7. TeamMember、ProviderBinding、ConnectionGrant 或责任撤销后，现有执行权限立即收紧；
8. 进程在 CLAIMED、PREPARING 和 RUNNING 退出后均能自动收敛，不产生孤立 Step 或 AgentRun；
9. Redis 丢失后可从 AgentStateSnapshot 与 PostgreSQL 检查点恢复；无法精确续接时有明确 continuity gap；
10. 成员可以从 Conversation Mode 和 Control Mode 查看同一 Task 事实，并完成暂停、恢复、取消和重试；
11. 页面断线后可通过 Task Event Cursor 补发，重复或乱序事件不造成状态回退；
12. M2 Conversation、Personal Agent、TaskIntent、WorkItem 与 Provider Binding 回归测试全部通过；
13. M3 没有创建 ExecutionWorkspace、执行真实 Provider 写操作或暴露内部 Token/State；
14. 后端、前端、迁移、故障、安全、Axe、视觉和文档门禁全部通过。

## 11. 开工与提交顺序

推荐按以下节点实施和审查：

1. `M3-S01` 至 `M3-D09`：冻结状态、责任快照、租约、Token 与数据契约；
2. `M3-I01` 至 `M3-I04`：完成 Runtime、Claim、Lease 和 Task Token 基础设施；
3. `M3-I05` 至 `M3-I09`：完成 AgentScope Task Runtime、AgentRun、Snapshot 与 Worker；
4. `M3-A01` 至 `M3-A07`：完成创建、查询、控制、事件和运行健康 API；
5. `M3-F01` 至 `M3-F07`：完成 Conversation/Control 双入口；
6. `M3-Q01` 至 `M3-Q03`：完成安全、故障与 Release Gate。

每个节点先整体 Review，修正文档和实现后再提交。任务完成证据保存到 `docs/spikes` 或 `docs/testing`，文件名以任务 ID 开头。
