# M4-A07 Runtime Fleet 与运维命令

## 1. 交付范围

M4-A07 在 M3 Runtime Fleet 和 M4-I10 Worker 资源对账之上交付：

- Workspace 最大、活跃与可用容量；
- Sandbox、Diff Watcher 和清理组件健康；
- Team 成员安全摘要与 `TEAM_OBSERVE` 运维明细；
- Actuator 聚合容量与组件健康；
- Organization + Runtime Environment 级 Reconcile 与 Archive 命令；
- 平台管理员授权、CommandReceipt 幂等、DomainEvent/Outbox 耐久审计；
- 低基数观测指标与结构化安全日志。

## 2. Runtime Fleet 投影

`CodingWorkspaceRuntimeOperationsAdapter` 从当前 Worker 的 `CodingWorkspaceRuntimeRegistry`、`RuntimeWorkerRegistrationSpec` 和 `CodingWorkspaceStartupReconciler` 读取同一套运行事实。

成员 `/runtime-health` 的 `codingWorkspaces` 仅包含：

- `HEALTHY/DEGRADED/UNAVAILABLE`；
- Workspace 容量；
- Sandbox 与 Watcher 的总数、健康数和失败数；
- 清理健康和批次容量状态。

`/runtime-health/operations` 增加最新恢复、失败、归档、孤立 Sandbox 清理、Artifact Purge 数量和受限 Java 失败类型。两个视图都省略 Workspace、TaskExecution、Runtime、Worker、Lease、Fencing、容器、宿主路径、Storage URI、argv、Token 和异常正文。

原有 Runtime 等待诊断继续使用 `CAPABILITY_UNAVAILABLE`、`NO_ACTIVE_WORKER`、`DRAINING`、`HEARTBEAT_STALE`、`CAPACITY_EXHAUSTED` 与 `REQUEUE_PENDING`。

## 3. Actuator

`CodingWorkspaceStartupHealthIndicator` 复用相同的本地观察适配器。Health Details 包含 Workspace 容量、Sandbox/Watcher 健康与计数、I10 清理计数、批次容量状态和失败类型。

清理尚未完成、容量用尽、组件失败、清理失败或批次容量触顶时为 `DOWN`。健康且仍有可用容量时为 `UP`。

## 4. 运维命令

```text
POST /api/v1/organizations/{organizationId}/runtime-health/operations/reconcile
POST /api/v1/organizations/{organizationId}/runtime-health/operations/archive
```

两个命令接受可选 `environment`，要求 `Idempotency-Key`，只允许当前 Organization 内的 ACTIVE USER 平台管理员。

Reconcile 顺序固定为：

```text
Lease/Task 启动对账
  -> ExecutionWorkspace RECOVERING 标记
  -> Workspace/Worktree 修复
  -> Sandbox 孤儿清理
  -> Watcher RESET 重建
```

Archive 执行到期 Workspace 有界归档和 Tombstone Artifact 有界 Purge。两个入口直接复用 M4-I10 Reconciler，不建立第二套资源状态。

命令在事务内完成幂等预留、物理操作、`CODING_RUNTIME_RECONCILE_COMPLETED` 或 `CODING_RUNTIME_ARCHIVE_COMPLETED` DomainEvent、Outbox 和 CommandReceipt。相同语义与相同 Key 返回原 Receipt，并跳过物理操作。不同语义复用 Key 使用共享 `idempotency_conflict` 协议。

HTTP 入口先解析当前 Organization 内的可行动 Principal，再判断本进程运维能力。server-only 进程和非本 Worker Environment 使用 `runtime_operations_unavailable` 稳定错误信封。

## 5. 观测与审计

- `crewscope.runtime.observation.requests` 使用 `view/health/workspace_health`；
- `crewscope.runtime.maintenance.commands` 使用 `operation/outcome`；
- Tag 值来自固定枚举；
- Organization、Team、Principal、Correlation ID 只进入结构化日志；
- 日志和指标不包含宿主路径、容器 ID、Workspace ID、Lease、Token、argv 和 Storage URI；
- 成功命令通过 DomainEvent 投影进入耐久 AuditEvent。

## 6. 验证

专项验证覆盖：

- 成员与运维 DTO 白名单；
- Workspace 容量闭合；
- Sandbox/Watcher 失败聚合；
- Worker Scope 不匹配失败关闭；
- 平台管理员强授权；
- Receipt 重放跳过物理操作；
- DomainEvent、Outbox 与 CommandReceipt 完整提交；
- Reconcile 先执行 Lease/Task 对账；
- Archive 复用 I10 权威路径；
- Actuator 与 Runtime Fleet 共用健康事实；
- server-only 稳定降级；
- 低基数指标和路径脱敏。

执行命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-server -am \
  '-Dtest=*M4A07Test,RuntimeObservationControllerM3A07Test,RuntimeObservationRecorderM3A07Test,CodingWorkspaceStartupHealthIndicatorM4I10Test,CodingWorkspaceStartupReconcilerM4I10Test,CodingWorkspaceRecoveryConfigurationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

专项验证通过 28 项测试。提交前复审的 M4-A01–A07 广泛专项与关联回归通过 87 项测试。全仓 `clean verify` 通过 1438 项测试，0 失败、0 错误、0 跳过；175 份 Markdown 文档链接检查通过，`git diff --check` 通过，Docker 受管 Sandbox 无残留。
