# M4-D06 Command 与 TestEvidence 领域模型

> 完成日期：2026-08-17
> 范围：`crewscope-domain`、`crewscope-application`

## 1. 交付结果

M4-D06 将受信 Sandbox Runner 观察到的命令、测试与验收事实实现为不可变证据模型：

- `CommandSpec`：精确 WorkspacePolicy、BuildProfile、命令槽、typed argv、工作目录、超时、Sandbox 镜像 Digest 与规格 Hash；
- `CommandEvidence`：命令时间、终止方式、Exit Code、日志 Artifact、摘要、稳定失败分类与证据 Hash；
- `TestStatistics`：测试总数、成功、失败、错误和跳过计数；
- `AcceptanceResult`：按 CodingTargetSnapshot 固定顺序保存每条验收标准的判定与命令证据引用；
- `TestEvidence`：被测 DiffGeneration/Manifest Hash、有序 CommandEvidence、报告 Artifact、测试统计、验收结果、自动成功判定与证据 Hash；
- `CommandEvidenceRepository`、`TestEvidenceRepository`：完整 WorkProject Scope、Workspace 序号唯一和稳定排序 Port。

Agent、Structured Output 或 API 调用方不能提交 `succeeded=true`。成功与失败分类只由平台证据计算。

## 2. CommandSpec

`CommandSpec.capture` 只接受 WorkspacePolicy 中未被替换的 `CommandKind` 槽，并闭合：

```text
WorkspacePolicy ID + Policy Hash
BuildProfile Key + Version + Profile Hash
CommandKind + ToolKey
实际执行 argv
仓库相对 workingDirectory
timeoutSeconds
Sandbox Image Digest
```

实际 argv 必须保留 BuildCommand 固定前缀，没有 Selector 的命令不得追加参数；允许 Selector 的最终 argv 仍受参数数、单参数 UTF-8 字节数和 Runner 选择器信封限制。超时不能低于命令默认值，不能超过命令上限与 Sandbox 单命令上限。

规格 Hash 使用 `command-spec-v1` 前缀与长度前缀编码。重建时修改 Policy、Profile、镜像、argv、工作目录或超时都会失败关闭。M4-I07 的受信 Runner 负责把结构化模块/测试 Selector 生成最终 argv；领域模型不接受 raw Shell 字符串。

## 3. CommandEvidence

`CommandEvidence.record` 只在 ExecutionWorkspace 为 `ACTIVE` 时记录，并要求 Workspace、WorkspacePolicy、TaskExecution、attempt、CodingTarget、完整 Scope 与 CommandSpec 精确一致。聚合固化：

- ExecutionWorkspace ID 与 Fingerprint；
- WorkspacePolicy 与 CodingTarget 的 Hash 闭合引用；
- Workspace 内正数 `EvidenceSequence`；
- 开始/结束时间与记录审计时间；
- 平台观察到的 `CommandTermination`；
- 仅 `EXITED` 状态允许且必须保存 Exit Code；
- 有界 `EvidenceSummary`；
- `COMMAND_LOG` ArtifactStore 引用；
- 自动失败分类与 `command-evidence-v1` 证据 Hash。

命令成功只等于 `termination=EXITED && exitCode=0`。其他结果按以下稳定值分类：

| 终止事实 | 分类 |
|---|---|
| 启动失败 | `COMMAND_START_FAILED` |
| 超时 | `COMMAND_TIMED_OUT` |
| 输出超限 | `COMMAND_OUTPUT_LIMIT_EXCEEDED` |
| Sandbox Policy 拒绝 | `COMMAND_SANDBOX_POLICY_VIOLATION` |
| 取消 | `COMMAND_CANCELLED` |
| 非零 Exit Code | `COMMAND_NON_ZERO_EXIT` |

`CommandEvidence.reconstitute` 重新推导失败分类并重算 Hash，拒绝 Exit Code、摘要、Artifact、分类或其他权威事实篡改。

## 4. Artifact 与摘要边界

命令日志和测试报告使用 `EvidenceArtifactReference`：

```text
artifactId
kind = COMMAND_LOG | TEST_REPORT
canonical contentType
sizeBytes
contentHash
```

零字节 Artifact 必须使用空 UTF-8 内容的 SHA-256。大日志、完整测试报告、宿主路径、异常对象和原始环境不进入领域聚合；M4-I09 负责写入 ArtifactStore 并复验字节数与 Hash。

`EvidenceSummary` 最多 4,096 UTF-8 字节、100 行，必须非空且不含 NUL。Agent 上下文和公开 DTO 只消费该有界摘要与 Artifact 引用。

## 5. TestEvidence 与验收闭合

`TestEvidence.publish` 接受 `ACTIVE` 或 `FINALIZING` Workspace，并要求：

1. CodingTargetSnapshot、WorkspacePolicy、Workspace、TaskExecution、attempt 和完整 Scope 精确一致；
2. 固化受信 Git Reconciler 在测试边界提供的 DiffGeneration 与 Manifest Hash；
3. 至少一条 CommandEvidence，全部属于同一 Workspace ID、Fingerprint、Policy 和 CodingTarget；
4. CommandEvidence ID 唯一且 `EvidenceSequence` 严格递增；
5. 至少包含一个 `TEST`、`VERIFY` 或 `ACCEPTANCE` 命令；
6. 发布时刻不早于任一命令完成时刻；
7. `total = passed + failed + errors + skipped`，所有计数非负且无溢出；
8. AcceptanceResult 的数量、1-based Index、文本与 CodingTargetSnapshot 验收标准完全一致；
9. 已判定的验收项必须引用当前 TestEvidence 内的 CommandEvidence，`NOT_EVALUATED` 不得伪造引用；
10. 测试报告存在时必须是 `TEST_REPORT` Artifact。

判定器按固定优先级选择第一项失败：

1. 按证据顺序出现的首个 CommandEvidence 失败分类；
2. `TEST_REPORT_MISSING`；
3. `NO_TESTS_EXECUTED`；
4. `TESTS_FAILED`；
5. `ACCEPTANCE_INCOMPLETE`；
6. `ACCEPTANCE_FAILED`；
7. 全部通过时成功。

TestEvidence 成功要求所有命令成功、报告存在、至少执行一个测试、失败与错误数为零、所有验收标准均为 `PASSED`。最终成功还必须由 D07 复验被测 DiffGeneration/Manifest Hash 与最终 DiffArtifact 完全一致，测试后再修改代码会使旧证据失效。`TestEvidence.reconstitute` 重新推导分类并复验 `test-evidence-v1` Hash。

## 6. Repository Port

两个 Repository Port 均提供原子 `create`、按 ID、TaskExecution 和 ExecutionWorkspace 查询。每次查询显式携带 Organization、Team 和 WorkProject，列表按 `EvidenceSequence` 升序返回。

数据库适配器必须实现以下唯一键和稳定冲突码：

```text
CommandEvidence: execution_workspace_id + evidence_sequence
  -> command_evidence_sequence_conflict

TestEvidence: execution_workspace_id + evidence_sequence
  -> test_evidence_sequence_conflict
```

M4-D08 建立物理唯一约束，M4-D09 实现 PostgreSQL Adapter 与并发映射。

## 7. 测试证据

Domain 新增 11 个专项场景，覆盖：

- CommandSpec 的 Policy/Profile/Image/argv/timeout 与规格 Hash；
- Exit Code 形状、零退出成功和六类命令失败；
- 命令时间、日志类型、空内容 Hash、摘要字节/行上限；
- TestStatistics 等式、非负数与溢出；
- 命令 ID/顺序/验证类型与不可修改集合；
- 验收标准文本、Index、顺序、引用成员关系和未评估形状；
- 命令、报告、零测试、测试失败、验收不完整与验收失败优先级；
- 聚合重建、自动分类和证据 Hash 防篡改；
- 不存在由调用方传入的成功字段。

Application 新增 4 个 Repository 契约场景，覆盖 Workspace 内 Sequence 唯一、稳定错误码、完整 Scope 隔离和 TaskExecution/Workspace 列表排序。

验证命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-domain,crewscope-application -am test
```

## 8. 后续边界

M4-D07 在本领域模型上定义 `TestEvidenceV1`、`CodeChangeResultV1` 与 CodingCheckpoint 结构化契约。M4-D08/M4-D09 交付 V14 表结构和 PostgreSQL Adapter。M4-I07 实现受信 BuildProfile Runner 与 CommandEvidence Writer，M4-I09 实现日志和测试报告 ArtifactStore。
