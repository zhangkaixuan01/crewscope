# M6-D02 Inbox 来源与成员处置契约

> 任务：`M6-D02`<br>
> 日期：2026-08-25<br>
> 状态：完成<br>
> 关联决策：[ADR-020](../adr/ADR-020-投影代际重建与游标协议.md)、[ADR-022](../adr/ADR-022-Inbox与固定模板通知授权协议.md)

## 1. 交付目标

M6-D02 建立五类成员 Inbox 的领域与应用契约：

- `OWNERSHIP`：我的负责；
- `EXECUTION`：我的执行；
- `REVIEW`：待 Review；
- `CONFIRMATION`：待确认；
- `EXCEPTION`：异常。

Inbox 来源行随 Projection Generation 重建。成员处置使用稳定 InboxItem 身份独立保存。查询由服务端把当前 Generation 来源与权威处置合并。

## 2. 稳定来源身份

`InboxSourceKey` 固定包含：

```text
organizationId
memberId
itemType
sourceType
sourceId
sourceRevision
```

字段使用长度前缀规范编码，`InboxItemId` 在 `crewscope:inbox-item:v1` 命名空间内确定性派生。Projection Name、Generation 和 Projection Schema Version 保存在 `InboxItem` 行中，不参与稳定身份派生。

相同来源 Revision 的实时投影、重复重放和影子重建得到同一个 InboxItem ID。来源 Revision 增长、新成员接手或新的来源对象生成新的 InboxItem ID，并拥有独立处置状态。

`InboxSourceRevision` 从 0 开始，提供溢出保护。ItemType 与 SourceType 使用闭合映射：责任来源只能生成负责或执行项，Review 和 Confirmation 使用各自来源，异常只接受 Task、Action 或 Notification Delivery 来源。

## 3. 来源生命周期

`InboxSource` 保存优先级、可选截止时间、打开时间、`OPEN/CLOSED`、关闭原因和关闭时间。截止时间与关闭时间不能早于打开时间。

关闭原因按 ItemType 约束：

| ItemType | 关闭事实 |
|---|---|
| `OWNERSHIP/EXECUTION` | 责任释放、责任替换 |
| `REVIEW` | Review 完成、被替代 |
| `CONFIRMATION` | 已完成、取消、过期 |
| `EXCEPTION` | 异常恢复、人工解决 |
| 全部 | 成员已不具备处理资格 |

关闭返回保留相同 InboxItem ID 的历史行。相同终结事实重复到达保持幂等，不删除来源，也不修改成员处置。

## 4. 成员处置与强 ETag

`UNREAD` 由不存在 `InboxDisposition` 行表示，ETag Version 为 0。首次执行 `READ`、`ACTED` 或 `ARCHIVED` 创建 Version 1 的权威行。后续命令必须提交精确 `expectedVersion`，Repository 使用 Compare-and-Set 插入或更新。

处置顺序单调：

```text
UNREAD -> READ -> ACTED -> ARCHIVED
```

允许跳过中间状态，禁止反向迁移。相同状态和当前 ETag 的重试返回原事实且不增加版本。陈旧 ETag 返回乐观锁冲突。

`InboxDisposition` 保存 Organization、Team、Member 和稳定 InboxItem ID，不包含 Projection Generation。`InboxItemView.merge(...)` 使用无处置行时的 `UNREAD@0` 或已提交处置生成服务端查询结果。

## 5. 授权与持久化边界

`InboxDispositionApplicationService` 每次命令执行以下校验：

1. Actor 是当前 Organization 内可执行的 USER Principal；
2. Actor 对应当前 Team 的 ACTIVE TeamMember；
3. InboxItem 来自请求 Organization、Team 和当前 Projection Generation；
4. InboxItem 的目标 Member 与当前 Actor Membership 完全一致；
5. 已提交 Disposition 与 InboxItem 的 Organization、Team、Member 和 Item ID 完全一致；
6. Repository 使用命令 ETag 完成原子 Compare-and-Set。

平台管理员身份不取得其他成员 Inbox 的处置权。来源关闭、重建、代际切换和旧代际清理均不删除 Disposition。

M6-D08 落地来源表、处置表与数据库唯一约束，M6-E03 实现五类来源 Projector 和合并查询，M6-A02 增加 Idempotency-Key、CommandReceipt 与 HTTP ETag。

## 6. 验证

专项测试：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-domain,crewscope-application -am \
  -Dtest=InboxDomainM6D02Test,InboxDispositionM6D02Test \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：9 个测试通过，0 Failure，0 Error，0 Skip。

覆盖：

- 相同来源重复投影与跨 Generation 稳定身份；
- 新 Source Revision 和责任接替生成独立身份；
- 责任替换、Review 失效、Confirmation 终结与异常恢复关闭；
- 类型、截止时间、Revision 和关闭原因约束；
- READ、ACTED、ARCHIVED 单调迁移和陈旧 ETag；
- 影子重建与来源关闭保留成员处置；
- 跨成员命令拒绝与 Repository 零写入。
