# ADR-017：Reviewer 证据与人工 Gate 边界

> 状态：ACCEPTED<br>
> 日期：2026-08-22<br>
> 影响里程碑：M5–M6<br>
> 关联决策：[ADR-005](ADR-005-事件与投影协议.md)、[ADR-007](ADR-007-API命令与并发协议.md)、[ADR-013](ADR-013-AgentScope事件映射与披露协议.md)、[ADR-016](ADR-016-Agent所有权、模板与执行配置.md)

## 背景

M4 已交付不可变 `CodingTargetSnapshot`、`DiffArtifact`、`CommandEvidence` 和 `TestEvidence`。M5 的 Reviewer Specialist 需要利用这些事实发现代码问题，同时保持以下产品边界：

- 模型看到的上下文足以评审，且不扩展到整个仓库、历史会话或凭证；
- Finding 可由成员复核，并能定位到本次精确 Diff、测试证据和验收事实；
- 相同问题不会因模型措辞或重试产生多条重复记录；
- Agent 自检、独立 Agent Review 和成员 Gate Review 的效力不同；
- 模型输出不能形成 `APPROVED`、`CHANGES_REQUESTED` 或 `REJECTED` 等 Gate 结论。

## 决策

### 1. ReviewRequest 绑定精确事实

`ReviewRequest` 引用以下不可变坐标：

- WorkProject Scope、Task、TaskExecution 和 attempt；
- `ReviewSubjectType + SubjectId + SubjectHash`；
- `CodingTargetSnapshotId + Revision + SnapshotHash`；
- `DiffArtifactId + FinalHash + BaselineCommit + DeliveryCommit`；
- `DiffGeneration + DiffManifestHash + PatchArtifactReference`；
- `TestEvidenceId + EvidenceHash + tested DiffGeneration/ManifestHash`；
- 全部 `AcceptanceResult` 及其 `CommandEvidenceReference`；
- Reviewer `AgentProfile/TemplateVersion/ConfigurationVersion` 与 `PolicySnapshot`；
- `ContextPackageId + Version + ContextHash`。

任一 Subject、Diff、Evidence、Template 或 Policy 坐标变化都创建新的 ContextPackage 和 ReviewRequest。旧 Request、Finding 和 Gate Decision 保留为历史，但不能控制新版本状态迁移。

### 2. 最小 ContextPackage

Reviewer Specialist 的 `ContextPackageV1` 只包含：

```text
Review Subject 精确引用
CodingTarget 精确引用
Reviewer Template 与 Policy 精确引用
Baseline / Delivery Commit
最终 DiffArtifact、Manifest 与有界 Changed Hunk
TestEvidence、CommandEvidence 摘要与全部 AcceptanceResult
服务端推导的 ReviewerRelationship
ContextPackage Hash
```

默认上限：128 个变更文件、512 KiB Patch Hunk、64 个 CommandEvidence、100 条 AcceptanceResult。超限内容通过受权 Artifact 引用分片读取，不把整个仓库、完整会话、原始环境、原始任意命令、凭证、Provider 原始错误或未引用 Artifact 放入上下文。

`ContextHash` 使用规范顺序闭合全部引用、版本、Hash、路径与 Hunk 坐标。Reviewer 开始、恢复和输出落库前都复验 ContextHash 与当前 ReviewRequest。

### 3. Reviewer Prompt

`reviewer@1` System Prompt 固定以下行为：

1. 只报告会影响正确性、安全、可靠性、可维护性、测试或验收的可执行问题；
2. 正确变更返回空 Finding，不生成表扬性 Finding；
3. 每条 Finding 引用 ContextPackage 中的真实变更路径和 Hunk；
4. 每条 Finding 同时引用精确 DiffArtifact、TestEvidence 和 AcceptanceResult；
5. 不推断 ContextPackage 外的仓库事实；
6. 只输出 `ReviewFindingListV1`；
7. 不批准、不拒绝、不请求修改，不输出 Gate Decision。

用户补充指令不能修改上述边界、Schema、证据要求或 Review 效力。

### 4. ReviewFindingListV1

`ReviewFindingListV1` 根对象仅包含：

```text
schemaVersion
findings[]
```

每条 Finding 包含：

```text
severity       BLOCKER / HIGH / MEDIUM / LOW
category       CORRECTNESS / SECURITY / RELIABILITY / MAINTAINABILITY / TESTING / ACCEPTANCE
title          有界标题
claim          可验证的问题陈述
suggestedFix   有界修复建议
evidence[]     1–8 个证据坐标
```

每个证据坐标必须包含规范路径、起止行、DiffArtifact ID/Hash、DiffManifest Hash、TestEvidence ID/Hash 和 Acceptance Criterion Index。路径必须存在于当前 Diff，行号必须落在提供的 Hunk 内，所有 ID 与 Hash 必须匹配 ContextPackage，Criterion Index 必须对应当前完整 AcceptanceResult。

Schema 所有字段 required，所有对象 `additionalProperties=false`。AgentScope 使用原生 Structured Output 或合成 `generate_response`，CrewScope 在 DTO 转换前执行严格 Schema 校验，随后执行 Evidence Resolver、Scope、业务和 Policy 校验。无证据、伪造路径、越界行号、旧 Hash、未知验收项和 Context 外结论全部失败关闭。

### 5. Finding 去重

平台计算规范 Fingerprint，模型不能提交 Fingerprint：

```text
SHA-256(
  subjectHash
  + category
  + canonicalPath
  + normalizedRange
  + normalizedClaim
)
```

`normalizedClaim` 执行 Unicode 规范化、去首尾空白、连续空白折叠和稳定小写。相同 ReviewRequest 内相同 Fingerprint 只保存一条 Finding；不同 ReviewRequest 保留各自历史。标题、严重级别和建议不作为身份，防止措辞变化制造重复 Finding。合并时保留首次发现事实，并把后续来源记录为 Observation/Audit。

### 6. SELF_REVIEW 与 Gate

`ReviewerRelationship` 由服务端根据 Reviewer Agent Owner、被审对象责任链和 ReviewRequest 当前事实推导：

```text
INDEPENDENT
SELF_REVIEW
```

模型不能提交或覆盖该关系。Reviewer Agent 的所有 Finding 效力始终为 `ADVISORY`。`SELF_REVIEW` 可用于执行前自检和修复，但不能满足职责分离、Gate Reviewer Assignment 或 Gate Policy。

Gate Decision 是独立成员命令：

```text
eligible TeamMember
  -> ReviewerEligibilityPolicy
  -> current ReviewRequest + ETag
  -> APPROVED / CHANGES_REQUESTED / REJECTED / COMMENTED
```

`ReviewFindingListV1` 不存在 `gateDecision`、`effect`、`approval` 或状态迁移字段。Gate Application Service 只接受当前 USER 对应的合格 TeamMember，拒绝 Agent Principal、Service Principal 和模型 Candidate。Agent 不能通过模板、Owner、配置、Fallback 或管理员降级获得 Gate 权限。

## 实现约束

1. ContextPackage 只保存权威对象引用、受控 Hunk 和内容 Hash，不复制无版本业务文本作为权威事实。
2. TestEvidence 的 DiffGeneration/ManifestHash 必须与被审 DiffArtifact 一致。
3. 正确样本返回空列表；空列表是成功 Review 输出。
4. Finding 的 Severity 不直接决定 Gate 结论，由 PolicyPack 和合格 TeamMember 处理。
5. Evidence Resolver 在 Finding 入库前完成，无法解析的 Finding 不以“低可信度”降级保存。
6. ReviewRequest 失效后禁止新 Finding、Gate Decision 和 ActionBundle；进行中的 Agent 调用在安全点失败关闭。
7. Finding、Decision、失效、重复合并和修改轮次产生 DomainEvent、Outbox 与 AuditEvent。
8. API 和前端分别展示“Agent 建议”与“成员结论”，不得把 Advisory 渲染为已批准状态。

## 结果

- Reviewer Specialist 获得足够且最小的代码、测试和验收上下文；
- 每条 Finding 都能回到真实 M4 Artifact 和验收事实；
- 自检可用于提高交付质量，但不替代团队责任与成员审批；
- AgentScope Structured Output 负责生成，CrewScope 负责证据真实性和业务效力；
- Gate Decision 保持为可追责的成员行为。

## 验证

1. 固定正确样本重复执行均返回空 Finding。
2. 固定缺陷样本重复执行均返回相同可验证 Finding。
3. 固定无关样本产生 Context 外路径时被稳定拒绝。
4. 缺失 Evidence、错误 Diff/Test Hash、越界行号和未知 Acceptance Index 被拒绝。
5. 相同 Claim 的空白、大小写和标题变化被规范 Fingerprint 合并。
6. Reviewer Owner 与 Subject Owner 相同时由服务端标记 `SELF_REVIEW`，效力仍为 `ADVISORY`。
7. Agent 输出 `APPROVED` 等额外字段时 AgentScope Schema 拒绝并允许修复为合法 Finding List。
8. Agent Principal 调用 Gate 命令被拒绝，只有通过 Eligibility Policy 的 TeamMember 可提交。

## 重新评估条件

- 产品允许 Agent 在无人参与下控制生产发布或不可逆外部动作；
- Review Subject 扩展到无法使用路径与行号表达的二进制、模型或基础设施制品；
- AgentScope 提供可验证出处、签名证据或原生 Human Gate 协议；
- 团队法规要求双人 Gate、多级审批或外部代码所有者联签。
