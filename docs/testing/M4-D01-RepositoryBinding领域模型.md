# M4-D01：RepositoryBinding 领域模型

> 日期：2026-08-17<br>
> 状态：已完成<br>
> 模块：`crewscope-domain`、`crewscope-application`

## 目标

建立 Coding Task 选择受管源仓库时使用的稳定业务事实，为 M4-D02 的 `CodingTargetSnapshot`、M4-D08 的 V14 表结构、M4-I02 的 `ManagedRepositoryResolver` 和 M4-A01 的仓库管理用例提供统一契约。

## 聚合边界

`RepositoryBinding` 表示一个 WorkProject 对一个受管源仓库的版本化绑定，保存以下事实：

```text
RepositoryBindingId
RepositoryBindingScope（Organization / Team / Workspace / WorkProject）
RepositoryKind.LOCAL_MANAGED
RepositoryKey
DefaultBranch
RepositoryBindingStatus
Version
AuditMetadata
```

绑定创建时要求 WorkProject 为 `ACTIVE`，操作者为同一 Organization 且不越出 Team Scope 的活动 Principal。Scope、Repository Kind 和 Repository Key 创建后保持不变。默认分支、状态、Version 和修改审计通过显式领域方法推进。

## 受管仓库身份

`RepositoryKey` 使用 M4-S02 冻结的 `[a-z0-9][a-z0-9-]{0,62}` 格式。它是业务模型、API、任务快照和执行链之间传递的唯一仓库定位信息。

`RepositoryBinding` 不保存、不接收也不公开 `Path`、绝对路径或 Managed Repository Root。M4-I02 才会在受信 Worker 内将 Key 解析为 `<managed-root>/<repositoryKey>.git`，并完成 canonical containment、符号链接、Owner 和 bare repository 校验。

## 默认分支

`RepositoryBranchName` 保存短分支名，不接受 `refs/heads/` 前缀。校验覆盖 Git Ref 的控制字符、空格、反斜杠、`..`、`@{`、连续分隔符、隐藏组件、`.lock` 结尾、前导参数形态和长度上限。

默认分支是未来 Coding Target 的缺省输入。已创建的 `CodingTargetSnapshot` 将在 M4-D02 固化精确 Binding Version、Ref 和完整 Commit，因此后续默认分支变更不会让历史 Task 漂移。

## Scope 与唯一性

Repository Binding 使用完整的 `Organization + Team + Workspace + WorkProject` Scope。`RepositoryBindingRepository` 的所有查询都显式携带 Organization、Team 和 WorkProject，避免仅凭 Binding ID 或 Repository Key 跨 Scope 读取。

唯一键定义为：

```text
OrganizationId + TeamId + WorkProjectId + RepositoryKey
```

同一 WorkProject 不能重复绑定同一个 Repository Key；不同 WorkProject 可以绑定同一受管仓库。Application Port 规定 `create` 必须原子拒绝重复键并返回稳定的 `REPOSITORY_BINDING_KEY_CONFLICT`。M4-D08 使用数据库唯一约束关闭并发竞争，M4-D09 将约束冲突映射为同一领域错误。

## 生命周期与版本

状态机为：

```text
ACTIVE -> DISABLED -> ACTIVE
```

`ACTIVE` 允许创建新的 Coding Target；`DISABLED` 阻止未来选择，但不篡改历史快照。启用、停用和默认分支变更都要求 Expected Version，成功后 Version 严格加一并更新 `AuditMetadata`。重复启用、重复停用、旧 Version 和同值分支变更失败关闭。

M4-A01 在启用前执行仓库 Preflight。领域层只表达状态迁移，不读取宿主仓库或执行 Git 命令。

## 应用层边界

M4-D01 定义 `RepositoryBindingRepository` Port：

- 创建并原子裁决 WorkProject 内 Repository Key 唯一性；
- 使用乐观锁更新聚合；
- 按完整 Scope 与 Binding ID 查询；
- 按完整 Scope 与 Repository Key 查询；
- 查询一个 WorkProject 的全部 Binding。

正式 PostgreSQL Adapter、分页查询和条件更新在 M4-D09 实现。管理员授权、幂等命令、事件、Outbox、Preflight 与 REST API 在 M4-A01 实现。

## 阶段边界

M4-D01 不创建 V14 数据表、JPA/JDBC Adapter、Spring Bean、Controller、宿主路径 Resolver 或 Git 命令执行器。上述能力分别在 M4-D08、M4-D09、M4-I01、M4-I02 和 M4-A01 交付。

## 验证

9 个专项测试覆盖：

- WorkProject、Organization、Team、Workspace Scope 闭合；
- 仅活动 WorkProject 和同 Scope 活动 Principal 可创建 Binding；
- `LOCAL_MANAGED`、稳定 Repository Key 和默认分支校验；
- 启用、停用、默认分支变更、Version 和审计单调推进；
- 重复状态迁移、旧 Version 与同值变更失败关闭；
- WorkProject 内唯一、跨 WorkProject 可复用的 Repository Key 契约；
- 完整 Scope 查询隔离；
- 聚合字段、方法返回值和方法参数不公开宿主 `Path`。

验证命令：

```bash
./mvnw --batch-mode --no-transfer-progress -pl crewscope-domain,crewscope-application -am test
./mvnw --batch-mode --no-transfer-progress test
node scripts/check-doc-links.mjs
git diff --check
```
