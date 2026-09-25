# M9b 模型服务管理契约

> A03 的权威接口与安全边界说明。Provider/Catalog 是服务端目录事实，Connection 是用户可管理的凭证绑定；浏览器只接收安全投影，不接收 Endpoint、Credential ID 或 API Key。

## 1. 事实来源与版本

- Provider 由服务端受控目录提供，按 `providerKey` 定位，带 `status`、`version`、Region、数据留存和训练使用策略。普通成员不能通过模型连接接口提交任意 Endpoint。
- Catalog 按 Provider 分页返回，条目带 `catalogRevision`、`modelRevision`、能力、上下文窗口、最大输出 token 与可选的生效价格修订。价格缺失必须显示“未知/当前没有生效价格修订”，不能当作 0。
- Connection 的 `version` 是并发控制版本，`credentialVersion` 是凭据业务版本。创建后的健康状态为 `UNKNOWN`；凭据轮换会将健康重新置为 `UNKNOWN`。
- 所有版本化写命令均要求 `If-Match` 与 `Idempotency-Key`。命令回执只包含命令、领域事件、提交版本和关联 ID，不包含凭据或 Provider 原始响应。

## 2. Connection 生命周期

| 当前状态 | 操作 | 条件 | 结果 |
| --- | --- | --- | --- |
| `ACTIVE` | 验证健康 | Provider ACTIVE、连接/凭据版本匹配 | 记录 `HEALTHY` 或稳定失败码；不保存原始响应 |
| `ACTIVE` | 停用 | owner 权限、版本匹配 | `SUSPENDED`，保留加密凭据但停止新模型选择 |
| `SUSPENDED` | 验证健康 | Provider ACTIVE、当前凭据可用 | 更新当前凭据版本的健康事实 |
| `SUSPENDED` | 重新启用 | 当前凭据健康为 `HEALTHY`、Provider ACTIVE、版本匹配 | `ACTIVE`；记录 `MODEL_CONNECTION_ACTIVATED` |
| `SUSPENDED` | 重新启用 | `UNKNOWN`、`UNHEALTHY`、凭据过期或 Provider 非 ACTIVE | 拒绝；先验证/修复凭据，不改变状态 |
| `REVOKED` | 验证/重新启用/恢复 | 任意 | 拒绝；撤销是终态，不提供复活路径 |

API 路径：

```text
POST /api/v1/organizations/{organizationId}/model-connections/{connectionId}/verify
POST /api/v1/organizations/{organizationId}/model-connections/{connectionId}/suspend
POST /api/v1/organizations/{organizationId}/model-connections/{connectionId}/activate
POST /api/v1/organizations/{organizationId}/model-connections/{connectionId}/rotate
POST /api/v1/organizations/{organizationId}/model-connections/{connectionId}/revoke
```

`verify`、`suspend`、`activate` 的请求体只包含当前 `credentialVersion`；`rotate` 只在请求体短暂接收新 API Key，并在服务端写入 CredentialStore 后清除明文；`revoke` 还要求稳定撤销原因。

## 3. 权限、幂等与并发

- USER 连接只能由本人管理；TEAM/ORGANIZATION 连接需要对应 Provider Manager 权限。读 Team 连接不等于拥有管理或读取凭据的权限。
- 服务端每次命令重新解析 owner、Provider、连接版本和凭据版本。前端禁用按钮只是体验提示，不能作为授权边界。
- 相同意图重放同一个 `Idempotency-Key` 返回原命令回执，不重复健康探测、状态变更或审计事件；修改目标、凭据或版本是新意图。
- `If-Match`、连接版本或凭据版本过期时返回冲突；客户端应重新读取连接事实，不盲目覆盖。

## 4. 凭据与网络边界

- Endpoint 来自服务端 Provider Catalog，`ModelEndpoint` 仅接受绝对 HTTP(S)，拒绝 userinfo、query 和 fragment；普通用户没有任意 URL 探测入口。
- 健康探测只请求固定的 `<endpoint>/models`，使用短时 `ProviderCredentialHandle` 注入 Bearer，响应体丢弃，不把 API Key 写入 DTO、URL、前端 Store、日志、错误或审计摘要。
- 健康探测 HTTP 客户端显式 `Redirect.NEVER`，不把 Bearer 凭据转发到重定向目标。超时、网络错误、401/403、429 和其他响应只映射为稳定失败码。
- 本地/私网 Provider 是否可用由部署者控制的 Provider Catalog 决定；这不是普通成员的 SSRF 能力。若暴露到不可信用户，必须把 Provider 管理限制在平台管理员并在网络层限制可达范围。

## 5. 前端展示合同

- 模型设置页按 Provider → Catalog → Connection 展示；高级目录修订和策略是只读事实。
- Connection 详情只显示安全投影和稳定健康失败码。`SUSPENDED` 连接展示“重新启用”，只有当前健康为“健康”时按钮可用；未知/不健康时说明先验证或轮换凭据。
- 价格未知显示“当前没有生效价格修订”，不能显示 `0` 或伪造估算。上下文窗口、最大输出 token、输入/输出价格单位分别展示，不能把字符数当 token 或费用。
- 创建/轮换后必须显式验证健康；保存成功不代表可以执行模型调用。

## 6. 兼容与回退

旧客户端没有 `activate` 按钮时仍可读取 `SUSPENDED` 投影；服务端保持撤销不可恢复和版本校验。OpenAPI 生成物须随 Controller 一起更新；A04 增加项目执行默认值接口后，当前操作总数为 223。
