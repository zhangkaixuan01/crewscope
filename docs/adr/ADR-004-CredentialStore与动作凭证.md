# ADR-004：CredentialStore 与动作级凭证

> 状态：ACCEPTED<br>
> 日期：2026-08-05<br>
> 更新：2026-08-15（M3-I04 接通 Task Token Key Ring、当前事实复验与请求中间件）<br>
> 影响里程碑：M0、M2、M3、M5

## 背景

CrewScope 需要管理 GitHub App、OAuth Token、PAT、飞书凭证和服务身份。Agent、Sandbox、日志、Memory 和 Artifact 都属于凭证明文禁入区。Connector Worker 需要在精确动作期间获得最小权限。

## 决策

### CredentialStore Port

应用层只依赖 CredentialStore：

```text
create(createRequest, secret) -> credentialDescriptor
resolve(credentialRef, accessContext) -> resolvedCredential
rotate(credentialRef, expectedVersion, mutationContext, newSecret) -> credentialDescriptor
revoke(credentialRef, expectedVersion, mutationContext, reason) -> credentialDescriptor
```

`CredentialCreateRequest` 使用调用方生成的稳定 Credential ID，包含 Organization、Credential Subject、组织内唯一 Credential Key、Provider、可选 Connection、Credential Type、非敏感元数据、可选到期时间和创建 Principal。Credential Subject 使用 `ORGANIZATION/TEAM/PRINCIPAL`，Scope 形状必须与 V2 数据库约束一致。

`CredentialReference` 同时携带 Organization ID 和 Credential ID，所有 SQL 都带 Organization 谓词。`CredentialAccessContext` 由 Credential Service 根据 Task Token、PlannedAction 和 ProviderBinding 构建，包含显式允许的 Credential ID 集合和用途。缺失与无权读取统一返回空结果。

`CredentialSecret` 使用可关闭的短生命周期 byte 容器，构造和读取都防御性复制，`close()` 清零内部缓冲区，`toString()` 只返回脱敏标记。Descriptor、异常、日志和元数据不保存明文。

### Team Beta 实现

使用 `DatabaseEnvelopeCredentialStore`：

- AES-256-GCM 加密；
- 每次写入使用 96 位随机 Nonce 和 128 位 Authentication Tag；
- AAD v1 使用长度前缀二进制编码，绑定 Credential ID、Organization、Subject 形状、Credential Key、Provider、Connection、Credential Type、到期时间、规范元数据、算法和 Key ID；
- Status、操作人、时间和乐观锁 Version 不进入 AAD，便于不解密执行原子撤销；
- 数据库保存 ciphertext、nonce、tag、key_id、算法和元数据；
- 主密钥由进程外 Secret 注入；
- key_id 支持轮换和 rewrap；
- 明文只存在于 Connector Worker 动作执行的短生命周期内。

密文和 Tag 分列保存。解密前重建 AAD，Ciphertext、Tag、AAD 或主密钥任一不匹配时返回稳定 `INTEGRITY_VIOLATION`，不返回密码学库原始消息。解析已撤销或已到期凭证返回空结果。Secret 轮换先认证旧信封，再使用 `organization_id + id + version + ACTIVE` 原子更新。撤销不解密明文，使用安全随机数覆盖 Ciphertext、Nonce 和 Tag 后原子更新状态，即使数据库状态被改回 `ACTIVE` 也无法通过认证。

生产加固通过相同 Port 接入 Vault/KMS。

### 密钥注入与轮换

Spring Boot 只从进程外配置读取密钥环，不提供仓库默认密钥：

```text
CREWSCOPE_CREDENTIAL_CURRENT_KEY_ID=credential-key-2026-08
CREWSCOPE_CREDENTIAL_KEYS=credential-key-2026-08=<base64-32-bytes>;
                          credential-key-2026-07=<base64-32-bytes>
```

`CURRENT_KEY_ID` 指向新建、Secret Rotate 和 Rewrap 使用的密钥。`KEYS` 保留当前与未完成 Rewrap 的历史密钥，每个值是严格 32 字节主密钥的 Base64。启动时校验配置完整性、Key ID 形式、重复 ID、Base64、密钥长度、密钥数量和当前 ID 存在性；任一失败时终止启动，异常不包含配置原文。

Resolve 按数据库 `key_id` 选择历史密钥。Rewrap 按稳定 Credential ID 顺序批量处理 `ACTIVE AND key_id <> current_key_id` 的信封：先使用历史密钥认证并解密，再使用当前密钥、新 Nonce 和新 AAD 加密，最后以 `organization_id + id + ACTIVE + version + old_key_id` 乐观锁原子更新。并发业务变更形成 Conflict 计数，不覆盖新值。只有旧 Key ID 引用计数为零后才从下一次部署配置移除历史密钥。

Key Ring、Key Material、Parser、CredentialSecret 和 ResolvedCredential 的字符串表示统一脱敏。Actuator 不暴露 `env/configprops`，且对两个端点显式配置 `show-values=never`。Health、Info 和 Prometheus 不注册密钥、密文、Nonce、Tag 或凭证明文指标。

### Task Token 与动作凭证

- Agent 与 Sandbox 只获得短期 Task Token；
- Task Token 生存期为 5 秒至 15 分钟且不超过当前 ExecutionLease；
- Task Token 通过共享 `TaskTokenGrantScope` 闭合 Organization、Team、Workspace、Task、TaskExecution、attempt、ExecutionLease、Runtime、Worker、Claim Token Hash、Fencing Token、Execution Principal、PolicySnapshot 和 SafetyEnforcementOverlay；
- Task Token 只包含 Policy 和 Safety 允许的 Tool，Provider 范围固化 Binding/ConnectionGrant 版本、Capability 和显式资源最小子集；
- JTI 明文只进入一次性签发结果，数据库保存 SHA-256 Hash，字符串输出统一脱敏；
- `TaskCredentialGrant` 记录使用次数、最后使用时间、撤销/过期终态、乐观锁和审计事实；
- 使用时校验 Grant/Claims 闭合、当前 Lease 全坐标、Tool、ProviderBinding、Capability、Resource 和 Grant Version；
- JWT 使用外部 HS256 Key Ring、显式 `kid`、固定 issuer/audience/type、精确时间与完整 Scope SHA-256 承诺；当前 Key 签发，保留的历史 Key 只验证存量短期 Token；
- `/api/internal/v1/worker/**` 只接受 Bearer Task Token；服务端回查 Grant、Lease、TaskExecution、Execution Principal 和 Provider 当前事实后注入可信上下文，不接受 Basic、浏览器 Session 或 Body 身份回退；
- Token 轮换原子撤销旧 Grant 并创建新 JTI/Grant，新范围只能等于或窄于旧范围；
- Credential Service 校验 Task Token、PlannedAction、Confirmation、Binding 和 SafetyEnforcementOverlay；
- Connector Worker 获得动作级短期能力；
- Git Push 使用一次性 GitHub App installation token 和临时 `GIT_ASKPASS`；
- 临时文件、环境变量和进程在动作结束后立即清理。

V10 `task_credential_grant` 只保存 JTI Hash 和 Claim Token Hash，两者都是 64 位小写 SHA-256 文本。表中没有 JTI 或 Claim Token 明文列。Grant 使用复合外键闭合 TaskExecution、ExecutionLease、Runtime/Worker、Execution Principal、PolicySnapshot 和 SafetyEnforcementOverlay；Tool 和 Provider 授权使用子表保存最小集合。`ux_task_credential_grant_active_execution` 裁决每个 TaskExecution 的单活动 Grant，轮换在同一事务中终止旧 Grant 并创建新 Grant。

## 结果

- Agent 上下文没有长期凭证；
- 开发和 Team Beta 获得可实施的凭证存储；
- Vault/KMS 接入保持稳定扩展边界；
- 每次凭证使用可以关联 Task、Action、Credential Subject 和 AuditEvent。

## 验证

1. 数据库、日志、Trace、异常、Artifact 和模型上下文没有凭证明文；
2. 篡改 ciphertext、AAD 或 tag 后解密失败；
3. 过期、撤销、Claim 不匹配和范围不足的 Task Token 无法换取凭证；
4. Connection 撤销立即使待执行 Action 过期；
5. Git Push 完成后 AskPass 和 installation token 完成清理；
6. 主密钥轮换后历史凭证可以 rewrap 并继续受审计访问。

## 重新评估条件

- Team Beta 进入正式生产；
- 组织要求专用 Vault、云 KMS 或 HSM；
- 引入跨区域密钥和数据驻留；
- 外部 Provider 支持原生短期身份联邦。
