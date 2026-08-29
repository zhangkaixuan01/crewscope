# M7-A05 Team 邀请管理与当前账号接受

## 1. 交付范围

M7-A05 交付五个邀请接口：

```text
POST /api/v1/organizations/{organizationId}/teams/{teamId}/invitations
GET  /api/v1/organizations/{organizationId}/teams/{teamId}/invitations
POST /api/v1/organizations/{organizationId}/teams/{teamId}/invitations/{invitationId}/revoke
POST /api/v1/invitations/preview
POST /api/v1/invitations/accept
```

创建、撤销和 Accept 要求 `Idempotency-Key`。创建成功的第一次响应返回邀请元数据和明文 Token；相同请求重放只返回原 Command Receipt，Token 与邀请明细为空。列表只允许拥有当前 `MEMBER_MANAGE` 的 Team Owner/Admin 读取，可展示目标邮箱和管理状态，但不包含 Token Digest。

Preview 可匿名调用，只返回 `AVAILABLE / EXPIRED / UNAVAILABLE`。可用结果包含 Invitation ID、Team 名称、目标内置角色、过期时间和是否定向；Preview 不返回目标邮箱、Token Digest 或邀请人身份。未知、已接受和已撤销 Token 统一投影为 `UNAVAILABLE`。

## 2. 当前账号与权限边界

Accept 只使用当前 Browser Session 的 Account ID 与 SecurityVersion 重新解析 ACTIVE Account、Organization Binding 和 Organization Scope USER Principal。请求体只包含 Token，不接受 Account、Organization、Principal、Team、Membership 或 Role 坐标。

Team 邀请管理从当前 TeamMember 的有效 Team Scope Role Grant 计算 `MEMBER_MANAGE`。Team Owner 和 Team Admin 默认允许；Member、Team Lead 和 Auditor 默认拒绝。目标角色允许 `TEAM_ADMIN / TEAM_LEAD / MEMBER / AUDITOR`，不允许通过邀请转移 `TEAM_OWNER`。

Accept 复用 `TeamInvitationAcceptanceService`：

- 无 Membership 时创建 ACTIVE Invitation Membership；
- `INVITED / LEFT / REMOVED` Membership 原 ID 激活；
- ACTIVE Membership 直接复用；
- SUSPENDED Membership 失败关闭；
- 已有当前有效目标 Role Grant 时复用，否则创建新 Grant。

Application Service 在任何 Membership 创建/激活和 Role Grant 写入前，先解析目标 BuiltIn TeamRole 并复验 `isGrantable`。目标 Role 缺失或已停用使整个 Accept 按 `invitation_invalid` 失败关闭，不产生短暂 Membership 写入，不依赖外层事务回滚完成该前置校验。

新账号带邀请注册继续由 M7-A01 在注册事务中调用同一个 Acceptance Service，不经已登录 Accept API。

## 3. 事务、并发与错误

创建、撤销和 Accept 在 REQUIRED 事务中提交业务事实、DomainEvent、Outbox 和 Command Receipt。撤销与 Accept 都先锁 TeamInvitation，再锁 Team；Repository 的 `lockById` 与 `lockByTokenDigest` 对同一数据库行执行 `FOR UPDATE`，管理请求和 Token 消费不会形成反向锁序。

Token 无效、过期、邮箱不匹配、跨 Organization、Team 失效和已消费统一返回 `422 invitation_invalid`，不暴露内部失败层级。撤销非 PENDING 邀请返回 `409 invitation_not_pending`。Controller 把阻塞身份解析和数据库操作切换到 `boundedElastic`，不占用 WebFlux Event Loop。

## 4. 自动验证

```bash
./mvnw -pl crewscope-server -am \
  -Dtest=TeamInvitationApplicationServiceM7A05Test,\
TeamInvitationControllerM7A05Test,ApplicationCompositionConfigurationTest,\
SecurityConfigurationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

./mvnw -pl crewscope-infrastructure -am \
  -Dtest=JdbcTeamInvitationRepositoryM7I06IntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：

- 应用层 7 个专项场景覆盖 Admin 创建/列表/撤销、Member 拒绝、Token 首次返回与幂等重放、Causation 隔离、Preview 隐私、邮箱不匹配、停用目标 Role 零 Membership 写入、接受后 Membership/Role Grant 和 Event/Outbox/Receipt 精确一次；
- HTTP 5 个专项场景覆盖 DTO 白名单、匿名 Preview、Accept Session、稳定非识别错误、无效 Token/Cursor、TEAM_OWNER 拒绝、`no-store` 和 `boundedElastic`；
- Security 路由矩阵与 Spring Composition 证明 Preview 匿名、Accept/管理需认证，Invitation Service Bean 唯一装配；
- PostgreSQL 17 的 6 个邀请场景证明明文 Token 不落库、Digest/ID 两种入口锁定同一行、并发只有一个终态、Keyset 分页和过期清理保持事实；
- Java 17 Release 编译、文档链接和 `git diff --check` 作为最终门禁执行。
