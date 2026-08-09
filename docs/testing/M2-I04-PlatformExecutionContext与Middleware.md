# M2-I04：PlatformExecutionContext 与 Middleware

> 日期：2026-08-09<br>
> 状态：已完成<br>
> 模块：`crewscope-application`、`crewscope-agentscope`、`crewscope-server`

## 目标

实现 [ADR-012](../adr/ADR-012-PlatformExecutionContext与AgentScope安全中间件.md) 定义的服务端可信执行上下文、AgentScope 类型化注入、安全 Middleware、ProviderBinding 复验与基础 Audit Middleware。

## 实现边界

```text
认证主体 + 当前 PostgreSQL Facts + ProviderBindingResolver
  -> PlatformExecutionContextResolver
  -> immutable PlatformExecutionContext
  -> Conversation Invoke / Resume / Cancel Request
  -> RuntimeContext.put(PlatformExecutionContext.class, context)
  -> PlatformRuntimeContextMiddleware
  -> ProviderBindingSecurityMiddleware
  -> PlatformAuditMiddleware
  -> HarnessAgent Model / Tool
```

Application 层定义可信快照和解析器。AgentScope Adapter 注入、二次校验并向 ToolExecutionContext 投影。Spring Boot Composition Root 以构造器方式装配解析器、三个 Middleware、有序 `PlatformAgentMiddlewareSet` 和结构化日志 Audit Sink。

## 验证范围

- 当前 Session、Team、Workspace、Membership、USER、Personal Agent、Conversation、Participant 和 AgentProfile 解析；
- 当前 TeamRole Grant、Role Key 与 Permission 合并；
- 缺 Membership、Participant、Workspace Scope、Profile 与 ProviderBinding 失败关闭；
- Binding 缺失、撤销、歧义等失败不泄露候选 ID；
- Invoke、Resume、Cancel 请求与 PlatformExecutionContext 的 Session、Profile、Principal、Conversation、Invocation、Correlation 闭合；
- RuntimeContext `userId/sessionId` 与类型化属性校验；
- PlatformExecutionContext 对 AgentScope ToolExecutionContext 可见；
- ProviderBinding 在 Agent、Model 与 Tool 阶段复验；
- Native Runtime 在 Model 调用前返回安全 `AUTHORIZATION` 终态；
- AG-UI 只注入服务端上下文，客户端控制字段不能覆盖；
- Audit 成功、失败、取消、Tool 名称白名单与数量限制、内容脱敏和 Sink 失败关闭；
- Spring Bean 单实例与 Middleware 顺序。

## 实现结果

- `PlatformExecutionContext` 与 `ResolvedProviderBinding` 使用不可变集合并排除 Credential；
- `PlatformExecutionContextResolver` 从当前 Repository 与 BindingResolver 重建授权事实；
- Conversation Execution、Resume、Cancel 请求强制携带并校验可信上下文；
- `AgentScopeNativeRuntime` 对初始调用注入上下文，对 Resume 刷新上下文，对 Cancel 核对上下文；
- `PersonalAgentFactory` 的生产构造器强制接收有序 `PlatformAgentMiddlewareSet`；
- `ControlledAguiBridge` 注入相同上下文，并保持官方 AG-UI control payload 为空；
- 安全异常映射为固定 Authorization 分类与 Runtime Code；
- Audit Record 不提供消息正文、Prompt、Reasoning、Tool 参数或 Credential 字段，并在记录边界规范化不可信 Tool 名称和失败代码。

## 专项验证

```text
PlatformExecutionContextResolverTest       3 tests passed
PlatformMiddlewareM2I04Test                6 tests passed
AgentScopeNativeRuntimeIntegrationTest    10 tests passed
ControlledAguiBridgeM2S01IntegrationTest   8 tests passed
ApplicationCompositionConfigurationTest    1 test passed
```

其中 Native Runtime 新增用例证明缺少必需 ProviderBinding 时模型调用次数为 0；AG-UI 用例同时证明服务端 PlatformExecutionContext 被类型化注入。

## 全仓验证

```text
mvn clean verify                  BUILD SUCCESS，570 个 Java 测试通过
node scripts/check-doc-links.mjs  通过，检查 82 个 Markdown 文件
git diff --check                  通过
```
