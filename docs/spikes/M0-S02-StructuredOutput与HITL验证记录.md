# M0-S02 Structured Output、AG-UI 与 HITL 验证记录

> 验证对象：AgentScope Java `v2.0.0`  
> CrewScope 模块：`crewscope-agentscope`  
> 验证方式：无网络、无外部模型的确定性集成测试

## 1. 验证目标

1. 验证 Structured Output 的生成与 Java 对象映射；
2. 验证 CrewScope 对结构化结果执行 Jakarta Bean Validation；
3. 验证官方 AG-UI Adapter 的文本流式事件；
4. 验证高风险 Tool 的 Interrupt 和 Confirm Resume；
5. 验证重复 Resume 的原生行为与 CrewScope 幂等边界。

## 2. Structured Output

调用链如下：

```text
HarnessAgent.call(messages, TaskIntentProbe.class, RuntimeContext)
  -> generate_response ToolUseBlock
  -> Msg.getStructuredData(TaskIntentProbe.class)
  -> Jakarta Validator
  -> Application Command
```

测试结构包含：

```text
objective          @NotBlank
acceptanceCriteria @Size(min = 1)，元素 @NotBlank
riskLevel          @Min(1) @Max(3)
```

合法结果完成 Java 对象映射并通过校验。非法结果可以完成 AgentScope 映射，CrewScope
Validator 检出 `objective`、`acceptanceCriteria` 和 `riskLevel` 三个非法字段。

AgentScope 负责 JSON Schema、`generate_response` 工具和对象映射，不自动执行 Jakarta Bean
Validation。CrewScope 在结构化结果进入 Application Command 前统一校验。

AgentScope 2.0.0 在 `generate_response` 返回结果后直接结束本轮调用，只发生一次 Model 调用。

## 3. AG-UI 流式事件

官方 `AguiAgentAdapter` 使用以下输入：

```text
threadId = conversation-crw-agui
runId = run-m0-s02-agui
toolMergeMode = AGENT_ONLY
```

确定性文本响应产生稳定事件序列：

```text
RUN_STARTED
TEXT_MESSAGE_START
TEXT_MESSAGE_CONTENT
TEXT_MESSAGE_END
RUN_FINISHED
```

每个事件都携带相同的 `threadId` 和 `runId`。Adapter 同时将 `threadId` 映射为
`RuntimeContext.sessionId`，并将完整 `RunAgentInput` 写入 RuntimeContext。

AgentScope 2.0.0 官方 Adapter 内部仍调用已弃用的
`Agent.stream(List<Msg>, StreamOptions, RuntimeContext)` 粗粒度接口，没有直接消费
`HarnessAgent.streamEvents(...)` 的 v2 细粒度事件。CrewScope 将官方 Adapter 保留为协议兼容
基线，M2 实现自己的细粒度事件 Converter 和可信上下文 Enricher。

## 4. Interrupt 与 Resume

测试 Tool 的权限检查固定返回 `PermissionDecision.ask(...)`。首次调用产生：

```text
RequireUserConfirmEvent
RequestStopEvent
AgentResult.generateReason = PERMISSION_ASKING
ToolUseBlock.state = ASKING
```

首次调用不会执行 Tool。CrewScope 从结果中取得待确认的 `ToolUseBlock`，使用
`Msg.METADATA_CONFIRM_RESULTS` 携带 `ConfirmResult(true, pendingToolCall)` 发起第二次调用。

确认后的结果：

```text
Tool 执行次数 = 1
Model 调用次数 = 2
最终文本 = confirmed-action-complete
```

## 5. 重复 Resume

AgentScope 2.0.0 没有 Resume Request ID 或 Confirmation Decision ID。相同 ConfirmResult 在
首次 Resume 完成后再次提交时：

```text
Tool 执行次数 = 1
Model 调用次数 = 3
首次结果 = first-resume-result
重复结果 = duplicate-resume-result
```

已完成的 Tool 没有重复执行，重复请求仍再次进入 Model、追加上下文并产生不同结果，因此原生
行为不满足 CrewScope 的请求级幂等要求。

测试中的 `ResumeRequestGuardProbe` 在 AgentScope 调用前按 `resumeRequestId` 缓存完成结果，重复
请求返回同一个结果：

```text
Tool 执行次数 = 1
Model 调用次数 = 2
重复请求返回首次结果
```

该 Probe 只用于确定架构边界。正式实现使用持久化 Confirmation/Resume 记录、唯一约束、状态机
和事务，保存决策、结果与审计信息，并处理多实例并发和执行结果未知状态。

## 6. 自动化证据

测试类：

```text
crewscope-agentscope/src/test/java/io/crewscope/agentscope/
  HarnessAgentM0S02IntegrationTest.java
  ScriptedModel.java
```

覆盖用例：

1. 合法 Structured Output 映射与校验；
2. 非法 Structured Output 拒绝；
3. AG-UI 文本事件序列；
4. Interrupt 与确认恢复；
5. AgentScope 原生重复 Resume 行为；
6. CrewScope 请求级去重边界。

验证命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-agentscope -am \
  -Dtest=HarnessAgentM0S02IntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：

```text
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 7. 结论

M0-S02 已验证通过，接口选择如下：

- Structured Output 使用 AgentScope 原生类型映射；
- CrewScope 在 Application Command 边界执行 Bean Validation 和业务规则；
- Web 协议兼容官方 AG-UI，内部事件源使用 HarnessAgent v2 细粒度事件；
- Interrupt 以 `RequireUserConfirmEvent`、`RequestStopEvent` 和待确认 ToolCall 为恢复依据；
- Resume 幂等在调用 AgentScope 前由 CrewScope 持久化边界保证；
- 所有外发事件由 CrewScope Enricher 注入服务端可信身份、会话和追踪元数据。

可以进入 M0-S03。
