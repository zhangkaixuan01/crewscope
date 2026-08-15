# M3-I02 Claim Scheduler

## 1. 目标与边界

M3-I02 将 M3-D02 的 READY 调度状态、M3-D04/I01 的 Runtime Registry、M3-D05 的 Claim/Lease 协议和 M3-D09 的 PostgreSQL 锁定适配组合为可调用的 `TaskClaimScheduler`。

本任务负责：

- 按 `priority DESC + notBefore ASC + createdAt ASC + executionId ASC` 选择到期 READY TaskExecution；
- 将当前 PolicySnapshot 的 ExecutionCapability 映射为 RuntimeCapabilities；
- 校验 Runtime/Worker 状态、心跳、能力、容量和 Scope 谱系；
- 在数据库事务内裁决 Team、Runtime 与 Worker 并发上限；
- 原子提交 `READY -> CLAIMED`、Fencing Token 递增和 PREPARE ExecutionLease；
- 使用 CSPRNG 生成一次性 Claim Token，明文只进入成功回执；
- 返回有界 `ClaimReceipt` 批次和低基数调度结果指标。

本任务不执行 PREPARE/RUN Heartbeat、Lease 过期清理、Task Token、AgentScope Task Orchestrator 或 JVM Worker 循环。Heartbeat 与过期恢复已由 [M3-I03](M3-I03-Lease-Heartbeat释放与过期恢复.md) 交付；其余能力由 M3-I04、M3-I06 和 M3-I09 交付。

## 2. Claim 事务

每次 Claim 在一个 `READ COMMITTED` 外层事务中执行。生产装配从 PostgreSQL `clock_timestamp()` 读取数据库实时权威时间，Worker 本机时钟和事务行锁等待不能提前 notBefore 或延长 PREPARE Lease：

```text
读取当前 Runtime/Worker
  -> 锁定有界 READY 候选 FOR UPDATE SKIP LOCKED
  -> 读取固定 PolicySnapshot 并解析 RuntimeCapabilities
  -> 校验 Worker claimable 与兼容 Runtime
  -> 获取 Organization Claim 配额事务锁
  -> 统计活动 Team/Runtime/Worker Lease
  -> TaskExecution.claim：递增 Fencing Token
  -> 创建 256-bit Claim Token 和 PREPARE Lease
  -> 条件更新 TaskExecution 并插入 ExecutionLease
  -> COMMIT
  -> 返回 Claim Token 明文一次
```

候选行锁、配额裁决、TaskExecution 条件更新和 Lease 插入处于同一事务。任何一步失败都会回滚全部事实，不返回 ClaimReceipt。

## 3. 排序、扫描与路由

调度器只读取 `notBefore <= authoritativeNow` 的 READY TaskExecution。`maximumBatchSize` 限制一次返回的回执数，`maximumScanSize` 限制为了跳过其他 Team 配额或不匹配 Worker 而检查的候选数。调用方不能突破部署配置的批量上限。

PolicySnapshot 与 Runtime 使用两套边界清晰的能力枚举。Claim 时执行显式映射：

| ExecutionCapability | RuntimeCapability |
|---|---|
| `SESSION_RESUME` | `INTERRUPT_RESUME + SESSION_STATE` |
| `SESSION_FORK` | `SESSION_STATE` |
| `PLAN` | `PLAN` |
| `STRUCTURED_OUTPUT` | `STRUCTURED_OUTPUT` |
| `TOOL_APPROVAL` | `EXTERNAL_TOOL` |
| `SANDBOX` | `SANDBOX` |
| `WORKTREE` | `WORKTREE` |
| `MULTI_REPOSITORY` | `MULTI_REPOSITORY` |
| `CONTEXT_USAGE` | CrewScope 本地执行事实，不增加 Runtime 路由要求 |

当前 Worker 暂时不可领取，但 Registry 中存在能力兼容的 Runtime/Worker 时，TaskExecution 保持 READY，交给其他 Worker 或后续轮询。Registry 中没有任何能力载体时，TaskExecution 进入 `WAITING + RUNTIME`，避免持续占据 READY 队列头部。

## 4. 并发配额

并发配额不维护可漂移的独立计数器。`execution_lease.status = ACTIVE` 是 Team、Runtime 和 Worker 当前占用量的唯一事实源。

每次配额检查先获取 Organization 范围的 PostgreSQL `pg_advisory_xact_lock`，再在当前事务中统计：

```text
Team active leases    < teamConcurrentLimit
Runtime active leases < runtimeConcurrentLimit
Worker active leases  < worker.maxConcurrentExecutions
```

同一事务刚插入的 Lease 会被后续批次候选计入。事务回滚不会留下占用；显式释放或过期释放把 Lease 改为 RELEASED 后自然归还容量。Organization 级锁避免一个批次跨多个 Team 时产生锁顺序死锁，也不阻塞其他 Organization。后续性能数据证明需要时，可以在保持活动 Lease 为唯一事实源的前提下细分锁粒度。

Team 配额不足时跳过当前候选并继续扫描其他 Team。Runtime 或 Worker 配额不足时停止本批次，因为当前 Scheduler 的剩余候选无法使用该固定 Worker 领取。

## 5. Token、Fencing 与 Lease

Claim Token 使用 `SecureRandom` 生成 32 字节随机值并编码为无填充 base64url。数据库只保存其 SHA-256 Hash；领域对象、ClaimReceipt 日志字符串和指标都不输出明文。

TaskExecution 是 Fencing Epoch 的唯一事实源。每次成功 Claim 调用 `TaskExecution.claim`，从空值生成 1，后续重新领取使用 `lastFencingToken.next()`。ExecutionLease 绑定本次已提交的 Runtime、Worker、attempt、Claim Token Hash 和 Fencing Token，不独立分配纪元。

新 Lease 从 PREPARE 阶段开始。TTL 由 `prepareLeaseDuration` 配置，并受领域 `5s..15m` 边界约束。

## 6. 配置与装配

`all` 与 `worker` Profile 创建 `TaskClaimScheduler`；`server` Profile 不创建。默认配置为：

```yaml
crewscope:
  runtime:
    scheduler:
      prepare-lease-duration: 30s
      team-concurrent-limit: 8
      runtime-concurrent-limit: 32
      maximum-batch-size: 8
      maximum-scan-size: 32
```

批量范围、扫描范围、配额和 PREPARE TTL 在 Bean 创建时失败关闭。Scheduler 使用 M3-I01 已注册的 Runtime key、Worker stable key、Actor Principal 和 Heartbeat timeout，不建立第二套部署身份。

## 7. 可观测性

指标 `crewscope.task.claims` 只使用固定 `outcome` 标签：

- `claimed`
- `empty`
- `waiting_runtime`
- `capability_deferred`
- `team_quota`
- `runtime_quota`
- `worker_quota`
- `failed`

Organization、Team、Runtime、Worker、Task 和 Execution ID 不进入指标标签。指标记录失败不会改变已经提交的 Claim 结果。

## 8. 自动化证据

专项测试：

```bash
./mvnw -pl crewscope-server -am \
  -Dtest=TaskRuntimeCapabilityResolverTest,DurableTaskClaimSchedulerM3I02IntegrationTest,RuntimeRegistryConfigurationTest,TaskClaimSchedulerMetricsTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

专项场景覆盖：

- priority/notBefore 公平排序与未来任务过滤；
- 最大 Claim 批量和扫描边界；
- 无能力载体进入 WAITING_RUNTIME；
- 当前 Worker 不匹配但存在其他能力载体时保持 READY；
- Team 与 Runtime 活动 Lease 配额；
- 两个 Worker 并发竞争同一 TaskExecution；
- Claim Token 明文不落库且日志脱敏；
- 释放并重新领取后的新 Token 与单调 Fencing Token；
- Spring Profile 装配与固定低基数指标。

全仓回归：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
node scripts/check-doc-links.mjs
```

最终结果：

- Maven Reactor 7 个模块全部通过；
- 868 tests，0 failures，0 errors，0 skipped；
- 117 个 Markdown 文件链接检查通过。
