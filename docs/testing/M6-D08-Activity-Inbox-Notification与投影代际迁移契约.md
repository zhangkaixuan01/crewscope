# M6-D08 Activity、Inbox、Notification 与投影代际迁移契约

> 任务：`M6-D08`<br>
> 日期：2026-08-25<br>
> 状态：完成<br>
> 前置契约：M6-D01 至 M6-D04、M6-D06、M6-D07、ADR-020、ADR-022

## 1. 交付目标

M6-D08 通过 `V27__activity_inbox_notification.sql` 为团队观测读模型、固定模板通知和可重建投影建立 PostgreSQL 持久化基线：

- Projection Definition、Generation、Pointer、RebuildJob、Validation、Checkpoint、Receipt、DeadLetter 与管理员 CommandReceipt；
- Generation-aware ActivityEvent、ActivityReference、InboxItem 和 NotificationIntent；
- 独立于 Generation 的 InboxDisposition，旧代际清理后继续保留成员处置；
- 版本化 NotificationTemplate/Variable、Preference、PlannedAction、Delivery、Receipt 与 RedeliveryReceipt；
- AuditEvent 分类、保留级别、Provider 安全引用、Keyset 查询索引和追加写保护；
- V26 单代际 Checkpoint 向 Generation 1 的兼容迁移。

本任务只交付数据库结构、约束、迁移兼容和集成测试。Generation-aware Runner、投影器、JDBC Adapter、HTTP API 和前端由 M6-E01 及后续任务实现。

## 2. 投影代际与重建

`projection_definition` 固定 Definition Version、Projection Schema Version、Canonical Encoder 和 Validator。`projection_generation` 使用 `organization_id + projection_name + generation` 定位代际，并通过部分唯一索引保证每个投影最多一个 `ACTIVE` 和一个 `BUILDING/VALIDATING` 影子代际。

`projection_pointer` 保存唯一读代际。延迟约束触发器在事务提交时验证：

- Pointer 必须指向同 Organization、Projection 下的 `ACTIVE` Generation；
- 每个存在 Pointer 的投影必须精确存在一个 `ACTIVE` Generation；
- 切换事务按旧代际退役、目标代际激活、Job 完成、Pointer 推进的顺序提交。

RebuildJob、ValidationResult 和失败分区保存完整 Organization、Projection、Generation、Job 与 Definition 坐标。延迟完整性约束要求 `VALIDATING/ACTIVE` Generation 关联通过的当前校验，校验结果、失败分区和 RebuildJob 互相闭合。

`projection_consumer_receipt`、`projection_generation_checkpoint` 和 `projection_dead_letter` 均携带完整 Generation。写入触发器复验当前 Generation 状态与 Fencing Token，旧 Worker、终态 Generation 和跨租户引用直接失败。`projection_command_receipt` 保存管理员命令指纹、结果坐标与强版本回放事实。

V27 保留 `event_projection_checkpoint` 供现有 Runner 使用，并把已有 Checkpoint 确定性回填到 Generation 1、ACTIVE Generation 和 Pointer。M6-E01 切换到 Generation-aware Runner 后使用新 Checkpoint 与 ConsumerReceipt。

## 3. Activity 与 Inbox

`activity_event` 使用稳定 Activity/Event 身份、Team Sequence、Projection Generation、公开 Payload Schema 和类型化 Subject/Actor。外键将 Organization、Team、DomainEvent 和 Generation 组成闭合租户图。`activity_reference` 保存可公开的类型化证据引用。Team Cursor 与 Subject 查询索引使用 `team_sequence + activity_event_id` 保证 Keyset 稳定顺序。

`inbox_item` 保存五类来源投影、稳定 Inbox Item ID、Source Revision、优先级、期限和关闭事实，并绑定 Projection Generation。`inbox_disposition` 使用稳定 Inbox Item ID 保存 `READ/ACTED/ARCHIVED`、强版本和审计 Principal，不引用可清理的 Generation 行。新代际可以复用同一 Inbox Item ID，旧代际数据清理不会删除成员处置。

## 4. 固定模板通知

Notification 持久化分为四层：

1. `notification_template` 与 `notification_template_variable` 保存版本化固定模板和变量白名单；每个 Server Template Key 最多一个 `PUBLISHED` 版本；
2. `notification_preference` 保存成员级通知偏好、DND 时段和强版本；
3. `notification_intent` 与 `notification_planned_action` 保存 Generation 来源、精确模板、变量 Hash、收件人映射、Binding、Connection、Grant、Team Policy、Preference 和 Authorization Digest；
4. `notification_delivery`、`notification_receipt` 与 `notification_redelivery_receipt` 保存 Claim/尝试、逻辑去重、Provider 安全 Hash、终态回执和再次投递命令历史。

通知 PlannedAction 使用独立表，M5 GitHub `planned_action` 与 Confirmation 协议保持原有结构。PlannedAction 保存 Recipient Mapping、ProviderBinding、Connection、Grant、Team Policy 和 Preference 的完整版本坐标；Member、Intent、Template 与 Connection 使用租户闭合外键，M6-D09 补齐 Lark Mapping 持久化，M6-E04/M6-I03 在计划和投递时复验全部当前授权事实。Delivery 与 Receipt 使用延迟双向约束：终态 Delivery 必须关联一致 Receipt，Receipt 必须匹配同 Organization、Action、Digest 和 Deduplication Key 的 Delivery。状态迁移触发器保护动作、投递、回执和再次投递历史。

## 5. Audit 查询与追加写

V27 为 `audit_event` 增加 `event_category`、`retention_level`、Agent、Provider、Correlation 与 Causation 安全引用。Provider 只保存受控 Provider Key、Operation、Result Code 和外部引用 Hash。Team、Category、Initiator、Agent 与 Provider 查询均提供 `occurred_at + event_id` Keyset 索引。

Audit 追加写触发器拒绝更新和删除，既有审计历史继续有效。Retention Level 作为事件写入时的不可变治理事实。

## 6. 验证

专项迁移与通用 Flyway 门禁：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  -Dtest=V27ActivityInboxNotificationMigrationIntegrationTest,FlywayMigrationIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：10 个测试通过，0 Failure，0 Error，0 Skip。覆盖空库、V26→V27、非默认 Schema、Checkpoint 回填、单 ACTIVE/单影子、Pointer 原子切换、Fencing、跨租户 FK、InboxDisposition 跨代保留、通知完整性、Audit 索引和追加写保护。

关联回归：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  -Dtest=V27ActivityInboxNotificationMigrationIntegrationTest,FlywayMigrationIntegrationTest,ProjectionGenerationM6S01IntegrationTest,CheckpointedProjectionRunnerTest,AuditProjectionIntegrationTest,OutboxPublisherIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：32 个测试通过，0 Failure，0 Error，0 Skip。现有 Outbox Publisher、单代际 Runner、Audit Projector 和 M6-S01 Generation 协议保持兼容。

Infrastructure 全量回归：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am test
```

结果：Infrastructure 581 个测试通过，0 Failure，0 Error，0 Skip；Domain、Application 与 Infrastructure Reactor 全部构建成功。
