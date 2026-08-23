# M5-D07 ReviewFinding 与成员 Gate 领域契约

> 任务：`M5-D07`<br>
> 日期：2026-08-23<br>
> 状态：通过<br>
> 关联决策：[ADR-017](../adr/ADR-017-Reviewer证据与人工Gate边界.md)

## 1. 交付范围

M5-D07 在 `crewscope-domain` 与 `crewscope-application` 交付：

- `ReviewFindingCandidate`、`FindingLocation`与 `FindingEvidence`：固定 ReviewFindingListV1 的严格字段、文本预算和 1–8 个证据坐标；
- `ReviewFinding`：在 `IN_PROGRESS` 期间解析当前 Diff Hunk、TestEvidence 和 AcceptanceResult，固定 `ADVISORY` 效力和服务端 ReviewerRelationship；
- `ReviewFindingFingerprint`：使用 SubjectHash、Category、规范路径、行号范围和 Unicode 规范 Claim 计算 SHA-256；
- `ReviewFindingObservation`：保留首次 Finding 事实，后续同 Fingerprint 候选以只追加 Observation 记录；
- `ReviewDecision`：固定 `GATE` 模式、当前 ReviewRequest ETag、USER Principal、Active TeamMember、Active Reviewer Assignment 和资格决策；
- `ReviewModificationRound`：将连续 ReviewRequest Revision 上的 `CHANGES_REQUESTED` 结论记录为连续修改轮次；
- Finding、Observation、Decision、ModificationRound 和 ReviewRequest 失效安全 DomainEvent；
- Finding、Observation、Decision 和 ModificationRound Repository Port；所有读取方法显式携带 `OrganizationId`，不接受跨租户裸 ID 查询。

V21 表结构已由 M5-D11 交付，PostgreSQL Adapter 属于 M5-I07；ContextPackageBuilder、Structured Output Decoder、Evidence Resolver 和 Reviewer Specialist 已由 M5-I06 交付；命令事务、Outbox 和 API 属于 M5-A05。

## 2. Finding 契约

1. Agent Finding 只在精确 `IN_PROGRESS` ReviewRequest 上产生，创建前复验 ETag 和当前 ContextPackage。
2. 调用 Principal 必须是 ContextPackage 固定的 Active `SPECIALIST_AGENT`。
3. 每条 Evidence 必须匹配当前 DiffArtifact ID/Hash、ManifestHash、TestEvidence ID/Hash 和 Acceptance Index。
4. 路径必须存在于当前 Diff，行号范围必须完全落在 ContextPackage 提供的 Hunk 内。
5. ReviewerMode 固定为 `ADVISORY`，ReviewerRelationship 直接读取服务端 ContextPackage；`SELF_REVIEW` 保持 Advisory 效力。
6. Fingerprint 对 Claim 执行 Unicode NFKC、去首尾空白、连续空白折叠和稳定小写；Title、Severity 和 SuggestedFix 不参与身份。
7. 相同 ReviewRequest 和 Fingerprint 保留首条 Finding，后续候选保留 CandidateHash 和 ObservationNumber。

## 3. 成员 Gate 契约

1. ReviewDecision 只接受已完成、未失效的当前 ReviewRequest，并固定完整 ReviewRequest Reference 和 ETag。
2. 调用者必须是当前 WorkItem Team 的 Active USER Principal 和 Active TeamMember，同时持有 Active USER Reviewer Assignment。
3. Gate 复用 M1 `ReviewerEligibilityPolicy`：默认要求 Owner/Executor 职责分离，单人团队降级只读取显式 PolicyPack 证据。
4. Agent Principal、Service Principal、模板配置和模型输出无法创建 ReviewDecision。
5. `COMMENTED` 允许连续追加；`APPROVED/CHANGES_REQUESTED/REJECTED` 为当前 ReviewRequest 的终结 Gate 结论，后续不可替换。
6. `CHANGES_REQUESTED` 生成修改轮次，后续轮次必须对应同 Task 下连续的 ReviewRequest Revision。

## 4. 自动化验证

新增 9 个自动化场景：

- `ReviewFindingTest`：4 个场景覆盖严格 Evidence Resolver、伪造 Hash/未知 Acceptance/越界 Hunk、Agent 身份、Fingerprint 规范化、重复 Observation、SELF_REVIEW 和陈旧请求拒绝；
- `ReviewDecisionTest`：5 个场景覆盖 APPROVED/CHANGES_REQUESTED/REJECTED、COMMENTED 链、终结不可替换、Agent 拒绝、Assignment、职责分离、ETag、失效请求和连续修改轮次。

D06 的共享 `ReviewRepositoryTenantBoundaryTest` 同时锁定 Finding、Observation、Decision 与 ModificationRound Repository 的显式租户谓词，不计入本任务 9 个领域场景。

| 验证 | 结果 |
|---|---|
| M5-D07 新增测试 | `9 / 9` |
| Domain 模块回归 | `500 / 500` |
| Application 模块回归 | `331 / 331` |
| AgentScope 模块回归 | `118 / 118` |
| Integration 模块回归 | `1 / 1` |
| Infrastructure 模块回归 | `484 / 484` |
| Server 模块回归 | `217 / 217` |
| 全仓 Maven 回归 | `1651 / 1651` |
| 文档链接与差异格式门禁 | 通过 |
