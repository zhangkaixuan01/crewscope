# M6-D04 Lark 外部身份与成员映射契约

> 任务：`M6-D04`<br>
> 日期：2026-08-25<br>
> 状态：完成<br>
> 关联决策：[ADR-004](../adr/ADR-004-CredentialStore与动作凭证.md)、[ADR-006](../adr/ADR-006-ProviderBinding解析与授权.md)、[ADR-022](../adr/ADR-022-Inbox与固定模板通知授权协议.md)

## 1. 交付目标

M6-D04 建立 CrewScope TeamMember 与 Lark 外部成员的精确映射契约：

- 类型化 `tenant_key`、`open_id`、`union_id` 和 Provider Version；
- 经过验证且可版本化的 `LarkExternalTenant`；
- 最长 15 分钟的 `LarkMemberVerificationProof`；
- 管理员确认、可撤销、保留历史证据的 `LarkMemberMapping`；
- 发送前重新授权的 `CollaborationRecipient`；
- 原子双唯一 Repository Port 与安全的应用服务编排。

本任务只交付领域与应用契约。Lark HTTP Client、Tenant Token Cache、Notification Worker、数据库迁移和 HTTP API 分别由 M6-I03 至 M6-I06、M6-D08/M6-D09 和 M6-A04 实现。

## 2. 精确身份与能力边界

Lark 成员验证 Port 只提供两种固定调用：

```text
verifyTenant(current authorization)
verifyMember(current authorization, exact LarkOpenId)
```

生产契约不接受显示名、邮箱、手机号、通用查询字符串或模糊匹配。`union_id` 只作为验证证据，不作为自动绑定键。

Connection 能力拆分为：

| 能力 | 用途 |
|---|---|
| `collaboration.member.lookup-exact` | 验证 Tenant 并按精确 `open_id` 查询成员 |
| `collaboration.notification.send-fixed-template` | 使用已批准的固定模板发送通知 |

验证和 Recipient 解析各自要求精确能力。授权交集缺少必要能力时在 Provider HTTP 调用前失败关闭。

## 3. Tenant 与验证 Proof

`LarkExternalTenant` 的 ID 由 `Organization + Connection` 确定性派生，并保存：

```text
Organization
Connection ID / Version
Connection Grant ID / Version
Tenant Key
Provider Version
Status / Verified At / Aggregate Version
```

OpenAPI 观测到的 `tenant_key` 必须与 Connection 配置完全相等。相同授权和 Provider Version 的重复验证幂等返回当前 Tenant；Connection、Grant 或 Provider Version 变化时推进 Tenant Version，旧 Proof 和 Mapping 自动失效。

ExternalTenant 只允许 `VERIFIED -> INVALIDATED`，`INVALIDATED` 是保留旧授权证据的不可逆终态，不得通过 `refresh` 复活。撤销后重新接入使用新 Connection 和新的确定性 ExternalTenant ID，避免新旧授权证据共用一条历史。

`LarkMemberVerificationProof` 绑定完整当前坐标：

```text
Organization / Team
ProviderBinding ID / Version
Connection ID / Version
ConnectionGrant ID / Version
ExternalTenant ID / Version / Tenant Key
Open ID / Union ID / Provider Version
Verification Source / Verified At / Valid Until
```

Proof 使用 `LARK_OPEN_API_EXACT_OPEN_ID` 来源，有效窗口大于 0 且不超过 15 分钟。请求与返回的 `open_id` 必须完全相等。迟到确认、时钟回退、跨 Team、跨 Organization 和任一授权版本漂移都会拒绝确认。

## 4. Mapping 唯一性与生命周期

ACTIVE Mapping 同时受两个唯一键保护：

```text
Internal Key = Organization + Team + TeamMember
External Key = Organization + TenantKey + OpenId
```

外部 Key 包含 Organization，不同 Organization 的外部身份注册表相互隔离。应用服务在确认前查询双索引，Repository 使用数据库部分唯一约束作为最终并发保护。

Mapping 状态为：

```text
ACTIVE -> REVOKED
ACTIVE -> INVALIDATED
```

终结原因使用安全枚举 `ADMIN_REVOKED`、`MEMBER_LEFT`、`AUTHORIZATION_DRIFT`、`IDENTITY_REPLACED`。撤销和失效使用强 Expected Version，保留原始验证身份与审计信息。

相同成员、身份和当前验证的重复确认返回原 Mapping。身份不变且授权或 Provider Version 已漂移时，Repository 在同一原子操作中终结旧 ACTIVE Mapping 并插入重新确认的 Mapping。

## 5. Collaboration Recipient

`CollaborationRecipient` 只在以下事实同时成立时生成：

1. TeamMember 当前为 ACTIVE 并且可参与；
2. Mapping 当前为 ACTIVE；
3. Organization、Team、Member 与 Mapping 完全相等；
4. ExternalTenant 当前为 VERIFIED；
5. ProviderBinding、Connection、Grant、Tenant 的 ID 和 Version 全部匹配；
6. 当前授权包含固定模板通知能力。

Recipient 保存 Mapping 和全部授权版本坐标，供 M6-D03 的 Authorization Snapshot 精确绑定。Connection 或 Grant 撤销、成员离队、Mapping 撤销、Tenant 失效和能力变化均会在返回 `open_id` 前失败关闭。

## 6. 敏感字段边界

`LarkTenantKey`、`LarkOpenId`、`LarkUnionId` 的 `toString()` 不输出原值。Tenant 和 Member Observation 也使用脱敏字符串。领域对象不保存显示名、邮箱、手机号、Token、Authorization Header、Endpoint 或原始 Provider Payload。

## 7. 验证

专项测试：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-domain,crewscope-application -am \
  -Dtest=LarkCollaborationDomainM6D04Test,LarkMemberMappingM6D04Test \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：12 个测试通过，0 Failure，0 Error，0 Skip。

覆盖：

- 外部 ID 格式、精确查询和输出脱敏；
- Tenant Key 与 `open_id` 不匹配时零 Proof、零 Mapping；
- 最长 15 分钟 Proof 和全授权坐标漂移；
- 单成员、单外部身份与 Organization 隔离；
- 当前映射幂等、Provider Version 刷新后原子重新确认与双索引一致性；
- Mapping 撤销、强 Version、历史身份不可变；
- Connection/Grant 撤销后在外部查询和 Recipient 披露前失败关闭。
