# M3-D08 V10 耐久 Task Runtime 数据迁移

## 结论

M3-D08 已完成。`V10__durable_task_runtime.sql` 将 M3-D01 至 M3-D07 的领域契约固化为 PostgreSQL 关系事实，并保持 V1–V9 前向升级兼容。

## 数据模型

V10 新增：

- `task_responsibility_snapshot` 和 `task_responsibility_snapshot_entry`；
- `task`、`conversation_task_link` 和 `task_execution`；
- `policy_snapshot`、`safety_enforcement_overlay`、`plan_version`、`plan_step`、`plan_todo_summary` 和 `step_execution`；
- `execution_runtime`、`runtime_worker` 和 `execution_lease`；
- `task_credential_grant`、`task_credential_grant_tool` 和 `task_credential_grant_provider`；
- `agent_run`、`agent_run_segment`、`agent_interrupt`、`runtime_artifact` 和 `agent_state_snapshot`。

V10 扩展 `agent_runtime_session`，使用 `session_purpose` 区分 `PERSONAL/TASK/STEP/SPECIALIST`。既有 Personal Session 自动回填通用 Agent Principal、Principal Type 和 Profile Type。Personal 形状与 Task-side 形状由 Check Constraint 保持互斥。

现有 Personal Session JPA 写入路径同步写入旧 Personal 绑定列与 V10 通用 Agent 身份列，保证升级后的新会话创建、并发初始化和读取链路继续可用。

AgentRun Segment 使用独立子表持久化。Segment 表保存序号、`INVOKE/RESUME/RECOVERY` 类型、Interrupt 来源、有限流状态和开始/结束时间，支持 M3-I07 的事件重放与中断恢复。

## 数据库裁决

- Task、Execution、Step、Plan、Policy、Session、Run、Artifact 和 Snapshot 使用 Organization、Team、Workspace、WorkProject、Task 与 TaskExecution 复合外键；
- `task_id + attempt`、Plan/Policy Revision、Step Sequence、Run Sequence、Snapshot Sequence 和 Checkpoint Sequence 使用唯一约束；
- 单活动 ExecutionLease、TaskCredentialGrant、Task-side Session、AgentRun、Pending Interrupt 和 Current Snapshot 使用部分唯一索引；
- TaskExecution Priority 的数据库约束与领域模型统一为 `0..100`；`ix_task_execution_ready_queue` 固定 READY 领取顺序，`ix_execution_lease_expiry` 支持 Lease Sweeper；
- Runtime 使用 Organization + Environment + Runtime Key 唯一，Worker 使用 Runtime + Stable Key 唯一；
- Worker 容量、状态、Heartbeat Sequence、Lease 时间线、Snapshot 大小和所有领域状态使用 Check Constraint；
- TaskExecution 在 `CLAIMED/PREPARING/RUNNING/PAUSE_REQUESTED/RECOVERING` 状态必须持有正数 Fencing Token，其他状态允许保留最近一次正数 Token；
- Claim Token、Interrupt Token 和 Task Token JTI 只保存 64 位小写 SHA-256 Hash；
- RuntimeArtifact 和 AgentStateSnapshot 只保存元数据，不保存模型结果、Tool 结果或 AgentState 正文；
- 数据库不创建 Step Lease 表。

## 审计与技术事实

Task、TaskExecution、StepExecution、PlanVersion、PolicySnapshot、Runtime、Worker、CredentialGrant、AgentRun、Interrupt、RuntimeArtifact 和 AgentStateSnapshot 保存创建时间、修改时间、创建 Principal、修改 Principal 和乐观锁版本。

ExecutionLease 保存 `acquired_at`、`last_heartbeat_at`、`expires_at`、Phase、Owner 全坐标和独立 `lease_version`。RuntimeWorker 保存 Heartbeat 时间与单调 Sequence。周期性 Heartbeat 不修改 TaskExecution Version。

## 自动化验证

`V10DurableTaskRuntimeMigrationIntegrationTest` 提供 6 个真实 PostgreSQL 场景：

1. 空库创建 V10 表、字段、约束与索引；
2. 已有 WorkItem、Responsibility、Conversation 和 Personal Session 的 V9 数据升级到 V10 后保持不变；
3. 非默认 `search_path` 仍只在 `crewscope` Schema 迁移；
4. Task Scope、attempt、状态、活动所有权 Fencing Token、Policy 父版本和 Plan 父版本跨 Execution 引用被拒绝；
5. Worker 容量、单活动 Lease、单活动 CredentialGrant 和 Session 形状被数据库裁决；
6. 单活动 AgentRun、Segment Sequence、Pending Interrupt 和 Current Snapshot 的部分唯一约束生效。

`FlywayMigrationIntegrationTest` 继续动态覆盖空库至 latest、V1 至 latest 以及非默认 `currentSchema/search_path`。

最终验证结果：

- V10 与通用 Flyway 专项测试：9 个测试通过；
- Personal Session JPA 向后兼容测试：21 个测试通过；
- Maven 全仓回归：7 个模块、845 个测试通过，0 Failure、0 Error、0 Skipped；
- 文档链接检查：114 个 Markdown 文件通过；
- `git diff --check` 与新增文件尾随空白检查通过。

## 后续边界

M3-D09 已在 V10 上实现 JPA Entity、Mapper、Repository Adapter 以及 Claim/Heartbeat/Sweeper JDBC Adapter。V10 只定义持久化契约，不在迁移中实现 Worker 调度循环；该循环从 M3-I01 开始交付。
