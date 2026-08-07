# M0-I04：Credential Key Ring 与 Rewrap 基线

> 日期：2026-08-07<br>
> 状态：已完成

## 目标

将 `DatabaseEnvelopeCredentialStore` 的 AES-256 主密钥改为进程外 Key Ring 注入，建立无默认密钥的启动校验、历史密钥读取、在线 Rewrap、并发保护和配置面脱敏基线。

## 外部配置

```text
CREWSCOPE_CREDENTIAL_CURRENT_KEY_ID=credential-key-2026-08
CREWSCOPE_CREDENTIAL_KEYS=credential-key-2026-08=<base64-32-bytes>;
                          credential-key-2026-07=<base64-32-bytes>
```

- `CURRENT_KEY_ID` 选择新建、Secret Rotate 和 Rewrap 使用的密钥；
- `KEYS` 保留当前密钥与仍被活动信封引用的历史密钥；
- Key ID 符合 `[A-Za-z0-9][A-Za-z0-9._-]{0,99}`；
- 每个值是严格 32 字节 AES-256 密钥的 Base64；
- Key Ring 最多包含 16 个 Key；
- 配置缺失、格式非法、Key ID 重复或 Current Key 不存在时阻止 Spring Context 启动；
- 配置异常、Key Ring 和 Key Material 的字符串表示不包含密钥原文。

Spring 托管的 Key Ring 在 Context 关闭时清零内部 Key byte 缓冲。`close()` 幂等，关闭后不再允许复制 Key Material。

## 轮换与 Rewrap

1. 部署新 Key，将其设为 Current Key，同时保留历史 Key；
2. Resolve 按数据库 `key_id` 选择对应 Key；
3. `rewrapBatch(batchSize)` 每批选择 1–1000 个 `ACTIVE AND key_id <> current_key_id` 信封；
4. 先用历史 Key 完成 GCM 认证与解密，再使用 Current Key、新 Nonce 和新 AAD 加密；
5. 通过 `organization_id + id + ACTIVE + version + old_key_id` 原子更新；
6. 并发变更记入 Conflict，不覆盖新值；
7. `remaining=0` 后才允许在下一次部署中删除历史 Key。

Rewrap 是密钥维护操作，保留 Secret 内容、`rotated_at` 和业务修改 Principal，只刷新信封、`updated_at` 和 Version。

## 配置面脱敏

- Actuator Web 只暴露 `health,info,prometheus`；
- `env.show-values=never`；
- `configprops.show-values=never`；
- `health.show-details=never`；
- `application.yml` 不包含可用的 Base64 默认密钥；
- Health、Info 和 Prometheus 不注册 Key、Ciphertext、Nonce、Tag 和 Secret 值。

## 验证

`CredentialKeyRingParserTest` 的 8 个单元测试覆盖：

1. Current 与 Historical Key 解析；
2. Key Ring 与 Key Material 字符串脱敏；
3. 缺失配置；
4. 非法 Base64 和非 32 字节 Key；
5. 重复、超量和非法 Key ID；
6. Current Key 缺失；
7. 失败异常不回显配置原文；
8. 关闭幂等与 Key byte 不可再读。

`CredentialStoreConfigurationTest` 的 3 个 Context 测试覆盖有效注入、缺失配置 Fail Closed 和非法配置脱敏。

`CredentialKeyRotationIntegrationTest` 的 4 个 PostgreSQL 集成测试覆盖历史 Key Resolve、分批 Rewrap、Secret 不变、新信封与 Version、旧 Key 移除、缺失 Key 失败、并发冲突不覆盖和批量范围。

`CredentialActuatorConfigurationTest` 的 1 个 Server 测试固化 Actuator 暴露面、Show Values 和无默认密钥边界。

定向验证命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  -Dtest=CredentialKeyRingParserTest,CredentialStoreConfigurationTest,CredentialKeyRotationIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-server -am \
  -Dtest=CredentialActuatorConfigurationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：15 个 Infrastructure 测试和 1 个 Server 测试全部通过。

全仓验证命令：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

结果：7 个 Maven 模块成功，140 个测试全部通过。

## 后续

`M0-A01` 建立 `/api/v1` 错误信封、Cursor、`Idempotency-Key`、`If-Match` 和 Command Receipt 基线。
