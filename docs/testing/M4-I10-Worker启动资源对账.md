# M4-I10 Worker 启动资源对账

## 1. 交付范围

M4-I10 在 M3 Lease Sweeper 与 M4 Worktree、Sandbox、Diff、Artifact 生命周期之上交付：

- TaskExecution 重新入队前的 ExecutionWorkspace `RECOVERING` 原子标记；
- PROVISIONING、READY、ACTIVE、FINALIZING 中断后的物理资源闭合；
- FINALIZING 已发布 Archive Ref 并移除 Worktree 后，从同一 Delivery Tree 恢复受管 Worktree；
- 旧 Sandbox 强制删除及其中遗留命令进程树终止；
- Git Worktree 验证、Provision 残留回滚和 Diff RESET 重建；
- Organization 与 Runtime Environment 标签约束的未知 Sandbox 清理；
- 当前 Organization 与 Runtime Environment 内到期 Workspace 幂等 Archive，以及 Tombstone Artifact 有界 Purge；
- 不携带 Workspace、容器、路径或异常正文的启动容量健康信息。

## 2. 启动顺序

Worker 在接受新 Claim 前同步执行：

```text
Runtime Worker 注册
  -> PostgreSQL 权威时间判定 Lease 过期
  -> TaskExecution 进入 RECOVERING
  -> 同一事务锁定关联 ExecutionWorkspace
  -> Workspace beginRecovery 并递增 recoveryGeneration
  -> 关闭孤立 AgentRun/StepExecution
  -> TaskExecution 重新进入 READY
  -> 强制关闭旧 Sandbox 与命令进程树
  -> 回滚中断 Provision，验证保留 Worktree，或从 FINALIZING Delivery Tree 恢复 Worktree
  -> 重建 Diff RESET
  -> 清理有 Organization 与 Environment 归属证明的未知 Sandbox
  -> 归档到期 Workspace，清理到期 Tombstone Artifact
  -> 发布容量健康并开放 Claim
```

`TaskExecutionRecoveryObserver` 在 M3 Reconciler 持有 TaskExecution 行锁时运行。`CodingWorkspaceRecoveryMarker` 使用 TaskExecution Scope 锁定唯一 Workspace，只有 `PROVISIONING/READY/ACTIVE/FINALIZING` 会开始新的恢复代次；已经 `RECOVERING` 或终态的 Workspace 不重复迁移。

## 3. 物理资源闭合

`CodingWorkspaceStartupReconciler` 是 M3 Reconciler 的 Worker-only `@Primary` 装饰器：

- `PROVISIONING` 调用 `rollbackProvisionOrphan`，只有 Worktree、Branch、HEAD、基线与 Policy 完整闭合时才删除；
- `READY/ACTIVE` 先验证 retained Worktree，再使用 Git 权威结果重建一个 RESET，不留下启动期 Watcher 线程；
- `FINALIZING` 验证 retained Worktree；Worktree 已移除时校验 Archive Ref、Delivery Commit 与 Baseline，重建基线 Worktree 并恢复精确 Delivery Tree，后续 Finalizer 复用同一 Commit 与 Artifact；
- 任一恢复资源无法闭合时，Workspace 以 `STARTUP_RECOVERY_FAILED` 明确失败，不保存原始异常和宿主路径；
- 已有 Sandbox 按稳定容器名、WorkspaceKey 和 TaskExecution ID 复验后强制删除，Docker `rm --force` 同时终止中断命令的容器进程树；
- 新 Sandbox 携带 Organization ID 与 Runtime Environment 标签。未知容器清理同时要求 managed、Organization 与 Environment 标签；缺少数据库 Workspace、已 `RECOVERING` 或终态的容器可以删除，其他活动 Workspace 保留；
- 到期 Workspace 复用幂等 Archive Ref/Delivery Commit 协议，成功后进入不可变 `ARCHIVED`；到期 Tombstone Artifact 通过有界 Purge 清理。

重复启动允许重复验证和 RESET，不创建 Delivery Commit、DiffArtifact、CommandEvidence 或 TestEvidence。启动对账没有 shutdown hook；Worker Drain 继续由 M3 执行循环先停止 Claim、等待在途执行并释放所有权，Workspace 不会因 Drain 被归档或删除。

## 4. 容量健康

Actuator 健康信息只返回：

- 是否完成；
- 恢复、明确失败、归档、归档失败数量；
- 清理 Sandbox 和 Artifact 数量；
- 是否触及任一批次上限；
- 最后失败的 Java 类型名。

未完成、归档失败或触及批次上限时为 `DOWN`。详情不包含 Organization、Workspace、TaskExecution、容器、Worker、宿主路径和异常正文。

## 5. 配置

```yaml
crewscope:
  coding:
    recovery:
      recovery-batch-size: 100
      retention-batch-size: 100
      artifact-purge-batch-size: 100
```

Workspace 与 Artifact Purge 批次范围均为 1 至 1000。

## 6. 验证结果

专项测试覆盖：

- Workspace 恢复观察器先于 TaskExecution 重新入队提交；
- ACTIVE/FINALIZING 的 Worktree 验证、Diff RESET 和失败关闭；
- PROVISIONING 残留回滚；
- 重复启动不归档、不发布 Artifact；
- 未知 Organization 与 Environment 归属 Sandbox 清理；
- 保留期 Workspace Archive 与 Artifact Purge；
- 配置上限、Spring Primary 装配与容量健康脱敏；
- M4-I04 真实 Docker 安全契约与零容器残留回归。

执行命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-server -am \
  '-Dtest=*M4I10Test,CodingWorkspaceRecoveryConfigurationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

M4-I10 专项测试共 10 项通过，0 失败、0 错误、0 跳过。全仓 `./mvnw --batch-mode --no-transfer-progress test` 共 1331 项通过，0 失败、0 错误、0 跳过；165 份 Markdown 文档链接检查通过，补丁格式检查通过，受管 Sandbox 容器无残留。

## 7. 后续边界

M4-I11 使用当前恢复基础设施装配 Coding Specialist 与 AgentScope Coding Runtime。M4-I12 在新 Lease/Fencing 下调用 `resumeRecovery`，恢复 Workspace 原中断状态并接续 AgentState、Checkpoint 和执行预算。
