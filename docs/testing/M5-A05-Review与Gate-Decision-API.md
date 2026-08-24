# M5-A05 Review 与 Gate Decision API

## 交付范围

M5-A05 将 M5-D06/D07 与 M5-I06/I07 的 Review 领域、AgentScope Reviewer 和 PostgreSQL 持久化能力接入成员 Web API：

- 从当前 Task attempt 的最终 `DiffArtifact`、精确 `TestEvidence`、其引用的 `CommandEvidence` 和 Reviewer `PolicySnapshot` Schema v2 创建 `ReviewSubject + ContextPackage + ReviewRequest`；
- 提供 ReviewRequest 列表、详情和失效历史读取；
- 启动或恢复独立 Reviewer Specialist Session，使用 `reviewer@1`、空 Toolkit 和 AgentScope 原生 Structured Output 生成 Finding；
- 事务内执行 Evidence Resolver、Finding Fingerprint 去重、Observation 追加、Request 完成、事件、Task Timeline、Outbox 和投影重建；
- 由当前 Active TeamMember 提交 `COMMENTED / APPROVED / CHANGES_REQUESTED / REJECTED` Gate Decision；
- `CHANGES_REQUESTED` 原子追加连续 `ReviewModificationRound`；
- 旧 Request 失效后，使用新 Reviewer PolicySnapshot 和当前 Artifact 权威事实创建连续 re-review Revision。

## HTTP 契约

路由根：

```text
/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/reviews
```

| 方法 | 路由 | 契约 |
|---|---|---|
| `POST` | `/reviews` | 创建初始 ReviewRequest；要求 `Idempotency-Key` |
| `GET` | `/reviews` | 返回当前 attempt 的安全 Review 投影 |
| `GET` | `/reviews/{reviewRequestId}` | 返回 Context、Diff/Test 坐标、Finding、Decision 与修改轮次；携带强 ETag |
| `POST` | `/reviews/{reviewRequestId}/execute` | 启动或恢复 Reviewer；要求 `Idempotency-Key + If-Match` |
| `POST` | `/reviews/{reviewRequestId}/decisions` | 提交成员 Gate Decision；要求 `Idempotency-Key + If-Match` |
| `POST` | `/reviews/{reviewRequestId}/modifications` | 提交 `CHANGES_REQUESTED` 并追加修改轮次 |
| `POST` | `/reviews/{reviewRequestId}/re-review` | 从指定失效前驱创建下一 Review Revision |

公开 DTO 不返回 Patch 正文、Context Hunk 正文、System Prompt、模型原始输出、Tool 原始结果、Credential、Endpoint、内部 Reasoning 或 Provider 错误。Patch 内容继续使用 M4 受限 Artifact 下载边界。

## 权威与授权边界

创建前闭合以下关系：

```text
Organization / Team / WorkProject / WorkItem / Task
  = current TaskExecution attempt
  = final DiffArtifact
  = latest TestEvidence for exact Diff generation + manifest hash
  = every referenced CommandEvidence ID + sequence + hash
  = reviewer PolicySnapshot task/execution + Schema v2
  = active reviewer Agent Principal/Profile + reviewer@1
  = active advisory REVIEWER responsibility
  = active Reviewer Agent owner TeamMember
  = exactly one active subject Owner TeamMember
```

`ReviewerRelationship` 只由 Reviewer Agent Owner 与 subject Owner 推导。两者相同得到 `SELF_REVIEW`，Finding 仍为 `ADVISORY`，不能形成 Gate Approval。

Reviewer 启动、恢复、Receipt 回放和结果提交都重新验证当前 Team Membership、Agent/Profile、Reviewer Assignment、Owner Membership、Specialist Session、PolicySnapshot Hash、Request ETag 和 ContextPackage。Task 已切换 attempt、Diff 自动失效或配置坐标漂移时失败关闭。

Gate Receipt 回放同样重新执行当前 Active TeamMember、Active USER Reviewer Assignment 和 `ReviewerEligibilityPolicy`。Agent Principal 无法进入 Decision API，Owner/Executor 冲突和职责分离规则继续由领域策略裁决。

## 幂等与恢复

- 创建、重审、Reviewer 执行和 Gate Decision 的请求 Hash 均包含路由、Actor、目标 ID、ETag 和命令正文；同键异参冲突；
- Reviewer 首次执行把 `OPEN -> IN_PROGRESS`、`REVIEW_REQUEST_STARTED` 与 CommandReceipt 提交后再调用模型，数据库事务不跨越模型网络调用；
- 模型调用失败时 Request 保持 `IN_PROGRESS`，调用者使用新幂等键和当前 ETag 恢复；已完成 Receipt 只返回回放，不重复调用模型；
- 并发恢复最多一个结果事务完成 Request；Finding 唯一约束和 Observation 行锁消除输出重放噪声；
- Finding、Observation、Request `COMPLETED`、安全事件、Task Event、Outbox 与投影重建共享 REQUIRED 事务，任一步失败整体回滚；
- Diff 发布后的 M5-I07 Consumer 把旧 Request 标记为 `INVALIDATED/DIFF_CHANGED`。失效详情仍可审计读取，任何新 Finding、Decision 或执行均被拒绝。

## 自动化验证

专项测试：

```bash
./mvnw -q -pl crewscope-application,crewscope-server -am \
  -Dtest=ReviewerExecutionApplicationServiceM5A05Test,ReviewGateApplicationServiceM5A05Test,ReviewControllerM5A05Test \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Reviewer 与持久化回归：

```bash
./mvnw -q -pl crewscope-agentscope,crewscope-infrastructure -am \
  -Dtest=ReviewerSpecialistRuntimeM5I06Test,JdbcReviewPersistenceM5I07IntegrationTest,ReviewRepositoryTenantBoundaryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

覆盖结果：

- Reviewer Receipt 回放前持续复验 Agent、Assignment 与 Owner Membership；
- 失效 ETag 在 AgentScope 调用前拒绝；
- Gate Receipt 回放前持续复验成员 Assignment 与 Eligibility；
- 创建 Body 只接受 Reviewer PolicySnapshot 坐标；
- Reviewer 执行强 ETag、幂等回放响应和修改请求路由通过；
- Review 列表 DTO 不包含 Patch、Prompt、Credential 或内部运行事实；
- I06 AgentScope 原生 Structured Output 和 I07 PostgreSQL Scope/Hash/Projection 回归通过。
