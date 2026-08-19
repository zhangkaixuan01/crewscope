# M4-I08 Workspace Diff 与最终 DiffArtifact

## 1. 交付范围

M4-I08 在 M4-I03 受管 Worktree 和 M4-D05 DiffArtifact 领域模型之上交付：

- `WorkspaceDiffWatcher` 递归观察 AllowedPaths，并将文件事件合并为路径无关的调度提示；
- `WorkspaceDiffMonitor` 串行连接 Watcher、Git Reconcile 与 Event Store；
- `GitWorkspaceDiffReconciler` 从 Git 权威事实生成 DiffManifest、单文件 Patch Hash、统计和有界 Preview；
- `WorkspaceDiffEventStore` 发布 RESET/DELTA、Generation、Sequence 与 HMAC Cursor，并提供有界 Replay；
- `WorkspaceDiffFinalizer` 从精确 Baseline/Delivery Commit 重新生成最终 Manifest，写入完整 Patch Artifact，并原子发布 DiffArtifact。

## 2. Watcher 与 Reconcile

WatchService 只缩短刷新延迟，不形成业务事实。Watcher 启动、周期对账、`OVERFLOW`、WatchKey 失效和文件变化均触发完整 Git Reconcile。提示不携带宿主路径或文件内容。

Watcher 注册 Worktree 根目录、AllowedPaths 的现有祖先和授权子树。新目录只有位于授权根内或属于授权根祖先时才递归注册；`.git`、符号链接目录和无关目录不进入递归观察。高频事件按 Workspace Debounce 合并，Monitor 使用互斥锁防止两个提示并发生成重复 Generation。

Reconciler 使用类型化 `GitCommandExecutor`，固定执行：

```text
git diff --name-status -z --find-renames --find-copies <baseline> [<delivery>] --
git diff --numstat -z --find-renames --find-copies <baseline> [<delivery>] -- <literal paths>
git diff --binary --no-ext-diff --no-textconv --unified=3 <baseline> [<delivery>] -- <literal paths>
```

实时模式额外读取 NUL-delimited porcelain status。未跟踪文件使用固定 `git diff --no-index /dev/null <literal path>` 生成 Patch，不修改 Git Index；Exit Code `1` 只在该类型化命令中表示发现差异。

所有当前路径和 Rename/Copy 原路径必须位于 WorkspacePolicy AllowedPaths。变更文件数、单文件 Patch、累计 Patch、单文件 Preview 字节和 Preview 行数分别受 WorkspaceOperationBudget 与部署配置约束。二进制文件不生成文本 Preview。Git 输出、文件内容、宿主路径和 Patch 不进入异常消息。

## 3. Diff Event Store

每个流绑定完整 WorkProject Scope、ExecutionWorkspace Fingerprint 和 Recovery Generation。Watcher/Worker 重建创建新 Stream Epoch 并发布完整 RESET；普通内容变化发布直接后继 DELTA。Manifest Content Hash 未变化时不增加 Generation，不发布 Event。

事件包含：

- WorkItemScope、ExecutionWorkspaceId、Stream Epoch；
- 单调 Sequence、DiffGeneration、EventId；
- RESET/DELTA、Upsert、Removal、Manifest Hash；
- HMAC-SHA256 不透明 Cursor 和发生时间。

Cursor 闭合 Workspace、Epoch、Sequence 和 Generation。部署密钥跨 Worker 重启保持稳定，旧 Epoch Cursor 返回 RESET；签名篡改、未来 Sequence 和 Generation 不匹配返回 `INVALID_CURSOR`。Event Store 保留有界内存窗口；Cursor 早于窗口时返回当前完整 Manifest，不拼接不完整 DELTA。并发重复 Reconcile 只发布一条 Event。

Event 超限时先移除非权威 Patch Preview；路径、统计、Patch Hash 与 Manifest Hash 保持完整。移除 Preview 后仍超限则拒绝发布。

## 4. 最终固化

WorktreeProvisioner 创建 Delivery Commit 和 Archive Ref 后，Finalizer 执行：

```text
验证 FINALIZING Workspace、CodingTarget、Policy、Principal
  -> 重读当前 ExecutionWorkspace 版本与 Fingerprint
  -> 验证 Archive Ref、Delivery 单父 Baseline 和 Delivery Tree
  -> 从 Baseline/Delivery Commit 重新生成最终 Manifest 与完整 Patch
  -> 写入 Restricted Workspace Patch Artifact
  -> 再次验证 Workspace 与 Commit 对
  -> 发布唯一 DiffArtifact
```

重试先查询 ExecutionWorkspace 的既有 DiffArtifact；相同 Commit 对返回既有结果，不重复发布。不同 Delivery、Baseline 或 CodingTarget 返回 `FINALIZATION_CONFLICT`。ArtifactStore Descriptor 的 ID、Content Type、大小和 SHA-256 必须与写请求完全一致。

Patch Reader、Tombstone、Range、保留期和有界清理已由 M4-I09 完成；Worker 重启后的孤立 Artifact 与 Workspace/Sandbox/Watcher 批量对账由 M4-I10 完成。

## 5. 验证结果

专项测试覆盖：

- 真实 Git 的 tracked、untracked、deleted、rename 和 binary Diff；
- 无变化不增加 Generation，内容变化生成直接后继；
- AllowedPaths、单文件/累计 Patch 和变更文件预算；
- WatchService 启动提示、Debounce、授权路径和无关路径隔离；
- RESET/DELTA Replay、事件丢失窗口、重复、乱序、并发和 Epoch 旋转；
- Cursor 篡改、Generation 错配和 Recovery Generation 切换；
- Baseline/Delivery/Tree 验证、完整 Patch Artifact、最终 DiffArtifact 与幂等重试；
- Worker/all Spring 装配、server Profile 退让和配置上限。

执行命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  '-Dtest=*M4I08Test,WorkspaceDiffConfigurationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

专项测试共 13 项通过。全仓执行 `./mvnw --batch-mode --no-transfer-progress test`，
7 个 Reactor 模块全部成功，共 1,311 项测试通过，0 失败、0 错误、0 跳过。

## 6. 后续边界

M4-I09 已实现 Patch、构建日志和测试报告 Artifact Reader、Tombstone、保留期、Range 与公开摘要。M4-I10 使用当前 `restart -> RESET -> Git Reconcile` 契约完成 Worker 冷启动和在途 Workspace 对账。
