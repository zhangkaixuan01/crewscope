# M5-D06 Reviewer ContextPackage 与 ReviewRequest 领域契约

> 任务：`M5-D06`<br>
> 日期：2026-08-23<br>
> 状态：通过<br>
> 关联决策：[ADR-017](../adr/ADR-017-Reviewer证据与人工Gate边界.md)

## 1. 交付范围

M5-D06 在 `crewscope-domain` 与 `crewscope-application` 交付：

- `ReviewSubject`：固定 WorkItem Scope、Task、TaskExecution attempt、CodingTarget 和最终 Diff 坐标；
- `ContextPackageV1`：保存权威引用、有界 Changed Hunk、完整 AcceptanceResult 与安全 CommandEvidence 摘要；
- `ReviewerExecutionReference`：固定 Reviewer AgentProfile、Principal、Owner Relationship、Template、Configuration 和 PolicySnapshot v2；
- `ReviewRequest`：提供 `OPEN/IN_PROGRESS/COMPLETED/INVALIDATED` 状态机、连续 Revision、乐观 Version 和不可逆失效；
- `ReviewSubjectRepository`、`ContextPackageRepository` 与 `ReviewRequestRepository` Port；所有读取方法显式携带 `OrganizationId`，不提供跨租户裸 ID 查询。

Finding、Evidence Resolver、Fingerprint、Advisory 效力和成员 Gate Decision 属于 M5-D07；数据库表与 Adapter 属于 M5-D11/I07。

## 2. 领域规则

1. ContextPackage 全部事实必须共享 Organization、Team、Workspace、Project、Task、TaskExecution、attempt 和 CodingTarget。
2. Review Repository 的所有查询以 `OrganizationId` 为第一限定条件；对象内 Scope 复验与数据库租户谓词同时生效。
3. TestEvidence 的 DiffGeneration 与 ManifestHash 必须精确匹配被审 DiffArtifact。
4. Context 只保存规范路径、有界行范围、PatchHash、可选有界 Patch、CommandEvidence 安全摘要和完整 AcceptanceResult；不保存原始 argv、环境、日志、凭证或 Context 外 Artifact。
5. 默认预算固定为 128 个 Hunk、512 KiB Patch、64 个 CommandEvidence 和 100 条 AcceptanceResult。
6. ContextHash 使用稳定顺序闭合 Subject、Diff、Hunk、Test/Acceptance、Template、Configuration、Policy 和 ReviewerRelationship。
7. 相同权威事实不能创建 ContextPackage 后继；相同 ContextPackage 不能创建第二个活动 ReviewRequest。
8. 后继 ReviewRequest 只能在旧请求进入 `INVALIDATED` 后创建，Revision 必须连续并保留 predecessor。
9. Subject、Diff、TestEvidence、Reviewer Configuration、Policy 或仅 Hunk/Context 变化均产生稳定失效原因。
10. Reviewer start、resume 前复验和 output complete 都必须传入当前 ContextPackage；陈旧请求失败关闭。

## 3. 自动化验证

新增 9 个自动化场景：

- `ContextPackageTest`：4 个场景覆盖最小上下文、规范 Hash、复原防篡改、预算、版本、跨 Scope/Task/Execution/attempt、Diff/Test lineage 和无变化后继拒绝；
- `ReviewRequestTest`：4 个场景覆盖状态机、乐观 Version、重复活动请求、连续后继、Diff/Test/Configuration/Policy/Context 失效和陈旧 start/current 拒绝；
- `ReviewRepositoryTenantBoundaryTest`：1 个应用边界场景，通过反射锁定 D06/D07 Review Repository 所有读取方法的首个参数必须为 `OrganizationId`。

验证结果：

| 验证 | 结果 |
|---|---|
| M5-D06 新增测试 | `9 / 9` |
| Domain 模块回归 | `500 / 500` |
| Application 模块回归 | `331 / 331` |
| 全仓 Maven 回归 | `1651 / 1651` |
| 文档链接与差异格式门禁 | 通过 |
