# M3-D06 Task Token 与 CredentialGrant

> 日期：2026-08-14<br>
> 状态：已完成<br>
> 适用范围：Task Token Claims、TaskCredentialGrant、JTI Hash、ExecutionLease 闭合、Tool/Provider 最小授权与 Application Persistence Port

## 1. 目标

M3-D06 建立 TaskExecution Runtime 的短期、可撤销授权边界。领取成功的 Worker 只能在当前 ExecutionLease、PolicySnapshot、SafetyEnforcementOverlay、Execution Principal、Tool 和 Provider 显式资源交集内使用 Task Token。M3-D06 建立 Runtime/Tool/资源访问边界，Provider 写动作在 PlannedAction 阶段接入。

## 2. 闭合的授权范围

`TaskTokenGrantScope` 同时进入持久化 `TaskCredentialGrant` 和受信签名边界 `TaskTokenClaims`，包含：

```text
Organization / Team / Workspace / WorkProject
Task / TaskExecution / attempt
ExecutionLease / RuntimeEnvironment / Runtime / Worker
ClaimTokenHash / FencingToken
ExecutionPrincipalSnapshot
PolicySnapshot ID + Hash
SafetyEnforcementOverlay ID + Version + Hash
allowedTools
ProviderBinding Version + ConnectionGrant ID/Version + Capability + explicit Resources
```

`TaskCredentialIssuance` 逐字段验证 Grant 与 Claims 共用 Grant ID、JTI Hash、Scope、issuedAt 和 expiresAt。任一字段遗漏或替换都无法形成合法签发结果。

## 3. JTI 与时间边界

- JTI 接受 43–128 位 Base64URL 安全值；
- 持久化只保存 SHA-256 JTI Hash；
- 明文 JTI 只通过一次性 `TaskCredentialIssuance` 进入受信签名边界；
- Repository 使用全局 JTI Hash 唯一约束拒绝重复 Token；
- Token 生存期为 5 秒至 15 分钟，且 `token.expiresAt <= lease.expiresAt`；
- 权威时钟满足 `authoritativeNow >= expiresAt` 时 Token 立即过期。

JTI、JTI Hash、Claim Token Hash、Tool 键和 Provider 资源不进入 Task Token 相关对象的字符串输出。

## 4. Tool 与 Provider 最小授权

签发 Tool 集合必须是当前 PolicySnapshot 的子集，且 SafetyEnforcementOverlay 没有禁用该 Tool。

Provider 授权满足：

- Binding 为 ACTIVE 且 ID 存在于当前 PolicySnapshot；
- Binding 属于同一 Organization、Team 和 Workspace；
- WorkProject Binding 匹配 Task 的 WorkProject；
- Capability 与资源集合是 Binding `effectiveAccess` 的子集；
- 资源使用显式集合，不接受 `allResources()`；
- 同一 Grant 对一个 ProviderBinding 只保存一份授权。

每次使用携带一个 Tool 和可选的 `ProviderBinding + Capability + Resource`，所有维度同时命中才能记录使用。

## 5. 生命周期

```text
ACTIVE --use----> ACTIVE   useCount + 1 / lastUsedAt / version + 1
ACTIVE --revoke-> REVOKED  termination / version + 1
ACTIVE --expire-> EXPIRED  termination / version + 1
```

REVOKED 和 EXPIRED 为互斥且不可变的终态。使用、撤销和过期都携带 Grant 期望 Version。持久化重建验证时间线、useCount/lastUsedAt、Version、终态事实和 AuditMetadata 形状。

## 6. Application Port

`TaskCredentialGrantRepository` 定义：

- `create`：创建 Grant，依赖全局 JTI Hash 唯一约束和同一 TaskExecution 唯一 ACTIVE Grant 约束；
- `recordUse`：使用 Grant Version 条件记录授权使用；
- `terminate`：使用 Grant Version 条件提交 REVOKED 或 EXPIRED；
- `rotate`：原子终止当前 Grant 并创建范围收紧的替换 Grant；
- `findByJtiHash/findActiveByTaskExecution/findExpired`：提供安全验证与过期扫描查询。

M3-D08 建立数据库唯一约束，M3-D09 实现带 Organization/Environment 谓词和乐观锁的 Adapter。

## 7. 验证

M3-D06 新增 14 个专项测试：

- `TaskCredentialGrantTest`：11 个；
- `TaskTokenSecurityValueTest`：3 个。

覆盖 Claims 闭合、JTI Hash 稳定唯一键、Tool/Provider 最小范围、Policy/Safety 收紧、Token/Lease 时间边界、使用计数、撤销、过期、错误 Lease ID/TaskExecution/attempt/Runtime/Worker/Claim Token Hash/Fencing Token/Binding/Capability/Resource、乐观锁、终态互斥、非法重建形状和安全字段脱敏。

专项 Reactor 验证：

```text
crewscope-domain       307 tests passed
crewscope-application  178 tests passed
```

最终全仓回归：

```text
7 Maven modules successful
828 tests passed, 0 failures, 0 errors, 0 skipped
112 Markdown files passed link validation
git diff --check passed
```

相关决策与前置契约：

- [ADR-004：CredentialStore 与动作级凭证](../adr/ADR-004-CredentialStore与动作凭证.md)；
- [M3-D05 ExecutionLease 与所有权协议](M3-D05-ExecutionLease与所有权协议.md)；
- [M3 耐久 Task Runtime 执行清单](../plans/M3-耐久Task-Runtime.md)。
