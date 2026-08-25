# ADR-022：Inbox 与固定模板通知授权协议

> 状态：ACCEPTED<br>
> 日期：2026-08-25<br>
> 更新：2026-08-25（M6-S04 完成 Lark 外部身份、凭证、固定模板幂等投递、查询恢复、限流和安全错误验证）<br>
> 关联决策：[ADR-004](ADR-004-CredentialStore与动作凭证.md)、[ADR-005](ADR-005-事件与投影协议.md)、[ADR-006](ADR-006-ProviderBinding解析与授权.md)、[ADR-019](ADR-019-ActionBundle调度与外部结果对账协议.md)、[ADR-020](ADR-020-投影代际重建与游标协议.md)<br>
> 影响里程碑：M6

## 背景

CrewScope 根据责任、Review、Action 和运行异常生成成员 Inbox，并将部分待办通过飞书固定模板通知成员。Inbox 来源可以从 DomainEvent 重建，成员已读和归档属于用户主动形成的状态。通知 Worker 运行在数据库事务之外，可能收到重复意图、发生响应丢失、被策略或连接变更中断，也可能在最终失败后由成员发起再次投递。

通知需要在不逐条人工确认的前提下可靠发送低风险固定模板，同时保持 M5 GitHub Push 与 Draft PR 的成员确认边界。

## 决策

### Inbox 来源与成员处置

Inbox 使用两个独立事实集合：

```text
InboxSource / InboxItem Projection
  = DomainEvent 可重建来源、当前是否仍需处理、优先级、截止时间、关闭原因

InboxDisposition
  = 成员对稳定 InboxItem 身份执行的 READ / ACTED / ARCHIVED 权威事实
```

InboxItem 的稳定唯一坐标为：

```text
organizationId
memberId
itemType
sourceType
sourceId
sourceRevision
```

相同来源 Revision 的重复投影只生成一个 InboxItem。影子 Generation 重建相同坐标时恢复相同 InboxItem 身份，并在查询时与 Generation 外的 InboxDisposition 合并。重建、切换、失败或旧代际清理不修改 Disposition。

责任释放、Review 被替代、Confirmation 终结和异常恢复通过新 DomainEvent 把对应来源关闭并记录稳定 CloseReason。关闭保留 InboxItem 和成员处置历史。新来源 Revision 表达新的待处理事实，并拥有独立处置状态。

### 通知意图与固定模板

Notification Intent 由可审查策略从 Inbox 来源事实产生。MVP 只允许 Registry 中已发布的版本化固定模板。模板声明精确变量 Schema；服务端要求变量集合与 Schema 完全相等，完成类型、长度、链接和转义校验后再渲染。任意正文、未知变量、原始 DomainEvent Payload、Agent 输出和 Provider 原始响应不能进入通知正文。

每次授权计算保存不可变 `NotificationAuthorizationSnapshot`，至少包含：

```text
authorizationMode = POLICY_PREAUTHORIZED
notification source identity/revision
template id/version
canonical variable hash
recipient mapping id/version
provider binding id/version
connection/grant id/version
team notification policy id/version
member notification preference version
deduplication key
```

`NOTIFY_COLLABORATION` PlannedAction Digest 使用带版本的长度前缀规范编码与 SHA-256 覆盖 Action 身份、上述快照和去重键。Template、变量、Recipient Mapping、ProviderBinding、Connection/Grant、Policy 或 Preference 任一变化都会形成新 Digest。旧 PlannedAction/Dispatch 进入 `INVALIDATED`，Worker 必须重新解析当前事实并使用新计划。

### 策略预授权边界

`POLICY_PREAUTHORIZED` 只授权满足全部条件的 `NOTIFY_COLLABORATION`：

1. Notification Intent 来自受支持的 Inbox 来源类型和 Revision；
2. Template ID/Version 已发布且变量严格满足 Schema；
3. Team Policy 允许该通知类型、接收者和时段；
4. 成员 Preference 允许投递或给出明确的 DND 延后时间；
5. Recipient Mapping 经过管理员精确确认且当前有效；
6. ProviderBinding、Connection 和 Grant 当前有效且能力精确包含固定模板成员通知；
7. 当前事实与 Authorization Snapshot 和 PlannedAction Digest 完全相等。

GitHub `PUSH_BRANCH`、`CREATE_DRAFT_PR` 继续使用 ADR-019 的 ActionBundle、当前人类 Owner Gate 和精确 Confirmation。通知授权器不能构造、恢复或调度 GitHub Action，M5 Action Worker 不能接受 `POLICY_PREAUTHORIZED` Snapshot。

### 去重、Dispatch 与 Receipt

相同 Notification Source Revision 和相同授权事实具有稳定自动去重键。重复事件、重建、Outbox 重放和重复调度收敛到一个 PlannedAction、一个逻辑 NotificationDelivery 和一个最终 Receipt。数据库唯一约束至少覆盖 Organization 与 PlannedAction，并对 Provider 支持的稳定业务键建立唯一约束。

Worker 只 Claim 已提交的 Notification Dispatch，使用 Lease 和 Fencing Token。每次外部调用前复验当前 Snapshot。Receipt 保存动作 Digest、结果分类、Provider 安全引用、证据摘要和接收时间；不保存 Token、原始 Body、无限正文或未脱敏 PII。终态 Receipt 只追加且不可改写。

超时、限流和响应丢失进入有界重试或 Provider 查询恢复。能够证明未发生外部写入时才允许安全重试；结果不确定时进入 `UNKNOWN/RECONCILING`；达到自动处理上限后进入 `FAILED_FINAL` 和失败 Inbox。

### 人工再次投递

人工再次投递要求当前成员权限、原 `FAILED_FINAL` Delivery、强版本前置和新的幂等 Command ID。该命令使用当前 Template、Recipient、Binding、Grant、Policy 与 Preference 重新授权，创建新的 PlannedAction、Dispatch、Attempt 和去重键，并保存 `redeliveryOf` 引用。

相同 Command ID 重放返回同一个新动作。不同 Command ID 表达成员明确要求的新一次外部投递。原 PlannedAction、Attempt 和 Receipt 保持不可变，新投递不能覆盖或删除历史失败证据。

### Lark Tenant 与凭证

MVP 使用企业自建应用的 `app_id/app_secret` 调用固定 Endpoint：

```text
POST /open-apis/auth/v3/tenant_access_token/internal
GET  /open-apis/tenant/v2/tenant/query
GET  /open-apis/contact/v3/users/{open_id}
POST /open-apis/im/v1/messages?receive_id_type=open_id
GET  /open-apis/im/v1/messages/{message_id}
```

生产 Base URI 固定为 `https://open.feishu.cn`。Endpoint 不接受用户输入、Connection 元数据或 Provider 响应覆盖。测试 Profile 可以显式启用字面量 Loopback HTTP Endpoint；用户信息、非标准路径、Query、Fragment、元数据地址和其他 Host 全部拒绝。

`app_secret` 只通过 ADR-004 CredentialStore 的动作级短生命周期 Handle 进入 Connector。Token Exchange 完成后清零 Handle 和临时缓冲。`app_id`、`app_secret`、Tenant Access Token、Base URI、Authorization Header 和原始响应 Body 不进入浏览器、Agent、Artifact、日志、Trace、指标、异常或公开 DTO。

Tenant Access Token Cache Key 精确覆盖：

```text
organization
connection id/version
connection grant id/version
credential id/version
expected external tenant key
```

缓存使用 Provider `expire` 并保留至少 60 秒安全余量。401 只清除该精确 Cache Key 并允许一次凭证重取；Connection、Grant、Credential 或 Tenant 坐标变化形成新 Key。每次 API 调用先复验 Connection/Grant 当前状态，撤销后旧缓存不可使用。生产缓存对同一 Key 使用 Single Flight，避免并发刷新风暴。

### Lark 成员精确映射

Connection 通过 `/tenant/v2/tenant/query` 验证当前 Token 对应的 `tenant_key` 与配置完全一致。成员验证只接受 `open_id` 类型，并使用固定 `user_id_type=open_id` 查询精确用户。验证 Proof 同时闭合 Organization、Team、Connection/Grant ID 与 Version、Tenant Key、Open ID、Union ID 和 Provider Version；管理员确认时必须使用生成该 Proof 的同一条当前 Connection/Grant。管理员确认后保存：

```text
organization/team/member
connection id/version
external tenant_key
open_id
union_id（证据）
provider version/verifiedAt
verifiedBy Team Admin
mapping version/status
```

同一 Organization/Team Member 只能拥有一个当前有效 Lark Mapping，同一 Organization 内的 `tenant_key + open_id` 只能映射一个 Team Member。不同 Organization 的映射注册表独立。显示名、姓名、昵称、手机号和模糊邮箱不参与自动绑定。Organization、Team、Tenant、Open ID、Connection/Grant 或验证版本变化需要管理员重新确认。旧 Connection Proof、跨 Team Proof、404、撤权、Tenant 错配和身份冲突使 Preflight 失败关闭。

### Lark 固定模板投递与查询恢复

固定模板在 CrewScope 服务端完成变量 Schema、长度、可信链接 Origin 和 JSON 转义校验。可信 Origin 精确匹配 Scheme、Host 和 Port，默认 HTTPS Origin 只接受默认端口。MVP 使用 Lark `text` 消息形状：

```json
{
  "receive_id": "<exact open_id>",
  "msg_type": "text",
  "content": "{\"text\":\"<server-rendered fixed template>\"}",
  "uuid": "<stable provider idempotency key>"
}
```

Provider `uuid` 由 Organization、Connection、PlannedAction ID/Digest 和 Notification Deduplication Key 规范派生，长度固定且不包含 PII。相同动作在同一 Connection 的重复 Event、Dispatch、Lease 接管和网络重试必须使用同一 `uuid`；不同 Organization、Tenant 或 Connection 形成不同 UUID。自动重试窗口保持在 Provider UUID 去重保留期内；Worker 不能生成新 UUID 重放同一 PlannedAction。

发送成功响应提供 `message_id` 后，Worker 使用 `/im/v1/messages/{message_id}` 查询精确消息存在性。查询结果证明 Provider 接受并保存了该消息，不表达成员已读。Receipt 保存 CrewScope 的 UUID、Message ID、`ACCEPTED`、安全证据码和时间，不依赖 Provider 回传 UUID。

发送响应丢失时，Worker 在同一去重窗口内使用相同 UUID 重试，Provider 返回原 Message ID 后再查询确认。全部尝试仍未取得 Message ID 时进入 `UNKNOWN/RECONCILING`；超过去重窗口或自动上限后进入人工处理，禁止使用新 UUID 自动重发。

### Lark 错误与重试

HTTP 和 Provider 错误归一化为稳定安全分类：

| 外部结果 | CrewScope 分类 | 处理 |
|---|---|---|
| `401` | `AUTHENTICATION_REQUIRED` | 精确 Token Cache 失效并最多刷新一次 |
| `403` | `PERMISSION_DENIED` | 失败关闭并提示管理员检查 Scope/Grant |
| `404` | `RESOURCE_UNAVAILABLE` | Mapping/Message 不可用，禁止模糊查找替代 |
| `429` | `RATE_LIMITED` | 尊重有界 `Retry-After`，使用相同 UUID |
| `5xx` | `PROVIDER_UNAVAILABLE` | 有界指数退避，使用相同 UUID |
| Timeout/连接中断 | `UNKNOWN_DELIVERY` | 相同 UUID 重试或进入对账 |
| 取消/线程中断 | `CANCELLED` | 保留中断标记并停止重试 |
| 非法 JSON/字段缺失 | `INVALID_RESPONSE` | 失败关闭并保存安全证据码 |

错误对象只保存稳定分类、是否可重试和有界 Retry-After。Provider Body、Endpoint、Token、成员 PII 和内部错误文本不进入错误消息。429/5xx/Timeout 的最大尝试次数、退避上限和总去重窗口由受控配置提供。

## 实现约束

1. Inbox Source 表和 InboxDisposition 表使用独立生命周期；Projection Generation 外键不能级联删除 Disposition。
2. Inbox 查询在服务端合并当前 Source Generation 与成员 Disposition，浏览器不能推断或修复处置状态。
3. 同来源的关闭通过状态更新和 CloseReason 完成，投影不得物理删除历史 InboxItem。
4. Notification Intent Projector、Planner、Worker 和 Provider Adapter 分层；Projector 与 Controller 不直接发送消息。
5. Digest 由服务端计算，浏览器、Agent、模板变量和 Provider 不能提交可信 Digest 或授权版本。
6. 模板变量使用确定性排序和规范 Hash；变量值不直接进入指标、日志和 Cursor。
7. 自动去重与人工再次投递使用不同命名空间；再次投递必须引用原失败动作。
8. M6-D03 复用 M5 的 PlannedAction/Dispatch/Receipt 语义时增加独立授权联合类型，禁止使用可空 Confirmation 表达两种授权模式。
9. Lark HTTP Client 只暴露固定类型化操作，禁止通用 Method/URL/Body 透传接口。
10. Tenant Token Cache、成员 Mapping、Provider UUID、Message ID 和 Receipt 全部闭合 Organization/Connection/Grant/Action Scope。
11. Provider 查询确认只证明消息存在和已接受，不推导成员已读或业务动作已完成。
12. M6-D03/D04、I03–I06 按本 ADR 实现，生产 Adapter 不复用 Spike 内嵌 Harness。

## 结果

- 投影重建和代际切换不清除成员 READ、ACTED 或 ARCHIVED；
- 重复来源只产生一个待办和一个自动逻辑投递；
- 所有影响接收者、正文、Provider 权限和策略的事实都进入 Digest；
- 固定模板通知可以使用团队策略预授权，无需逐条人工确认；
- GitHub 写动作保持原成员 Gate 和精确 Confirmation；
- 最终失败可由成员再次投递，历史 Receipt 保持不可变。
- Tenant Token 按完整授权坐标隔离，401 刷新与撤权失败关闭边界明确；
- 成员映射只接受管理员确认的 Tenant/Open ID 精确身份；
- Lark UUID、响应丢失重试和 Message ID 查询收敛为一个 Provider 消息；
- Provider 安全错误不包含凭证、Endpoint、原始 Body 或成员 PII。

## 验证

M6-S03 的 test-only Harness 覆盖 7 个场景：

1. 相同来源在新 Generation 重建后保持 `ARCHIVED`；
2. 重复投影只产生一个 InboxItem，终结事实关闭但不删除历史；
3. 重复通知意图只产生一个 PlannedAction、Provider 写入和 Receipt；
4. Template、变量、Recipient、Binding、Grant、Policy 与 Preference 漂移产生新 Digest 并使旧动作失效；
5. 未注册模板、未知变量和非通知动作不能使用 `POLICY_PREAUTHORIZED`；
6. M5 Push 与 Draft PR 仍要求精确人类 Confirmation；
7. `FAILED_FINAL` 再次投递产生新 Command/Action/Receipt，原 Receipt 不变。

领域验证与 M5 Action 回归见 [M6-S03 Inbox 与固定模板通知授权验证记录](../spikes/M6-S03-Inbox与固定模板通知授权验证记录.md)。

M6-S04 的 Loopback Lark OpenAPI Harness 覆盖 6 个场景：

1. Tenant A/B Token Cache 隔离、完整版本 Key、401 精确刷新和凭证 Handle 清理；
2. Tenant/Open ID 精确成员验证、管理员确认、反向唯一和模糊身份拒绝；
3. 固定模板转义、Timeout 响应丢失、同 UUID 重试、唯一消息和 Message ID 查询；
4. 429/5xx 有界退避、Retry-After 和最大尝试收敛；
5. 撤权、模板漂移、未知变量和非可信链接在 HTTP 写入前失败关闭；
6. 401/403/404/429/5xx 安全归一化、Endpoint Allowlist 和敏感证据零泄漏。

外部适配验证见 [M6-S04 Lark OpenAPI 与通知投递验证记录](../spikes/M6-S04-Lark-OpenAPI与通知投递验证记录.md)。

## 重新评估条件

- 通知扩展到自由文本、Agent 生成正文或任意收件人；
- Provider 不提供稳定外部业务坐标且无法查询发送结果；
- 通知用于审批、法律告知或其他需要逐条人工签署的场景；
- 支持飞书入站消息驱动任务或双向 Channel；
- 一个通知需要跨多个 Provider 原子投递。
