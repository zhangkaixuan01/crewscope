# M5-I06 Reviewer Specialist 与 Evidence Resolver

## 1. 交付范围

M5-I06 已将 M5-D06/D07 的 Review 领域契约接入 AgentScope Java 2.0.0：

- `ContextPackageBuilder` 从精确 M4 `DiffArtifact`、`TestEvidence`、`CommandEvidence` 和 Restricted Patch Artifact 构建版本化 `ContextPackageV1`；
- Patch Reader 复验访问主体、Scope、完整对象大小、内容 Hash 和重新计算的 SHA-256，统一 Diff Parser 只接受 Manifest 内的文本 Hunk；
- `ReviewerSpecialistRuntime` 只运行 `reviewer@1`，使用独立 Specialist Session、零 Tool 和 AgentScope 原生 Structured Output；
- `ReviewFindingListV1` 固定全字段 `required`、所有对象 `additionalProperties=false`、最多 50 条 Finding 和每条 1～8 个 Evidence；
- `ReviewFindingBatchRecorder` 调用领域 Evidence Resolver，服务端生成 Finding ID、Fingerprint、Relationship 和 `ADVISORY` Effect；
- 重复 Fingerprint 只保留第一条 Finding，后续候选和恢复重放追加 `ReviewFindingObservation`；
- `ReviewRepairRequestSummary` 最多包含 20 条有界 Finding 摘要，供后续 Coding 修复轮次使用。

Repository 事务、并发唯一约束、Outbox、Audit 和持久化恢复由 M5-I07 接续；ReviewRequest 命令事务与 API 由 M5-A05 接续。

## 2. 上下文与模型边界

Context Builder 在模型调用前复验：

- ReviewSubject 与 DiffArtifact 的 Scope、Task、TaskExecution、attempt 和最终 Diff 引用；
- TestEvidence 的 CodingTarget、DiffGeneration、ManifestHash 和 CommandEvidence 引用顺序；
- 每条 CommandEvidence 的 Scope、TaskExecution、attempt、CodingTarget、ID、Sequence 和 EvidenceHash；
- ArtifactAccessContext 的 Organization、Principal、Team 和 Workspace 授权；
- Restricted Patch 的完整大小、已提交 Hash、重新计算 Hash、Canonical UTF-8、Manifest Path 和 Hunk 行数。

Reviewer Prompt 仅包含 Hash 闭合的 ContextPackage JSON，并将其中全部字符串和 Patch 明确标记为不可信证据。Reviewer 关闭 Filesystem、Shell、Subagent、Memory、Dynamic Skill、Workspace Context、Compaction、`@path` 展开和 Tools Config，不读取完整仓库、会话、环境、命令、凭证或 Context 外 Artifact。超过 128 个 Hunk、512 KiB Patch、64 条 CommandEvidence 或 100 条 AcceptanceResult 时失败关闭，调用方需要拆分交付或转人工评审。

运行请求再次固定 Agent Principal、AgentProfile ID/Version、`reviewer@1` Template Version/Hash、Configuration Revision/Hash、空 Tool Surface、精确 Structured Output Schema Hash、当前 `IN_PROGRESS` ReviewRequest 和 ETag。Provider/超时错误经过安全分类；Schema 解码错误、证据越权和领域状态冲突保留平台错误语义。

## 3. Finding 权威与恢复

模型只能提交 Severity、Category、Title、Claim、SuggestedFix 和 Evidence 坐标，不能提交 Gate、Effect、Relationship、Fingerprint 或状态变化。严格 Decoder 拒绝未知字段、缺失字段、非法枚举、无证据和超预算输出。

领域 Evidence Resolver 要求每个坐标命中当前 DiffArtifact ID/Hash、ManifestHash、TestEvidence ID/Hash、Acceptance Index 和真实 Hunk 行号范围。平台基于 SubjectHash、Category、规范路径、行号范围和规范化 Claim 计算 Fingerprint。`SELF_REVIEW` 仍只形成 Advisory Finding，成员 Gate Decision 保持独立。

单次输出内和恢复重放时，相同 Fingerprint 不创建第二条 Finding，只追加连续 Observation。M5-I07 将使用数据库唯一约束和事务锁关闭多 Worker 并发窗口。

## 4. 自动化验证

- `ContextPackageBuilderM5I06Test`：4 个场景覆盖真实 M4 坐标、Artifact/访问/Command 漂移、非法 Patch、文件段路径重置和 `---/+++` 内容行；
- `ReviewFindingBatchRecorderM5I06Test`：3 个场景覆盖单批去重、恢复重放、SELF_REVIEW Advisory、修复摘要预算和旧 Evidence 拒绝；
- `ReviewerStructuredOutputM5I06Test`：2 个场景覆盖完整/空输出以及 Gate、Effect、Fingerprint、未知字段和无证据拒绝；
- `ReviewerSpecialistRuntimeM5I06Test`：2 个场景覆盖 AgentScope 合成 Structured Output、零 Tool Context、Prompt 不可信标记、权威 Recorder 调用和 `reviewer@1` 版本锁；
- `ReviewerSpecialistM5S03IntegrationTest`：5 个固定语料场景继续覆盖正确、缺陷、无关、重复 Finding 和 Gate 攻击。

M5-I06 专项共 16 个测试，全部通过。

完整 Maven Reactor 回归共 1713 个测试，全部通过：

- Domain：500；
- Application：350；
- AgentScope：141；
- Integration：1；
- Infrastructure：495；
- Server：226。

全量回归结果为 `BUILD SUCCESS`，耗时 8 分 43 秒。
