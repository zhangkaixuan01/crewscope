# M0-E03：Audit 投影基线

> 日期：2026-08-07<br>
> 状态：已完成

## 目标

将 E02 发布的 DomainEvent 投影为追加写 AuditEvent，通过持久化 ProjectionCheckpoint 保证分区顺序、缺口检测和进程重启恢复。

## 组件

```text
ProjectionEventJsonMapper
  -> 校验 DomainEvent Topic 以及 EventPublication 与规范 Envelope 一致

CheckpointedProjectionRunner
  -> 锁定 Checkpoint
  -> 检查投影位置
  -> 执行 ProjectionHandler
  -> 推进 Checkpoint

AuditEventProjector
  -> 追加 AuditEvent
```

`ProjectionHandler` 是稳定投影扩展点。M0 注册 `audit-event-v1`，对应消费者名称 `projection:audit-event-v1`。

## 原子事务

```text
INSERT event_consumer_receipt
  -> INSERT checkpoint ON CONFLICT DO NOTHING
  -> SELECT checkpoint FOR UPDATE
  -> INSERT audit_event
  -> UPDATE checkpoint
  -> COMMIT
```

五个操作在同一本地数据库事务中。JSON 校验、版本缺口、投影写入或 Checkpoint 推进任一失败时，回执、投影副作用和 Checkpoint 全部回滚。

Audit Projection 只消费 `crewscope.domain-events.v1`。错误 Topic 在创建消费副作用、Checkpoint 和 AuditEvent 前被拒绝，为后续引入实时事件或多传输路由保留明确边界。

## 顺序契约

Checkpoint 主键：

```text
organizationId + projectionName + partitionKey
```

投影位置：

```text
aggregateVersion + occurredAt + eventId
```

处理规则：

1. 新分区从 Aggregate Version 0 开始；
2. 同一聚合版本可包含多个 DomainEvent，按 OccurredAt 和 Event ID 继续推进；
3. 下一聚合版本必须是当前版本加一；
4. 低于当前位置的过期重放不重复执行副作用；
5. 高于期望版本的事件触发 `ProjectionGapException`；
6. Checkpoint 使用 `aggregate-version:{n}` Cursor，并同时保存 Event ID 和 OccurredAt。

## AuditEvent 映射

AuditEvent 保留 DomainEvent 的 Organization、Team、Workspace、EventType、Subject、Actor、Correlation、Causation、SchemaVersion、OccurredAt 和 Payload。M0 领域事件的 Outcome 为 `SUCCEEDED`，AuthorizationContext 为空 JSON Object。

- USER Actor 同时写入 Principal、Initiator 和 Actor；
- Personal/Team/Specialist Agent Actor 写入 Principal、Actor 和 Agent Principal；
- DomainEvent 未提供的 Initiator、Credential Subject 和 Trace 保持为空。

## Server 接入

`ProjectionConfiguration` 将 Audit Runner 注册为 `DomainEventConsumer`。`InProcessEventTransport` 通过 E02 的事务性幂等边界调用 Runner。Server 的 `crewscope.outbox.enabled` 默认值在 E03 调整为 `true`。

## 验证

`AuditProjectionIntegrationTest` 使用真实 PostgreSQL 覆盖：

1. DomainEvent→Outbox→Publisher→Receipt→Audit→Checkpoint 端到端闭环；
2. 重复投递和 Runner 重启不重复生成 Audit；
3. 版本缺口使 Receipt、Audit 和 Checkpoint 一起回滚，按序重放后成功；
4. 同 Aggregate Version 的多个 DomainEvent 按序生成多条 Audit；
5. 低于 Checkpoint 的过期重放不使位置回退；
6. ProjectionHandler 失败时其数据库副作用、Receipt 和 Checkpoint 一起回滚；
7. USER 和 Specialist Agent 正确映射 Initiator、Actor 和 Agent Principal。

`CheckpointedProjectionRunnerTest` 额外验证 Projection Name 在 Runner 创建时固定，并拒绝带首尾空白的名称，保证 Consumer Receipt 与 Checkpoint 始终使用同一投影身份。

`ProjectionEventJsonMapperTest` 验证规范 DomainEvent Topic 可映射，其他 Topic 在进入投影事务前被拒绝。

全仓库验证命令：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

实际结果：7 个 Maven 模块成功；E03 包含 7 个 PostgreSQL 集成测试、2 个 Runner 契约测试和 1 个 Topic 边界测试。

## 后续

- M1 和 M2 在 `ProjectionHandler` 上增加 WorkItem Activity、Conversation 和 Team 读模型；
- M6 完成投影重建、失败收件箱、运维重放和延迟指标。
