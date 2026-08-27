# M6-A01 Team 与 WorkItem Activity API

> 任务：`M6-A01`<br>
> 日期：2026-08-27<br>
> 状态：完成<br>
> 前置契约：M6-D01、M6-E02、M6-E05、M6-I01、ADR-020、ADR-021

## 1. 交付目标

M6-A01 在 Generation-aware Activity 投影和签名 Team Cursor 上交付权限感知的公开读取边界：

- Team Activity Keyset 历史、单事件详情和同一数据库快照内的有界快照；
- WorkItem Activity Keyset 历史、单事件详情和有界快照；
- Team Activity 可恢复 SSE，支持 `Last-Event-ID` 与 `after` 的一致恢复坐标；
- Cursor 绑定 Organization、Team、Projection、Generation、Projection Schema 和规范 Filter Fingerprint；
- 公开 DTO 只包含已评审 Activity 字段，不返回 Projection 内部状态、原始 DomainEvent Payload、凭证或 Provider Body。

## 2. API 契约

Team 路由：

```text
GET /api/v1/organizations/{organizationId}/teams/{teamId}/activity
GET /api/v1/organizations/{organizationId}/teams/{teamId}/activity/snapshot
GET /api/v1/organizations/{organizationId}/teams/{teamId}/activity/events
GET /api/v1/organizations/{organizationId}/teams/{teamId}/activity/{activityEventId}
```

WorkItem 路由：

```text
GET /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/activity
GET /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/activity/snapshot
GET /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/activity/{activityEventId}
```

Team 列表和 SSE 支持 WorkItem、Category、EventType、Actor Principal 过滤；WorkItem 路由把精确 WorkItem ID 固化进 Filter。过滤条件经过去重、排序和数量上限后形成 Fingerprint。客户端只保存和原样回传不透明 Cursor。

快照返回当前 Generation 的公开行、历史续页 Cursor、同一读取快照的高水位 `snapshotCursor` 和 `hasMore`。历史续页使用最后扫描的耐久位置，隐藏事件不会在下一页重复扫描。客户端完成快照安装后使用 `snapshotCursor` 打开 SSE，关闭安装快照与建立实时连接之间的提交窗口。

## 3. 授权与持续复验

- 所有入口先解析服务端可信 `TeamAccessContext`，并要求当前 ACTIVE TeamMember；
- WorkItem 路由额外验证完整 Organization、Team、WorkProject、WorkItem Scope；
- `TEAM_MEMBERS` 对当前 ACTIVE 成员可见；
- `TEAM_ADMINS` 只对当前平台管理员或持有有效 Team-wide `TEAM_OWNER` / `TEAM_ADMIN` 的成员可见；
- `WORK_ITEM_PARTICIPANTS` 只在当前 WorkItem 仍属于该 Team 且调用者具有当前访问权时可见；
- 不可见详情按不存在处理，避免利用 Event ID 探测受限事实；
- SSE 在提交 HTTP 200 前完成身份、成员资格、Cursor 和首批耐久读取；连接期间每个业务帧和心跳都重新读取当前成员与角色事实，撤权后终止流；
- 隐藏事件仍推进服务端连接位置，但不会生成公开 SSE 帧或暴露隐藏数量。

## 4. Cursor、错误和公开 DTO

- 非规范编码、签名失败、过滤变化、跨 Organization/Team/Projection 使用返回 `400 invalid_cursor`；
- 有效签名 Cursor 因时间、Generation、Schema 或 Retention 失效返回 `410 cursor_expired`；
- `Last-Event-ID` 与 `after` 同时存在且不一致返回 `400 invalid_cursor`；
- JSON 和 SSE 均使用 `Cache-Control: no-store`；
- 公开事件只包含 Event/DomainEvent ID、TeamSequence、EventType、Category、Visibility、Subject、Actor、Reference、发生时间和版本化公开 Payload；
- Projection Name、Generation、内部 Checkpoint、Cursor Scope、原始 Payload、错误正文和凭证不进入公开 DTO。

## 5. 实施结果

- `ActivityApplicationService` 集中处理 Team/WorkItem 当前成员、角色与可见性复验，隐藏行使用底层耐久扫描位置续页；
- `JdbcActivityQueryAdapter.findCurrentById` 只读取精确 Organization、Team、`team-activity` 和 ACTIVE Generation 内的事件；
- `TeamActivityController` 交付历史、快照、详情和 Team SSE，业务帧与心跳均持续复验当前权限；
- `WorkItemActivityController` 在 Cursor 解码前复验完整 WorkProject/WorkItem 路由，并将路由 WorkItem 固化进 Filter；
- `ActivityResponse` 使用显式白名单，不直接序列化领域对象；
- Activity 能力未开启时，依赖 Cursor/Realtime 的历史、快照与 SSE 返回 `503 activity_unavailable`，避免可选 Bean 导致应用启动失败。

## 6. 验证证据

专项测试覆盖：

1. ACTIVE 成员、停用成员、Team Admin 与普通成员可见性；
2. WorkItem 完整路由 Scope 与不可见事件详情；
3. Team/WorkItem 稳定 Keyset、隐藏事件续页和快照高水位；
4. Category/EventType/Actor/WorkItem Filter 与 Cursor Fingerprint；
5. Cursor 篡改、跨 Scope、过期和 `Last-Event-ID` 冲突；
6. SSE 断线补发、重复事件、持续撤权和安全公开 DTO；
7. PostgreSQL 当前 Generation 单事件读取与跨 Organization/Team 隔离。

执行结果：

```text
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-server -am \
  -Dtest='*M6A01*,*M6E05*,*M6I01*' \
  -Dsurefire.failIfNoSpecifiedTests=false test

BUILD SUCCESS
Application Activity/Snapshot: 7 / 7
Infrastructure M6-I01 联合回归: 13 / 13
Server Activity/Cursor/SSE: 20 / 20
```

```text
TESTCONTAINERS_RYUK_DISABLED=true ./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  -Dtest='JdbcQueryAdaptersM6I01IntegrationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test

BUILD SUCCESS
PostgreSQL: 6 / 6
```
