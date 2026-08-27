# M6-A07 Correlation 查询与 Task Timeline 白名单

## 1. 交付结果

M6-A07 交付 Team 成员可用的 Correlation 查询：

```text
GET /api/v1/organizations/{organizationId}/teams/{teamId}/correlations/{correlationId}
```

查询把同一 Correlation 下已评审的 DomainEvent 和不对应 DomainEvent 的直接 Audit
事实合并为一个倒序 Keyset 页面。Audit 对 DomainEvent 的投影行按 `domain_event_id` 去重，
不会与权威 DomainEvent 重复展示。

公开对象使用闭合集合：Conversation、WorkItem、Task、Review、Action、PullRequest、
Activity、Inbox、Notification 和 Audit。事件携带正向对象引用；页面对象携带当前页相关
Event ID，形成可验证的双向图。所有 `href` 都由服务端固定 `/activity` 路由模板生成，
不接受数据库、Payload 或客户端提供的 URL。

## 2. 授权与隐私

每次首读和翻页都重新验证当前 Organization 用户与 ACTIVE Team Membership。服务在授权后
解析签名 Cursor；Cursor 使用独立 HMAC 签名域并绑定 Organization、Team、Correlation、
OccurredAt、EventId 和 Source，拒绝篡改、跨 Team 与跨 Correlation 重放。

Activity 只读取当前 `team-activity` Generation。Inbox 和 Notification 只读取当前成员在
当前 `member-inbox` Generation 中的对象，不公开其他成员的私人待办或通知。PullRequest
引用使用内部 `external_result.id`，不返回外部 PR ID、Business Key、Repository 或 Provider
身份。

## 3. 公开白名单

Correlation 事件类型复用 `AuditEventTypeRegistry` 的精确 `EventType + SchemaVersion` 已评审
坐标。查询只返回 Event ID、Source、EventType、Actor Type、可选 Actor ID、可选 Outcome、
OccurredAt 和类型化对象引用。以下内容不会进入响应：

- DomainEvent、Audit、Activity、Notification 原始 Payload；
- Authorization Context、Credential、Token、Hash、Trace 和 Provider Body；
- Projection Name、Generation、Schema 内部坐标、Connection 和 Grant；
- Provider 外部 ID、PR Business Key、Endpoint、Repository 与任意外部 URL。

Task Timeline 沿用 M3/M4/M5 已交付的 JSON History 与 SSE。`TaskPublicEventMapper` 现在同时
提供冻结的 EventType 白名单，JDBC 在分页 SQL 中先过滤未知事件，再映射字段白名单。
未来或未注册的 Task Event 只推进内部耐久流，不进入公开历史页，也不会导致整页失败。

## 4. 分页与查询预算

Correlation 按 `occurredAt DESC, eventId DESC, source DESC` 排序，单页为 1–100 条。Adapter
在 REPEATABLE READ 中执行一条候选事件 SQL 和至多一条批量对象丰富 SQL。第二条 SQL 一次
解析当前页全部 Activity、ActivityReference、当前成员 Inbox、NotificationIntent 和
PullRequest 内部引用；查询次数不随页内事件数量增加，不存在逐事件或逐对象 N+1。

原始 Payload 只在 Infrastructure Adapter 内用于固定 UUID 字段映射：
`conversationId/sourceConversationId`、`workItemId`、`taskId`、
`reviewRequestId/sourceReviewRequestId`、`actionBundleId/plannedActionId`。不执行任意 JSON
遍历，非法 UUID 和未知字段直接忽略。

## 5. 验证

新增或扩展测试覆盖：

- `CorrelationQueryServiceM6A07Test`：每页持续授权与当前成员 Inbox Scope；
- `CorrelationPageM6A07Test`：闭合对象类型、正反向边和分页不变量；
- `CorrelationCursorCodecM6A07Test`：Round Trip、篡改、跨 Team、跨 Correlation；
- `CorrelationControllerM6A07Test`：No-store、安全 DTO、固定内部链接和敏感字段缺失；
- `TaskPublicEventMapperM6A07Test`：未知事件失败关闭和字段/嵌套形状白名单；
- `JdbcQueryAdaptersM6I01IntegrationTest`：真实 PostgreSQL 同链分页、未知事件跳过、
  当前 Activity Generation 和双向对象引用。

执行命令：

```bash
./mvnw -pl crewscope-application,crewscope-infrastructure,crewscope-server -am \
  -Dtest='Correlation*Test,TaskPublicEventMapperM6A07Test,JdbcQueryAdaptersM6I01IntegrationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw clean verify
node scripts/check-doc-links.mjs
git diff --check
```
