# M3-S01：PostgreSQL 领取与租约验证记录

> 状态：VERIFIED<br>
> 日期：2026-08-12<br>
> PostgreSQL 基线：17<br>
> 关联决策：[ADR-001](../adr/ADR-001-执行状态与租约.md)

## 1. 验证目标

M3-S01 使用真实 PostgreSQL 事务验证 TaskExecution 的唯一领取、配额、所有权、续租、接管和条件提交协议。Spike 使用独立测试表，不创建 `V10` 迁移，不提前形成生产 Repository Adapter。

验收场景包括：

- 两个 Worker 并发领取同一 TaskExecution 时只有一个成功；
- `FOR UPDATE SKIP LOCKED` 跳过已经锁定的候选并领取下一条可执行任务；
- Runtime 并发配额在 Claim 事务内生效；
- Claim Token 明文只返回一次，数据库只保存 SHA-256 Hash；
- 续租保持 Worker、Claim Token 和 Fencing Token 不变；
- 过期 Lease 释放后重新领取生成新 Claim Token，并递增 Fencing Token；
- 旧 Worker、旧 Claim Token、旧 Fencing Token、错误 attempt 和错误版本不能续租或提交结果；
- Complete 与 Lease Sweeper 竞争时只有一个条件状态迁移成功。

## 2. 事务协议

Claim 使用 PostgreSQL `READ COMMITTED`。每次领取事务按以下顺序处理数据库事实：

```text
task_execution candidate
  -> runtime_quota
  -> execution_lease insert
```

`READY` TaskExecution 不允许存在活动 Lease。过期 Lease 由 Sweeper 先把 TaskExecution 条件推进到 `RECOVERING`，删除旧 Lease 并归还配额；恢复决策再把执行重新排队。重新领取不会覆盖既有 Lease。

领取 SQL 顺序为：

```text
SELECT READY candidate
ORDER BY priority DESC, not_before, id
FOR UPDATE SKIP LOCKED
  -> UPDATE runtime_quota WHERE active_count < max_active
  -> UPDATE task_execution SET status = CLAIMED,
       fencing_token = fencing_token + 1,
       version = version + 1
  -> INSERT execution_lease(claim_token_hash, fencing_token, worker, expiry)
  -> COMMIT
```

候选行先于配额行加锁。不同 Worker 可以跳过已经锁定的候选；配额更新仍在同一事务内串行裁决。配额不足时整个事务回滚，候选保持 `READY`。

Heartbeat 只更新 Lease 的 Heartbeat、到期时间和 Lease Version，不更新 TaskExecution Version。Progress、Complete、Fail 和恢复状态迁移校验 TaskExecution Version。`expectedVersion` 表示本次命令所修改事实的版本：Heartbeat 使用 Lease Version，执行状态命令使用 TaskExecution Version。

Spike Harness 通过显式注入的单调逻辑时间复现租约到期边界，避免 `Thread.sleep` 和真实时间窗口造成并发测试抖动。该时间只用于测试 SQL 参数。生产 Adapter 在 `M3-I03` 使用 PostgreSQL 时间作为 Lease 创建、续期、有效性判断和 Sweeper 的唯一时钟，并通过数据库集成测试覆盖 Worker 时钟偏移。

## 3. 条件更新谓词

Worker 续租和结果提交共同校验：

```text
task_execution_id
+ attempt
+ runtime_id
+ worker_id
+ claim_token_hash
+ fencing_token
+ expected_version
+ lease_expires_at > authoritative_now
+ current status
```

Claim Token 使用 CSPRNG 生成，明文不进入数据库、日志、事件或查询结果。数据库保存固定长度的小写 SHA-256。Fencing Token 保存在 TaskExecution 上，每次成功领取或恢复接管加一，普通续租不改变。

Lease Sweeper 只处理已过期 Lease，并使用相同 TaskExecution Version 和 Fencing Token 将执行推进到 `RECOVERING`。Complete 与 Sweeper 都先条件更新 TaskExecution；只有获得该状态迁移的一方释放 Lease、归还 Runtime 配额并产生后续事件事实。

## 4. 索引契约

`V10` 至少建立：

```sql
CREATE INDEX ... ON task_execution
    (runtime_id, priority DESC, not_before, id)
    WHERE status = 'READY';

CREATE UNIQUE INDEX ... ON execution_lease(task_execution_id);

CREATE INDEX ... ON execution_lease(expires_at, task_execution_id);
```

TaskExecution 的 `(task_id, attempt)`、当前有效尝试和 Scope 约束由 `M3-D08` 一并落地。`M3-D09` 使用 `EXPLAIN` 固定 READY 队列与过期 Lease 查询计划。

## 5. 验证矩阵

| 场景 | 预期证据 | 状态 |
|---|---|---|
| 双 Worker 同任务 | 一个 ClaimReceipt，一个空结果 | 通过 |
| 锁定首候选 | 第二 Worker 在首事务提交前领取下一候选 | 通过 |
| Runtime 配额 | 两个 Worker 竞争不同任务仍只有一个领取；释放后继续领取 | 通过 |
| Token 存储 | 返回明文，数据库只有对应 SHA-256 Hash | 通过 |
| Lease 续租 | Owner、Claim Token、Fencing Token 不变，Lease Version 递增 | 通过 |
| 过期接管 | 新 Token、新 Owner、更大 Fencing Token | 通过 |
| 旧 Owner 回写 | 过期续租与旧 Owner/Token/Fencing/attempt/Runtime/version 提交全部为零行 | 通过 |
| Complete/Sweeper 竞争 | 仅一个状态迁移与一次配额释放成功 | 通过 |

## 6. 实现边界

Spike 的测试表、SQL 和 JDBC Harness 只验证 PostgreSQL 语义。正式领域值对象由 `M3-D05` 实现，正式 Schema 与索引由 `M3-D08` 实现，生产 Claim/Heartbeat/Sweeper Adapter 由 `M3-D09`、`M3-I02` 和 `M3-I03` 实现。

## 7. 验证结果

专项验证命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  -Dtest=PostgresTaskExecutionLeaseM3S01IntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：7 个 PostgreSQL 17 Testcontainers 测试通过。测试覆盖唯一 Claim、`SKIP LOCKED`、并发配额、Token Hash、Lease Version、过期接管、全坐标失败关闭和 Complete/Sweeper 竞争，不使用 `Thread.sleep` 或时间窗口推测并发结果。

相关回归执行 `./mvnw --batch-mode --no-transfer-progress -pl crewscope-infrastructure -am test`，Domain 199 个、Application 178 个、Infrastructure 157 个测试全部通过，共 534 个。`node scripts/check-doc-links.mjs` 检查 104 份 Markdown 文件通过，`git diff --check` 通过。

结论：PostgreSQL `READ COMMITTED`、候选行 `FOR UPDATE SKIP LOCKED`、事务内条件配额更新、一次性 Claim Token Hash、单调 Fencing Token、分离的 Lease/TaskExecution Version 和条件终态更新可以作为 M3-D05/D09 与 M3-I02/I03 的实现基线。
