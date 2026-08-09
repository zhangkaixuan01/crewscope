# M2-S01：受控 AG-UI Bridge 验证记录

> 状态：VERIFIED
> 日期：2026-08-09
> AgentScope 基线：`v2.0.0`（`44c304ec`）
> 关联决策：[ADR-005](../adr/ADR-005-事件与投影协议.md)

## 1. 验证目标

M2-S01 验证 Web 客户端只能提交对话内容，Agent、Conversation、AgentRuntimeSession、Principal、AgentScope 状态键和可用 Tool 全部由 CrewScope 服务端解析。AG-UI 负责服务端已授权调用的流式传输，不参与身份、Scope、Session 或能力裁决。

验收场景包括：

- path 和 header 中的 Agent 提示不能选择 Agent；
- body 中的 `threadId`、`runId`、`tools`、`context`、`state` 和 `forwardedProps` 不能进入运行时；
- `forwardedProps` 中的 Agent、Principal、Role、ProviderBinding、Connection 和 Session 提示不能覆盖服务端事实；
- AgentScope `RuntimeContext.userId/sessionId` 来自持久化 `AgentRuntimeSession.agentScopeKey`；
- AG-UI 对外 Thread/Run ID 由服务端生成；
- Adapter 固定使用 `ToolMergeMode.AGENT_ONLY`；
- Adapter 固定关闭 Reasoning/Thinking 输出；
- 普通文本流仍保持 AG-UI 标准事件顺序。

## 2. AgentScope 2.0.0 源码结论

### 2.1 官方通用入口

`agentscope-agui-spring-boot-starter` 在 Reactive Web 应用中自动注册：

```text
POST /agui/run
POST /agui/run/{agentId}
```

`AguiRequestProcessor` 的 Agent ID 解析顺序是：

```text
path agentId
  -> configured header
  -> forwardedProps.agentId
  -> defaultAgentId
  -> "default"
```

该行为适用于通用 AgentScope 应用，不满足 CrewScope 服务端身份裁决要求。CrewScope 禁用 Starter 自动注册的通用 WebFlux 路由，只在后续 `M2-A03` 注册 Conversation Scope 下的受控调用、恢复和取消入口。

### 2.2 原始请求透传

官方 `RunAgentInput` 接受客户端提供的：

```text
threadId / runId / messages / tools / context / state / forwardedProps
```

`AguiAgentAdapter` 将 `threadId` 直接设置为 `RuntimeContext.sessionId`，并把原始 `RunAgentInput`、Tools、Context、State 和 ForwardedProps 放入 RuntimeContext。`AguiRequestProcessor` 还会使用 `forwardedProps.agentId` 选择 Agent。

CrewScope Bridge 不把外部 `RunAgentInput` 作为应用入口 DTO。受控 DTO 只允许一段当前用户消息文本；role 和 Message ID 由服务端构造，Bridge 使用服务端绑定重新构造 `RunAgentInput` 和 RuntimeContext。

### 2.3 Tool 与 Reasoning

`AguiAdapterConfig` 默认值不符合 CrewScope 的工具边界：

```text
toolMergeMode = MERGE_FRONTEND_PRIORITY
enableReasoning = false
```

AgentScope 2.0.0 已提供需要的收紧开关：

```java
AguiAdapterConfig.builder()
    .toolMergeMode(ToolMergeMode.AGENT_ONLY)
    .enableReasoning(false)
    .build();
```

`AGENT_ONLY` 会忽略前端 Tool；关闭 Reasoning 时 `ThinkingBlock` 不产生 `REASONING_*` 事件。CrewScope Bridge 仍不接收前端 Tool，从输入边界和 Adapter 配置形成两层保护。

## 3. 受控边界设计

```text
Spring Security Authentication
  -> Organization / Team / Workspace / Conversation 路由
  -> Membership、Participant 与可见性校验
  -> Personal Agent / AgentProfile 解析
  -> AgentRuntimeSession.ensurePersonal
  -> ProviderBindingResolver
  -> ServerResolvedAguiInvocation
  -> ControlledAguiBridge
  -> official AguiAgentAdapter
  -> SSE
```

`ServerResolvedAguiInvocation` 是调用前已解析的可信快照，至少保存：

- Organization、Team、Workspace 和 Conversation；
- 当前 USER Principal、TeamMember 和 Personal Agent Principal；
- AgentProfile 版本与服务端 Agent 实例；
- AgentRuntimeSession 和稳定的 AgentScope `userId/sessionId`；
- 服务端生成的 AG-UI `threadId/runId`；
- Correlation ID；
- 后续 M2-I04 注入的 ProviderBinding 解析结果。

Bridge 的输入规则：

1. 客户端 DTO 只接收当前消息文本，不接收 role、Message ID 或控制字段；
2. JSON 出现未知控制字段时解析失败；
3. Bridge 从可信绑定创建全新的 `RunAgentInput`，Tools、Context、State 和 ForwardedProps 均为空；
4. Bridge 用服务端 `AgentScopeSessionKey` 覆盖 Adapter 临时构造的 RuntimeContext 身份和 Session；
5. RuntimeContext 以类型化属性携带服务端绑定，后续 Middleware 和 Tool 只读取该类型化事实；
6. Agent 实例、绑定 ID 或 Session Key 不一致时调用在模型执行前失败；
7. 对外错误不回显客户端伪造值、Principal、Binding、Credential 或内部 Session Key。

## 4. 安全不变量

| 边界 | 不变量 |
|---|---|
| 路由 | 不提供按客户端 Agent ID 路由的生产端点 |
| 身份 | 当前 Principal 来自 Spring Security 与服务端仓储 |
| Agent | Agent 实例和 Personal Agent Principal 来自服务端绑定 |
| Conversation | Conversation 与 Organization/Team/Workspace 必须属于同一持久化 Scope |
| Session | AgentScope `userId/sessionId` 来自 `AgentRuntimeSession.agentScopeKey` |
| 协议 ID | AG-UI Thread/Run ID 由服务端生成并与 Conversation/Invocation 关联 |
| Tool | 客户端 DTO 无 Tool 字段，Adapter 固定 `AGENT_ONLY` |
| Reasoning | Adapter 固定 `enableReasoning(false)`，不输出 ThinkingBlock |
| 上下文 | 客户端 Context/State/ForwardedProps 不进入 RuntimeContext |
| 失败模式 | 缺失、停用、越权、跨 Scope 或不一致绑定全部失败关闭 |

## 5. 验证矩阵

| 场景 | 预期 |
|---|---|
| 请求 `/agui/run/client-agent` | Starter 通用路由不存在 |
| Header 发送 `X-Agent-Id: client-agent` | 不存在使用该 Header 选 Agent 的入口 |
| DTO 注入 `threadId` 或 `runId` | JSON 解析拒绝 |
| DTO 注入 `tools` | JSON 解析拒绝，Agent Toolkit 不变 |
| DTO 注入 `forwardedProps.agentId/principalId/role/sessionId` | JSON 解析拒绝 |
| 服务端绑定执行 | 事件仅使用服务端 Thread/Run ID |
| 捕获 RuntimeContext | `userId/sessionId` 等于服务端 AgentScopeSessionKey |
| Agent 输出 ThinkingBlock | 无 `REASONING_*` 事件和思考文本 |
| Agent 输出普通 TextBlock | `RUN_STARTED -> TEXT_* -> RUN_FINISHED` |
| Agent 与绑定不一致 | 模型执行前失败 |

## 6. 实现范围

M2-S01 交付最小生产边界对象、Bridge、自动路由禁用配置和可控测试。数据库身份解析、正式 Conversation API、Resume/Cancel、ProviderBinding Middleware、消息持久化、统一事件信封和 SSE 断线恢复分别由 `M2-I04`、`M2-A03`、`M2-I06` 和 `M2-A04` 完成。

## 7. 验证结果

已交付：

- `ControlledAguiClientInput`：只接收一段当前用户消息，显式拒绝全部未知字段；
- `ServerResolvedAguiInvocation`：只从 ACTIVE AgentRuntimeSession、服务端 Message ID、Run ID、Correlation ID 和已解析 Agent 创建；
- `ControlledAguiBridge`：重建安全 `RunAgentInput`，固定 `AGENT_ONLY` 与关闭 Reasoning，并用 AgentRuntimeSession 的 `userId/sessionId` 替换通用 Adapter Context；
- `CrewScopeApplication`：显式排除 AgentScope MVC/WebFlux AG-UI 自动路由；
- ADR-005、总体设计和 M2 执行清单同步更新。

专项验证命令：

```bash
/Users/zhangkaixuan/Downloads/apache-maven-3.9.6/bin/mvn \
  -pl crewscope-agentscope,crewscope-server -am \
  -Dtest=ControlledAguiBridgeM2S01IntegrationTest,CrewScopeApplicationAguiBoundaryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：9 个专项测试通过。覆盖宽松 Jackson 配置下的控制字段拒绝、工具与 Reasoning 固定策略、安全协议输入、服务端 Thread/Run ID、可信 RuntimeContext User/Session、Thinking 过滤、标准文本事件顺序、Agent 不匹配、非 ACTIVE Session 和通用路由禁用。

全仓回归：`mvn clean verify` 通过，共 514 个 Java 测试；`node scripts/check-doc-links.mjs` 通过，共检查 72 个 Markdown 文件。

结论：AgentScope 2.0.0 的 AG-UI Adapter 可以作为 CrewScope 内部协议转换器；官方通用 Starter 路由不进入生产暴露面。`M2-I04` 在该边界上补充完整 PlatformExecutionContext、Membership、Participant、ProviderBinding 和 Audit Middleware，`M2-A03` 再注册 Conversation Scope 正式 API。
