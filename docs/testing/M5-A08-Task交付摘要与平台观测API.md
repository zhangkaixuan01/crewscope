# M5-A08 Task 交付摘要与平台观测 API

> 日期：2026-08-24
> 范围：`crewscope-application`、`crewscope-infrastructure`、`crewscope-server`

## 1. 交付结果

M5-A08 将当前 Task attempt 的 Agent 配置、Review、Action 与 GitHub 结果组合为成员安全摘要，并把同一摘要提供给 Conversation 卡片。Task Timeline、Runtime Fleet、Actuator 和 Team 运维视图同步纳入 M5 交付事实。

公开入口：

```text
GET /api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/delivery-summary
GET /api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/delivery-cards
GET /api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/events
GET /api/v1/organizations/{organizationId}/teams/{teamId}/runtime-health
GET /api/v1/organizations/{organizationId}/teams/{teamId}/runtime-health/operations
GET /actuator/health
```

Task Delivery Summary 公开以下事实：

- Agent Profile ID、Template Key/Version、Configuration Revision、ExecutionScope、Binding Source；
- 主模型与 Fallback 的 Provider、Model ID、Catalog Revision；
- 最新 ReviewRequest 状态、Finding/Blocker/High 数量、Gate Decision 和修改轮次；
- 最新 ActionBundle Version/Digest/Validity、Confirmation 状态、Repository Key；
- Push 与 Draft PR 的 Dispatch/Receipt/ExternalResult 状态及外部身份安全 Hash。

DTO 不包含 Model/GitHub Connection、Credential、Grant、Endpoint、Policy/Safety 内部 Hash、Worker、Lease、Fencing Token、幂等键、原始外部 ID、Business Key 或 Observation Key。

## 2. 授权与游标

Task 摘要每次读取都重新验证当前 Team 与 WorkItem 可见性。Conversation 卡片先通过现有 Conversation 服务验证共享或 PRIVATE Conversation 可见性，再对返回页中的每个 Task 重新验证当前可见性。成员离队或权限撤销后的再次读取失败关闭。

Conversation 卡片复用 Task Association 的 keyset 分页语义。游标绑定 Organization、Team、Source Type、Conversation ID、Associated At 和 Task ID；跨 Team、跨 Conversation 或跨 Source 重放返回 `invalid_cursor`。响应使用 `Cache-Control: no-store`。

交付卡片的独立页面预算为默认 `20`、最大 `50`。单页充实在一个事务内执行，复用 Association 读模型已返回的 Task，不再为每张卡片重复回读 Task 或开启独立事务。Review/Action 子投影未切换为批量读模型前不提高页上限。

## 3. Timeline 与运行观测

`TaskPublicEventMapper` 为 ReviewRequest、Finding、Decision、修改轮次、ActionBundle、Confirmation、Dispatch、Receipt 与 ExternalResult 建立显式公开白名单。未知类型继续失败关闭；Dispatch 的 Worker/Lease/Fencing 与 ExternalResult 原始外部标识不会进入成员 Timeline。

Runtime Fleet 的 `actionDelivery` 按 Organization 与 Team 查询，只公开：

```text
health
running
unknown
reconciling
manualReview
oldestUnresolvedAgeSeconds
stale
```

成员摘要和 `TEAM_OBSERVE` 运维视图共享该安全子投影。Actuator 继续使用平台级低基数队列健康；浏览器没有 Dispatch Claim 或 Worker 执行 API。

Task Delivery 读取记录 Correlation ID、Actor、Organization、Team、View 与 Item Count 的结构化安全审计和 Trace。指标 `crewscope.task.delivery.summary.requests` 只使用固定 `view/review/delivery` Tag；Runtime 指标增加固定 `action_health` Tag。

## 4. 自动化证据

专项测试：

```bash
./mvnw -pl crewscope-application,crewscope-server -am \
  -Dtest=TaskPublicEventMapperM3A05Test,RuntimeObservationServiceM5A08Test,\
TaskDeliverySummaryServiceM5A08Test,RuntimeObservationControllerM3A07Test,\
TaskDeliverySummaryControllerM5A08Test \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：Application `9 / 9`、Server `10 / 10`，合计 `19 / 19` 通过。

后端全量回归覆盖 Domain `500 / 500`、Application `418 / 418`、AgentScope Adapter
`142 / 142`、Integration `1 / 1`、Infrastructure `546 / 546`、Server `281 / 281`，
合计 `1888 / 1888` 通过。Server 回归同时验证 Runtime 指标只增加固定
`action_health` 维度，未引入 Organization、Team、Actor 或 Correlation 等高基数 Tag。

覆盖项：

- Agent/Review/Action/GitHub 安全摘要和敏感字段白名单；
- Review/Action/GitHub Task Timeline 白名单与未知类型失败关闭；
- 当前成员撤权后的再次读取失败；
- PRIVATE Conversation 可见性错误不降级为空卡片；
- Conversation 游标跨 Team 重放拒绝和原游标续页；
- Conversation 卡片默认 `20`、最大 `50`，整页充实复用已查 Task 并共享一个事务；
- Team 隔离 Action Delivery 队列健康及空 Runtime Fleet；
- 成员与管理员 Runtime HTTP 安全 DTO；
- `no-store`、Correlation、Trace、Audit 与低基数指标入口。
