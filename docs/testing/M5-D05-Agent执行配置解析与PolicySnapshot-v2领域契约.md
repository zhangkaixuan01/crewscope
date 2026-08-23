# M5-D05 Agent 执行配置解析与 PolicySnapshot v2 领域契约

> 任务：`M5-D05`<br>
> 日期：2026-08-23<br>
> 状态：通过<br>
> 关联决策：[ADR-015](../adr/ADR-015-Agent模型目录、连接与配置解析.md)、[ADR-016](../adr/ADR-016-Agent所有权、模板与执行配置.md)

## 1. 交付范围

M5-D05 在 `crewscope-domain` 与 `crewscope-application` 交付：

- 基于共享 WorkItem、团队责任链、团队预算和团队 SLA 服务端事实的 `AgentExecutionScopePolicy`；
- `AgentExecutionConfigurationResolver`、Direct Binding 与 Team→Organization 默认解析；
- Primary/Fallback 分别执行的 Provider、Catalog、Connection、能力、区域、数据、Token、权限、责任、预算、配额和价格 Preflight；
- `ResolvedModelSelection` 对 Provider Definition/Adapter、Connection/Version、Credential Version、Catalog/Model Revision 和 Price Revision/单价的精确固定；
- `ResolvedAgentExecutionConfiguration` 对 AgentProfile、Template、Configuration、ExecutionScope、默认来源和 Prompt/Tool/Skill/Schema/Policy Hash 的规范固定；Prompt、Tool 和 Skill 使用带版本、长度前缀的规范编码；
- PolicySnapshot Schema v1/v2 分流、v1 原 Hash 兼容、v2 全坐标 Hash、未知 Schema 失败关闭；
- SafetyEnforcementOverlay 在固定模型坐标之上实时停用模型、Connection、Credential、Principal 或 Membership，不能选择额外模型或恢复权限。

数据库 Schema v2 列、Repository Adapter 与 V20 迁移由 M5-D10/I01 交付；短 TTL 健康缓存和 AgentScope Model 装配由 M5-I03/I04 交付；Task 委托接线由 M5-A04 交付。

## 2. 固定领域规则

1. PERSONAL/TEAM 由服务端任务事实判定，发起成员和客户端字段不能覆盖 ExecutionScope。
2. PERSONAL 只解析 PERSONAL Binding；TEAM 只解析 TEAM Binding，永不回退 PERSONAL Binding。
3. `INHERIT_TEAM_DEFAULT` 先查询精确 Team + TemplateVersion + ExecutionScope；Team 默认不存在时查询 Organization 默认。
4. Team 默认存在但不可用时直接失败，不能跳过并扩大到 Organization 默认；默认缺失、重复、坐标错误和越权均失败关闭。
5. Primary 与 Fallback 使用相同完整 Preflight 独立校验；任一显式 Fallback 不可用时整个配置解析失败，平台不补充第三个候选。
6. USER Connection 只允许 Owner USER 发起的 USER-owned Agent PERSONAL 执行；TEAM 执行禁止 USER Connection。
7. 当前 Principal、Team participation、责任链、Connection 使用权、预算和配额都是运行前事实，任一失败返回稳定非敏感拒绝码。
8. 模型能力由 Template `model.*` 需求映射到 Catalog 能力，并与 Organization/Team 能力、Region、Retention、Training 和 Token 策略求交集。
9. 配置保存时不固定 Connection/Credential 乐观版本；每次新运行在 `ResolvedModelSelection` 固定当前 Connection Version 与 Credential Version。
10. 价格按运行时间点选择，只固定精确 Catalog Revision 上已生效的 Price Revision、币种和输入/输出/缓存单价。
11. Prompt 基线和补充指令按独立字段进入长度前缀编码，空值与存在值显式区分；自由文本中的分隔符不能改变字段边界或产生相同 Prompt Hash。
12. PolicySnapshot v1 继续使用 M3 原规范 Hash，M5 字段为空；PolicySnapshot v2 将完整 `ResolvedAgentExecutionConfiguration` Hash 纳入快照 Hash。
13. 未知 PolicySnapshot Schema 版本失败关闭；Schema v2 不能通过 v1 supersede 路径降级。
14. Safety Overlay 只能对已固定 Primary/Fallback 增加限制；未固定 Fallback 永远不能由 Overlay 或运行时补出。

## 3. 自动化验证

新增 18 个自动化场景：

- `AgentExecutionScopePolicyTest`：1 个场景覆盖 PERSONAL 与四类 TEAM 服务端事实；
- `ResolvedModelSelectionTest`：6 个场景覆盖运行坐标、价格 Hash、能力/区域/数据/Token、Principal/责任/预算/配额/Grant、USER Connection 隔离和生命周期稳定拒绝码；
- `PolicySnapshotTest`：新增 2 个场景覆盖 Schema v2 完整固定、复原防篡改和未知 Schema 失败关闭；
- `SafetyEnforcementOverlayTest`：新增 1 个场景覆盖固定模型上的单调实时收紧和无隐式 Fallback；
- `ResolvedAgentExecutionConfigurationHashTest`：1 个场景覆盖 Prompt 基线/补充指令分界与分隔符碰撞回归；
- `AgentExecutionConfigurationResolverTest`：7 个场景覆盖 Direct、Team 默认、Organization 默认、缺失/歧义、Fallback 独立 Preflight、不可用 Team 默认不降级、Team participation 与价格缺失。

验证结果：

| 验证 | 结果 |
|---|---|
| M5-D05 新增测试 | `18 / 18` |
| Domain 模块回归 | `500 / 500` |
| Application 模块回归 | `331 / 331` |
| 全仓 Maven 回归 | `1651 / 1651` |
| 文档链接与差异格式门禁 | 通过 |

全仓回归包含 AgentScope、Redis、PostgreSQL、Flyway、Git 与 Docker Sandbox 集成测试。
