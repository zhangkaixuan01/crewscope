# M3-I06 AgentScope Task Orchestrator

> 状态：COMPLETED
> 日期：2026-08-15
> 范围：`crewscope-application`、`crewscope-agentscope`

## 交付内容

M3-I06 将 M3-I05 `TaskExecutionRuntime` Port 接到 AgentScope Java 2.0.0，并保持 Conversation Runtime 不变。

- `TaskAgentFactory` 按 `AgentProfileId + AgentProfileVersion` 缓存版本固定的 HarnessAgent；
- Task Agent 使用稳定 Agent ID 和 TaskAgentRuntimeSession 的稳定 `userId/sessionId`；
- 开启 AgentScope Plan Mode 与 Todo，关闭文件、Shell、Subagent、Memory、动态 Skill 和客户端 Tool 配置；
- 精确 Toolkit 只保留 Plan/Todo、只读 `validate_task_plan` 和三个 `fixture.*`；
- `ControlledTaskPlanParser` 解析固定 Step、依赖、Capability、Tool 与 critical 字段，并要求至少一个 VALIDATION Step；
- `AgentScopeTaskPlanAdapter` 把 Plan/Todo 映射为未发布候选，Todo 不改变领域事实；
- `TaskPlanPublicationService` 在单事务重新加载当前事实，创建 PlanVersion、切换当前计划并创建 StepExecution；
- `AgentScopeTaskRuntime` 映射有限事件流，执行模型/Tool/Token/时长预算，传播 Pause/Resume/Cancel；
- Resume 可以在原实例继续，也可以重建 HarnessAgent 后从 AgentStateStore 恢复 Pending `plan_exit`；
- 每个 `fixture.*` 调用复验 Task Token Tool 范围，Step Session 继续复验当前 Plan Step requiredTools；
- Runtime 拥有上游订阅，下游传输取消不产生业务 Pause 或 Cancel。

## 受控计划格式

```markdown
# Controlled Task Plan

- `inspect` | ANALYSIS | Inspect input | deps=- | capabilities=PLAN | tools=fixture.inspect | critical=true
- `execute` | IMPLEMENTATION | Produce result | deps=inspect | capabilities=PLAN | tools=fixture.execute | critical=true
- `validate` | VALIDATION | Validate result | deps=execute | capabilities=STRUCTURED_OUTPUT | tools=fixture.validate | critical=true
```

`validate_task_plan` 只提供模型修正反馈。PlanVersion 发布边界重新解析完整 Markdown，并重新加载 Task、TaskExecution、PolicySnapshot、SafetyEnforcementOverlay、执行 Principal 与父 PlanVersion。模型调用过校验 Tool 不构成发布授权。

## 安全边界

M3 只运行 `fixture.inspect`、`fixture.execute` 和 `fixture.validate`。三个 Tool 都是进程内、确定性、无网络、无文件和无 Provider 副作用的观察 Fixture。GitHub、飞书和其他 Provider 写 Tool 即使被注入 Toolkit 也会在 Factory 构建阶段失败；运行时未知 Tool 事件继续失败关闭。

Plan/Todo 是 AgentScope 认知状态。Todo 只形成 `TodoSummaryItem`，不调用 TaskRepository、TaskExecutionRepository 或 StepExecutionRepository。领域 Step 状态由后续 Worker/Application 命令根据耐久事件推进。

## 自动化验证

新增 10 个测试方法：

1. 非法依赖计划返回修正结果，修正计划解析为三个有序 Step；
2. Todo 状态和可选 Step key 映射为未发布候选；
3. 同 Profile 版本复用 Agent，不同版本创建独立 Agent；
4. Profile 错配和 Provider 写 Tool 注入失败关闭；
5. 单事务按 PlanVersion、TaskExecution、StepExecution 顺序提交；
6. 过期 Profile 候选和非 Fixture Tool 在发布前拒绝；
7. 可控 Model 覆盖 Plan Mode、非法计划修正、Todo、计划发布、审批和三个 Fixture Step；
8. 新 Runtime/Factory 实例恢复 Pending 审批并继续执行；
9. 同一 Runtime 在 WAITING 释放 Lease 后仅接受更新 Fencing Token 的 Resume；
10. 模型调用预算、Pause 和 Cancel 产生唯一安全终态。

专项命令：

```bash
/Users/zhangkaixuan/Downloads/apache-maven-3.9.6/bin/mvn \
  -pl crewscope-application,crewscope-agentscope -am \
  -Dtest='TaskPlanPublicationServiceM3I06Test,AgentScopeTaskFactoryM3I06Test,AgentScopeTaskRuntimeM3I06IntegrationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

完整回归命令：

```bash
/Users/zhangkaixuan/Downloads/apache-maven-3.9.6/bin/mvn clean verify
```

验证结果：7 个 Maven 模块全部构建成功，共执行 913 个测试，0 Failure、0 Error、0 Skipped。文档链接检查覆盖 121 个 Markdown 文件并通过，已跟踪与未跟踪文件的差异格式检查通过。

## 后续边界

M3-I07 将当前安全运行事件映射为耐久 AgentRun、AgentInterrupt、RuntimeArtifact 与 DomainEvent。M3-I06 不提前实现事件持久化、Snapshot Artifact 或 Worker 执行循环。
