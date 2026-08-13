# M3-S02：AgentScope Task Orchestrator 映射验证记录

> 状态：VERIFIED<br>
> 日期：2026-08-12<br>
> AgentScope 基线：`v2.0.0`（`44c304ec`）<br>
> 关联决策：[ADR-010](../adr/ADR-010-ExecutionRuntime调用与流协议.md)、[ADR-011](../adr/ADR-011-AgentScopeNativeRuntime实例与恢复协议.md)

## 1. 验证目标

M3-S02 验证 AgentScope Task Agent 与 CrewScope Task Orchestrator 的能力映射，固定 Plan Mode、TodoTools、RuntimeContext、Interrupt/Resume、AgentState 恢复与领域事实之间的边界。

验收场景包括：

- Task Session 通过稳定 `RuntimeContext.userId/sessionId` 隔离和恢复；
- Plan Mode 在 AgentState 中持久化，并在新 HarnessAgent 实例中恢复；
- Plan Mode 阻止未授权的变更 Tool；
- `plan_write` 生成 ProposedPlan，`plan_exit` 产生 Permission ASK 中断；
- 审批 Resume 在同一 Pending Tool 上退出 Plan Mode；
- TodoTools 在 AgentState 中保存全量替换型运行时 Todo；
- 新 Agent 实例读取既有 Plan Mode、Pending Interrupt、Todo 和上下文；
- CrewScope 防腐层只输出 ProposedPlan 与 TodoSnapshot，不直接创建 PlanVersion、StepExecution 或 AgentRun；
- M2 Runtime 在 M3 Task Port 尚未接通前继续不声明 `PLAN` 能力。

## 2. AgentScope 2.0.0 源码结论

### 2.1 Plan Mode

`HarnessAgent.enablePlanMode()` 注册 `plan_enter`、`plan_write`、`plan_exit` 和 `PlanModeMiddleware`。`PlanModeContextState` 是 AgentState 的持久字段，保存 `planActive` 与 `currentPlanFile`。

Plan Mode 激活时，Middleware 只允许只读 Tool、Plan 控制 Tool、`todo_write` 和受控内部协作 Tool。其他变更 Tool在 Acting 前收到合成 `DENIED` 结果，不会实际执行。CrewScope M3 关闭 Shell、Filesystem、Subagent、Memory 和 Dynamic Skill，因此 Task Spike 只保留 Plan/Todo 与无副作用 Fixture Tool。

`plan_write` 通过 WorkspaceManager 覆盖当前 `plans/PLAN.md`。`plan_exit` 的 Permission Self-check 固定返回 `ASK`；同意后 Tool 才调用 `PlanModeManager.exit`，拒绝时继续保持 Plan Mode。

### 2.2 TodoTools

`todo_write` 使用全量替换语义，把 Model 提交的完整列表写入 `AgentState.tasksContext`。状态只有 `pending`、`in_progress`、`completed`，最多一个 `in_progress`。内容相同的 Todo 在重写时尽力保留 AgentScope Todo ID。

Todo 是 Agent 的运行时认知和提示输入。它不是 CrewScope Task、PlanStep 或 StepExecution，不拥有平台状态迁移权。

### 2.3 Session 与恢复

ReActAgent 每次调用按 `(userId, sessionId)` 从 AgentStateStore 重新加载 `agent_state`，调用结束或 Permission ASK 中断后保存完整 AgentState。Pending Tool、PlanModeContext、TasksContext 和对话上下文可以由使用相同 Store、Agent 配置和 Session Key 的新 HarnessAgent 实例续接。

恢复 Pending Tool 时，AgentScope 按当前 Agent `name` 查找最近的 Assistant Tool Call。Task Agent 的运行时身份必须稳定包含 `name`、`agentId`、`userId`、`sessionId` 和 Agent 版本；Worker 换实例或换节点时使用完全相同的版本化配置重建 HarnessAgent。

Plan Mode 对变更 Tool 生成 `DENIED` ToolResult 并写入 AgentState。中间 ReAct 轮次产生的合成 ToolResult 事件在 2.0.0 中不保证进入最终公开事件列表，CrewScope 以持久 `DENIED` 结果和 Tool 零执行作为恢复与审计依据。

Runtime-only InterruptControl 不序列化。进程退出后的业务恢复不能依赖内存中断句柄，必须依赖 PostgreSQL AgentRun/AgentInterrupt、AgentStateSnapshot 和当前 Lease 重建；该二级恢复由 M3-S03/I08 验证。

## 3. CrewScope 映射

| AgentScope 事实 | CrewScope M3 映射 | 规则 |
|---|---|---|
| `plans/PLAN.md` | ProposedPlan | 规范化 Markdown、计算 SHA-256，只作为候选；公开值对象构造时复验内容、路径与 Hash 不变量 |
| Plan Mode active | AgentRun planning phase | 运行态；不直接改变 TaskExecution 状态 |
| `plan_exit` Permission ASK | AgentInterrupt | 保存 Pending Tool 身份并生成平台不透明 Interrupt Token |
| Resume ConfirmResult | AgentInterrupt resolution | 平台先校验 Lease、PolicySnapshot、权限、Token 与幂等，再构造 ConfirmResult |
| `tasksContext` | TodoSnapshot | 运行时进度摘要，可审计但不直接推进 StepExecution |
| 校验通过的 ProposedPlan | PlanVersion | Application Service 生成不可变版本、Step 和 PolicySnapshot 引用 |
| HarnessAgent 一段调用 | AgentRun Segment | PostgreSQL 分配 Run/Segment 序号并保存唯一终态 |
| AgentStateStore 检查点 | AgentStateSnapshot 输入 | Redis 为热状态；ArtifactStore 快照与 PostgreSQL 检查点提供二级恢复 |

PlanVersion 发布前至少校验：Task/Execution Scope、责任、PolicySnapshot、预算、可用 RuntimeCapability、受控 Step 类型、唯一 Step Key、顺序/依赖无环、验收标准覆盖和禁止 Provider 写操作。AgentScope Todo 的新增、删除、重排和完成都不能绕过这些校验。

## 4. 安全检查点

```text
模型输出 Plan/Todo
  -> AgentScope Schema/Tool 校验
  -> CrewScope ProposedPlan/TodoSnapshot 防腐层
  -> Lease + Fencing + Task Token + PolicySnapshot 复验
  -> Plan 领域校验
  -> 原子发布 PlanVersion / AgentRun checkpoint / DomainEvent
```

Permission ASK 到达时保存 AgentRun Segment、Pending Tool 摘要和 AgentStateSnapshot 引用，再提交 `WAITING_USER`。Resume 必须先验证当前 AgentInterrupt、TaskExecution Lease、Fencing Token、PolicySnapshot 和成员权限，成功后才进入 AgentScope。

## 5. 验证矩阵

| 场景 | 预期证据 | 状态 |
|---|---|---|
| Plan 写入 | Model 调用 `plan_write`，生成规范 ProposedPlan 与 Hash | 通过 |
| Plan 只读 | Plan Mode 中变更 Tool 持久化 `DENIED` 结果且执行计数为零 | 通过 |
| Exit 中断 | `plan_exit` 先产生 RequireUserConfirm，再以 Permission ASK 停止 | 通过 |
| 新实例恢复 | 同名、同 Agent ID 的新 HarnessAgent 读取相同 Plan/Pending Tool/Session | 通过 |
| Resume | 同意后退出 Plan Mode，继续同一 Pending Tool | 通过 |
| Todo 进度 | `todo_write` 形成稳定 TodoSnapshot，最多一个进行中 | 通过 |
| 领域隔离 | Snapshot 不生成 PlanVersion、StepExecution 或 AgentRun ID | 通过 |
| 能力披露 | M2 Runtime 仍不声明 `PLAN`、Sandbox、Subagent | 通过 |

## 6. 实现边界

M3-S02 交付映射结论、最小防腐层和受控 HarnessAgent 测试。正式 Task Execution Port 由 M3-I05 实现，Task Orchestrator 与版本化 Task Agent Factory 由 M3-I06 实现，AgentRun/Interrupt 耐久映射由 M3-I07 实现。

## 7. 验证结果

实现产物：

- `AgentScopeTaskPlanningSnapshotMapper`：规范化 Plan、计算并复验 SHA-256、限制 Plan/Todo 大小并拒绝多个进行中 Todo；公开快照值对象在直接构造时继续执行相同不变量校验，输出不含 CrewScope 领域 ID 的不可变候选快照。
- `HarnessAgentM3S02TaskOrchestratorIntegrationTest`：使用确定性 Model 覆盖 Plan/Todo、变更拦截、Permission ASK、新实例恢复、Resume、防腐层负向校验和能力披露。

验证命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-agentscope -am \
  -Dtest=HarnessAgentM3S02TaskOrchestratorIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：5 个测试全部通过。

模块回归：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-agentscope -am test
```

结果：Domain 199 个、Application 178 个、AgentScope Adapter 75 个测试全部通过，共 452 个测试。
