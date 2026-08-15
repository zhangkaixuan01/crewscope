# M3-A01 交给 Agent 处理应用与 API

## 目标

从一个当前可见且版本匹配的 WorkItem 创建可被耐久调度器领取的 Task。创建边界固化成员确认的目标、验收标准、责任、执行 Agent、策略和 Provider 授权事实，并与事件和幂等回执原子提交。

## 已交付能力

- `POST /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/tasks`，要求 `Idempotency-Key` 和强 `If-Match`；
- `TaskBrief` 独立保存目标与有序验收标准，提供长度/数量边界和规范 SHA-256；
- Owner 或当前 USER Executor 可以执行命令，其他可见成员不能委托；
- 在 WorkItem 责任锁内复验版本、完整 Scope、唯一 Owner、有效 Executor、Agent Principal/Profile 和当前 ProviderBinding；
- Personal Agent 或 Team Agent 可以担任 Task Orchestrator，Specialist Agent 只用于后续 Step；
- 可选 Conversation Message 经过当前可见性和 PRIVATE 历史截止点复验；
- 原子创建 Task、首个 READY TaskExecution、责任快照、PolicySnapshot、SafetyEnforcementOverlay、可选 ConversationTaskLink、DomainEvent、Outbox、Conversation Event 和 CommandReceipt；
- `TASK_DELEGATED_TO_AGENT` 固化 WorkItem 版本、TaskBrief/Hash、来源 Message、执行 Assignment/Profile、Policy/Overlay 和 ProviderBinding；
- AgentScope 规划输入消费 TaskBrief，并把用户内容作为转义后的数据区；
- V11 为 Task 增加 `objective` 和 `acceptance_criteria`，并从既有 WorkItem 回填历史行。

## 失败关闭

以下情况不创建任何 Task 事实：WorkItem ETag 过期、当前责任链不完整、调用者不是 Owner/Executor、AgentProfile 禁用或跨 Scope、Task Orchestrator 类型不匹配、Agent 不是当前 Executor、Conversation Message 不可见、ProviderBinding/Connection/Grant 失效、用户级 Binding 不属于执行 Agent Owner，以及任一持久化、事件或回执步骤失败。

## 验证

- `TaskBriefTest`、`TaskTest`：输入规范化、不可变、Hash、边界和 Task 生命周期保持；
- `TaskAgentRuntimeSessionTest`：Personal/Team Task Orchestrator 与 Specialist Step 边界；
- `ProviderBindingResolverTest`：显式 Binding 当前事实复验及撤权关闭；
- `AgentTaskCreationServiceM3A01Test`：完整创建图、Owner/Executor 权限、幂等重放、版本冲突、Profile 类型关闭和事件载荷；
- `TaskControllerTest`：202 回执、强 ETag、幂等 Header、字段与路由校验；
- `AgentScopeTaskRuntimeM3I06IntegrationTest`：TaskBrief 进入受控规划输入；
- `M3TaskRuntimePersistenceIntegrationTest`：TaskBrief JSONB 持久化与领域重建。

## 下一项

`M3-A02` 已完成 Task 集合、详情、attempt、Plan、Step、AgentRun、Interrupt、Snapshot 和 Runtime Facts 查询 API；`M3-A03` 已完成受信 Worker Command Port。下一项为 `M3-A04` 成员 Pause、Resume、Cancel 和 Retry 命令。
