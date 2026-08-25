# M6-S03 Inbox 与固定模板通知授权验证记录

> 任务：`M6-S03`<br>
> 日期：2026-08-25<br>
> 结论：领域协议验证通过；M6-S04 已完成 Lark 外部适配协议<br>
> 长期决策：[ADR-022](../adr/ADR-022-Inbox与固定模板通知授权协议.md)（M6-S04 后 ACCEPTED）

## 1. 验证目标

M6-S03 对照 M5 ActionBundle、PlannedAction、Confirmation、Dispatch/Fencing、ActionReceipt 和 ProviderBinding 授权实现，验证：

1. Inbox 可重建来源与成员权威处置使用独立生命周期；
2. 相同来源 Revision 重建和重放只形成一个 InboxItem；
3. 固定模板通知使用闭合的 `POLICY_PREAUTHORIZED` Authorization Snapshot；
4. 相同通知意图收敛为一个 PlannedAction、外部写入和逻辑 Receipt；
5. 所有影响正文、收件人、Provider 权限和策略的事实进入 Digest；
6. 通知策略预授权不扩大 M5 GitHub Action 权限；
7. 最终失败后的人工再次投递创建新命令和历史链。

本 Spike 使用 `crewscope-application` 测试内纯 Java Harness，不创建 M6-D02/D03 生产领域对象、不增加 ActionKind、不创建 V27 表，也不实现 Notification Worker。

## 2. M5 基线与扩展边界

M5 已具备：

- 类型化 PlannedAction 和版本化规范 Digest；
- ActionBundle 对动作顺序、依赖、Review、责任、Binding、Grant 和策略的闭合；
- 当前人类 Owner 对精确 Bundle/Action Digest 的 Confirmation；
- 提交后 Dispatch、Lease、Fencing、UNKNOWN/Reconcile 和唯一 ActionReceipt；
- ProviderBinding 当前授权复验和动作级短期凭证。

通知沿用“先持久化计划、提交后执行、唯一 Receipt、旧 Fencing 零回写”的外部副作用骨架。授权采用明确的联合类型：GitHub 动作携带 Human Confirmation，固定模板通知携带 Notification Authorization Snapshot。生产实现不得用空 Confirmation 或布尔字段混合两条路径。

## 3. Inbox 来源与处置验证

测试模型使用以下稳定来源坐标：

```text
organization + member + itemType + sourceType + sourceId + sourceRevision
```

Generation 11 生成 Review InboxItem 后，成员先标记 `READ` 再标记 `ARCHIVED`。Generation 12 从同一来源重建时产生相同确定性 InboxItem ID，Disposition Store 仍返回 `ARCHIVED`，证明处置事实不依赖投影代际。

同一来源在同一 Generation 投影两次时 Map/未来数据库唯一约束只保留一行。`REVIEW_SUPERSEDED` 事实把该来源变为 Closed 并保存 CloseReason，历史行数保持 1，Open 数量从 1 变为 0。

## 4. Authorization Snapshot 与 Digest

Spike 的 Snapshot 覆盖：

```text
POLICY_PREAUTHORIZED
source identity/revision
template id/version
canonical variable hash
recipient mapping id/version
provider binding id/version
connection grant id/version
team policy id/version
member preference version
deduplication key
```

变量先按名称排序，再使用长度前缀规范编码与 SHA-256。Snapshot 与 PlannedAction 都由服务端 Harness 计算。基线意图重复计划两次返回同一 Action 实例，重复 Dispatch 只执行一次 Provider 写入并返回同一 Receipt。

测试分别改变 Template Version、变量值、Recipient Mapping Version、ProviderBinding Version、Connection Grant Version、Policy Version 和 Preference Version。7 个变化生成 7 个不同新 Digest，均不等于原 Digest，原 PlannedAction 进入 `INVALIDATED`。

## 5. 固定模板与授权失败关闭

Template Registry 保存 `review-required@3` 和 `review-required@4`，变量集合固定为：

```text
workItemTitle
reviewUrl
```

已注册模板和精确变量可以生成 `NOTIFY_COLLABORATION`。以下输入全部拒绝：

- 未注册的 `free-form@1`；
- 额外 `arbitraryBody` 变量；
- 使用通知 Snapshot 计划 `PUSH_BRANCH`；
- 使用通知 Snapshot 计划 `CREATE_DRAFT_PR`。

Spike 同时直接引用生产 `ActionKind.PUSH_BRANCH` 和 `ActionKind.CREATE_DRAFT_PR`，固定两者仍需精确 Human Confirmation。正式 M5 `ActionBundleTest` 与 `ActionDeliveryTest` 联合回归继续验证无 Confirmation 无法 Schedule/写入。

## 6. 失败与再次投递

初始通知取得唯一 `FAILED_FINAL / RECIPIENT_UNAVAILABLE` Receipt。成员使用新 Command ID 再次投递时，Planner：

1. 校验命令引用原失败 Action；
2. 使用当前 Template、Recipient、Binding、Grant、Policy 和 Preference 重新授权；
3. 使用人工再次投递命名空间生成新去重键；
4. 创建带 `redeliveryOf` 的新 PlannedAction 和新 Digest；
5. 相同 Command ID 重放返回同一新动作。

新动作成功后形成第二个 Receipt。原失败 Receipt 的结果和原因保持不变。不同 Command ID 代表成员明确要求的另一轮实际发送，不与自动来源去重键合并。

## 7. 自动化验证

测试文件：

```text
crewscope-application/src/test/java/io/crewscope/application/notification/
  InboxNotificationM6S03Test.java
```

专项命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-application -am \
  -Dtest=InboxNotificationM6S03Test \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：

```text
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

M5 Confirmation 与 Action Receipt 联合回归命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-application -am \
  -Dtest=InboxNotificationM6S03Test,ActionBundleTest,ActionDeliveryTest,ActionWorkerM5I11Test,ActionReconciliationWorkerM5I12Test \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

```text
Tests run: 43, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

根 README 与 `docs` 共 249 份 Markdown 文档链接通过。

## 8. 后续实现边界

- M6-S04 已使用 Loopback Lark API 补齐外部身份、Token、固定模板 HTTP、限流、幂等和查询恢复，并把 ADR-022 转为 `ACCEPTED`；
- M6-D02 实现 InboxSource、InboxItem 和独立 InboxDisposition；
- M6-D03 实现 NotificationTemplate/Intent/AuthorizationSnapshot/Delivery/Receipt 与授权联合类型；
- M6-D08 使用 V27 落地来源/处置隔离、通知唯一约束和历史不可变约束；
- M6-E03/E04 实现 Inbox 与 Notification Intent Projector；
- M6-I03/I06 实现带 Lease/Fencing 的 Worker 与 Lark 固定模板投递；
- M6-A04/F05 提供受权限、ETag 和 Idempotency-Key 保护的设置、历史与再次投递入口。

## 9. 结论

M6-S03 领域协议验证通过。Inbox 来源与成员处置可以在投影重建中独立收敛，固定模板通知可以通过完整 Authorization Snapshot 获得策略预授权，重复投影和调度可以收敛为唯一逻辑结果。授权事实漂移会使旧动作失效，GitHub 动作继续要求 M5 精确 Confirmation，最终失败再次投递保留完整不可变历史。
