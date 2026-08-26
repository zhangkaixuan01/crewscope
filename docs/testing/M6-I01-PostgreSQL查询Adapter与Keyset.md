# M6-I01 PostgreSQL 查询 Adapter 与 Keyset

> 任务：`M6-I01`<br>
> 日期：2026-08-26<br>
> 状态：已完成<br>
> 前置契约：M6-D08、M6-E02 至 M6-E07、ADR-020 至 ADR-022

## 1. 交付目标

M6-I01 把 M6 已冻结的查询 Port 接入 V27/V28 PostgreSQL 对象图：

- Activity 使用 `Organization + Team + Projection + Generation + TeamSequence` Keyset，查询行和 Reference 在固定查询数内重建为安全领域对象；
- Team Activity 快照在同一只读事务快照中读取 Pointer、Projection Schema、高水位和有界数据，断线补发拒绝退休代际、Schema 漂移和已清理位置；
- Inbox 只读取 Pointer 指向的当前代际，并在服务端合并 Generation 外的成员 Disposition；
- Disposition 使用精确版本 Compare-and-Set，并发插入或更新只允许一个提交者成功；
- Notification Plan 按 Deduplication Key、Intent、Delivery 和 Redelivery Command 精确读取，写入动作与投递保持原子和唯一；
- Audit 按 `occurred_at DESC, event_id DESC` 查询和导出，只映射 Registry 允许的安全摘要；Legacy 和 Unregistered 行返回空摘要；
- Operations Health 使用固定查询数聚合 Projection、Outbox、Dead Letter、Cursor 和 Notification，管理员坐标不包含原始 Payload、错误文本或外部正文；
- 所有查询把 Organization、Team、Projection 和 Generation 条件写入 SQL，不依赖 Java 侧过滤实现租户隔离。

M6-I02 继续实现 `ProjectionAdministrationRepository`、`OperationsRecoveryRepository`、Projection Supervisor、启动恢复、Retention/Cleanup 和受审计恢复写命令。这些 Repository 需要把状态变化、Command Receipt、DomainEvent 和 Audit 原子提交，属于 I02 的写侧运维边界。本任务不发送 Lark 消息、不提供 HTTP API，也不改变 V27/V28 迁移。

## 2. 查询与并发约束

Activity 和 Audit 分页都使用 `limit + 1` 探测 `hasMore`，不会使用 Offset。Activity 的 Reference 批量加载保持每页固定两次 SQL；Audit 每页固定一次 SQL。动态组合过滤只拼接受控列和占位符，枚举、UUID 和时间全部通过 JDBC 参数绑定。

Team Activity 快照使用 PostgreSQL `REPEATABLE READ` 只读事务：

1. 锁定同一快照内的当前 Pointer 和 ACTIVE Generation；
2. 读取 Team 当前高水位；
3. 读取不超过高水位的有界过滤行；
4. 返回与 Pointer、Generation、Projection Schema 和 Filter Fingerprint 绑定的 Cursor。

Inbox Disposition 的首次写入依赖唯一键，后续写入依赖 `WHERE version = expectedVersion`。冲突时只在完整 Organization、Team、Member 和 Item Scope 内回读当前版本。

Notification 的动作、Delivery、Receipt 和 Redelivery Receipt 在同一事务提交。唯一键竞态必须回读同 Organization 下的已提交逻辑计划，不允许产生第二个外部动作身份。

## 3. 安全映射

- Activity Payload 必须匹配已评审的公开 Schema；数据库中的未知字段、错误 Schema 或非法 Reference 失败关闭。
- Audit `authorization_context.classification=REVIEWED` 才按 Registry 解析摘要；`UNREGISTERED` 和 Legacy 空 Authorization 固定映射为空摘要。
- Audit 查询不读取 DomainEvent 原始 Payload；`audit_event.payload` 只被当作已投影的安全摘要解析。
- Operations 只返回闭集 Failure Code、计数、版本和恢复目标 Hash，禁止返回 Partition Key、异常消息、通知变量、Provider Body、Credential 或 Cursor Token。
- 所有 Adapter 使用构造器注入并保留必要的持久化边界注释。

## 4. 验证

专项测试使用真实 PostgreSQL/Testcontainers，覆盖：

1. Activity 稳定 Keyset、同微秒/同序列边界、Reference 批量加载和索引执行计划；
2. Snapshot 高水位、空过滤结果、Pointer 切换、旧 Generation 和跨租户拒绝；
3. Inbox 当前代际合并、Disposition 跨代保留和并发 CAS；
4. Notification 唯一逻辑计划、并发去重、终态 Receipt 和 Redelivery Command 回放；
5. Audit 组合过滤、PostgreSQL UUID Keyset、Legacy/Unregistered 空摘要、31 天与 10,000 行边界；
6. Operations 五组件固定查询、Generation/Rebuild/DeadLetter 坐标、跨 Organization 隔离和安全字段扫描；
7. V27 索引在典型 Keyset 查询中产生 Index Scan 或 Bitmap Index Scan。

## 5. 实现结果

- `JdbcActivityQueryAdapter` 同时实现 `ActivityQueryPort` 与 `TeamRealtimeEventStore`，Activity 页固定为事件查询和 Reference 批量查询，快照额外读取 Pointer 与高水位；
- `JdbcInboxRepositoryAdapter` 继续承担当前代际 Inbox 与 Generation 外 Disposition 合并，并保持完整 Scope CAS；
- `JdbcNotificationPlanRepositoryAdapter` 实现四类精确查询、原子保存/更新/漂移替换/再次投递，持久化重建复验 Authorization Digest、Action ID/Digest、Delivery ID 和 Receipt Binding；
- `JdbcAuditQueryAdapter` 实现 PostgreSQL UUID Keyset、组合过滤和有界导出，只读取安全 Audit 投影；
- `JdbcOperationsHealthQueryAdapter` 在同一只读快照内返回五组件、Projection 诊断和每类最多 200 个恢复坐标。

专项命令：

```bash
TESTCONTAINERS_RYUK_DISABLED=true ./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  -Dtest='*M6I01*' -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：10 / 10 通过。测试覆盖 Activity Keyset/代际失效/租户隔离、Audit PostgreSQL UUID 同时刻边界、Legacy/Unregistered 空摘要、Operations 五组件、V27 Activity/Audit 索引计划、Notification 对象图重建、并发 Dedup 收敛、Delivery CAS/终态 Receipt、Redelivery Command 回放和 Digest 篡改失败关闭。Inbox 当前代际与处置跨代保留继续由 M6-E03 的 9 个真实 PostgreSQL 场景联合覆盖。

全仓回归命令：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

结果：Maven Reactor 7 / 7 模块通过，Surefire 2,175 / 2,175 通过，失败 0、错误 0、跳过 0。文档链接 269 份全部通过，`git diff --check` 无异常。
