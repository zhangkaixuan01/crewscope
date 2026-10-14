# M12 企业身份与会话 API 契约

> 模块：M12-I01（OIDC 接入与企业身份）　状态：C1 已交付（2026-10-14）；C2 OIDC 核心流已交付（2026-10-15，§6）；C3 攻击集与收口后续切片<br>
> 依据：[ADR-036](../adr/ADR-036-企业身份与OIDC接入.md)、[S01 冻结记录](../spikes/M12-S01-现状盘点与合同冻结.md)、[主计划](../plans/M12-企业化与多租户治理.md) §6 I01

## 0. 本轮边界与偏差声明（先读）

C1 交付**与 provider 无关的底座**（会话主体带 `identityProvider`、会话枚举/单设备撤销 API、身份策略维度、组织锚点单源、`/auth/session` 响应面扩展）；C2 交付 **OIDC 授权码流本体**（§6：PKCE、首登开通/再认证、防护衔接、内嵌测试 IdP）。**协议攻击集、撤销延迟钉死与 runbook 归 C3**。

### 偏差声明（相对 S01 冻结卡与主计划）

1. **切片重组**：S01 冻结卡的卡C1（OIDC 核心）/卡C2（策略与枚举）/卡C3（攻击与救援）按依赖序重组为提交切片——本 C1=卡C2 策略与枚举全量+卡C1 的会话同构底座；C2=卡C1 剩余（首登/落点）+防护衔接；C3=卡C3+救援文档。内容集不变。
2. **`IdentityPolicyProperties` 未单独成类**：`crewscope.security.identity-policy` 经 `@Value` 注入 `IdentityPolicyConfiguration`（与 `SecurityMode`、`SecurityConfiguration` 既有惯例一致），策略解析与启动守卫矩阵集中于一个配置类，无行为差异。
3. **`AccountSessionView.loginIdentities` 经由 `AccountOrganizationResolution` 携带**（第五字段+`withLoginIdentities` 装饰），快照已 JOIN 的 login_identity 列表随解析链带出，控制器零额外查询；三个既有测试构造点经四参便捷构造器保持不动。
4. **M7 响应面门禁评审豁免**：`SessionResponse.ssoAuthorizationUrl` 字段名含 `authorization` 子串，命中 M7-A07/M7-Q01 两处字段名黑名单门禁；该字段承载公开框架登录路径 `/oauth2/authorization/{registrationId}`（非凭证材料），ADR-036 冻结其名，两测试中已登记为显式评审豁免条目（其余字段仍受黑名单约束）。

## 1. 端点总览（本片 +2 操作，OpenAPI 303→305）

| # | 方法/路径 | 认证 | 成功 | 主要错误 |
| --- | --- | --- | --- | --- |
| 1 | GET `/api/v1/auth/sessions` | 浏览器会话 | 200 `{sessions:[{sessionId,createdAt,lastAccessedAt,current}]}`（`lastAccessedAt` 降序，no-store） | 401/503 |
| 2 | DELETE `/api/v1/auth/sessions/{sessionId}` | 浏览器会话+CSRF | 204（删除当前会话=登出等效） | 404 `session_not_found`（含他属 id，不确认存在性）/401（CSRF 门禁先于认证：未认证且缺 CSRF 令牌的请求先得 403 fail-closed）/503 |

既有端点行为扩展（无新增路径）：

- GET `/api/v1/auth/session`：响应新增 `identityPolicy`（`local-only`/`sso-only`/`coexist`，镜像配置词汇）与 `ssoAuthorizationUrl`（已配置 OIDC registration 时为 `/oauth2/authorization/{registrationId}`，否则 null；匿名与已认证对称）；`account` 新增 `loginIdentities:[{provider,status,lastAuthenticatedAt}]`（**不含 subject**）。
- POST `/api/v1/auth/login`：`sso-only` 部署 → 403 `local_login_disabled`（在 LoginDefense 准入**之前**，不消耗防护窗口、不留防护痕迹）。
- POST `/api/v1/auth/register`：`sso-only` 部署 → 403 `local_registration_disabled`（同上，先于幂等键消费与防护准入）。

## 2. 会话枚举与单设备撤销

- **作用域**：枚举与删除均以会话主体（accountId）为作用域在 `BrowserSessionLifecycle` 内完成；`ReactiveSessionRegistry.findByPrincipalName` 先例（M7I02）+ 会话仓储 `findById` 补 `createdAt`/`lastAccessedAt`。已过期或索引与存储脱节的行静默跳过。
- **他属不可区分**：删除请求的 sessionId 不属于本人时与不存在的 id 同形——404 `session_not_found`，**不确认存在性**（防枚举探测）。
- **当前会话标记**：`current` 由请求自身会话 id 比对得出；删除当前会话后，同一浏览器后续请求即匿名（登出等效，无需 `/logout`）。
- **删除通道**：删除当前会话走 `WebSession.invalidate()`（请求提交阶段不会把已删会话的部分数据回写存储）；删除其他设备会话按 id 直删索引+存储。读取端把任何残缺会话数据（如并发设备在删除后回写的部分字段）一律视为「无此会话」——受影响设备下一请求匿名重建，不产生 500。
- **CSRF**：DELETE 走既有 Cookie CSRF 通道（`XSRF-TOKEN` cookie + `X-XSRF-TOKEN` 头）；GET 无需 CSRF。
- **缓存**：两端点均 `Cache-Control: no-store`（设备列表是安全敏感投影）。

## 3. 身份策略（identity-policy）

配置键 `crewscope.security.identity-policy`，三值 `local-only` / `sso-only` / `coexist`（默认 `coexist`）。策略是**叠加维度**（叠加在 `crewscope.security.mode` 之上），不是第四种 mode。

### 3.1 启动失败矩阵（非法组合拒绝启动，不降级）

| mode \ policy | coexist | local-only | sso-only |
| --- | --- | --- | --- |
| bootstrap | ✅ | ❌ ISE | ❌ ISE |
| local | ✅ | ✅ | ❌ ISE |
| oidc | ✅ | ❌ ISE | ✅ |

未知/空白策略值同样拒绝启动（`IdentityPolicy.from`，词汇错误信息含合法值清单）。

### 3.2 运行时门禁（fail-closed）

- `allowsLocalPasswordLogin()`：仅 `sso-only` 为 false → POST /login 403 `local_login_disabled`。
- `allowsLocalPasswordRegistration()`：仅 `sso-only` 为 false → POST /register 403 `local_registration_disabled`。
- **sso-only 下 IdP 故障不静默退回本地密码**（ADR-036 红线）：门禁是常量策略判定，与 IdP 可用性无关。
- 防护窗口零消耗：两处 403 均先于 `LoginDefense.admit`，失败的本地登录尝试不会喂满任何标识符/网络桶。

## 4. 组织锚点单源（BrowserOrganizationAnchor）

- **local/bootstrap 模式**：锚点=`crewscope.registration.organization-id`（可空；空值保持既有端点 503 行为，不变）。
- **oidc 模式**：锚点=`crewscope.security.oidc.organization-id`（**必填**，缺失拒绝启动）；`registration.organization-id` 非空且不一致 → 拒绝启动（两个入口永不分歧）。
- 消费方：`/auth/session`、`/register`、`/account`、onboarding、邀请、仓库身份解析器与 OIDC subject extractor 全部经锚点取值（extractor 不再直接读配置键）。

## 5. 会话主体同构（兼容性注记）

`BrowserSessionPrincipal` 新增第三字段 `identityProvider`（`local` 或 `oidc/{registrationId}`）；**存量 Redis 会话缺字段反序列化为 null → 归一为 `local`**，升级部署无迁移、旧会话按 local 复检继续通过。会话复检（`resolveSession`）现在按「建立会话的那个 provider 的唯一 usable 身份」解析：OIDC-only 账号（无 local 身份）的会话不再被 local 强制误杀；local 会话在账号只剩 OIDC 身份时正确拒绝。

## 6. OIDC 授权码登录流（C2 交付）

`crewscope.security.mode=oidc` 时浏览器 SSO 登录走标准授权码+PKCE 流：

```
浏览器 → GET /oauth2/authorization/{registrationId}          （CrewScope：start gate → 302 IdP）
       → GET {issuer}/authorize?...（code_challenge S256）    （IdP：302 回 redirect_uri）
       → GET /login/oauth2/code/{registrationId}?code&state   （CrewScope：换 ID token、
                                                               首登开通或再认证、建立会话 → 302 /）
```

### 6.1 产品红线

- **PKCE（S256）无条件强制**（`OidcPkceAuthorizationRequestResolver`，无配置开关）：confidential client 同样携带 `code_challenge`；不带 challenge 的流在 C3 攻击集 fail-closed。
- **establish 后不持有、不转发任何 IdP 令牌**：code 交换得来的 access/refresh token 即弃——授权码流与 CrewScope 会话是两个不相交的信任域（撤销延迟上限见第 7 章）。
- **无 CommandReceipt**：`(provider, subject)` 行即幂等事实。并发首登在 `findByIdentityKeyForUpdate` 行锁+唯一索引（`uk_login_identity_provider_subject`）上收敛——恰一个事务开通，另一个事务换锁后转入再认证；账号/身份唯一冲突按确定性 attempt(1..3) 重推导，耗尽 fail-closed（不引入随机熵）。

### 6.2 首登开通（仅 registrationMode=OPEN）

每次 OIDC 首登在**单事务**内开通四件套并复检 outbox：`user_account`（source=SSO）→ `login_identity`（provider=`oidc/{registrationId}`，subject=sub claim）→ `principal`（**不带外部身份列**，联邦真值唯一存 login_identity）→ `account_organization_binding`；调用 `ensureInitialOwner`（operator-bootstrap 部署为 no-op）；发 `USER_ACCOUNT_REGISTERED`（source=SSO）+`AUTHENTICATION_SUCCEEDED` 两域事件入 outbox。INVITE_ONLY/DISABLED 部署首登拒绝（`sso_error=registration_blocked`），无任何账号副作用。

身份派生（ADR-036 冻结，域类 `ExternalAccountIdentity`）：

| 字段 | 规则 |
| --- | --- |
| displayName | claims `name→preferred_username→email→sub` 首个非空（全空 fail-closed） |
| email | 仅 `email_verified=true` 采纳；否则合成 `{username}@sso.oidc.invalid`（RFC2606 保留域，永不与真实邮箱冲突） |
| username | 优先 `preferred_username`（非法/缺失→`sso-{sha256("oidc/{regId}|{sub}") 前 12 hex}`）；冲突按 attempt(1..3) 确定性重推导 |

### 6.3 登录结果重定向（302，错误码固定枚举）

| 场景 | Location |
| --- | --- |
| 成功（首登或再认证） | `/`（会话 cookie 随响应轮换下发） |
| 身份/账号/绑定不可用 | `/login?sso_error=denied` |
| 已知账号被防护锁定 | `/login?sso_error=locked` |
| 部署拒绝首登 | `/login?sso_error=registration_blocked` |
| 基础设施故障（泛化） | `/login?sso_error=unavailable` |
| 协议级失败（state/nonce/签名/redirect_uri 验证，含 C3 攻击集全部行） | `/login?sso_error=denied`（异常文本留服务端，不回显） |

错误码只描述部署状态，永不携带 subject/claims 内容。

### 6.4 LoginDefense 衔接

- **start gate**（`OidcAuthorizationStartDefenseWebFilter`，先于授权重定向）：GET `/oauth2/authorization/*` 消耗**独立 OIDC 网络窗口**（`admitNetwork`，不与密码登录共享双键桶——OIDC 无用户输入 identifier）；限流→429 `too_many_requests`；防护存储不可达→503 `login_defense_unavailable`（不可达的限流器绝不退化为不限流）。
- **已知账号**：再认证先 `observeAccount`——锁定→`recordFailure`+拒绝建立会话；成功路径 establish 后 `recordSuccess` 清算账号失败窗口。
- **首登**：新账号无失败历史，仅消耗网络窗口。

## 7. 撤销延迟（诚实上限，C3 测试钉死）

- **账号级禁用/密码轮换/全量撤销**：即时——既有 `securityVersion` 复检（下一请求即匿名）+ `invalidateAll`（Redis 会话键即刻消失）。
- **IdP 侧令牌失效**：上限=会话 TTL（12h）。CrewScope 在 establish 后**不持有、不刷新** IdP 令牌，故无法提前感知 IdP 侧撤销；此为架构性上限而非实现延迟，不承诺「下次令牌刷新」。

## 8. 已知限制（声明不改）

1. **OIDC-only 账号不可用 `/account/sessions/revoke`、`/account/password`、PATCH `/account`**（均要求 `currentPassword`，M7 冻结契约）——账号级撤销=逐会话 DELETE（本片新增）或运维救援（C3 runbook）。
2. **INVITE_ONLY 部署无 SSO 账号预置通道**（C2 细化：首登仅 OPEN 放行）——本地注册+OIDC 首登会建两个账号，是「禁止邮箱相同自动合并」红线的直接推论。
3. `ssoAuthorizationUrl` 目前仅指向**首个** registration（多 IdP 部署入口选择归后续里程碑）。

## 9. OpenAPI

操作基线 303→305（GET/DELETE sessions）；`crewscope-web/src/api/generated/openapi.ts` 与 state-machines 指纹随本片同笔再生成。前端消费归 M12-F03。
