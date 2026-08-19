# M4-I12 Coding Specialist Step 执行与恢复

## 1. 交付范围

M4-I12 将原生 Coding Specialist 接入 M3 的 `TaskExecutionRuntimeFacts`、`StepExecution`、`AgentRun`、耐久事件与 AgentState Snapshot 协议，形成 `CodingSpecialistStepRuntime`。Workspace、Sandbox、Tool Session 和最终 Diff 固化通过 `CodingSpecialistAuthorityGateway` 接入，M4-A03 提供该 Gateway 的生产资源生命周期实现。

核心实现：

- `AgentScopeCodingRuntime` 按 `(userId, sessionId)` 注册活动调用，支持精确 Interrupt、Snapshot 与 Restore；
- 安全点从同一 AgentState 和 Agent Workspace 读取真实 Plan/Todo，生成 `CodingCheckpointWorkState`；
- `CodingSpecialistStepRuntime` 强制 `SPECIALIST + StepExecution` 边界，在同一 Run、Segment、Session 和 attempt 内执行；
- 测试失败按 `WorkspacePolicy.operationBudget.maxTestRepairRounds` 继续修复；
- `DurableCodingSpecialistExecutionStore` 按“耐久事件 → AgentState Snapshot → CodingCheckpoint → StepCheckpoint”顺序提交；
- 最终成功由 `CodingOutputValidator` 对 RepositoryAnalysis、CodingTarget、Workspace、DiffArtifact 和成功 TestEvidence 复验；
- Pause/Cancel 使用 AgentScope 定向 Interrupt，并映射到 AgentRun 与 StepExecution 终态；
- Resume 先执行 Workspace 对账，再恢复 Snapshot，随后使用同一 Specialist Session 接续；
- 后继 attempt 使用新的 TaskExecution、Step、RuntimeSession、AgentRun 与 AgentState 槽。

## 2. 身份与恢复闭包

Task Orchestrator 与 Coding Specialist 使用独立稳定 Agent namespace：

```text
Task Agent:   crewscope-task-<profileId>-v<version>
Coding Agent: crewscope-coding-<profileId>-v<version>
```

`DurableAgentStateSnapshotService` 根据 `TaskAgentSessionPurpose` 校验 namespace，避免 Task State 与 Specialist State 交叉恢复。

恢复顺序固定为：

```text
M4-I10 Workspace reconcile
  -> Snapshot 候选校验与恢复
  -> AgentState 写入原 AgentScope Session
  -> 使用 M3 控制面已创建的同 AgentRun RESUME Segment 执行
```

M4-I12 消费 `TaskExecutionRuntimeFacts` 中已经闭合的 Run/Segment，不创建 AgentRun Segment。M3 控制面负责创建和持久化 RESUME Segment，Coding Runtime 负责在该 Segment、Lease/Fencing 与 Specialist Session 边界内继续执行。

每个 CodingCheckpoint 闭合：

```text
CodingTargetSnapshot
+ ExecutionWorkspace/WorkspacePolicy
+ AgentRun/Segment/PlanVersion/StepExecution
+ Agent Plan/Todo
+ 当前 DiffManifest/TestEvidence
+ CURRENT AgentStateSnapshot
```

## 3. 预算与结果裁决

首轮调用完成后读取平台权威 TestEvidence。失败或缺失证据进入修复轮次，每轮沿用当前 attempt、Run 与 Session，并重新读取 Git、Diff 和 Test 事实。修复次数达到 WorkspacePolicy 上限后写入 `TEST_REPAIR_BUDGET_EXHAUSTED`。

成功 TestEvidence 出现后，平台复验模型输出中的 Workspace ID/Fingerprint、CodingTarget ID/Revision/Hash、RepositoryAnalysis Hash、DiffArtifact ID/Hash 和 TestEvidence ID/Hash。任一声明不匹配写入 `CODING_RESULT_INVALID`，Step 与 AgentRun 不进入成功态。

## 4. 控制与终态

| 场景 | AgentScope | AgentRun | StepExecution |
|---|---|---|---|
| 成功 | 调用完成并保存安全点 | `COMPLETED` | `SUCCEEDED` |
| 测试预算耗尽 | 保存最后安全点 | `FAILED` | `FAILED_FINAL/FAILED_RETRYABLE` 按失败策略 |
| Pause | 定向 Interrupt + Snapshot | `INTERRUPTED` | `WAITING(AGENT_INTERRUPT)` |
| Resume | Workspace 对账 + Restore | 使用 M3 已创建的同 Run `RESUME` Segment | `READY -> RUNNING` |
| Cancel | 定向 Interrupt + Snapshot | `CANCELLED` | `CANCELLED` |
| 后继 attempt | 新 Session 槽 | 新 Run | 新 StepExecution |

## 5. 自动验证

专项测试：

```bash
/Users/zhangkaixuan/Downloads/apache-maven-3.9.6/bin/mvn \
  -pl crewscope-agentscope -am \
  -Dtest=AgentScopeCodingRuntimeM4I11IntegrationTest,CodingSpecialistStepRuntimeM4I12Test,DurableCodingSpecialistExecutionStoreM4I12Test \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：`12 / 12` 通过，其中 M4-I12 的 `9 / 9` 场景覆盖成功复验、同 Run 修复、预算耗尽、结果伪造、Workspace/Snapshot 恢复、Pause、Cancel、后继 attempt 隔离和事件先于 Snapshot 的提交顺序。

Spring 装配验证：

```bash
/Users/zhangkaixuan/Downloads/apache-maven-3.9.6/bin/mvn \
  -pl crewscope-server -am \
  -Dtest=TaskWorkerConfigurationM3I09Test \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：`4 / 4` 通过。`all/worker` 创建 Durable Coding Execution Store；M4-A03 的 `CodingSpecialistAuthorityGateway` 存在时创建 `CodingSpecialistStepRuntime`；纯 `server` 不装配 Worker 执行能力。

## 6. 后续衔接

M4-A03 将 `CodingSpecialistAuthorityGateway` 连接到 `ManagedWorktree`、`ManagedTaskExecutionSandbox`、Repository/Coding/Command Tool Session、Workspace Diff Monitor 与 Finalizer，并把当前 Step Runtime 放入 Durable Worker 的 PREPARING/RUNNING/Finalize 主链路。
