# M3-I04 Task Token 签发、验证与请求中间件

> 日期：2026-08-15<br>
> 状态：已完成<br>
> 适用范围：Task Token JWT、TaskCredentialGrant、ExecutionLease、授权复验、Key Ring、Worker 请求中间件和可信执行上下文

## 1. 交付结果

M3-I04 将 D06 的短期授权领域契约接入可运行的签发和请求验证链路：

```text
有效 TaskExecution + 当前 PlanningContext
  + ACTIVE ExecutionLease
  + 当前 PolicySnapshot / SafetyEnforcementOverlay
  + 最小 Tool / Provider 请求
  -> PostgreSQL 权威时间
  -> 256-bit 随机 JTI
  -> TaskCredentialGrant（仅保存 JTI SHA-256）
  -> HS256 Task Token（明文只返回可信 Worker 一次）
```

`TaskTokenService` 提供签发、验证、授权使用、范围收窄轮换和撤销。签发有效期为 5 秒至 15 分钟，并自动受当前 Lease `expiresAt` 上界约束；Lease 剩余时间不足 5 秒时失败关闭。

## 2. 最小签名载荷

JWT 使用显式 `kid`、`typ=crewscope-task+jwt`、HS256、issuer、audience、subject、JTI、issuedAt 和 expiresAt。载荷额外保存 Grant ID、Organization、Runtime Environment 与 `scope_sha256`。

`scope_sha256` 是以下完整范围的规范化承诺：

- Organization、Team、Workspace、WorkProject、Task、TaskExecution 和 attempt；
- Lease、Environment、Runtime、Worker、Claim Token Hash 和 Fencing Token；
- Execution Principal、Responsibility、PolicySnapshot 和 SafetyEnforcementOverlay；
- 排序后的 Tool、ProviderBinding、ConnectionGrant、Capability 和显式资源集合。

JWT 不复制长期 OAuth Token、PAT、GitHub App Key、Provider Credential 或 Claim Token 明文。服务端根据签名后的 Grant ID、JTI Hash 和范围指纹回查完整持久化范围，避免把数据库授权事实替换为客户端声明。

PostgreSQL 时间保留微秒精度。JWT 标准 NumericDate 保留秒级兼容字段，同时签名 `issued_at_exact/expires_at_exact`，验证时同时检查两种时间表示。

## 3. 每次请求的当前事实复验

`TaskTokenAuthenticator` 在每次内部 Worker 请求中验证：

1. JWT 算法、类型、Key ID、签名、issuer 和 audience；
2. JTI Hash 对应的 TaskCredentialGrant 仍为 ACTIVE；
3. Grant、subject、Organization、Environment、issuedAt、expiresAt 和范围指纹完全闭合；
4. ExecutionLease 仍为 ACTIVE，且 TaskExecution、attempt、Runtime、Worker、Claim Token Hash 和 Fencing Token 全坐标一致；
5. TaskExecution 当前 PlanningContext、Execution Principal、Policy 和 Safety 指针未变化；
6. Execution Principal 仍存在且可以行动；
7. PostgreSQL 权威时间位于 `[issuedAt, expiresAt)`。

`authorizeUse` 继续复验具体 Tool。Provider 使用还会复验当前 ProviderBinding 状态与版本、Capability/Resource，以及当前 ConnectionGrant 状态、版本和时间边界。Binding 禁用或 ConnectionGrant 撤销在下一次使用立即生效。

## 4. 轮换、撤销与 Key Ring

Token 轮换在一个事务中提交：

```text
旧 Grant ACTIVE -> REVOKED(TASK_TOKEN_ROTATED)
新 JTI + 新 Grant ACTIVE
新 Token Scope ⊆ 旧 Token Scope
```

轮换不能增加 Tool、ProviderBinding、Capability 或资源，也不能替换 ConnectionGrant ID。新 Token 使用独立 JTI。显式撤销立即使后续请求失败；达到过期边界的撤销请求提交 EXPIRED 终态。

签名 Key Ring 使用外部 Base64 Secret。当前 `kid` 负责签发，Key Ring 中保留的旧 Key 只负责验证存量短期 Token，因此可以先发布新 Key、切换 current key，再在最长 Token 生命周期后移除旧 Key。HS256 Key 少于 256 bit 时启动失败。

## 5. Web 请求边界

`/api/internal/v1/worker/**` 只接受单一 `Authorization: Bearer <Task Token>`。中间件在 bounded-elastic 调度器完成阻塞数据库复验，将 `TaskTokenExecutionContext` 写入 Exchange Attribute 和 Spring Security Reactor Context，并授予唯一 `TASK_RUNTIME` Authority。

Basic Auth、OIDC 浏览器 Session、重复 Authorization Header、查询参数 Token 和无效 Bearer 都不能调用内部 Worker 路由。失败响应固定为无敏感信息的 `401 task_token_invalid`，并设置 `Cache-Control: no-store`。M3-A03 将直接消费该可信上下文，不读取请求 Body 中伪造的 Principal、Scope、Runtime 或 Worker 身份。

配置：

```yaml
crewscope:
  security:
    task-token:
      enabled: true
      issuer: crewscope
      current-key-id: v1
      keys:
        v1: ${CREWSCOPE_TASK_TOKEN_KEY_V1}
```

`server`、`all` 和 `worker` Profile 都可以创建验证器；只有 `all/worker` 创建签发、轮换、授权使用和撤销服务。功能未启用时内部 Worker 路由仍要求 `TASK_RUNTIME`，不会回退到成员身份或长期 Worker 凭证。

## 6. 自动化证据

M3-I04 新增 22 个专项测试：

- `TaskTokenScopeFingerprintTest`：1 个；
- `DurableTaskTokenServiceTest`：4 个；
- `DurableTaskTokenAuthenticatorTest`：5 个；
- `NimbusTaskTokenCodecTest`：4 个；
- `TaskTokenWebFilterTest`：4 个；
- `TaskTokenSecurityConfigurationTest`：4 个。

覆盖签名与篡改、Key Rotation、弱 Key 启动失败、JTI、audience/issuer、精确 expiry、Lease/attempt/Runtime/Worker/Fencing、Principal 停用、Grant 撤销、Scope 指纹替换与集合边界、范围收窄轮换、ProviderBinding 即时撤权、Token/日志脱敏、Bearer-only 中间件、Profile 装配和无长期凭证回退。

专项命令：

```bash
./mvnw -pl crewscope-server -am \
  -Dtest=TaskTokenScopeFingerprintTest,TaskTokenSecurityConfigurationTest,SecurityConfigurationTest,DurableTaskTokenServiceTest,DurableTaskTokenAuthenticatorTest,NimbusTaskTokenCodecTest,TaskTokenWebFilterTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

全仓验证命令：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

验证结果：7 个 Maven 模块全部构建成功，共运行 897 个测试，失败 0、错误 0、跳过 0。

相关文档：

- [M3-D06 Task Token 与 CredentialGrant](M3-D06-Task-Token与CredentialGrant.md)；
- [M3-I03 Lease Heartbeat、释放与过期恢复](M3-I03-Lease-Heartbeat释放与过期恢复.md)；
- [M3 耐久 Task Runtime 执行清单](../plans/M3-耐久Task-Runtime.md)。
