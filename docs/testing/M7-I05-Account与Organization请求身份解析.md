# M7-I05 Account 与 Organization 请求身份解析

## 1. 完成范围

M7-I05 将浏览器 Session 从“携带可直接授权的角色”收敛为“携带待复验的 Account 坐标”。`AuthenticationSubjectExtractor` 现在区分两类受信主体：

- `AccountSessionSubject` 只包含 `UserAccountId + SecurityVersion`；
- `ExternalAuthenticatedSubject` 只包含 Bootstrap/OIDC 的稳定 Provider/Subject、展示名和可选 Organization 约束。

`AuthenticatedAccountOrganizationResolver` 每次请求重新读取 `CurrentAccountSnapshot`，精确校验当前 Account、SecurityVersion 和唯一 ACTIVE Local LoginIdentity，再通过 `AccountOrganizationPrincipalResolver` 查询既有 ACTIVE Binding 与同 Organization 的 ACTIVE USER Principal。任何账号、Identity、Binding 或 Principal 状态失效，以及缺少目标 Organization Binding，均返回同一无枚举拒绝结果。

## 2. 授权与会话边界

`PlatformRoleAuthorities` 只从持久化 `UserAccount.platformRole()` 生成权限：

- 普通账号固定为 `ROLE_USER`；
- Bootstrap Operator 为 `ROLE_USER + ROLE_OPERATOR`；
- 非 ACTIVE Account 不生成任何 Authority。

`BrowserSessionLifecycle` 的公开建立入口只接受完整的持久化 `UserAccount`，并由 Account 派生最小 `BrowserSessionPrincipal` 与 Authority。传入任意 Authority 名称的底层入口降为包内可见，仅服务 Session 基础设施测试。Team 请求不会信任 Session 中已有的 `ROLE_ADMIN / ROLE_OPERATOR`，`TeamAccessContext.platformAdministrator` 只来自本次读取的持久化 PlatformRole；TeamMember 与 TeamRole 权限继续由既有 Team 应用策略裁决。

## 3. 删除请求路径首次映射

`RepositoryTeamRequestIdentityResolver` 不再依赖或调用 `IdentityMappingService.map(...)`。本地 Session 和已经链接到 Account 的外部 LoginIdentity 都必须具备现有 Account/Organization Binding；访问另一个 Organization URL 不会创建 Principal 或 Binding。

未链接 Account 的旧 Bootstrap/OIDC 身份只允许通过 `PrincipalRepository.findByExternalIdentity(...)` 查询目标 Organization 中完全匹配、ACTIVE、Organization Scope 的 USER Principal。该兼容路径没有 Provision 能力，也不具备平台 Operator 权限。M7-I07 已删除旧 `ROLE_ADMIN` 授权来源，平台管理权限只来自当前持久化 Account 的 `PlatformRole.OPERATOR`。

## 4. 验证结果

执行：

```bash
./mvnw -pl crewscope-server -am \
  -Dtest=AccountOrganizationResolutionM7D04Test,M7I01IdentityPersistenceIntegrationTest,BrowserSessionLifecycleM7I02IntegrationTest,LocalSessionSecurityM7S01IntegrationTest,AuthenticatedAccountOrganizationResolverTest,AuthenticationSubjectExtractorTest,PlatformRoleAuthoritiesTest,RepositoryTeamRequestIdentityResolverTest,TeamControllerTest,TaskControllerTest,ProviderBindingControllerTest,TeamApplicationServiceTest,WorkItemAccessPolicyM3A07Test,ProviderBindingResolverTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：

- application：35 / 35；
- infrastructure：9 / 9，使用真实 PostgreSQL 17 与 Redis 7.4 Testcontainers；
- server：36 / 36，包含真实 Redis Session 创建、续期、旋转、退出、过期、双实例共享、LRU 和失败关闭；
- 合计：80 / 80。

覆盖本地 Session、SecurityVersion 撤权、跨 Organization、USER 伪造 Operator Authority、持久化 OPERATOR、Account/LoginIdentity/Binding/Principal 停用、旧外部 Principal 只读兼容，以及 Team、Task、Provider 既有入口和 Team/WorkItem/Provider 权限策略。

## 5. 后续

M7-I06 已实现邀请持久化与一次性 Token；M7-I07 已将旧 Bootstrap Principal 原位升级为 Account、Local LoginIdentity、Credential、Organization Binding 和 OPERATOR PlatformRole，并删除旧 `ROLE_ADMIN` 兼容授权来源。M7-I08 将完成部署凭证分离与生产安全 Guard。
