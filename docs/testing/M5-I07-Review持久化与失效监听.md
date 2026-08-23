# M5-I07 Review 持久化与失效监听

## 1. 交付结果

M5-I07 已将 M5-D06/D07/D11 与 M5-I06 的 Review 权威事实接入 PostgreSQL、查询投影和耐久事件链路：

- V24 补齐 `ReviewDecision` 的冲突职责、PolicyPack 与完整 Override Reason，旧迁移保持不可变；
- `ReviewSubject`、`ContextPackage`、`ReviewRequest`、`ReviewFinding`、`ReviewFindingObservation`、`ReviewDecision` 和 `ReviewModificationRound` 均可按 Organization Scope 无损恢复；
- `review_request_projection` 提供 Request、Execution Attempt 和 Task History 查询，可按单个 Request 或整个 Organization 从事实表确定性重建；
- Review 事件在一个 REQUIRED 事务中写入 DomainEvent、TaskEvent 和 Outbox，并复用 AuditEvent 投影；
- `FINAL_DIFF_ARTIFACT_PUBLISHED` 会将当前旧 ReviewRequest 标记为 `DIFF_CHANGED`，随后发布安全的 `REVIEW_REQUEST_INVALIDATED`；
- `(ReviewRequest, Fingerprint)` 以数据库唯一约束保留首条 Finding，Finding 行锁串行分配连续 Observation Number，ReviewRequest 使用强乐观锁。

M5-I07 不提供 Review 命令 API、Reviewer Gate 编排或 Workbench 页面；这些能力由 M5-A05 与 M5-F06 接续。

## 2. 权威持久化

ContextPackage 使用“关系列权威坐标 + 显式非秘密 JSONB 快照”保存完整上下文。恢复时重新构造领域对象并计算 Context Hash，同时逐项校验 Scope、Task、Execution、Subject、Diff、TestEvidence、Reviewer、Template、Configuration、PolicySnapshot 与数据库标量一致。Hunk、CommandEvidence 和 AcceptanceResult 子投影也按顺序和值复验，任何漂移都会失败关闭。

Review 查询只读取 `review_request_projection`，事实写入后在同一事务重建对应 Request。投影不承担权威，可删除后从 Review 事实表恢复。Organization 全量重建是受控维护路径；在线 Request、Execution 和 Task History 查询均为一次有索引的投影查询，不加载 Finding 明文或 Patch。

## 3. 并发与事务

ReviewRequest 更新比较持久化 Version，竞争失败返回领域统一的 Optimistic Lock Conflict。Finding 使用 `(review_request_id, fingerprint) ON CONFLICT DO NOTHING` 原子选出唯一 Winner；重复候选转为 Observation。Observation 写入前锁定 Finding，再读取最大编号并追加，多个 Worker 并发时仍形成连续序列。

Review 业务事实、Projection、DomainEvent、TaskEvent 与 Outbox 共用 REQUIRED 事务。专项故障注入证明 Outbox 创建失败会同时回滚 Finding、Projection 和全部事件行。Outbox 消费使用既有幂等 Receipt；Diff 失效处理和后继失效事件也在同一消费事务内提交。

## 4. 安全事件

Reviewer 事件只包含标识、Scope、Hash、Severity、Category、Relationship、状态和数量等安全字段，不包含 Claim、SuggestedFix、Patch、Prompt、Token 或 Credential。Diff 自动失效使用 Service Actor；ReviewRequest 数据库审计继续保留原始可信 Principal Provenance。所有 Repository 查询以 `OrganizationId` 为首参数，跨租户 ID 查询返回空结果。

## 5. 自动化验证

验证命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  -Dtest='V24ReviewPersistenceProjectionMigrationIntegrationTest,JdbcReviewPersistenceM5I07IntegrationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

专项覆盖：

1. V21→V24 增量升级、Decision Authority 补齐与 Projection Schema；
2. 七类 Review 事实的 Hash 闭合往返恢复；
3. Context 标量、JSONB、Hunk、Command 和 Acceptance 漂移拒绝；
4. 跨 Organization 查询隔离、Execution 查询与 Task History；
5. ReviewRequest 状态历史和强乐观锁；
6. 并发 Finding 唯一 Winner 与 Observation 连续编号；
7. 单 Request 与 Organization 投影重建；
8. DomainEvent、TaskEvent、Outbox、Audit 的完整发布链；
9. 事件发布失败时业务事实和投影整体回滚；
10. Final Diff 变化自动失效、状态历史和安全后继事件。

## 6. 后续任务

下一任务为 `M5-I08`：实现 GitHub Provider Adapter、Connection Grant、Repository Catalog/Binding Preflight，以及 GitHub App/OAuth 身份与权限校验。
