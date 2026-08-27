# M6-A02 我的 Inbox API

> 任务：`M6-A02`<br>
> 日期：2026-08-27<br>
> 状态：完成<br>
> 前置能力：M6-D02、M6-E03、M6-I01、ADR-020、ADR-022

## 1. 交付目标

M6-A02 交付当前 Team 成员自己的五类 Inbox HTTP 闭环：

- 查询“我的负责”“我的执行”“待 Review”“待确认”“异常”，支持闭集类型、来源状态和处置状态过滤；
- 使用 `Priority DESC、Deadline ASC NULLS LAST、OpenedAt DESC、InboxItemId DESC` 的稳定 Keyset 分页；
- 返回五类 OPEN 且未归档条目的总数与未读数；
- 返回当前代际详情和强 ETag；
- 接受 `READ / ACTED / ARCHIVED` 单调处置命令；
- 通过 `Idempotency-Key + If-Match` 提供并发保护、语义冲突检测和原 Receipt 回放；
- 把来源对象解析为服务端固定模板的站内路径，并在返回前重新校验当前 TeamMember 与 WorkItem 可见性。

## 2. API

基础路径：

```text
/api/v1/organizations/{organizationId}/teams/{teamId}/inbox
```

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/` | 当前成员 Inbox Keyset 列表 |
| `GET` | `/counts` | 五类总数和未读数 |
| `GET` | `/{inboxItemId}` | 当前代际详情与强 ETag |
| `GET` | `/{inboxItemId}/target` | 重新授权后的站内来源跳转 |
| `PUT` | `/{inboxItemId}/disposition` | READ、ACTED 或 ARCHIVED 命令 |

列表默认只读 `OPEN` 来源。`itemTypes`、`sourceStatuses` 和
`dispositionStatuses` 接受逗号分隔或重复参数。公开 DTO 不包含 MemberId、Projection Name、Generation、Schema、凭证、通知正文或原始事件 Payload。

## 3. 代际、分页与重建

Adapter 先读取 `member-inbox` 当前 Pointer，再在同一只读事务内读取固定 Generation。Cursor 保存 Generation、优先级、截止时间、打开时间和稳定 InboxItemId，并在传输层绑定 Organization、Team 与规范化过滤器。

翻页请求在解码 Cursor 前先重新解析当前 USER Principal 并要求精确 Team 的 ACTIVE TeamMember；未授权请求不能通过 `invalid_cursor` 与有效 Token 的差异推断 Cursor 事实。

影子重建不会复制 `InboxDisposition`。Pointer 切换后：

- 新请求读取新 Generation，并继续合并稳定 InboxItemId 对应的原处置；
- 旧 Generation Cursor 返回 `410 cursor_expired`；
- 已经返回的旧页不会被静默拼接到新 Generation；
- 来源关闭仍可查询详情，且不会丢失 READ、ACTED 或 ARCHIVED 状态。

## 4. 授权与安全跳转

每次列表、计数、详情、命令和跳转都从当前 USER Principal 解析 ACTIVE TeamMember。请求不能提交 MemberId，平台管理员也没有读取或处置其他成员 Inbox 的旁路。

跳转 API 不接受 URL。PostgreSQL Adapter 按 `Organization + Team + Current Generation + Member + InboxItem` 读取来源，再以闭合 SQL Join 解析 WorkItem、Task、Review、Action 或 Notification 坐标。应用层对 WorkItem 目标再次调用 `WorkItemAccessPolicy`，服务端最后生成 `/work` 或 `/settings/integrations` 站内路径。

## 5. 验证

应用与 HTTP 专项：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-application,crewscope-server -am \
  -Dtest='InboxApplicationServiceM6A02Test,InboxControllerM6A02Test,InboxCursorCodecM6A02Test,InboxApplicationConfigurationM6A02Test' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：14 / 14 通过。其中包含离队成员不能通过旧幂等键获取 Receipt、未授权请求不解码非法 Cursor、处置命令拒绝未知字段，以及旧代际 Cursor 的 HTTP `410 cursor_expired` 契约。

真实 PostgreSQL 联合回归：

```bash
TESTCONTAINERS_RYUK_DISABLED=true ./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  -Dtest=InboxEventProjectorM6E03IntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：8 / 8 通过。新增场景覆盖两页 Keyset、五类完整计数、责任来源安全解析、影子重建切换后处置保留和旧 Cursor 失效；既有场景继续覆盖来源终结、成员离队、迟到事件和跨 Generation 合并。
