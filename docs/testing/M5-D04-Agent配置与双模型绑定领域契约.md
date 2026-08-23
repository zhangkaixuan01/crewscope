# M5-D04 Agent 配置与双模型绑定领域契约

> 任务：`M5-D04`<br>
> 日期：2026-08-23<br>
> 状态：通过<br>
> 关联决策：[ADR-015](../adr/ADR-015-Agent模型目录、连接与配置解析.md)、[ADR-016](../adr/ADR-016-Agent所有权、模板与执行配置.md)

## 1. 交付范围

M5-D04 在 `crewscope-domain` 与 `crewscope-application` 交付：

- 只追加 `AgentConfigurationVersion`、连续 Configuration Revision、前一版本引用和规范配置 Hash；
- PERSONAL/TEAM `AgentModelBinding`、Primary/Fallback、Team 默认继承和默认 Personal 编排态；
- 非秘密 `AgentModelSelection` 与 USER/TEAM/ORGANIZATION Ownership/ExecutionScope 矩阵；
- 模板验证后的 Prompt/Tool/Schema、批准 Skill、Memory/Budget/PolicyPack Reference 与 SafeGenerateOptions；
- 只追加 `AgentModelDefault`、连续 Default Revision 和 Organization/Team 默认边界；
- AgentConfiguration/ModelDefault Repository Port 与显式客户端 Draft 白名单。

模型可选交集、Connection Grant、当前健康/凭证复验、默认解析、PolicySnapshot 固定和 Safety Overlay 由 M5-D05 交付；数据库落地由 M5-D10 交付；写入 API 与 Command Receipt 由 M5-A02 交付。

## 2. 固定领域规则

1. Configuration Revision 从 1 连续追加，只能引用同 Profile 的直接前一 Revision；历史版本不可覆盖。
2. PERSONAL Binding 只能 DIRECT；TEAM 执行 Agent可 DIRECT 或 INHERIT_TEAM_DEFAULT；默认 Personal Agent 的 TEAM 侧只能 ORCHESTRATION_ONLY。
3. 模板允许的执行范围必须具有对应 Binding，模板未允许的范围不得携带 Binding；TEAM 永不回退 PERSONAL。
4. Primary 必填，Fallback 可选且必须不同；Primary/Fallback 按相同 Scope、Provider、Catalog、Region 与 Ownership 规则独立验证。
5. USER Connection 只允许 USER-owned Agent Owner 的 PERSONAL Binding；TEAM Execution、TEAM/ORGANIZATION-owned Agent 和 ModelDefault 均禁止 USER Connection。
6. ModelSelection 只保存 Connection ID/Owner、Catalog Coordinate/Hash 和 Provider Hash，不保存 Endpoint、Credential、Key、Header 或任意秘密。
7. 配置固定 TemplateVersion/ContentHash、模板验证后的 Prompt/Tool/Schema、Skill、Memory/Budget/PolicyPack、SafeGenerateOptions 和规范 Hash。
8. AgentModelDefault 按 Scope、TemplateVersion、ExecutionScope 和连续 Revision 只追加；Team 默认使用同 Team/Organization Connection，Organization 默认只使用 Organization Connection。
9. 客户端 Draft 只包含稳定 ID 与受控配置字段；Owner、Provider/Adapter、Model 名称、Hash、Prompt 基线、Tool、Schema、PolicyPack、Endpoint、Credential 和任意 Map 均由服务端边界拒绝。

## 3. 自动化验证

新增 14 个自动化场景：

- `AgentConfigurationVersionTest`：8 个场景，覆盖双执行绑定、主/Fallback、连续 Revision、配置 Hash、Ownership/ExecutionScope 矩阵、默认 Personal 编排态、安全 GenerateOptions、Skill 与秘密字段隔离；
- `AgentModelDefaultTest`：3 个场景，覆盖 Team/Organization 默认 Connection 边界、连续 Default Revision、内容 Hash 与复原防篡改；
- `AgentConfigurationClientBoundaryTest`：3 个应用边界场景，证明客户端只能提交稳定选择坐标和受控配置字段，不能声明编排态、PERSONAL 默认继承或向 Repository 写入 Draft/Map。

验证结果：

| 验证 | 结果 |
|---|---|
| M5-D04 专项测试 | `14 / 14` |
| Domain 模块回归 | `457 / 457` |
| Application 模块回归 | `323 / 323` |
| 全仓 Maven 回归 | `1586 / 1586` |
| 文档链接与差异格式门禁 | 通过 |

全仓回归包含 AgentScope、Redis、PostgreSQL、Flyway、Git 与 Docker Sandbox 集成测试。
