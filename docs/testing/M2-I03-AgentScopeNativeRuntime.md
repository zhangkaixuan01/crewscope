# M2-I03：AgentScopeNativeRuntime

> 日期：2026-08-09<br>
> 状态：已完成<br>
> 模块：`crewscope-agentscope`

## 目标

实现 [ADR-011](../adr/ADR-011-AgentScopeNativeRuntime实例与恢复协议.md) 定义的 AgentScope Java 2.0.0 原生 Conversation Runtime，将 `ExecutionRuntime` Port 接到 HarnessAgent 的普通流式调用、Structured Output、Permission Interrupt、Resume 与精确 Cancel。

## 实现边界

```text
AgentRuntimeSession
  -> AgentProfileId + pinned version
  -> PersonalAgentConfigurationSource
  -> AgentScopeModelResolver
  -> PersonalAgentFactory
  -> shared HarnessAgent per Profile version
  -> RuntimeContext(userId, sessionId)
```

M2-I03 使用注入的 `AgentStateStore`、Toolkit Factory 和模型解析器。Redis、Provider Tool、安全 Middleware 与完整 PlatformExecutionContext 分别由 M2-I04/M2-I05 装配。

## 验证范围

- Profile ID/版本、模型、Prompt、迭代和重试配置闭合；
- 同版本 Agent 实例复用、跨版本隔离和 Factory 生命周期；
- 普通多轮对话、Conversation Session 隔离和文本增量；
- TaskIntent Structured Output 与 Java 类型转换；
- 澄清 Permission ASK、Pending Tool 捕获、同 Invocation Resume；
- Resume 本地准备后原子消费 Pending Interrupt；
- 错 Session、错 Token、重复 Resume 和并发 Resume/Cancel 失败关闭；
- 模型错误、无效 Structured Output 和安全错误分类；
- 缺失上游终态时内部 Invocation 以安全失败闭合，终态后信号不产生第二终态；
- Flow demand、传输断开、显式 Cancel 和精确 AgentScope Session Interrupt；
- 终态 Registry 容量与 `ACCEPTED/ALREADY_TERMINAL/NOT_FOUND` 结果。

## 验证结果

已完成以下实现：

- `AgentScopePersonalAgentConfiguration`、`PersonalAgentConfigurationSource` 与 `AgentScopeModelResolver` 固定 Profile 版本、主/备用模型、System Prompt、迭代上限和模型重试配置；
- `PersonalAgentFactory` 按 `AgentProfileId + AgentProfileVersion` 原子缓存 HarnessAgent，同版本复用、跨版本隔离，并统一管理 Agent 与 Workspace 生命周期；
- HarnessAgent 关闭 M2 尚未授权的文件、Shell、Subagent、Memory、动态 Skill 与 Workspace 能力，Toolkit 和 AgentStateStore 通过服务端构造器注入；
- `AgentScopeNativeRuntime` 使用持久化 `AgentScopeSessionKey` 构造精确 `RuntimeContext`，普通调用接入 `streamEvents`，Structured Output 接入 `call(..., Class<?>)`；
- AgentScope 文本增量、结构化结果、Permission、External Execution、Middleware Stop、最大迭代、完成、中断、取消与失败统一映射为 `ExecutionEvent`；
- Permission Resume 使用服务端保存的 Pending Tool 与不透明 Interrupt Token，把已提交 USER 回答绑定为 `ConfirmResult`，完成本地准备后同步提交消费，并支持同一 Invocation 连续多轮不同澄清；
- Runtime 内部持有 AgentScope Reactor 订阅，Web Subscription Cancel 只断开事件传输；显式 Cancel 使用同一 RuntimeContext 精确中断业务调用；
- Invocation Registry 保存活动调用、Pending Interrupt 与有限终态，先登记逻辑终态再完成事件流；AgentScope 漏终态时 Runtime 主动补安全失败，终态后信号不覆盖既有结果；Registry 稳定提供 `ACCEPTED/ALREADY_TERMINAL/NOT_FOUND` 结果并按容量淘汰最早终态；
- Provider 原始错误与敏感内容不进入对外事件，模型限流、状态、Tool、超时和 Structured Output 转换失败映射为安全稳定分类。

专项验证：

```text
PersonalAgentFactoryTest                    4 tests passed
AgentScopeNativeRuntimeIntegrationTest     16 tests passed
```

集成测试覆盖 Agent 实例复用和版本隔离、多轮对话与 Session 隔离、TaskIntent Structured Output、单次与连续澄清恢复、错 Token/错 Session/重复 Resume、恢复段状态预检、模型限流、观测链路安全错误分类、无效结构化输出、传输断开、精确取消和终态 Registry 淘汰。

全仓验证：

```text
mvn clean verify                  BUILD SUCCESS，605 个 Java 测试通过
node scripts/check-doc-links.mjs  87 个 Markdown 文件链接通过
git diff --check                  通过
```
