# M4：AgentScope 原生 Coding Agent 执行清单

> 对应总计划：[CrewScope 实施计划](../CrewScope-实施计划.md) M4<br>
> 前置条件：M3 Release Gate 通过，ADR-002 已接受，M0-S03 Docker Sandbox 验证通过<br>
> 目标周期：4–5 周，按纵向波次推进<br>
> 目标结果：成员从 WorkItem 或 Conversation 指定受管仓库目标后，Coding Specialist 可在独立 Worktree 与 Docker Sandbox 中分析、修改、测试并交付可恢复、可观察、可审计的 Diff 与 TestEvidence<br>
> 当前进度：44 个任务全部完成，M4 Release Gate 已通过（2026-08-22）

## 1. 出口结果与范围

M4 完成后具备：

- Team/WorkProject 级 RepositoryBinding 与 Task 级不可变 CodingTargetSnapshot；
- 每个 TaskExecution 独立的 ExecutionWorkspace、分支、Worktree、基线 Commit 和 WorkspacePolicy；
- 同机 Execution Worker、Git Worktree、AgentScope Docker Sandbox 与 Diff Watcher；
- 受控仓库读取、搜索、修改、命令、构建、测试和 Git 只读工具；
- AgentScope 原生 Coding Specialist 的“分析—计划—修改—测试—Diff 自检—交付”循环；
- DiffArtifact、DiffManifest、CommandEvidence、TestEvidence 与大日志 RuntimeArtifact；
- Conversation Mode 与 Control Mode 共享的 Execution Studio、实时 Diff、测试证据和恢复状态；
- Worktree、Sandbox、Watcher、Worker 与 Agent 进程故障后的对账和续接；
- 固定 Coding 任务集、安全攻击集、故障集和 M4 Release Gate。

M4 的 RepositoryBinding 只支持受管本地 Git 源仓库。成员不能从浏览器提交任意宿主路径，服务端通过配置的 Repository Root 与稳定 Repository Key 解析真实路径。GitHub Clone、Push、Draft PR、Webhook、外部凭证、PlannedAction、独立 Reviewer Specialist 和 Gate Review 在 M5 交付。

M4 不提供浏览器任意交互式终端，不开放原始宿主 Shell，不允许 Agent 执行 `git add/commit/reset/clean`，不启用外部网络、MCP、动态 Skill 写入或 AgentScope Coding 示例中的 GitHub/Reviewer 工具。平台在 Diff 与测试证据验证后可创建本地交付 Commit；该 Commit 不离开受管 Worktree。

## 2. 用户闭环

```text
Team Admin 注册受管 RepositoryBinding
  -> 成员从 WorkItem 或 Conversation 选择仓库、基线 Ref、允许路径和构建配置
  -> 服务端固化 CodingTargetSnapshot 并创建 Task + READY attempt
  -> Worker 领取 attempt，解析基线 Commit，创建独立 Worktree 与 Docker Sandbox
  -> Coding Specialist 分析、计划、修改并执行受控构建/测试
  -> Diff Watcher 持续发布变更摘要，TestEvidence 固化命令结果
  -> Coding Specialist 自检并提交结构化 CodeChangeResultV1
  -> 平台复验工作区、Diff、测试和验收标准，固化最终 Artifact
  -> Conversation Mode 与 Execution Studio 展示同一终态事实
```

传统管理入口负责 RepositoryBinding、Coding Task、Execution Studio、Diff/Test 查询和运维诊断；对话入口负责提出目标、确认 CodingTarget 和观察 Task 卡片。两个入口只调用同一应用服务和服务端事实。

## 3. AgentScope Java 2.0.0 能力映射

| AgentScope 能力 | M4 使用方式 | CrewScope 约束 |
|---|---|---|
| `HarnessAgent` | Coding Specialist 主体，使用固定 AgentProfile/Model/Prompt/Skill 版本 | 一个 Task AgentRuntimeSession 对应稳定 Agent 身份，状态进入 M3 Snapshot 协议 |
| Plan Mode 与 Task List | 形成候选计划和 Todo 进度 | 只有校验后发布的 PlanVersion、StepExecution 和 Checkpoint 是领域事实 |
| `DockerFilesystemSpec`、`WorkspaceSpec` | 将当前 Worktree 绑定到 `/workspace/repository` | 镜像使用 Digest；网络默认关闭；普通用户、只读根层、CPU/内存/PID/超时限制 |
| Sandbox Lifecycle、State Store、Execution Guard | 复用 Sandbox 文件系统注入和自管理调用窗口；CrewScope 包装 TaskExecution 级 Sandbox 生命周期 | external Sandbox 路径绕过原生 Guard；CrewScope 在注入和每次 Tool 调用时复验 Workspace、Task Token、Lease 与 Fencing |
| `AbstractFilesystem` | 复用 list/read/write/edit/grep/glob/delete/move 的 Sandbox 实现 | 不直接注册原生 `FilesystemTool`，由 AllowedPaths、大小和数量受限的 CrewScope Tool 包装 |
| `ShellExecuteTool` | 只作为源码能力参考 | 其参数是原始 Shell 字符串，M4 禁止直接暴露；改用结构化 `SandboxCommandTool` 与固定命令目录 |
| Compaction 与 Tool Result Eviction | 控制长会话上下文和大工具结果 | Checkpoint 前保存 AgentStateSnapshot；大输出先写 RuntimeArtifact，再给 Agent 有界摘要 |
| Interrupt/Resume 与 AgentStateStore | 复用 M3 Pause/Resume、Redis 热状态和 Snapshot 恢复 | 恢复时重新验证 Workspace、Sandbox、Task Token、Policy 与当前 Lease |
| Skills | 只加载 PolicySnapshot 固定的只读 Coding Skill Bundle | 动态 Skill 管理、自动推广、Workspace 自写 Skill 和外部 MCP 在 M4 关闭 |
| Coding Agent 示例 | 参考 Prompt、预算和会话组织 | 不依赖 examples Artifact，不复制 GitHub、Webhook、PR Review 或外部网络能力 |

## 4. 固定执行契约

### 4.1 RepositoryBinding 与 Coding Target

- RepositoryBinding 绑定 Organization、Team、Workspace、WorkProject、稳定 Repository Key、默认分支、受管源仓库标识、状态和版本；
- 宿主绝对路径只存在于受信 Repository Resolver，不进入浏览器 DTO、Prompt、日志或 Audit Details；
- CodingTargetSnapshot 固化 RepositoryBinding ID/Version、请求 Ref、解析后的基线 Commit、AllowedPaths、BuildProfile、验收标准 Hash 和创建 Principal；
- 基线 Ref 必须在受管源仓库解析为 Commit，Task 创建后基线 Commit 不随默认分支移动；
- Retry 创建新的 TaskExecution 和 ExecutionWorkspace，沿用或显式更新当前仍授权的 CodingTargetSnapshot，旧 Workspace 和 Artifact 保持不可变。

### 4.2 ExecutionWorkspace

- 一个 TaskExecution 最多一个活动 ExecutionWorkspace；一个 Workspace 只属于一个 Organization、Team、Task、attempt、Runtime 与 Worker 谱系；
- 状态固定为 `PENDING -> PROVISIONING -> READY -> ACTIVE -> FINALIZING -> COMPLETED`，故障进入 `RECOVERING/FAILED`，保留期结束进入 `ARCHIVED`；
- Pause 只停止 Agent 与命令，不销毁 Worktree；Cancel 终止 Sandbox 和命令并固化最后可证明 Diff，随后按策略归档；
- Worktree 目录由平台根据 Workspace ID 确定，不能由成员、Agent、Prompt 或 Tool 参数指定；
- 分支名由稳定 ID 生成，创建、校验、锁、回滚、恢复、归档和清理均幂等；
- Worktree 是代码事实源，Sandbox、AgentState 和 Diff Event 都不能覆盖其实际内容。

### 4.3 Sandbox 与 Tool

- Sandbox 只读挂载必要缓存，读写挂载当前 Worktree；禁止挂载 Docker Socket、宿主 Home、CrewScope 数据目录和凭证目录；
- Agent 环境只包含短期 Task Token 和非敏感运行坐标，不包含 OAuth Token、PAT、GitHub App Key、数据库密码或宿主环境全集；
- 原生 Filesystem/Shell Tool 不直接暴露；CrewScope Tool 每次调用复验 Task Token、Lease/Fencing、Workspace、Policy、AllowedPaths、命令目录和预算；
- `SandboxCommandTool` 接受 CommandKind、模块/测试选择器和有界参数，不接受任意 Shell 字符串；Maven Wrapper、Maven、Gradle Wrapper 和项目脚本按 BuildProfile 显式允许；
- Git status、diff、log、show 等读取通过类型化能力提供；宿主 Git 管理命令使用参数数组；本地交付 Commit 由平台 Finalizer 创建；
- 文件数、单文件大小、累计写入、Diff 大小、命令次数、进程数、输出、Token、模型调用和总时长都有硬上限。

### 4.4 Diff、测试与 Artifact

- Diff Event 是可丢失的实时提示，Git 基线与 Worktree Reconcile 是权威事实；
- Watcher 事件按 Workspace 分区，周期 Reconcile 生成 Reset/Upsert/Delete 事件并使用不透明 Cursor；
- 最终 DiffArtifact 固化 baseline Commit、交付 Commit/Tree、文件清单、增删行、Patch Artifact、Workspace Fingerprint 和 Hash；
- TestEvidence 固化结构化 CommandSpec、镜像 Digest、开始/结束时间、Exit Code、超时、摘要、日志 Artifact 和验收项结果；
- 数据库只保存有界元数据，大 Patch、构建日志、测试报告和二进制结果进入 ArtifactStore；
- 前端只读取公开摘要或受权 Artifact 内容，不读取宿主路径、原始环境、Task Token、AgentState 或内部 Reasoning。

### 4.5 Coding Specialist 与恢复

- Coding Specialist 使用独立 AgentProfile 和稳定 Task/Step AgentRuntimeSession，不复用 Personal Agent 会话；
- RepositoryAnalysisV1、CodeChangeResultV1、TestEvidenceV1 和 DiffManifestV1 使用严格 Structured Output；
- Agent 的计划、Todo 与自述不能证明成功，平台必须复验 Git 状态、AllowedPaths、命令证据、测试结果和验收条件；
- Checkpoint 同时记录 Plan/Todo、AgentRun Segment、Workspace Fingerprint、Diff Generation、最近 TestEvidence 和 AgentStateSnapshot；
- 恢复顺序固定为 Lease/Task Token -> Workspace -> Git 基线/Worktree -> Sandbox -> Diff Reconcile -> AgentState -> Agent Run；
- Workspace 或 Worktree 不可证明安全时失败关闭，不根据 Agent 文本重建代码；
- 测试失败可在同一 attempt 内按预算修复；平台故障使用原 attempt 恢复，业务失败或达到重试策略时由 M3 Retry 创建后继 attempt。

## 5. 依赖顺序

```text
M4-S01 -> M4-D04 -> M4-I04..I07 -> M4-I11
M4-S02 -> M4-D01..D03 -> M4-D08 -> M4-D09 -> M4-I01..I03
M4-S03 -> M4-D05 -> M4-I08 -> M4-A05 -> M4-F05
M4-S04 -> M4-D07 -> M4-I11..I12 -> M4-Q03

M4-D01 + D02 -> M4-A01 + A02
M4-D03..D06 + D09 -> M4-A03 + A04 + A06
M4-I01..I10 -> M4-A03
M4-I11 + I12 -> M4-A03 + M4-F07

M4-A01 -> M4-F01 + F02
M4-A02 -> M4-F03
M4-A03 + A04 -> M4-F04
M4-A05 + A06 -> M4-F05
M4-A06 -> M4-F06
M4-F02..F07 -> M4-F08

安全边界完成 -> M4-Q01
恢复链路完成 -> M4-Q02
Coding 闭环完成 -> M4-Q03
全部能力 -> M4-Q04
```

## 6. Spike 与架构验证

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M4-S01` | SPIKE | M3-Q03, M0-S03 | agentscope | 已完成：对照 AgentScope 2.0.0 验证 HarnessAgent、DockerFilesystemSpec、Sandbox Lifecycle/Guard、AbstractFilesystem、Plan/Task List、Compaction、Interrupt/Resume 与自定义受控 Tool 的组合；冻结直接复用与包装边界 | [M4-S01 AgentScope 原生 Coding 组合验证记录](../spikes/M4-S01-AgentScope原生Coding组合验证记录.md)与 Docker 集成测试证明受控 Model 可完成读、改、测、暂停和恢复，raw Shell/Filesystem Tool、MCP、动态 Skill 与 Subagent 未注册 |
| `M4-S02` | SPIKE | ADR-002 | infrastructure | 已完成：使用临时 Git Fixture 冻结 Repository Resolver、类型化 Git 参数、Worktree 路径锁、分支命名、部分创建回滚、损坏元数据检测、冷恢复和归档协议 | [M4-S02 Git Worktree 与冷恢复协议验证记录](../spikes/M4-S02-Git-Worktree与冷恢复协议验证记录.md)与 10 个专项场景证明双 Worker 只有一个 Creator，普通失败完整回滚，进程骤停孤儿可冷恢复，目录残留、错误 HEAD、失效 `.git` 指针和符号链接越界均失败关闭，归档中断可幂等收口 |
| `M4-S03` | SPIKE | S02 | infrastructure/web | 已完成：验证 WatchService 事件、Git 周期 Reconcile、不透明 Cursor、Reset 事件、Patch 截断与最终 Diff 固化协议 | [M4-S03 Diff Stream 与最终固化协议验证记录](../spikes/M4-S03-Diff-Stream与最终固化协议验证记录.md)、7 个 Java 场景、共享 JSON/TypeScript 投影、4 个 Vitest 和 2 个 Playwright 场景证明丢失、重复、乱序事件可收敛到 Git 权威结果，桌面/窄屏 Fixture 可稳定回放 |
| `M4-S04` | SPIKE | S01,S02 | all | 已完成：冻结 12 个 Java/Spring Boot Coding 任务、可重复 Git Fixture、Agent 不可见 Judge Pack、AgentScope/Profile/Prompt/Skill/Tool、Sandbox 镜像 Digest、预算、随机参数、RunLock、判定脚本与故障样本 | [M4-S04 Coding Agent 评测协议验证记录](../spikes/M4-S04-Coding-Agent评测协议验证记录.md)与 `evaluation/m4/coding-v1` 证明每个任务显式闭合 Baseline Commit、AllowedPaths、验收参数数组、期望行为和超时，确定性 CI 与真实模型基准使用独立轨道 |

## 7. 领域与数据

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M4-D01` | TASK | S02 | domain/application | 已完成：实现 `RepositoryBinding`、`RepositoryKind.LOCAL_MANAGED`、完整 WorkProject Scope、稳定 `RepositoryKey`、默认分支、启停状态机、乐观版本、审计和 Scope 化 Repository Port；模型不保存或公开宿主路径 | [M4-D01 RepositoryBinding 领域模型](../testing/M4-D01-RepositoryBinding领域模型.md)；9 个专项测试覆盖 Scope、WorkProject 内唯一性、跨项目复用、启停、默认分支、版本、审计、查询隔离和宿主 `Path` 禁止公开 |
| `M4-D02` | TASK | D01, M3-A01 | domain/application | 已完成：实现可选不可变 `CodingTargetSnapshot`、TaskBrief/验收标准、RepositoryBinding Version、Ref/Commit、canonical AllowedPaths、BuildProfile 引用、Revision/Parent、Retry 沿用/换版、Scope 与 SHA-256 闭合 | [M4-D02 CodingTargetSnapshot 领域模型](../testing/M4-D02-CodingTargetSnapshot领域模型.md)；12 个专项测试覆盖不可变快照、Ref 漂移隔离、路径规范化、Hash 防篡改、授权收紧、Retry 沿用/换版、Revision 唯一和非 Coding Task 兼容 |
| `M4-D03` | TASK | D02, S02 | domain/application | 已完成：实现 `ExecutionWorkspace` 聚合、状态机、TaskExecution/Runtime/Worker/Lease/Fencing 所有权、稳定 Workspace/Branch/Worktree/Archive 标识、逻辑 Fingerprint、恢复代次、保留策略、乐观锁和 Scope 化 Repository Port | [M4-D03 ExecutionWorkspace 领域模型](../testing/M4-D03-ExecutionWorkspace领域模型.md)；14 个专项测试覆盖一 attempt 一 Workspace、合法迁移、Pause/Cancel、Retry 隔离、恢复重绑定、Retention、Scope、乐观锁、终态和篡改拒绝 |
| `M4-D04` | TASK | S01,D02 | domain/application | 已完成：实现 `WorkspacePolicy`、`AllowedPathSet`、`BuildProfile`、typed-argv `CommandCatalog`、有界模块/测试选择器、摘要固定 Sandbox 镜像、Sandbox/Workspace 双预算与只能收紧的 `WorkspacePolicyOverlay`；闭合 CodingTarget、TaskExecution、PolicySnapshot 和精确 BuildProfile | [M4-D04 WorkspacePolicy 领域模型](../testing/M4-D04-WorkspacePolicy领域模型.md)；18 个专项测试覆盖路径、命令、选择器、网络、CPU/内存/PID/超时/输出、文件/Diff/写入上限、PolicySnapshot 闭合、Hash 防篡改、Overlay 单调收紧与 Port 契约 |
| `M4-D05` | TASK | D03,S03 | domain/application | 已完成：实现 `DiffArtifact`、`DiffManifest`、`DiffFileEntry`、`DiffGeneration`、完整 Patch Artifact 引用、最终 Hash 和 Workspace 唯一发布 Repository Port | [M4-D05 DiffArtifact 领域模型](../testing/M4-D05-DiffArtifact领域模型.md)；17 个专项测试覆盖基线/交付闭合、AllowedPaths、路径唯一性、Unicode 排序、增删行、二进制、重命名、截断、Preview 排除 Hash、代次单调、防篡改和终态不可变 |
| `M4-D06` | TASK | D03,D04 | domain/application | 已完成：实现 `CommandSpec`、`CommandEvidence`、`TestEvidence`、`AcceptanceResult`、被测 Diff 引用、日志/报告 Artifact 引用、自动成功判定、稳定失败分类、证据 Hash 与 Scope 化 Repository Port | [M4-D06 Command 与 TestEvidence 领域模型](../testing/M4-D06-Command与TestEvidence领域模型.md)；15 个专项场景覆盖命令规格 Hash、镜像、argv、退出码、超时、测试计数、摘要上限、Artifact、被测 Diff、证据顺序、验收闭合、失败优先级、Scope 和不可伪造成功 |
| `M4-D07` | TASK | D02,D05,D06,S04 | domain/application/agentscope | 已完成：实现 RepositoryAnalysisV1、CodeChangeResultV1、TestEvidenceV1、DiffManifestV1、CodingCheckpoint、严格 JSON Schema、原始 Map Decoder 与领域事实复验 | [M4-D07 Coding 结构化输出与 Checkpoint 契约](../testing/M4-D07-Coding结构化输出与Checkpoint契约.md)；专项测试覆盖缺字段、未知字段、路径越界、证据不匹配、Hash、陈旧被测 Diff、版本演进、AgentScope JsonNode 调用和失败关闭 |
| `M4-D08` | TASK | D01..D07 | infrastructure | 已完成：新增 `V14__execution_workspace_and_artifacts.sql`，建立 RepositoryBinding、CodingTargetSnapshot、ExecutionWorkspace、WorkspacePolicy/Overlay、DiffArtifact/DiffFileEntry、CommandEvidence、TestEvidence/验收映射与 CodingCheckpoint，并扩展 Coding Artifact 类型 | [M4-D08 V14 执行工作区与制品迁移](../testing/M4-D08-V14执行工作区与制品迁移.md)；空库、V1、V9、V10、V13→V14 和非默认 `search_path` 迁移通过，复合 Scope、冲突唯一键、状态、索引和分层审计完整 |
| `M4-D09` | TASK | D08 | infrastructure | 已完成：实现 9 个 Coding Repository Port 的 Spring JDBC Adapter、领域 Mapper、Workspace `FOR UPDATE SKIP LOCKED` 有界查询、Artifact/Test/Checkpoint 对象图查询、乐观锁与 Overlay compare-and-set、事务发布和 PostgreSQL 唯一键冲突映射 | [M4-D09 Coding 持久化与锁定查询](../testing/M4-D09-Coding持久化与锁定查询.md)；真实 PostgreSQL 的 8 个场景覆盖完整往返、并发创建、跨 Scope、稳定排序、锁互斥、条件更新和事务回滚；公开 API Cursor、批量 DTO 投影与 N+1 门禁归 M4-A04 |

## 8. Workspace、Sandbox 与 AgentScope

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M4-I01` | TASK | S02,D03 | infrastructure | 已完成：实现无任意命令入口的类型化 GitCommandExecutor，固定参数数组、隔离环境、禁用 Repository Hook、进程树超时终止、有界输出和稳定安全错误分类 | [M4-I01 类型化 GitCommandExecutor](../testing/M4-I01-类型化GitCommandExecutor.md)；10 个专项场景覆盖真实 rev-parse/worktree/status/diff/log/show/commit/archive、Spring 装配、环境隔离、Hook/参数注入、超时与输出洪泛 |
| `M4-I02` | TASK | D01,D02,I01 | infrastructure | 已完成：实现 `ManagedRepositoryResolver` 与 Baseline Preflight，按 canonical Managed Root + `RepositoryKey` 解析受管裸仓库，校验逐级 containment、符号链接、Root/Repository Worker Owner、Binding/Kind、Ref 与固定 Commit；宿主路径保持基础设施内部 | [M4-I02 ManagedRepositoryResolver 与基线 Preflight](../testing/M4-I02-ManagedRepositoryResolver与基线Preflight.md)；16 个专项场景覆盖越界/Option Key、符号链接、缺失仓库、Owner、普通/脏工作仓库、失效 Binding/Ref、移动 Ref、历史快照、错误 Commit 和纯 Server 退让 |
| `M4-I03` | TASK | D03,I01,I02 | infrastructure | 已完成：实现 `WorktreeProvisioner`、JVM + OS 非阻塞路径锁、固定 Worktree/Branch、部分创建补偿、无本地元数据的物理 Fingerprint、`commit-tree` Delivery Commit、Archive Ref、归档与冷恢复清理；公开结果与异常不暴露宿主路径 | [M4-I03 Worktree 生命周期与物理指纹](../testing/M4-I03-Worktree生命周期与物理指纹.md)；17 个专项场景覆盖重复/竞争、普通失败与进程骤停、未知残留、损坏 HEAD/Branch/`.git`、符号链接、Policy、Archive 冲突/中断/清理失败和 Worker-only 装配，回滚完整率 100% |
| `M4-I04` | TASK | S01,D03,D04,I03 | agentscope/infrastructure | 已完成：实现 CrewScope 持有的 TaskExecution 级 Docker Sandbox Factory/Lifecycle，复用 AgentScope `DockerFilesystemSpec` 与 external Sandbox；固定摘要镜像、普通用户、只读根层、Worktree bind mount、无网络、CPU/内存/PID/超时/输出限制、Pause/Recover/Destroy 和 Lease/Fencing Guard | [M4-I04 TaskExecution 级 Docker Sandbox](../testing/M4-I04-TaskExecution级Docker-Sandbox.md)；10 个专项场景覆盖同机写入、安全参数、固定环境、幂等 Provision、PREPARE→RUN、Pause/Resume、新旧 Fencing 隔离、并发/过期 Lease、UTF-8 输出预算、Worker-only 装配和零容器残留 |
| `M4-I05` | TASK | D04,I04 | agentscope | 已完成：实现受控 RepositoryInspectionTool/Session，委托 AgentScope SandboxBackedFilesystem 提供有界 tree/list/read/grep/glob，并补充 AllowedPaths 约束的类型化 Git history/status/text diff；业务路径使用 literal pathspec，不能触发 Git pathspec magic | [M4-I05 受控 RepositoryInspectionTool](../testing/M4-I05-受控RepositoryInspectionTool.md)；每次调用复验 Workspace 与 Lease/Fencing，分页、UTF-8 结果上限、二进制/敏感/符号链接规则和 Worker-only 装配通过，8 个 Tool 均为 Plan Mode 可用的 `readOnly=true` |
| `M4-I06` | TASK | D04,I04 | agentscope | 已完成：实现受控 CodingFilesystemTool/Session，复用 AgentScope SandboxBackedFilesystem 提供 create/edit/patch/move/delete；统一复验 Workspace、Lease/Fencing、AllowedPaths、canonical/symlink、UTF-8、敏感路径、大小写、TOCTOU 与 Workspace 累计写预算 | [M4-I06 受控 CodingFilesystemTool](../testing/M4-I06-受控CodingFilesystemTool.md)；单元、Spring 与真实 Docker 场景覆盖五种操作、跨 Session 预算、失效上下文和固定攻击集，越界实际写入为 0；原生 FilesystemTool 与 raw Shell 未注册 |
| `M4-I07` | TASK | D04,D06,I04 | agentscope/infrastructure | 已完成：实现结构化 SandboxCommandTool、BuildProfile Runner、Workspace 累计命令预算、Command Log Artifact 与 CommandEvidence Writer，只允许固定命令种类、模块和测试选择器 | [M4-I07 结构化 SandboxCommandTool 与 CommandEvidence](../testing/M4-I07-结构化SandboxCommandTool与CommandEvidence.md)；覆盖 Maven/Wrapper/Gradle Wrapper/项目脚本、raw Shell/选择器攻击、超时容器级进程树终止、输出 Artifact、Exit Code 证据、Spring 装配与真实 Docker |
| `M4-I08` | TASK | S03,D05,I01,I03 | infrastructure | 已完成：实现 AllowedPaths 递归 Workspace Diff Watcher、串行 Monitor、Git 权威 Reconciler、有界 RESET/DELTA Event Store、HMAC Cursor、Patch 限额和幂等最终 DiffArtifact Finalizer | [M4-I08 Workspace Diff 与最终 DiffArtifact](../testing/M4-I08-Workspace-Diff与最终DiffArtifact.md)；真实 Git、WatchService、ArtifactStore 与并发测试覆盖丢失/重复/乱序事件、Watcher 重启、基线冲突、未跟踪/二进制/重命名/大 Diff、最终 Hash 和 Worker-only 装配 |
| `M4-I09` | TASK | D05,D06,I07,I08 | infrastructure | 已完成：统一 Patch、构建日志、测试报告 Restricted Artifact 发布、稳定 ID、保留期、整对象校验后 Range、关系元数据闭合 Reader、Tombstone/Purge 与公开摘要 | [M4-I09 Coding Artifact 读写与生命周期](../testing/M4-I09-Coding-Artifact读写与生命周期.md)；文件 Store 覆盖 Hash/大小、写入中断、重复发布、删除、Range、敏感探针和数据库引用闭合 |
| `M4-I10` | TASK | I03,I04,I08,I09,M3-I09 | infrastructure/server | 已完成：在 M3 重新入队事务内标记 Workspace RECOVERING，并在开放 Claim 前完成 Sandbox/命令进程、Worktree、Diff RESET、未知容器、到期 Archive 与 Tombstone Artifact 对账，提供脱敏容量健康 | [M4-I10 Worker 启动资源对账](../testing/M4-I10-Worker启动资源对账.md)；专项覆盖 PROVISIONING/ACTIVE/FINALIZING、失败关闭、重复启动、孤立容器、归档/Purge、Primary 装配、容量上限和 Drain 不清理在途 Workspace |
| `M4-I11` | FEATURE | S01,D07,I04..I09 | agentscope/application | 已完成：实现 CodingSpecialistFactory 与 AgentScopeCodingRuntime，启用固定 Plan/Task List、Compaction、Tool Eviction、State/Snapshot、受控 Skill Bundle 和 Coding Tools | [M4-I11 AgentScope Coding Specialist 运行时](../testing/M4-I11-AgentScope-Coding-Specialist运行时.md)；可控 Model 完成 Skill 加载、分析、计划、跨文件修改、测试、自检与严格 Structured Output，缺失/额外/raw Tool 失败关闭，MCP/Subagent/Reviewer/GitHub 工具均不存在 |
| `M4-I12` | FEATURE | I10,I11,M3-I06..I09 | agentscope/application | 已完成：将 Coding Specialist 接入 Task Orchestrator/StepExecution，完成策略预算、测试失败修复轮次、事件优先 Checkpoint、Pause/Resume/Cancel、跨进程恢复、结果复验与终态映射；Worker 在 Task Agent 与 Specialist 共用的 Lease 窗口内将成员控制请求路由到当前活动 Session，暂停保留 Workspace/Sandbox，终态模型调用数来自累计遥测 | [M4-I12 Coding Specialist Step 执行与恢复](../testing/M4-I12-Coding-Specialist-Step执行与恢复.md)；专项覆盖同 Run 修复、Snapshot/Workspace 恢复、预算耗尽、结果伪造、Pause/Cancel、多模型调用计数、生产控制路由、后继 attempt 和耐久提交顺序 |

## 9. 应用、API 与服务端

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M4-A01` | FEATURE | D01,D09 | application/server | 已完成：提供 Team/WorkProject 级 RepositoryBinding 创建、列表、详情、启停和 Preflight API；ACTIVE Member 可读，内置 Team Owner/Admin 与平台管理员可修改；创建与启用强制受管仓库 Preflight | [M4-A01 RepositoryBinding 管理与 Preflight API](../testing/M4-A01-RepositoryBinding管理与Preflight-API.md)；20 项相关测试覆盖权限、完整 Scope、幂等、事件与 Outbox、强 ETag/If-Match、版本冲突、Worker/Server 装配、路径不披露和稳定错误信封 |
| `M4-A02` | FEATURE | D02,D09,M3-A01 | application/server | 已完成：扩展统一 WorkItem/Conversation Task 委托命令，以可选 CodingTarget 接受 RepositoryBinding、Ref、canonical AllowedPaths、精确 BuildProfile 和验收条件，在首个 attempt 发布前原子固化 Snapshot，并提供成员 BuildProfile 选项与显式 Ref Preflight | [M4-A02 CodingTarget 委托与原子固化](../testing/M4-A02-CodingTarget委托与原子固化.md)；表单与 Conversation 来源固化同一事实模型，专项覆盖非 Coding 兼容、完整 Scope/启用状态、失效 Ref、路径穿越、精确 Profile、幂等 Hash、持久化顺序和公开 DTO 白名单 |
| `M4-A03` | FEATURE | D03,D09,I03,I04,I10,I12 | application/server | 已完成：将 Workspace Provision/Recover/Finalize 接入 Durable Worker PREPARING/RUNNING/Complete 链路，以 V15 持久化文件写预算并在 Tool 开放前恢复精确计数；验证命令解析 Maven 测试汇总并发布 TestReport/TestEvidence，最终结果使用平台权威坐标 | [M4-A03 Coding Workspace 执行生命周期](../testing/M4-A03-Coding-Workspace执行生命周期.md)；专项覆盖恢复激活、跨 Worker 预算、暂停、取消、完成、失败、重试、Worker Shutdown、事务回滚、旧 Fencing、测试证据和最终结果复验 |
| `M4-A04` | FEATURE | D05,D06,D09 | application/server | 已完成：提供 Task 当前/历史 attempt 的 Workspace、Sandbox 预算、Diff Manifest、Command/TestEvidence 与耐久 Coding Result 查询 API，非 Coding Task 使用显式空语义 | [M4-A04 Coding attempt 查询 API](../testing/M4-A04-Coding-attempt查询API.md)；Scope 绑定 Cursor、ACTIVE Member 权限、公开 DTO 白名单和固定次数批量投影通过 |
| `M4-A05` | FEATURE | I08,M3-A05 | application/server | 已完成：将 Workspace 生命周期、Diff RESET/DELTA、TestEvidence 和最终 DiffArtifact 作为安全 DomainEvent 归并到统一 Task Timeline，复用 JSON/SSE Cursor、持续授权和终态排空协议 | [M4-A05 Coding 事件历史与 SSE](../testing/M4-A05-Coding事件历史与SSE.md)；断线追平、410 Cursor、去重、Reset、状态不回退、持续授权复验、事务发布失败与终态关流通过 |
| `M4-A06` | FEATURE | I09,A04 | application/server | 已完成：提供 Task/attempt/证据关系闭合的 Patch、构建日志和测试报告 API，支持标准单 Range、字节分页、类型化下载、大小与并发上限和安全审计 | [M4-A06 Coding Artifact 内容 API](../testing/M4-A06-Coding-Artifact内容API.md)；跨 Scope、未完成/敏感 Artifact、Range、分页、流关闭、并发容量、安全下载名和审计通过 |
| `M4-A07` | TASK | I10,A03 | application/server | 已完成：扩展 Runtime Fleet 与 Actuator，提供本地 Worker Workspace 容量、Sandbox/Watcher/清理健康和既有等待原因；提供 Organization + Environment 级 Reconcile/Archive 运维命令 | [M4-A07 Runtime Fleet 与运维命令](../testing/M4-A07-Runtime-Fleet与运维命令.md)；成员安全摘要、运维明细白名单、平台管理员强授权、幂等 Receipt、DomainEvent/Outbox 审计、server-only 降级与低基数指标通过 |

## 10. 前端 Execution Studio

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M4-F01` | TASK | A01,A02,A04 | web | 已完成：建立 Repository/CodingTarget/Workspace/Diff/Test Gateway、公开类型、Store 与 Task/attempt/Workspace 深链接路由 | [M4-F01 Coding 前端数据层与深链接](../testing/M4-F01-Coding前端数据层与深链接.md)；13 个新增 Vitest 与 197 个前端全量测试覆盖 DTO 白名单、完整 WorkProject Scope 切换、过期请求隔离、Cursor、缓存失效、错误信封和深链接恢复，生产构建通过 |
| `M4-F02` | FEATURE | F01,A01 | web | 已完成：在 WorkProject Settings 交付 RepositoryBinding 管理页，并补齐管理员专用、路径无关的受管 Repository Catalog；支持 Catalog 选 Key、Draft/Existing Preflight、创建、启停、原键重试和版本冲突强制刷新 | [M4-F02 RepositoryBinding 管理页](../testing/M4-F02-RepositoryBinding管理页.md)；203 个前端测试、桌面/390×844 Playwright、生产构建、Catalog 应用测试和 Server Reactor 编译覆盖权限、无 Catalog、失效仓库、路径不披露及全状态 |
| `M4-F03` | FEATURE | F01,A01,A02 | web | 已完成：在 WorkItem 委托表单与 Conversation TaskIntent 确认结果中交付统一 CodingTarget 表单，提供 Repository、Ref、AllowedPaths、BuildProfile、验收条件、通用任务切换、草稿恢复与 Preflight | [M4-F03 CodingTarget 委托表单](../testing/M4-F03-CodingTarget委托表单.md)；权限、服务端默认值、无仓库、失效 Ref、Scope 化恢复、精确 DTO、同键重试和桌面/390×844 双入口通过 |
| `M4-F04` | FEATURE | F01,A03,A04 | web | 已完成：在 Task 详情交付 Execution Studio，聚合不可变基线、Workspace/Sandbox、Coding Agent、计划与当前步骤、最近结构化命令、资源预算和恢复代次，并将 attempt/Workspace 选择固化到深链接 | [M4-F04 Execution Studio 基础观察面](../testing/M4-F04-Execution-Studio基础观察面.md)；Conversation/Control 双入口、Loading/Empty/Error/Forbidden/Recovering/Terminal、attempt 切换、桌面/390×844、Axe 与视觉回归通过 |
| `M4-F05` | FEATURE | F01,A05,A06 | web | 已完成：交付文件树、变更状态、单文件 Patch、累计统计和实时 Diff Stream；复用 Task Cursor 续传，严格应用同 Epoch 直接后继 DELTA，缺口时以 attempt 权威快照 Reset Reconcile；Patch 通过独立授权 Artifact API 分页读取并复验 Size、ETag、SHA-256 与 UTF-8 | [M4-F05 Diff Explorer 与实时 Diff Stream](../testing/M4-F05-Diff-Explorer与实时Diff-Stream.md)；新增/修改/删除/重命名/二进制、大文件树、断线、乱序、403、桌面双栏、390×844 顺序阅读、Axe 与视觉回归通过 |
| `M4-F06` | FEATURE | F01,A04,A06 | web | 已完成：交付 CommandEvidence、TestEvidence 与 Acceptance 只读证据面板，展示退出码、时长、超时、测试统计和失败分类；日志与报告使用固定关系入口按 64 KiB 加载，复验 Range、元数据、Size、SHA-256 与 UTF-8，完整后使用服务端文件名下载 | [M4-F06 Evidence 只读面板与有界 Artifact](../testing/M4-F06-Evidence只读面板与有界Artifact.md)；Cursor、Range、429 后保留已验证分页、失败与超时、敏感内容遮蔽、无终端输入、键盘、桌面/390×844、Axe 与回归通过 |
| `M4-F07` | FEATURE | F04,M3-F05,A03 | web | 已完成：将五阶段 Coding 轨道、Plan Todo、Step Checkpoint、Agent Run/State Snapshot、TestEvidence、修复预算与 M3 Pause/Resume/Cancel/Retry 控件整合到 Execution Studio；当前 attempt 按强版本开放命令，历史 attempt 保持只读 | [M4-F07 Coding 进度与执行控制整合](../testing/M4-F07-Coding进度与执行控制整合.md)；命令执行中控制、409/412 回读、离线、同键重试、恢复后一致性、焦点恢复、桌面/390×844、视觉与 Axe 回归通过 |
| `M4-F08` | HARDENING | F02..F07 | web | 已完成：收口 Repository 与 Execution Studio 全状态、桌面/窄屏响应式、键盘、ARIA Live、Reduced Motion、Axe WCAG 2.2 AA、视觉回归和组件 Story | [M4-F08 前端全状态与质量门禁](../testing/M4-F08-前端全状态与质量门禁.md)；237 项 Vitest、7 个 Story/32 个 Variant、126 项双视口 Playwright、视觉和 Axe 门禁通过，内部路径、Token、State 与 Reasoning 未进入 Web 状态 |

## 11. 测试、评测与发布

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M4-Q01` | HARDENING | D04,I01..I09,A01..A07 | all | 已完成：建立 Repository/Workspace/Sandbox/Tool/Artifact 固定攻击集，覆盖路径穿越、符号链接、命令注入、旧 Lease、跨 Scope、网络、挂载、环境和内容披露；Sandbox 恢复改为单一 Worktree 挂载与环境变量名称白名单的完整契约复验 | [M4-Q01 Coding 执行安全硬化与固定攻击集](../testing/M4-Q01-Security-Hardening.md)；Java 157 项与 Web 37 项专项门禁通过，真实 Docker 无跳过，越界修改、禁止命令、未授权网络、敏感挂载、未授权 Artifact 读取和公开泄漏均为 0，攻击阻断率 100% |
| `M4-Q02` | HARDENING | I10,I12,A05 | all | 已完成：建立 Worker、Agent、Sandbox、Watcher、Worktree、Artifact 与控制重放固定故障集；FINALIZING 可从已验证 Archive Ref 恢复精确 Delivery Tree；同一 CommandEvidence 的不确定提交重试复用 TestEvidence | [M4-Q02 Coding 执行故障注入与恢复](../testing/M4-Q02-Fault-Recovery.md)；55 项固定故障与重放样本恢复率 100%，137 项专项门禁通过，真实 Docker 无跳过，Worktree 普通失败回滚率 100%，孤立容器/进程/锁与重复 Commit/Artifact/TestEvidence 均为 0 |
| `M4-Q03` | HARDENING | S04,I12,F08 | all | 已完成：DeepSeek `deepseek-v4-flash@DeepSeek-V4-Flash-0731` 最终正式矩阵 29 / 36、80.56%，Pass@1 75%，任务成功率 100%，安全合规率 100%，CrewScope 自修改闭环与质量门禁通过；Token 作为成本指标并保留资源失控保护；修复 VERIFY 验收证据导出遗漏并使用追加写入修正链保持历史报告不可变 | [M4-Q03 Coding Agent 质量基线](../testing/M4-Q03-Coding-Agent质量基线.md)；36 次真实运行完整归档，成功任务编译、测试、验收、路径、安全和人工辅助复核全部通过，真实 CrewScope 修改闭环可追溯 |
| `M4-Q04` | HARDENING | Q01,Q02,Q03 | all/docs/ci | 已完成：提供统一 M4 Release Gate，审查领域、V14–V19 迁移、Spring 装配、Git/Workspace、Sandbox、AgentScope、API、前端、M0–M3 回归、依赖和文档 | [M4-Q04 Release Gate](../testing/M4-Q04-Release-Gate.md)；Maven 1517 项、Judge Pack 12 源、Vitest 237 项、Playwright/视觉/Axe 126 项及安全、故障、评测、构建、依赖、链接和格式门禁全部通过 |

## 12. 纵向实施波次

| 波次 | 任务 | 可演示结果 |
|---|---|---|
| W0 契约验证 | S01–S04 | AgentScope 安全工具面、Worktree、Diff 和评测口径冻结 |
| W1 仓库目标 | D01–D02、D08–D09、A01–A02、F01–F03 | 管理员注册受管仓库，成员通过对话或表单创建 Coding Task |
| W2 Workspace | D03–D06、I01–I04、A03–A04、F04 | Worker 为 attempt 创建独立 Worktree/Sandbox，Execution Studio 可观察 |
| W3 代码执行 | D07、I05–I07、I11–I12、F06–F07 | Coding Specialist 修改真实代码并生成命令与测试证据 |
| W4 Diff 与恢复 | I08–I10、A05–A07、F05、F08 | 实时 Diff、Artifact、重启恢复、运维诊断和完整前端状态闭环 |
| W5 发布 | Q01–Q04 | 安全、故障、评测与 Release Gate 关闭 M4 |

前端不等待全部后端完成后集中开发。每个波次先冻结公开 DTO、错误和事件契约，后端提供真实 API 或固定 Contract Fixture，前端在同一波次完成 Store、页面、状态和自动化测试。

## 13. Release Gate

M4 完成需要同时满足：

1. 每个 TaskExecution 创建独立 Workspace、Worktree 和分支，跨 Task/attempt 不能复用；
2. Repository Ref 在 Task 创建时解析为不可变基线 Commit，后续分支移动不改变执行输入；
3. 双 Worker、重复请求和部分创建故障只产生一个活动 Workspace，失败回滚完整率 100%；
4. Worktree 元数据损坏、Worker/Sandbox 重启和 Watcher 事件丢失后可恢复或明确失败关闭；
5. Sandbox 使用固定镜像、普通用户、资源限制和默认无网络，CI/MVP 验收不使用本地进程 Sandbox；
6. AllowedPaths 外实际修改、禁止命令实际执行、未授权网络连接和敏感挂载数量均为 0；
7. 旧 Lease、旧 Fencing Owner、撤销责任或失效 Task Token 不能读取、修改、执行或固化 Workspace 结果；
8. Diff Stream 经 Reconcile 后与 Git 权威结果一致，最终 DiffArtifact Hash 可复验；
9. 每个成功 Coding Task 都有匹配基线、Diff、CommandEvidence、TestEvidence 和验收结果；
10. Coding Specialist 固定任务集成功率 `>=70%`，成功样本编译、测试和验收标准全部通过；
11. 固定故障样本恢复率 `>=95%`，无孤立容器、进程、锁、Workspace、重复 Commit 或重复 Artifact；
12. Pause/Resume/Cancel/Retry 在 Workspace、Agent、命令和 Diff 层保持一致且可审计；
13. Conversation Mode 与 Control Mode/Execution Studio 展示并控制同一服务端事实；
14. 前端不提供任意交互 Shell，不展示宿主路径、Token、原始 AgentState 或内部 Reasoning；
15. M0–M3 全量回归、V14 迁移、后端、前端、Docker、安全、故障、评测、依赖和文档门禁全部通过。

## 14. 开工与提交顺序

推荐按以下节点实施和审查：

1. `M4-S01` 至 `M4-S04`：冻结 AgentScope、Worktree、Diff 与评测协议；
2. `M4-D01` 至 `M4-D09`：冻结 Repository、CodingTarget、Workspace、Policy 和 Artifact 数据契约；
3. `M4-I01` 至 `M4-I10`：完成 Git、Worktree、Sandbox、受控 Tool、Diff 与恢复基础设施；
4. `M4-I11` 至 `M4-A07`：完成 Coding Specialist、Task Orchestrator、应用与 API 闭环；
5. `M4-F01` 至 `M4-F08`：随对应后端波次完成 Repository 管理与 Execution Studio；
6. `M4-Q01` 至 `M4-Q04`：完成安全、故障、评测与 Release Gate。

每个提交节点先整体 Review，先修正文档与契约，再修正实现并运行相应门禁。任务完成证据保存到 `docs/spikes`、`docs/testing` 或 `docs/evaluations`，文件名以任务 ID 开头。
