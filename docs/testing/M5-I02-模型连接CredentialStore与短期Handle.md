# M5-I02 模型连接 CredentialStore 与短期 Handle

> 任务：`M5-I02`<br>
> 日期：2026-08-23<br>
> 状态：通过<br>
> 关联决策：[ADR-004](../adr/ADR-004-CredentialStore与动作凭证.md)、[ADR-015](../adr/ADR-015-Agent模型目录、连接与配置解析.md)

## 1. 交付范围

M5-I02 将 `ModelConnection` 与加密 `CredentialStore` 接成完整生命周期：

- `ModelConnectionCredentialService` 原子编排 Connection 与 Credential 的创建、轮换和撤销；
- Provider 健康验证在数据库事务外执行，验证结果以 Connection/Credential 双版本前置条件写回；
- `ProviderCredentialHandle` 只保存 Connection ID、Credential Secret Version、签发时间和过期时间，不保存明文；
- Handle 每次使用时重新读取当前 Provider、Connection 和 Credential，Provider/Connection 停用、轮换、撤销、过期或关闭后立即失败；
- `OpenAiCompatibleModelProviderHealthProbe` 调用 Connection Endpoint 的 `/models`，只处理 HTTP 状态并映射平台稳定错误码；
- 创建、Handle 签发、验证成功/失败、轮换和撤销均写入 DomainEvent 与 Outbox，事件载荷只包含安全元数据；
- Server 使用构造器注入装配应用服务，通过类型安全的 `ModelCredentialProperties` 外部配置 Handle TTL、连接超时和请求超时，并在启动期校验安全上限。

HTTP 管理 API 仍由 M5-A01 交付；动态 AgentScope Model 创建与 Handle 消费由 M5-I03 交付。

## 2. Credential 双版本模型

`V23__credential_secret_business_version.sql` 为凭证增加 `secret_version`：

| 字段 | 语义 | Secret Rotate | KMS Rewrap | Revoke |
|---|---|---:|---:|---:|
| `version` | Credential Envelope 乐观锁版本 | +1 | +1 | +1 |
| `secret_version` | API Key 等业务明文版本 | +1 | 不变 | 不变 |

`model_connection.credential_version` 引用 `secret_version`。外键使用 `DEFERRABLE INITIALLY DEFERRED`，允许同一事务先轮换 Credential，再推进 Connection Binding，并在事务提交时验证最终一致性。这样 KMS Rewrap 不会让 Connection 健康状态失效，撤销也不会破坏历史绑定。

V22 升级时从已提交 `model_connection.credential_version` 回填其绑定 Credential 的 `secret_version`，因此保留非零的历史轮换版本。同一 Credential 如果出现多个冲突的当前绑定版本，迁移失败关闭，不猜测业务版本。

`CredentialStore.describe` 只返回授权范围内的非秘密 Descriptor。轮换和撤销先读取 Envelope `version`，Connection Binding 使用独立的 `secretVersion`。

## 3. 明文与错误边界

1. 创建和轮换命令不包含 Secret；明文通过单独的 `CredentialSecret` 单向输入，应用服务完成后关闭并清零。
2. Handle 不缓存 `CredentialSecret`。每次 `useSecret` 临时 Resolve，向受信回调传递防御性副本，回调结束后清零副本并关闭已解析凭证。
3. Handle 的 `toString`、DomainEvent、Outbox 和健康结果均不含 Secret、Authorization Header、Ciphertext、Nonce、Tag、Endpoint、Provider Body 或底层异常消息。
4. 健康探测只保留 `AUTHENTICATION_FAILED`、`ENDPOINT_UNREACHABLE`、`TIMEOUT`、`RATE_LIMITED`、`PROVIDER_REJECTED`、`POLICY_REJECTED`。
5. Provider Adapter 异常在应用边界转换为安全失败码，原始异常不会进入领域状态或审计载荷。

## 4. 自动化验证

M5-I02 新增和扩展以下验证：

- `ModelConnectionCredentialServiceTest`：创建、Handle 使用、轮换或 Connection 停用后旧 Handle 失效、关闭、TTL 过期、健康验证、撤销并发、审计脱敏和 Rewrap 版本独立；
- `OpenAiCompatibleModelProviderHealthProbeTest`：Loopback `/v1/models`、Bearer 注入、成功、认证失败和限流稳定映射；
- `DatabaseEnvelopeCredentialStoreIntegrationTest`：`describe` 授权边界以及创建、轮换、撤销的双版本语义；
- `CredentialKeyRotationIntegrationTest`：真实加密信封 Rewrap 推进 Envelope Version 且保持 Secret Version；
- `V23CredentialSecretBusinessVersionMigrationIntegrationTest`：V22→V23 升级、历史回填、延迟外键、同事务轮换、Rewrap 和伪造版本失败关闭；
- `ModelCredentialApplicationConfigurationM5I02Test`：Spring Bean 装配、Boot Duration 绑定、Provider-specific Probe 覆盖和 Handle TTL 启动期失败关闭。

最终回归结果：Application 运行 335 个测试，0 失败；Infrastructure Reactor 运行 495 个测试，0 失败；Server 运行 220 个测试，0 失败；文档链接和差异格式检查通过。所有测试使用占位 Secret，并断言事件和安全结果不包含明文。
