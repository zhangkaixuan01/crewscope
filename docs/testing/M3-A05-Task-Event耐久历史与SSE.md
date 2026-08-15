# M3-A05：Task Event 耐久历史与 SSE

> 日期：2026-08-15<br>
> 状态：已完成<br>
> 依赖：M3-A02、M3-I07、ADR-005、ADR-013

## 1. 交付结论

CrewScope 已建立成员可见的 Task 耐久事件流。Task 创建、Worker 命令、成员 Pause/Resume/Cancel/Retry、AgentRun Event、AgentRun Resume 和 Lease Recovery 在原业务事务中同时写入 DomainEvent、Outbox 与 Task Event 索引，页面刷新或连接中断不会丢失已经提交的事实。

`task_event` 是可由 DomainEvent 重建的关系索引。业务载荷只保存在 `domain_event`；索引保存单调 Position、Task、TaskExecution、可选 StepExecution、AgentRun、ExecutionLease、源 DomainEvent ID 与稳定 Task Stream Event ID。

## 2. HTTP 契约

```text
GET /api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/events
Accept: application/json

GET /api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/events
Accept: text/event-stream
Last-Event-ID: {taskEventCursor}
```

JSON 历史使用 `after` 与 `limit`，按 Position 升序返回 `items`、`hasMore`、`taskTerminal` 和 `nextCursor`。SSE 的 `id` 与 JSON Cursor 相同，`event` 为公开业务 Event Type，`data` 为 Task Event Response。所有响应使用 `Cache-Control: no-store`。

Task Event Response 包含：

- `context`：Task、TaskExecution、StepExecution、AgentRun 和 ExecutionLease 的安全关系坐标；
- `projectionGap`：同一 Aggregate 的 Version 在当前 Task 流中出现缺口；
- `event`：统一 `RealtimeEventResponse`，包含 Task Stream Event ID、源 DomainEvent ID、Aggregate、Correlation、Causation、时间和公开载荷。

Cursor 是版本化规范 Base64URL 二进制值，绑定 Organization、Team、Task、Position 和 Stream Event ID。跨 Task/Scope、非规范编码和 `Last-Event-ID`/`after` 不一致返回 `400 invalid_cursor`；已被保留策略清理的位置返回 `410 cursor_expired`。

## 3. 追平、背压与关闭

服务端在提交 SSE `200` 前完成首次身份、Membership、Task 可见性和 Cursor 校验。后续每次轮询重新解析当前身份，并由 `TaskEventService` 再次复验 ACTIVE Team Membership 与 Task Scope。

一页完全交给下游后才读取下一页。`crewscope.task-events.batch-size` 范围为 1–100，空轮询可以合并，已读取的业务事件不会被背压丢弃。`crewscope.task-events.maximum-events-per-connection` 范围为 1–100000；达到上限后连接在最后已发送 Cursor 处关闭，客户端通过 SSE ID 继续。Task 终态且历史完全排空后连接自动完成。

同一 DomainEvent 在 Task、Conversation 或后续 Team 流中拥有不同 Stream Event ID，并保持相同 `domainEventId`。客户端在单流内按 `eventId` 去重，跨流按 `domainEventId` 合并。Cursor 不声明跨 Aggregate 全局顺序。

## 4. 公开披露边界

`TaskPublicEventMapper` 只接受以下 M3 公开事件类型：

- `TASK_DELEGATED_TO_AGENT`；
- `WORKER_TASK_PREPARE_ACCEPTED`、`WORKER_TASK_START_ACCEPTED`、`WORKER_TASK_HEARTBEAT_ACCEPTED`、`WORKER_TASK_PROGRESS_ACCEPTED`、`WORKER_TASK_COMPLETE_ACCEPTED`、`WORKER_TASK_FAIL_ACCEPTED`；
- `MEMBER_TASK_PAUSE_ACCEPTED`、`MEMBER_TASK_RESUME_ACCEPTED`、`MEMBER_TASK_CANCEL_ACCEPTED`、`MEMBER_TASK_RETRY_ACCEPTED`；
- `TASK_EXECUTION_RECOVERY_STARTED`；
- `AGENT_RUN_RESUMED`；
- `AGENT_RUN_EVENT_RECORDED`。

每个事件类型继续使用字段级白名单。AgentRun 的 Usage 与 Failure 使用独立嵌套白名单；Interrupt Token、Claim Token、Task Token、Tool 参数与原始结果、Provider 原始错误、AgentState、Structured Output 原值和内部 Reasoning 没有公开字段。未知类型即使符合控制命令的命名形式也失败关闭，新增事件必须完成单独披露审查后加入精确白名单。

## 5. 数据迁移

V13 创建 `task_event_position_seq`、`task_event`、完整复合外键、两个唯一约束和 Task + Position 查询索引。V12→V13 回填只识别上述已知 Task 事件族，通过受约束 Aggregate、Payload 坐标与现有 Task Runtime 关系闭合 Task；任意 JSON 中出现 `taskId` 不能投影无关事件。

新事件由应用服务在业务事务内显式调用 `TaskEventRepository.append`。DomainEvent、Task Event、Outbox 或 CommandReceipt 任一步失败时完整事务回滚。

## 6. 自动化证据

| 测试 | 覆盖 |
|---|---|
| `TaskPublicEventMapperM3A05Test` | 顶层与嵌套白名单、Token/Reasoning/Tool 参数移除、未知类型失败关闭 |
| `RealtimeStreamEventIdsTest` | Task Stream Event ID 稳定性及与 Conversation/Team 的跨流区分 |
| `TaskEventCursorCodecTest` | 完整 Task 路由往返、跨 Task 与非规范 Cursor 拒绝 |
| `TaskEventControllerM3A05Test` | JSON 历史、关系上下文、投影缺口、Last-Event-ID、终态关闭、持续身份解析、单连接上限、Cursor 过期与冲突 |
| `M3TaskRuntimePersistenceIntegrationTest` | 真实 PostgreSQL append、复合关系、公开载荷、升序分页、缺口检测、DomainEvent ID 保留和过期 Cursor |
| `V10DurableTaskRuntimeMigrationIntegrationTest` | V12→V13 升级、已知事实回填、无关 Payload 拒绝和索引 |
| M3-A01/A03/A04 与 M3-I07 回归 | Task 创建、Worker 命令、成员控制和 AgentRun Event 在原事务中同步追加 Task Event |

验证命令：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
node scripts/check-doc-links.mjs
git diff --check
```

2026-08-15 最终工作区验证报告合计 1011 个测试，`Failures: 0`、`Errors: 0`、`Skipped: 0`。M3-A05 专项覆盖同时包含真实 PostgreSQL、V12→V13 升级和完整 Server 回归。

## 7. 下一项

`M3-A06`：实现 WorkItem、Conversation 与 Task 双向关联查询和对象级深链接，并按每个对象当前可见性过滤反向结果。
