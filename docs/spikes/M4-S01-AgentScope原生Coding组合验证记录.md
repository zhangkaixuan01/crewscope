# M4-S01 AgentScope 原生 Coding 组合验证记录

> 验证对象：AgentScope Java `v2.0.0`（`44c304ec84d5fbd8588c1af8bc71b1edb9663380`）<br>
> CrewScope 模块：`crewscope-agentscope`<br>
> 验证日期：2026-08-16

## 1. 验证结论

AgentScope Java 2.0.0 可以作为 CrewScope Coding Specialist 的原生 Agent 内核。组合验证已完成以下闭环：

```text
Todo 初始化
  -> 进入 Plan Mode
  -> 受控只读 Tool 读取代码
  -> Plan Mode 阻断写 Tool
  -> 写入计划
  -> plan_exit 产生人工确认中断
  -> Sandbox 调用窗口释放
  -> 确认后恢复同一 Agent Session
  -> 受控写 Tool 修改 Worktree
  -> 受控验收 Tool 编译并验证代码
  -> Todo 全部完成
  -> 返回最终结果
```

Compaction 在长 Tool 循环中实际触发，Plan Mode 和 Todo 状态通过 AgentState 保持。宿主 Git 最终只观察到目标 Java 文件发生预期变更。

模型可见 Tool 面固定为：

```text
repository_read_target
repository_apply_fixture_change
repository_run_acceptance
todo_write
plan_enter
plan_write
plan_exit
```

原生 `FilesystemTool`、`ShellExecuteTool`、MCP、动态 Skill、Skill 自写、Subagent、动态 Subagent 和异步等待 Tool 均未进入模型 Tool 面。

## 2. 验证配置

```text
model = ScriptedModel
compactionModel = 独立 ScriptedModel
image = maven:3.9.6-eclipse-temurin-17
imageDigest = sha256:29a1658b1f3078e07c2b17f7b519b45eb47f65d9628e887eac45a8c5c8f939d4
containerWorkspace = /workspace
repositoryMount = <host-temp-repository>:/workspace/repository:rw
network = none
isolationScope = SESSION
stateStore = InMemoryAgentStateStore
guardKey = task-execution-m4-s01
compactionTrigger = 6 messages
compactionKeep = 2 messages
```

Fixture 使用固定 Git 基线，目标文件为：

```text
src/main/java/io/crewscope/probe/Greeting.java
```

受控变更为：

```diff
-        return "before-m4";
+        return "after-m4";
```

验收 Tool 只执行平台固定命令，不接受 Shell 字符串参数：

```text
javac 编译固定目标
  -> grep 校验固定期望
  -> 输出 ACCEPTANCE_OK
```

## 3. AgentScope 2.0.0 源码映射

| 能力 | 源码位置 | M4 决策 |
|---|---|---|
| Harness 组合入口 | `agentscope-harness/.../HarnessAgent.java:1008` | 直接复用 Builder 和 ReAct 生命周期 |
| Todo Task List | `agentscope-core/.../ReActAgent.java:4066`、`TodoTools.java:49`、`TaskReminderMiddleware.java:54` | 直接复用 Agent 临时 Todo；领域事实仍为 PlanVersion 与 StepExecution |
| Plan Mode | `HarnessAgent.java:1794`、`PlanModeTools.java:66`、`PlanModeMiddleware.java:58` | 直接复用进入、计划、退出和只读 Tool 过滤 |
| Compaction | `HarnessAgent.java:1572`、`CompactionMiddleware.java:57`、`ConversationCompactor.java:57` | 直接复用，使用固定独立模型和 PolicySnapshot 参数 |
| Interrupt/Resume | `ReActAgent.java:1519`、`ReActAgent.java:1618`、`HarnessAgent.java:481` | 直接复用 Agent 内中断；CrewScope 负责幂等 Resume、Checkpoint 和领域状态迁移 |
| AgentStateStore | `HarnessAgent.java:1387`、`ReActAgent.java:3641` | 复用 M3 Redis 热状态和 AgentStateSnapshot 二级恢复协议 |
| Docker 声明 | `DockerFilesystemSpec.java:31` | 复用镜像、WorkspaceSpec、网络和 Docker 参数声明 |
| Sandbox 调用生命周期 | `SandboxLifecycleMiddleware.java:51` | 复用文件系统注入机制；TaskExecution 级容器所有权由 CrewScope 包装 |
| Sandbox Guard | `SandboxExecutionGuard.java:59`、`SandboxManager.java:66` | 只用于 AgentScope 自管理 Sandbox 调用窗口；不能替代 Lease/Fencing |
| AbstractFilesystem | `AbstractFilesystem.java:44` | 复用底层 list/read/write/edit/grep/glob/delete/move 接口，不直接暴露原生 Tool |
| 原生文件 Tool 注册 | `HarnessAgent.java:2262` | 固定调用 `disableFilesystemTools()` |
| 原生 Shell Tool 注册 | `HarnessAgent.java:2265` | 固定调用 `disableShellTool()` |
| MCP 注册 | `HarnessAgent.java:2300` | 固定调用 `disableToolsConfig()`，不传入 MCP Client |
| 动态 Skill | `HarnessAgent.java:1733` | 固定调用 `disableDynamicSkills()` 和 `disableDefaultWorkspaceSkills()` |
| Subagent | `HarnessAgent.java:1881` | M4 固定关闭；独立 Reviewer Specialist 在 M5 通过平台编排实现 |
| Coding 示例 | `agentscope-examples/agents/agentscope-codingagent/.../CodingAgentFactory.java:43` | 只参考 Prompt、Todo、Compaction 和 Sandbox 组合，不复制其开放 Tool 面与外部能力 |

源码映射以 AgentScope Java `v2.0.0` 为基线。升级 AgentScope 时必须重新执行本 Spike，并检查 Builder 默认值和自动注册 Tool 的变化。

## 4. 直接复用边界

以下能力由 AgentScope 提供实现，CrewScope 只提供版本化配置和领域映射：

1. `HarnessAgent` ReAct 循环、Model 调用、Tool 调度和流式事件；
2. `todo_write` 与每轮 Todo Reminder；
3. Plan Mode 状态、计划 Tool 和只读 Tool 过滤；
4. Compaction、Tool Result Eviction 和上下文重建；
5. AgentState 的 Session 隔离、保存与载入；
6. Permission Asking 产生的中断和 `ConfirmResult` 恢复；
7. `DockerFilesystemSpec`、`WorkspaceSpec`、`BindMountEntry` 和 Sandbox 文件系统代理；
8. `AbstractFilesystem` 的底层文件访问实现。

这些状态用于 Agent 连续工作。任务成功、计划选择、步骤状态、命令证据和最终 Diff 由 CrewScope 领域对象证明。

## 5. CrewScope 包装边界

### 5.1 TaskExecution 级 Sandbox

AgentScope 自管理 Docker Sandbox 的默认释放路径为：

```text
persist state
  -> sandbox.stop()
  -> sandbox.shutdown()
  -> docker stop --time=30
  -> docker rm --force
```

本次首次调用中断后容器被删除，恢复调用根据保存的 SandboxState 发现原容器不存在并创建新容器。Bind Mount 中的 Worktree 变更仍然存在，容器内部未挂载内容不具备同等耐久性。

M4-I04 因此实现 `TaskExecutionSandboxFactory/Lifecycle`：

- Worker 在 `PREPARING` 创建 Sandbox，在整个 TaskExecution 活动期持有；
- Pause 停止 Agent 和在途命令，保留 Worktree，并按策略停止或保留容器；
- Resume 先复验 Workspace、镜像、挂载、Policy、Lease 和 Fencing，再重新注入 Sandbox；
- Cancel、终态和恢复失败统一终止容器与进程并执行孤立资源对账；
- Worktree 始终是代码事实源，容器层不保存唯一业务事实。

### 5.2 Guard、Lease 与 Fencing

`SandboxExecutionGuard` 覆盖 AgentScope 自管理 Sandbox 的 `acquire -> call -> release` 窗口。本次两次 Agent 调用均获得并释放同一个 Session Key，计数为：

```text
guardEnter = 2
guardClose = 2
```

AgentScope 源码中的 external Sandbox 和 external SandboxState 路径会绕过该 Guard。M4 的 TaskExecution 级外部 Sandbox 必须由 CrewScope 在注入前完成 Workspace Owner、Task Token、Lease 和 Fencing 校验，并在每次受控 Tool 调用时重新校验。AgentScope Guard 作为进程内组合能力，不能作为平台所有权证明。

### 5.3 文件与命令 Tool

原生 `FilesystemTool` 直接暴露通用读写接口，原生 `ShellExecuteTool` 接受原始命令字符串。M4 使用三类包装：

```text
RepositoryInspectionTool
  -> AllowedPathSet
  -> 有界参数
  -> AbstractFilesystem read/list/grep/glob

CodingFilesystemTool
  -> AllowedPathSet + canonical/symlink 复验
  -> 文件数量、大小、累计写入和 Diff 限额
  -> AbstractFilesystem write/edit/delete/move

SandboxCommandTool
  -> CommandKind + BuildProfile + 有界选择器
  -> 固定参数数组或平台生成命令
  -> CommandEvidence + 日志 Artifact
```

模型不能提交任意 Shell 字符串、宿主路径、Docker 参数或 Git 管理命令。

### 5.4 Plan、Todo 与 Checkpoint

Plan Mode 文件和 Todo 是 Agent 工作状态。CrewScope 采用以下事实层次：

```text
AgentScope Plan/Todo
  -> 候选工作状态
  -> CodingCheckpoint 快照

CrewScope PlanVersion/StepExecution
  -> 经过校验和发布的领域事实
  -> Execution Studio 与恢复依据
```

Compaction 可以重写对话上下文，不会替代 PlanVersion、StepExecution、Workspace Fingerprint、Diff Generation 或 TestEvidence。Checkpoint 在 Compaction、Pause、外部等待和关键命令前保存 AgentStateSnapshot 与平台事实引用。

### 5.5 Tool 面收口

Builder 关闭原生能力后，Harness 仍会因 Sandbox Workspace MessageBus 自动注册 `wait_async_results`。M4 的 Factory 在 Agent 构建后显式移除该 Tool，并对最终 Tool 名称集合执行白名单断言。未来 AgentScope 升级新增自动 Tool 时测试必须失败关闭。

## 6. Interrupt/Resume 结果

`plan_exit` 作为 PLAN 到 BUILD 的交接点生成 `PERMISSION_ASKING`：

1. 第一次调用返回 `ToolCallState.ASKING`；
2. 写 Tool 在 Plan Mode 中被 `PlanModeMiddleware` 阻断，执行次数保持 0；
3. 源文件仍为 `before-m4`；
4. 第二次调用携带 `ConfirmResult(true, pendingPlanExit)`；
5. AgentState 恢复并退出 Plan Mode；
6. 写 Tool 和验收 Tool 各执行一次；
7. 三个 Todo 均进入 `COMPLETED`；
8. 最终返回 `m4-s01-controlled-coding-complete`。

Fixture 使用 `PermissionMode.BYPASS` 仅消除其他固定 Tool 的人工交互，同时为 `plan_exit` 配置显式 ASK Rule。生产运行不使用该 Fixture 权限配置，由 WorkspacePolicy、PlatformExecutionContext 和受控 Tool 自检共同决定。

## 7. Compaction 结果

Compaction 使用独立确定性 Model，消息达到 6 条时触发并保留最近 2 条。验证期间实际完成多轮：

```text
7 messages -> 1 summary + 2 tail
```

压缩后仍可完成 Plan Exit、恢复、修改、验收和 Todo 收口。生产配置需要固定：

- Compaction Model/Profile 版本；
- Trigger、Reserved 和 Keep 参数；
- Tool Result Eviction 上限；
- Summary Prompt 版本；
- Compaction 前 AgentStateSnapshot 与 CodingCheckpoint；
- 大 Tool 输出先进入 RuntimeArtifact，再向 Agent 提供有界摘要。

## 8. 自动化证据

测试类：

```text
crewscope-agentscope/src/test/java/io/crewscope/agentscope/
  HarnessAgentM4S01CodingCompositionIntegrationTest.java
```

执行命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-agentscope -am \
  -Dtest=HarnessAgentM4S01CodingCompositionIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

验证结果：

```text
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Docker 或固定镜像不可用时测试使用 JUnit Assumption 标记为 Skipped。M4 Release Gate 必须预拉取 Digest 固定镜像，并校验该测试没有 Skipped。

## 9. 冻结决策

M4-S01 冻结以下决策：

1. 使用 AgentScope 原生 `HarnessAgent` 实现 Coding Specialist，不包装外部 Coding Agent CLI；
2. 直接复用 Plan Mode、Todo、Compaction、AgentState 和 Interrupt/Resume；
3. Worktree 是代码事实源，AgentScope Plan/Todo 是 Agent 工作状态；
4. TaskExecution Sandbox 生命周期、Lease/Fencing 和恢复所有权由 CrewScope 实现；
5. 所有代码读写和命令能力通过 CrewScope 受控 Tool 包装；
6. M4 不注册 raw Filesystem/Shell、MCP、动态 Skill、Skill 自写和 Subagent；
7. Coding Specialist Factory 对最终 Tool 名称集合执行严格白名单；
8. M4-D04 已按本记录实现 WorkspacePolicy、AllowedPathSet、BuildProfile、CommandCatalog、SandboxResourceBudget 和只能收紧的 Overlay，固定 Tool Key 为 `command.mavenTest` 与 `command.mavenVerify`。

M4-S01 至 M4-S04、M4-D01 至 M4-D09 已完成，当前下一项为 M4-I01。M4-D08 已建立 V14 Coding 数据结构、完整 Scope 外键、冲突唯一键、分层审计、Artifact 类型和恢复查询索引；M4-D09 已完成 Coding Repository Port、JDBC Mapper、Workspace 锁定查询、条件更新、Artifact 对象图事务与稳定冲突映射。
