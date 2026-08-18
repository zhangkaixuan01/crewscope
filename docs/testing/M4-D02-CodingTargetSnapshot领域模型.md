# M4-D02：CodingTargetSnapshot 领域模型

> 日期：2026-08-17<br>
> 状态：已完成<br>
> 模块：`crewscope-domain`、`crewscope-application`

## 目标

将成员确认的 Coding 目标固化为独立、不可变、可复验的 Task 事实，为 M4-D03 的 `ExecutionWorkspace`、M4-D04 的完整 `BuildProfile` 与 `WorkspacePolicy`、M4-D08 的 V14 表结构和后续 Coding Task 创建用例提供统一契约。

## 快照边界

`CodingTargetSnapshot` 是 Task 的可选附属事实，不进入 `Task` 聚合。普通 Task 不创建快照，继续使用 M3 的创建、执行、重试和查询链路。

初始快照只能在 Task 处于 `CREATED` 且尚未绑定 TaskExecution 时创建，并保存：

```text
CodingTargetSnapshotId
WorkItemScope / TaskId / TaskBriefHash
Revision / ParentSnapshotId / ChangeReason
RepositoryBindingId / RepositoryBindingVersion
RepositoryKind / RepositoryKey
BaselineRef / BaselineCommit
AllowedPaths / BuildProfileReference
AcceptanceCriteria
SnapshotHash
CreatedByPrincipalId / CreatedAt
```

RepositoryBinding 必须处于 `ACTIVE`，Organization、Team、Workspace 与 WorkProject 必须和 Task 完整一致。操作者必须是同一 Organization 且不越出 Team Scope 的活动 Principal。

## Ref 与 Commit

`BaselineRef` 保存成员选择的短分支 Ref，`BaselineCommit` 保存 Preflight 解析出的 40 位小写完整 Git Commit ID。后续 Ref 移动、默认分支变化或 RepositoryBinding 停用不会改变历史快照。

执行链只使用快照中的 Commit 创建 Worktree，不在 Provision 或恢复阶段重新解释 Ref。Preflight 和受管仓库读取在 M4-I02 至 M4-I03 实现。

## AllowedPaths

`CodingTargetAllowedPaths` 保存 1 至 200 个 canonical 仓库相对路径，使用 Unicode Code Point 顺序形成稳定集合。它拒绝：

- 绝对路径和 Windows Drive 路径；
- 反斜杠、空路径组件和重复分隔符；
- `.`、`..` 路径组件；
- NUL、控制字符和超长路径。

单独的 `.` 表示仓库根。重复路径自动去重，父路径存在时折叠冗余子路径。`allows` 和 `containsAll` 为后续 WorkspacePolicy、Tool 路径检查和 Retry 授权收紧提供同一语义。

## BuildProfile 与验收标准

M4-D02 保存 `BuildProfileReference` 的稳定 Key、Version 与 Profile Hash。完整 `BuildProfile`、命令目录和 Sandbox 预算在 M4-D04 实现；快照引用保证后续定义升级不会改变历史 Task 的构建语义。

验收标准从不可变 `TaskBrief` 复制，同时保存 TaskBrief Hash。Coding Target 必须至少包含一条验收标准，Retry 不能替换 TaskBrief 或验收标准。

## Revision 与 Retry

初始版本固定为：

```text
revision = 1
parentSnapshotId = empty
changeReason = TASK_CREATED
```

Retry 默认通过 `CodingTargetSnapshotReference` 沿用原 Snapshot ID、Revision 与 Hash。显式换目标创建线性后继版本：

```text
revision = parent.revision + 1
parentSnapshotId = parent.id
changeReason = RETRY_TARGET_UPDATED
```

换版时重新验证失败 Task、TaskBrief、当前 RepositoryBinding、操作者和完整 Scope。新的 AllowedPaths 必须保持或收紧父版本授权，不能新增父版本未覆盖的路径。完全没有有效变化的换版请求失败关闭。

## Hash 与持久化契约

`SnapshotHash` 对全部不可变字段使用长度前缀 canonical SHA-256。`reconstitute` 会重新计算并比对 Hash，持久化字段被篡改时拒绝恢复。

`CodingTargetSnapshotRepository` 规定：

- `TaskId + Revision` 唯一，重复创建返回稳定的 `CODING_TARGET_SNAPSHOT_REVISION_CONFLICT`；
- 查询显式携带 Organization、Team 与 WorkProject Scope；
- `findLatestByTask` 返回最大 Revision；
- 空结果表示兼容的非 Coding Task；
- 正式 PostgreSQL 唯一约束与 Adapter 在 M4-D08 至 M4-D09 实现。

## 阶段边界

M4-D02 不创建 V14 表、JDBC/JPA Adapter、Controller、Task 创建应用服务、Git Preflight、完整 BuildProfile、WorkspacePolicy 或 ExecutionWorkspace。上述能力分别在 M4-D08、M4-D09、M4-A02、M4-I02、M4-D04 和 M4-D03 交付。

## 验证

12 个专项测试覆盖：

- 初始快照的 Task、Scope、Binding Version、Ref/Commit、BuildProfile、验收标准和 Hash；
- 不可变集合与 canonical Hash 复验；
- Ref 与默认分支漂移不影响历史 Commit；
- Binding 状态、完整 Scope 和 Task 创建阶段校验；
- 40 位小写完整 Git Commit 校验；
- AllowedPaths 规范化、排序、折叠、包含与路径逃逸拒绝；
- Retry 原引用沿用；
- Retry Revision、Parent、ChangeReason 和当前 Binding Version；
- Retry 授权收紧成功、扩权失败和无变化换版失败；
- TaskBrief 与验收标准不可借 Retry 改写；
- 持久化字段或 Hash 篡改后恢复失败；
- Task Revision 唯一、最新版本查询、Scope 隔离和非 Coding Task 空结果。

验证命令：

```bash
./mvnw --batch-mode --no-transfer-progress -pl crewscope-domain,crewscope-application -am test
./mvnw --batch-mode --no-transfer-progress test
node evaluation/m4/coding-v1/scripts/evaluate.mjs validate
node scripts/check-doc-links.mjs
git diff --check
```
