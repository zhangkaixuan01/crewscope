# M9-A03：个人工作摘要 Query 验证

## 交付范围

- application 提供 `WorkDeskQueryService` 与 `WorkDeskRepository` 读端口；权限统一委托给既有 `WorkItemAccessPolicy`。
- infrastructure 通过 `JdbcWorkDeskRepositoryAdapter` 从 WorkItem、TaskExecution、Review、Human Gate 与 Inbox 投影派生摘要。
- server 提供 `GET /api/v1/organizations/{organizationId}/teams/{teamId}/work-desk`，成员身份由服务端会话解析，客户端不能传入 memberId。
- 前五个 section 共用 500 条响应预算，查询分支最多读取 501 行；Inbox 返回实际未读数量与聚合入口。

## 本地验证

```bash
./mvnw -q -pl crewscope-application,crewscope-infrastructure,crewscope-server -am -DskipTests compile
./mvnw -q -pl crewscope-application,crewscope-server -am \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=WorkDeskQueryServiceTest,WorkDeskControllerTest test
node scripts/check-openapi-drift.mjs
git diff --check
```

应用层测试验证权限委托、Scope/过滤参数传递和服务端成员身份；HTTP 测试验证合法响应、`no-store` 缓存策略以及非法组织、Team、Project 和责任角色参数。

## 后续集成验证

真实 PostgreSQL 数据规模、跨 Team 撤权、并发状态变更和 P95 查询基线在 M9-Q 任务中执行；本阶段不新增迁移，也不改变既有状态或权限事实。
