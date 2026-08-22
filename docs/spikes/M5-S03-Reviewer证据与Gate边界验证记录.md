# M5-S03 Reviewer 证据与 Gate 边界验证记录

> 验证日期：2026-08-22<br>
> 验证对象：AgentScope Java `v2.0.0`、CrewScope M4 DiffArtifact/TestEvidence、Reviewer Specialist<br>
> 结论：通过

## 1. 目标

M5-S03 冻结 Reviewer Specialist 的 Prompt、最小 ContextPackage、`ReviewFindingListV1`、证据引用、严重级别、重复 Finding、`SELF_REVIEW` 和成员 Gate 边界。Spike 不新增 M5 生产领域对象、Repository 或数据库迁移。

## 2. AgentScope 源码映射

| 能力 | AgentScope Java 2.0.0 位置 | CrewScope 使用方式 |
|---|---|---|
| 按调用 Structured Output | `ReActAgent#doStructuredCall` | Reviewer 每次调用传入固定 ReviewFinding Schema |
| 非原生模型兼容 | `ReActAgent#doFallbackStructuredCall` | 合成 `generate_response` Tool，Schema 错误可进入修复轮次 |
| Tool 参数校验 | `ReActAgent#createStructuredOutputTool` 与 Tool Executor | 拒绝未知 Gate 字段、缺失证据和错误类型 |
| 结构化结果提取 | `Msg#getStructuredData` | 先读取原始 Map，再由 CrewScope 严格 Decoder 转换 |
| Harness 生命周期 | `HarnessAgent.Builder` | 关闭 Reviewer 不需要的文件、Shell、Subagent、Memory、动态 Skill 和 Tools Config |

AgentScope 负责模型调用、Schema 和修复循环。CrewScope 继续负责 Bean、Evidence Resolver、Scope、责任、Policy 和 Gate 业务校验。

## 3. 冻结契约

### 3.1 最小 ContextPackageV1

测试内 ContextPackage 绑定：

- Task、TaskExecution；
- `CODE_CHANGE SubjectId + SubjectHash`；
- `CodingTargetSnapshotId + Revision + Hash`；
- `reviewer@1 + TemplateHash` 与 `PolicyId + Version + Hash`；
- DiffArtifact ID/FinalHash、Baseline/Delivery Commit、Generation/ManifestHash；
- 规范路径、起止行、PatchHash 和有界 Patch Hunk；
- TestEvidence ID/EvidenceHash、tested Generation/ManifestHash；
- CommandEvidence ID/EvidenceHash 和完整 AcceptanceResult；
- 服务端推导的 Reviewer Agent/Owner/Subject Owner 与 Relationship；
- 规范 `ContextHash`。

固定预算为 128 个文件、512 KiB Patch Hunk、64 个 CommandEvidence 和 100 条 AcceptanceResult。凭证、原始环境、任意原始命令、完整仓库和 Context 外 Artifact 不进入 Prompt。

### 3.2 ReviewFindingListV1

根对象只允许 `schemaVersion` 与 `findings`。Finding 固定：

- Severity：`BLOCKER/HIGH/MEDIUM/LOW`；
- Category：`CORRECTNESS/SECURITY/RELIABILITY/MAINTAINABILITY/TESTING/ACCEPTANCE`；
- Title、Claim、SuggestedFix；
- 1–8 个 Evidence；
- 每个 Evidence 含路径/行号、DiffArtifact ID/Hash、ManifestHash、TestEvidence ID/Hash 和 Acceptance Index。

所有对象全字段 required 且 `additionalProperties=false`。正确变更返回空 Finding List。

### 3.3 去重与效力

Fingerprint：

```text
subjectHash + category + canonicalPath + normalizedRange + normalizedClaim
```

模型不提交 Fingerprint、ReviewerRelationship 或 ReviewEffect。平台从 Owner/责任事实推导 `INDEPENDENT/SELF_REVIEW`，Agent Finding 的 Effect 固定为 `ADVISORY`。

Gate Decision 是独立的 TeamMember 命令。Review Schema 中没有 `APPROVED`、`CHANGES_REQUESTED`、`REJECTED`、Gate Effect 或状态迁移字段。

## 4. 固定样本

| 样本 | Diff | 预期 | 结果 |
|---|---|---|---|
| 正确 | `name == null ? "" : name.strip()` | 空 Finding | 两次一致，通过 |
| 缺陷 | `name.strip()` 且验收失败 | HIGH/CORRECTNESS，引用真实行、Diff、Test、Acceptance | 两次一致，通过 |
| 无关 | 只修改 `docs/changelog.md`，模型声称 `Greeting.java` 有问题 | Context 外路径拒绝 | 两次一致，通过 |

## 5. 负向验证

固定测试覆盖：

- Evidence 为空由严格 Schema 拒绝；
- TestEvidence Hash 不匹配被拒绝；
- Acceptance Index 不存在被拒绝；
- Finding 行号超出提供 Hunk 被拒绝；
- 相同 Claim 仅有大小写、连续空白、标题或建议差异时合并；
- Reviewer Owner 与 Subject Owner 相同只能得到 `SELF_REVIEW + ADVISORY`；
- Agent 首次输出额外 `gateDecision=APPROVED` 时，真实 AgentScope `generate_response` 参数校验拒绝；模型第二轮修复为空 Finding 后完成；
- Agent Actor 无法通过 Gate Boundary，只有 Eligible TeamMember 可以提交。

## 6. 测试证据

测试类：

```text
crewscope-agentscope/src/test/java/io/crewscope/agentscope/
  ReviewerSpecialistM5S03IntegrationTest.java
```

专项命令：

```bash
./mvnw -pl crewscope-agentscope -am \
  -DskipITs \
  -Dtest=ReviewerSpecialistM5S03IntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

结果：`5 / 5` 通过。

## 7. 后续实现约束

- M5-D06 将 Spike ContextPackage 形状实现为正式不可变领域对象、Hash 和失效状态机；
- M5-D07 将 Finding、Evidence、Fingerprint、ReviewerRelationship、ReviewDecision 和 Eligibility Policy 实现为正式领域规则；
- M5-I06 复用 M4 Artifact Reader 构建有界 Context，并接入真实 Reviewer Template 与模型；
- M5-A05 将 Agent Advisory 与 TeamMember Gate 拆成不同命令、权限和审计入口；
- M5-F06 在 Review Workbench 中分开展示 Agent Finding、SELF_REVIEW 和成员 Gate Decision。

完整架构决策见 [ADR-017](../adr/ADR-017-Reviewer证据与人工Gate边界.md)。
