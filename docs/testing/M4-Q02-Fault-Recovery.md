# M4-Q02 Coding 执行故障注入与恢复

> 状态：已完成<br>
> 日期：2026-08-21<br>
> 范围：Worker、Coding Specialist、ExecutionWorkspace、Git Worktree、Docker Sandbox、Diff Watcher、Artifact、TestEvidence 与执行控制

## 1. 验收目标

M4-Q02 使用确定性进程退出点、真实 Git、真实文件 ArtifactStore、固定 Digest Docker Sandbox 和控制命令重放验证 Coding 执行恢复。固定验收不变量如下：

1. PROVISIONING、ACTIVE 与 FINALIZING 中断后收敛到可重试状态或明确失败状态；
2. FINALIZING 在 Archive Ref 创建前后退出均可恢复，同一 Workspace 只保留一个 Delivery Commit；
3. Worktree 创建和恢复的普通失败完成补偿，进程退出残留由冷启动对账闭合；
4. 命令超时后容器进程树终止，Sandbox 在同一受控调用窗口内重新启动；
5. Watcher 丢失、重启、事件缺口和持久化失败通过 Git 权威 RESET 收敛；
6. Artifact 流中断不留下临时文件、引用或锁，重试只发布一个内容对象和一个引用；
7. 同一 CommandEvidence 的不确定提交重试返回已有 TestEvidence；
8. Pause、Resume、Cancel 同键重放不重复修改执行、AgentRun、事件、Artifact 或 TestEvidence。

## 2. FINALIZING 恢复协议

Worker 可能在 Delivery Commit 已创建、Archive Ref 已发布、Worktree 已移除后退出。恢复链路固定为：

```text
Lease Sweep 与 Fencing
  -> ExecutionWorkspace 进入 RECOVERING，保留 FINALIZING 目标
  -> 强制删除旧 Sandbox 和其中的命令进程树
  -> 校验 Archive Ref、Delivery Commit 单父节点和 Baseline
  -> 在 Baseline 上重建同一受管分支与 Worktree
  -> 将 Baseline..Delivery 的二进制 Patch 恢复为 staged changes
  -> 复验恢复后的 Git Tree 等于 Delivery Tree
  -> Git 权威 Diff RESET
  -> 新 Lease 恢复 FINALIZING 并重新注册 Workspace
  -> 幂等 Archive 与 Finalizer 复用原 Delivery Commit、Patch Artifact 和 DiffArtifact
```

空 Patch、文本文件、二进制文件和普通失败补偿均使用同一协议。恢复过程持有 Workspace JVM/OS 双层锁，所有异常路径释放锁。Archive Ref 与 Delivery Commit 保持不可变。

## 3. TestEvidence 不确定提交

TestEvidence 发布前按完整 WorkProject Scope 读取当前 Workspace 的已有证据。已有证据包含同一不可变 `CommandEvidenceReference` 时，发布器直接返回该证据，并跳过 Diff Reconcile、报告写入、数据库创建与 Timeline 发布。

Sandbox 调用窗口保证同一 Workspace 的命令串行执行，数据库继续使用 `(execution_workspace_id, evidence_sequence)` 唯一约束。两层约束共同覆盖 Worker 在事务提交后、收到返回前退出的窗口。

## 4. 固定故障矩阵

| ID | 故障面 | 固定样本 | 收敛结果 | 自动化证据 |
|---|---|---:|---|---|
| `QF-01` | Worker 在 CLAIMED、PREPARING、RUNNING 退出 | 3 | Lease Fencing 后关闭 Run/Step 并重新入队 | `DurableTaskWorkerStartupReconcilerM3Q02Test` |
| `QF-02` | Workspace 在 PROVISIONING、ACTIVE、FINALIZING Archive 前后退出 | 7 | 回滚 Provision；验证 ACTIVE；恢复 FINALIZING Delivery Tree | `CodingWorkspaceStartupReconcilerM4I10Test`、`WorktreeProvisionerM4I03IntegrationTest` |
| `QF-03` | Worktree 创建、恢复、清理、HEAD、Branch 与 Git Pointer 故障 | 6 | 普通失败全补偿，证据冲突失败关闭，锁可立即重用 | `WorktreeProvisionerM4I03IntegrationTest` |
| `QF-04` | Agent 退出、Checkpoint 中断、Snapshot 恢复、Pause、Cancel | 5 | Round 关闭，成功不可误提交，恢复顺序保持 Workspace→State | `CodingSpecialistStepRuntimeM4I12Test` |
| `QF-05` | Sandbox 暂停、旧 Fencing、并发调用、Lease 过期与命令挂起 | 5 | 旧容器删除，超时进程树终止，Sandbox 可继续执行 | `TaskExecutionSandboxFactoryM4I04DockerIntegrationTest` |
| `QF-06` | Watcher 重启、事件丢失、Retention Gap、并发重复与发布失败 | 6 | RESET/DELTA 单调收敛，重复权威事件为零 | `WorkspaceDiffEventStoreM4I08Test`、`WorkspaceDiffWatcherM4I08Test` |
| `QF-07` | Artifact 流中断、并发重试、Diff 元数据中断、TestEvidence 不确定提交 | 5 | 临时状态清零，Artifact、DiffArtifact 与 TestEvidence 唯一 | `FilesystemArtifactStoreIntegrationTest`、`WorkspaceDiffFinalizerM4I08Test`、`TestEvidencePublisherM4A03Test` |
| `QF-08` | Pause、Resume、Cancel 首次提交与各 5 次同键重放 | 18 | 三类控制各提交一次，15 次重放副作用为零 | `MemberTaskCommandServiceM3A04Test`、前端 Task/Coding Store |

固定故障与重放样本共 `55` 项，全部在显式测试超时内收敛。

## 5. 自动化门禁

执行命令：

```bash
nvm use 24
./scripts/m4-q02-fault-gate.sh
```

脚本强制要求 Docker Daemon、固定 Digest Maven 镜像、Node.js 24 和 pnpm。Java 门禁覆盖 AgentScope、Application 与 Infrastructure 真实故障边界；Web 门禁覆盖命令 pending 去重、原 Idempotency Key 重试、409/412 权威回读、Diff 乱序/缺口恢复和 attempt 缓存隔离。Docker 不可用、镜像缺失或 Sandbox 测试跳过均视为门禁失败。

## 6. 验收结果

- 固定故障与重放恢复：`55 / 55`，恢复率 `100%`；
- Worktree 创建与恢复普通失败回滚：`100%`；
- 真实 Docker Sandbox：`10 / 10`，跳过 `0`；
- 命令超时后的延迟越界写入：`0`；
- 孤立测试容器、命令进程和 Workspace 锁：`0`；
- 重复 Delivery Commit、Patch Artifact、DiffArtifact 与 TestEvidence：`0`；
- 重复控制副作用：`0 / 15` 次重放；
- Java 专项：`97 / 97`，其中 Application 11、AgentScope 13、Infrastructure 73；
- Web 专项：`40 / 40`；
- 专项自动化总计：`137 / 137`；
- M4-Q04 全量 `mvn clean verify`：7 个 Reactor 模块全部成功，`1517 / 1517` 项测试通过，失败 `0`、错误 `0`、跳过 `0`。

M4-Q03 继续执行冻结 Coding 评测集，记录模型、环境、预算、成功率、编译、测试、验收、路径、安全、Token、成本、耗时与人工判定。
