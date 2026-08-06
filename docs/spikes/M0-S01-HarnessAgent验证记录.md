# M0-S01 HarnessAgent 验证记录

> 验证对象：AgentScope Java `v2.0.0`  
> CrewScope 模块：`crewscope-agentscope`  
> 验证方式：无网络、无外部模型的确定性集成测试

## 1. 验证目标

1. 创建最小 `HarnessAgent`；
2. 使用可控测试 Model 完成单轮调用；
3. 使用同一 Session Key 完成跨 `HarnessAgent` 实例的多轮调用；
4. 记录 AgentScope 细粒度事件序列；
5. 验证 `AgentState` 的保存和恢复。

## 2. 验证配置

```text
agentName = crewscope-m0-s01-agent
userId = member-zhang
sessionId = conversation-crw-1024
stateStore = InMemoryAgentStateStore
stateKey = agent_state
model = ScriptedModel
```

M0-S01 关闭 Filesystem、Shell、Subagent、Memory Tool、动态 Skill、Workspace Context 和
Tools Config，只保留 HarnessAgent、ReAct 循环、细粒度事件和状态持久化，用于建立最小运行基线。

## 3. 单轮结果

确定性文本响应产生以下事件序列：

```text
AGENT_START
MODEL_CALL_START
TEXT_BLOCK_START
TEXT_BLOCK_DELTA
TEXT_BLOCK_END
MODEL_CALL_END
AGENT_RESULT
AGENT_END
```

调用完成后：

- `AGENT_RESULT` 包含 Model 返回的最终消息；
- `AgentState.userId = member-zhang`；
- `AgentState.sessionId = conversation-crw-1024`；
- `AgentState.context` 包含一条用户消息和一条 Agent 消息；
- `AgentStateStore.listSessionIds(member-zhang)` 包含该 Session ID。

## 4. 多轮恢复结果

验证过程：

1. 第一个 `HarnessAgent` 实例写入首轮用户消息和 Agent 响应；
2. 关闭第一个实例；
3. 使用同一个 `AgentStateStore` 创建新的 `HarnessAgent` 实例；
4. 使用相同 `(userId, sessionId)` 发起第二轮调用；
5. 第二次 Model 输入包含首轮用户消息、首轮 Agent 响应和本轮用户消息；
6. 第二轮完成后 `AgentState.context` 包含四条消息。

该结果证明 HarnessAgent 可以通过稳定 Session Key 跨实例恢复多轮上下文。

## 5. 框架行为记录

AgentScope 2.0.0 的顶层 `AgentStartEvent` 由 `ReActAgent` 使用
`new AgentStartEvent(null, replyId, name)` 创建，因此事件自身的 `sessionId` 为 `null`。
真实会话身份仍正确存在于：

- 调用输入的 `RuntimeContext.userId/sessionId`；
- `AgentState.userId/sessionId`；
- `AgentStateStore` 的 `(userId, sessionId, stateKey)` 地址。

CrewScope 在 M2 的 `AgentEventConverter/AguiEventEnricher` 中从服务端可信
`RuntimeContext` 补充 Session、Organization、Workspace、Conversation 和 Correlation
元数据，不依赖顶层 `AgentStartEvent.sessionId`。

## 6. 自动化证据

测试类：

```text
crewscope-agentscope/src/test/java/io/crewscope/agentscope/
  HarnessAgentM0S01IntegrationTest.java
  ScriptedModel.java
```

验证命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-agentscope -am \
  -Dtest=HarnessAgentM0S01IntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## 7. 结论

M0-S01 已验证通过，可以进入 M0-S02。当前接口选择如下：

- 使用 `HarnessAgent.streamEvents(..., RuntimeContext)` 消费 AgentScope v2 细粒度事件；
- 使用服务端生成的稳定 `userId/sessionId` 作为状态槽；
- 使用 `AgentStateStore` 保存和恢复 `agent_state`；
- 使用事件 Enricher 补充 CrewScope 可信上下文；
- 测试继续使用确定性 Model，避免外部 API 和模型波动影响技术验证。
