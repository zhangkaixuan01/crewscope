# ADR-004：CredentialStore 与动作级凭证

> 状态：ACCEPTED<br>
> 日期：2026-08-05<br>
> 影响里程碑：M0、M2、M3、M5

## 背景

CrewScope 需要管理 GitHub App、OAuth Token、PAT、飞书凭证和服务身份。Agent、Sandbox、日志、Memory 和 Artifact 都属于凭证明文禁入区。Connector Worker 需要在精确动作期间获得最小权限。

## 决策

### CredentialStore Port

应用层只依赖 CredentialStore：

```text
store(subject, provider, secret, metadata) -> credentialRef
resolve(credentialRef, purpose, caller) -> secret handle
rotate(credentialRef, newSecret)
revoke(credentialRef, reason)
```

### Team Beta 实现

使用 `DatabaseEnvelopeCredentialStore`：

- AES-256-GCM 加密；
- organization、provider、connection、credential_id 和 key_id 作为 AAD；
- 数据库保存 ciphertext、nonce、tag、key_id、算法和元数据；
- 主密钥由进程外 Secret 注入；
- key_id 支持轮换和 rewrap；
- 明文只存在于 Connector Worker 动作执行的短生命周期内。

生产加固通过相同 Port 接入 Vault/KMS。

### Task Token 与动作凭证

- Agent 与 Sandbox 只获得短期 Task Token；
- Task Token 绑定 TaskExecution、Claim、Principal、ProviderBinding、Tool 和资源；
- Credential Service 校验 Task Token、PlannedAction、Confirmation、Binding 和 SafetyEnforcementOverlay；
- Connector Worker 获得动作级短期能力；
- Git Push 使用一次性 GitHub App installation token 和临时 `GIT_ASKPASS`；
- 临时文件、环境变量和进程在动作结束后立即清理。

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
