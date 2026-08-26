# M6-E03 Inbox Projector 与 Disposition 合并查询

> 日期：2026-08-26
> 范围：`crewscope-application`、`crewscope-infrastructure`
> 结论：通过

## 1. 交付内容

- 建立不可变 `InboxEventTypeRegistry`，按 `EventType + SchemaVersion` 精确匹配 16 个已评审事件坐标；Task Retry 的 V1/V2 分别登记，未知事件和未登记 Schema 不读取 Payload、不生成 Inbox 来源。
- 实现 Generation-aware `member-inbox` Projector，使用 `inbox.canonical-v1` 与 `inbox.expected-v1` 规范快照协议。
- 生成 `OWNERSHIP`、`EXECUTION`、`REVIEW`、`CONFIRMATION`、`EXCEPTION` 五类成员来源；相同来源坐标在重复投递、实时代际和影子代际中获得相同 `InboxItemId`。
- Owner/Executor 使用当前 USER Responsibility，Reviewer 使用 Review Context 冻结的 `reviewer_owner_member_id`，Confirmer 和 Action 异常使用 ActionBundle 冻结的 Owner Responsibility，Task 异常使用 WorkItem 当前 ACTIVE Owner。
- 责任释放或替换、Review 完成或失效、Confirmation 完成或取消、Task Retry、Action 成功或人工恢复关闭来源并保留历史行与稳定关闭原因。
- 注册事件缺少必填字段、字段类型错误、UUID 非法、Team 不一致或无法解析精确权威对象时失败关闭，并由 Generation 事务统一回滚来源、Receipt 与 Checkpoint。
- 当前权威 Review、Confirmation、Responsibility、Task 和 Action 已终结时，迟到的打开事件直接生成 CLOSED 来源或执行 close-only，不会重新打开已完成工作；纯成功 Action 不生成无意义异常项。
- 每次 Team 事件投影后收敛非 ACTIVE TeamMember 的 OPEN 来源；`reconcileCurrentEligibility(...)` 支持成员资格变更后的显式收敛，关闭原因为 `MEMBER_NO_LONGER_ELIGIBLE`。
- 新增 PostgreSQL Inbox Repository Adapter，从 `projection_pointer` 读取 `member-inbox` 当前 Generation，并 LEFT JOIN Generation 外 `inbox_disposition`；无处置行返回 `UNREAD@0`，处置写入使用 Version 0 INSERT 或强版本 Compare-and-Set UPDATE。
- `InboxEventProjector` 通过单构造器必需注入 `NotificationIntentProjector`，不保留可空生产依赖或显式 `@Autowired`；Disposition 冲突回读始终使用 Organization + Team + Member + InboxItem 完整坐标。
- 影子重建只替换来源 Generation。Pointer 原子切换后，相同稳定 InboxItem ID 继续合并原有 `READ/ACTED/ARCHIVED` 状态。

## 2. 来源映射

| Inbox 视图 | 来源 | 打开事实 | 关闭事实 |
|---|---|---|---|
| `OWNERSHIP` | `RESPONSIBILITY_ASSIGNMENT` | Owner 分配或替换 | 释放、替换、成员失去资格 |
| `EXECUTION` | `RESPONSIBILITY_ASSIGNMENT` | Executor 分配 | 释放、成员失去资格 |
| `REVIEW` | `REVIEW_REQUEST` | 创建、开始 | 完成、失效、成员失去资格 |
| `CONFIRMATION` | `ACTION_CONFIRMATION` | ActionBundle 已计划 | 已确认、取消、成员失去资格 |
| `EXCEPTION` | `TASK_EXECUTION` | Task 执行失败 | Retry、恢复、成员失去资格 |
| `EXCEPTION` | `ACTION_DELIVERY` | Dispatch 失败或进入人工处理 | 成功、人工成功、取消、成员失去资格 |

责任来源 Revision 固定为 0，用关闭事实终结同一来源。Review 使用 `review_request.revision`，Confirmation 使用 `action_bundle.version`，Task Exception 使用 Execution Attempt，Action Delivery 使用 PlannedAction ID 与 Revision 0。

## 3. 查询与重建协议

```text
projection_pointer(member-inbox)
  -> 当前 inbox_item Generation
  LEFT JOIN inbox_disposition(stable inbox_item_id)
  -> InboxItemView
```

查询闭合 Organization、Team、Projection Name、当前 Generation 和 InboxItem ID。Disposition 继续闭合 Organization、Team、Member 与稳定 InboxItem ID，不保存 Generation。没有 Disposition 行时由服务端合并为 `UNREAD@0`，浏览器不推断处置状态。Compare-and-Set 失败后的 Actual Version 查询同样必须闭合这四级坐标，不允许从同 Organization 的其他 Team 或 Member 读取冲突版本。

`expectedSnapshot(...)` 从规范 DomainEvent 历史重放已评审事件，并使用当前权威责任与成员资格完成终态收敛。`actualSnapshot(...)` 读取目标 Generation。两者使用同一长度前缀规范编码与 SHA-256，排除 Projection Generation、写入时间和 Pointer，因此可以在切换前比较在线代际与影子代际。

## 4. 验证结果

专项命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  -Dtest=InboxEventTypeRegistryM6E03Test,InboxEventProjectorM6E03IntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：9 / 9 通过，其中 Application 2 个、PostgreSQL/Testcontainers 集成 7 个。

覆盖项：

1. Owner、Executor、Reviewer、Confirmer、Task Exception 与 Action Delivery Exception 六种来源映射；
2. 16 个精确事件坐标、Task Retry V1/V2、未知事件和未知 Schema；
3. 责任释放、Review 完成、Confirmation 完成、Task Retry 与 Action 恢复关闭；
4. 迟到 Responsibility、Review 和 Action 打开事件不能绕过当前权威终态；
5. 成员离队关闭 OPEN 来源；
6. 重复事件只生成一个稳定 InboxItem，成功 Action 不生成异常噪声；
7. 无 Disposition 时合并为 `UNREAD@0`，保存后返回 `READ@1`；
8. 有界历史重放构建影子 Generation，规范 Count/Hash 校验通过并原子切换 Pointer；
9. Pointer 切换后继续返回原 `READ@1`，Disposition 行数保持不变。

## 5. 后续边界

M6-E04 在 OPEN Inbox 来源之上实现 Notification Intent Projector，并按固定模板、成员偏好、DND、Lark Mapping 和当前授权事实生成通知 PlannedAction。Inbox 列表、计数、处置命令与安全跳转 API 由 M6-I01 和 M6-A02 继续交付。
