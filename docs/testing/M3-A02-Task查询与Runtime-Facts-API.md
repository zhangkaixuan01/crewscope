# M3-A02 Task 查询与 Runtime Facts API

## 目标

为团队成员提供 Task 集合、Task 详情、当前/历史 attempt 和单 attempt 耐久运行事实查询，作为 Control Mode 和后续 Task 详情页的权威读取边界。

## 已交付 API

- `GET /api/v1/organizations/{organizationId}/teams/{teamId}/tasks`：按 `updatedAt DESC, id DESC` 稳定分页，支持 `projectId`、`status`、`after` 和 `limit`；Cursor 同时绑定 Organization、Team、Project 和 Status，不能跨集合重放；
- `GET /api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}`：返回 Task、来源、责任快照和全部有界 attempt 摘要；
- `GET /api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts`：按 attempt 顺序返回当前与历史尝试；
- `GET /api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/runtime-facts`：返回选定 attempt 的 Execution、PlanVersion/PlanStep/Todo、StepExecution/checkpoint、Task Agent Session、AgentRun/Segment/continuity gap、Interrupt、Snapshot 元数据和 Lease/Runtime/Worker 安全摘要。

列表、详情和 Runtime Facts 返回 `Cache-Control: no-store`。Task 详情与 Runtime Facts 分别使用 Task/TaskExecution 领域版本生成强 ETag。

## 可见性与安全边界

- 无 Project 筛选时复验 ACTIVE Team Membership，有 Project 筛选时同时复验 Project 归属和可见性；
- Task 必须归属 URL 指定的 Organization/Team，TaskExecution 必须归属该 Task 且具有相同 WorkItemScope；
- 跨 Team Task、其他 Task 的 execution 和 Scope 不闭合的持久化事实按不可见处理；
- HTTP DTO 使用显式白名单，不序列化 Claim Token/Hash、Fencing Token、Task Token、AgentScope userId/sessionId、stateReference、Snapshot contentHash/原始 State、Interrupt Token Hash、Resume responseHash 和内部 Policy/Safety Hash；
- Runtime/Worker 仅披露具体 attempt 已绑定的 ID、环境、阶段、时间与释放状态，全局健康、能力和容量由 M3-A07 提供。

## 查询与持久化

- Task 列表的当前 Membership/Project 授权检查与轻量投影读取位于同一事务；投影只联接 Task 与当前 TaskExecution，不重建责任快照；
- Runtime Facts 为每类子事实调用一次 execution 级 Repository；
- PlanVersion 在固定三次查询中批量读取 Plan parent、PlanStep 和 Todo；
- AgentRun 批量读取所有 Run Segment，查询数量不随 Run 数量增长；
- V12 增加 Team/Project/Status/updatedAt Task Keyset 索引，以及 execution 级 Interrupt、Snapshot 和 Lease 历史索引。

## 验证

- `TaskQueryServiceM3A02Test`：成员/Project 可见性、筛选和 Cursor 传递、跨 Scope 关闭、历史 attempt 与固定批量 Repository 调用；
- `TaskListCursorCodecTest`：不透明 Cursor 往返、Organization/Team/Project/Status 重放拒绝和非规范/未知版本拒绝；
- `TaskQueryControllerTest`：列表、详情、attempt、Runtime Facts、ETag、`no-store`、路由参数和敏感字段不披露；
- `M3TaskRuntimePersistenceIntegrationTest`：真实 PostgreSQL 列表分页/筛选/当前 attempt 联接、跨 Team 空结果、Plan/Run 批量子事实、Interrupt/Snapshot/Lease 历史与七个索引；
- `V10DurableTaskRuntimeMigrationIntegrationTest`：真实 PostgreSQL V11→V12 升级并验证新索引。

## 下一项

`M3-A03` 已完成：受信 Worker Command Port 已提供 Claim、Prepare、Start、Heartbeat、Progress、Complete 和 Fail 命令边界。下一项为 `M3-A04` 成员 Pause、Resume、Cancel 和 Retry 命令。
