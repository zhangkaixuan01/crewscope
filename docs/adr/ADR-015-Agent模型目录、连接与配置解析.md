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
| `ModelCatalogEntry` | 记录 Provider、Model ID、Revision、上下文窗口、Tool/Structured Output/Vision 能力、Token 单价和生命周期 |
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

### 模型选择与策略解析

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

## 重新评估条件

- 所有模型流量收敛到统一企业 Model Gateway；
- AgentScope 提供原生多租户 Model Registry 与凭证动态解析；
- 引入按请求动态路由、模型竞价或自动评测选型；
- Team/Specialist Agent 需要使用发起成员个人账单主体。
