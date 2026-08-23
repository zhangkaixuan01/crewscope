# M5-D11：V21 Review、Action 与 GitHub 迁移契约

> 状态：已完成<br>
> 日期：2026-08-23<br>
> 对应迁移：`V21__review_action_and_github.sql`

## 目标

V21 将 M5-D06 至 M5-D09 的领域对象落到 PostgreSQL，并建立 Review 证据、成员 Gate、GitHub Connection、ActionBundle、Confirmation、Dispatch、Receipt 和外部结果对账之间的持久化边界。

迁移保存可授权、可查询、可冲突检测的标量坐标。复杂快照使用 JSONB 保存，JSONB 不替代 Scope、版本、Hash、外部唯一键、状态和审计列。

## 物理模型

### Review

- `review_subject`：固定精确 DiffArtifact、CodingTarget、Commit、Manifest、Patch 和 SubjectHash。
- `review_context_package`：固定 Subject、Diff、TestEvidence、Reviewer Profile/Template/Configuration 和 PolicySnapshot。
- `review_context_hunk`：保存有序、受大小限制的 Diff Hunk 引用。
- `review_context_command_evidence`：保存 Reviewer 可见的安全 CommandEvidence 摘要。
- `review_context_acceptance_result`：保存完整 AcceptanceResult 与 Evidence 坐标。
- `review_request`：保存 ReviewRequest 当前状态和乐观版本。
- `review_request_state`：为每个 ReviewRequest 版本追加不可变状态事实，使 Finding 和 Decision 能固定精确版本，同时不阻塞合法状态迁移。
- `review_finding`、`review_finding_evidence`、`review_finding_observation`：保存首条 Finding、严格证据和重复候选 Observation。
- `review_decision`：保存成员 Gate 决策；终结 Gate 对每个 ReviewRequest 唯一。
- `review_modification_round`：保存由 `CHANGES_REQUESTED` 触发的连续修改轮次。

### GitHub

- `github_connection_profile`：保存 Connection 版本、身份类型、外部账号安全标识、权限摘要和 Repository Allowlist Hash。
- `github_repository_catalog_entry`：以 GitHub Repository ID 为稳定身份，保存名称、默认分支、可见性、Archived/Fork、Pull/Push/PR 权限和缓存状态。
- `github_rate_limit_snapshot`：只追加保存 Connection-scoped RateLimit 观察事实。

身份矩阵由数据库约束固定：

| Connection Owner | Authentication | Execution Identity |
|---|---|---|
| `TEAM` | `APP_INSTALLATION` | `TEAM_SERVICE_ACCOUNT` |
| `USER` | `OAUTH_USER` | `DELEGATED_USER` |

凭证、Token、Secret、Authorization Header、Provider 原始 Payload 和内部 Endpoint 不进入 V21 表。

### Action

- `action_bundle`：固定 ReviewDecision、ReviewSubject/Context/Diff、责任、Provider、Connection/Grant、Policy、Safety Overlay、Repository 和 CodingTarget 全部权威坐标。
- `planned_action`：保存类型化 Push Branch 或 Create Draft PR 参数、风险、有效期和 ActionDigest。
- `planned_action_dependency`：保存同一 Bundle 内严格依赖。
- `action_confirmation`、`confirmation_action`：固定成员确认的 BundleDigest 和有序 ActionDigest。
- `action_dispatch`、`action_dispatch_dependency`：保存调度状态、Lease、Fencing、重试/对账次数和依赖。
- `action_receipt`：每个 PlannedAction 只允许一个不可改写的逻辑终态。
- `external_observation`：按 Connection 和 ObservationKey 追加 Provider 事实。
- `external_result`：按 Provider Version 或 ProviderUpdatedAt 单调合并当前外部对象状态。

## 约束

### Scope 与权威引用

V21 为 V6、V7、V10、V14 和 V20 的权威表补充精确历史唯一键。跨聚合外键携带 Organization、Team、Workspace、Project、Task 和 TaskExecution 中适用的完整 Scope，并使用 `ON DELETE RESTRICT`。

ActionBundle 的授权坐标分别引用：

- APPROVED ReviewDecision 与精确 ReviewRequest 状态版本；
- ReviewSubject、ContextPackage 和 DiffArtifact Hash；
- OWNER ResponsibilityAssignment ID/Version/Principal；
- ProviderBinding、Definition、Implementation、Connection 和 Grant 版本；
- PolicySnapshot、Safety Overlay、RepositoryBinding 和 CodingTarget 版本与 Hash。

### 只追加

数据库触发器拒绝对以下事实执行 UPDATE 或 DELETE：

- ReviewSubject、ContextPackage 及其证据子项；
- ReviewRequestState、Finding、FindingEvidence、FindingObservation、Decision、ModificationRound；
- ActionBundle、PlannedAction、Dependency、ConfirmationAction；
- ActionReceipt、ExternalObservation；
- GitHub RateLimitSnapshot。

ReviewRequest、Confirmation、ActionDispatch 和 ExternalResult 使用受控状态迁移与强乐观版本。ActionDispatch 同时拒绝 Fencing Token 回退和终态改写。

### 唯一性与部分成功

- `(ReviewRequest, Fingerprint)` 唯一，重复 Finding 保存 Observation。
- 每个 ReviewRequest 只有一个终结 Gate。
- 每个 PlannedAction 只有一个 Dispatch 和一个 Receipt。
- 幂等键在 Organization 内唯一。
- External ID 和 BusinessKey 在 Connection 与 ObjectType 内唯一。
- Webhook/Observation 去重键包含 Connection。
- Push Receipt 成功后，Draft PR Dispatch 可以继续保持 READY、UNKNOWN 或失败；后继只依赖 Push 的唯一成功 Receipt，不重新执行 Push。

## 验证

专项测试：`V21ReviewActionGithubMigrationIntegrationTest`

7 个场景覆盖：

1. 空库 V1 至 V21，25 张 V21 表、可变根/不可变事实审计形状和秘密字段排除；
2. V20 至 V21 单迁移升级、Flyway History 和 Validate；
3. 非默认 `search_path` 仍只写入 `crewscope`；
4. 复合 Scope/Hash/版本约束、冲突键和队列/历史查询索引；
5. Review → Gate → ActionBundle → Push/PR → Receipt → Observation/ExternalResult 代表图，以及 Push 成功、PR 待执行的部分成功；
6. Decision、Receipt、Observation 只追加，Decision Revision、ObservationKey、跨 Scope 和旧 Fencing Token 失败关闭；
7. Connection-scoped External ID/BusinessKey 与 ExternalResult 单调版本、不可删除终态。

执行命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure \
  -Dtest=V21ReviewActionGithubMigrationIntegrationTest test
```

结果：`7 / 7` 通过。

回归门禁：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am clean test

./mvnw --batch-mode --no-transfer-progress test
```

- Infrastructure Reactor：Domain `500` + Application `331` + Infrastructure `484` = `1315` 个测试，全部通过。
- 全仓 Reactor：Domain `500` + Application `331` + AgentScope Adapter `118` + Integration `1` + Infrastructure `484` + Server `217` = `1651` 个测试，全部通过。
- 文档链接：`209` 个 Markdown 文件全部通过链接检查。
