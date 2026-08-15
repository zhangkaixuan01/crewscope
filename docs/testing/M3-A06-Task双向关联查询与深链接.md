# M3-A06：Task 双向关联查询与深链接

> 日期：2026-08-15<br>
> 状态：已完成<br>
> 依赖：M3-A01、M3-A02、M2-A08

## 1. 交付结论

CrewScope 已闭合 WorkItem、Conversation 与 Task 的三个查询方向：WorkItem 可以查看其全部 Task，Conversation 可以查看通过 `ConversationTaskLink` 关联的全部 Task，Task 可以返回其唯一 WorkItem 与当前调用者仍可发现的 Conversation。取消、失败、完成和旧执行 Task 继续作为历史业务事实返回，不按当前运行状态裁剪。

关联读取使用独立 `TaskAssociationRepository`。WorkItem/Conversation 到 Task 的页面一次联接 Task、当前 TaskExecution 和当前 WorkItem；Task 到 Conversation 的页面一次联接 `ConversationTaskLink`、Conversation 和当前调用者的 Participant。查询次数不随关联结果数量增长。

## 2. HTTP 契约

```text
GET /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/tasks
GET /api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/tasks
GET /api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/associations
```

三个入口都接受 `after` 和 `limit`，响应使用 `Cache-Control: no-store`。WorkItem/Conversation 页面返回关联时间、关联来源与 Task 安全摘要；Task 反向入口返回 Task、WorkItem 和分页 Conversation 摘要。

每个对象摘要提供服务端生成的 Web 深链接：

- Task：`/work?team=...&project=...&workItem=...&task=...`；
- WorkItem：`/work?team=...&project=...&workItem=...&focus=...`；
- Conversation：`/conversation?team=...&conversation=...`。

深链接只基于已经通过服务端授权并由持久化 Scope 闭合的对象生成。前端不从 Task 来源字段或客户端缓存推测关联对象。

## 3. Cursor 与历史语义

关联 Cursor 是版本化规范 Base64URL 二进制值，绑定：

- Organization；
- Team；
- 来源对象类型 `WORK_ITEM/CONVERSATION/TASK`；
- 来源对象 ID；
- 关联时间；
- 目标对象 ID。

WorkItem 方向按 `Task.createdAt DESC + Task.id DESC` 分页；Conversation/Task 方向按 `ConversationTaskLink.createdAt DESC + 目标 ID DESC` 分页。Cursor 不能跨 Team、跨来源类型或跨来源对象重放，非法值返回 `400 invalid_cursor`。

## 4. 当前可见性与 Scope

WorkItem 入口先执行当前 WorkItem 可见性策略。Conversation 入口先执行当前 Conversation 可读策略，因此不可发现的 PRIVATE Conversation 不会成为查询源。

Task 反向入口按对象分别裁决：

1. Task 必须属于路由 Organization/Team；
2. WorkItem 必须通过当前 WorkItem 策略并与 Task 的 Organization、Team、Workspace、WorkProject 完全一致；
3. TEAM Conversation 对当前 ACTIVE TeamMember 可发现；
4. PRIVATE Conversation 只在存在与当前 Principal 和当前 TeamMember ID 同时匹配的 USER Participant 时返回；
5. `ConversationTaskLink`、Task 和 Conversation 的 Organization、Team、Workspace、WorkProject、WorkItem 坐标必须闭合。

持久化结果出现跨 Scope 行时，应用层以 `taskAssociation.repositoryResult` 失败关闭。PRIVATE Conversation 的标题、ID、数量和深链接都不会通过 Task 反向结果泄露。

## 5. 自动化证据

| 测试 | 覆盖 |
|---|---|
| `TaskAssociationServiceM3A06Test` | 多 Task、取消历史、源对象授权、PRIVATE 源隐藏、跨 Team 行失败关闭、Task 反向固定批量调用 |
| `TaskAssociationCursorCodecTest` | 完整 Scope/来源往返、跨 Team/类型/对象重放拒绝、非规范 Cursor |
| `TaskAssociationControllerM3A06Test` | 三组路由、`no-store`、关联来源、终态 Task、对象深链接、Cursor 错误信封 |
| `M3TaskRuntimePersistenceIntegrationTest` | 真实 PostgreSQL 双向分页、一个 WorkItem/Conversation 多 Task、PRIVATE Conversation 隐藏、跨 Team 空结果、Hibernate 单查询统计 |

验证命令：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
node scripts/check-doc-links.mjs
git diff --check
```

验证结果：Maven Reactor 7 个模块全部通过，共执行 `1023` 项测试，`0` 失败、`0` 错误、`0` 跳过；文档链接检查覆盖 `130` 个 Markdown 文件，差异格式检查通过。

## 6. 下一项

`M3-A07`：实现 Runtime/Worker 健康、能力、容量和等待原因查询，并区分成员安全摘要与运维授权明细。
