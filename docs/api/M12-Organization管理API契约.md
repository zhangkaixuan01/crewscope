# M12 Organization 管理 API 契约

> 状态：冻结（A01 组织设置 / ORG_* 角色 / 治理三工作流）<br>
> 版本：V1<br>
> 日期：2026-10-13<br>
> 适用范围：`/api/v1/organizations/{organizationId}` 根下的组织信息与设置读写、ORG_* 角色授权、受控导出、账号删除、审计保留（ADR-035、S01 §3.4/§3.6、主计划 M12-A01）

## 0. 本轮边界与偏差声明

本契约冻结 A01 的组织管理 REST 面（C1 设置/角色 + C2 治理工作流）。以下不在本轮内：

- **组织生命周期 API**（创建/删除组织、name/status 写）——ADR-035 托管增量；`organization` 表零改动，GET 只读；
- **前端 org:\* 渲染**——归 F01；A01 只交付 session 投影字段（§3.4）；
- **预占实检查（openReservations）**——A02 交付；形状冻结为 `null`；
- **导出物理打包（blob 内容）/产物自动清理**——见偏差 3；
- **Team 授权链**——零改动（§6 正交性靠「零引用」保证）。

### 偏差声明（相对 S01 冻结与主计划）

1. **删除的终态是禁用而非物理删行**：V31 凭证史守卫（guard_login_identity_v31 等）禁止 DELETE login_identity/local_credential/user_account，且 login_identity 守卫是 XOR 语义——**状态翻转与 last_authenticated_at 推进互斥**，禁用不得触碰认证时间。因此 TOMBSTONE_IDENTITY 步骤=四身份面（login_identity / account_organization_binding / principal / user_account）原地 DISABLED + user_account security_version 递增（踢全部会话），行保留作凭证史。principal/user_account 的物理行删除属后续增量。
2. **TEAM_MEMBER 是软删**（DELETE_DERIVED_ROWS 对成员行同样落 DISABLED），复用 M9b 成员生命周期语义。
3. **ARTIFACT_REFERENCE 族只导 descriptor**（id/sha256/size/classification/坐标），不含 blob 内容；物理打包为声明后的后续增量。产物自动清理同（COMPLETED 后不自动清，授权拉取+audit，清理属将来 org 级增量）。
4. **备份声明是常量**，随 job 冻结（§4.4）；恢复备份后重跑 process 以对账收敛（job 幂等可重入）。
5. **JSONB manifest 规范化**：manifest 落库为 JSONB（COMPLETED 冻结时写入），响应时按对象回放；**对账权威是 item 的 checksum（hex sha256 of NDJSON 字节流）**，manifest 数字仅导航用——JSONB 键序不保证字节稳定。
6. **向量链关闭时族不消失**：includeVectors=true 且链 disabled→create 照常（族计数 0），process 将该项落 SKIPPED/`VECTOR_STORE_DISABLED` 入 manifest 供对账。部署未 opt-in 向量链（`crewscope.knowledge.vector.enabled` 缺省 false）时无向量 bean，应用照常启动。
7. **SECURITY 审计豁免已下沉库层**：V69 以治理触发器替换 V27 append-only 触发器——audit_event 仍拒一切 UPDATE（防篡改），DELETE 成为保留清扫的受控面、`event_category='SECURITY'` 行豁免一切窗口。契约例外不靠应用约定。

## 1. 端点总览（17 操作，OpenAPI 289→300）

| # | 方法 | 路径 | 权限（capability） | 幂等 |
|---|---|---|---|---|
| 1 | GET | `/api/v1/organizations/{orgId}` | 本组织 ACTIVE principal | — |
| 2 | GET | `…/settings` | SETTINGS_READ（AUDITOR+） | — |
| 3 | PUT | `…/settings` | SETTINGS_WRITE（ADMIN+） | Idem-Key+If-Match |
| 4 | GET | `…/role-grants` | AUDIT_READ_ORG（AUDITOR+） | — |
| 5 | POST | `…/role-grants` | ROLE_ASSIGN；授 OWNER 需 ROLE_ASSIGN_OWNER | Idem-Key |
| 6 | POST | `…/role-grants/{grantId}/revoke` | ROLE_ASSIGN；撤 OWNER 需 ROLE_REVOKE_OWNER | Idem-Key+If-Match |
| 7 | GET | `…/deletion-precheck?accountId=` | GOVERNANCE_DELETION（OWNER） | — |
| 8 | POST | `…/account-deletions` | GOVERNANCE_DELETION | Idem-Key |
| 9 | POST | `…/account-deletions/{jobId}/confirm` | GOVERNANCE_DELETION | If-Match（job version） |
| 10 | POST | `…/account-deletions/{jobId}/process` | GOVERNANCE_DELETION | job 状态机 |
| 11 | POST | `…/account-deletions/{jobId}/cancel` | GOVERNANCE_DELETION | job 状态机 |
| 12 | GET | `…/account-deletions/{jobId}` | AUDIT_READ_ORG | — |
| 13 | POST | `…/data-exports` | GOVERNANCE_EXPORT（ADMIN+） | Idem-Key |
| 14 | POST | `…/data-exports/{jobId}/process` | GOVERNANCE_EXPORT | job 状态机（可重入） |
| 15 | POST | `…/data-exports/{jobId}/cancel` | GOVERNANCE_EXPORT | job 状态机 |
| 16 | GET | `…/data-exports/{jobId}` | AUDIT_READ_ORG | — |
| 17 | GET | `…/data-exports/{jobId}/artifacts` | GOVERNANCE_EXPORT | 每次拉取直写审计 |

全部端点 authenticated、应用层授权（§6）；process/get/cancel/confirm 不收 Idempotency-Key——job 自身状态机即幂等（V45 先例）。全端点 noStore。

## 2. 命令

### 2.1 PUT settings（首写建行）

```jsonc
PUT …/settings
Idempotency-Key: <必填>    If-Match: "<version>"    // 首写 "0"
{ "auditEventRetentionDays": 90 }                    // null/缺省=永久
```

- 202 + CommandReceipt（首执行）；同 Idempotency-Key 重放回原 receipt + `Idempotency-Replayed: true`；GET 回读 `{auditEventRetentionDays, version}`（无行时 `{null, 0}`）；
- `auditEventRetentionDays` 下限 30（防「秒删审计」），<30→422；
- 设置行首写才建（notification_preference 先例），organization 表零改动。

### 2.2 POST role-grants / revoke

```jsonc
POST …/role-grants
Idempotency-Key: <必填>
{ "principalId": "<uuid>", "role": "ORG_OWNER|ORG_ADMIN|ORG_AUDITOR", "reason": "…" }
// 202 + CommandReceipt（重放回原 receipt）

POST …/role-grants/{grantId}/revoke
Idempotency-Key: <必填>    If-Match: "<grant version>"
{ "reason": "…" }
// 202 + CommandReceipt（重放回原 receipt）
```

- grant 形状经 GET 回读：`{id, organizationId, principalId, role, status, grantedByPrincipalId, grantedAt, revokedAt, revokedByPrincipalId, reason, version}`；
- 目标 principal 必须属于本组织（422）；OWNER 仅 OWNER 可授/撤（ADMIN 触 OWNER→403）；
- **最后一张 ACTIVE OWNER 不可撤**→409 `last_owner_protection`（应用事务级保护，复用 LAST_OWNER_PROTECTION 域错误码）；
- REVOKED 留史（partial unique 只保 ACTIVE 唯一），列表回 active+revoked 全量。

### 2.3 POST data-exports

```jsonc
POST …/data-exports
Idempotency-Key: <必填>
{ "includeVectors": false }
// 202 + ExportJobResponse{status: "PENDING", items: [22 族预览], manifest: null, …}
```

- create 即冻结 22 非向量族（includeVectors=true 时 24 族）的 per-family item 预览（family×rowCount，keyset 起点）；
- 同 Idempotency-Key 重放→202 + 原 receipt + `Idempotency-Replayed: true`。

### 2.4 POST account-deletions

```jsonc
POST …/account-deletions
Idempotency-Key: <必填>
{ "accountId": "<uuid>", "reason": "<20..500 字>" }   // confirmationToken=目标 username 回显
// 202 + DeletionJobResponse{status: "PENDING_CONFIRMATION", items: [13 步], …}
```

- 先决硬校验：在途任务执行==0 且未转移 ACTIVE 责任==0，否则 422（引导先跑 handover）；
- `reservationCheck` 冻结先决快照（`openReservations: null` A02 预留）；`backupRetentionStatement` 冻结常量声明（§4.4）。

### 2.5 confirm / process / cancel（两工作流共用形态）

```jsonc
POST …/{jobId}/confirm     // 仅删除：{ "confirmationToken": "<目标 username>" } + If-Match "<job version>"
// 200 CONFIRMED；token 不匹配→422 token_mismatch
POST …/{jobId}/process     // 200 + job+items；删除未确认→422
POST …/{jobId}/cancel      // 200 CANCELLED；已 COMPLETED→409
```

- process 逐 item 独立短事务，中断续跑首个 PENDING（V45 重入语义）；删除 RUNNING 中 cancel 仅当尚有 PENDING（已 DONE 永不回滚）；
- **每步结算即直写审计**（ORG_DELETION_STEP_EXECUTED，含 CONFLICT/DENIED）。

## 3. 查询

### 3.1 GET organization（只读投影）

`{id, name, status, version}`——本组织任意 ACTIVE principal 可读；组织写面不存在（边界）。

### 3.2 GET deletion-precheck

`{accountId, inFlightTaskExecutions, activeResponsibilities, openReservations: null}`——软预览（不建 job）；硬校验在 create。

### 3.3 GET job（对账面=协调清单本体）

- 删除：DeletionJobResponse.items = 13 步 `{stepKey, targetRef, expectedCount, state, resultCount, errorCode, processedAt}`；
- 导出：ExportJobResponse.items = 族级 `{resourceFamily, rowCount, state, checksum, errorCode}`，COMPLETED 后 `manifest` 冻结（§4.1）。

### 3.4 GET artifacts（导出产物拉取）

`200 application/vnd.crewscope.governance-export+json` + `Content-Disposition: attachment; filename="export-<jobId>.json"`；body=`{manifest, artifacts:[{itemId, artifactSequence, byteSize, checksum, rows:[…]}]}`。未 COMPLETED→409。**每次拉取直写审计**（ORG_EXPORT_DOWNLOADED，DENIED 同样落）。

### 3.5 session 组织投影（AuthenticationController 增量）

登录 session 追加 `organization: {organizationId, name, role}`（无 ORG 角色时缺省）+ `permissions` 并入 org:\* 词汇：OWNER/ADMIN→`org:audit-read, org:govern, org:manage-settings`；AUDITOR→`org:audit-read`；无角色→[]。只加不删不改序（F01 消费）。

## 4. 治理契约

### 4.1 导出 manifest（COMPLETED 冻结入 JSONB）

```jsonc
{ "generatedAt": "…",
  "families": [ { "family": "TEAM", "itemCount": 1, "rowCount": 42,
                   "checksum": "<hex sha256>", "skipped": [] } ],
  "vectors": { "encoding": "base64(little-endian float32) + dimension + model_key + content_hash" } }
```

- **对账验收=GET job 即得全资源族×计数×校验和×异常项**；checksum=该族 NDJSON 字节流（`row + "\n"` 逐行）的 sha256；
- 异常项入 `skipped` 数组（如 `VECTOR_STORE_DISABLED`），SKIPPED≠失败——数据面事实照实入 manifest。

### 4.2 资源族清单（v1 冻结，24 族）

非向量 22：ORGANIZATION、TEAM、TEAM_MEMBER、TEAM_ROLE、TEAM_MEMBER_ROLE、WORKSPACE、WORK_PROJECT、WORK_ITEM、WORK_ITEM_RELATION、TASK、TASK_EXECUTION、RESPONSIBILITY_ASSIGNMENT、KNOWLEDGE_ENTRY、KNOWLEDGE_ENTRY_VERSION、REPOSITORY_INDEX_GENERATION、ARTIFACT_REFERENCE、TASK_CREDENTIAL_GRANT、CREDENTIAL_SECRET、LOCAL_CREDENTIAL、USER_ACCOUNT、ACCOUNT_ORGANIZATION_BINDING、AUDIT_EVENT。向量 2：KNOWLEDGE_ENTRY_EMBEDDING、REPOSITORY_CHUNK_EMBEDDING。

**安全边界=列白名单**：CREDENTIAL_SECRET/LOCAL_CREDENTIAL/USER_ACCOUNT/TASK_CREDENTIAL_GRANT 只投影引用与坐标（绝不密文/密码哈希/token/snapshot 哈希）；向量=base64 LE float32 位级无损（norm 不导，cosine 内含，导入侧重算）。

### 4.3 删除步骤序（13 项，创建时 pin expected_count）

1. REVOKE_TASK_CREDENTIAL_GRANTS → 2. REVOKE_SUBJECT_CREDENTIALS → 3. TOMBSTONE_ACCOUNT_ARTIFACTS（descriptor created_by∈目标→tombstone；物理清走既有 purgeTombstoned+retentionUntil）→ 4-11. DELETE_DERIVED_ROWS×8 表（FK 序：TEAM_MEMBER_ROLE/NOTIFICATION_PREFERENCE/INBOX_DISPOSITION/CONVERSATION_PARTICIPANT/COMMAND_RESULT/TEAM_INVITATION/ORGANIZATION_ROLE_GRANT/TEAM_MEMBER，TEAM_MEMBER 殿后）→ 12. TOMBSTONE_IDENTITY（偏差 1：四面 DISABLED+security_version+1）→ 13. RECORD_BACKUP_RETENTION（终项固化声明）。

结算语义：DONE（含幂等 no-op）/CONFLICT（版本冲突）/DENIED（守卫拒绝）——恢复备份重跑以对账收敛。

### 4.4 备份声明（常量，随 job 冻结）

> Row deletion is not full copy erasure. retain-backups.sh keeps daily snapshots for 7 days and weekly snapshots for 4 weeks; rows deleted before a snapshot may survive in backups for up to about 4 weeks. Restoring a backup from inside that window re-introduces deleted rows; after a restore, re-run process to reconcile (the job is resumable — already deleted steps converge as no-op DONE or CONFLICT). After the window the backups age out naturally.

### 4.5 保留（retention）

配置=PUT settings（null=永久默认；≥30 下限）；执行=RetentionSweepScheduler 1h fixedDelay+防重叠；runOnce=读设置→批 500 循环删 `occurred_at < cutoff` 的 audit_event（**排除 event_category='SECURITY'，豁免已由 V69 库层触发器强制**）→直写 ORG_RETENTION_SWEEP_COMPLETED(计数) 自证。

## 5. 错误码

| 码 | 场景 |
|---|---|
| 400 | 缺 Idempotency-Key / body 不合法 |
| 403 | capability 不足（`details={action}`）；ADMIN 触 OWNER 面 |
| 404 | 组织/job/grant 不存在或不在本组织 |
| 409 | optimistic_lock_conflict（If-Match 不匹配）；job 已终态再 cancel/process；**last_owner_protection** |
| 422 | settings <30；grant 目标非本组织 principal；删除先决非空；confirm token_mismatch；未确认即 process |
| 428 | 缺 If-Match |
| 500→修复语义 | 幂等重放必须命中 COMPLETED 回执——命令事务内完成回执（receipts.complete）是每个新命令的硬契约 |

## 6. 正交与越权（双向硬隔离，验收项）

- **ORG 路径零引用 TeamPermission/TeamAccessContext**——OrganizationAuthorization 只读 organization_role_grant；ORG_ADMIN 对 team_member_role 无行→任何 Team 写自然 403；
- **Team 路径零改动**——requirePermission/DefaultAuditAuthorization 不读 org 表；TEAM_OWNER 无 ORG 角色→PUT settings 403、session 无 org:\* 词汇；
- **检查顺序冻结**：Team 作用域资源先 Team 边界、不足时不以 Org 角色补写权限；Org 角色只在 `/organizations/{org}` 根面生效；审计读是唯一叠加点（组织级审计走 AUDIT_READ_ORG 新端点）；
- session 投影同样正交：ORG grant 无 team 成员→仅 org:\*；team 角色无 ORG grant→零 org:\*。

## 7. 审计落点（命令走事件投影、访问/治理动作走直写）

| 面 | 类型 | category | 落点 |
|---|---|---|---|
| 设置变更 | ORGANIZATION_SETTING_CHANGED | IDENTITY | 事件投影 |
| 角色授/撤 | ORGANIZATION_ROLE_GRANTED/REVOKED | IDENTITY | 事件投影 |
| 导出发起/完成/取消 | ORGANIZATION_DATA_EXPORT_CREATED/COMPLETED/CANCELLED | SECURITY | 事件投影 |
| 删除四生命周期 | ORGANIZATION_ACCOUNT_DELETION_REQUESTED/CONFIRMED/COMPLETED/CANCELLED | SECURITY | 事件投影 |
| 导出拉取 | ORG_EXPORT_DOWNLOADED（含 DENIED） | SECURITY | GovernanceAuditRecorder 直写 |
| 删除逐步 | ORG_DELETION_STEP_EXECUTED | SECURITY | 直写（step_key/target_ref/result_count/error_code） |
| 保留清扫 | ORG_RETENTION_SWEEP_COMPLETED | SECURITY | 直写（同事务自证） |

导出三层闭环=发起（事件）+完成（事件）+拉取（直写）。

## 8. 事件清单（CrewScopeAuditEventTypes.registerOrganizationGovernanceEvents）

8 条域事件（§7 上半表）+4 条直写字面量（下半表）；全部 SchemaVersion.V1、retention=EXTENDED。
