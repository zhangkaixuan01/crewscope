# M7-D08 TeamInvitation 迁移

## 1. 目标

M7-D08 将 M7-D05 的 `TeamInvitation` 领域契约落入 PostgreSQL V32，并保持以下边界：

- 数据库只保存 32-byte Digest 的 64 位小写十六进制编码，不保存明文邀请 Token；
- 邀请完整绑定 Organization、Team、邀请人、可选目标邮箱、内置角色和有效期；
- 接受事实同时绑定 Account 与稳定 TeamMember；
- `PENDING` 只允许一次关闭为 `ACCEPTED / REVOKED / EXPIRED`；
- 历史 DomainEvent 与 AuditEvent 保持只追加，V32 不回写事件载荷。

## 2. V32 数据结构

`V32__team_invitation.sql` 新增：

- `crewscope.team_invitation`：邀请事实与受限 Token Digest；
- `crewscope.team_invitation_metadata`：排除 Digest 的管理、Preview 和审计投影；
- `uk_team_invitation_token_digest`：全局 Digest 唯一裁决；
- `ix_team_invitation_team_status_v32`：Team 管理列表；
- `ix_team_invitation_pending_expiry_v32`：有界待过期扫描；
- `ix_team_invitation_pending_target_v32`：定向邀请查询。

底表与元数据视图均撤销 PUBLIC 权限。通用 Reader 只授权元数据视图，后续 M7-I06 的 Invitation Adapter 才读取 Digest-bearing 底表。

## 3. Scope 与接受链

数据库通过复合外键证明：

- `(organization_id, team_id)` 必须对应同一 Team；
- `(organization_id, invited_by_principal_id)` 必须对应同一 Organization Principal；
- `(organization_id, team_id, accepted_member_id)` 必须对应同一 TeamMember；
- `accepted_by_account_id` 必须对应现有 UserAccount。

签发触发器要求当前 Team 为 ACTIVE，邀请人为 ACTIVE Organization Scope USER Principal。接受触发器进一步连接 UserAccount、AccountOrganizationBinding、Principal、Team 与 TeamMember，要求接受 Account ACTIVE、定向邮箱匹配、ACTIVE Binding 精确解析到 ACTIVE Organization USER Principal，且目标 Team 和 Membership 均为 ACTIVE。触发器对这五类状态行取 `FOR NO KEY UPDATE` 锁，使接受与并发停用、悬置和归档互斥。跨 Organization、跨 Team、Service Principal 和 Team Scope USER 都失败关闭。

## 4. 一次性生命周期

新行只能以 `PENDING / version=0` 建立。坐标、邀请人、目标、角色、Digest、有效期和创建时间不可修改；更新必须单步推进版本并把 PENDING 关闭为一个终态：

- `ACCEPTED / version=1`：保存 Account、TeamMember 与早于有效期的 `resolved_at`；
- `REVOKED / version=1`：不保存接受身份，且必须早于有效期；
- `EXPIRED / version=1`：不保存接受身份，且必须位于有效期或之后。

三个终态不可再次修改，所有邀请都禁止物理删除。并发 Accept 使用 `status=PENDING AND version=0` 条件更新，PostgreSQL 行锁和强版本保证只有一个终态提交。

## 5. 验证

专项测试：

```bash
./mvnw -pl crewscope-infrastructure -am \
  -Dtest=V32TeamInvitationMigrationIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：`8 / 8` 通过，覆盖：

- 非默认 `search_path` 的空库 V1→V32 与重复迁移收敛；
- V31→V32 增量升级，既有 Account、Binding、Team 和只追加 DomainEvent 不变；
- Digest 格式与唯一性、非 Owner 角色、ACTIVE USER 邀请人；
- 跨 Organization/Team 复合外键和错误 Principal 类型；
- 双线程并发 Accept 只有一个条件更新成功；
- 接受时复核 ACTIVE Account、定向邮箱、ACTIVE Team 和完整授权链；
- 接受事务持有的状态行锁阻止并发 Binding 停用；
- 精确有效期边界、版本单步推进、终态闭合和禁止删除；
- 待过期扫描索引、元数据视图、底表权限隔离和无明文 Token 列；
- 保留关系冲突时 V32 整笔回滚，Flyway 版本保持 V31。

通用 Flyway 回归继续由 `FlywayMigrationIntegrationTest` 覆盖空库到 latest、V1 到 latest 和非默认 `search_path`。

## 6. 结论

M7-D08 完成。V31/V32 已形成开放用户体系的数据库基线；M7-I01 已实现账号身份持久化，M7-I02 已实现 Redis 浏览器 Session 边界，下一任务为 M7-I03。
