# M4-I02 ManagedRepositoryResolver 与基线 Preflight

> 完成日期：2026-08-18
> 范围：`crewscope-infrastructure`、`crewscope-server`

## 1. 交付结果

M4-I02 将 `RepositoryKey` 解析为 Worker 信任边界内的受管裸仓库，并把可移动 Ref 转换为后续 Provision 使用的不可变完整 Commit。

核心组件：

- `ManagedRepositoryResolver`：启动时 canonicalize Managed Root，固定解析 `<managed-root>/<repositoryKey>.git`；
- `ManagedRepository`：公开 Repository Key，canonical 宿主路径保持 package-private，`toString()` 不打印路径；
- `BaselinePreflight`：区分新目标 capture、发布前 Expected Commit 复验和历史 Snapshot Commit 复验；
- `RepositoryPreflightError/Exception`：只公开稳定分类与安全摘要，不保留文件系统/Git 底层异常链；
- `ManagedRepositoryProperties/Configuration`：使用构造器注入完成 Spring Boot 属性绑定、Bean 退让和 `all/worker` 部署条件装配。

`GitCommandExecutor` 增加 `isBareRepository` 与 `verifyCommit` 两个类型化操作，继续保持无 raw command/argv 入口。

## 2. Repository Resolver 边界

解析顺序固定为：

1. Worker 启动时将配置 Root 解析为存在的 canonical Directory；
2. `RepositoryKey` 继续由领域值对象限制为 `[a-z0-9][a-z0-9-]{0,62}`；
3. 候选固定为 `<managed-root>/<repositoryKey>.git` 并执行 lexical containment；
4. 候选按 `NOFOLLOW_LINKS` 拒绝符号链接和非目录目标；
5. 候选 `toRealPath()` 后再次执行 canonical containment；
6. Root 与 Repository Owner 必须精确匹配配置的 Worker Owner；
7. 类型化 Git 验证必须返回 bare repository。

普通工作仓库无论 clean/dirty 都不是受管源仓库。缺失目录、符号链接、Owner 不符、非裸库或 Git 验证失败全部失败关闭。

## 3. 基线语义

`capture(binding, ref)` 用于创建新 CodingTargetSnapshot，要求 Binding 为 ACTIVE `LOCAL_MANAGED`，解析当前 Ref 并返回完整 Commit。

`verifyExpected(binding, ref, expectedCommit)` 用于 snapshot 发布前的二次复验。当前 Ref 不再等于首次解析结果时返回 `BASELINE_MOVED`，调用方不能悄悄固化新 Commit。

`verifySnapshot(snapshot)` 用于 Provision 和恢复，只验证快照的完整 Commit 仍存在。Ref 后续移动、Binding 默认分支变化或 Binding 停用不会改变历史执行输入；错误 Commit 返回 `COMMIT_NOT_FOUND`。

## 4. 路径与错误防披露

公开对象、异常消息和异常链不携带 Managed Root、canonical repository path 或 Git 原始错误。稳定分类包括：

```text
MANAGED_ROOT_INVALID / REPOSITORY_NOT_FOUND / PATH_ESCAPE / SYMLINK_REJECTED
OWNER_MISMATCH / NOT_BARE_REPOSITORY
BINDING_INACTIVE / BINDING_MISMATCH
REFERENCE_INVALID / BASELINE_MOVED / COMMIT_NOT_FOUND / COMMAND_FAILED
```

Spring 配置：

```yaml
crewscope:
  coding:
    repository:
      managed-root: ./var/crewscope/repositories
      required-owner: <Worker OS owner>
```

## 5. 自动化证据

专项命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  -Dtest=ManagedRepositoryResolverM4I02IntegrationTest,ManagedRepositoryConfigurationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

16 个 M4-I02 场景全部通过：真实 canonical 裸仓库解析、完整 Commit capture、Traversal/Option Key、仓库符号链接逃逸、缺失仓库、错误 Owner、clean/dirty 普通仓库、Disabled Binding、缺失 Ref、移动 Ref、历史快照 Ref 隔离、错误 Commit、非法 Root，以及 Spring 属性绑定/启动失败/Bean 退让/纯 Server Profile 隔离。

M4-I01/I02 组合专项共 `26/26` 通过。Domain `409/409`、Application `265/265`、Infrastructure `301/301`，合计 `975/975` 通过。Server YAML `2/2`、M4 固定评测 `12 tasks / 2 tracks`、157 份 Markdown 文档链接同步通过。

## 6. 后续边界

M4-I03 已使用 `ManagedRepository` 的内部 canonical path、CodingTargetSnapshot 固化 Commit、数据库 ExecutionWorkspace 事实和类型化 Git Executor，实现路径锁、Worktree Provision、Fingerprint、回滚、归档与冷恢复。Resolver 保持 Repository 解析与 Preflight 单一职责。
