# M4：AgentScope 原生 Coding Agent 执行清单

> 对应总计划：[CrewScope 实施计划](../CrewScope-实施计划.md) M4<br>
> 前置条件：M3 Release Gate 通过，ADR-002 已接受，M0-S03 Docker Sandbox 验证通过<br>
> 目标周期：4–5 周，按纵向波次推进<br>
> 目标结果：成员从 WorkItem 或 Conversation 指定受管仓库目标后，Coding Specialist 可在独立 Worktree 与 Docker Sandbox 中分析、修改、测试并交付可恢复、可观察、可审计的 Diff 与 TestEvidence<br>
> 当前进度：已拆分为 44 个任务，下一项为 `M4-S01`（2026-08-16）

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
| Sandbox Lifecycle、State Store、Execution Guard | 管理 TaskExecution 级 Sandbox 获取、恢复与互斥 | Guard 绑定 Workspace、Lease 与 Fencing；旧 Owner 不能恢复或执行命令 |
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
M4-A05 -> M4-F05
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
| `M4-S01` | SPIKE | M3-Q03, M0-S03 | agentscope | 对照 AgentScope 2.0.0 验证 HarnessAgent、DockerFilesystemSpec、Sandbox Lifecycle/Guard、AbstractFilesystem、Plan/Task List、Compaction、Interrupt/Resume 与自定义受控 Tool 的组合；冻结直接复用与包装边界 | 可控 Model 在 Docker Sandbox 内完成读、改、测、暂停、恢复；原生 raw Shell/Filesystem Tool、MCP、动态 Skill 与 Subagent 未注册；输出源码映射和最小适配记录 |
| `M4-S02` | SPIKE | ADR-002 | infrastructure | 使用临时 Git Fixture 冻结 Repository Resolver、类型化 Git 参数、Worktree 路径锁、分支命名、部分创建回滚、损坏元数据检测、冷恢复和归档协议 | 双 Worker 并发创建只有一个成功；注入进程退出、目录残留、错误 HEAD、失效 `.git` 指针和符号链接越界后均回滚或失败关闭 |
| `M4-S03` | SPIKE | S02 | infrastructure/web | 验证 WatchService 事件、Git 周期 Reconcile、不透明 Cursor、Reset 事件、Patch 截断与最终 Diff 固化协议 | 丢弃、重复、乱序文件事件后，客户端投影与 `git diff` 权威结果一致；桌面/窄屏 Fixture 可稳定回放 |
| `M4-S04` | SPIKE | S01,S02 | all | 冻结 10–20 个 Java/Spring Boot Coding 任务、仓库 Fixture、模型/Profile/Prompt/Skill/Tool 版本、Sandbox 镜像 Digest、预算、随机参数、判定脚本与故障样本 | 评测清单版本化；每个样本有基线 Commit、AllowedPaths、验收命令、期望行为和超时；确定性 CI 集与真实模型基准分开记录 |

## 7. 领域与数据

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M4-D01` | TASK | S02 | domain/application | 实现 RepositoryBinding、RepositoryKind.LOCAL_MANAGED、状态、默认分支、稳定 Repository Key、版本和 Team/WorkProject Scope | 领域测试覆盖 Scope、唯一性、启停、版本、默认分支和禁止公开宿主路径 |
| `M4-D02` | TASK | D01, M3-A01 | domain/application | 实现 CodingTargetSnapshot，绑定 Task、RepositoryBinding 版本、基线 Ref/Commit、AllowedPaths、BuildProfile、验收标准与 Hash | 测试覆盖不可变快照、Ref 漂移隔离、路径规范化、授权收紧、Retry 沿用/换版和非 Coding Task 兼容 |
| `M4-D03` | TASK | D02, S02 | domain/application | 实现 ExecutionWorkspace 聚合、状态机、Worker/Runtime/TaskExecution 所有权、Worktree/分支稳定标识、Fingerprint、恢复代次和保留策略 | 测试覆盖一 attempt 一活动 Workspace、合法迁移、Pause/Cancel 语义、Retry 隔离、乐观锁和终态不可变 |
| `M4-D04` | TASK | S01,D02 | domain/application | 实现 WorkspacePolicy、AllowedPathSet、BuildProfile、CommandCatalog、SandboxResourceBudget 和只能收紧的运行时 Overlay | 测试覆盖路径、命令、网络、资源、文件/Diff/输出上限与 PolicySnapshot 闭合 |
| `M4-D05` | TASK | D03,S03 | domain/application | 实现 DiffArtifact、DiffManifest、DiffFileEntry、DiffGeneration、Patch Artifact 引用和最终 Hash | 测试覆盖基线/交付闭合、路径唯一性、增删行、二进制、重命名、截断、代次单调和终态不可变 |
| `M4-D06` | TASK | D03,D04 | domain/application | 实现 CommandEvidence、TestEvidence、AcceptanceResult、日志/报告 Artifact 引用和稳定失败分类 | 测试覆盖命令规格 Hash、镜像、退出码、超时、测试计数、摘要上限、证据顺序与不可伪造成功 |
| `M4-D07` | TASK | D02,D05,D06,S04 | domain/application/agentscope | 实现 RepositoryAnalysisV1、CodeChangeResultV1、TestEvidenceV1、DiffManifestV1 与 CodingCheckpoint 契约 | JSON Schema/Structured Output 测试覆盖缺字段、未知字段、路径越界、证据不匹配、Hash、版本演进和失败关闭 |
| `M4-D08` | TASK | D01..D07 | infrastructure | 新增 `V14__execution_workspace_and_artifacts.sql`，建立 RepositoryBinding、CodingTargetSnapshot、ExecutionWorkspace、DiffArtifact、DiffFileEntry、CommandEvidence 与 TestEvidence | 空库、V1、V9、V10、V13→V14 和非默认 `search_path` 迁移通过；复合 Scope、唯一约束、状态、索引和审计字段完整 |
| `M4-D09` | TASK | D08 | infrastructure | 实现 JPA/JDBC Repository、Mapper、Workspace 锁定查询、Artifact/Test 分页查询和幂等条件更新 | 真实 PostgreSQL 覆盖往返、并发创建、Cursor、跨 Scope、条件更新、批量查询、查询计划和事务回滚 |

## 8. Workspace、Sandbox 与 AgentScope

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M4-I01` | TASK | S02,D03 | infrastructure | 实现类型化 GitCommandExecutor，所有宿主管理命令使用参数数组、固定环境、超时、输出上限和稳定错误分类 | 临时仓库覆盖 rev-parse、worktree、status、diff、log、show、commit；恶意 Ref/路径/参数不能形成额外命令 |
| `M4-I02` | TASK | D01,D02,I01 | infrastructure | 实现 ManagedRepositoryResolver 与基线 Preflight，按 Repository Root + Key 解析 canonical path、校验所有权/裸库/工作树、Ref 和 Commit | 越界路径、符号链接、失效 Binding、移动 Ref、脏源仓库和错误 Commit 失败关闭 |
| `M4-I03` | TASK | D03,I01,I02 | infrastructure | 实现 Worktree Provisioner、路径锁、分支、部分创建回滚、Workspace Fingerprint、本地交付 Commit、归档与清理 | 并发、重复、崩溃点、残留目录、损坏元数据、清理失败与冷恢复 Fixture 通过，回滚完整率 100% |
| `M4-I04` | TASK | S01,D03,D04,I03 | agentscope/infrastructure | 实现 TaskExecution 级 Docker Sandbox Factory/Lifecycle，配置固定镜像、普通用户、只读根层、bind mount、网络、CPU/内存/PID/超时和 Guard | Docker 集成测试证明同机挂载、资源限制、无网络、无敏感环境、旧 Lease/Fencing 不能恢复 Sandbox 且无残留容器 |
| `M4-I05` | TASK | D04,I04 | agentscope | 实现受控 RepositoryInspectionTool，委托 AgentScope AbstractFilesystem 提供 tree/list/read/grep/glob，并补充类型化 Git history/status/diff 读取 | 每次调用复验 Context；分页、结果上限、二进制与敏感文件规则通过；Plan Mode 只保留只读能力 |
| `M4-I06` | TASK | D04,I04 | agentscope | 实现受控 CodingFilesystemTool，支持 create/edit/patch/move/delete，统一执行 AllowedPaths、canonical/symlink、文件数/大小和累计写入检查 | 表驱动攻击集覆盖 `..`、绝对路径、符号链接、大小写、重命名、删除和 TOCTOU；越界实际写入为 0 |
| `M4-I07` | TASK | D04,D06,I04 | agentscope/infrastructure | 实现结构化 SandboxCommandTool、BuildProfile Runner 与 CommandEvidence Writer，只允许固定命令种类、模块和测试选择器 | 禁止 raw Shell；覆盖 Maven/Wrapper/Gradle Wrapper/项目脚本白名单、超时、进程树终止、输出 Artifact 和 Exit Code 证据 |
| `M4-I08` | TASK | S03,D05,I01,I03 | infrastructure | 实现 Workspace Diff Watcher、Git Reconciler、Diff Event Store、Patch 限额和最终 DiffArtifact Finalizer | 丢失/重复/乱序事件、Watcher 重启、基线变化、二进制/重命名/大 Diff 和最终 Hash 测试通过 |
| `M4-I09` | TASK | D05,D06,I07,I08 | infrastructure | 实现 Patch、构建日志、测试报告 Artifact Writer/Reader、Tombstone、保留期和公开摘要生成 | 文件 ArtifactStore 覆盖 Hash/大小、写入中断、重复发布、删除、Range 读取、敏感内容探针和数据库元数据闭合 |
| `M4-I10` | TASK | I03,I04,I08,I09,M3-I09 | infrastructure/server | 实现 Worker 启动 Workspace/Sandbox/Watcher 对账、RECOVERING、命令进程终止、孤立资源关闭、归档清理与容量健康 | 在 PROVISIONING/ACTIVE/FINALIZING 退出后可恢复或明确失败；重复对账无重复 Commit/Artifact，Worker Drain 保留在途 Workspace |
| `M4-I11` | FEATURE | S01,D07,I04..I09 | agentscope/application | 实现 CodingSpecialistFactory 与 AgentScopeCodingRuntime，启用固定 Plan/Task List、Compaction、Tool Eviction、State/Snapshot、受控 Skill Bundle 和 Coding Tools | 可控 Model 完成分析、计划、跨文件修改、测试、自检与 Structured Output；raw Tool/MCP/Subagent/Reviewer/GitHub 工具均不存在 |
| `M4-I12` | FEATURE | I10,I11,M3-I06..I09 | agentscope/application | 将 Coding Specialist 接入 Task Orchestrator/StepExecution，完成预算、测试失败修复轮次、Checkpoint、Pause/Resume/Cancel、跨进程恢复、结果复验与终态映射 | 同 Run Resume、Snapshot 恢复、Workspace 恢复、预算耗尽、测试失败、结果伪造、Cancel 和后继 attempt 测试通过 |

## 9. 应用、API 与服务端

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M4-A01` | FEATURE | D01,D09 | application/server | 提供 Team/WorkProject 级 RepositoryBinding 创建、列表、详情、启停和 Preflight API；仅管理员可修改 | WebTestClient 覆盖权限、Scope、幂等、版本冲突、路径不披露和稳定错误信封 |
| `M4-A02` | FEATURE | D02,D09,M3-A01 | application/server | 扩展 WorkItem/Conversation 委托，接受 RepositoryBinding、Ref、AllowedPaths、BuildProfile 与验收条件，原子固化 CodingTargetSnapshot | 对话确认与传统表单创建同一事实；跨 Team Binding、失效 Ref、越权路径和重复提交被拒绝 |
| `M4-A03` | FEATURE | D03,D09,I03,I04,I10,I12 | application/server | 将 Workspace Provision/Recover/Finalize 接入 Durable Worker PREPARING/RUNNING/Complete 链路，保持 Lease/Fencing/Task Token 条件写入 | 创建、领取、暂停、恢复、取消、完成、重试、Worker 重启和事务失败闭环通过 |
| `M4-A04` | FEATURE | D05,D06,D09 | application/server | 提供 Task/attempt 级 Workspace、Sandbox 摘要、Diff Manifest、Command/TestEvidence 与 Coding Result 查询 API | Cursor、批量查询、当前/历史 attempt、权限、公开 DTO 白名单和 N+1 检查通过 |
| `M4-A05` | FEATURE | I08,M3-A05 | application/server | 提供 Workspace/Diff/Test 耐久历史与 SSE Cursor，将实时事件归并到 Task Timeline 并在最终 Artifact 后收口 | 断线追平、410 Cursor、去重、Reset、状态不回退、持续授权复验与终态关流测试通过 |
| `M4-A06` | FEATURE | I09,A04 | application/server | 提供受权 Patch、构建日志和测试报告内容 API，支持 Range/分页、内容类型、下载名和大小上限 | 跨 Scope、路径注入、未完成 Artifact、敏感 Artifact、Range、并发下载和审计测试通过 |
| `M4-A07` | TASK | I10,A03 | application/server | 扩展 Runtime Fleet/Actuator，提供 Workspace 容量、Sandbox/Watcher/清理健康、等待原因和运维 Reconcile/Archive 命令 | 成员只见安全摘要，运维命令强授权/幂等/审计；指标保持低基数且不包含宿主路径 |

## 10. 前端 Execution Studio

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M4-F01` | TASK | A01,A02,A04 | web | 建立 Repository/CodingTarget/Workspace/Diff/Test Gateway、公开类型、Store 与 Task/attempt/Workspace 深链接路由 | Vitest 覆盖 DTO 白名单、Scope 切换、过期请求隔离、Cursor、缓存失效和错误信封 |
| `M4-F02` | FEATURE | F01,A01 | web | 在 WorkProject Settings 交付 RepositoryBinding 管理页，从服务端受管 Repository Catalog 选择 Key，支持 Preflight、创建、启停和版本冲突刷新 | 管理员权限、无 Catalog、失效仓库、路径不披露、Loading/Empty/Error/Forbidden 和窄屏交互通过 |
| `M4-F03` | FEATURE | F01,A01,A02 | web | 在 WorkItem 委托表单和 Conversation TaskIntent 确认中提供 Repository、Ref、AllowedPaths、BuildProfile 与验收条件选择 | 权限、默认值、Preflight、无仓库、失效 Ref、表单恢复、幂等重试和窄屏交互通过 |
| `M4-F04` | FEATURE | F01,A03,A04 | web | 在 Task 详情交付 Execution Studio，展示基线、Workspace/Sandbox、Coding Agent、计划、当前命令、资源预算和恢复代次 | Conversation/Control 双入口读取同一事实；Loading/Empty/Error/Forbidden/Recovering/Terminal 与 attempt 切换通过 |
| `M4-F05` | FEATURE | F01,A05 | web | 交付文件树、变更状态、单文件 Patch、累计统计和实时 Diff Stream，支持 Cursor 续传与 Reset Reconcile | 新增/修改/删除/重命名/二进制/大 Diff、断线、乱序、桌面双栏和窄屏顺序阅读通过 |
| `M4-F06` | FEATURE | F01,A04,A06 | web | 展示 Command/TestEvidence、退出码、时长、测试统计、有界日志和 Artifact 下载；命令面板保持只读 | 不提供任意终端输入；日志分页/Range、超时、失败、敏感内容隐藏和键盘导航通过 |
| `M4-F07` | FEATURE | F04,M3-F05,A03 | web | 将 Coding 阶段、Todo、Checkpoint、测试修复轮次与 M3 Pause/Resume/Cancel/Retry 控件整合到 Execution Studio | 命令执行中控制、冲突刷新、离线、同键重试、恢复后事实一致和焦点恢复通过 |
| `M4-F08` | HARDENING | F02..F07 | web | 收口 Repository 与 Execution Studio 全状态、桌面/窄屏响应式、键盘、ARIA Live、Reduced Motion、Axe WCAG 2.2 AA、视觉回归和组件 Story | Vitest coverage、Histoire、Playwright 双视口、视觉和 Axe 门禁通过；内部路径、Token、State、Reasoning 不进入 Web 状态 |

## 11. 测试、评测与发布

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M4-Q01` | HARDENING | D04,I01..I09,A01..A07 | all | 建立 Repository/Workspace/Sandbox/Tool/Artifact 固定攻击集，覆盖路径穿越、符号链接、命令注入、旧 Lease、跨 Scope、网络、挂载、环境和内容披露 | 越界文件实际修改、禁止命令实际执行、未授权网络连接、凭证/宿主路径泄漏均为 0；攻击阻断率 100% |
| `M4-Q02` | HARDENING | I10,I12,A05 | all | 注入 Worker、Agent、Sandbox、Watcher 在 PROVISIONING/ACTIVE/FINALIZING 退出，Worktree 损坏、事件丢失、命令挂起、Artifact 写入中断和重复控制 | 固定故障样本恢复率 `>=95%`，Worktree 创建失败回滚率 100%，无孤立容器/进程/锁，无重复 Commit/Artifact/TestEvidence |
| `M4-Q03` | HARDENING | S04,I12,F08 | all | 执行冻结 Coding 评测集并归档模型、环境、预算、成功率、编译/测试、验收、路径、安全、Token/成本/耗时和人工判定 | 端到端成功率 `>=70%`；成功任务编译、测试和验收标准全部通过；至少一个真实 CrewScope 修改走完整闭环 |
| `M4-Q04` | HARDENING | Q01,Q02,Q03 | all/docs/ci | 执行 M4 Release Gate，审查领域、迁移、Spring 装配、Git/Workspace、Sandbox、AgentScope、API、前端、M3 回归、依赖和文档 | 后端、V14 迁移、Docker、固定评测、安全、故障、Vitest、Playwright、Axe、视觉、依赖、链接和格式全部通过；形成版本化报告 |

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
