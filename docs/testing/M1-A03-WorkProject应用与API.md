# M1-A03：WorkProject 应用与 API

> 日期：2026-08-08<br>
> 状态：已完成<br>
> 模块：`crewscope-domain`、`crewscope-application`、`crewscope-infrastructure`、`crewscope-server`

## 目标

提供 Team 范围内的 WorkProject 创建、列表、详情和 Key 可用性纵向闭环。HTTP 层只接收认证后的 USER，应用层解析 Membership 与 TeamRole，持久化层负责租户隔离、Cursor 和数据库唯一性。

## API

```text
POST /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects
GET  /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects?after={cursor}&limit={limit}
GET  /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}
GET  /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/keys/{projectKey}
```

创建请求包含 `key` 和 `name`，要求 `Idempotency-Key`，返回统一的 `202 Accepted` CommandReceipt。列表返回：

```json
{
  "items": [],
  "nextCursor": null
}
```

详情响应包含完整 Organization、Team、Workspace Scope、Key、状态、版本以及创建和修改审计信息，并使用 Version 生成 `ETag`。Key 查询返回规范 Key 与 `available`，不代替创建时的唯一性裁决。

## 权限与 Scope

- 创建者必须是同 Organization 的 ACTIVE USER 和 ACTIVE TeamMember；
- 创建要求当前有效的 Team Scope Grant 提供 `WORK_PROJECT_MANAGE`；
- WorkProject Scope Grant 不能创建 Team 下的新项目；
- 项目固定创建在 Team 默认 ACTIVE TEAM Workspace；
- 列表、详情和 Key 查询要求 ACTIVE Membership；
- 详情中的 Project 必须属于 URL 指定 Team，Scope 不匹配按 Not Found 处理；
- 待初始化 Team 不进入 WorkProject 用例。

## 幂等、并发与事件

创建请求 Hash 包含 Actor、Team、Causation、Key 和规范化名称。相同幂等键和相同请求返回原 Receipt，相同幂等键改变内容或 Causation 返回 `idempotency_conflict`。

创建事务使用 Team 行作为同一 Team 项目 Key 的串行化点。锁内再次查询 `(Organization, Team, Key)`，冲突返回 `work_project_key_conflict`。PostgreSQL 的 `(team_id, project_key)` 唯一约束继续作为最终完整性边界。成功事务原子提交：

```text
WorkProject
-> WORK_PROJECT_CREATED DomainEvent
-> OutboxEvent
-> CommandReceipt
```

## Cursor

Repository 使用 `updated_at DESC, id DESC` 排序和 Keyset 分页。HTTP Cursor 是带版本的 URL-safe Base64 二进制 Token，包含微秒时间与 WorkProject UUID。Token 不暴露查询实现，非法版本、长度、编码和内容统一返回 `invalid_cursor`。每页默认 50 条，范围为 1–100。

## 验证

专项测试覆盖：

- Application：创建、事件、Outbox、幂等重放、内容冲突、唯一 Key、Team Scope 权限、Project Scope 越权、Membership、详情隔离和 Cursor 传递；
- Server：4 条 HTTP 路由、Receipt、分页响应、Opaque Cursor、ETag、审计字段、Key 查询及请求校验；
- PostgreSQL：Key 查询、两页 Keyset 续传，以及两路并发同 Key 只有一份 Project、DomainEvent 和 Outbox 提交。

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-server -am \
  -Dtest=WorkProjectControllerTest,WorkProjectApplicationServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  -Dtest=M1JpaPersistenceIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

专项结果：Application 6 个测试、Server 5 个测试和 PostgreSQL 集成类 14 个测试通过，0 失败、0 错误、0 跳过。

全仓回归：7 个 Maven 模块全部成功，后端 360 个测试通过，0 失败、0 错误、0 跳过。

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
node scripts/check-doc-links.mjs
git diff --check
```

## 阶段边界

M1-A03 不实现 WorkProject 改名、归档、ProviderBinding、仓库绑定、WorkItem 和前端。WorkItem 创建与状态迁移由 M1-A04 实现，WorkProject/Team 切换与管理视图由 M1-F01 实现。
