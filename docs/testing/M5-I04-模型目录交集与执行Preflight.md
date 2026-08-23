# M5-I04 模型目录交集与执行 Preflight

## 1. 交付范围

M5-I04 已将 M5-D05 领域规则与 M5-I01～I03 基础设施组装为正式应用链路：

- `SelectableModelCatalogService` 只返回当前 Principal 的可用 Connection、Provider/Model 允许列表、Template 模型能力、Organization/Team 数据与区域策略、预算、配额、有效价格和凭证可用性的交集；
- 目录投影只包含稳定 Connection/Model 坐标、产品显示信息、能力、区域和价格，不包含 Endpoint、Adapter 私有参数、Credential Reference 或 Provider 错误；
- `AgentExecutionConfigurationService` 加载精确 AgentProfile、当前或指定 Configuration Revision 和内容 Hash 匹配的 Team/Organization Template；
- Team 执行按 `DIRECT -> Team Default -> Organization Default` 的显式规则解析；Team 默认一旦选中后不会因不可用而隐式降级到 Organization 默认；
- Primary 和 Fallback 分别执行完整 Preflight，不从目录中自动扩大 Fallback；
- `ResolvedAgentPolicySnapshotService` 先完成配置和模型解析，再通过 `PolicySnapshot.initialV2` 将 Agent、Template、Configuration、Default、Provider、Connection/Credential Version、Model/Price Revision 与策略 Hash 一次性固定并持久化。

## 2. 凭证与健康缓存

`CachedModelConnectionAvailabilityVerifier` 在 AgentScope Model Factory 之前执行：

- 验证 Connection 处于 `ACTIVE`，健康结果为当前 Credential Version 的 `HEALTHY`；
- 通过 `CredentialStore.describe` 只读取非秘密元数据；
- 验证 Credential ID/Subject、Provider、Connection Ref、Credential Type 和 Secret Version 的完整坐标；
- 拒绝已撤销、缺失、坐标不符或已过期凭证，统一返回稳定 `CREDENTIAL_UNAVAILABLE`；
- 缓存键包含 Organization、请求 Principal、Connection ID/Version 和 Credential Version，不同 Principal 不共享可用性决策；
- 缓存有界、LRU、默认 TTL 30 秒且最长 5 分钟，有效期不会越过 Credential 到期时间；
- Connection 创建、验证、轮换和撤销事务成功后主动失效该 Connection 的全部缓存版本。

## 3. 失败关闭边界

以下事实在构建 AgentScope Model 前失败关闭：

- Agent、Template、Provider、Catalog 或 Connection 已停用；
- Credential 撤销、过期、轮换后版本不符或绑定坐标不符；
- Team/Organization Default 缺失、歧义或坐标不符；
- Connection 使用权、所有权或团队参与事实不符；
- Template 所需 Tool/Structured Output/Vision 等模型能力不符；
- 数据保留、训练使用、区域、Context/Output Token 上限不符；
- 预算、配额、责任或当前有效价格不可用。

## 4. 自动化验证

- `AgentExecutionConfigurationResolverTest`：8 个场景覆盖 Direct、Team/Organization Default、缺失/歧义、Fallback 独立校验、不可用默认不降级、参与、价格、能力、区域与预算；
- `CachedModelConnectionAvailabilityVerifierTest`：4 个场景覆盖精确版本缓存、Principal 权限隔离、主动失效、过期凭证和非当前健康状态；
- `SelectableModelCatalogServiceM5I04Test`：验证只返回权限、允许列表、能力、区域、数据、健康、价格交集；
- `AgentExecutionConfigurationServiceM5I04Test`：2 个场景验证当前配置加载、精确 Template Hash 和 Team/Organization 重复坐标失败关闭；
- `ModelPreflightApplicationConfigurationM5I04Test`：2 个场景验证唯一 Spring 装配图、PolicySnapshot v2 装配器和缓存/目录上限启动校验；
- `PolicySnapshotTest`、`ResolvedModelSelectionTest` 和 M5-I01 PolicySnapshot JSONB 持久化测试继续作为完整坐标固定与 Hash 闭合回归。

回归结果：

- Domain 完整测试：500 个，0 失败、0 错误、0 跳过；
- Application 完整测试：342 个，0 失败、0 错误、0 跳过；
- Server 完整测试：224 个，0 失败、0 错误、0 跳过；
- M5-I04 专项回归：Application 14 个、AgentScope Adapter 6 个、Server 装配 5 个，全部通过；
- 213 个 Markdown 文件链接检查通过；
- `git diff --check` 通过。
