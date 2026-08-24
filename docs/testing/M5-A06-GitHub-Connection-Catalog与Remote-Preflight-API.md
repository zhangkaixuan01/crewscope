# M5-A06 GitHub Connection、Catalog 与 Remote Preflight API

> 实现模块：`crewscope-domain`、`crewscope-application`、`crewscope-server`
> 完成日期：2026-08-24

## 1. 交付范围

M5-A06 将 M5-I08 的 GitHub Provider 能力收口为成员可用的安全应用边界：

- 创建、列表、详情、验证和撤销 GitHub Connection；
- `APP_INSTALLATION` 固定 TEAM Owner 与 `TEAM_SERVICE_ACCOUNT`，Credential Subject 使用 TEAM 或平台管理员批准的 ORGANIZATION；
- `OAUTH_USER` 固定当前成员 USER Owner、PRINCIPAL Credential Subject 与 `DELEGATED_USER`；
- Connection 创建原子提交 Credential 密文、Connection、显式 Repository ConnectionGrant、DomainEvent、Outbox 和 Command Receipt；
- verify、Catalog synchronize 与 Remote Preflight 是远端只读校验或本地缓存刷新，不建立领域命令 Command Receipt；Connection 创建、Binding 创建和撤销继续遵循 ADR-007 的幂等命令协议；
- 撤销原子终结 ConnectionGrant、Credential 与 Connection；
- 为已验证 Connection 创建 Team Workspace GitHub ProviderBinding，固定 Definition、Implementation、Connection、Grant、Execution Identity、Capability 与 Repository Resource 版本；
- 同步和查询 Repository Catalog，只向选择 API返回当前 `DELIVERABLE` Repository；
- 使用 ProviderBinding 和稳定 Repository ID 执行 Remote Preflight；
- 查询 Connection、Grant、Credential、Profile、Catalog、RateLimit 和 Webhook Receiver 配置形成的授权健康摘要；
- GitHub Provider 安全错误映射到稳定 `/api/v1` 错误信封。

## 2. HTTP 边界

根路径：

```text
/api/v1/organizations/{organizationId}/github-connections
```

公开能力：

```text
POST   /                                      创建 Connection
GET    /                                      按 USER/TEAM Owner 列表
GET    /{connectionId}                        详情
POST   /{connectionId}/verify                 远端身份验证
POST   /{connectionId}/bindings               创建 ProviderBinding
GET    /{connectionId}/bindings               查询 Team Workspace Binding
POST   /{connectionId}/repositories/synchronize
GET    /{connectionId}/repositories
POST   /{connectionId}/repositories/{repositoryId}/preflight?bindingId=...
GET    /{connectionId}/health
POST   /{connectionId}/revoke
```

创建、Binding 和撤销使用 `Idempotency-Key`。Binding 和撤销使用强 `If-Match`；验证、Catalog 同步和 Remote Preflight 使用强 Connection Version，防止旧页面把过期授权事实带入远端调用。

## 3. 服务端授权重建

浏览器只提交 Connection 类型、目标 Team、Credential Subject 类型、GitHub 数字身份、Repository Allowlist、一次性 Token、Binding ID 和 Repository ID。服务端读取并复验：

```text
当前 USER Principal
  -> Organization / Team Membership / PROVIDER_MANAGE
  -> Connection Owner / Status / Version
  -> ConnectionGrant Grantee / Status / Version
  -> Credential Subject / Status / Secret Version
  -> ProviderBinding Owner / Definition / Implementation / Connection / Grant Pins
  -> Binding EffectiveAccess 与 Repository Resource
  -> GitHub Profile / Catalog / Organization Policy
  -> Remote Repository / Default Branch / Permission
```

USER Connection 只能绑定 Connection 本人加入的 Team。TEAM Connection 只能绑定 Owner Team，并要求当前 `PROVIDER_MANAGE`。旧 Binding、已撤销 Grant、Connection 或 Grant Version 漂移、Execution Identity 不一致、跨 Connection Repository 和默认分支漂移全部失败关闭。

## 4. 敏感字段白名单

公开 Connection DTO 只包含 Owner 类型、Team、认证类型、执行身份、外部展示 Login、状态、版本、Repository Allowlist、Credential 状态和时间。Catalog DTO 只包含稳定 Repository ID、Full Name、默认分支、Visibility 与缓存时间。

以下字段不进入响应、错误或 Receipt：

- Token、Credential ID、密文和 Secret Version；
- GitHub 外部数字账号 ID；
- ConnectionGrant ID/Version 和内部授权摘要；
- Provider Endpoint、Remote URL、Authorization Header；
- 原始 OAuth Scope、HTTP Body、Git stderr 和内部异常。

## 5. 自动化验证

专项命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-server -am \
  -Dtest=GitHubConnectionApplicationServiceM5A06Test,GitHubConnectionControllerM5A06Test,GitHubConnectionApplicationConfigurationM5A06Test,GitHubProviderApplicationConfigurationM5I08Test \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

专项结果：`14 / 14` 通过，其中 M5-A06 新增 `11 / 11`，M5-I08 Spring 回归 `3 / 3`。

覆盖：

- USER OAuth Principal Credential 与规范 Repository Resource Grant；
- TEAM App 缺少 `PROVIDER_MANAGE` 时拒绝且 Secret 清零；
- 相同创建命令 Receipt 回放不重复保存 Credential、Connection、Event 或 Outbox；
- 已验证 Connection 到成员 Team Workspace 的 ProviderBinding；
- Remote Preflight 使用持久 Binding、Grant 和默认分支，不信任浏览器版本或权限；
- 强 ETag、Idempotency-Key、Catalog/Health `no-store`；
- Token、Credential、Grant、Endpoint、Remote URL 和 Provider Body 响应白名单；
- 429 与 Provider 不可用安全错误；
- 有无 Webhook Secret Resolver 的条件 Spring 装配。

## 6. 结论

M5-A06 已把 GitHub 外部授权、Workspace 能力绑定、Repository 选择和写前远端复验连接为可审计应用闭环。M5-A07 可以基于当前 ProviderBinding、Repository Preflight、Review Gate 与 PolicySnapshot 生成并确认 ActionBundle。
