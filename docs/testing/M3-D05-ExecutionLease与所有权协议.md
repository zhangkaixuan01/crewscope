# M3-D05 ExecutionLease 与所有权协议

> 日期：2026-08-13<br>
> 状态：已完成<br>
> 适用范围：TaskExecution Claim、ExecutionLease、Claim Token、Fencing Token 与 Application Persistence Port

## 1. 目标

M3-D05 建立单个 Worker 对一次 TaskExecution attempt 的有期、可防护所有权，固定 Claim、准备、运行、续租、阶段切换、显式释放和过期终止协议。MVP 只存在 TaskExecution Lease，StepExecution 继承所属 TaskExecution 的同一所有权。

## 2. 领域对象

本阶段增加：

- `ExecutionLease`、`ExecutionLeaseId`、`ExecutionLeasePhase`；
- `ClaimToken`、`ClaimTokenHash`、`ClaimReceipt`；
- `FencingToken`、`LeaseOwnership`；
- `ExecutionLeaseRelease`、`ExecutionLeaseReleaseReason`；
- `ExecutionLeaseRepository` Application Port。

`ExecutionLease` 固化 Organization、Environment、TaskExecution、attempt、Runtime、Worker、Claim Token Hash、Fencing Token、Phase、时间线、释放事实和独立 Lease Version。

## 3. Claim Token 与一次性回执

- Claim Token 使用 43–128 位 Base64URL 安全字符，持久化前转换为 SHA-256 Hash。
- Claim Token 和 Hash 的字符串表示默认脱敏。
- 明文只在 Claim 成功后进入一次性 `ClaimReceipt`，回执的 `toString()` 不披露明文。
- Lease 通过 Claim Token Hash 校验回执和后续所有权，不保存明文。

## 4. Fencing Epoch

TaskExecution 保存 `lastFencingToken`，它是当前已提交所有权纪元的唯一事实源。

```text
首次 READY -> CLAIMED    Fencing Token = 1
恢复后重新 Claim       Fencing Token = last + 1
Heartbeat / Phase 切换    Fencing Token 不变
```

`ExecutionLease.acquire()` 只能复制 TaskExecution 当前纪元，不接受外部分配的 Fencing Token。`CLAIMED/PREPARING/RUNNING/PAUSE_REQUESTED/RECOVERING` 重建时必须存在 Fencing Token；返回 READY 后保留历史纪元，为下次 Claim 单调递增提供基准。

## 5. 完整所有权坐标

每个 Worker mutation 使用 `LeaseOwnership` 校验：

```text
TaskExecutionId
+ attempt
+ ExecutionRuntimeId
+ RuntimeWorkerId
+ ClaimTokenHash
+ FencingToken
```

任一坐标不一致、Lease 已释放、Lease 已过期或 Lease Version 冲突均失败关闭。

## 6. PREPARE 与 RUN Lease

- Claim 首先创建 `PREPARE` Lease，单次 TTL 为 5 秒至 15 分钟。
- TaskExecution 进入 RUNNING 后才能切换为 `RUN` Lease，单次 TTL 为 5 秒至 10 分钟。
- Heartbeat 只更新 `lastHeartbeatAt`、`expiresAt` 和 Lease Version。
- Heartbeat 不更新 TaskExecution Version，不更改 Runtime、Worker、Claim Token Hash 或 Fencing Token。

Lease 和 TaskExecution 版本分离后，周期性续租不与 Step 检查点、执行进度或终态更新产生无意义的乐观锁冲突。

## 7. 过期与释放

过期使用权威时钟和精确边界：

```text
authoritativeNow >= expiresAt
```

`EXPIRED` 只能由 `expire()` 提交。显式释放原因为 `COMPLETED`、`FAILED`、`CANCELLED`、`PAUSED`、`WAITING`、`MANUAL_TAKEOVER` 和 `WORKER_SHUTDOWN`，必须与 TaskExecution 状态一致。显式释放和过期释放是互斥终态，只能提交一个。

## 8. Application Port 与原子边界

`ExecutionLeaseRepository` 固定以下语义：

- `acquire` 原子提交 TaskExecution `READY -> CLAIMED`、Fencing Token 递增与唯一活动 PREPARE Lease；
- `renew` 使用完整所有权坐标和 Lease Version 续租；
- `switchPhase` 使用 TaskExecution 与 Lease 条件切换 PREPARE-to-RUN；
- `release` 原子提交 TaskExecution 结果与 Lease 释放事实；
- 查询端只提供 Lease ID、TaskExecution 活动 Lease 和过期候选查询。

Port 不提供通用 Lease Update，防止 Adapter 绕过续租、阶段切换和释放的条件语义。数据库原子实现、唯一活动 Lease 约束和 Sweeper 并发适配由 M3-D08、M3-D09 和 M3-I03 落地；M3-S01 已用 PostgreSQL 验证该协议。

## 9. 验证

M3-D05 新增 18 个专项测试：

- `ExecutionLeaseTest`：13 个；
- `ClaimTokenAndFencingTokenTest`：3 个；
- `TaskExecutionTest` Fencing 专项：2 个。

覆盖领取、Claim Token 脱敏与 Hash、Fencing Token 单调递增、一次性回执、阶段切换、心跳与版本分离、TTL 边界、重建时间线、完整 Ownership 坐标、错误 TaskExecution/Fencing Epoch、精确过期边界、显式释放、释放/过期互斥与旧 Owner 失效。

专项 Reactor 验证：

```text
crewscope-domain       293 tests passed
crewscope-application  178 tests passed
```

最终全仓回归：

```text
7 Maven modules successful
814 tests passed, 0 failures, 0 errors, 0 skipped
111 Markdown files passed link validation
git diff --check passed
```

相关决策和前置验证：

- [ADR-001：Task、TaskExecution、StepExecution 与 Lease](../adr/ADR-001-执行状态与租约.md)；
- [M3-S01 PostgreSQL 领取与租约验证记录](../spikes/M3-S01-PostgreSQL领取与租约验证记录.md)；
- [M3 耐久 Task Runtime 执行清单](../plans/M3-耐久Task-Runtime.md)。
