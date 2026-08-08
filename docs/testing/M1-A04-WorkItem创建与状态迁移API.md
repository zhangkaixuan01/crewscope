# M1-A04：WorkItem 创建与状态迁移 API

> 日期：2026-08-08<br>
> 状态：已完成<br>
> 模块：`crewscope-domain`、`crewscope-application`、`crewscope-infrastructure`、`crewscope-server`

## 目标

提供 WorkProject 范围内的 Native WorkItem 与初始 Owner 原子创建、状态迁移纵向闭环。HTTP 层校验幂等键和强 ETag，应用层校验 Membership、Role Scope 与权限，领域层执行状态机，持久化层保证项目内 Key、唯一 ACTIVE Owner 和版本原子更新。

## API

```text
POST /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items
POST /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/transitions
```

创建请求包含 `key`、`type`、`title`、`description`、`priority`、`labels` 和 `dueAt`，初始状态为 `BACKLOG`，来源为 `CREWSCOPE`，当前创建者自动成为初始 Owner。创建和迁移均要求 `Idempotency-Key`，返回统一的 `202 Accepted` CommandReceipt。

状态迁移请求包含 `targetStatus`，并要求一个强 ETag：

```http
If-Match: "0"
```

缺少 `If-Match` 返回 `428 precondition_required`。弱 ETag、通配符和多值 ETag 返回 `400 invalid_if_match`。提交版本已变化时返回 `409 optimistic_lock_conflict`，响应的 `currentVersion` 提供当前提交版本。

## 权限与 Scope

- 调用者必须是同 Organization 的 ACTIVE USER 和 ACTIVE TeamMember；
- 创建要求当前有效的 Team Scope 或目标 WorkProject Scope Grant 提供 `WORK_CREATE`；
- 迁移要求当前有效的 Team Scope 或目标 WorkProject Scope Grant 提供 `WORK_PARTICIPATE`；
- 其他 WorkProject 的 Grant 不提供目标项目权限；
- Team、WorkProject 与 WorkItem 的 Organization、Team、Workspace 和 Project Scope 必须完整一致；
- 待初始化 Team 不进入 WorkItem 用例；
- 外部 Provider 投影的 WorkItem 由来源系统管理状态，本地迁移接口拒绝修改。

## 幂等、并发与事件

创建请求 Hash 覆盖 Actor、Team、WorkProject、全部业务字段、排序后的 Label 和 Causation ID。迁移请求 Hash 覆盖 Actor、Team、WorkProject、WorkItem、目标状态、期望版本和 Causation ID。相同幂等键和相同请求返回原 Receipt，相同幂等键改变内容返回 `idempotency_conflict`。

创建事务锁定目标 WorkProject，锁内检查 `(Organization, WorkProject, WorkItem Key)`，冲突返回 `work_item_key_conflict`。数据库 `(project_id, item_key)` 唯一约束保留为最终完整性边界。成功创建原子提交：

```text
WorkItem
-> ACTIVE Owner ResponsibilityAssignment
-> WORK_ITEM_CREATED DomainEvent
-> OutboxEvent
-> CommandReceipt
```

`WORK_ITEM_CREATED` Payload 固化初始 Owner Assignment ID 与 Principal ID，使时间线和审计可以从同一创建事实还原初始责任。生产 Spring 组合根只注册 M1 `WorkItemCommandService`；不注册缺少 Membership、Role Scope 和 Owner 初始化规则的旧兼容服务。

状态迁移先校验客户端期望版本，再通过带旧版本条件的单条更新提交新状态。两路请求使用同一提交版本时只有一条更新成功，另一条返回带当前版本的 `optimistic_lock_conflict`。成功迁移原子提交 `WorkItem`、`WORK_ITEM_STATUS_CHANGED`、Outbox 和 CommandReceipt，失败事务不保留 PENDING Receipt。

## 验证

专项测试覆盖：

- Application：完整字段与初始 Owner 创建、事件 Owner 坐标、Outbox、幂等重放、内容冲突、项目内 Key 冲突、Team/WorkProject Scope 权限、Membership、外部来源拒绝、状态机和版本冲突；
- Server：两条嵌套路由、Receipt、请求字段、枚举和标识校验、强 `If-Match`、428 前置条件与 409 当前版本响应；
- PostgreSQL：`findByKey`、WorkProject 行锁、两路并发同 Key 只有一份 WorkItem/ACTIVE Owner/DomainEvent/Outbox/Receipt，以及两路同版本迁移只有一次状态和版本更新。

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-server -am \
  -Dtest=WorkItemControllerTest,WorkItemCommandServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  -Dtest=M1JpaPersistenceIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

专项结果：Application 6 个测试、Server 5 个测试和 PostgreSQL 集成类 16 个测试通过，0 失败、0 错误、0 跳过。

全仓回归：7 个 Maven 模块全部成功，后端 374 个测试通过，0 失败、0 错误、0 跳过；其中 Spring Context 装配契约测试验证按业务边界注册的 Application Service 均恰好存在一个 Bean。

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
node scripts/check-doc-links.mjs
git diff --check
```

## 阶段边界

M1-A04 不实现 WorkItem 列表、详情、评论、ResourceLink、责任分配和前端。查询与协作子资源由 M1-A05 实现，责任链由 M1-A06 实现，WorkItem Web 视图由 M1-F02 和 M1-F03 实现。
