# M7-A02 JSON 登录与 Session 投影

## 交付范围

CrewScope 新增 `local` Security Mode，并在 `POST /api/v1/auth/login`、`POST /api/v1/auth/logout` 和 `GET /api/v1/auth/session` 闭合自有浏览器认证。Team Beta API 固定使用 `local`，Worker 保持无浏览器 Session 的 `bootstrap` 运行角色；本地账号 Session 与监控 Basic 凭证继续由独立 SecurityWebFilterChain 隔离。

登录先消费 `AuthenticationFlow.LOGIN` 的标识与受控网络窗口。账号不存在、标识非法、账号不可认证、Local Identity 不可用、Credential 不可用、临时锁定和密码错误都执行一次真实或 Dummy Password Match，并返回同一个 `invalid_credentials`。只有成功认证会清除该账号失败状态并通过 `BrowserSessionLifecycle` 旋转 Session ID、执行账号级 Session 上限并保存无 Credential 的 SecurityContext。

退出只失效当前浏览器 Session。匿名 Session 投影返回 Registration Mode 和 CSRF Header、Parameter、Token 坐标；认证投影每次按 Session 中的 Account ID 与 SecurityVersion 读取当前 Account、唯一 ACTIVE Local Identity、Organization Binding、USER Principal、Team Membership、有效 MemberRole 和 ACTIVE TeamRole 权限。任一当前事实失效都会删除该 Session，不信任 Redis 中缓存的旧 Authority。

## 公开字段

认证投影只包含当前用户自己的 Account ID、Username、Display Name、PlatformRole、SecurityVersion、Account Version、Principal/Organization ID，以及 Team ID、Team Name、Member ID 和权限名称。密码、Hash、Credential/Identity/Binding ID、邮箱、Session ID、Cookie、失败计数、锁定截止时间、Role Grant ID 和审计内部字段均不返回。所有 Auth 响应使用 `Cache-Control: no-store`，Local Mode 不发送 `WWW-Authenticate: Basic`。

## 验证

- `LocalAccountLoginServiceM7A02Test`：3 个账号命中、Dummy Match、成功清理和锁定失败场景；
- `AuthenticationControllerM7A02Test`：5 个匿名/认证 Session、固定失败、资源限流、当前 Team 权限和状态撤权场景；
- `SecurityConfigurationTest`、`TeamBetaDeploymentGuardM6I09Test`：12 个 Security Mode、无 Basic Challenge 和部署角色合同场景；
- `BrowserSessionLifecycleM7I02IntegrationTest`：真实 Redis 的 5 个旋转、两浏览器、多 Repository 实例、续期、LRU、过期、退出和故障关闭场景。

上述定向门禁共 25 个场景通过。
