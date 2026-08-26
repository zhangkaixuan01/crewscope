# M6-E01 Generation-aware 投影运行时与原子切换

> 任务：`M6-E01`<br>
> 日期：2026-08-25<br>
> 状态：完成<br>
> 前置契约：M6-S01、M6-D07、M6-D08、ADR-020

## 1. 交付目标

M6-E01 将 V27 的 Projection Generation 持久化契约接入生产运行时：

- 每次实时消费从持久化 Registry 动态解析 `ACTIVE/BUILDING/VALIDATING` Generation；
- `ACTIVE` 优先，影子 Generation 按代际升序处理，每个 Generation 使用独立本地事务；
- Generation Receipt、分区 Checkpoint、Projector 副作用和 Fencing Token 在同一事务内收敛；
- DomainEvent 历史使用有界 Keyset 分页重放，进程重启后依据数据库 Receipt 与 Checkpoint 继续；
- 规范 Count/SHA-256/缺口快照在锁定目标 Generation 时持久化；
- 切换使用 Pointer、目标 Generation、旧 ACTIVE、RebuildJob 的固定锁顺序，并在提交前重算目标快照。

Activity、Inbox 和 Notification 的具体事件映射分别由 M6-E02 至 M6-E04 实现；Supervisor Claim、启动恢复和代际清理由 M6-I02 实现。

## 2. 动态路由与事务边界

`GenerationAwareProjectionRouter` 在 Spring 组装时收集唯一 Projection Name 的 `GenerationAwareProjectionHandler`。每个 Handler 持有版本化 `ProjectionDefinition`、Generation-aware 写入逻辑和规范快照编码器。

`GenerationAwareProjectionRunner` 对每个事件查询 `projection_generation`，路由顺序为：

```text
ACTIVE -> BUILDING generation asc -> VALIDATING generation asc
```

每个 Generation 使用 `REQUIRES_NEW` 事务。影子代际发生版本缺口时，已成功的在线代际不回滚，Outbox 投递保持可重试；再次路由时在线代际由 Generation Receipt 去重，影子代际在历史重放补齐后继续。

## 3. Receipt、Checkpoint 与 Fencing

单个 Generation 的事务顺序为：

```text
锁定并复验 Generation 状态/Token
  -> 插入 projection_consumer_receipt
  -> 创建或锁定 projection_generation_checkpoint
  -> 校验 Aggregate Version 和同版本稳定顺序
  -> 执行 Handler 副作用
  -> 推进 Checkpoint
  -> 提交
```

版本缺口、Handler 异常、过期 Token、Handler Definition Version 与目标 Generation 不一致或数据库冲突不保留 Receipt、副作用和 Checkpoint 推进。Definition Version 不一致时运行时失败关闭，避免新版 Handler 污染仍绑定旧契约的代际。校验、取消、失败和切换提升 Fencing Token，旧 Worker 在 SQL 触发器与运行时双重复验下无法迟到写入。

## 4. 历史重放

`JdbcProjectionEventHistoryStore` 从 DomainEvent 与 Outbox 权威事实重建标准 `EventPublication`，使用以下组合 Keyset：

```text
Aggregate Type + Aggregate ID + Aggregate Version + Occurred At + Event ID
```

每页最多 1,000 条。`ProjectionHistoryReplayer` 复用实时 Runner 的 Receipt、Checkpoint、顺序和 Fencing 逻辑，不建立第二套重放语义。页 Cursor 是 Supervisor 后续持久化的恢复位置；正确性仍以每事件 Receipt 与每分区 Checkpoint 为准。

## 5. 校验与原子切换

`JdbcProjectionGenerationLifecycle` 在目标 Generation 写锁内计算 Handler 提供的期望与实际规范快照，持久化 Count、SHA-256、Gap 和失败分区。只有数量与 Hash 一致、两侧零缺口且无失败分区时进入 `VALIDATING`。

切换事务依次锁定 Pointer、目标 Generation、旧 ACTIVE 和 RebuildJob，对比最新目标快照与成功校验快照，然后一次提交：

```text
old ACTIVE -> RETIRED
target VALIDATING -> ACTIVE
Pointer -> target
RebuildJob -> COMPLETED
old/target Fencing Token + 1
```

校验后新事件修改目标快照、乐观版本冲突或并发切换均整体回滚，Pointer 保持原值。

## 6. Outbox 与滚动升级兼容

M0 已有 Outbox Claim 使用 `FOR UPDATE SKIP LOCKED`，Claim 有过期恢复、旧 Token 拒绝、分区顺序和有界并发。E01 保留这一边界，将 Generation Router 作为新的 `DomainEventConsumer` 接入现有 Idempotent Dispatcher。

M0 `CheckpointedProjectionRunner` 继续服务旧 Audit 投影，V27 回填的 Generation 1 与新 Generation Checkpoint 保持不变。后续业务 Projector 逐个注册 `GenerationAwareProjectionHandler`，不需要一次替换全部旧投影。

## 7. 验证

M6-E01 生产运行时专项：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  -Dtest=GenerationAwareProjectionRuntimeM6E01IntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：6 个 Testcontainers/PostgreSQL 测试通过，0 Failure，0 Error，0 Skip。

覆盖：

- 在线代际先提交、影子版本缺口整体回滚；
- 进程重启、有界 Keyset 分页、重复历史重放收敛；
- Handler 失败时 Receipt/副作用/Checkpoint 零残留；
- Handler Definition Version 与 Generation 不一致时在写入任何投影状态前失败关闭；
- 校验提升 Fencing Token，旧 Worker 无写入；
- 校验后数据变化拒绝切换，重新校验后原子切换；
- 两个并发切换请求仅一个修改 Pointer。

与 M6-S01、M0 Checkpoint/Audit、Outbox 和 V27 迁移联合回归共 34 个测试通过，0 Failure，0 Error，0 Skip。关联回归继续覆盖多实例 `SKIP LOCKED`、Claim 过期接管、旧 Claim Token 拒绝和 V26→V27 Checkpoint 回填。
