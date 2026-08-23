# ADR-015：Agent 模型目录、连接与配置解析

> 状态：ACCEPTED<br>
> 日期：2026-08-21<br>
> 影响里程碑：M5–M6<br>
> 关联决策：[ADR-004](ADR-004-CredentialStore与动作凭证.md)、[ADR-011](ADR-011-AgentScopeNativeRuntime实例与恢复协议.md)、[ADR-014](ADR-014-Agent模型调用可观测与安全重试协议.md)<br>
> 后续决策：[ADR-016](ADR-016-Agent所有权、模板与执行配置.md) 已替代本 ADR 中“全部 Specialist 固定使用团队连接”的解释，并引入 Ownership、Template 与 ExecutionScope

## 背景

CrewScope 为每个 TeamMember 创建一个默认 Personal Agent，并为团队任务创建 Task Orchestrator、Coding Specialist 和 Reviewer Specialist。当前实现通过进程环境把唯一 Spring `Model` Bean 绑定到 `crewscope-primary`，所有 AgentProfile 共享同一个部署级模型。

企业应用需要同时支持个人选择、团队默认、组织限制、凭证所有权、数据区域、模型能力、成本预算、运行版本固定和完整审计。DeepSeek 可以通过 AgentScope `OpenAIChatModel` 调用，产品和审计中的厂商仍然为 DeepSeek，OpenAI-compatible 只是传输适配协议。

## 决策

### 概念边界

GitHub、飞书、CI/CD 和知识库继续使用 `ProviderDefinition`、`ProviderImplementation`、`Connection` 和 `ProviderBinding`。模型能力使用独立的 Model Registry 领域，不向业务 `ProviderType` 增加协议适配语义。

Model Registry 包含以下事实：

| 概念 | 职责 |
|---|---|
| `ModelProviderDefinition` | 记录厂商 Key、显示名、AgentScope Adapter Key、默认 Endpoint、可用区域、数据保留与训练政策 |
| `ModelCatalogEntry` | 记录 Provider、Model ID、Revision、Context/Output Token 上限、Tool/Structured Output/Vision 能力、Region 和生命周期 |
| `ModelPriceSchedule` | 记录绑定精确 Catalog Revision 的输入/输出/缓存 Token 单价、币种、来源和只追加生效时间点 |
| `ModelConnection` | 记录 `USER/TEAM/ORGANIZATION` 所有权、Endpoint、Region、Credential Reference、账单主体、健康与版本 |
| `AgentConfigurationVersion` | 记录 AgentProfile 的追加配置版本，包含主模型、备用模型、Prompt、GenerateOptions、Tool/Skill/Memory/Policy 引用和预算 |
| `ResolvedModelSelection` | 运行前生成的受信结果，包含精确 Provider、Connection、Model ID/Revision、Adapter、价格和策略哈希，不包含凭证明文 |

`AgentProfile` 保持 Agent 稳定身份和生命周期。`AgentConfigurationVersion` 使用独立、单调的 Configuration Revision，避免把乐观锁 Version、状态迁移与运行配置混为同一个含义。

### 连接所有权与凭证

- `ORGANIZATION` ModelConnection 由组织管理员管理，用于企业网关、统一账单和组织默认模型。
- `TEAM` ModelConnection 由团队管理员管理，用于团队配额、区域和成本归属。
- `USER` ModelConnection 由当前成员管理，只在组织 PolicyPack 允许 BYOK 时创建。
- Personal Agent 可使用当前 Owner 的 USER Connection，也可使用授权的 TEAM/ORGANIZATION Connection。
- Team Agent 和 Specialist Agent 使用 TEAM/ORGANIZATION Connection。团队耐久任务不依赖某个成员的个人 Key。

API Key 只写入 `CredentialStore`。ModelConnection 只保存 Credential Reference、Subject、版本和健康摘要。创建成功后 API 不返回 Key，前端不缓存 Key，验证、轮换、撤销和过期生成 AuditEvent。

Owner、Credential Subject 和 Billing Subject 分别保存并执行以下矩阵：

| ModelConnection Owner | 允许的 Credential/Billing Subject |
|---|---|
| `USER` | 同一 `PRINCIPAL` |
| `TEAM` | 同一 `TEAM` 或所属 `ORGANIZATION` |
| `ORGANIZATION` | 同一 `ORGANIZATION` |

Credential Binding 只包含 Tenant-qualified Credential Reference、Subject 和乐观 Credential Version。轮换保持 Connection/Credential 稳定身份与 Subject，只追加下一 Credential Version，并将健康重置为 `UNKNOWN`。

健康快照绑定精确 Credential Version，保存 `UNKNOWN/HEALTHY/UNHEALTHY`、检查时间、最后成功时间、连续失败数与平台稳定错误码。Provider 原始错误、响应 Body、Header 和凭证线索不进入 Connection、日志或 API。验证写入同时使用 Expected Connection Version 和 Expected Credential Version，防止旧探测覆盖轮换后的当前健康。

### 模型选择与策略解析

`AgentConfigurationVersion` 使用连续、只追加的 Configuration Revision。每个版本绑定同一 AgentProfile、直接前一 Revision、精确 AgentTemplateVersion/ContentHash、PERSONAL/TEAM ModelBinding、模板验证后的 Prompt/Tool/Schema 结果、批准 Skill、Memory/Budget Policy Reference、PolicyPack Reference、SafeGenerateOptions、配置 Hash 与创建审计。

直接 ModelBinding 的 Primary 必填，Fallback 可选且必须不同；两者分别保存稳定 Connection ID、不可变 Connection Owner 快照、精确 Catalog Coordinate、Provider Definition Hash 与 Catalog Content Hash，并按相同 Scope、Provider、Region 和目录规则独立校验。配置不固定 Connection 乐观版本或 Credential Version，使凭证轮换可以保持稳定 Connection 身份；运行前再把当前 Connection/Credential Version 固定进 ResolvedModelSelection 和 PolicySnapshot。

PERSONAL Binding 仅支持 `DIRECT`。TEAM Binding 对执行 Agent 支持 `DIRECT` 或 `INHERIT_TEAM_DEFAULT`；默认 Personal Agent 的 TEAM 侧固定为 `ORCHESTRATION_ONLY`。未声明 TEAM Binding 时不得使用 PERSONAL Binding。Team/Organization ModelDefault 同样只追加并绑定精确 TemplateVersion 与 ExecutionScope；Team 默认只能使用同 Team/Organization Connection，Organization 默认只能使用 Organization Connection。

公开写入边界使用显式字段 DTO。客户端只提交稳定 Connection ID、Catalog Entry ID/Revision、补充指令、批准 Skill、Memory/Budget Reference 和安全生成选项，不能提交 Owner、Provider/Adapter、模型名称、Hash、System Prompt、Tool、Schema、PolicyPack、Endpoint、Credential Reference 或任意 Map。

新 AgentProfile 按以下顺序得到初始配置：

```text
AgentProfile 显式配置
  -> Team 按 AgentType 的默认配置
  -> Organization 按 AgentType 的默认配置
  -> 缺少可用配置时失败关闭
```

用户保存个人设置后，AgentProfile 始终引用精确 ModelConnection 和 ModelCatalogEntry，运行时不按名称猜测或随机选择。解析器取以下交集：

```text
模型目录处于 ACTIVE
∩ ModelConnection 处于 ACTIVE 且凭证可用
∩ AgentType 所需 Tool / Structured Output / Vision / Context 能力
∩ Organization 数据级别、区域、保留和训练策略
∩ Team 允许列表、预算和配额
∩ 当前 Principal 对 Connection 的使用权
```

主模型不可用时只能切换到同一 AgentConfigurationVersion 中明确声明且独立通过策略校验的 Fallback。平台不自动扩大到其他厂商或更宽数据区域。主模型和 Fallback 都不可用时返回稳定 `MODEL_UNAVAILABLE`。

### 版本固定与运行语义

- 修改 Agent 模型时新增 `AgentConfigurationVersion`，不覆盖历史版本。
- 新 Conversation 使用当前配置版本。已存在 Conversation 继续使用 AgentRuntimeSession 固定版本。
- 已存在 Conversation 只能在没有活动调用和 Pending Interrupt 的安全点显式刷新配置，刷新记录新的 Runtime Configuration Segment。
- TaskExecution 通过 PolicySnapshot 固定 AgentConfigurationVersion、Provider、Connection、Model ID/Revision、价格和策略哈希。运行中只允许固定 Fallback 和 SafetyEnforcementOverlay 实时收紧。
- 重试新 attempt 默认沿用原 PolicySnapshot。成员明确选择新配置时创建新 PolicySnapshot 并在审计中记录原因。
- 凭证轮换保持 ModelConnection 稳定身份，调用时从 CredentialStore 解析当前有效秘密。凭证撤销通过 SafetyEnforcementOverlay 在下一个模型边界生效。

### AgentScope 适配

`crewscope-primary` 保留为本地开发和单模型部署的 Bootstrap Slot。多租户模型选择使用受信 `AgentScopeModelFactory`，根据 ResolvedModelSelection 显式构建 AgentScope `Model`，不依赖唯一 Spring `Model` Bean。

ModelProviderDefinition 将产品厂商与 AgentScope Adapter 分开记录：

```text
providerKey = deepseek
adapterKey = openai-compatible
agentScopeFactory = openai
modelId = deepseek-v4-flash
```

Provider Adapter 负责 Endpoint 归一化、Formatter、GenerateOptions 和能力兼容策略。DeepSeek 在 Tool 与 Structured Output 同时存在时使用 `nativeStructuredOutputWithTools(false)`。这类兼容开关由平台 Adapter 管理，不暴露为用户可修改参数。

M5-S01 对照 AgentScope Java 2.0.0 源码和真实 `HarnessAgent + OpenAIChatModel` 本地双端点调用后，冻结以下 Adapter 结构：

```text
AgentScopeModelFactory
  -> Map<AdapterKey, AgentScopeModelProviderAdapter>
  -> build(TrustedModelBuildRequest, CredentialHandle)
  -> Connection-scoped AgentScope Model

TrustedModelBuildRequest
  = Provider/Adapter
  + Connection ID/Version
  + 受控 Endpoint/EndpointPath
  + Model ID/Revision
  + FormatterPolicy
  + StructuredOutputCompatibility
  + SafeGenerateOptions
  + Capability/Policy Hash
```

`AgentScopeModelProviderAdapter` 是服务端受信 SPI，通过 Spring `List<AgentScopeModelProviderAdapter>` 收集并按唯一 Adapter Key 建立不可变索引。ModelConnection 不注册为 Spring `Model` Bean，也不注册到进程全局 `ModelRegistry`。AgentScope OpenAI Starter 的 `@ConditionalOnMissingBean(Model.class)` 继续只承担 `crewscope-primary` Bootstrap Slot；企业动态模型由 Factory 按精确 Connection Version 创建和回收。

连接字段在 Model Builder 阶段固定。传入 Agent 的 `GenerateOptions` 只从平台白名单值对象生成，允许 Temperature、TopP、Token 上限、Reasoning、Cache、Parallel Tool、Seed 和受控 ExecutionConfig。它不能携带 `apiKey`、`baseUrl`、`endpointPath`、`modelName`、任意 Header、Query 或 Body 参数。AgentScope 2.0.0 的请求级 `GenerateOptions` 对同名 Builder 配置具有更高优先级，直接透传会造成 Connection 越权和模型串用。

Formatter、Native Structured Output 与 Tool 兼容标志由 Adapter 根据 Provider 和 Model Revision 固定。OpenAI 使用原生 `response_format`；DeepSeek 使用 `DeepSeekFormatter` 和合成 `generate_response` Tool。主模型与 Fallback 分别构建并分别校验 Connection、Endpoint、Credential、Formatter 和能力。组合后采用两者中更保守的 Structured Output 策略，不能依赖 AgentScope 内置 Fallback 包装器推断 `supportsNativeStructuredOutputWithTools`。

AgentScope 的 `ModelConfig.maxRetries` 会合入 `GenerateOptions.ExecutionConfig.maxAttempts`。主模型耗尽该预算后，ReActAgent 才在同一逻辑模型调用上通过 `switchOnFirst` 进入显式 Fallback。CrewScope 在 PolicySnapshot 中分别固定两套模型坐标，并由累计遥测区分 Primary Attempt、Retry Attempt 和 Fallback Attempt。

Connection-scoped Model 的缓存键至少包含 Organization、Connection ID/Version、Credential Version、Model ID/Revision、Adapter Version、Formatter/Compatibility Hash 和 SafeGenerateOptions Hash。凭证轮换、Connection 撤销、模型 Revision 或兼容策略变化会使旧实例不可再领取；Model 实例、Credential Handle 和缓存对象不进入 AgentState、配置 JSON、日志或 Artifact。

### V20 物理持久化

M5-D10 使用 `model_provider_definition`、`model_catalog_entry`、`model_price_revision`、`model_connection`、`agent_template_version`、`agent_configuration_version`、`agent_configuration_model_binding` 和 `agent_model_default` 八张表落地本 ADR。产品 Provider Definition 是全局可信根；租户内 Connection、Template、Configuration 和 Default 全部使用完整 Organization/Team/Owner 复合坐标。

`DIRECT` Binding 对 Primary 和可选 Fallback 分别固定 Connection、Provider Definition Hash、Catalog Revision 和 Catalog Content Hash；`INHERIT_TEAM_DEFAULT` 与 `ORCHESTRATION_ONLY` 不允许携带模型坐标。Catalog、Price、Template、Configuration 和 Default 使用精确 Revision、内容 Hash、唯一键和受限删除保持历史可复现；Catalog、Template、Configuration 和 Default 同时保存直接前驱。

ModelConnection 绑定精确 Credential Subject 和 Version，并由数据库同时约束 Owner、Credential Subject、Billing Subject、健康快照和撤销形状。应用 Adapter 继续负责连续 Revision 的并发创建与只追加写语义，数据库负责终局冲突和跨 Scope 阻断。

### API 与前端

模型管理 API 包含：

```text
GET    /api/v1/model-providers
GET    /api/v1/model-catalog?agentProfileId={agentProfileId}
GET    /api/v1/model-connections?ownerType={ownerType}&ownerId={ownerId}
POST   /api/v1/model-connections
POST   /api/v1/model-connections/{connectionId}/verify
POST   /api/v1/model-connections/{connectionId}/rotate-credential
DELETE /api/v1/model-connections/{connectionId}

GET    /api/v1/agent-profiles/{agentProfileId}/configurations/current
GET    /api/v1/agent-profiles/{agentProfileId}/configurations
POST   /api/v1/agent-profiles/{agentProfileId}/configurations
POST   /api/v1/agent-profiles/{agentProfileId}/model-preflight
POST   /api/v1/conversations/{conversationId}/agent-configuration-refresh
```

`model-catalog` 返回当前 Principal 在指定 AgentProfile 上真实可选的交集，前端不自行合并全量模型和权限。配置写入使用 `Idempotency-Key` 和 `If-Match`，返回 Command Receipt 并等待投影追平。

前端提供两个设置面：

- `我的 Personal Agent`：选择主模型、Fallback、输出偏好和授权的 USER/TEAM/ORGANIZATION Connection，显示生效范围、成本归属和新旧 Conversation 语义。
- `模型与凭证`：组织/团队管理员管理 ModelConnection、允许模型、默认配置、健康、区域、价格、配额和变更记录。

界面显示产品厂商、模型 ID、能力、价格、连接所有者、区域和健康。Adapter Key、Endpoint 私有参数和 Credential Reference 不进入普通成员页面。

### 观测与审计

AgentRun 和模型调用遥测记录 Organization、Team、AgentProfile/Configuration Revision、Provider、Connection ID/Version、Model ID/Revision、Primary/Fallback Role、Token、单价快照、成本、延迟、结果和 Trace。

以下操作生成 AuditEvent：ModelConnection 创建、验证、轮换、停用和撤销；ModelCatalogEntry 发布、改价、停用和 Revision 更新；AgentConfigurationVersion 创建和会话配置刷新；Policy 拒绝、Fallback 切换和 Safety Overlay 模型停用。

## 实现约束

1. 公开 API 不接受任意 Base URL、Adapter Key 或原始 GenerateOptions JSON；管理员通过受控字段创建私有网关连接。
2. AgentProfile 只能引用同 Scope 中可访问的 ModelConnection 和 ACTIVE ModelCatalogEntry。
3. ModelConnection 与 AgentConfigurationVersion 使用完整 Organization/Team/Owner 复合外键和乐观锁。
4. 模型目录变更只向前追加 Revision 和价格时间片，已归档 PolicySnapshot 的价格与模型身份不被覆盖。
5. 一个 Agent 版本的主模型与 Fallback 分别通过完整策略校验，它们可以使用不同 Provider，必须共同满足当前数据与能力要求。
6. 客户端只提交服务端目录中的稳定 ID，不提交模型显示名、单价、能力或策略结果。
7. 任务执行没有受控 Fallback 时不进行隐式模型切换。
8. 动态 Model Adapter 不能接收客户端构造的 AgentScope `GenerateOptions`、`ModelCreationContext`、Formatter、HTTP Transport 或 Model ID 字符串。
9. Adapter Key 必须唯一；缺失、重复、能力不兼容或主/Fallback 组合策略不一致时在创建 HarnessAgent 前失败关闭。
10. Credential Handle 只在受信 Adapter 构建窗口可用；异常消息、`toString`、Spring Bean 名称和缓存键不得包含秘密。

## 结果

- 每个成员可为自己的 Personal Agent 选择授权范围内的模型厂商、主模型和 Fallback。
- Team Agent 和 Specialist Agent 使用独立配置与团队/组织凭证，不随 Personal Agent 模型自动变化。
- 企业策略限制可选模型、数据区域、预算、配额和 BYOK。
- 模型配置、任务运行、成本和凭证主体形成可追溯链路。
- `crewscope-primary` 继续支持开发和单模型部署，企业多模型使用动态受信解析。

## 验证

1. 两个成员选择不同 Provider/Model，各自 Conversation 使用精确配置且 AgentState 保持隔离。
2. 用户可见目录只包含组织、团队、数据、能力、预算和凭证权限的交集。
3. USER Connection 只能被所有者 Personal Agent 使用，Team/Specialist Agent 引用时失败关闭。
4. 禁用模型、撤销 Connection、过期凭证、超预算和能力不匹配在 AgentScope 调用前被拒绝。
5. 配置变更生成新 Revision，新 Conversation 生效，旧 Conversation 保持固定版本并可在安全点刷新。
6. TaskExecution 全程保持 PolicySnapshot 固定的模型与单价，未声明模型不会成为隐式 Fallback。
7. DeepSeek 显示和审计为 `deepseek`，调用层通过 `openai-compatible` Adapter 运行 Tool 与 Structured Output 兼容策略。
8. API、日志、事件、Artifact、AgentState 和前端状态中不出现 Key、Credential Reference 或 Provider 原始错误。
9. DeepSeek 与备用 OpenAI Provider 通过两个独立 Connection 完成 Tool + Structured Output；重试保持原 Connection，Fallback 只使用自己的 Endpoint、Key、模型和 Formatter。

## 重新评估条件

- 所有模型流量收敛到统一企业 Model Gateway；
- AgentScope 提供原生多租户 Model Registry 与凭证动态解析；
- 引入按请求动态路由、模型竞价或自动评测选型；
- Team/Specialist Agent 需要使用发起成员个人账单主体。
