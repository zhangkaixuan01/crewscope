# M7-D05 TeamInvitation 领域契约

> 任务：`M7-D05`<br>
> 日期：2026-08-28<br>
> 状态：完成<br>
> 前置契约：[M7-D04 AccountOrganizationBinding](M7-D04-AccountOrganizationBinding领域契约.md)

## 1. 交付目标

M7-D05 将一次性 Team 邀请落为领域和应用契约：

- `TeamInvitation` 保存 Team Scope、邀请人、可选规范目标邮箱、目标内置角色、Token Digest、有效期、状态与稳定接受结果；
- `InvitationTokenDigest` 只接受 32-byte Digest，不保存或生成明文 Token；
- `PENDING / version=0` 只能单步进入 `ACCEPTED / REVOKED / EXPIRED / version=1`，终态不可逆；
- 接受结果固定 `UserAccountId + TeamMemberId`，重放不能产生第二个结果；
- `TeamInvitationAcceptanceService` 创建、恢复或原位复用稳定 Membership；
- `TeamInvitationRepository` 冻结 Digest 查找、锁定、Team 列表与乐观版本接口。

## 2. Token 与状态机

`InvitationTokenDigest` 接受 32 bytes 或 64 位小写十六进制编码，字符串表示固定为 `[REDACTED]`，已派生摘要通过常量时间比较。Digest 的持久化值只提供给 Repository Adapter 和索引查询。CSPRNG Token 生成、SHA-256/HMAC 派生与创建成功后的一次性明文返回属于 M7-I06。

状态图：

```text
PENDING -> ACCEPTED
        -> REVOKED
        -> EXPIRED
```

邀请创建时间必须早于 `expiresAt`。接受和撤销要求当前时刻严格早于 `expiresAt`；精确过期边界及之后只能执行 expire。三个终态均保存 `resolvedAt` 并关闭生命周期。由于终态不可再转移，领域创建和持久化重建均要求 PENDING 版本为 0、终态版本为 1，拒绝跳版或状态/版本错配。

Token Digest 或 ID 唯一冲突统一为 `team_invitation_conflict`，错误详情不包含 Digest、目标邮箱、Account、Principal 或数据库约束名。

## 3. 邀请约束

创建邀请要求 Team ACTIVE，邀请人为相同 Organization 内 Organization Scope、ACTIVE、USER Principal。权限检查由后续 A05 使用当前 TeamMember 与 TeamRole 完成。目标角色只能是以下产品内置角色：

```text
TEAM_ADMIN
TEAM_LEAD
MEMBER
AUDITOR
```

`TEAM_OWNER` 继续使用 Team 所有权转移流程。可选目标邮箱使用 D01 `NormalizedEmail`，接受账号必须精确匹配规范值；开放邀请不限制账号邮箱。

## 4. 接受状态交集

接受同时要求：

```text
TeamInvitation PENDING and before expiresAt
AND presented Token Digest matches
AND UserAccount ACTIVE
AND AccountOrganizationBinding ACTIVE and owned by Account
AND Binding resolves to current ACTIVE Organization USER Principal
AND Team ACTIVE and exact invitation Scope
AND TeamMember ACTIVE and exact Team/Principal
AND optional target email matches Account
```

任一条件失败都不产生接受事实。接受成功保存稳定 Account ID 与 TeamMember ID；调用方在同一数据库事务提交 Membership、目标内置角色 Grant 与 Invitation 乐观版本。

Membership 处理：

| 当前 Membership | 结果 |
| --- | --- |
| 不存在 | 创建 `INVITATION` 来源的 ACTIVE Membership |
| `ACTIVE` | 原位复用，不更新 Membership |
| `INVITED` | 邀请人一致时使用原 ID 激活 |
| `LEFT` | 使用原 ID 激活 |
| `REMOVED` | 使用原 ID 重新邀请并激活 |
| `SUSPENDED` | 拒绝，要求管理员单独重新启用 |

邀请 Token 不绕过成员停用。已有成员重复进入接受流程时仍使用同一个 TeamMember ID，避免重复 Membership、Personal Agent 或权限主体。

## 5. 并发

Repository 的 `lockByTokenDigest` 为正式接受事务提供行锁，`update(invitation, expectedVersion)` 提供最终乐观版本裁决。16 路读取同一 PENDING 版本并发提交时只有一个 ACCEPTED 更新成功，其余请求得到版本冲突；重新读取 ACCEPTED 邀请后，状态机拒绝 Token 重放。

目标邮箱、Team Scope、Account Binding、Membership 和目标角色必须在持锁事务内重新验证。数据库外部的 Preview 或缓存不承担接受授权。

## 6. 验证

专项命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-domain,crewscope-application -am \
  -Dtest=TeamInvitationM7D05Test,TeamInvitationAcceptanceM7D05Test \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

24 个测试通过，0 Failure、0 Error、0 Skip：

| 模块 | 测试数 |
| --- | ---: |
| `crewscope-domain` | 15 |
| `crewscope-application` | 9 |
| 合计 | 24 |

覆盖邀请创建、Digest 长度/编码/脱敏/比较、错误邀请人、归档 Team、TEAM_OWNER 拒绝、规范目标邮箱、完整接受结果、Token 重放、错误 Digest、邮箱不匹配、Account/Binding/Principal/Team/Membership 状态与 Scope 冲突、精确过期边界、撤销、终态形状、状态/版本强一致、安全冲突、Membership 创建/复用/恢复、SUSPENDED 防绕过、待接受 Membership 邀请源一致性、16 路并发单终态和 Digest 唯一冲突。

## 7. 后续边界

- M7-D06 定义邀请创建、接受、撤销和失败的安全 DomainEvent/Audit Schema；
- M7-D08 通过 V32 表、Digest 唯一索引、跨 Scope 外键和状态约束落地真实存储；
- M7-I06 实现 CSPRNG Token、Digest 派生、Repository Adapter、行锁和过期清理；
- M7-A01 在带邀请注册事务中复用相同接受计划；
- M7-A05 实现邀请创建、列表、Preview、撤销和已登录 Accept API；
- M7-F07 从 URL Fragment 读取 Token 到进程内存并立即清理地址栏。
