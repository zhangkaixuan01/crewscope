# M9b-A07 团队成员生命周期 API 契约

ADR-038 的 HTTP 边界：成员生命周期命令（停用/恢复/移除/离开/角色授予与撤销/Owner 转让）、责任交接 job、邀请受权坐标与本人通知偏好。全部命令遵循 ADR-007 协议：`Idempotency-Key`（必填，缺省 400 `invalid_request`）+ `If-Match`（强 ETag `"N"`，缺省 428 `precondition_required`），成功 202 共享命令回执（`commandId`/`domainEventId`/`committedVersion`/`correlationId`）；幂等重放返回原结果并附 `Idempotency-Replayed: true`。所有响应 `Cache-Control: no-store`，不暴露凭证、Endpoint 或外部 Provider 原始错误。

## authorizationVersion 语义

`team_member.authorization_version BIGINT NOT NULL DEFAULT 1`（V43，CHECK ≥ 1）。它与乐观锁 `version` 语义分离：

- **version**：成员行的乐观锁，任何写命令 +1，`If-Match` 比对它。
- **authorizationVersion**：授权代数。状态转换（suspend/activate/remove/leave/reinvite）、角色授予/撤销、Owner 转移双方、邀请 ensureRoleGrant 都会在同一 Team 锁事务内 +1；`recordActivity` 不递增。执行通道的快照携带它作为附加维度（缺失不拒绝历史读，副作用边界仍以实时成员事实为准）。

成员列表响应字段：`id`、`userPrincipalId`、`displayName`、`status`、`joinMethod`、`joinedAt`、`roles`（当前有效 role key 列表）、`grants`（`[{id, roleKey}]`，撤销角色需要 grantId）、`authorizationVersion`、`version`。

## 生命周期命令

基路径 `/api/v1/organizations/{organizationId}/teams/{teamId}`，全部 POST + 202 回执（不内嵌成员现状——客户端重读成员列表，与其他 Team 命令一致）：

| 命令 | 路径 | 请求体 | 权限 |
|---|---|---|---|
| 停用 | `/members/{memberId}/suspend` | — | MEMBER_MANAGE，非本人 |
| 恢复 | `/members/{memberId}/activate` | — | MEMBER_MANAGE，非本人 |
| 移除 | `/members/{memberId}/remove` | — | MEMBER_MANAGE，非本人 |
| 离开 | `/members/me/leave` | — | 本人（目标由身份推导，不信任客户端） |
| 授予角色 | `/members/{memberId}/roles` | `{roleKey}` | ROLE_MANAGE；`TEAM_OWNER` 不可手工授予（422 `validation_failed`） |
| 撤销角色 | `/members/{memberId}/roles/{grantId}/revoke` | — | ROLE_MANAGE |
| 转让 Owner | `/transfer-ownership` | `{targetMemberId}` | 操作者持有效 TEAM_OWNER + ROLE_MANAGE；目标须 ACTIVE |

`If-Match` 比对目标成员的 `version`（Owner 转让比对目标成员 version；旧 Owner 的撤权在同一事务内完成，双方各 `markAuthorizationChanged`）。

### 错误矩阵

| 场景 | 状态码 | code |
|---|---|---|
| 缺 Idempotency-Key | 400 | `invalid_request` |
| 缺 If-Match | 428 | `precondition_required` |
| 成员 version 已变化 | 409 | `optimistic_lock_conflict`（`currentVersion` 回填） |
| 最后一个有效 Owner 被停用/移除/退出 | 409 | `last_owner_protection` |
| 权限不足 / 非成员 / 操作自己（suspend/remove） | 403 | `policy_denied` |
| 手工授予 TEAM_OWNER / 角色不存在 | 422 | `validation_failed` |

`last_owner_protection` 判定：`countEffectiveOwners`（`team_member(status='ACTIVE') × team_member_role(status='ACTIVE' 且有效期覆盖) × team_role(key='TEAM_OWNER')`，排除目标）在 Team PESSIMISTIC_WRITE 锁内执行；两个 Owner 并发退出由锁串行化，后进者见 count=0 → 409。suspend/remove 的自我目标是更早的 `policy_denied`（自我退出走 leave），不进入该判定。

### 域语义

- **恢复不复活**：`activate` 重建基础访问（默认 MEMBER），旧的已撤销/已过期角色授权一律不复活，需要重新授予；suspend/remove/leave 同步清空角色授权。
- **leave** 与 suspend/remove 一样触发全通道撤权；移除后可通过新邀请重新加入（`membershipDisposition=ACTIVATED/CREATED`）。
- 命令骨架复用 Team 命令协议：reserve → `requireLockedTeam` → 锁内重读操作者权限 → 目标 If-Match → 最后 Owner 校验 → 域变更 → 事件 + Outbox + 回执；锁序恒 Team → 成员/角色按 ID。

## 责任交接（responsibility handover）

V45 落两张表：`responsibility_handover_job`（`ux_handover_job_command UNIQUE(command_id)` 保证"重复原命令返回原 job"）与 `responsibility_handover_item`（`uk_handover_item_assignment UNIQUE(job_id, assignment_id)` 保证 DONE 不重发）。角色枚举 `ResponsibilityRole = OWNER | EXECUTOR | REVIEWER`（REVIEWER 由服务端按目标类型分流 gate/advisory，API 层不区分）。

| 操作 | 方法与路径 | 成功 | 说明 |
|---|---|---|---|
| 预览 | `GET .../members/{memberId}/responsibilities?role=` | 200 `[{assignmentId, workItemId, role, version}]` | 该成员该角色的 ACTIVE assignment，供确认页 |
| 创建 | `POST .../teams/{teamId}/responsibility-handovers`，体 `{sourceMemberId, targetPrincipalId, role}` | 202 `{command, job}` | MEMBER_MANAGE；每项预检 RESPONSIBILITY_MANAGE，失败整体 403；目标须 `canParticipate`（源成员不要求 ACTIVE——移除后交接正是语义） |
| 处理 | `POST .../{jobId}/process` | 200 job | 逐项独立短事务，可重入；重启只取 PENDING |
| 读取 | `GET .../{jobId}` | 200 job | MEMBER_MANAGE |
| 取消 | `POST .../{jobId}/cancel` | 200 job | 仅未终态 job；已 DONE 项不回滚；终态 job 取消 → 422 |

job 形状（创建与 process/get/cancel 一致）：

```json
{
  "jobId": "…", "status": "PENDING|RUNNING|COMPLETED|CANCELLED", "role": "OWNER",
  "sourceMemberId": "…", "targetPrincipalId": "…",
  "sourceAuthorizationVersion": 3, "version": 0,
  "items": [{ "itemId": "…", "assignmentId": "…", "workItemId": "…",
              "state": "PENDING|DONE|CONFLICT|DENIED",
              "resultAssignmentId": null, "errorCode": null }]
}
```

- **无计数派生字段**；完成数由 items 派生。
- item 结果分类：DONE（成功，`resultAssignmentId` 为新 assignment）；CONFLICT（版本/乐观锁/责任冲突，保持原状）；DENIED（权限或目标失效，`errorCode` 说明）。其他异常回滚留 PENDING 可重试。
- 每项复用既有责任命令原语（OWNER 单命令 `replaceOwner`；EXECUTOR/REVIEWER 先 release 源再 assign 目标，同一 item 事务），内嵌命令幂等键 `handover:{jobId}:{itemId}`（两命令项再加 `:release`/`:assign` 后缀），逐项复验操作者权限——process 时被降权 → 该项 DENIED 而非整体失败。
- 数量边界：该角色无 ACTIVE assignment → 422；超过 100 项 → 422（`responsibilityHandover.items`）。

## 邀请受权坐标（acceptance coordinates）

`POST /api/v1/invitations/accept` 的 202 响应升级为嵌套形状（加法兼容，旧客户端忽略新字段）：

```json
{
  "command": { "commandId": "…", "domainEventId": "…", "committedVersion": 1, "correlationId": "…" },
  "acceptance": {
    "teamId": "…", "memberId": "…", "invitationId": "…",
    "membershipDisposition": "CREATED|ACTIVATED|REUSED",
    "roleGrantCreated": true
  }
}
```

- 首次接受：`acceptInTransaction` 落 `CommandResult`（V46 `type=TEAM_MEMBER`，`resourceId=memberId`，`teamId` 必填、`projectId` 必空），响应携带完整坐标。
- **幂等重放**：按 org+key+actor 回查存储结果，只回填 `teamId`/`memberId`，`invitationId`/`membershipDisposition`/`roleGrantCreated` 为 null——重放不重新接受、不重新发角色。
- 无存储结果的重放：`acceptance` 为 null。
- 本人可查 `GET /api/v1/organizations/{org}/command-results`（幂等键请求头）确认已提交的接受，不重复发送。

前端语义：InvitePage/RegisterPage 用 `acceptance.teamId`（或注册响应的 `teamId`）直达 `/conversation?team={teamId}`，删除"差集→同名→第一个"三级猜测；会话尚未含该 Team 时进入"接受已提交、会话待同步"状态，重新同步只刷新会话与命令结果，不重新 accept。

## 本人通知偏好

`GET/PUT /api/v1/organizations/{organizationId}/teams/{teamId}/members/me/notification-preference`

授权即"本人 ACTIVE 成员资格"，从身份推导 memberId，不信任客户端，不隐含任何管理权（无 providerManage 要求；他人/非成员/移除成员 → 403 `policy_denied`）：

- **GET** → 200 + 强 ETag `"N"` + `{memberId, enabled, enabledItemTypes, mutedUntil, version}`。
- **PUT**（体 `{enabled, enabledItemTypes[], mutedUntil}`，未知属性 400；`enabledItemTypes` 非空且 ≤16、`mutedUntil` ≤100 字符）→ 需要 `If-Match` + `Idempotency-Key`，成功 202 + 新 ETag + 新偏好；version 冲突 → 409 `optimistic_lock_conflict`。
- 管理接口（`.../notification-administration/...`）不变，仍需 providerManage。

## 七通道撤权检查点

成员停用/移除/退出与角色撤销后，各通道的失效边界（全部直读权威事实、无缓存放行、失败关闭）：

| 通道 | 检查点 | 上界 |
|---|---|---|
| HTTP 读 | 每请求直读 `canParticipate` | 即时 |
| 会话投影 | session 计算只含 ACTIVE 成员的 Team | 即时 |
| Team SSE | 空闲探测帧复用 `authorizeFrame` 的 `requireTeamAccess`（`TeamActivityRealtimeProperties.idleProbeInterval`，默认 5s） | 5s |
| Conversation SSE | 每批读取后、发射前 `requireReadable` 复验 | 批内截断 |
| PersonalAgent | 消息/TaskIntent 提交、瞬态事件落库、缓存回放三边界 `MemberAuthorizationGuard` | 即时 |
| Task 执行 | Worker heartbeat（15s 窗口）/事件提交前/token 认证点 `TaskTokenCurrentAuthorization.requireCurrentMembership`；沙箱工具链本身校验 lease/fencing/身份，成员事实由其持的 token 在认证边界复验；快照携带 `memberAuthorizationVersion` 附加维度（旧快照缺维度可读历史，下一副作用边界拒绝） | 即时（heartbeat ≤15s） |
| GitHub dispatch | `CurrentActionAuthorityFactsResolver` 按成员事实解析，失效 → unavailable（claim 与适配器前重验） | 即时 |
| Notification 投递 | 发送前复验成员 `status='ACTIVE'` | 发送前 |
