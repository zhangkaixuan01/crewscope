# M2-I02：ExecutionRuntime Port

> 日期：2026-08-09<br>
> 状态：已完成<br>
> 模块：`crewscope-application`、`crewscope-agentscope`

## 目标

将 M0 的描述符型 `AgentRuntime` 演进为 [ADR-010](../adr/ADR-010-ExecutionRuntime调用与流协议.md) 定义的 `ExecutionRuntime` Port，为 M2 Conversation Invocation 建立框架无关的调用、流式输出、Structured Output、Interrupt、Resume、Cancel、终态和错误分类契约。

## 边界

M2 请求使用以下已提交可信事实：

```text
RuntimeInvocationId
AgentRuntimeSession
USER Message
optional StructuredOutputSpec
Correlation ID
```

Resume 使用同一 Invocation 与 Session、Interrupt Token、Resume Request ID 和 USER 回答 Message。Cancel 使用同一 Invocation 与 Session。M2 的 Port 直接服务 Conversation，M3 在后续任务中加入 TaskExecution 请求、Lease 和耐久 AgentRun。

## 流协议

每次 Invoke 或 Resume 返回一个单订阅有限流：

```text
STARTED
  -> TEXT_DELTA / STRUCTURED_OUTPUT
  -> COMPLETED | INTERRUPTED | CANCELED | FAILED
  -> onComplete
```

事件序号从 1 严格递增。Subscriber demand 控制发送数量；Subscription Cancel 关闭当前传输订阅，ExecutionRuntime Cancel 取消业务调用。

## 验证范围

- 请求 Scope、Conversation、Session、Owner、消息类型和作者闭合；
- Runtime 描述符、稳定能力枚举和 AgentScope 2.0.0 Profile；
- Structured Output Schema 与 Java 类型闭合；
- Flow demand、单订阅和订阅取消；
- Completed、Interrupted、Canceled、Failed 唯一终态；
- Resume 同 Invocation 与 Interrupt Token；
- Cancel 幂等结果与事件传播；
- 错误分类、可重试性和安全错误信息；
- `ExecutionStreamValidator` 的序号与终态协议。

## 验证结果

已完成以下实现：

- Application 层提供框架无关的 `ExecutionRuntime` Port，覆盖 Conversation Invoke、Resume、显式 Cancel、稳定描述符和能力快照；
- Invoke 与 Resume 请求直接使用持久化 `AgentRuntimeSession` 和已提交 `Message`，构造时闭合 ACTIVE 状态、Scope、Conversation、USER 类型、Session Owner 作者及 Correlation ID；
- `StructuredOutputSpec` 使用版本化 Schema ID 和 Java 类型闭合值，运行时异常映射为安全分类、可重试性和稳定 Runtime Code；
- `ExecutionHandle` 自动包装底层 Publisher，原子裁决单订阅，透传正向 demand 与订阅取消，并验证 Invocation、连续序号、首个 `STARTED`、唯一终态和终态后无事件；
- Subscription Cancel 只向底层 Publisher 传播传输取消，业务取消只通过 `ExecutionRuntime.cancel` 进入 Runtime；
- Interrupt 以可恢复终态结束当前流段，Resume 使用同一 Invocation、Session 和服务端 Interrupt Token 开始新的有限流段；
- AgentScope 2.0.0 Profile 固定为 `agentscope-java-native / AgentScope Java / 2.0.0`，只声明 M2 已接通的六项能力。

专项验证：

```text
ExecutionRuntimeContractTest     10 tests passed
AgentScopeRuntimeProfileTest      1 test passed
```

契约测试覆盖非 ACTIVE Session、跨 Scope/Conversation、非 USER 与错误作者拒绝，Structured Output 类型闭合，Flow demand、单订阅、非法 demand、订阅取消，错 Invocation、跳号、缺少 STARTED、终态后事件、缺少终态，Interrupt/Resume、Cancel 结果和安全失败分类。

全仓验证：

```text
mvn clean verify                  BUILD SUCCESS，547 个 Java 测试通过
node scripts/check-doc-links.mjs  78 个 Markdown 文件链接通过
git diff --check                  通过
```
