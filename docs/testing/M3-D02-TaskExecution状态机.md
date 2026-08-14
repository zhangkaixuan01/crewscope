# M3-D02：TaskExecution 状态机

> 日期：2026-08-13<br>
> 状态：已完成<br>
> 适用范围：M3 TaskExecution 领域模型与 Application Repository Port

## 1. 目标

建立 Task 的耐久执行尝试模型，固定执行状态、等待原因、暂停与取消请求、调度顺序、失败分类和重试链，为 M3-D03 Step/Plan、M3-D05 Lease、M3-D08 数据表和 M3-I02 Claim Scheduler 提供稳定契约。

## 2. 聚合边界

`TaskExecution` 表示同一 Task 的一次执行尝试，保存：

```text
TaskExecutionId / TaskId / WorkItemScope
attempt / maxAttempts / parentExecutionId
priority / notBefore
status / waiting / controlRequest / terminal
version / AuditMetadata
```

Task 保存业务生命周期和当前有效尝试引用。TaskExecution 保存调度与运行生命周期。ExecutionLease、Runtime、Worker、Claim Token 和 Fencing Token 由 M3-D04/D05 接入，不提前进入本聚合。

## 3. 状态契约

主状态为：

```text
CREATED -> READY -> CLAIMED -> PREPARING -> RUNNING -> COMPLETED
READY -> WAITING(RUNTIME) -> READY
RUNNING -> WAITING(reason) -> READY
RUNNING -> PAUSE_REQUESTED -> PAUSED -> READY
CLAIMED / PREPARING / RUNNING -> RECOVERING -> READY
允许取消的非终态 -> CANCEL_REQUESTED -> CANCELLED
RUNNING / WAITING -> MANUAL_TAKEOVER -> COMPLETED / FAILED / CANCEL_REQUESTED
RUNNING / RECOVERING / MANUAL_TAKEOVER -> FAILED
```

`WAITING` 只保存一个独立原因：`RUNTIME`、`COLLABORATION`、`REVIEW`、`CONFIRMATION`、`USER_INPUT`、`EXTERNAL_EXECUTION`、`EVENT` 或 `MANUAL`。等待事实只在 WAITING 存在，终态事实只在 COMPLETED、FAILED、CANCELLED 存在。

暂停和取消分为请求与收敛两步。请求事实保存请求类型、Principal、时间和规范化原因。Worker 到达安全点后提交 PAUSED 或 CANCELLED。终态不可逆，任何状态回退或终态修改均由聚合拒绝。

## 4. 调度与重试

调度优先级使用 `0..100`，默认值 50，与 WorkItem 产品优先级分离。`notBefore` 不得早于尝试创建时间；Claim 时必须已经到达该时间。READY 队列最终按 priority、notBefore 和稳定 ID 排序，由 M3-I02/D09 落地查询与并发领取。

首个尝试固定为 attempt 1、无 parent，只能为尚未绑定执行的 CREATED Task 创建。Retry 同时要求：

- Parent 是该 Task 当前指向的 FAILED 尝试；
- 失败类别允许重试；
- 新 attempt 恰好等于 parent attempt + 1；
- 新尝试继承 maxAttempts，且不超过 100；
- Parent 与新尝试属于相同完整 Scope 和 Task；
- 历史失败尝试不能再次分叉。

安全失败只保存稳定分类和大写错误码。分类区分瞬时、限流、超时、Runtime/Model/Tool 不可用、资源耗尽、恢复中断，以及 Validation、Authentication、Authorization、Policy、Capability、Not Found、Conflict 和 Internal 等不可重试失败。原始异常消息、Provider 响应和凭证不进入领域事实。

## 5. Application Port

`TaskExecutionRepository` 提供：

- version 0 创建；
- 使用前一版本作为乐观锁谓词的更新；
- Organization Scope 下按 ID 查询；
- 按 Task 返回 attempt 升序历史。

JPA Adapter、队列 SQL 和复合 Scope 约束分别在 M3-D08/D09 实现。

## 6. 验证

`TaskExecutionTest` 的 14 个专项测试覆盖：

- 首次创建、完整 Scope、attempt 和 maxAttempts；
- READY、CLAIMED、PREPARING、RUNNING、COMPLETED 主链；
- WAITING(RUNTIME) 与运行中显式等待原因；
- Pause 请求、安全点确认和重新排队；
- Cancel 请求、终态确认和审计事实；
- CLAIMED、PREPARING、RUNNING 进入 RECOVERING；
- 可重试失败、不可重试失败、预算耗尽和历史分叉拒绝；
- notBefore、调度修改、版本冲突、非法回退和终态不可变；
- 重建时等待、控制请求、终态和 Parent 形状校验；
- 优先级范围、尝试预算和安全错误码格式。

专项验证：

```text
Domain tests       231 passed
Application tests  178 passed
Failures              0
Errors                0
Skipped               0
```

M3-D03 在本契约上增加 StepExecution、PlanVersion、PolicySnapshot、Executor 和检查点。M3-D05 将 Claim、Lease 与 Fencing 所有权绑定到 CLAIMED/PREPARING/RUNNING/RECOVERING 迁移。
