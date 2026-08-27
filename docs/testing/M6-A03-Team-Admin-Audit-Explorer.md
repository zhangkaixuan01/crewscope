# M6-A03 Team Admin Audit Explorer

## 1. 交付范围

M6-A03 交付 Team 范围的安全 Audit 查询与治理导出 HTTP 边界：

- `GET /api/v1/organizations/{organizationId}/teams/{teamId}/audit-events`；
- `POST /api/v1/organizations/{organizationId}/teams/{teamId}/audit-events/export`；
- 时间、Category、Outcome、Initiator、Actor、Agent、Subject、ProviderBinding 与 Correlation 组合过滤；
- `occurredAt DESC, eventId DESC` 稳定 Keyset；
- 最多 31 天、10,000 行的 JSON 导出；
- 查询与导出自身 Audit。

## 2. 授权与 Cursor

查询复用 M6-D06 当前授权：每次请求重新检查 Organization、Team Membership、Role 与 MemberRole；读取要求 `AUDIT_READ`，导出要求 `GOVERNANCE_EXPORT`。平台管理员仍受 Organization 范围约束。

Audit Cursor 使用 HMAC-SHA256，签名域为 `crewscope:audit-cursor:v1`，绑定 Organization、Team、规范化 Filter SHA-256、OccurredAt 和 AuditEventId。服务端先完成当前权限复验，再解码范围信息；篡改、跨 Team、跨 Organization、过滤条件变化和已移除 Key 均返回统一 `invalid_cursor`。

## 3. 公开数据与自身 Audit

查询和导出共享 `AuditEventResponse` 白名单，仅返回事件身份、Category、Outcome、Retention、身份链、Subject、Provider 安全引用、Correlation 与 Registry 脱敏摘要。不返回原始 Payload、Authorization Context、Credential、Endpoint、Trace ID、Provider Body 或冗余 Organization/Team 坐标。

查询追加 `AUDIT_EXPLORER_QUERIED@1`，导出追加 `AUDIT_EXPORT_GENERATED@1`。两者属于 `SECURITY/EXTENDED`，摘要严格为：

```text
operation
result
rowCount
```

成功请求必须成功写入自身 Audit，否则请求失败关闭。授权或查询失败时尝试记录 `DENIED/FAILED`；记录失败作为原错误的 suppressed evidence，不覆盖安全错误响应。当前 Registry 为 100 个精确坐标：M6-E06 的 96 个、M6-E07 的 2 个和 M6-A03 的 2 个。

## 4. 验证结果

新增 16 项测试，覆盖：

- 查询、导出成功与失败自身 Audit、Correlation 和 RowCount；
- 授权拒绝、Adapter 失败、Audit Sink 失败关闭与主错误保留；
- Cursor Round Trip、篡改、跨 Scope、跨 Filter、Key 轮换与 Key 移除；
- 组合过滤、公开 DTO 白名单、签名 Next Cursor；
- 导出媒体类型、附件名、31 天与 10,000 行边界；
- 非法时间、Subject 配对、403 与未知异常安全响应；
- Spring 构造器装配与 Cursor Codec 条件装配；
- PostgreSQL 自身 Audit 读回、三字段摘要与跨 Team 隔离。

定向验证共 26 项测试通过：Application 9 项、Server 10 项、Infrastructure PostgreSQL 联合回归 7 项。主代码与测试代码均使用 Java 17 编译通过。
