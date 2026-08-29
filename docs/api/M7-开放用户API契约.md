# M7 开放用户 API 契约

> 状态：冻结<br>
> 版本：V1<br>
> 日期：2026-08-29<br>
> 适用范围：单 Organization 自托管 Local/OIDC 浏览器 Session

## 1. 通用协议

所有业务响应使用 `Cache-Control: no-store`。浏览器先调用 `GET /api/v1/auth/session` 建立匿名 Session 并取得 CSRF 坐标，随后在所有写请求中按返回的 Header 名提交 CSRF Token。Local 与 OIDC 使用相同的 Cookie/Header CSRF 协议。

请求与响应使用 `application/json`。公开 DTO 是闭合集合：未知字段、拼写错误字段和客户端提交的 Organization、Principal、Account、Membership、Role、Permission、Credential、Session 等服务端坐标返回：

```json
{
  "code": "invalid_request",
  "message": "Request could not be decoded",
  "correlationId": "d6c6651b-e35c-40dd-bd68-5ca559bc7a96",
  "retryable": false,
  "currentVersion": null,
  "details": {}
}
```

所有错误响应使用稳定的小写 `snake_case` Code，并携带 `X-Correlation-Id`。请求值、密码、Token、账号状态、SQL、异常消息和内部路径不进入错误信封、日志、Trace 或指标标签。

## 2. Session 与登录

### 2.1 `GET /api/v1/auth/session`

匿名和已认证用户均可调用。匿名响应提供 Registration Mode 与 CSRF 坐标；已认证响应从数据库重新解析当前 Account、Principal、Organization Binding、Team Membership、Role Grant 和 Permission。

```json
{
  "authenticated": false,
  "registrationMode": "OPEN",
  "csrf": {
    "headerName": "X-XSRF-TOKEN",
    "parameterName": "_csrf",
    "token": "opaque-csrf-token"
  },
  "account": null,
  "principal": null,
  "teams": [],
  "permissions": []
}
```

已认证 `account` 固定包含 `accountId`、`username`、`displayName`、`platformRole`、`securityVersion`、`version`；`principal` 包含 `principalId`、`organizationId`；每个 Team 包含 `teamId`、`name`、`memberId`、`permissions`。

Session 不暴露 Java 枚举名。每个 `teams[].permissions` 使用稳定的小写产品能力键：ACTIVE TeamMember 固定获得 `scope:read`、`team:members:read`、`work-projects:read` 与 `work:read`；TeamRole 继续投影 `conversation:use`、`team:members:manage`、`work-projects:manage`、`work:create`、`work:participate`、`responsibility:manage`、`repositories:manage`、`agent:manage`、`provider:manage`、`audit:read` 和 `governance:export`。顶层 `permissions` 只发布账号级能力，当前仅允许持久化 `PlatformRole.OPERATOR` 产生 `operations:manage`，禁止合并多个 Team 的权限。AuthStore 使用当前 URL/Scope 选中的 Team 权限裁剪导航与操作入口，切换 Team 时原 Team 能力立即失效；服务端仍按当前数据库事实重新授权每个请求。

### 2.2 `POST /api/v1/auth/login`

请求字段：

| 字段 | 约束 |
|---|---|
| `identifier` | 必填，最长 1024；可提交用户名或邮箱 |
| `password` | 必填，最长 512 |

成功返回 `200`：

```json
{
  "authenticated": true,
  "accountId": "f3409bd3-931f-4c70-b9da-45333640133b",
  "displayName": "Alice"
}
```

服务端在成功登录时旋转 Session ID。未知账号、错误密码、临时锁定、持久锁定和禁用账号统一返回 `401 invalid_credentials`。

### 2.3 `POST /api/v1/auth/logout`

要求已认证 Session，成功返回 `204`。只删除当前浏览器 Session，不影响其他设备。

## 3. 注册

### 3.1 `POST /api/v1/auth/register`

要求单值 `Idempotency-Key`。请求字段：

| 字段 | 约束 |
|---|---|
| `username` | 必填，3–64 |
| `email` | 必填，最长 254 |
| `displayName` | 必填，最长 200 |
| `password` | 必填，最长 512 |
| `invitationToken` | 可选，固定 43 字符 Base64URL Token |

客户端不能选择 PlatformRole、Principal、Organization、Team、Membership 或 Role Grant。服务端只创建 `USER` Account，并按 Registration Mode 决定是否要求邀请。

首次提交返回 `201`；同键同请求完成恢复返回 `200` 与 `Idempotency-Replayed: true`。两种成功响应的 `Location` 均指向当前 Session 可读取的 `/api/v1/account`。响应字段固定为：

```text
accountId, principalId, organizationId, teamId?, memberId?, onboardingRequired,
commandId, domainEventId, committedVersion, correlationId, replayed
```

响应中的 `teamId` 与 `memberId` 只在带邀请注册并成功加入 Team 时出现。密码不进入 Request Hash、事件、Outbox、Receipt 或响应；完成重放通过当前 Credential 复验原密码后恢复 Browser Session。

## 4. 当前账号

### 4.1 `GET /api/v1/account`

返回 `AccountResponse` 和当前聚合强 `ETag`：

```text
accountId, username, email, displayName, status, platformRole,
securityVersion, version, createdAt, updatedAt
```

### 4.2 `PATCH /api/v1/account`

要求单值 `If-Match: "<version>"`。请求字段：

```text
username?, email?, displayName?, currentPassword?, securityVersion?
```

修改用户名或邮箱时必须同时提交当前密码与当前 `securityVersion`。响应返回最新 `AccountResponse` 和新强 `ETag`。

### 4.3 `POST /api/v1/account/password`

要求单值强 `If-Match`。请求字段：

```text
currentPassword, newPassword, securityVersion
```

成功推进 Credential Version、Account SecurityVersion 与聚合 Version，删除该账号全部 Browser Session，返回 `204` 和新强 `ETag`。

### 4.4 `POST /api/v1/account/sessions/revoke`

要求单值强 `If-Match`。请求字段：

```text
currentPassword, securityVersion
```

客户端不能提交 Session ID。成功推进 SecurityVersion、删除该账号全部 Browser Session，返回 `204` 和新强 `ETag`。

## 5. Onboarding

### 5.1 `GET /api/v1/onboarding`

返回：

```text
state, onboardingRequired, activeTeamCount
```

`state` 当前为 `TEAM_REQUIRED` 或 `COMPLETE`。

### 5.2 `POST /api/v1/onboarding/team`

要求单值 `Idempotency-Key`，请求只包含 `name`。Organization、Owner、Workspace、Role、Grant 和默认 Personal Agent 均由服务端创建。首次与重放都返回 `202 CommandReceipt`；重放增加 `Idempotency-Replayed: true`。已完成 Onboarding 后使用新键返回 `409 onboarding_already_complete`。

## 6. Team Invitation

### 6.1 管理入口

基路径：

```text
/api/v1/organizations/{organizationId}/teams/{teamId}/invitations
```

| 方法与子路径 | 请求 | 响应 |
|---|---|---|
| `POST /` | 单值 `Idempotency-Key`；`targetEmail?`、`targetRole`、`expiresInMinutes` | `202`；首次返回 Receipt、邀请和一次性明文 Token，重放只返回 Receipt |
| `GET /?after=&limit=` | Keyset Cursor，默认 50 | 邀请元数据页，不含 Token/Digest |
| `POST /{invitationId}/revoke` | 单值 `Idempotency-Key`，无 Body | `202 CommandReceipt` |

管理入口要求当前 Team 的 `MEMBER_MANAGE` 权限。`targetRole` 不允许 `TEAM_OWNER`；`expiresInMinutes` 范围为 1–43,200。

### 6.2 公开 Preview

`POST /api/v1/invitations/preview` 接受固定字段 `token`。响应只包含：

```text
state, invitationId?, teamName?, targetRole?, expiresAt?, targetRestricted
```

Preview 不返回目标邮箱、邀请人、Organization、Token Digest 或 Membership 坐标。

### 6.3 当前账号接受

`POST /api/v1/invitations/accept` 要求已认证 Session、单值 `Idempotency-Key` 和固定请求字段 `token`，返回 `202 CommandReceipt`。Account、Principal、Binding、Membership 与 Role Grant 均从当前服务端事实解析。Token 只能成功消费一次；错误、过期、撤销和跨账号目标统一使用非识别性邀请错误。

## 7. 幂等与强版本

`Idempotency-Key` 长度为 1–200，只允许字母、数字、`.`、`_`、`:`、`/` 和 `-`。缺失、重复 Header 行、逗号多值或非法格式返回 `400 invalid_request`。

- 同一键与同一规范请求返回原 Receipt，不重复写业务事实、DomainEvent、Outbox 或 Audit；
- 同一键对应不同命令或不同规范请求返回 `409 idempotency_conflict`；
- Onboarding 首 Team 与普通 Team 创建使用独立 Command Type，不能跨入口共享完成 Receipt；
- 仅重放响应携带 `Idempotency-Replayed: true`；
- Receipt 固定包含 `commandId`、`domainEventId`、`committedVersion`、`correlationId`。

`If-Match` 必须是一个单值强 ETag，例如 `"12"`。缺失返回 `428 precondition_required`；Weak ETag、`*`、负数、前导零、逗号多值和重复 Header 行返回 `400 invalid_if_match`。聚合版本冲突使用 `409 optimistic_lock_conflict`，仅在服务端已知当前版本时返回 `currentVersion`。

## 8. 稳定错误码

| HTTP | Code | 含义 |
|---:|---|---|
| 400 | `invalid_request` | DTO、Bean Validation、幂等 Header 或请求解码失败 |
| 400 | `invalid_if_match` | If-Match 不是单值强非负版本 ETag |
| 401 | `authentication_required` | 缺少或失效 Browser Session |
| 401 | `invalid_credentials` | 登录或当前密码复验失败 |
| 403 | `registration_unavailable` | 注册模式禁止自助注册 |
| 409 | `registration_conflict` | 账号注册冲突 |
| 409 | `registration_recovery_failed` | 已提交注册无法完成 Session 恢复 |
| 409 | `account_identifier_conflict` | 用户名或邮箱规范唯一键冲突，不披露冲突字段 |
| 409 | `security_version_conflict` | Account 安全代际已变化 |
| 409 | `account_credential_conflict` | Credential 被并发更新 |
| 409 | `onboarding_already_complete` | 已拥有活动 Team |
| 409 | `invitation_not_pending` | 邀请已进入终态 |
| 409 | `idempotency_conflict` | 同键对应不同语义请求 |
| 409 | `optimistic_lock_conflict` | 聚合强版本冲突 |
| 422 | `registration_unavailable` | 当前模式要求有效邀请或邀请不可用 |
| 422 | `invitation_invalid` | 邀请 Token 无效、过期、撤销或不匹配 |
| 428 | `precondition_required` | 缺少 If-Match |
| 429 | `too_many_requests` | 注册或登录准入限制 |
| 503 | `authentication_unavailable` | 登录依赖不可用 |
| 503 | `registration_unavailable` | 注册依赖不可用 |
| 503 | `registration_session_unavailable` | 注册已提交但 Browser Session 建立失败 |
| 503 | `account_service_unavailable` | 当前账号依赖不可用 |
| 503 | `onboarding_unavailable` | Onboarding 身份或应用依赖不可用 |
| 503 | `invitation_unavailable` | 邀请依赖不可用 |

身份持久化有界执行器容量耗尽不公开线程池细节：注册入口使用 `registration_unavailable`，当前账号入口使用 `account_service_unavailable`，并固定 `retryable=true`。

Security Chain 在 Controller 之前产生的 `csrf_rejected`、`cross_origin_rejected`、`request_too_large` 和 `access_denied` 见 [M7-A06 认证路由与浏览器安全边界](../testing/M7-A06-认证路由与浏览器安全边界.md)。

## 9. Audit 与观测

M7 V1 固定映射 10 个安全事件：

```text
USER_ACCOUNT_REGISTERED
AUTHENTICATION_SUCCEEDED
AUTHENTICATION_FAILURES_AGGREGATED
ACCOUNT_TEMPORARILY_LOCKED
ACCOUNT_LOGGED_OUT
ACCOUNT_PROFILE_CHANGED
ACCOUNT_PASSWORD_CHANGED
TEAM_INVITATION_CREATED
TEAM_INVITATION_ACCEPTED
TEAM_INVITATION_REVOKED
```

每个事件只有一条 `SchemaVersion.V1` Reviewed Audit Definition，Allowed Source Fields 与领域事件 Record 精确一致。密码、Hash、Session ID、Cookie、CSRF、Token、Token Digest、目标邮箱和原始错误不进入 Payload 或 Audit Summary。

HTTP 观测只使用规范 Method、路由模板、Status、Outcome 和稳定 Error Code；认证防护指标只使用固定 `flow × operation × outcome` 枚举，总 Series 上限为 64。Trace 与结构化日志只记录 Correlation/Trace 坐标及稳定低基数字段，不记录 DTO 值或 Header Secret。

## 10. Spring 装配

Auth、Account、Onboarding 与 Invitation Controller 使用单构造器注入。Application Service 保持纯 Java，并在 `IdentityApplicationConfiguration` 与 `TeamApplicationConfiguration` 显式装配。每种 Controller 与 Service 在 Spring Context 中只能存在一个 Bean；Invitation Application Service 缺失时不暴露 Invitation Controller。

Spring Boot Web 编解码使用一份 `tools.jackson.databind.ObjectMapper`（Jackson 3）。AgentScope 2.0 与 Coding Adapter 使用独立的一份 `com.fasterxml.jackson.databind.ObjectMapper`（Jackson 2），两者不得以同一类型或重复 Bean 混用。应用 Security Context 固定包含一条业务 Chain 和一条 Prometheus Chain。
