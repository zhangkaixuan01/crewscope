# M0-E02：Outbox 发布基线

> 日期：2026-08-07<br>
> 状态：已完成

## 目标

将 D06 产生的 PENDING Outbox 转换为可并发、可重试、可恢复的至少一次事件投递，并为 E03 投影器提供事务性幂等消费边界。

## 数据库契约

V4 前向迁移保留 V1–V3，为 `outbox_event` 增加：

```text
claim_token
claimed_by
claim_expires_at
last_error_code
```

投递状态限定为 `PENDING`、`CLAIMED`、`DELIVERED` 和 `DEAD_LETTER`。约束保证 CLAIMED 必须拥有完整租约字段，DELIVERED 必须拥有投递时间，其他状态不携带过期 Claim。

`event_consumer_receipt` 使用 `(consumer_name, domain_event_id)` 主键。回执插入、消费者数据库副作用和事务提交位于同一本地事务。

## 发布流程

```text
回收过期 Claim
  -> 达上限：DEAD_LETTER
  -> 未达上限：PENDING + 指数退避

PENDING 分区队首
  -> FOR UPDATE SKIP LOCKED
  -> CLAIMED + Claim Token + Lease
  -> 事务外并发 EventTransport.publish
  -> DELIVERED / PENDING / DEAD_LETTER
```

Claim、成功确认和失败确认分别使用短事务。成功和失败更新需要匹配 Outbox ID、CLAIMED 状态、Claim Token 和未过期租约。外部发布成功而确认失败时保留至少一次语义。

同一 Topic 和分区键只领取最早的 PENDING/CLAIMED 事件。前序事件处于投递或退避时阻止后续事件，不同分区由有界线程池并发发布。

Worker ID 在 Publisher 创建时完成非空、规范化和 200 字符上限校验，与数据库 `claimed_by` 边界一致。Claim Lease、Initial Backoff 和 Maximum Backoff 不小于 1ms，避免亚毫秒配置在 PostgreSQL 时间精度或毫秒退避换算中退化。

## 配置

Server 提供 `crewscope.outbox.*` 配置，包含批次大小、并发数、租约时长、最大尝试次数和退避上限。E02 完成时 `enabled` 默认为 `false`；E03 注册持久化 AuditEvent 投影消费者后默认启用。

## 验证

`V4OutboxPublicationMigrationIntegrationTest` 覆盖：

1. V4 空库迁移的列、状态约束、索引和消费回执表；
2. V3 存量 PENDING Outbox 升级；
3. 非法状态和不完整 Claim 被数据库拒绝。

`OutboxPublisherIntegrationTest` 覆盖：

1. 两个 Publisher 并发领取 20 个事件时无重复发布；
2. 同一分区的两个事件按 Aggregate Version 顺序发布；
3. 不同分区在配置的并发上限内同时发布；
4. 传输失败按 1 秒、2 秒退避，第三次失败进入 DEAD_LETTER；
5. Claim 后崩溃被租约回收，旧 Token 不能确认新 Claim；
6. 重复消费只执行一次有效副作用；
7. 消费失败时回执和数据库副作用一起回滚，后续可重试。

`OutboxConfigurationContractTest` 额外覆盖 Worker ID 的构造期长度边界，以及租约和退避的 1ms 最小精度。

全仓库执行：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

结果：7 个 Maven 模块成功，79 个测试全部通过。

## 后续

- M0-E03 实现 Projection Runner、ProjectionCheckpoint 和 AuditEvent 最小投影；
- M6 在需要独立消息中间件时保留 EventTransport Port，替换本地传输而不改变 Outbox 协议。
