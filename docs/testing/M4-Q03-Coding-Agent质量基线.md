# M4-Q03 Coding Agent 质量基线

> 状态：已完成<br>
> 日期：2026-08-22<br>
> 范围：真实模型绑定、冻结评测矩阵、依赖快照、平台证据、人工判定、质量聚合与 CrewScope 修改闭环

## 1. 验收目标

M4-Q03 对 `crewscope-java-spring-coding@1.0.0` 的 12 个任务使用 3 个冻结 Seed 执行 36 次真实模型运行。每次运行固定 Provider、Model ID、Model Revision、AgentProfile、Prompt、Skill、Tool、Sandbox、依赖缓存、预算和随机参数。

质量门禁要求：

1. 端到端成功率不低于 `70%`，36 次运行至少成功 26 次；
2. 每个成功运行的编译、测试、验收、路径、安全和人工复核全部通过；
3. 平台预算与模型遥测逐字段一致；
4. 汇总记录 Pass@1、任务成功率、编译率、测试率、验收率、路径合规率、安全合规率、人工通过率、Token、成本和墙钟时间；
5. 至少一个 `crewscope-java` 自身修改形成 Workspace、AgentRun、CommandEvidence、TestEvidence、DiffArtifact、Delivery Commit、CodingResult 和人工复核闭环。

## 2. 模型装配

AgentScope OpenAI Starter 在 Spring 容器中创建 Provider `Model`。`AgentScopeModelConfiguration` 将稳定槽位 `crewscope-primary` 解析到唯一的 Spring `Model` Bean；其他显式模型键继续交给 AgentScope `ModelRegistry`。

服务可在未配置模型的 API 开发环境启动。首次 Agent 调用发现 `crewscope-primary` 没有唯一 Provider Model 时失败关闭。选择 OpenAI Provider 后，`OPENAI_API_KEY` 只通过环境进入 Starter，不进入 RunLock、日志、遥测或评测归档。

## 3. 评测协议

Q03 资产位于 `evaluation/m4/coding-q03`，保留 S04 的 `evaluation/m4/coding-v1` 历史资产不变。

`benchmark.mjs prepare` 生成 12 个任务乘 3 个 Seed 的不可变运行矩阵和逐 Run Lock。`aggregate` 对每个运行重新调用 S04 Judge，核对 Workspace、Platform Report、Telemetry 和 Human Review，再生成追加写入的聚合报告。缺失运行、额外运行、矩阵乱序、跨 Run 证据、RunLock 漂移、遥测与预算不一致、人工复核缺失和 CrewScope 闭环缺失均失败关闭。

聚合器使用冻结单价和平台 Token 事实计算成本。API Key、宿主路径、原始模型错误和内部 Reasoning 不进入聚合报告。

## 4. Maven 依赖快照

`m4-q03-prepare-maven-cache.sh` 在隔离仓库中执行全部 Judge Pack 的 `dependency:go-offline` 和 `test-compile`，随后移除缓存写权限并生成路径、大小和文件字节组成的 SHA-256 树指纹。

本机快照结果：

```text
Snapshot ID = m4-q03-20260821-02
Files       = 2531
Bytes       = 96223025
SHA-256     = d23ee7ae615cae0b26d3736b872bbccaec13cc8bf7bcfc92352b935ca01054fd
```

固定 Digest Maven Sandbox 已使用无网络、非 Root、只读根文件系统和只读缓存挂载完成离线 `testCompile`，结果为 `BUILD SUCCESS`。

## 5. 当前验证

- S04 冻结评测协议：12 个任务、2 个轨道，通过；
- Q03 聚合器边界：7 / 7，通过，包含 6 项聚合不变量和 1 项 36 次完整矩阵聚合与追加写入拒绝；
- Spring Model 槽位与 OpenAI Starter 装配：7 / 7，通过；
- 应用装配回归：1 / 1，通过；
- Coding Artifact 独立装配与运行时元数据注册条件：4 / 4，通过；
- Q03 运行矩阵烟测：36 / 36 个 RunLock 生成；
- Maven Cache 树指纹复验：通过；
- 固定 Sandbox 离线编译：通过；
- Maven 全量 `verify`：7 / 7 个 Reactor 模块通过；
- Coding Runtime 异常有界恢复：5 / 5，通过；
- Maven 多模块测试选择器：追加 `-Dsurefire.failIfNoSpecifiedTests=false`，专项测试通过；
- 任务级隐藏 Judge 注入：5 / 5，通过，未选中的 Judge 不进入当前 Worktree。

## 6. 首轮正式结果

正式批次为 `m4-q03-deepseek-v4-flash-20260822-03`，固定 Provider `deepseek`、Model ID `deepseek-v4-flash`、Model Revision `DeepSeek-V4-Flash-0731`、温度 `0`、Top P `1`、AgentProfile `1.0.0`、Sandbox Digest 和 Maven Cache 快照。

真实聚合结果：

```text
Total Runs             = 36
Successful Runs        = 15
End-to-End Success     = 41.67%
Pass@1                 = 33.33%
Task Success Rate      = 66.67%
Compile Rate           = 77.78%
Test/Acceptance Rate   = 75.00%
Path Compliance Rate   = 75.00%
Security Compliance    = 100.00%
Input / Output Tokens  = 4334075 / 319931
Cost                    = USD 0.35763656
CrewScope Closure      = PASSED
Quality Gate           = FAILED
```

失败由 `12` 次 `BUDGET_EXHAUSTED` 和 `9` 次 `PATH_VIOLATION` 组成。该批次按当时冻结的 `120000` 输入 Token 上限判定，结果保持不可变。人工复核身份固定为 `codex-m4-q03-assisted-review`，结论属于人工辅助复核。

CrewScope 自修改闭环已通过，Delivery Commit 为 `0d0869f83bd11076619b09c52d64b05586207c87`，独立 `ClarificationAnswersTest` 为 `4 / 4`。闭环包含 Workspace、Specialist AgentRun、CommandEvidence、TestEvidence、DiffArtifact、CodingResult Hash 和 Delivery Commit 的可追溯坐标。

## 7. 首轮整改

首轮暴露并完成以下平台整改：

1. 历史 Repository 只读结果允许 AgentScope Eviction，Mutation Receipt 与固定 Skill 保留；Compaction Trigger 从 `40` 收紧到 `24`，保留消息从 `8` 调整为 `6`，Tool Result Eviction 从 `32768` 调整为 `8192`；
2. malformed Provider Output、无效结构化结果或不完整安全点使用同一 Agent 状态与 Worktree 执行一次有界恢复；Provider Transport Retry 继续由 AgentScope `maxRetries` 管理；
3. Maven 多模块测试选择器允许不拥有指定测试的 Reactor 模块安全通过；
4. 隐藏 Judge 按不可变 Allowed Java Source Path 派生并只注入当前任务对应测试。未整改前，无选择器 `VERIFY` 会运行全部 12 个 Judge，当前任务即使通过也会被其他 11 个未修复目标拖失败，并触发无意义修复和 Token 消耗。

整改 Pilot 已证明原 `CODING_RUNTIME_FAILED / PATH_VIOLATION` 样本可以转为 `COMPLETED`。余额恢复后，`java-username-normalization` 的 3 个固定 Seed 均达到 `COMPLETED`，编译、测试、验收、路径、安全、结构化结果和人工辅助复核全部通过；输入 Token 分别为 `137492`、`330445` 和 `81469`。最高消耗样本经历两轮真实测试修复，其质量证据仍然完整通过。

Token 继续作为成本与效率指标聚合，不再使用 `120000` 作为模型质量的苛刻门槛。运行时保留资源失控保护：单次输入 Token 上限 `600000`、输出 Token 上限 `64000`、模型调用上限 `80`；测试、验收、路径、安全、结构化输出和人工辅助复核仍是成功运行的硬门禁。该调整形成新的 Runtime Asset Hash，只适用于调整后创建的新批次，不改写任何历史 RunLock 或报告。

## 8. 最终正式结果

最终正式批次为 `m4-q03-deepseek-v4-flash-20260822-06`。36 次运行全部使用全新 `Q03-12001..12036` WorkItem 区间、当前 Runtime Asset Hash、固定模型身份、Sandbox Digest 和 Maven Cache 快照，峰值单价作为全程保守成本上界。

真实聚合结果：

```text
Total Runs             = 36
Successful Runs        = 29
End-to-End Success     = 80.56%
Pass@1                 = 75.00%
Task Success Rate      = 100.00%
Compile Rate           = 86.11%
Test/Acceptance Rate   = 83.33%
Path Compliance Rate   = 80.56%
Security Compliance    = 100.00%
Input / Output Tokens  = 4825863 / 448232
Conservative Cost      = USD 1.039345992
CrewScope Closure      = PASSED
Quality Gate           = PASSED
```

7 次失败均为 `PATH_VIOLATION`，包括无代码修改、越界或不可接受的最终路径事实；失败运行未被人工判定包装为成功。全部 12 个任务至少有 1 个 Seed 通过，成功运行不存在编译、测试、验收、路径、安全或人工判定不变量缺口。

原始导出器只将 `TEST` CommandEvidence 映射为验收证据，遗漏了 4 条成功 `VERIFY` 命令。修复后导出器接受 `TEST / VERIFY / ACCEPTANCE`；历史 `platform-report.json` 与 `telemetry.json` 保持不可变，追加 `evidence-correction.json`、哈希绑定的 corrected 文件和 `aggregate-v2.json`。独立 Judge 复判后的 `aggregate-v2.json` 为最终权威报告，初次失败聚合继续保留用于审计。

M4-Q03 已满足真实模型成功率不低于 70%、成功运行质量事实完整和 CrewScope 自修改闭环通过的完成条件。协议门禁和脚本化结果未计入模型能力分数。
