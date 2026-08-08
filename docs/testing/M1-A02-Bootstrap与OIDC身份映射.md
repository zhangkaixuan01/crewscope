# M1-A02：Bootstrap 与 OIDC 身份映射

> 日期：2026-08-08<br>
> 状态：已完成<br>
> 模块：`crewscope-domain`、`crewscope-application`、`crewscope-infrastructure`、`crewscope-server`

## 目标

将 Bootstrap 用户名和基础 OIDC Subject 映射为 CrewScope 的持久化 USER Principal，为 Team 创建、成员管理和后续业务 API 提供可信调用者身份。

## 身份键

```text
Bootstrap -> provider=bootstrap, subject=username
OIDC      -> provider=oidc/{registrationId}, subject=OIDC sub
```

OIDC 显示名按 `name -> preferred_username -> email -> sub` 选择并限制为 Principal 显示名长度。显示名变化不改变身份键。不同 Registration 的同名 Subject 属于不同外部身份。

`CREWSCOPE_OIDC_ORGANIZATION_ID` 将部署 ClientRegistration 绑定到唯一 Organization。OIDC 认证生成 Organization Constraint，服务端在持久化前校验请求路径中的 Organization，阻止跨租户自助映射。

## 原子映射

`IdentityMappingService` 在 REQUIRED 事务中完成：

1. 校验目标 Organization 存在；
2. 构造 ACTIVE、Organization Scope、ORGANIZATION 可见的 USER Principal；
3. 通过 PostgreSQL `INSERT ... ON CONFLICT DO NOTHING` 竞争外部身份唯一索引；
4. 返回本次创建或并发事务已提交的 Principal；
5. 首次创建时追加 `USER_IDENTITY_MAPPED` DomainEvent 和 PENDING Outbox。

唯一索引键为 `organization_id + identity_provider + external_subject`。两个并发首次认证只产生一个 Principal、一条 DomainEvent 和一条 Outbox。

事件 Payload 只包含 Provider。原始 Subject 保留在 Principal 外部身份字段，不进入 DomainEvent Payload、Outbox Payload 和测试日志。HTTP Correlation ID 贯穿身份映射事件与后续 Team 命令。

## 冲突与账户状态

已有映射必须满足：

- Principal 属于请求 Organization；
- Principal 为 Organization Scope；
- Principal 类型为 USER；
- ExternalIdentity 与认证身份一致；
- Principal 状态为 ACTIVE。

类型或 Scope 不兼容返回 `identity_mapping_conflict`。`SUSPENDED`、`DISABLED` 和 `ARCHIVED` 返回 `policy_denied`。未知 Organization 返回 `aggregate_not_found`。认证类型缺少明确提取规则时失败关闭。

## TeamMember 边界

登录身份映射只创建 Principal。TeamMember 由以下受控业务流程创建：

- Team 创建：为创建者生成 Owner Membership、TEAM_OWNER Grant 和默认 Personal Agent；
- 遗留 Team 补全：为选定 Owner 生成完整 Team 基础；
- 成员加入：由具有 `MEMBER_MANAGE` 的 TeamMember 添加目标 USER Principal。

访问 Team 路由不会创建 Membership。已有 OIDC Principal 仍需 ACTIVE Membership 才能读取 Team；成员加入后复用 A01 的权限、角色和 Personal Agent 初始化规则。

## 安全模式

`CREWSCOPE_SECURITY_MODE` 支持：

| 模式 | 认证 | CSRF | 使用环境 |
|---|---|---|---|
| `bootstrap` | HTTP Basic | 关闭 | 开发与初始化 |
| `oidc` | OAuth2 Login | Cookie CSRF Token | 浏览器部署 |

未知模式使启动失败。OIDC 模式要求 Organization Binding 和有效的 Spring Security ClientRegistration，缺失配置时启动失败。`ROLE_ADMIN` 只从服务端 Authentication Authority 解析。

## 验证范围

- Bootstrap 新 Subject 创建 ACTIVE USER Principal；
- 相同 Subject 重复认证复用 Principal；
- 两线程并发首次映射只创建一个 Principal；
- OIDC 使用 `sub`，显示名 Claim 不参与唯一键；
- OIDC Organization Constraint 阻止跨租户映射；
- Provider/Registration 隔离；
- 非 USER 或错误 Scope 的 Subject 映射冲突；
- `SUSPENDED/DISABLED/ARCHIVED` 账户失败关闭；
- 未知 Organization 返回稳定 Not Found；
- 身份映射不产生 TeamMember；
- DomainEvent 和 Outbox 不包含原始 Subject；
- Bootstrap、OIDC、未知安全模式、OIDC Organization Binding 和 ClientRegistration 缺失分支。

## 验证结果

- 应用层身份映射测试覆盖创建、复用、冲突、三种不可用账户状态、未知 Organization 和隐私安全事件；
- PostgreSQL 集成测试覆盖并发唯一性、Principal/Event/Outbox 原子提交和零隐式 Membership；
- 服务端测试覆盖 Bootstrap 提取、OIDC `sub` 提取、显示名选择、未知认证类型、管理员 Authority 和安全模式启动分支；
- M1-A01 Team 权限测试继续覆盖 ACTIVE Membership、Team Scope Grant 和未授权访问拒绝。
- `./mvnw clean verify` 通过 348 个测试：Domain 124、Application 72、AgentScope 9、Infrastructure 99、Server 44；51 个 Markdown 文档链接和 `git diff --check` 通过。
