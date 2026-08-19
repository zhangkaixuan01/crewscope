# M4-I03 Worktree 生命周期与物理指纹

## 1. 交付范围

M4-I03 在 `crewscope-infrastructure` 交付受管 Git Worktree 的完整物理生命周期：

- `WorktreeProvisioner`：Provision、Verify、Provision Orphan Rollback、Delivery Archive 与 Cleanup；
- `WorkspacePathLockManager`：同一 Workspace 的 JVM 重叠锁与操作系统非阻塞 `FileLock`；
- `ManagedWorktree` 与 `WorkspacePhysicalFingerprint`：只公开稳定身份和 SHA-256 证明，canonical Path 保持包内可见；
- `WorktreeArchiveResult`：返回 Archive Ref、Delivery Commit 和 Delivery Tree；
- `ManagedWorktreeConfiguration`：只在 `all/worker` Profile 装配，纯 `server` Profile 不访问宿主 Worktree；
- `GitCommandExecutor` 扩展：Branch/Archive Ref 查询、HEAD/Branch/CommonDir、Commit Tree/Parent 与 Worktree Prune 类型化操作。

## 2. 事实源与物理证明

PostgreSQL `ExecutionWorkspace` 保存逻辑身份、状态、所有权、恢复代次和领域 Fingerprint。Worktree 层不创建本地 metadata 文件。

每次 Provision、Verify、Recover 或 Archive 都在路径锁内重新计算物理 Fingerprint，闭合：

- ExecutionWorkspace ID、领域 Fingerprint、RepositoryBinding ID/Version；
- RepositoryKey、canonical repository 与 canonical worktree；
- TaskExecution ID、attempt、Managed Branch、baseline 和当前 HEAD；
- canonical `git-common-dir`；
- RuntimeEnvironment、Runtime、Worker、Lease 与 Fencing Token；
- WorkspacePolicy ID/Hash、AllowedPaths、BuildProfile Key/Version/Hash；
- SandboxResourceBudget 与 WorkspaceOperationBudget。

宿主路径不进入 `ManagedWorktree` 公开方法、`toString()`、稳定异常或持久化。

## 3. 路径与锁协议

Worktree 路径固定为：

```text
<worktree-root>/<repositoryKey>/<workspaceKey>
```

Provision、Verify、Orphan Rollback、Archive 与 Cleanup 共用同一个稳定路径键。锁获取顺序固定为：

```text
JVM ReentrantLock.tryLock
  -> <lock-root>/<sha256(locator)>.lock
  -> FileChannel.tryLock
```

任一层竞争都返回 `WORKSPACE_BUSY`。Worktree Root 与 Lock Root 必须预创建、canonicalize 且属于配置的 Worker Owner。路径创建前逐段执行 lexical containment、`NOFOLLOW_LINKS`、目录类型与 Owner 校验；创建后再次执行 canonical containment。

## 4. Provision、回滚与冷恢复

Provision 固定执行：

```text
resolve bare repository
  -> reject existing Archive Ref
  -> verify Path and Branch absence
  -> prepare managed repository directory
  -> git worktree add -b <managedBranch> <path> <baselineCommit>
  -> verify .git/CommonDir/Branch/HEAD/Owner
  -> calculate physical fingerprint
```

重复 Provision 只在既有 Worktree 完整通过同一验证时返回同一物理 Fingerprint。

普通异常触发同步补偿，只删除本次创建且身份完整闭合的 Path 与 Branch。进程骤停由新 Worker 读取 PostgreSQL Workspace 后调用 Orphan Rollback；Path、Branch、HEAD、CommonDir、Repository、WorkspacePolicy 任一项不闭合时保留现场并失败关闭。

## 5. Delivery 与 Archive

归档固定执行：

```text
git add --all
  -> git write-tree
  -> git commit-tree <tree> -p <baseline>
  -> git update-ref <archiveRef> <deliveryCommit> <zeroOid>
  -> remove Worktree
  -> delete managed Branch at expected baseline
```

`commit-tree` 不移动活动 Branch 与 HEAD。Archive Ref 原子创建并拒绝覆盖。Archive Ref 已发布后的重试验证 Delivery Commit 只有一个 baseline Parent；Worktree 尚存时同时验证 Delivery Tree 与当前索引树一致，再继续清理。清理失败保留 Archive Ref、Worktree 和 Branch 的可恢复事实，下一次调用可以幂等收口。

## 6. 稳定错误

Worktree 边界提供以下路径无关分类：

```text
MANAGED_ROOT_INVALID
WORKSPACE_BUSY
PATH_ESCAPE
PATH_SYMLINK_ESCAPE
UNOWNED_PATH_RESIDUE
BRANCH_CONFLICT
NOT_PROVISIONED
CORRUPT_HEAD
CORRUPT_BRANCH
CORRUPT_GIT_POINTER
POLICY_MISMATCH
WORKSPACE_MISMATCH
ARCHIVE_CONFLICT
ROLLBACK_FAILED
CLEANUP_FAILED
COMMAND_FAILED
```

异常只包含稳定分类与安全摘要，原始 Git 输出、文件系统异常消息和宿主路径均不进入异常链。

## 7. 自动化验证

专项测试：

```text
WorktreeProvisionerM4I03IntegrationTest  13
ManagedWorktreeConfigurationTest         4
合计                                    17
```

覆盖场景：

1. 真实 bare repository Provision、重复恢复与物理 Fingerprint；
2. 双 Provisioner 路径锁竞争与 `WORKSPACE_BUSY`；
3. `git worktree add` 后普通失败的完整补偿；
4. 进程骤停遗留的精确孤儿冷恢复清理；
5. 预存目录残留保留；
6. Managed Branch 冲突保留；
7. 移动 HEAD、Detached Branch 与损坏 `.git` 指针分类；
8. Worktree 符号链接逃逸；
9. WorkspacePolicy lineage 不匹配；
10. Delivery Commit Parent/Tree、Archive Ref 与 Path/Branch 清理；
11. Archive Ref 发布后进程骤停的幂等续接；
12. 错误 Archive Ref 失败关闭并保留现场；
13. 清理失败保留证据并支持重试；
14. Worktree/Lock Root 缺失与 Worker Owner 错误；
15. 纯 Server Profile 不装配宿主 Worktree 能力。

执行命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  -Dtest=WorktreeProvisionerM4I03IntegrationTest,ManagedWorktreeConfigurationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：`17 / 17` 通过，普通失败回滚完整率 `100%`。

## 8. 后续边界

M4-I04 已使用本阶段输出的 `ManagedWorktree` 与物理 Fingerprint 创建 TaskExecution 级 Docker Sandbox，并在容器标签、bind mount 与恢复协议中复验 Workspace、Policy、Runtime、Worker、Lease 和 Fencing 所有权。验证见 [M4-I04 TaskExecution 级 Docker Sandbox](M4-I04-TaskExecution级Docker-Sandbox.md)。
