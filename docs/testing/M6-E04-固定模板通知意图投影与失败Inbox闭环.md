# M6-E04 固定模板通知意图投影与失败 Inbox 闭环

> 日期：2026-08-26<br>
> 范围：`crewscope-application`、`crewscope-infrastructure`<br>
> 结论：通过

## 1. 交付内容

- 建立服务器拥有的 `NotificationIntentPolicyRegistry`，固定映射五类 Inbox 与版本化模板；未登记的来源不能产生通知，`EXCEPTION + NOTIFICATION_DELIVERY` 明确排除以阻断失败通知递归。
- `NotificationIntentProjector` 作为 `member-inbox` Projector 的后置阶段运行，与 Inbox 共用精确 Projection Generation、Schema 和事务边界。
- OPEN Inbox 生成稳定、不可变的 Notification Intent。Intent ID 由 InboxItem ID 确定性派生，模板、变量集合和变量 Hash 在首次投影时钉住，重复事件和并发投影使用数据库唯一约束收敛。
- 仅当前 ACTIVE Generation 可以创建 `POLICY_PREAUTHORIZED` PlannedAction 和 READY Delivery；影子 Generation 只构建 Intent，Pointer 切换后再根据当前事实计划投递。
- 每次计划重新校验 ACTIVE TeamMember、NotificationPreference、DND、Lark MemberMapping、ProviderBinding、Connection、Grant 与 ExternalTenant 的完整 ID/Version/Provider Version 坐标及固定模板通知能力。
- 无偏好、禁用偏好、Mapping 缺失、成员离队、Binding/Connection/Grant/Tenant 撤销或版本漂移均失败关闭；已存在的非终态 Action、Delivery 进入 `INVALIDATED` 并保存不可变 Receipt。
- DND 只推迟 `notBefore`，有效期从推迟后的时间开始计算；相同 Intent 和相同授权快照只产生一个自动逻辑 Action 与 Delivery。
- 当前 Generation 的已钉住模板退役后立即以 `TEMPLATE` 失效旧投递，不改写历史 Intent。新模板版本在下一影子 Generation 构建新 Intent，Pointer 切换后形成新授权 Digest 与替代计划。
- `FAILED_FINAL` Delivery 生成 `EXCEPTION + NOTIFICATION_DELIVERY` Inbox；成功 Redelivery 将失败 Inbox 关闭为 `EXCEPTION_RESOLVED`。失败 Inbox 不进入通知策略，避免“通知失败的通知”循环。

## 2. 固定策略矩阵

| Inbox 类型 | 来源类型 | 固定模板 Key |
|---|---|---|
| `OWNERSHIP` | `RESPONSIBILITY_ASSIGNMENT` | `ownership-assigned` |
| `EXECUTION` | `RESPONSIBILITY_ASSIGNMENT` | `execution-assigned` |
| `REVIEW` | `REVIEW_REQUEST` | `review-required` |
| `CONFIRMATION` | `ACTION_CONFIRMATION` | `confirmation-required` |
| `EXCEPTION` | `TASK_EXECUTION`、`ACTION_DELIVERY` | `exception-alert` |

策略版本固定为 1，自动 Action 有效期固定为 1 小时。MVP 尚未提供团队自定义通知策略；策略矩阵和版本均由服务端发布并参与 Authorization Snapshot。

## 3. 代际与授权流程

```text
member-inbox exact Generation
  -> OPEN Inbox + registered policy
  -> published fixed template + exact safe variables
  -> immutable Notification Intent
  -> active Pointer Generation?
       no  -> stop at Intent
       yes -> Preference / DND
           -> MemberMapping + Binding + Connection + Grant + ExternalTenant revalidation
           -> NotificationAuthorizationSnapshot
           -> deduplicated PlannedAction + READY Delivery

FAILED_FINAL Delivery
  -> EXCEPTION / NOTIFICATION_DELIVERY Inbox
  -> excluded from notification policy
  -> successful redelivery closes Inbox as EXCEPTION_RESOLVED
```

Notification Intent 与 Inbox 共用 `member-inbox` Generation，因为 V27 外键将 Intent 绑定到同代 Inbox 来源。自动通知不能从影子代际提前排队。Pointer 切换完成后调用 `reconcileCurrentGeneration(...)`，让新活动代际基于切换时的当前授权事实开始计划。

## 4. 安全边界

- 模板必须为当前 Intent 精确引用的 `PUBLISHED` 版本，变量名必须位于服务端安全白名单且与模板 Schema 完全相等。
- 链接由配置的 HTTPS Public Origin 生成，模板中的 Trusted Origin 再执行精确 Scheme、Host 和 Port 校验。
- 浏览器、Agent、DomainEvent Payload 和 Provider 不能提交模板正文、变量 Hash、授权 Digest、去重键或授权版本。
- Lark 授权同时绑定 MemberMapping、Binding、Connection、Grant 和 ExternalTenant；Tenant 的 Connection、Grant 与 Provider Version 也必须和 Mapping 快照一致。
- Projector 只写 Intent、Action、Delivery、Receipt 与失败 Inbox，不调用外部 Provider；实际发送、查询恢复和再次投递由 M6-I03 至 M6-I06 实现。

## 5. 验证结果

专项命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  -Dtest=NotificationIntentPolicyRegistryM6E04Test,NotificationIntentProjectorM6E04IntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：7 / 7 通过，其中 Application 2 个、PostgreSQL/Testcontainers 集成 5 个。

覆盖项：

1. 五类固定策略、未知组合失败关闭和通知失败来源递归阻断；
2. 相同来源重复投影只产生一个 Intent、Action 和 Delivery；
3. DND 将 READY Action 的 `notBefore` 延后；
4. Mapping 缺失不产生未授权写入，Mapping 撤销使现有 Action、Delivery 和 Receipt 收敛到 `INVALIDATED`；
5. 模板升级先使活动代际的钉住 Intent 失败关闭，再由影子 Generation 使用新版本，Pointer 切换后生成新 Digest；
6. 影子 Generation 零计划，Pointer 切换后才创建当前 Generation Action；
7. 最终失败只生成一个失败 Inbox，成功 Redelivery 关闭该 Inbox，且不产生递归通知 Intent。

## 6. 后续边界

M6-E05 继续实现 Team Realtime Event Store、签名 Cursor、快照与 SSE 恢复。通知纵向链路由 M6-I01 提供 PostgreSQL Adapter，M6-I03 至 M6-I06 提供 Worker、Lark Client、成员映射和固定模板投递，M6-A04/M6-F05 提供管理入口。
