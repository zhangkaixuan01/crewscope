# M3-A04 成员 Task 控制与重试

## 目标

交付成员面向当前 Task attempt 的 Pause、Resume、Cancel 和 Retry 闭环，将用户意图变成可审计的耐久事实，再由持有当前 Lease/Fencing 所有权的 Worker 传播到 AgentScope 安全点。

## API 契约

- `POST /api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/pause`；
- `POST .../resume`；
- `POST .../cancel`；
- `POST .../retry`。

所有命令要求 `Idempotency-Key`和 TaskExecution 强 `If-Match`。Pause/Cancel 只接受有界 `reason`，Resume/Retry 不接受 Body，避免客户端伪造 Worker、调度、尝试次数或授权事实。响应使用统一 Command Receipt 并设置 `Cache-Control: no-store`。

## 责任、并发与审计

- 请求者必须是当前 USER、ACTIVE Team Member，并持有 WorkItem 当前 Owner 或 Executor 责任；
- 应用服务先复验对象可见性，再按 Task、TaskExecution 的固定顺序获取悲观写锁；
- Route Task/attempt、WorkItem Scope、Task 当前 attempt 和 `If-Match` Version 必须同时一致；
- mutation、`MEMBER_TASK_*_ACCEPTED` DomainEvent、Outbox 和 CommandReceipt 在同一事务提交；
- 同一 Idempotency Key 与相同请求 Hash 只返回原 Receipt，不重复变更、中断或新建 attempt。

## Pause、Cancel 与终态竞争

RUNNING Pause/Cancel 先在 TaskExecution 上提交 `PAUSE_REQUESTED/CANCEL_REQUESTED`。Worker 在 Lease Heartbeat 成功后读取最新执行状态，将耐久请求转换为 `TaskExecutionRuntime.controlTask`。Control Request ID 由 Execution ID 和不可变请求事实稳定派生，重试 Heartbeat 不会产生新中断。

AgentScope Pause 终态使用该 Control Request ID 作为 Interrupt Token，数据库继续只保存 SHA-256。已接受的 Pause/Cancel 与稍后到达的 Completed 终态竞争时，Worker 以最新耐久请求为准，分别释放为 PAUSED/CANCELLED。CREATED、READY、WAITING、PAUSED 和 RECOVERING 没有活动 Worker，Cancel 在应用事务内直接收敛为 CANCELLED，并同时关闭 Task。

## Resume 与 Retry

Resume 只允许 PAUSED 当前 attempt。服务解析同一 AgentRun 的当前 Pause Interrupt，用稳定 Control Request ID 重建原始 Interrupt Token，通过 `DurableAgentRunResumeService` 完成 Hash 验证并打开 RESUME Segment，然后将原 attempt 放回 READY。新 Worker Claim 在 `executeTask` 前先调用 `controlTask(RESUME)`，保证 AgentScope 恢复授权与耐久 Segment 一致。

Retry 只允许当前可重试 FAILED attempt，并遵守 `maxAttempts`。创建后继 attempt 前重新验证 Executor Assignment ID/Version/Principal、Executor Principal、AgentProfile 状态/版本/归属，以及每个 ProviderBinding 的 Definition、Implementation、Connection 和 ConnectionGrant 当前事实。通过后创建 `attempt + 1`、新 PolicySnapshot 和 SafetyEnforcementOverlay，继承已批准的优先级、PolicyPack、能力、Tool、Binding 和预算，发布 READY 并切换 Task 当前 attempt。

## 验证

- `MemberTaskCommandServiceM3A04Test`：7 个应用层测试覆盖 Owner 命令、即时 Cancel、Pause/Resume Token、Retry 后继 attempt、maxAttempts、责任变更、无权、版本冲突和幂等重放；
- `TaskCommandControllerM3A04Test`：4 个 HTTP 测试覆盖四类路由、强 `If-Match`、幂等键、Reason 校验、空 Body 和非法 ID；
- `ProviderBindingResolverTest`：2 个当前事实测试覆盖显式 Binding 复验、Connection 停用、Grant 撤销、Registry 变更和跨 Organization 拒绝；
- `AgentScopeTaskRuntimeM3I06IntegrationTest`：固定 Pause payload Token 精确等于传入 Control Request ID；
- `DurableTaskWorkerExecutionHandlerM3I09Test`：3 个新增 Worker 测试固定 Heartbeat Pause 传播、Resume-before-execute 顺序和 Cancel-wins-Complete 竞争。

M3-A04 不需要新数据库迁移，复用 M3-D02、M3-D07 已持久化的控制请求、AgentRun Segment 和 AgentInterrupt 事实。

## 下一项

`M3-A05`：实现 Task Event 耐久历史、统一公开事件映射和 SSE Cursor。
