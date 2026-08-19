# M4-I11 AgentScope Coding Specialist 运行时

> 实现日期：2026-08-19
>
> AgentScope 基线：Java 2.0.0
> 涉及模块：`crewscope-agentscope`、`crewscope-server`

## 1. 交付结果

M4-I11 已实现 `CodingSpecialistFactory` 与 `AgentScopeCodingRuntime`。运行时接收平台准备完成的 Specialist RuntimeSession、固定受控 Toolkit 和任务指令，完成一次 AgentScope 原生 Coding 循环并返回：

- 严格 `CodeChangeResultV1`；
- 同一次调用完成后的 AgentState 安全点；
- 稳定 Agent ID、AgentScope User ID 与 Session ID。

Task Orchestrator、StepExecution、Checkpoint 持久化、Pause/Resume/Cancel 和跨进程恢复已由 M4-I12 接入；Workspace/Lease 生产资源主链路由 M4-A03 接入。

## 2. AgentScope 原生能力

`CodingSpecialistFactory` 显式启用：

- `HarnessAgent` ReAct 循环；
- Plan Mode：`plan_enter`、`plan_write`、`plan_exit`；
- Task List：`todo_write`；
- 独立 Compaction Model 和固定消息阈值；
- Tool Result Eviction；
- `AgentStateStore`；
- JsonNode 严格 Structured Output；
- 只读 classpath Skill Repository；
- 固定 Skill Filter 与 `load_skill_through_path`。

Skill Bundle 为 `java-spring-v1`，资源 SHA-256 固定为：

```text
56534716ce9dff791e29845ec996e5928f85c14ce51e4e6037f9f028af79d8b4
```

每次创建 Agent 都会复验资源 Hash、Skill 数量、Skill ID 和 Repository 只读属性。Workspace Skill、Skill Manage、Skill Curator、Skill Promotion 和外部 Skill Repository 均未装配。

## 3. 固定 Tool 面

模型可见的业务 Tool 精确为：

```text
repository_tree
repository_list
repository_read
repository_grep
repository_glob
repository_git_history
repository_git_status
repository_git_diff
coding_create
coding_edit
coding_patch
coding_move
coding_delete
coding_run_command
```

AgentScope 增加的固定运行时 Tool 精确为：

```text
plan_enter
plan_write
plan_exit
todo_write
load_skill_through_path
```

Factory 在构建前校验 14 个业务 Tool 的完整集合和只读元数据，在构建后校验 Plan/Todo Tool，在调用完成后再次校验 Skill Tool。任何缺失、额外或错误只读分类都会失败关闭。

以下能力没有注册：

```text
execute
read_file
write_file
edit_file
grep_files
glob_files
list_files
MCP Tool
agent_spawn
动态 Subagent
Reviewer Tool
GitHub Tool
skill_manage
propose_skill
wait_async_results
```

## 4. 上下文治理

Compaction 使用固定独立 Model、消息阈值、保留尾部数量和关闭长期 Memory Flush/Offload 的配置。它压缩 Agent 工作上下文，领域计划、Workspace、Diff、CommandEvidence 和 TestEvidence 保持平台权威事实。

Tool Result Eviction 保留为命令大结果的上下文宽度保护。Repository Tool 已有分页和大小上限，Coding 写 Tool 返回有界摘要，Skill Loader 返回固定受信内容，因此这些 Tool 进入排除集。完整命令日志继续由 M4-I07 `CommandEvidenceWriter` 写入 Restricted Artifact，Eviction 文件只用于 AgentScope 当前 Session 的上下文保护。

AgentState 在 Structured Output 完成后从相同 `(userId, sessionId, agent_state)` 槽读取并复验身份。返回对象的 `toString()` 对 JSON 内容固定脱敏。

AgentScope 2.0.0 的本地 Workspace 默认使用 `USER` 隔离。CrewScope 的 Coding Principal 可执行多个 Task，因此 Coding Specialist 显式配置 `IsolationScope.SESSION`。同一 AgentProfile 版本继续复用稳定 Workspace 根，每个耐久 AgentScope Session 使用独立 Plan 文件命名空间，跨 Task 的 Plan 与恢复文件不会互相覆盖。

## 5. Structured Output

运行时直接复用 M4-D07：

```text
CodingStructuredOutputSpecs.CODE_CHANGE_RESULT
  -> AgentScope JsonNode Schema 调用
  -> StrictStructuredOutputDecoder
  -> CodeChangeResultV1
```

缺字段、未知字段、错误类型、越界长度和错误格式继续由严格 Schema 拒绝。Workspace、CodingTarget、DiffArtifact 和 TestEvidence 的权威事实复验由 M4-I12 完成。

## 6. 可控模型验证

`AgentScopeCodingRuntimeM4I11IntegrationTest` 使用确定性 Model 完成：

```text
加载固定 Skill
  -> 建立 Todo
  -> 进入 Plan Mode
  -> 读取两个文件
  -> 写入并退出 Plan
  -> 修改两个文件
  -> 执行 TEST 命令
  -> 检查 Git Diff
  -> 完成 Todo
  -> 返回严格 CodeChangeResultV1
```

测试同时证明 Compaction 实际调用、命令大结果发生 Eviction、AgentState 可读取、同一 Coding Principal 的不同 AgentScope Session 使用独立 Plan Workspace、Skill 内容进入模型 Prompt、最终 Tool 面保持固定，以及缺失 Tool 和 raw `execute` Tool 在模型调用前被拒绝。

Spring Worker 装配提供：

- `CodingSpecialistRuntimeProperties`；
- `CodingSpecialistConfigurationSource`；
- `CodingSpecialistSkillBundle`；
- `CodingSpecialistFactory`；
- `AgentScopeCodingRuntime`。

`SERVER` 部署不创建这些 Bean，`ALL` 与 `WORKER` 部署创建同一套 Coding Runtime Bean。

## 7. 验证命令

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-server -am \
  '-Dtest=*M4I11*,TaskWorkerConfigurationM3I09Test' \
  -Dsurefire.failIfNoSpecifiedTests=false test

node scripts/check-doc-links.mjs
git diff --check
```

验证结果：

- M4-I11 专项与 Worker 装配门禁：7 项测试通过；
- 全仓 Maven 回归：1,345 项测试通过，失败 0，错误 0，跳过 0；
- 文档链接检查：167 个 Markdown 文件通过；
- Git 差异格式检查通过；
- 测试结束后无 CrewScope 受管 Sandbox 容器遗留。

M4-I12 已将该 Runtime 接入 Specialist Step 协调与 Durable Execution Store，持久化当前返回的 AgentState 安全点，并完成恢复、控制与领域结果复验。M4-A03 将 Authority Gateway 接入真实 Workspace Tool Session 和 Durable Worker 资源主链路。
