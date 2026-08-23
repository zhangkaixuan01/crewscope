# M5-I01 模型与 Agent 配置持久化契约

> 完成日期：2026-08-23
> 范围：`crewscope-application`、`crewscope-domain`、`crewscope-infrastructure`

## 1. 交付结果

M5-I01 将 M5-D01 至 M5-D05、M5-D10 的模型目录、模型连接、Agent Template、AgentProfile、Agent Configuration、Model Default 和 PolicySnapshot v2 领域事实接入 PostgreSQL，并保持追加修订、乐观生命周期、租户隔离和对象图完整性。

本任务交付以下 Spring Repository Adapter：

| Adapter | 持久化事实 |
|---|---|
| `JdbcModelRegistryRepositoryAdapter` | Provider Definition、Catalog Revision、Price Revision |
| `JdbcModelConnectionRepositoryAdapter` | USER/TEAM/ORGANIZATION ModelConnection 与健康、轮换、生命周期版本 |
| `JdbcAgentTemplateRepositoryAdapter` | Organization/Team Publisher 下的不可变 Template Version 与生命周期 |
| `JpaAgentProfileRepositoryAdapter` 扩展 | 非默认 Template Agent 创建、乐观更新和 Organization 分页 |
| `JdbcAgentConfigurationRepositoryAdapter` | AgentConfigurationVersion 与 PERSONAL/TEAM Binding 对象图 |
| `JdbcAgentModelDefaultRepositoryAdapter` | Organization/Team Model Default 修订流 |
| `TaskRuntimeExtendedPersistenceMapper` 扩展 | PolicySnapshot Schema v2 的 ResolvedAgentExecutionConfiguration JSONB |

Application Repository Port 同步增加创建和有界分页能力。所有页使用稳定排序，`offset >= 0`、`1 <= limit <= 200`，非法边界失败关闭。

## 2. 修订、并发与生命周期

Catalog、Price、Template、Configuration 和 Model Default 都是连续追加流。写入事务先按权威 Stream Key 获取 PostgreSQL Transaction Advisory Lock，再读取已提交 Head，只接受精确的下一个 Revision。两个并发写入者提交相同下一修订时只有一个成功，失败方不能覆盖已提交事实。

Provider、Catalog Revision、Template Version 和 AgentProfile 的可变生命周期使用上一版本谓词更新。更新 SQL 同时固定不可变 Content Hash；陈旧版本、缺失对象和内容坐标漂移均返回领域冲突，不执行静默覆盖。

事务 Adapter 保持可被 Spring 默认 CGLIB 代理，依赖均通过构造器注入。Spring 独立测试上下文只导入 M5 持久化组件即可完成 Repository Port 装配。

## 3. 对象图与查询边界

Agent Configuration 查询先用一次联表查询加载目标页面的 Header、PERSONAL/TEAM Binding 及主、备用 Connection Owner 坐标，再固定加载一次 AgentProfile 和一次 Template。Binding 数量和页面大小不会产生逐行 Profile/Connection 查询。

Hibernate 查询统计验证 Configuration 页面从 1 个修订扩大到 3 个修订时，JPA Profile 查询仍保持 1 次。Provider、Catalog、Connection、Template、Profile 和 Configuration 均提供稳定分页；历史 Configuration 按 Revision 倒序返回。

所有 Organization-scoped 查询显式携带 `organization_id`，Connection Owner 查询携带精确 Owner Type 与 Owner ID。跨 Organization 读取返回空结果；伪造跨 Scope 的 Connection、Credential Subject 或 Billing Subject 由复合外键和领域映射共同拒绝。

## 4. PolicySnapshot Schema v2

`ResolvedAgentExecutionConfigurationJsonCodec` 显式序列化和重建 PolicySnapshot v2 中的完整执行配置，包括：

- Agent、Template、Configuration 与 PERSONAL/TEAM ExecutionScope；
- Primary/Fallback Model、Connection Owner 和精确 Catalog Revision；
- Provider、Catalog、Template、Configuration、Prompt、Policy 与 Resolution Hash；
- 价格、预算、Memory、Generate Options 和创建审计坐标。

Codec 不使用领域对象的通用反射序列化，JSON 形状由版本化字段白名单固定。Credential ID、Credential Reference、API Key 和 Provider 秘密不进入 JSONB。Schema v1 保持原路径可读，Schema v2 缺少执行配置、未知 Schema 或数据库约束越界均失败关闭。

## 5. V22 修订身份升级

M5-D10 的 V20 首版表把 `model_catalog_entry.id` 设为单列主键，与领域中的稳定 Entry ID 语义冲突：相同模型发布第二个 Catalog Revision 时需要沿用 ID，因此会触发主键冲突。V20 的 Price 主键也没有包含 Catalog Revision，使不同 Catalog Revision 无法各自从 Price Revision 1 开始。

`V22__model_catalog_revision_identity.sql` 保留既有 V20 校验和并执行向前升级：

- Catalog 主键调整为 `(id, catalog_revision)`；
- Catalog Previous Revision 使用 `(id, provider_key, model_id, previous_catalog_revision)` 自引用外键，禁止稳定 ID、Provider 或 Model 在修订链中漂移；
- Price 主键调整为 `(catalog_entry_id, catalog_revision, price_revision)`；
- Price 生效时间唯一键和有效价格查询索引加入 Catalog Revision。

V21→V22 升级测试先保留一组 Catalog/Price Revision 1，再用相同 Entry ID 写入 Catalog Revision 2，并验证两个 Catalog Revision 的价格流都可从 Revision 1 开始；伪造跨 Model 的 Previous Revision 被数据库拒绝。

## 6. 自动化证据

专项持久化与升级命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  -Dtest=M5I01ModelAgentPersistenceIntegrationTest,V22ModelCatalogRevisionMigrationIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

6 个 M5-I01 PostgreSQL 对象图场景和 1 个 V21→V22 升级场景通过，覆盖完整往返、跨 Publisher 精确 Template 解析、分页与 N+1、四类并发 Revision、唯一 Current Default、跨 Scope、乐观生命周期、Content Hash 和 Spring 装配。

PolicySnapshot v2 专项命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  -Dtest=M3TaskRuntimePersistenceIntegrationTest#roundTripsPolicySnapshotSchemaV2AndRejectsUnknownSchemas \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Schema v2 JSONB 往返、Schema v1 兼容路径、跨 Scope、秘密字段排除和未知 Schema 数据库拒绝均通过。Infrastructure Reactor 全量 `492/492` 通过；全仓 Maven `1659/1659` 通过。

## 7. 后续边界

M5-I01 保存 Credential 的不可恢复引用和版本，不读取模型密钥。M5-I02 将 ModelConnection 接入 CredentialStore，交付创建、验证、轮换、撤销、健康探测和短生命周期 Provider Credential Handle，并验证明文零持久化、零返回和零日志。
