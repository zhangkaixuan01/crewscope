# M4-S02 Git Worktree 与冷恢复协议验证记录

> 验证对象：Git `2.50.1`（Apple Git-155）<br>
> CrewScope 模块：`crewscope-infrastructure`<br>
> 验证日期：2026-08-16

## 1. 验证结论

CrewScope 使用受管裸仓库、确定性 Workspace 身份、类型化 Git 参数、同机文件锁和持久化 Workspace 元数据，可以稳定管理 TaskExecution 级 Git Worktree。

临时 Git Fixture 已完成以下闭环：

```text
Repository Key
  -> ManagedRepositoryResolver
  -> canonical bare repository
  -> repository + taskExecution 路径锁
  -> 固定分支与 Worktree 路径
  -> git worktree add
  -> Fingerprint 校验
  -> ACTIVE 元数据原子发布
  -> 冷恢复校验
  -> Delivery Commit 与 Archive Ref
  -> Worktree/活动分支清理
  -> ARCHIVED 元数据原子发布
```

并发创建、普通失败、进程骤停、目录残留、错误 HEAD、失效 `.git` 指针和符号链接越界均获得确定结果。平台只自动清理能够证明由当前 Workspace 创建的资源；来源不明或身份不闭合的目录进入 `CORRUPT`，保留现场并等待运维对账。

## 2. Fixture 拓扑

每个测试创建独立临时目录：

```text
<fixture-root>/
  source/                              普通 Git 仓库，两条固定 Commit
  managed-repositories/
    repository-01.git/                 受管裸仓库
  worktrees/
    repository-01/
      ws-<taskExecutionId>-a<attempt>/  TaskExecution Worktree
  workspace-locks/
    ws-<taskExecutionId>-a<attempt>.lock
  workspace-registry/
    ws-<taskExecutionId>-a<attempt>.properties
  command-home/                        Git 固定 HOME
```

`workspace-registry/*.properties` 只用于 Spike 表达原子元数据协议。生产实现由 `execution_workspace` 数据库事实、Workspace Fingerprint 和乐观锁承担同一职责。

## 3. Repository Resolver 协议

MVP 只解析平台配置的受管仓库根目录。API、模型和 Tool 只提交稳定 `RepositoryKey`，不提交宿主路径。

解析顺序固定为：

1. Worker 启动时将 Managed Repository Root 解析为 canonical path；
2. `RepositoryKey` 只允许小写字母、数字和连字符，长度为 1–63；
3. 仓库候选固定为 `<managed-root>/<repositoryKey>.git`；
4. 候选路径按 `NOFOLLOW_LINKS` 校验，所有已有父级和目标均不得为符号链接；
5. canonical repository 必须位于 Managed Repository Root 内；
6. `git rev-parse --is-bare-repository` 必须返回 `true`；
7. 生产 Preflight 再校验配置的 Worker Owner、RepositoryBinding 状态、默认分支和目标 Commit。

普通工作仓库、越界 Key、参数形态 Key 和指向根目录外的符号链接均以稳定 Resolver 错误失败。

## 4. 类型化 Git 参数

Git 管理命令由平台代码生成参数数组。M4-I01 对外提供下列值对象：

| 类型 | 约束 | 来源 |
|---|---|---|
| `RepositoryKey` | `[a-z0-9][a-z0-9-]{0,62}` | RepositoryBinding 服务端事实 |
| `CommitId` | 40 位小写十六进制完整 Commit ID | Baseline Preflight 解析结果 |
| `WorkspaceKey` | `ws-<32位UUID>-a<attempt>` | TaskExecution 与 attempt 确定性派生 |
| `ManagedBranchName` | `crewscope/tasks/<taskExecutionUuid>/attempt-<attempt>` | 平台工厂生成 |
| `ArchiveRef` | `refs/crewscope/archives/<workspaceKey>` | 平台工厂生成 |

`HEAD`、短 SHA、客户端 Ref、`--help`、`--orphan`、`../` 和原始命令字符串不进入宿主管理命令。

Git 子进程使用以下固定边界：

```text
argument array = 平台命令模板 + 类型化参数
HOME = Worker 专用空目录
GIT_CONFIG_NOSYSTEM = 1
GIT_CONFIG_GLOBAL = /dev/null
GIT_TERMINAL_PROMPT = 0
LC_ALL/LANG = C
timeout = 有界
combined output = 有界
failure = 稳定分类
```

M4-I01 将分类固定为 `NOT_A_REPOSITORY`、`INVALID_REFERENCE`、`CONFLICT`、`TIMEOUT`、`OUTPUT_LIMIT` 和 `COMMAND_FAILED`，业务层只读取安全摘要。

## 5. Workspace 身份与路径

一个 RepositoryBinding 下，一个 TaskExecution attempt 对应一个活动 Workspace 身份：

```text
lockKey      = repositoryId + taskExecutionId
workspaceKey = executionWorkspaceId + attempt
branch       = crewscope/tasks/<taskExecutionId>/attempt-<attempt>
worktreePath = <worktree-root>/<repositoryKey>/<workspaceKey>
archiveRef   = refs/crewscope/archives/<workspaceKey>
```

Spike 使用 TaskExecution UUID 确定性派生 `workspaceKey`。生产领域模型保存独立稳定 `ExecutionWorkspaceId`，并通过数据库唯一约束保证一个 attempt 只有一个活动 Workspace。

路径创建前逐段执行 lexical containment 与符号链接校验。创建后再执行 canonical containment，避免 `/var` 与 `/private/var` 等平台路径别名产生错误 Fingerprint。

## 6. 并发锁协议

MVP 使用同机 Execution Worker 和本地文件系统。Provision、Recover、Archive 与 Cleanup 共用一个非阻塞路径锁：

```text
JVM overlapping lock detection
  + OS advisory FileLock
  + PostgreSQL execution_workspace 唯一约束/条件更新
```

锁文件位于 Worktree Root 外部，资源清理不会删除锁。竞争 Worker 未获得锁时返回 `WORKSPACE_BUSY`，由 Task Scheduler 退避重试，不进入第二次创建。

双 Worker Fixture 在第一个 Worker 持有锁且已经创建 Git Worktree 时启动第二个 Worker，结果为：

```text
creator = 1
workspace_busy = 1
worktree = 1
managed branch = 1
ACTIVE metadata = 1
```

该协议依赖同节点本地文件锁。未来 RWX PVC 或多节点 Workspace Manager 需要重新验证文件锁语义，并以数据库 Lease/Fencing 或分布式锁作为权威所有权。

## 7. Provision 与回滚协议

Provision 在同一 Workspace 锁内执行：

```text
resolve managed repository
  -> 校验 metadata/path/branch 均不存在
  -> 创建受管父目录
  -> git worktree add -b <managedBranch> <path> <fullCommitId>
  -> 校验 Workspace Fingerprint
  -> staged metadata
  -> ATOMIC_MOVE 发布 ACTIVE metadata
```

Workspace Fingerprint 至少包含：

- RepositoryBinding ID/Version 与 canonical repository path；
- ExecutionWorkspace ID、TaskExecution ID 和 attempt；
- canonical worktree path；
- managed branch；
- baseline full Commit ID 与当前 HEAD；
- `git-common-dir` canonical path；
- Worker、Runtime、Lease ID 和 Fencing Token；
- WorkspacePolicy/AllowedPaths/BuildProfile/Sandbox Image 版本。

普通异常触发补偿回滚：

```text
git worktree remove --force
  -> git worktree prune（仅在受管残留清理后需要）
  -> 删除 managed branch ref
  -> 删除本次 staged/committed workspace metadata
  -> 复验 path/branch/metadata 全部不存在
```

回滚不删除 Provision 前已经存在的目录或文件。未知残留返回 `UNOWNED_PATH_RESIDUE`。

## 8. 进程骤停与冷恢复

Fixture 在 `git worktree add` 完成、ACTIVE 元数据发布前注入 `SimulatedProcessExit`。异常跳过应用回滚，模拟 JVM 被终止后由操作系统释放文件锁的状态。

新 Worker 启动后按以下顺序恢复：

1. 读取 PostgreSQL `ExecutionWorkspace` 与本地资源候选；
2. 获取同一 Workspace 路径锁；
3. 重新解析 RepositoryBinding canonical path；
4. 校验路径、`.git` 指针、`git-common-dir`、Branch、HEAD 和 Fingerprint；
5. 有 ACTIVE 元数据且 Fingerprint 完整时进入 `ACTIVE/RECOVERING`；
6. 无元数据且路径、Branch、Repository 和身份全部精确匹配时识别为 Provision 孤儿并补偿回滚；
7. 任一身份不匹配时进入 `CORRUPT`，保留现场并发布安全运维诊断；
8. 完成 Lease/Fencing 复验后才允许恢复 Sandbox、Watcher 和 Agent。

Fixture 证明有效 ACTIVE Workspace 可由新 Provisioner 实例恢复；进程骤停孤儿可被新实例清理并使用同一稳定身份重新创建。

## 9. 损坏检测与清理所有权

| 注入状态 | 结果 | 自动删除 |
|---|---|---|
| Provision 前目录残留 | `UNOWNED_PATH_RESIDUE` | 否 |
| Worktree HEAD 偏离 baseline | `CORRUPT_HEAD` | 否 |
| Worktree 进入 detached HEAD/错误 Branch | `CORRUPT_BRANCH` | 否 |
| `.git` 指向无效或外部目录 | `CORRUPT_GIT_POINTER` | 否 |
| Worktree 路径包含符号链接 | `PATH_SYMLINK_ESCAPE` | 否 |
| 无元数据、Branch/Path/CommonDir/身份全部匹配 | `PROVISION_ORPHAN` | 是 |
| ACTIVE 元数据与完整 Fingerprint 匹配 | `ACTIVE` | 否 |

自动删除需要同时满足受管根目录、稳定 Workspace 身份、预期 managed branch、预期 bare repository 和 Git common directory 五项证明。损坏检测不读取或遍历符号链接目标。

## 10. 归档协议

归档在同一 Workspace 锁内执行。Delivery Commit 通过索引树创建，不移动活动 Branch：

```text
git add --all
  -> git write-tree
  -> git commit-tree <tree> -p <baselineCommit>
  -> update-ref refs/crewscope/archives/<workspaceKey> <deliveryCommit>
  -> 发布 ARCHIVING metadata（含 deliveryCommit）
  -> git worktree remove --force
  -> 删除 managed branch ref
  -> 发布 ARCHIVED metadata
```

`git commit-tree` 保持活动 Branch 和 HEAD 位于 baseline。Archive Ref 发布前进程退出时，Workspace 仍满足 ACTIVE Fingerprint，重试可以重新生成并固定 Delivery Commit；未引用对象由 Git GC 回收。

Archive Ref 已发布且 `ARCHIVING` 元数据已提交后进程退出时，新 Worker 校验 Archive Ref 与 Delivery Commit 一致，随后幂等完成 Worktree 和 managed branch 清理并发布 `ARCHIVED`。`ARCHIVED` 冷恢复只验证 Archive Ref 与 Delivery Commit，不重建 Worktree。

Archive Ref 是 M4 本地交付与恢复锚点。M5 创建 GitHub Draft PR 后，外部 Branch/PR、ActionReceipt 与本地 Archive Ref 共同构成交付证据；保留期到达后由受审计 Cleanup 删除本地 Ref 和 Artifact。

## 11. 自动化证据

测试类：

```text
crewscope-infrastructure/src/test/java/io/crewscope/infrastructure/workspace/
  GitWorkspaceM4S02IntegrationTest.java
```

执行命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  -Dtest=GitWorkspaceM4S02IntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

验证结果：

```text
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

测试覆盖：

1. 受管裸仓库解析与符号链接越界；
2. 类型化标识、固定 Branch/Path 与参数注入拒绝；
3. 双 Worker 同 Workspace 并发创建；
4. 普通部分创建失败补偿回滚；
5. 有效 ACTIVE Workspace 冷恢复；
6. 进程骤停孤儿识别、回滚与重建；
7. 未知目录残留保留；
8. 错误 HEAD/Branch 与失效 `.git` 指针检测；
9. Worktree 路径符号链接越界检测；
10. Archive 中断后的冷恢复与幂等清理。

## 12. 冻结决策

M4-S02 冻结以下决策：

1. MVP RepositoryBinding 使用受管本地裸仓库与稳定 Repository Key；
2. Git 宿主管理命令只接受平台生成的类型化参数数组；
3. 一个 TaskExecution attempt 对应一个稳定 ExecutionWorkspace、managed branch 和 Worktree 路径；
4. Provision、Recover、Archive 与 Cleanup 共用 `repositoryId + taskExecutionId` 路径锁；
5. Worktree 创建成功以 Fingerprint 校验和 ACTIVE 数据库事实提交为边界；
6. 普通失败执行同步补偿回滚，进程骤停由启动对账识别；
7. 自动清理只处理身份完全闭合的受管资源，损坏和未知资源失败关闭；
8. Worktree 是代码文件事实源，Delivery Commit 与 Archive Ref 是归档恢复锚点；
9. 归档使用 `commit-tree` 创建 Delivery Commit，活动 Branch 保持 baseline；
10. M4-I01、M4-I02、M4-I03 分别实现 Git Executor、Repository Resolver 和 Worktree Lifecycle，复用本记录的固定 Fixture 与故障矩阵。
