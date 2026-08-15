# M3-I05 Task Execution Runtime Port

> 日期：2026-08-15<br>
> 状态：已完成<br>
> 适用范围：TaskExecutionRuntime、服务端事实闭合、AgentRun Segment 事件流、Pause/Resume/Cancel 和 RuntimeCapabilities

## 1. 交付结果

M3-I05 在 M2 Conversation `ExecutionRuntime` 旁新增 Task 专用 Port：

```java
public interface TaskExecutionRuntime extends ExecutionRuntimeProfile {
    TaskExecutionHandle executeTask(TaskExecutionRequest request);
    CompletionStage<TaskExecutionControlResult> controlTask(
        TaskExecutionControlRequest request);
}
```

两个 Port 共用 `ExecutionRuntimeProfile` 的稳定 Descriptor 和 RuntimeCapabilities。M2 `ExecutionRuntime` 的 Conversation Invoke、Resume 和 Cancel 方法签名保持不变，`AgentScopeNativeRuntime` 继续只实现已接通的 Conversation Port。

## 2. 服务端事实闭合

`TaskExecutionRuntimeFacts` 只接收服务端加载或验证后的不可变领域事实：

- 当前开放 Task 与当前可执行 TaskExecution attempt；
- 当前 PlanningContext、PolicySnapshot、SafetyEnforcementOverlay 和可选 PlanVersion；
- 当前未释放 ExecutionLease、Runtime、Worker、ClaimTokenHash 和 FencingToken；
- `DurableTaskTokenAuthenticator` 验证后的 TaskTokenExecutionContext；
- ACTIVE TaskAgentRuntimeSession；
- 当前 RUNNING AgentRun 和 ACTIVE Segment；
- 可选 StepExecution 及其 Plan、Policy、Safety 和 Execution Principal 指针。

构造器逐项比对 Organization、Team、Workspace、WorkProject、Task、TaskExecution、attempt、Lease、Runtime、Worker、Fencing、Principal、AgentProfile、AgentRun、Segment、Policy、Safety、Plan 和 Step。Runtime Adapter 不从请求 Body 重建身份、授权或执行归属。

Session 被禁用后不能开始或恢复执行；显式 Pause/Cancel 仍可传播给已经运行的调用，使配置变化不会阻止安全停止。

## 3. TaskExecutionHandle 和事件协议

每个 Handle 固定一个：

```text
TaskExecution + attempt + AgentRun + Segment sequence + Segment kind
```

事件序号在 Segment 内从 1 连续递增，第一项必须是匹配当前 `INVOKE/RESUME/RECOVERY` 的 `Started`，流以一个终态结束并随后 `onComplete`：

```text
COMPLETED | INTERRUPTED | PAUSED | CANCELED | FAILED
```

非终态事件覆盖 TextDelta、ThinkingSummary、StructuredOutput、PlanChanged、ToolStarted、ToolResult、Progress、ArtifactCreated、StatusChanged 和 UsageReported。Thinking 只允许安全摘要；Tool 参数、原始结果和私有推理不进入协议。大结果只携带 RuntimeArtifactId。

`TaskExecutionEventPublisher` 保证：

- 一个 Handle 只能订阅一次；
- 正向 demand 原样传递给上游；
- 非法 demand 取消上游并失败；
- Scope、attempt、Run、Segment、序号、首事件和唯一终态不一致时失败关闭；
- Publisher 协议损坏使用 `onError`，业务失败使用带安全分类的 `Failed` 终态。

## 4. 业务控制

`TaskExecutionControlRequest` 使用非 Nil Control Request ID 表达幂等 `PAUSE/RESUME/CANCEL`。控制请求继续闭合当前 Lease、Fencing、Task Token 和 AgentRun，不使用 `Flow.Subscription.cancel()` 代替业务动作。

稳定结果为：

```text
ACCEPTED
ALREADY_APPLIED
ALREADY_TERMINAL
STALE_OWNER
NOT_FOUND
```

HTTP/SSE 断开只取消当前传输订阅，不产生 Pause 或 Cancel。M3-A04 将负责成员命令、强 ETag 和领域请求态；M3-I06 负责把控制传播到 AgentScope 安全点。

## 5. RuntimeCapabilities

Task Scheduler 的基础能力要求固定增加：

```text
TASK_EXECUTION
STREAMING
DURABLE_EVENT_STREAM
PAUSE_RESUME
CANCEL
SESSION_STATE
```

PolicySnapshot 的 PLAN、Structured Output、Interrupt Resume、External Tool、Sandbox、Worktree 和 Multi-repository 继续按显式映射叠加。未实现 Task Port 的 M2 AgentScope Profile 不声明新的三项 Task 能力，防止 Scheduler 把 Task 路由到只有 Conversation 能力的调用适配器。

## 6. 自动化证据

新增 `TaskExecutionRuntimeContractTest` 6 个测试，覆盖：

1. Task、Execution、Lease、Task Token、Session、Run、Policy 和 Safety 全事实闭合；
2. 单订阅有限流、正向 demand 和唯一终态；
3. 传输取消与业务控制分离；
4. 非法 demand、错 Owner、错 Segment、跳号、终态后事件和缺少终态；
5. Pause、Resume、Cancel 请求与幂等结果；
6. 安全事件、Token 脱敏和稳定错误分类。

专项与兼容验证：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure,crewscope-agentscope -am \
  -Dtest=TaskExecutionRuntimeContractTest,TaskRuntimeCapabilityResolverTest,ExecutionRuntimeContractTest,AgentScopeRuntimeProfileTest,DurableTaskClaimSchedulerM3I02IntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：Task 新契约 6 个、M2 Conversation Port 回归 10 个、能力映射 1 个、AgentScope Profile 1 个、Scheduler PostgreSQL 集成 6 个，合计 24 个测试全部通过。

模块回归：Domain 318 个、Application 186 个、AgentScope 75 个，共 579 个测试全部通过。

全仓执行 `./mvnw --batch-mode --no-transfer-progress clean verify`，从 Surefire XML 精确汇总 903 个测试，Failures 0、Errors 0、Skipped 0，构建成功。

相关文档：

- [ADR-010 ExecutionRuntime 调用与流协议](../adr/ADR-010-ExecutionRuntime调用与流协议.md)；
- [M3-S02 AgentScope Task Orchestrator 映射验证](../spikes/M3-S02-AgentScope-Task-Orchestrator映射验证记录.md)；
- [M3-D07 Agent 运行与恢复领域模型](M3-D07-Agent运行与恢复领域模型.md)；
- [M3-I04 Task Token 签发验证与请求中间件](M3-I04-Task-Token签发验证与请求中间件.md)。
