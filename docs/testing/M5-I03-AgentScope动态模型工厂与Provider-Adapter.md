# M5-I03 AgentScope 动态模型工厂与 Provider Adapter

## 1. 交付范围

M5-I03 已交付可信动态模型构建边界：

- `AgentScopeModelFactory` 只接收服务端构造的 `TrustedModelBuildRequest` 与 M5-I02 短期 `ProviderCredentialHandle`；
- `AgentScopeModelAdapterRegistry` 在 Adapter Key 重复、缺失或版本为空时失败关闭；
- `openai-compatible` Adapter 支持 DeepSeek Formatter 与显式登记的兼容 Provider；
- `openai` Adapter 作为原生 OpenAI 备用实现；
- DeepSeek 固定使用 `DeepSeekFormatter`、`nativeStructuredOutput(false)` 与 `nativeStructuredOutputWithTools(false)`；
- OpenAI 固定使用 `OpenAIChatFormatter` 与原生 Structured Output；
- `SafeAgentScopeGenerateOptionsMapper` 只映射温度、Top P、最大输出 Token、Reasoning、Cache、并行 Tool、Seed、Timeout 与 Retry；
- Connection-bound Model 忽略调用级 API Key、Endpoint、Endpoint Path、Model、Stream、Headers、Query、Body 和生成参数覆盖，只保留 AgentScope 内部 ToolChoice 与 ResponseFormat 编排字段；
- 动态 Model 不发布为 Spring `Model` Bean，也不进入全局 `ModelRegistry`。

M5-I04 继续负责目录可选交集、完整 Preflight、健康缓存、Team 默认和 `ResolvedModelSelection` 到 `PolicySnapshot` 的装配。M5-I05 继续负责 TemplateRegistry 与 Personal/Team/Specialist Agent Factory。

## 2. 缓存与凭证边界

动态 Model 使用有界 TTL/LRU 缓存。缓存键固定以下非秘密事实或安全摘要：

- Organization；
- Connection ID 与 Version；
- Credential Secret Version；
- Provider Definition Hash；
- Catalog Coordinate、Content Hash 与 Model Revision；
- Adapter Key 与实现 Version；
- Formatter 与 Structured Output 兼容策略；
- Endpoint 与 Endpoint Path 的 SHA-256；
- Compatibility Hash 与 Safe GenerateOptions Hash。

每次构建和缓存命中都必须提交当前 Handle。Factory 校验 Connection/Credential 坐标，缓存命中仍解析一次 Handle，使连接撤销、凭证轮换、Handle 关闭和 Handle 过期立即失败。Handle 在同步构建窗口结束时关闭，明文只在 `useSecret` 回调内出现。Request、Cache Key、异常和 `toString` 不输出 Endpoint 或 Credential。

## 3. Retry、错误与取消

`ObservableAgentScopeModel` 接收平台受控默认 `ExecutionConfig`，Connection-bound 外层统一补充无 Observation Context 的安全错误映射：

- 每个底层 HTTP Attempt 固定为单次，平台按 `maximumAttempts` 和 `ExecutionConfig.RETRYABLE_ERRORS` 重试；
- Provider 原始异常统一映射为稳定安全错误；
- Usage、Retry、Fallback 与逻辑调用观测继续沿用 M2 可观测链路；
- Reactor 取消直接传播到 Provider Publisher。

## 4. 自动化验证

生产代码测试：

- `AgentScopeModelFactoryTest`：6 个场景覆盖重复 Adapter、缓存命中、Connection Version 失效、Handle 坐标、未知 Adapter、调用级越权字段、非 Spring 构造的 Retry Backoff 边界、无 Context 安全错误和取消传播；
- `DynamicModelProviderIsolationM5I03IntegrationTest`：2 个真实 Loopback Endpoint 上并发执行 DeepSeek/OpenAI，验证 Endpoint、Key、Model 与 Structured Output 能力不串线；
- `DynamicAgentScopeModelConfigurationM5I03Test`：2 个场景验证两个默认 Adapter、唯一 Registry/Factory、不发布动态 Model Bean，以及缓存/Retry 配置越界时启动失败；
- `AgentCallObservabilityM2I07Test`：继续验证 Retry、Fallback、Usage、相关性和安全错误；
- `AgentScopeDynamicModelM5S01IntegrationTest`：继续验证 AgentScope 2.0.0 的 Tool + Structured Output、DeepSeek 合成 Tool、OpenAI 原生 Schema、Retry 与独立 Fallback。

验证命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-server -am \
  -Dtest=AgentScopeModelFactoryTest,DynamicModelProviderIsolationM5I03IntegrationTest,DynamicAgentScopeModelConfigurationM5I03Test,AgentCallObservabilityM2I07Test,AgentScopeDynamicModelM5S01IntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

最终回归结果：`crewscope-agentscope` 125 / 125、`crewscope-server` 222 / 222，失败、错误与跳过均为 0；212 份 Markdown 文档链接检查和 `git diff --check` 通过。
