# M5-S01 AgentScope 动态模型与多连接验证记录

> 验证对象：AgentScope Java `v2.0.0`、Spring Boot 4.0.4、OpenAIChatModel、HarnessAgent<br>
> CrewScope 模块：`crewscope-server`、`crewscope-agentscope`<br>
> 验证日期：2026-08-22

## 1. 验证目标

1. 对照 AgentScope Java 2.0.0 源码确认动态 Model、Formatter、GenerateOptions、Tool、Structured Output、Retry 和 Fallback 语义；
2. 使用 Spring 多 Adapter 装配 DeepSeek 与备用 OpenAI Provider；
3. 使用两个独立本地 HTTP Endpoint、Credential 和 Model ID 完成 HarnessAgent Tool + Structured Output；
4. 证明 Retry 留在原 Connection，Fallback 使用独立 Connection；
5. 冻结 M5-I03 使用的受信 Adapter SPI 和禁止透传字段。

## 2. AgentScope 2.0.0 源码能力映射

| CrewScope 需求 | AgentScope 源码能力 | M5 决策 |
|---|---|---|
| 动态模型 | `ModelRegistry.resolve(modelId, ModelCreationContext)`、`OpenAIChatModel.builder()` | 企业 Connection 使用 CrewScope Factory 直接构建，不进入全局 Registry |
| OpenAI-compatible | `OpenAIChatModel` 支持 Base URL、EndpointPath、Formatter 和默认 GenerateOptions | DeepSeek 与 OpenAI 复用传输实现，产品 Provider 保持独立 |
| Formatter | `OpenAIChatFormatter`、`DeepSeekFormatter` | 由受信 Adapter 按 Provider/Revision 固定 |
| Tool | `HarnessAgent` Toolkit 与 `OpenAIChatModel` Tool Schema/Tool Call | 两个 Provider 均执行真实 `connection_probe` Tool |
| Structured Output | `supportsNativeStructuredOutput*`、原生 `response_format`、合成 `generate_response` | OpenAI 走原生 Schema，DeepSeek 走合成 Tool |
| GenerateOptions | Builder 默认值与请求级逐字段 Merge | 只允许平台 SafeGenerateOptions；禁止连接字段和任意 Map 透传 |
| Retry | `ModelConfig.maxRetries` 合入 `ExecutionConfig.maxAttempts`，`ModelUtils` 重订阅 | Retry 保持同一 Model/Connection，并记录真实 Attempt |
| Fallback | ReActAgent 在 Primary 首个错误信号后切换显式 Fallback Model | 两个 Model 独立解析；组合能力采用保守策略 |
| Spring | OpenAI Starter 通过 `@ConditionalOnMissingBean(Model.class)` 创建单 Bean | Starter 只服务 Bootstrap Slot；动态连接装配 Adapter 列表 |

## 3. 关键源码结论

### 3.1 请求配置可以覆盖连接配置

`OpenAIChatModel` 调用 `GenerateOptions.mergeOptions(request, configured)`，请求值优先。AgentScope 的 `GenerateOptions` 同时包含：

```text
apiKey
baseUrl
endpointPath
modelName
additionalHeaders
additionalBodyParams
additionalQueryParams
```

这些字段不能进入 AgentConfiguration 的用户可配置对象，也不能由 Agent、Controller 或前端构造。M5-I03 使用独立 SafeGenerateOptions，只映射平台批准的生成参数与 ExecutionConfig。

### 3.2 Spring Starter 是单模型装配

`OpenAIAutoConfiguration` 只有在容器中缺少 `Model` 时创建一个 `OpenAIChatModel`。多个 ModelConnection 不能通过复制 `agentscope.openai.*` 配置或暴露多个 `Model` Bean 实现，否则 `crewscope-primary` 的唯一 Bean 解析也会变为歧义。

M5 使用：

```text
Spring List<AgentScopeModelProviderAdapter>
  -> 唯一 AdapterKey 索引
  -> AgentScopeModelFactory
  -> Connection-scoped Model
```

### 3.3 Structured Output 组合能力需要保守解析

DeepSeek Formatter 关闭 Native Structured Output，与 Tool 并用时由 HarnessAgent 注入 `generate_response` Tool。OpenAI 原生模型通过 `response_format.json_schema` 返回结构化内容。Primary 与 Fallback 的兼容能力可能不同；CrewScope 在建 Agent 前选择共同支持的更保守模式。

AgentScope 2.0.0 的 Fallback 包装器没有单独转发 `supportsNativeStructuredOutputWithTools`。M5-I03 不能把组合策略交给该包装器隐式判断。

### 3.4 Retry 与 Fallback 顺序

`maxRetries` 表示一次逻辑模型调用的最大 Attempt 数。Primary 在 `ModelUtils.applyTimeoutAndRetry` 中耗尽 Retry 后，ReActAgent 才在首个错误信号上调用 Fallback。每个后续 Reasoning Round 重新执行同样边界。

因此遥测和预算必须区分：

```text
Logical Model Call
  -> Primary Attempt 1..N
  -> Fallback Attempt 1..N
```

模型轮次、逻辑调用数和 HTTP Attempt 数不能互相替代。

## 4. 受信 Adapter SPI

M5-S01 冻结以下职责，正式类型在 M5-I03 落地：

```text
AgentScopeModelProviderAdapter
  adapterKey()
  build(TrustedModelBuildRequest, CredentialHandle) -> Model

TrustedModelBuildRequest
  provider/adapter
  connectionId/version
  endpoint/endpointPath
  modelId/revision
  formatterPolicy
  structuredOutputCompatibility
  safeGenerateOptions
  capability/policy hash
```

约束：

- Adapter Key 缺失或重复时启动失败；
- Endpoint、Formatter、HTTP Transport、Model ID 和 Credential 只来自服务端解析结果；
- Primary/Fallback 各自完成策略、Connection 和能力校验；
- Model 缓存绑定 Connection/Credential/Model/Adapter/配置完整版本；
- Model、Credential Handle 和密钥不序列化到 AgentState；
- ModelConnection 撤销或轮换后，旧实例不能进入新的调用。

## 5. 自动化场景

测试类：

```text
crewscope-server/src/test/java/io/crewscope/server/config/application/
  AgentScopeDynamicModelM5S01IntegrationTest.java
```

测试使用 JDK Loopback HTTP Server 建立两个真实 OpenAI-compatible Endpoint，不读取外部 Key，不访问公网。

### 5.1 双 Connection Tool + Structured Output

```text
DeepSeek Connection
  Endpoint A + Key A + deepseek-model + DeepSeekFormatter
  -> 首次 500
  -> 原 Connection Retry
  -> connection_probe Tool
  -> generate_response Tool

OpenAI Connection
  Endpoint B + Key B + openai-model + OpenAIChatFormatter
  -> connection_probe Tool
  -> response_format.json_schema
```

断言每个 Endpoint 收到的所有请求只包含自己的 Authorization 和 Model ID；Tool Result 中的 Connection Marker 不会进入另一 Provider；DeepSeek 不携带 `response_format`，OpenAI 的 Tool 列表不注入合成 `generate_response`。

### 5.2 独立 Fallback

Primary DeepSeek Endpoint 固定返回 503。HarnessAgent 耗尽当前 Round 的 Primary Attempt 后调用独立 OpenAI Fallback。两个 Reasoning Round 都先访问 Primary，再进入 Fallback；Fallback 使用自己的 Endpoint、Key、Model 和 Formatter 完成 Tool 与结构化交付。

## 6. 验证命令

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-server -am \
  -Dtest=AgentScopeDynamicModelM5S01IntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：

```text
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

完成专项验证后执行全仓 Maven 回归：

```bash
./mvnw --batch-mode --no-transfer-progress test
```

```text
Tests run: 1520, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

回归覆盖 Domain、Application、AgentScope Adapter、Integration、Infrastructure 和 Server 全部模块，并实际运行 PostgreSQL、Redis 与 Docker Sandbox 集成测试。文档链接门禁同时通过，共校验 191 个 Markdown 文件。

## 7. 结论

M5-S01 验证通过。AgentScope Java 2.0.0 支持 CrewScope 的多 Provider Tool、Structured Output、Formatter、GenerateOptions、Retry 与 Fallback 需求。企业多租户模型不能依赖唯一 Spring `Model` Bean、全局字符串 Registry 或请求级连接覆盖；M5-I03 按本记录冻结的受信 Adapter Registry 和 Connection-scoped Model 生命周期实现。
