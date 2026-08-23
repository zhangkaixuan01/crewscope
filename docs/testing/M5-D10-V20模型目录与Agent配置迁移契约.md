# M5-D10 V20 模型目录与 Agent 配置迁移契约

> 任务：`M5-D10`<br>
> 日期：2026-08-23<br>
> 状态：通过<br>
> 关联决策：[ADR-015](../adr/ADR-015-Agent模型目录、连接与配置解析.md)、[ADR-016](../adr/ADR-016-Agent所有权、模板与执行配置.md)

## 1. 交付范围

M5-D10 新增 `V20__model_catalog_agent_template_and_configuration.sql`，将 M5-D01 至 M5-D05 冻结的领域契约落入 PostgreSQL：

- `model_provider_definition`：产品厂商可信定义，与 AgentScope Adapter Key 分离；
- `model_catalog_entry`：精确 Provider/Model/Catalog Revision、能力、区域、生命周期和内容哈希；
- `model_price_revision`：绑定精确 Catalog Entry 的只追加价格修订和生效时间；
- `model_connection`：`USER/TEAM/ORGANIZATION` 所有权、Credential/Billing Subject、区域、健康、轮换版本与撤销事实；
- `agent_template_version`：组织/团队发布范围、连续 Template Version、Runtime Role、能力/工具/策略与内容哈希；
- `agent_configuration_version`：只追加 Configuration Revision、所有权快照、精确 Template Content Hash、受控配置与配置哈希；
- `agent_configuration_model_binding`：分离 `PERSONAL/TEAM` 执行范围，对 `DIRECT` 固定 Primary/Fallback Connection 和 Catalog 快照，对继承/编排态禁止伪造模型坐标；
- `agent_model_default`：组织/团队、Template、ExecutionScope 维度的连续默认修订。

V20 同时扩展 `agent_profile`、`agent_runtime_session` 和 `policy_snapshot`，使稳定 Agent 身份、执行 Session 和策略证据均能引用 M5 运行坐标。

## 2. Scope、版本与不可变契约

- 租户业务关联使用完整 Organization/Team/Owner 复合外键，跨 Scope 引用由 PostgreSQL 拒绝；
- Catalog、Price、Template、Configuration 和 ModelDefault 保存精确 Revision；Catalog、Template、Configuration 和 ModelDefault 另固定直接前驱，历史坐标不随当前默认漂移；
- Model Binding 对 Connection、Provider Definition Hash、Catalog Revision 和 Catalog Content Hash 使用精确复合外键；
- Credential 只以 `organization + credential + subject + version` 引用，不保存 Key 明文；
- 价格修订、Template Version、Configuration Version 和 Default Revision 的历史行均使用 `ON DELETE RESTRICT`；
- 主模型和 Fallback 完整性、Connection Scope、健康与撤销形状、Binding Kind、Hash 和时间关系由 Check/Unique/FK 约束共同失败关闭；
- 选择、当前 Revision、Connection 健康、Session Configuration 和能力包含查询具有专用 B-tree/GIN/部分索引。

## 3. M2–M4 历史数据升级

V20 只使用旧 `agent_profile.type` 和 `owner_member_id` 执行确定性回填：

| 旧 Profile | Ownership | RuntimeRole | Template |
|---|---|---|---|
| `PERSONAL` | `USER` | `PERSONAL_ASSISTANT` | `personal-assistant@1` |
| `TEAM` | `TEAM` | `TEAM_COORDINATOR` | `team-coordinator@1` |
| 有 Owner 的 `SPECIALIST` | `USER` | `SPECIALIST` | `coding@1` |
| 无 Owner 的 `SPECIALIST` | `TEAM` | `SPECIALIST` | `coding@1` |

迁移不根据显示名、Prompt 或历史输出推断 Reviewer，不伪造 ModelConnection、Credential 或 AgentConfigurationVersion。已有 Session 从 Profile 回填 Ownership、RuntimeRole 和 Template 坐标，Configuration Revision/Hash 成对保持为空，由 M5 新 Factory 创建的 Session 显式固定。

旧 `policy_snapshot` 回填 `schema_version=1`，`agent_execution_configuration` 保持为空，原 `snapshot_hash` 不变。M5 新快照使用 `schema_version=2` 并必须保存 JSON Object 形状的精确非秘密执行配置；未知 Schema Version 或 v2 缺失配置时失败关闭。

## 4. 滚动升级边界

V20 对旧 V19 节点保留有界兼容：`project_legacy_agent_profile_v20` INSERT 前触发器仅在 `ownership_type/runtime_role/template_key/template_version` 四个字段全部缺省时，按旧 Profile 权威字段投影 M5 坐标。任意部分坐标、显式伪造坐标或新形状冲突都不会被触发器“修复”，继续由数据库约束拒绝。

JPA `AgentProfileEntity` 与 `TeamPersistenceMapper` 已显式读写 Ownership、RuntimeRole 和 TemplateVersion，并通过 `reconstituteTemplateInstance` 恢复领域对象，避免新库列依赖隐式默认。

## 5. 自动化验证

`V20ModelCatalogAgentConfigurationMigrationIntegrationTest` 共 7 个场景：

- 空库全量迁移后的 8 张新表、扩展列、只追加事实和可变生命周期根；
- V19→V20 升级和 Flyway History 闭合；
- 非默认 `search_path` 仍只写入 `crewscope` Schema；
- Personal/Team/Specialist Profile、Session 和 PolicySnapshot 回填后稳定 ID、StateReference 与历史 Hash 不变；
- Scope/Revision/Hash 复合外键、冲突键、Check 约束和查询索引完整；
- 重复 Catalog/Price Revision 与越权 Connection Scope 被拒绝；
- 伪造 Profile/Policy 以及部分 Session 升级形状被拒绝。

| 验证 | 结果 |
|---|---|
| V20 专项迁移测试 | `7 / 7` 通过 |
| 通用 Flyway 迁移测试 | `3 / 3` 通过 |
| M1 AgentProfile JPA 回归 | `22 / 22` 通过 |
| M2 AgentProfile JPA 回归 | `21 / 21` 通过 |
| Domain 模块 | `498 / 498` 通过 |
| Application 模块 | `330 / 330` 通过 |
| Infrastructure 及依赖 Reactor | `477 / 477` 通过 |
| 全仓 Maven Reactor | `1641 / 1641` 通过 |
| Markdown 文档链接 | `208 / 208` 通过 |

M5-D10 自动化验收通过。V20 已为 M5-I01 的 PostgreSQL Adapter 和 M5-I04/M5-I05 的运行配置固定提供可信数据边界。
