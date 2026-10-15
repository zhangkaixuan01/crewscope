# M12-I01：OIDC 接入与协议攻击自测

状态：C3 已完成（全量 verify 终跑见文末证据行）
日期：2026-10-15
关联：[ADR-036](../adr/ADR-036-企业身份联邦与会话策略.md)、[M12-企业身份与会话API契约](../api/M12-企业身份与会话API契约.md)、[S01 冻结记录](../spikes/M12-S01-现状盘点与合同冻结.md)、主计划 §6 I01

## 1. 切片与验证总览

| 切片 | 内容 | 交付物 | 状态 |
| --- | --- | --- | --- |
| C1 | 会话同构底座+身份策略+会话枚举 | `BrowserSessionPrincipal.identityProvider`、sessions GET/DELETE、`IdentityPolicy` 启动矩阵、`BrowserOrganizationAnchor`、OpenAPI 303→305 | 完成（2026-10-14） |
| C2 | OIDC 核心流 | `OidcAccountAuthenticationService`、PKCE resolver、success/failure handlers、start gate 防护、`EmbeddedOidcIdp`、`ExternalAccountIdentity`、`IdentityMappingService` 退役 | 完成（2026-10-15） |
| C3 | 协议攻击集+撤销延迟+收口 | 攻击矩阵（9 行）、撤销延迟（2 测试）、契约定稿、runbook 企业身份章节、本文档 | 完成（2026-10-15） |

## 2. C3 攻击矩阵（`OidcProtocolAttackM12I01IntegrationTest`）

全栈形态：`crewscope.security.mode=oidc` + team-beta profile + 真实 PostgreSQL/Redis + `EmbeddedOidcIdp`（独立 reactor-netty 端口，RSA-256 真签名，严格默认：单次 code、redirect 精确匹配、client_secret_basic、强制 S256）。每行独立 subject，共享世界无截断。

九行矩阵与统一断言（拒绝 + 无会话 + 无账号副作用）：

| # | 测试方法 | 攻击注入 | 拒绝形态 |
| --- | --- | --- | --- |
| 1 | `idTokenSignedWithTheWrongKeyIsRefused` | IdP 旋钮 `WRONG_KEY`（另一把 RSA-2048 签名） | 302 `/login?sso_error=denied`；会话匿名；`login_identity` 零行 |
| 2 | `unsignedIdTokenIsRefused` | IdP 旋钮 `ALG_NONE`（PlainJWT） | 同上 |
| 3 | `issuerMismatchIsRefused` | `tamperIssuer("https://attacker.example.com")` | 同上 |
| 4 | `audienceMismatchIsRefused` | `tamperAudience("another-rp")` | 同上 |
| 5 | `nonceMismatchIsRefused` | `tamperNonce("attacker-chosen-nonce")` | 同上 |
| 6 | `tamperedStateParameterIsRefused` | 改写 consent Location 的 `state` 参数后访问 callback | `denied`（授权请求不匹配，token 交换前拒绝） |
| 7 | `replayedAuthorizationCodeIsRefused` | 同一 callback URL 二次访问；直连 IdP 同 code 二次兑换 | 端到端重放 `denied` 且原合法会话不受扰；IdP 二次兑换 `invalid_grant`（单次使用证明） |
| 8 | `tamperedRedirectUriIsRefusedAtTheProvider` | authorize 带攻击者回调路径；合法 code 兑换时调包 redirect_uri | authorize 400 `invalid_request` 且**无 Location**（无开放重定向）；兑换 `invalid_grant` |
| 9 | `pkceVerifierMismatchIsRefused` | 产品 start 的 challenge 断言；自建 challenge+错 verifier；无 challenge 的流 | authorize URL 真含 `code_challenge`+`method=S256`；两种兑换均 `invalid_grant`；攻击者 subject 零副作用 |

行 1–5 走真实三跳（浏览器 cookie jar），敌意信号由 IdP 注入、CrewScope 框架验证链拒绝；行 6–7 攻击浏览器↔CrewScope 段；行 8–9 攻击信号从未到达 CrewScope，断言在 IdP 直连面完成（产品 PKCE always-on 是前提，`OidcPkceAuthorizationRequestResolver` 无开关）。

实现注记：token 端点直连沿用 `EmbeddedOidcIdpTest` 惯例（`WebClient` + `exchangeToMono`，4xx 响应体即断言目标）；WebTestClient 没有 `exchangeToMono`——直连 POST 与断言型 GET 分属两个 client。

## 3. C3 撤销延迟（`OidcRevocationDelayM12I01IntegrationTest`）

两个测试，全栈同上：

1. **`disablingTheAccountRevokesEverySessionOnTheNextRequest`**：双设备（desktop/phone）各自三跳建立 OIDC 会话 → 合规 UPDATE（`status='DISABLED'`+`security_version+1`+`version+1`+`updated_at`——V31 guard 触发器要求每次变更 version 递增，缺它即 23514 拒绝）→ 断言：
   - desktop 下一请求 `/api/v1/auth/session` 匿名（`account` 空）；
   - Redis 会话键消失（`ReactiveRedisIndexedSessionRepository.findById` 按浏览器 cookie id 查无）；
   - phone 同样下一请求匿名+键消失（「两跳」断言）；
   - 设备列表端点回到 401 守卫。
2. **`sessionTtlCapsIdpSideRevocationAtTwelveHours`**：establish 后断言部署 TTL（`BrowserSessionProperties.getTtl()`）与 Redis 会话携带的 `getMaxInactiveInterval()` 同为 12h——IdP 侧失效上限的机制映射：establish 后 CrewScope 不持有/不刷新 IdP 令牌，任何会话活不过自身 TTL（契约 §7）。

## 4. C1/C2 验证证据（摘要）

- C1：`AuthenticationSessionApiM12I01IntegrationTest` 7 测试（枚举/current 标记/他属 404/自删他设备/删当前=登出/未知 id/未认证守卫+CSRF 先序）；`IdentityPolicyConfigurationTest` 非法矩阵；`BrowserOrganizationAnchorTest`；`AuthenticatedAccountOrganizationResolverTest` 扩展（OIDC provider 通过/local 缺失不误杀/不匹配拒绝/旧会话 null→local）；OpenAPI 漂移门禁基线 305。
- C2：`OidcLoginHttpM12I01IntegrationTest` 6 测试（首登四件套+outbox/再认证 last_authenticated_at 前进/INVITE_ONLY 无副作用/锁定拒绝/429/Redis 断供 503）；`OidcSsoOnlyHttpM12I01IntegrationTest` 3 测试（403 两码+SSO 仍可用）；`EmbeddedOidcIdpTest` 11；`OidcAccountAuthenticationServiceTest`/`ExternalAccountIdentityTest`/`LoginIdentityM7D02Test`（external 工厂盖章首认证）；审计 139 两门禁；`RedisLoginDefenseM7I04IntegrationTest`（admitNetwork）。

## 5. 复现命令

```bash
# C3 攻击集 + 撤销延迟
./mvnw -q -pl crewscope-server -am \
  -Dtest='OidcProtocolAttackM12I01IntegrationTest,OidcRevocationDelayM12I01IntegrationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
# 全量（提交前）
./mvnw --batch-mode --no-transfer-progress clean verify
```

## 6. 遗留登记（契约 §8 已声明）

1. GET `/api/v1/auth/session` 的 CSRF lazy cookie 在响应 commit 后二次写入（服务端 ISE 痕迹，客户端无感；观测层 200 记 errorCode=internal_error）——C1 遗留，独立切片处理。
2. 成功 callback 后框架二次 convert 的 `authorization_request_not_found` 日志噪音（失败处理器 isCommitted 防御已无害化，不影响浏览器结果）。
