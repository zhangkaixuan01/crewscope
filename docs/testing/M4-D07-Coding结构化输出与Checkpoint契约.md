# M4-D07 Coding 结构化输出与 Checkpoint 契约

> 完成日期：2026-08-17
> 范围：`crewscope-domain`、`crewscope-application`、`crewscope-agentscope`

## 1. 交付结果

M4-D07 将 Coding Specialist 的模型输出限定为版本化候选 DTO，并用服务端权威事实重新闭合：

- `RepositoryAnalysisV1`：引用固定 CodingTargetSnapshot，保存有界模块、构建入口、相关路径、风险和计划；
- `DiffManifestV1`：逐项重复 Git 权威 DiffManifest 的代次、Hash、统计和排序文件项；
- `TestEvidenceV1`：逐项引用平台 TestEvidence、CommandEvidence、测试统计和验收结果；
- `CodeChangeResultV1`：引用已经验证的分析、最终 DiffArtifact 和 TestEvidence；
- `CodingCheckpoint`：闭合 Plan/Todo、AgentRun Segment、Workspace Fingerprint、Policy、Diff Generation、最近 TestEvidence 与 AgentStateSnapshot；
- `CodingOutputValidator`：执行 Bean Validation、版本检查、canonical/AllowedPaths 检查和领域事实复验；
- `CodingStructuredOutputSpecs`：提供四份递归关闭的严格 JSON Schema；
- `StrictStructuredOutputDecoder`：在 AgentScope 反序列化前检查原始 Map，拒绝缺字段、未知字段、错误类型、边界和 Pattern；
- `CodingCheckpointRepository`：定义按 Organization 隔离的追加、按 ID 与 Workspace 最新 Checkpoint 查询 Port。

结构化输出没有 `success` 或 `succeeded` 字段。模型不能通过结果文本、未知字段或自报布尔值形成成功事实。

## 2. 三层结构化输出边界

四类输出固定经过以下边界：

```text
AgentScope JsonNode Structured Output
  -> strict=true + additionalProperties=false + 全字段 required
  -> StrictStructuredOutputDecoder 对原始 Map 再验证
  -> Java V1 DTO + Jakarta Bean Validation
  -> CodingOutputValidator 对照当前服务端领域事实
  -> 允许后续应用服务固化结果
```

`StructuredOutputSpec` 保留原有 `schemaId + javaType` 调用，并新增可选严格 Schema。严格 Schema 构造时要求：

1. 根节点和所有嵌套对象都是 `type=object`；
2. 每层 `additionalProperties=false`；
3. `required` 与该层 `properties` 完全一致；
4. Schema 深拷贝后不可修改；
5. Schema 值只能使用 JSON 兼容类型。

`AgentScopeNativeRuntime` 对带严格 Schema 的调用使用 AgentScope 2.0.0 原生 `call(messages, JsonNode, RuntimeContext)`。该路径继续使用 AgentScope 的 Native Structured Output 或 `generate_response` fallback；返回后先读取原始 Structured Output Map，通过严格 Decoder，再转为 DTO。AgentScope 默认 Jackson 会忽略未知字段，因此不能单独承担 CrewScope 的安全边界。

## 3. RepositoryAnalysisV1

`RepositoryAnalysisV1` 闭合：

```text
schemaVersion = 1
CodingTargetSnapshot ID + Revision + Snapshot Hash
modules[0..100]
buildEntries[0..50]
relevantPaths[1..500]
risks[0..50]
plan[1..100]
```

Build Entry 和 Relevant Path 必须是 canonical 仓库相对路径，并位于 CodingTargetSnapshot 的 AllowedPaths 内。绝对路径、Windows Drive、反斜杠、空段、`.`、`..`、控制字符和越权目录失败关闭。路径和模块集合不得重复。

验证后按 `repository-analysis-v1`、长度前缀字段和列表原顺序计算稳定 SHA-256。`CodeChangeResultV1.repositoryAnalysisHash` 必须引用该验证结果。

## 4. DiffManifestV1

`DiffManifestV1` 是模型对 Git 权威事实的声明，不是新的 Diff 来源。它保存 Workspace ID/Fingerprint、CodingTarget 引用、DiffGeneration、Manifest Hash、文件数、增删行和有序 `DiffFileV1`。

每个文件项必须与最终 `DiffArtifact.manifest()` 同位置条目完全一致：

- current path 与可选 old path；
- `ADDED/MODIFIED/DELETED/RENAMED/COPIED/TYPE_CHANGED`；
- additions、deletions、binary、patchTruncated；
- Patch SHA-256。

当前路径和 old path 都要通过 AllowedPaths。文件缺失、重复、重排、统计差异、代次差异或任一 Hash 差异均失败关闭。模型不能提交 Patch 正文。

## 5. TestEvidenceV1

`TestEvidenceV1` 引用固定 Workspace、CodingTarget 和 WorkspacePolicy，并重复平台 TestEvidence 的：

- ID、EvidenceSequence 与 Evidence Hash；
- 被测 DiffGeneration 与 Manifest Hash；
- 有序 CommandEvidence ID、Sequence 与 Evidence Hash；
- TestStatistics；
- 有序 AcceptanceResult、证据引用和 Summary Hash；
- TestEvidence Summary Hash。

校验要求全部引用属于同一 Scope、TaskExecution、attempt、Workspace Fingerprint、CodingTarget 和 WorkspacePolicy。被测 DiffGeneration/Manifest Hash 必须与领域 TestEvidence 一致；命令必须数量一致、顺序一致、ID 唯一；统计、验收文本、Index、状态、证据成员和摘要 Hash 必须逐项一致。

`TestEvidenceV1` 不携带平台失败分类或成功布尔值。最终成功读取领域 `TestEvidence.succeeded()`。

## 6. CodeChangeResultV1

最终候选只保存以下闭合引用和有界公开摘要：

```text
Workspace ID + Fingerprint
CodingTarget ID + Revision + Hash
RepositoryAnalysis Hash
DiffArtifact ID + Final Hash
TestEvidence ID + Evidence Hash
changeSummary / limitations / risks
```

`CodingOutputValidator.validateCodeChangeResult` 重新验证 RepositoryAnalysis，确认 DiffArtifact 和 TestEvidence 属于同一 ExecutionWorkspace/CodingTarget，被测 DiffGeneration/Manifest Hash 精确等于最终 DiffArtifact，并要求真实 TestEvidence 成功。伪造 ID、Hash、Workspace、Target、失败证据或测试后代码变化都会使结果失败。

## 7. CodingCheckpoint

Checkpoint 是不可变恢复闭包，保存：

- Task、TaskExecution attempt、CodingTarget；
- ExecutionWorkspace ID/Fingerprint 与 WorkspacePolicy Hash 引用；
- AgentRun ID、Run Sequence、当前 Segment Sequence 和可选 StepExecution；
- 可选 PlanVersion ID/Hash；
- 有界 Agent Plan Markdown 和唯一键 Todo 列表；
- DiffGeneration 与 DiffManifest Hash；
- 可选最近 TestEvidence ID/Hash；
- AgentStateSnapshot ID、Snapshot Sequence、Content Hash 与 Checkpoint Sequence；
- 创建 Principal、时间和 `coding-checkpoint-v1` Hash。

Plan/Todo 是恢复用 Agent 工作状态，不能替代 PlanVersion、StepExecution、Git Diff、TestEvidence 或验收事实。`CodingCheckpoint.capture` 只接受同一 Scope/Task/Execution 的 Target、Workspace、Policy、Run、Plan、Evidence 和 `CURRENT` AgentStateSnapshot；存在 TestEvidence 时，其被测 Diff 必须与 Checkpoint Diff 一致。成对引用必须同时存在，集合不可修改，重建时重新计算 Hash。

Repository Adapter 在 M4-D09 实现 Workspace 内 CheckpointSequence 单调追加；M4-D08 建立物理唯一约束。并发冲突统一返回 `coding_checkpoint_sequence_conflict`。

## 8. 自动化证据

专项测试覆盖：

- 四份严格 Schema 的全字段 required、递归 `additionalProperties=false` 和不可修改；
- AgentScope 2.0.0 对 DTO required 字段的 Schema 识别；
- AgentScope JsonNode Structured Output 的真实 HarnessAgent fallback 调用；
- 原始结果缺字段、顶层未知字段和伪造 `succeeded` 拒绝；
- SchemaVersion 前向版本拒绝；
- canonical 路径、AllowedPaths 越界和重复路径拒绝；
- Diff 文件项、顺序、统计、Generation 和 Hash 复验；
- CommandEvidence 顺序/Sequence/Hash、测试统计、验收和摘要 Hash 复验；
- 最终 DiffArtifact/TestEvidence Hash 伪造、失败 TestEvidence 与陈旧被测 Diff 关闭；
- Checkpoint Scope/Run/Snapshot 闭合、不可修改 Plan/Todo、Hash 防篡改和无效快照拒绝。

验证命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-domain,crewscope-application,crewscope-agentscope -am test
```

## 9. 后续边界

M4-D08 建立 V14 表结构，M4-D09 实现 Repository Adapter。M4-I11 使用本阶段的严格 Specs 构建 Coding Specialist；M4-I12 执行 Checkpoint 保存、恢复和最终结果复验。本阶段不创建数据库表，不启动 Runner，不执行 Git/Filesystem/Sandbox 副作用。
