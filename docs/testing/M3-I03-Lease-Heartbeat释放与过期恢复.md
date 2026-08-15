# M3-I03 Lease Heartbeat、释放与过期恢复

## 1. 目标与边界

M3-I03 将 M3-D05/D09 的 ExecutionLease 领域与 PostgreSQL 条件更新组合成可信 Worker 命令边界，交付：

- PREPARE 与 RUN 独立 TTL 的 Heartbeat 续租；
- `CLAIMED -> PREPARING -> RUNNING` 与 Phase 原子切换；
- TaskExecution 非终态 Fencing 条件提交；
- Completed、Failed、Cancelled、Paused、Waiting、Manual Takeover 与 Worker Shutdown 的显式原子释放；
- PostgreSQL 权威时间驱动的有界过期 Sweeper；
- `RECOVERING + RELEASED(EXPIRED) + DomainEvent + Outbox` 单事务提交；
- 周期 Sweeper 生命周期和固定低基数指标。

本任务不自动决定重新排队、后继尝试或人工处理。恢复事件用于触发 AgentRun、Snapshot、ExecutionWorkspace 与 PlannedAction 对账；相应证据由 M3-I07、M3-I08、M3-I09 和 M4 接入。

## 2. Worker 命令与版本语义

`LeaseCommandScope` 固定：

```text
Organization + RuntimeEnvironment + LeaseId
TaskExecution + attempt + Runtime + Worker + ClaimTokenHash + FencingToken
```

命令版本只对应直接修改的事实：

| 命令 | TaskExecution Version | Lease Version |
|---|---:|---:|
| Prepare / Progress | required | 不参与竞争 |
| Heartbeat | 不修改 | required |
| Start / Phase Switch | required | required |
| Complete / Fail / Cancel / Pause / Wait / Shutdown | required | required |

Heartbeat 可以和 TaskExecution Progress 并发推进，避免周期性续租制造执行进度冲突。TaskExecution 条件 SQL 验证 Lease 仍为 ACTIVE、完整 Owner 坐标一致且 `expiresAt > authoritativeNow`。旧 Claim Token、旧 Fencing Token、错误 Worker、错误 attempt、已释放或已过期 Lease 都不能提交。

## 3. Phase TTL 与抖动余量

默认配置：

```yaml
crewscope:
  runtime:
    scheduler:
      prepare-lease-duration: 30s
      run-lease-duration: 30s
      lease-heartbeat-interval: 10s
      lease-heartbeat-jitter-tolerance: 5s
      lease-sweeper-interval: 5s
      maximum-sweep-size: 100
```

启动校验保证：

```text
heartbeatInterval + heartbeatJitterTolerance < prepareLeaseDuration
heartbeatInterval + heartbeatJitterTolerance < runLeaseDuration
```

PREPARE TTL 保持在 5 秒至 15 分钟，RUN TTL 保持在 5 秒至 10 分钟。每次续租都从 PostgreSQL `clock_timestamp()` 数据库实时时钟重新计算边界；达到 `now == expiresAt` 后立即失去所有权。

## 4. 过期恢复事务

```text
PostgreSQL clock_timestamp()
  -> 锁定 expiresAt <= now 的 ACTIVE Lease：FOR UPDATE SKIP LOCKED
  -> 领域模型再次验证精确过期边界
  -> TaskExecution.beginRecovery
  -> ExecutionLease.expire
  -> 条件提交 RECOVERING + RELEASED(EXPIRED)
  -> 写入 TASK_EXECUTION_RECOVERY_STARTED DomainEvent
  -> 写入对应 Outbox
  -> COMMIT
```

不同 Sweeper 使用 `SKIP LOCKED` 分摊批次。重复 Sweep 不再看到已释放 Lease。Complete 与 Sweeper 竞争时，TaskExecution Version、活动 Lease 与释放终态保证只提交一种结果。恢复事件的幂等键绑定 Lease ID，事件与 Outbox 不包含 Claim Token 明文。

## 5. Spring 装配与指标

`all/worker` Profile 创建：

- `TaskExecutionLeaseCoordinator`；
- `ExecutionLeaseSweeper`；
- `ExecutionLeaseSweeperLifecycle`。

`server` Profile 不创建 Worker Lease 组件。指标 `crewscope.task.lease.operations` 只包含固定 `operation` 与 `outcome` 标签，不包含 Organization、Task、Execution、Lease、Runtime 或 Worker ID。

## 6. 自动化证据

专项验证：

```bash
./mvnw -pl crewscope-server -am \
  -Dtest=DurableExecutionLeaseM3I03IntegrationTest,ExecutionLeaseCoordinatorSpecTest,ExecutionLeaseSweeperLifecycleTest,RuntimeRegistryConfigurationTest,TaskExecutionLeaseMetricsTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

覆盖场景：

- PREPARE/RUN 独立 TTL 与 Heartbeat 不修改 TaskExecution Version；
- Heartbeat interval 加抖动余量的启动失败关闭；
- 错误 Claim Token、过期 Heartbeat 与旧 Fencing Owner 回写拒绝；
- 释放后重新 Claim 使用更大的 Fencing Token；
- Complete 与 Sweeper 竞争只产生一个释放事实；
- 两个 Sweeper 并发分摊、重复 Sweep 幂等；
- 唯一恢复 DomainEvent/Outbox 与 Token 脱敏；
- 周期 Lifecycle、Profile 装配和固定低基数指标。

最终全仓验证：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

验证结果：7 个 Maven 模块全部构建成功，共执行 875 个测试，0 Failure、0 Error、0 Skipped。文档链接检查覆盖 118 个 Markdown 文件并通过。
