# ADR-024：Account 与 Principal 身份边界

> 状态：PROPOSED<br>
> 日期：2026-08-28<br>
> 关联决策：[ADR-007](ADR-007-API命令与并发协议.md)、[ADR-008](ADR-008-可观测性与日志安全协议.md)、[ADR-023](ADR-023-Team-Beta单机部署与发布验证协议.md)<br>
> 影响里程碑：M7

## 背景

CrewScope 现有 `Principal` 是 Organization 内统一行为主体，覆盖 USER、Agent 和 Service。`TeamMember`、TeamRole、Responsibility、Conversation、Task、Provider、DomainEvent 和 Audit 都引用稳定 Principal ID。M0–M6 的 Bootstrap/OIDC 映射把 `identity_provider + external_subject` 直接保存到 USER Principal，并由请求携带的 Organization 触发首次 Principal 创建。

M7 引入平台账号、本地凭证、正式登录会话和开放注册。登录账号具有用户名、邮箱、密码、安全版本和平台角色，这些事实不属于 Organization 行为主体或 Team Membership。把它们继续加入 Principal 会使 Agent/Service 身份承担无意义字段，也会让账号停用、Organization 绑定和 Team 权限形成循环依赖。

同时，Team Beta 已存在 `bootstrap/crewscope-monitor` USER Principal、TeamMember、责任和 Audit 历史。升级必须保留全部稳定 ID，不能把既有用户复制成新的 Principal。

## 决策

### 身份链分层

```text
authentication credential / provider assertion
  -> LoginIdentity
  -> UserAccount
  -> AccountOrganizationBinding
  -> Organization-scoped USER Principal
  -> TeamMember
  -> TeamRole / WorkProject grant
```

- `UserAccount` 是部署级登录主体，管理用户名、规范邮箱、账号资料、账号状态、Security Version 和 `USER / OPERATOR` PlatformRole；
- `LoginIdentity` 保存认证 Provider、稳定 Subject 和所属 Account；
- `LocalCredential` 属于 Account，本地用户名/邮箱只用于定位 Account，验证后使用 `local + Account ID` 作为稳定 LoginIdentity Subject；
- `AccountOrganizationBinding` 单向引用 Account、Organization 和该 Organization 内的 USER Principal；
- `Principal` 继续作为 Organization 内行为主体，不反向引用 Account、LoginIdentity、Credential 或 Session；
- `TeamMember` 继续只引用 USER Principal，表达其在一个 Team 内的参与状态；TeamRole 继续表达 Team/Project 权限；
- Team 成员目录从同 Organization 的 USER Principal 批量读取 `displayName` 作为人类可读身份；TeamMember API 不从 UserAccount 复制姓名，Web 不把 Principal ID 拼成伪显示名；
- PlatformRole 只授予部署级能力，不代替 Organization Binding、TeamMember 或 TeamRole。

### LoginIdentity 唯一性与合并

- `(provider, subject)` 在部署内唯一，只能属于一个 Account；
- `(account_id, provider)` 唯一，一个 Account 对同一 Provider 最多一个当前身份；
- 一个 Account 可以同时拥有 `local`、企业 OIDC 和未来社交 Provider 的不同身份；
- Subject 创建后不可修改；Provider Subject 变化通过受验证的换绑事务替换身份，不原地改写身份真相；
- 相同邮箱、用户名、显示名、Provider 昵称或声明相似度不能自动合并 Account；
- 身份关联要求已认证 Account 的重新认证或经过验证的 Provider Proof，并产生安全 Audit。

### Binding 与 Organization 选择

- `(account_id, organization_id)` 唯一，一个 Account 在一个 Organization 只有一个当前 Binding；
- `(organization_id, principal_id)` 唯一，一个 Organization USER Principal 只能绑定一个 Account；
- Binding 只接受相同 Organization、Organization Scope、`USER` 类型的 Principal；Account、Binding 和 Principal 都必须处于可用状态；
- 请求路径中的 Organization 只能选择已有 ACTIVE Binding，不能触发 Account、Binding 或 Principal 创建；
- M7 的单 Organization 自托管实例只会话化 Bootstrap Organization 的一个 ACTIVE Binding；未来多 Organization 选择沿用相同数据模型，不改变 Principal/TeamMember；
- 创建 Binding 不创建 TeamMember。加入 Team 仍通过 Onboarding、Invitation、SCIM 或管理员成员用例完成。

### Principal 兼容列

V2 的 `principal.identity_provider/external_subject` 在 M7 后不再是登录权威来源：

- M7 新建的 Account USER Principal 不填写 ExternalIdentity；
- V30 及更早数据保留 ExternalIdentity 作为升级定位与兼容事实，不批量清空；
- 登录解析只查询 LoginIdentity 与 Binding，不同时查询新旧两套身份真相；
- 旧 `IdentityMappingService` 在本地认证切换完成后退出普通 Web 请求路径，企业 OIDC Adapter 改为解析 LoginIdentity；
- Principal 显示名是 Organization Actor 标签，Account 显示名是账号资料。创建时使用相同初值；资料修改需要应用服务显式同步当前 Binding 的 USER Principal，不建立 Principal 到 Account 的反向依赖。

### Bootstrap Operator 无损升级

升级协议精确查找 Bootstrap Organization 内 `bootstrap/crewscope-monitor` ExternalIdentity：

1. 要求找到的 Principal 为 Organization Scope、ACTIVE、USER；类型、Scope、Organization 或状态不兼容时失败关闭；
2. 创建或复用一个 OPERATOR UserAccount；自助注册不能获得 OPERATOR；
3. 创建 `local + Account ID` LoginIdentity，并从外部 Bootstrap Secret 创建或轮换 LocalCredential；
4. 创建 AccountOrganizationBinding，引用原 Principal ID；
5. 保留旧 ExternalIdentity、Principal ID、TeamMember ID、TeamRole、Responsibility、DomainEvent 和 Audit；
6. 重复启动收敛到相同 Account、Identity、Binding 和 Principal；
7. 未找到旧 Principal 时才允许部署引导创建新的 Organization USER Principal；多个候选、既有冲突 Binding 或错误 PlatformRole 均失败关闭。

Prometheus 机器身份不创建 UserAccount、Binding、TeamMember 或业务 Session，并使用独立凭证与精确 Actuator Security Chain。

## 实现约束

V31 使用 PostgreSQL 唯一约束作为最终并发裁决：

```text
login_identity(provider, subject)
login_identity(account_id, provider)
account_organization_binding(account_id, organization_id)
account_organization_binding(organization_id, principal_id)
```

账号、身份、Binding 和必要 Principal 的首次创建位于同一事务。并发唯一冲突只能重新读取相同规范键并验证所有不可变字段；不能随机选择候选或在冲突后创建第二个 Account。事务提交前不能建立 Redis Session。

V31 必须用复合外键或等价约束证明 Binding Principal 属于同一 Organization。USER 类型与 Organization Scope 由迁移 Guard、Repository 锁定读取和应用不变量共同保证。Credential、密码、Hash、Session、Cookie、CSRF 和完整外部 Subject 不进入 DomainEvent、Audit Payload、日志、Trace、指标或公开 DTO。

有效权限按以下交集计算：

```text
Account ACTIVE
AND LoginIdentity usable
AND Binding ACTIVE
AND Principal ACTIVE / USER / matching Organization
AND TeamMember ACTIVE when Team access is required
AND current TeamRole grants
```

任何一层失效都不能由其他层的旧缓存或平台角色绕过。Session Projection 保存稳定 ID 与 Security Version，业务请求仍重新验证需要持续授权的当前状态。

## 结果

- 登录安全、Organization 行为身份和 Team 成员权限拥有独立生命周期；
- 一个 Account 可以扩展多个认证 Provider，而团队业务模型不感知 Provider；
- 既有 Principal、TeamMember 和 Audit 图可以原位升级；
- Platform Operator 与 Team Owner/Admin 不再混为一种权限；
- 数据模型为未来多 Organization 和企业身份保留扩展位；
- 代价是认证解析多一层 Binding 查询，并需要显式处理 Account 与 Principal 显示名同步、状态交集和迁移冲突。

## 验证

M7-S02 测试内 Registry 模拟上述唯一索引和事务，已验证：

- 16 路并发首次映射收敛为一个 Account、LoginIdentity、Binding 和 Principal，冲突事务零残留；
- 同一 Account 可拥有 local 与 OIDC 身份，Provider/Subject 不能跨 Account 复用；
- 未绑定 Organization 的请求被拒绝且不产生任何新事实；
- 一个 Organization Principal 不能绑定两个 Account；
- Bootstrap Operator 升级保留 Principal/TeamMember ID 与旧 ExternalIdentity，重复执行幂等；
- 错误 Principal 类型、Team Scope 和禁用状态全部失败关闭；
- Principal 与 TeamMember 的生产类型不依赖 Account/LoginIdentity。

验证记录见 [M7-S02 Account 与 Principal 边界验证记录](../spikes/M7-S02-Account与Principal边界验证记录.md)。

M7-D01 已用正式生产类落地 `UserAccount`、`Username`、`NormalizedEmail`、`AccountStatus`、`PlatformRole` 和 `SecurityVersion`。注册工厂不接受角色并固定生成 USER，Bootstrap Operator 使用独立受信工厂；账号资料、状态和安全版本不依赖 Principal、TeamMember、Credential 或 Session。验证见 [M7-D01 账号领域内核](../testing/M7-D01-账号领域内核.md)。

M7-D02 已落地 `LoginIdentity`、稳定精确 Subject、独立 Identity 状态机、部署全局 Provider/Subject 键和每 Account/Provider 键。本地 Subject 的生产构造路径只能使用 Account ID，ExternalIdentity 换绑不能原地修改 Subject。`LocalCredentialMetadata` 与哈希敏感值分离，两类应用 Repository 不反向引入 Principal、TeamMember 或 Session。验证见 [M7-D02 LoginIdentity 与 LocalCredential 契约](../testing/M7-D02-LoginIdentity与LocalCredential契约.md)。

M7-D04 已落地 `AccountOrganizationBinding`、Account/Organization 键、Organization/Principal 键、`ACTIVE / DISABLED` 状态机与安全统一冲突。创建和重新启用验证 Account 与 USER Principal 当前可用，绑定端点保持不可变；并发相同绑定通过 Repository 唯一边界重读收敛。`AccountOrganizationPrincipalResolver` 只依赖不含 Provision 方法的 `OrganizationPrincipalReader`，未绑定 Organization、错误状态、类型或 Scope 均返回空解析且不创建任何事实。Principal 与 TeamMember 继续没有 Account、LoginIdentity 或 Binding 反向依赖。验证见 [M7-D04 AccountOrganizationBinding 领域契约](../testing/M7-D04-AccountOrganizationBinding领域契约.md)。

## 接受与重新评估条件

ADR 在 M7-D01 至 M7-D04 冻结正式领域对象、M7-D07 通过 V31 真实 PostgreSQL 约束和 V30 升级测试后转为 `ACCEPTED`。

出现公共多租户 SaaS、跨部署统一账号、同 Provider 多身份、Account 合并、Organization 切换、企业 SCIM 主数据或管理员冒充登录需求时重新评估。企业 OIDC、GitHub/飞书社交登录只新增 Identity Adapter 时不需要改变本 ADR 的 Principal/TeamMember 边界。
