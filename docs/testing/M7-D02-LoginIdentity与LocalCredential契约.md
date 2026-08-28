# M7-D02 LoginIdentity 与 LocalCredential 契约

> 任务：`M7-D02`<br>
> 日期：2026-08-28<br>
> 状态：完成<br>
> 关联决策：[ADR-024](../adr/ADR-024-Account与Principal身份边界.md)、[ADR-025](../adr/ADR-025-本地密码与登录防护参数.md)

## 1. 交付目标

M7-D02 在 D01 稳定 UserAccount ID 上建立认证身份与本地 Credential 的正式领域/应用契约：

- `LoginIdentity` 保存 Account、Provider、不可变 Subject、状态、最后成功认证时间、乐观版本和生命周期；
- `IdentityProviderKey` 规范配置键，`LoginIdentitySubject` 保留 Provider 签发的精确文本值；
- `LoginIdentityKey` 和 `AccountIdentityProviderKey` 固定 V31 的两类唯一坐标；
- `LocalCredentialMetadata` 与 `LocalPasswordHash` 分离非秘密元数据和受限哈希值；
- `LoginIdentityRepository` 与 `LocalCredentialMetadataRepository` 不接受 Principal、TeamMember、Session、密码或 Hash。

## 2. Provider 与 Subject

`IdentityProviderKey` 使用 trim、NFKC 和 Locale-independent 小写形成最多 100 字符的配置路径，例如 `local`、`oidc/corporate`。它是 CrewScope 配置键，不是 Provider 签发的外部 Subject。

`LoginIdentitySubject` 是最多 500 code point / 1,024 UTF-8 byte 的不透明精确文本值：

```text
no trim; surrounding whitespace is rejected
no lowercase
no NFC / NFKC
exact equality only
toString = [REDACTED]
```

实现不会删除首尾空白，而是将这类输入直接拒绝；控制字符、双向覆盖、私用字符和长度超限同样失败关闭。NFC 与 NFD 形式被视为两个不同 Subject，避免平台改写 Provider 签发的身份值。

`LoginIdentity.local` 不接受 Subject 参数，始终从 UserAccountId 派生 Subject。持久化恢复同样复验该不变量，邮箱、用户名或其他 Account ID 不能伪装本地 Subject。`LoginIdentity.external` 明确拒绝 `local` Provider。

## 3. Identity 状态与唯一性

Identity 状态转移图：

```text
ACTIVE   -> DISABLED | REVOKED
DISABLED -> ACTIVE | REVOKED
REVOKED  -> terminal
```

只有 ACTIVE Identity 可认证。成功认证只推进 `lastAuthenticatedAt`、生命周期时间和乐观版本，Provider、Subject 和 Account ID 不变。Subject 换绑使用受验证事务撤销旧 Identity 并创建新 Identity，领域模型不提供 `changeSubject`。

V31/I01 必须实现：

```text
UNIQUE(provider, subject)
UNIQUE(account_id, provider)
```

同一 Account 可以拥有 local 和多个不同 Provider Identity，但不能在同一 Provider 下拥有两个 Subject；一个 Provider/Subject 也不能跨 Account 复用。两类冲突统一映射为无 Subject 和约束名的 `login_identity_conflict`。

## 4. Local Credential 边界

`LocalCredentialMetadata` 只保存：

```text
credentialId
accountId
algorithm
credentialVersion
passwordChangedAt
optimisticVersion
lifecycle
```

类字段与 Metadata Repository 方法不包含 `LocalPasswordHash`、String Hash、明文密码或 Secret。`LocalPasswordHash` 单独保持有界可打印 ASCII 编码，只能通过显式 `encodedValue()` 交给后续受信 I03 Adapter；`toString()` 固定为 `LocalPasswordHash[REDACTED]`。

M7-I01 将 `LocalCredentialMetadataRepository` 进一步收敛为只读与锁定端口。V31 的 Credential 行要求 Hash 与元数据原子写入，缺少 Hash 的 Metadata Port 不声明无法安全兑现的 `create/update`；M7-I03 的受信 Hash Store 负责创建、轮换和 compare-and-set，并继续通过 Metadata Port 向通用账号路径提供非秘密读取。

`PasswordHashAlgorithm` 闭集包含 ARGON2ID 与 BCRYPT。两者都可作为历史 Reader，但只有 ARGON2ID 是当前 Writer：

- 新建 Credential 要求 Argon2id；
- 轮换/Rehash 目标要求 Argon2id；
- 持久化恢复可读取 BCrypt，成功认证后以版本条件升级；
- Argon2id 降级到 BCrypt 失败关闭。

每次轮换同时推进 Credential Version、Metadata 乐观版本、Password Changed At 和 Lifecycle。每 Account 最多一个 Local Credential，唯一冲突使用不含哈希与 Account ID 的 `local_credential_conflict`。

## 5. 验证

专项命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-domain,crewscope-application -am \
  -Dtest=LoginIdentityM7D02Test,LocalCredentialMetadataM7D02Test,LoginIdentityRepositoryM7D02Test \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

27 个测试通过，0 Failure、0 Error、0 Skip：

| 模块 | 测试数 |
| --- | ---: |
| `crewscope-domain` | 22 |
| `crewscope-application` | 5 |
| 合计 | 27 |

覆盖：

1. Provider Key 兼容形、大小写、路径形状和长度边界；
2. Subject 精确相等、NFC/NFD 区分、code point/UTF-8 双预算和恶意 Unicode；
3. local Account ID Subject 派生、恢复复验和 external/local 工厂分离；
4. Identity 状态机、成功认证时序、Subject 不可变和终态撤销；
5. 同 Account 多 Provider、Provider/Subject 跨 Account 禁止和每 Account/Provider 唯一；
6. Argon2id/BCrypt Reader、Argon2id-only Writer、BCrypt 升级和降级拒绝；
7. Credential/Metadata 版本、时序、溢出和单 Account 冲突契约；
8. Subject、Hash、明文、Secret 的字符串与 Metadata Repository 零泄漏。

## 6. 后续边界

- M7-D03 在 Account/Identity/Credential 状态上建立密码与登录尝试策略；
- M7-D04 建立 AccountOrganizationBinding 和 Principal 持续授权链；
- M7-D07 以 V31 物理唯一索引和受限 Hash 列落地本契约；
- M7-I01 实现三个 Repository Adapter，M7-I03 实现受信 Hash Store、PasswordEncoder 和安全 Rehash。
