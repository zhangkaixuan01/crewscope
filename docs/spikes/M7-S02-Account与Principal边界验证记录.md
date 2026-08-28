# M7-S02 Account 与 Principal 边界验证记录

> 任务：`M7-S02`<br>
> 日期：2026-08-28<br>
> 结论：通过<br>
> 长期决策：[ADR-024](../adr/ADR-024-Account与Principal身份边界.md)（PROPOSED）

## 1. 验证目标

M7-S02 在现有 Principal、ExternalIdentity、TeamMember、IdentityMappingService 和 V2 数据约束之上验证：

1. UserAccount、LoginIdentity、AccountOrganizationBinding、Principal 和 TeamMember 的职责与引用方向；
2. 并发首次身份映射的唯一收敛点；
3. 同一 Account 拥有多个 Provider 身份的约束；
4. 未绑定 Organization 的访问不能隐式创建 Principal；
5. Bootstrap Operator 升级对既有 Principal、TeamMember 和 Audit 引用无损；
6. 类型、Scope、状态和重复绑定冲突失败关闭。

Spike 使用测试内 Registry 模拟未来 V31 的唯一索引和原子事务，不创建正式 Account 领域类、Repository 或迁移。正式实现由 M7-D01、D02、D04、D07 和 I07 完成。

## 2. 现有身份图

M0–M6 当前链路为：

```text
Spring Authentication
  -> AuthenticationSubjectExtractor
  -> provider + subject + requested Organization
  -> IdentityMappingService
  -> Principal(identity_provider, external_subject)
  -> TeamMember / TeamRole
```

现有实现已经具备：

- Principal 是 Organization 范围 USER/Agent/Service 的统一 Actor；
- TeamMember 只接受相同 Organization 的 ACTIVE USER Principal；
- V2 使用 `(organization_id, identity_provider, external_subject)` 唯一索引并发 Provision Principal；
- TeamMember 对 `(team_id, user_principal_id)` 唯一；
- Responsibility、Conversation、Task、Provider、DomainEvent 和 Audit 使用稳定 Principal ID；
- OIDC 带部署配置的 Organization 约束，Bootstrap 当前可使用请求 Organization 做首次映射。

M7 需要把账号、密码、平台角色和多 Provider 身份从 Principal 中分离，并关闭“访问 Organization URL 即创建 Principal”的行为。

## 3. 冻结引用方向

```text
UserAccount 1 ------ * LoginIdentity
     |
     +------ * AccountOrganizationBinding * ------ 1 USER Principal
                                                       |
                                                       +------ * TeamMember
                                                                  |
                                                                  +------ * TeamRole Grant
```

引用方向固定为 AccountOrganizationBinding 持有两端 ID。Principal 和 TeamMember 不反向持有 Account 或 LoginIdentity。Fixture 通过反射验证当前生产 `Principal` 与 `TeamMember` 字段不依赖新增账号类型。

账号状态决定能否登录；Binding 决定能否进入 Organization；Principal 决定能否作为 Organization Actor；TeamMember/TeamRole 决定能否参与 Team 工作。平台 OPERATOR 不自动成为 Team Owner/Admin。

## 4. 并发首次映射

16 个线程同时提交：

```text
provider = oidc/corporate
subject = subject-42
organization = Organization A
```

测试内事务以未来唯一键裁决：

```text
(provider, subject)
(account, provider)
(account, organization)
(organization, principal)
```

结果：

```text
Accounts = 1
LoginIdentities = 1
Bindings = 1
Principals = 1
all 16 results use the same four IDs
```

新 Principal 的 ExternalIdentity 为空，证明 M7 新链路只以 LoginIdentity 为登录真相。并发冲突必须重新读取并验证精确规范键，不能创建第二账号或用显示名/邮箱选择候选。

## 5. 多身份与账号合并

测试为一个 Account 建立：

```text
local / subject = immutable Account ID
oidc/corporate / subject = subject-42
```

两者共同指向一个 Account。以下冲突被拒绝：

- 第二个 Account 绑定相同 `oidc/corporate + subject-42`；
- 同一 Account 在 `oidc/corporate` 下绑定另一个 Subject。

邮箱、用户名、显示名和 OIDC Email Claim 不参与自动合并。同邮箱身份关联属于显式安全用例，需要重新认证或 Provider Proof，并产生 Audit。

## 6. Organization 与 Membership 边界

Account 只绑定 Organization A 时，请求 Organization B 返回边界拒绝；Account、Identity、Binding 和 Principal 数量保持不变。请求路径不能成为 Provision 命令。

测试还证明 Organization A 的同一 Principal 不能绑定第二个 Account。创建 Binding 不创建 TeamMember；Onboarding、Invitation 或管理员成员命令继续是 Membership 的唯一入口。

## 7. Bootstrap Operator 无损升级

Fixture 预置：

```text
Principal
  type = USER
  scope = Organization
  status = ACTIVE
  ExternalIdentity = bootstrap / crewscope-monitor

TeamMember
  userPrincipalId = existing Principal ID
  joinMethod = BOOTSTRAP
```

升级创建一个 OPERATOR Account、一个 `local + Account ID` LoginIdentity 和一个 Binding，并引用原 Principal。结果：

- Principal 总数不变；
- TeamMember 总数与 ID 不变；
- TeamMember 仍引用原 Principal ID；
- 旧 ExternalIdentity 保留；
- 重复升级返回相同 Account、Identity、Binding 和 Principal；
- 新 Session 可以通过 Binding 解析到原 Principal。

SERVICE Principal、Team Scope USER 和 DISABLED USER 均在创建 Account 前失败，Registry 数量保持不变。真实 M7-I07 还必须覆盖不存在、多个候选、已有冲突 Binding、Secret 轮换和事务失败。

另一个 Fixture 使用 `oidc/corporate + subject-42` 预置 SERVICE Principal，再执行首次映射。整个账号、身份、Binding 和 Principal Provision 事务回滚，除预置 Principal 外没有残留，证明并发冲突后的重新读取仍必须验证完整类型与 Scope。

## 8. 数据库约束草案

V31 至少需要：

```text
user_account
  unique normalized_username
  unique normalized_email according to D01 policy

login_identity
  unique(provider, subject)
  unique(account_id, provider)

account_organization_binding
  unique(account_id, organization_id)
  unique(organization_id, principal_id)
  FK organization_id -> organization
  composite FK organization_id + principal_id -> principal
```

Repository 在锁定读取中验证 Principal 为同 Organization、Organization Scope、USER 和允许状态。登录、注册、邀请注册和 Operator Upgrade 在 PostgreSQL 提交成功后才建立 Redis Session。

## 9. 自动化验证

测试文件：

```text
crewscope-application/src/test/java/io/crewscope/application/identity/
  AccountPrincipalBoundaryM7S02Test.java
```

专项命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-application -am \
  -Dtest=AccountPrincipalBoundaryM7S02Test \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：

```text
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

与现有 Principal、TeamMember 和 IdentityMapping 联合回归：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-application -am \
  -Dtest=PrincipalTest,TeamMemberTest,IdentityMappingServiceTest,AccountPrincipalBoundaryM7S02Test \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

```text
Tests run: 33, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

根 README 与 `docs` 共 306 份 Markdown 文档链接通过。

## 10. 后续实现边界

- M7-D01 实现 UserAccount、状态、安全版本和 PlatformRole；
- M7-D02 实现 LoginIdentity、LocalCredentialMetadata 与唯一性；
- M7-D04 实现 AccountOrganizationBinding 和显式解析不变量；
- M7-D07 用 PostgreSQL V31 验证全部唯一键、复合 FK 和 V30→V31 升级；
- M7-I01 实现 Repository 与并发事务；
- M7-I05 用 Account/Binding 替换请求 Organization 首次映射；
- M7-I07 实现 Bootstrap Operator 无损启动引导。

ADR-024 在 D01–D04 与 D07 真实约束通过前保持 `PROPOSED`。

## 11. 结论

M7-S02 验证通过。Account、LoginIdentity、Binding、Principal 和 TeamMember 可以形成单向、可并发收敛且兼容既有数据的身份链。登录 Provider 与团队授权解耦后，同一用户可以扩展多个认证身份，Organization URL 不能创建业务身份，Bootstrap Operator 可以保留全部团队与审计历史完成升级。
