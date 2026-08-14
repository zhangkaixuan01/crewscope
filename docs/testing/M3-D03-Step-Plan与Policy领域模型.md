# M3-D03：Step、Plan 与 Policy 领域模型

> 日期：2026-08-13<br>
> 状态：已完成<br>
> 适用范围：M3 StepExecution、PlanVersion、PolicySnapshot、SafetyEnforcementOverlay 与 Application Repository Port

## 1. 目标

建立 TaskExecution 的可审计执行计划和串行 Step 模型，固定 AgentScope 计划候选进入领域后的校验、版本发布、策略快照、实时撤权、执行 Principal、Todo 摘要和检查点契约。

## 2. AgentScope 映射

AgentScope `plans/PLAN.md` 和 TodoTools 输出进入防腐层后形成 `ProposedPlan` 与 Todo 候选。领域层不依赖 AgentScope 类型。

```text
AgentScope Plan/Todo
  -> ProposedPlan + TodoSummaryItem
  -> 当前 PolicySnapshot ∩ SafetyEnforcementOverlay
  -> PlanVersion
  -> StepExecution
```

`ProposedPlan` 保存规范化 Markdown、内容 SHA-256 和候选 Step。`PlanVersion` 是校验并发布后的不可变领域事实。Todo 是运行时进度摘要，不拥有 Step 状态迁移权。

## 3. 版本事实

### 3.1 PolicySnapshot

PolicySnapshot 为一次 TaskExecution 固化：

- Executor Principal、责任 Assignment、Assignment Version 和责任快照 Hash；
- PolicyPack、AgentProfile 及版本；
- ExecutionCapability、Tool、ProviderBinding 和预算；
- Parent Snapshot、变化原因、规范 SHA-256、创建 Principal 和时间。

首个快照为 revision 1。有效策略变化生成带直接 Parent 的后继版本。能力、Tool、ProviderBinding 或预算增加可通过 `expands(parent)` 识别。Executor、PolicyPack 或 AgentProfile 变化属于有效策略变化；授权流程由后续 Application Service 编排。

### 3.2 SafetyEnforcementOverlay

SafetyEnforcementOverlay 是同一 TaskExecution 的单调撤权流。首版本为空；后继版本保存直接父版本 Hash，只能累加撤权类别、禁用能力和禁用 Tool。Principal 或 Membership 停用会整体阻断执行，其他最小撤权事实通过能力和 Tool 交集生效。Provider、Connection、Credential、模型、资源和 Plugin 的精确标识在对应后续领域模型中扩展。

TaskExecution 切换 Overlay 时校验当前 ID、版本、Hash、直接父 Hash 和集合超集关系，并清空当前 Plan 指针。

### 3.3 PlanVersion

Plan 发布必须使用 TaskExecution 当前 PolicySnapshot、SafetyEnforcementOverlay 和 ExecutionPrincipal。计划校验包括：

- 1–100 个 Step，Key 和 sequence 唯一；
- sequence 从 1 连续，依赖只指向更早 Step；
- 至少一个 VALIDATION Step；
- 每个 Capability 和 Tool 同时被当前 Policy 与 Overlay 允许；
- Todo 最多 100 项、最多一个 `IN_PROGRESS`，映射目标必须是本计划 Step；
- 替换计划保存直接 Parent，并衔接 TaskExecution 当前 Plan；
- Policy 或 Overlay 更新会清空当前 Plan，随后允许在新事实下发布同内容的新版本。

PlanVersion 使用长度前缀规范序列计算 SHA-256，覆盖 Scope、Task/Execution、父版本、Policy/Overlay、完整 Executor 责任事实、Markdown、Step、Todo 和发布审计。重建时复验 Hash。

## 4. StepExecution

MVP Step 状态为：

```text
PENDING -> READY -> RUNNING -> SUCCEEDED
PENDING / READY -> SKIPPED
RUNNING -> WAITING(reason) -> READY -> RUNNING
RUNNING -> FAILED_RETRYABLE -> READY -> RUNNING
RUNNING / WAITING / FAILED_RETRYABLE -> FAILED_FINAL
允许取消的非终态 -> CANCELLED
```

Step 只能从 TaskExecution 当前选中的 PlanVersion 创建，并固化 Plan、Policy、Overlay 和 ExecutionPrincipal 的精确引用。实际运行、等待、检查点、成功和失败只能由固定 Executor 提交。非关键 Step 可跳过，关键 Step 不可跳过。

`runAttempt` 从 1 开始。可重试失败且预算未耗尽进入 `FAILED_RETRYABLE`，重新 READY 时递增；预算耗尽或不可重试失败进入 `FAILED_FINAL`。Checkpoint sequence 单调递增，只保存安全 code、Payload Hash、Executor 和时间；重建时校验 Executor、Step 审计时间边界与版本边界。大状态由 M3-D07 RuntimeArtifact 承载。

StepExecution 不保存 Lease，也不存在 Step Lease Port。MVP 由一个 TaskExecution Lease 串行驱动全部 Step。

## 5. Application Port

本阶段增加：

- `PlanVersionRepository`；
- `PolicySnapshotRepository`；
- `SafetyEnforcementOverlayRepository`；
- `StepExecutionRepository`。

版本事实使用 create 与按 Execution 查询；StepExecution 使用带乐观锁语义的 update。JPA Adapter 与数据库唯一约束由 M3-D08/D09 实现。

## 6. 验证

新增专项测试 27 个：

- `PolicySnapshotTest`：5 个；
- `SafetyEnforcementOverlayTest`：2 个；
- `PlanVersionTest`：8 个；
- `TaskExecutionPlanningContextTest`：3 个；
- `StepExecutionTest`：9 个。

覆盖计划/策略不可变版本、父版本、Hash 防篡改、权限扩大、当前策略约束、实时撤权父链、Executor 闭合、计划切换、Todo 映射、串行状态、等待原因、检查点单调性和边界、失败重试、可选 Step 跳过以及无 Step Lease 契约。

专项验证命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-domain,crewscope-application -am test
```

结果：Domain 258 个、Application 178 个测试通过，共 436 个；Failures、Errors、Skipped 均为 0。

M3-D04 在此契约之外建立 Runtime 与 Worker 能力事实。M3-D07 将 Step 与 AgentRun、Interrupt、AgentStateSnapshot 和 RuntimeArtifact 连接。
