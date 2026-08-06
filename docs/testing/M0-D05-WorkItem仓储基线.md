# M0-D05 WorkItem 仓储基线

> 模块：`crewscope-domain`、`crewscope-application`、`crewscope-infrastructure`  
> 数据库：PostgreSQL 17  
> ORM：Jakarta Persistence / Hibernate 7  
> 验证日期：2026-08-06

## 1. 目标

WorkItem 通过 Repository Port 持久化到真实 PostgreSQL。领域层保持纯 Java，应用层声明租户范围和
分页契约，基础设施层负责 JPA 映射、事务、乐观锁和数据库错误边界。

## 2. 领域持久化语义

WorkItem 当前包含：

- 强类型 WorkItem ID 与 WorkProject ID；
- Organization、Team、Workspace、WorkProject 不可变 Scope；
- Key、标题、状态和版本；
- 创建 Principal、最后修改 Principal、创建时间和最后修改时间。

`AuditMetadata` 允许历史迁移数据缺少 Principal。新 Command 必须提供可信 Principal。修改时间不能
早于创建时间或当前最后修改时间。领域状态迁移将期望提交版本加一，Repository 使用前一版本作为
数据库更新条件。

创建用例分离业务 Command 与可信 Context：Command 只包含 Project、Key 和标题；Context 包含服务端
解析的 Organization、Team、Workspace 和 Actor。领域标题长度与数据库 `VARCHAR(500)` 使用同一
常量约束。

M0 固定写入以下数据库字段，M1 再扩展为完整产品模型：

```text
item_type      = TASK
priority       = MEDIUM
source_provider = CREWSCOPE
```

## 3. Repository Port

`WorkItemRepository` 提供：

```text
create
update
findById(organizationId, workItemId)
findPage(query)
```

所有读取和更新显式携带 Organization。列表查询要求 Organization 和 Team，可选 WorkProject、状态及
Cursor。Cursor 使用 `updated_at + id` 降序 Keyset 分页，保证更新时间相同时仍有稳定顺序。

WorkItem 使用 `CANCELLED` 表达业务取消，M0-D05 不提供物理删除或通用逻辑删除。

## 4. JPA Adapter

`WorkItemEntity` 映射 `crewscope.work_item` 的全部现有列。Organization、Team、Workspace、Project 和
Principal 使用标量 UUID，不建立隐式 ORM 关联，所有租户条件由 Adapter 明确声明。

`WorkItemEntityMapper` 负责 Entity 与纯 Java WorkItem 之间的双向映射。历史行的空 Principal 映射为
空 Optional，新 Command 创建的行始终保存创建和修改 Principal。

更新使用单条带版本条件的 JPQL：

```text
UPDATE work_item
SET 状态、业务字段、updated_by、updated_at、version
WHERE organization_id = ?
  AND team_id = ?
  AND workspace_id = ?
  AND project_id = ?
  AND id = ?
  AND version = expectedVersion
```

影响行数为零时，Adapter 在相同 Organization 中查询当前版本：

- WorkItem 不存在：`AggregateNotFoundException`；
- WorkItem 存在但版本已变化：`OptimisticLockConflictException`，返回期望版本和已提交版本。

版本诊断查询使用与更新相同的 Organization、Team、Workspace、WorkProject 和 ID 条件。Scope
错配按不可见资源处理，不转换为误导性的版本冲突。

## 5. 租户与审计边界

- `findById`、`findPage` 和 `update` 都包含 Organization 条件；
- 更新同时校验 Team、Workspace 和 WorkProject Scope；
- 创建人和修改人由数据库 Organization 组合外键校验；
- 跨 Organization 查询返回空结果；
- 跨 Organization 更新按不可见资源处理；
- 跨 Organization 操作者写入由 PostgreSQL 外键拒绝；
- 同一 Organization 内错误 Team、Workspace 或 WorkProject 的更新按不可见资源处理；
- PostgreSQL 复合外键拒绝跨 Organization/Team/Workspace 组合创建；
- 状态、版本、修改人和修改时间在同一数据库语句中提交。

## 6. 自动化证据

核心测试类：

```text
AggregateIdTest
WorkItemTest
WorkItemApplicationServiceTest
JpaWorkItemRepositoryIntegrationTest
```

六个 Repository PostgreSQL 集成测试覆盖：

1. 创建、Entity 映射、M0 默认值、Scope、审计和回读；
2. 状态、版本、最后修改人和微秒时间原子提交；
3. 两个写者持有相同版本时只提交一个更新，并返回真实版本冲突；
4. 同一 Organization 内 Team、Workspace 或 WorkProject Scope 错配按不可见资源处理；
5. 查询、更新、Actor 和完整 Scope 创建的 Organization 隔离；
6. Team、WorkProject、状态过滤和 Keyset 分页无重复。

定向命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-domain,crewscope-application,crewscope-infrastructure -am \
  -Dtest=AggregateIdTest,WorkItemTest,WorkItemApplicationServiceTest,JpaWorkItemRepositoryIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：

```text
Tests run: 19, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

领域、应用和基础设施模块完整测试结果：

```text
Tests run: 45, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

全仓库 `clean verify` 结果：

```text
Tests run: 55, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 7. 后续边界

- M0-D06 在 Repository 写入旁路增加 DomainEvent 与 Outbox 同事务提交；
- M0-E01 定义完整事件信封；
- M1 扩展 WorkItem 类型、描述、优先级、评论、ResourceLink 和产品查询；
- M1 API 从服务端授权上下文解析 Organization、Team、Workspace、Project 和 Principal，不信任客户端
  直接提交的租户身份。

## 8. 结论

M0-D05 已完成。WorkItem 已具备真实 PostgreSQL 创建、查询、状态更新、审计、租户隔离、稳定分页和
乐观并发能力。
