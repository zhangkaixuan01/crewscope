# M1-A07 WorkItem 时间线查询 API

## 1. 交付范围

M1-A07 为 WorkItem 详情提供可授权、可分页、可续传的业务时间线：

- 读取 WorkItem、Comment、ResourceLink 和 Responsibility 的 M1 DomainEvent；
- 合并对应 AuditEvent，并以 DomainEvent ID 作为规范事件身份去重；
- 使用发生时间和规范事件 ID 建立稳定 Keyset Cursor；
- 返回 Actor、Aggregate、Correlation、Causation、Outcome 和结构化 Payload；
- 在读取事件前校验完整 WorkItem Scope 和 ACTIVE Team Membership；
- 保留 M6 切换到物化 Activity 读模型的应用层 Port。

## 2. API

```text
GET /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/timeline?after={cursor}&limit={limit}
```

默认 `limit=50`，有效范围为 1–100。响应使用 `Cache-Control: no-store`：

```text
items[]
  eventId
  domainEventId
  source
  eventType
  schemaVersion
  aggregateType / aggregateId / aggregateVersion
  actorType / actorPrincipalId / actorDisplayName
  correlationId / causationId
  occurredAt / outcome
  payload
nextCursor
```

`payload` 是 JSON Object。Cursor 是带类型和版本的 Base64URL 不透明令牌，WorkItem 列表 Cursor 不能用于时间线接口。

## 3. 顺序、去重与续传

时间线按 `occurredAt DESC, canonicalEventId DESC` 排序。`canonicalEventId` 对 DomainEvent 和其 Audit 投影均取 DomainEvent ID；无 DomainEvent 来源的 AuditEvent 使用自身 Event ID。DomainEvent 与 AuditEvent 同时存在时选择 DomainEvent 事实，只返回一项。

`after` 表示继续读取当前页最后一项之前的较早事件。数据库读取 `limit + 1` 项判断是否存在下一页，`nextCursor` 指向本页最后一项。相同微秒内发生的事件使用 PostgreSQL UUID 顺序保持确定性，分页之间不重复、不遗漏已经位于 Cursor 之后的历史事件。

## 4. 可见性与事件范围

查询先通过 `WorkItemAccessPolicy` 校验：

- 当前认证主体是同 Organization 的 ACTIVE USER；
- 当前 USER 在目标 Team 具有 ACTIVE Membership；
- URL 与持久化 WorkProject/WorkItem 的 Organization、Team、Workspace 和 Project Scope 一致；
- Scope 不一致按不可见资源返回 Not Found，Membership 不满足返回 Policy Denied；
- 授权失败时不查询 DomainEvent 或 AuditEvent。

M1 只公开以下已评审业务事件：

```text
WORK_ITEM_CREATED
WORK_ITEM_STATUS_CHANGED
WORK_ITEM_COMMENT_ADDED
WORK_ITEM_RESOURCE_LINKED
WORK_ITEM_OWNER_ASSIGNED
WORK_ITEM_OWNER_REPLACED
WORK_ITEM_EXECUTOR_ASSIGNED
WORK_ITEM_GATE_REVIEWER_ASSIGNED
WORK_ITEM_ADVISORY_REVIEWER_ASSIGNED
WORK_ITEM_RESPONSIBILITY_RELEASED
```

后续里程碑新增 Task、Review、Handoff、Artifact 和 Action 事件时扩展应用层可见事件集合。安全审计和未知事件不会因 Payload 中偶然包含 WorkItem ID 而自动暴露。

## 5. 自动化验证

| 层级 | 验证内容 |
|---|---|
| Application | 完整 Scope 传递、事件白名单、Cursor/Limit、停用 Membership、URL Scope 不匹配和授权失败前不读取事件 |
| Server | 结构化 Payload、`no-store`、默认/自定义分页、时间线专用 Cursor、非法 Cursor/Limit/标识符 |
| PostgreSQL | DomainEvent/AuditEvent 合并去重、Actor 显示名、同微秒稳定排序、两页续传无重复遗漏和事件类型隔离 |
| Composition | `WorkItemTimelineRepository` Port 与 `WorkItemTimelineService` Bean 唯一装配 |

验证命令：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
node scripts/check-doc-links.mjs
git diff --check
```

## 6. 阶段边界

M1-A07 不建立 Activity 表、不提供实时 Team Event 流，也不合并 AG-UI 瞬时事件。M6 使用相同查询语义切换到可重建 Activity 读模型，并加入 Task、Review、Action、Artifact 和团队活动投影。
