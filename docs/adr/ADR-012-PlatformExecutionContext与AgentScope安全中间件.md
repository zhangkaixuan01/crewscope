# ADR-012：PlatformExecutionContext 与 AgentScope 安全中间件

> 状态：ACCEPTED<br>
> 日期：2026-08-09<br>
> 影响里程碑：M2–M4<br>
> 关联决策：[ADR-006](ADR-006-ProviderBinding解析与授权.md)、[ADR-010](ADR-010-ExecutionRuntime调用与流协议.md)、[ADR-011](ADR-011-AgentScopeNativeRuntime实例与恢复协议.md)

## 背景

AgentScope `RuntimeContext` 同时承载状态隔离键和调用级类型化属性。CrewScope 的 Team、Membership、Principal、Workspace、Conversation、Participant、AgentProfile 与 ProviderBinding 是 PostgreSQL 和服务端认证事实，不能从 AG-UI 或普通 HTTP 请求字段复制。模型与 Tool 在开始执行前需要获得同一份不可变、无凭证的授权快照。

## 决策

### 可信上下文归属

`PlatformExecutionContext` 定义在 `crewscope-application/execution`。Application 层负责从认证主体和当前 Repository 事实构建快照；`crewscope-agentscope` 只负责注入、校验和消费，Application 不依赖 AgentScope。

每次 Invoke、Resume 和 Cancel 都创建新的上下文。上下文闭合以下事实：

```text
Organization / Team / Workspace
USER Principal / TeamMember / effective TeamRole and TeamPermission
Personal Agent Principal / pinned AgentProfile
Conversation / visibility / USER and Agent Participant
AgentRuntimeSession / AgentScopeSessionKey
RuntimeInvocationId / correlationId
required ProviderType / credential-free ResolvedProviderBinding
```

`ResolvedProviderBinding` 只携带 Binding、Target、Owner、Implementation、Connection、ConnectionGrant 的稳定 ID 和当前有效访问范围。Credential、消息正文、Prompt、Reasoning、Tool 参数和 Provider 原始错误不进入上下文或审计记录。

### 当前事实解析

`PlatformExecutionContextResolver` 从当前 Session、Conversation、Participant、Team、Workspace、Membership、Principal、AgentProfile、MemberRole、TeamRole 和 `ProviderBindingResolver` 重建快照。解析要求：

- Team、Workspace、Membership、USER、Personal Agent、Conversation、Participant、AgentProfile 和 Session 当前可用；
- Conversation、Session、Workspace、Principal、Profile 与 Participant Scope 完全一致；
- USER 与 Personal Agent 对 Conversation 都具有写权限；
- TeamRole Grant 当前有效且引用当前可授予的 TeamRole；
- 每个要求的 ProviderType 都得到唯一 `RESOLVED` Binding。

缺失、过期、撤销、Scope 不符和 Binding 歧义统一失败关闭。对外只返回稳定安全代码，不返回候选 Binding ID 或其他租户事实。

### RuntimeContext 与 Middleware

`AgentScopeNativeRuntime` 使用以下方式建立每次调用的上下文：

```java
RuntimeContext.builder()
    .userId(agentScopeSessionKey.userId())
    .sessionId(agentScopeSessionKey.sessionId())
    .put(PlatformExecutionContext.class, platformExecutionContext)
    .build();
```

类型化属性会进入 AgentScope `ToolExecutionContext` 投影。Resume 使用本次重新解析的上下文替换初始调用上下文；Cancel 同样校验新的上下文与已登记 Invocation、Session 和状态键一致。

M2 固定 Middleware 顺序：

```text
PlatformRuntimeContextMiddleware
  -> ProviderBindingSecurityMiddleware
    -> PlatformAuditMiddleware
      -> AgentScope core
```

第一个 Middleware 在 `onAgent` 校验类型化上下文、`userId/sessionId` 和 Team Scope。Provider Middleware 在 `onAgent`、`onModelCall` 与 `onActing` 复验必需 Binding 和 Target Scope。任何失败都发生在模型或 Tool 执行前，并映射为安全的 `AUTHORIZATION` 终态。

### 基础审计

`PlatformAuditMiddleware` 记录 Invocation、Model Call 与 Tool Execution 的 `STARTED/COMPLETED/FAILED/CANCELED`。记录只包含稳定 Scope/Invocation ID、时间、阶段、结果、Tool 名称、数量和安全失败类型。Tool 名称在记录构造处执行格式白名单、单值长度和集合数量限制，非法模型输出统一为 `unknown_tool`；失败类型只接受稳定大写代码。默认 M2 Sink 输出结构化安全日志；Sink 在开始记录时失败会阻止后续执行。M2-I07 和 M3 在同一 Port 后补充持久 Audit 与完整 Trace 关联。

### AG-UI 边界

`ServerResolvedAguiInvocation` 强制携带同一 `PlatformExecutionContext`，Run ID 必须等于 RuntimeInvocationId。`ControlledAguiBridge` 重建官方 `RunAgentInput` 和 `RuntimeContext`，客户端 `threadId`、`runId`、Tool、Context、State、Principal、Role 与 ProviderBinding 字段不能覆盖服务端事实。

出站事件不直接信任官方 Adapter。`AgentScopeEventMapper` 和 `AguiEventSanitizer` 按 [ADR-013](ADR-013-AgentScope事件映射与披露协议.md)过滤 Thinking、Tool 参数/结果、State、Custom、Provider 原始错误与内部 ID；`ConversationExecutionEventMapper` 使用本上下文的 Conversation、Agent Participant、Invocation 和 Correlation 构造安全实时事件与业务 Candidate。

## 结果

- 可信业务事实由框架无关的 Application Resolver 统一构建；
- AgentScope Middleware 与 Tool 读取同一类型化快照；
- 缺 Membership、Participant、Scope、Profile 或 Binding 的请求在模型执行前失败；
- Invoke、Resume、Cancel 和 AG-UI 使用一致的 Invocation、Session 与 Correlation 约束；
- 基础审计不采集消息、Prompt、Reasoning、Tool 参数或 Credential，模型生成的非法 Tool 名称不能注入日志。

## 验证

实现与验证结果见 [M2-I04 PlatformExecutionContext 与 Middleware](../testing/M2-I04-PlatformExecutionContext与Middleware.md)。

## 重新评估条件

- M2-A03 引入正式 Conversation Invocation/Resume/Cancel Application Service；
- M2-I07 接入持久 Audit、Token Usage、Latency、Retry、Fallback 与 Trace；
- M3 增加 Responsibility、CollaborationGrant、PolicySnapshot、Task Token 与预算事实；
- Runtime 独立部署，需要签名上下文或跨进程授权快照版本。
