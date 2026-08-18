# M4-D03：ExecutionWorkspace 领域模型

> 日期：2026-08-17<br>
> 状态：已完成<br>
> 模块：`crewscope-domain`、`crewscope-application`

## 目标

用一个耐久聚合表达 Coding Task 每次执行所拥有的逻辑 Workspace，闭合 TaskExecution、attempt、Runtime、Worker、Lease、Fencing、CodingTarget、受管分支、Worktree 定位、恢复代次和保留期，为 M4-I03 的物理 Worktree 生命周期与 M4-D08 的 V14 表结构提供统一契约。

## 聚合身份与所有权

`ExecutionWorkspace` 只为处于 `PREPARING` 且持有有效 PREPARE Lease 的 TaskExecution 创建。创建时逐项验证 CodingTargetSnapshot、Task、完整 WorkItemScope、TaskExecution、attempt、Lease、Fencing 和操作者 Scope，并保存：

```text
ExecutionWorkspaceId
Organization / Team / Workspace / WorkProject
TaskId / TaskExecutionId / attempt
CodingTargetSnapshotReference
RepositoryBindingId / RepositoryBindingVersion / RepositoryKey
BaselineCommit
WorkspaceKey / ManagedBranch / ArchiveReference
RuntimeEnvironment / RuntimeId / WorkerId / LeaseId / FencingToken
Status / RecoveryTargetStatus / RecoveryGeneration
CompletionReason / FailureCode
Retention / Fingerprint / Version / AuditMetadata
```

Repository Port 以 `TaskExecutionId + attempt` 唯一约束原子拒绝第二个 Workspace，并返回稳定错误码 `EXECUTION_WORKSPACE_ATTEMPT_CONFLICT`。查询显式携带 Organization、Team 与 WorkProject，恢复批次还携带 RuntimeEnvironment。

## 稳定标识与路径边界

标识只由服务端稳定事实派生：

```text
workspaceKey = ws-<executionWorkspaceId>-a<attempt>
branch       = crewscope/tasks/<taskExecutionId>/attempt-<attempt>
worktree     = <repositoryKey>/<workspaceKey>
archiveRef   = refs/crewscope/archives/<workspaceKey>
```

领域层只保存 RepositoryKey 和相对逻辑定位，不保存、接收或返回 `java.nio.file.Path`。`<worktreeRoot>`、canonical repository path、canonical worktree path 与 `git-common-dir` 由 M4-I02/M4-I03 的受信 Adapter 解析和验证。

attempt 范围与 `TaskExecution.MAX_SUPPORTED_ATTEMPTS` 共用同一个上限，所有受管标识支持第 1 至第 100 次执行。

## 状态机

正常路径固定为：

```text
PENDING -> PROVISIONING -> READY -> ACTIVE -> FINALIZING -> COMPLETED
```

控制与故障路径为：

```text
ACTIVE + TaskExecution.PAUSED -> READY
READY + 新 PREPARE Lease/Fencing -> READY
PROVISIONING/READY/ACTIVE/FINALIZING + TaskExecution.RECOVERING -> RECOVERING
RECOVERING + 新 PREPARE Lease/Fencing -> 原中断状态
非终态 -> FAILED
COMPLETED/FAILED + retention due -> ARCHIVED
```

Pause 保留 Workspace、Worktree、managed branch 与 archive reference。Resume 和 Recovery 必须绑定严格更大的 Fencing Token，旧 Worker、旧 Lease 或同一所有权纪元不能继续提交。

Cancel 先在 TaskExecution 进入 `CANCEL_REQUESTED/CANCELLED`，Workspace 进入 `FINALIZING` 固化最终可证明 Diff，随后以 `CANCELLED` 原因进入 `COMPLETED`。Cancel 不删除代码事实。

`COMPLETED`、`FAILED` 在保留期到达后才能归档；`ARCHIVED` 为不可变终态。物理 Archive Ref、Worktree 和 Branch 的幂等操作由 M4-I03 在 Workspace 锁内完成，聚合只提交可恢复的前后事实。

## Retry 与恢复

Retry 使用新的 TaskExecution、ExecutionWorkspaceId、WorkspaceKey、managed branch 和 Worktree 定位。旧 attempt 的 Workspace、Fingerprint 与后续 Artifact 保持独立，不复用旧目录或分支。

Recovery 保存被中断的 `PROVISIONING/READY/ACTIVE/FINALIZING` 状态并递增 `RecoveryGeneration`。恢复完成后清除 RecoveryTargetStatus，使用新的 Runtime/Worker/Lease/Fencing 所有权，返回原中断状态。

应用 Repository Port 提供两类受锁批次：

- 按 Organization 与 RuntimeEnvironment 领取 `RECOVERING` Workspace；
- 按 Organization 与权威时钟领取保留期已到的 `COMPLETED/FAILED` Workspace。

正式 PostgreSQL Adapter 使用 `FOR UPDATE SKIP LOCKED` 和版本条件更新，进入 M4-D09 实现。

## Fingerprint、失败与审计

逻辑 Fingerprint 使用长度前缀 canonical SHA-256，闭合 Workspace/Scope、TaskExecution/attempt、CodingTarget Snapshot Hash、RepositoryBinding Version、RepositoryKey、BaselineCommit、受管标识、当前 Runtime/Worker/Lease/Fencing、RecoveryGeneration 与 Retention。

领域 Fingerprint 不包含宿主绝对路径。M4-I03 在此基础上复验 canonical repository/worktree、HEAD、`git-common-dir` 与后续 M4-D04 WorkspacePolicy，组成物理 Workspace 证明。

持久化恢复会重新计算 Fingerprint，标识关联、恢复状态形状、终态 Completion/Failure 形状或 Hash 被篡改时失败关闭。失败只保存 1–64 位稳定大写下划线错误码，不保存原始异常、命令输出或宿主路径。

所有状态修改使用 Expected Version 和构造器注入的活动 Scope Principal，更新审计保留 createdBy/createdAt 并记录最后 updatedBy/updatedAt。

## 阶段边界

M4-D03 不执行 Git 命令，不解析宿主路径，不创建 Worktree/Sandbox，不新增 V14 迁移、JDBC/JPA Adapter、Controller 或 Worker 编排。WorkspacePolicy 在 M4-D04 实现，Diff/Test Artifact 在 M4-D05/M4-D06 实现，物理 Git 生命周期在 M4-I03 实现，持久化在 M4-D08/M4-D09 实现。

## 验证

14 个专项测试覆盖：

- 初始 Scope、Task、CodingTarget、Repository、Runtime、Worker、Lease、Fencing、Retention 与审计闭合；
- WorkspaceKey、managed branch、relative Worktree locator、archive reference 和最大 attempt；
- 领域模型不公开宿主 `Path`；
- CodingTarget、TaskExecution、Lease、操作者和 Retention 不匹配失败关闭；
- 正常状态机、非法迁移、乐观锁和终态不可变；
- Pause 保留与 Resume 新 Fencing；
- Cancel 最终 Diff 语义；
- RecoveryTarget、RecoveryGeneration 与新所有权；
- Retry Workspace/Branch/Worktree/Fingerprint 隔离；
- COMPLETED/FAILED 保留期归档；
- Fingerprint、恢复形状和标识关联篡改拒绝；
- TaskExecution attempt 唯一、Scope 查询隔离、Repository 乐观更新；
- 恢复与 Retention 受锁批次的 Organization、Environment、状态和上限筛选。

验证命令：

```bash
./mvnw --batch-mode --no-transfer-progress -pl crewscope-domain,crewscope-application -am test
./mvnw --batch-mode --no-transfer-progress test
node evaluation/m4/coding-v1/scripts/evaluate.mjs validate
node scripts/check-doc-links.mjs
git diff --check
```
