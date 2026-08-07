# M0-I03：DatabaseEnvelopeCredentialStore 基线

> 日期：2026-08-07<br>
> 状态：已完成

## 目标

建立框架无关的 `CredentialStore` Port 和开发/Team Beta 可用的 PostgreSQL AES-256-GCM 信封加密实现。长期凭证明文只通过显式授权的短生命周期 Handle 进入 Connector Worker。

## 应用契约

```text
create(createRequest, secret) -> credentialDescriptor
resolve(credentialReference, accessContext) -> resolvedCredential
rotate(reference, expectedVersion, mutationContext, newSecret) -> descriptor
revoke(reference, expectedVersion, mutationContext, reason) -> descriptor
```

- `CredentialId` 使用强类型非 Nil UUID；
- `CredentialSubject` 支持 `ORGANIZATION/TEAM/PRINCIPAL` 三种精确形状；
- `CredentialReference` 固化 Organization ID 和 Credential ID；
- `CredentialAccessContext` 携带显式允许的 Credential ID 集合与用途；
- 跨组织、未显式授权、已过期和已撤销的 Resolve 返回空结果；
- `CredentialSecret` 防御性复制 byte 数组，`close()` 清零内部缓冲，`toString()` 固定脱敏；
- Descriptor、Store 异常和 Resolved Handle 文本不包含明文。

## 信封格式

```text
algorithm       = AES-256-GCM
nonce           = 12 random bytes per write
authentication  = 16-byte tag
aadVersion      = 1
```

AAD v1 使用长度前缀二进制编码，绑定：

1. Credential ID 和 Organization ID；
2. Subject Type、Subject ID、Team ID 和 Principal ID；
3. Credential Key、Provider Key、Connection Ref 和 Credential Type；
4. Expires At 和按 Key 排序的非敏感 Metadata；
5. Algorithm、AAD Version 和 Key ID。

Ciphertext、Tag、AAD 或 32 字节主密钥任一不匹配，统一返回安全的 `INTEGRITY_VIOLATION`。密文和 Tag 分列写入 V2 `credential_secret`，未修改已发布迁移。

## 生命周期

- Create 显式写入创建/修改 Principal、UTC 微秒时间和 Version 0；
- Secret Rotate 先认证旧信封，避免将被篡改的 AAD 字段重新合法化；
- Rotate 使用新 Nonce 和 `organization_id + id + ACTIVE + version` 原子乐观锁更新；
- Revoke 不解密明文，随机覆盖 Ciphertext、Nonce 和 Tag 后原子写入 `REVOKED`；
- 撤销后将数据库状态改回 `ACTIVE` 仍无法通过 GCM 认证；
- 撤销 Reason 由应用层 AuditEvent 事务保存，不写入密文表的非审计字段。

## 验证

`CredentialStoreContractTest` 的 5 个单元测试覆盖：

1. Subject 形状；
2. Metadata 规范化和不可变；
3. Secret 防御复制、清零和脱敏；
4. Tenant-qualified 显式授权；
5. Active、Expiry 和 Revocation 边界。

`DatabaseEnvelopeCredentialStoreIntegrationTest` 的 7 个 PostgreSQL 集成测试覆盖：

1. 密文、Nonce、Tag、Key ID、算法和 AAD Version 持久化；
2. 授权、跨组织隐藏和到期精确边界；
3. Ciphertext、Tag、Provider、Connection 和 Metadata 篡改拒绝；
4. 相同 Key ID 下的错误主密钥拒绝；
5. Secret 轮换、新 Nonce、乐观锁冲突和跨组织拒绝；
6. 撤销、信封销毁、状态篡改拒绝和过期 Version 冲突；
7. Organization 内 Credential Key 唯一性。

定向验证命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  -Dtest=CredentialStoreContractTest,DatabaseEnvelopeCredentialStoreIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：5 个应用契约测试和 7 个 PostgreSQL 集成测试全部通过。

全仓验证命令：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

结果：7 个 Maven 模块成功，124 个测试全部通过。

## 后续

M0-I04 实现主密钥环的进程外注入、启动配置校验、Key ID 轮换/rewrap、日志与 Actuator 脱敏。
