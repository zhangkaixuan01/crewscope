# M9-A07 主体目录与成员角色 API 契约

## 主体目录

`GET /api/v1/organizations/{organizationId}/teams/{teamId}/principals?q={prefix}&offset=0&limit=50`

返回当前成员在 Team 作用域内可见的 USER 与 AGENT 主体。`q` 为可选的显示名大小写不敏感前缀（最多 100 个字符），分页使用 offset，单页上限 200。响应字段只有 `principalId`、`kind`、`displayName`、`status`、`roles` 和 `nextOffset`；不返回邮箱、外部身份、最后登录时间、创建人或资源标题。

主体目录复用现有 Team Membership、Principal 和 AgentProfile 权威事实。跨 Team、非活动成员、撤权后的请求即时失效。该端点为只读，不接受 `Idempotency-Key`，使用 `Cache-Control: no-store`。

## 成员角色

`GET /api/v1/organizations/{organizationId}/teams/{teamId}/members`

成员列表响应新增 `roles` 字段，内容是当前有效且处于 ACTIVE 的 TeamRole key，按字典序稳定返回。角色从既有 `MemberRoleRepository` 与 `TeamRoleRepository` 派生；本任务不提供改角色或移除成员的写路径。

参数错误返回 `invalid_request`，无权访问沿用既有 403 边界。响应不暴露凭证、Endpoint、宿主路径或外部 Provider 原始错误。
