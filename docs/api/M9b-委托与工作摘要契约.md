# M9b：委托与工作摘要契约

> 状态：2026-09-25 A06 已实现（工作项执行摘要、服务端稳定排序/筛选/分页、WorkDesk 分段分页、Inbox 来源上下文、主体目录全量化）；同日 A05 委托编排已实现（delegation-context 一处置备、分配并启动命令、TASK 恢复坐标、resultSummary 生产侧）<br>
> 依据：[M9b 实现边界与验收契约](../plans/M9b-实现边界与验收契约.md) §4.1/§1.1、[M9b 核心流程计划](../plans/M9b-核心流程与使用体验收口.md)<br>
> 交付与验证：[A06 完成记录](../testing/M9b-A06-工作摘要与稳定查询完成.md)、[A05 完成记录](../testing/M9b-A05-委托编排完成.md)

## 1. 本包边界

A06 只拥有"读"的这一侧：摘要口径、排序、分页、筛选与权限聚合。工作项列表与详情响应内嵌执行摘要（0/1/多 Task 的当前尝试规则），WorkDesk 六段各自独立分页并给出真实全量计数，Inbox 行补出来源对象的可读上下文，主体目录去掉"前 200 个 Agent"的内存窗口。执行呈现（摘要如何渲染）归 F03，列表/视图接线归 F04，委托编排归 A05——本契约中标注"留 F03/A05"的字段已定义但恒为 null。

所有新读端点：只读、`Cache-Control: no-store`、复用既有登录身份与项目可见性边界，权限在每次请求上重新求值，续页签名有效不跳过权限检查。

## 2. 工作项执行摘要（附录 §4.1 DTO）

列表与详情响应的每个工作项都内嵌 `summary` 块，无 include 开关——看板、列表与 Today 共用同一份事实，每页固定 4 条批量 SQL（当页 id 集合 IN，无逐行查询）。

```json
{
  "workItemId": "…", "workItemVersion": 3, "workStatus": "IN_PROGRESS",
  "taskCount": 2, "activeTaskCount": 1, "pendingReviewCount": 0,
  "currentTaskId": null, "currentExecutionId": null, "executionStatus": null,
  "selectionRequired": true,
  "blockedReasons": [
    { "code": "MEMBER_CONFIRMATION", "taskId": "…", "executionId": "…",
      "since": "2026-09-20T01:00:00Z", "waitingOnPrincipalId": "…" }
  ],
  "resultSummary": null, "resultSourceReference": null,
  "projectionVersion": 7, "observedAt": "2026-09-25T01:00:00Z"
}
```

- `taskCount` / `activeTaskCount`：按 Task 状态统计，`activeTaskCount` 计 `CREATED|ACTIVE|WAITING`——CREATED 任务尚无执行也计活跃，执行 join 不可替代。
- `currentTaskId` / `currentExecutionId` / `executionStatus`：三者同现同缺；`taskCount == 0` 时全缺。
- `selectionRequired`：恒等于 `taskCount > 1`。
- `blockedReasons[].code`：TaskExecutionWaitReason 的 8 个值之一，或派生的 `REVIEW_PENDING`；`waitingOnPrincipalId` 只在恰好一名成员可行动时给出。
- `resultSummary` / `resultSourceReference`：字段已定义，A06 恒 null，F03 呈现与 A05 编排接线时填充。
- `observedAt`：装配事务的观测时间；`workItemVersion` 是投影对齐用的版本下界。

## 3. 工作列表查询

```http
GET /api/v1/organizations/{organizationId}/teams/{teamId}/projects/{projectId}/work-items
    ?status=&type=BUG,CHORE&priority=&responsibilityRole=&sort=dueAt&after=&limit=20
```

- `sort`：`updatedAt`（默认）|`priority`|`dueAt`|`createdAt`。主向 UPDATED_AT/PRIORITY/CREATED_AT 为 DESC、DUE_AT 为 ASC NULLS LAST；末级一律以 `id` 同主方向打破平局——同值行的页位置稳定。
- `type` / `priority`：多值（逗号分隔或重复参数）；`status` 保留单值旧形。全部走白名单解析，未知值 400 `invalid_request`。
- `responsibilityRole`：按 ACTIVE ResponsibilityAssignment 过滤后再生效分页（先过滤后分页）。
- `limit`：默认 20，上限 100。
- 响应：`items[]`（原有字段 + `summary`）与 `nextCursor`；有下一页时 `nextCursor` 非空。

**续页 cursor v2**（排序与 cursor 同批上线，中途换排序续页必产出错行）：

- 签名域 `crewscope:work-item-cursor:v2`，HMAC 绑定 org/team/project/actor、`sort` 与过滤指纹（canonical SHA-256）。
- 过期（默认 30 分钟，`crewscope.work-item-query.cursor-maximum-age` 可配）→ **410 `cursor_expired`**，客户端从首页重查；scope/sort/filter 不符或 v1 旧 token → 400 `invalid_cursor`。
- cursor 瞬态无持久化：部署换代瞬间在途 token 失效可接受。

## 4. WorkDesk 双端点

```http
GET /api/v1/organizations/{organizationId}/teams/{teamId}/work-desk
    ?projectId=&responsibilityRole=&onlyNeedsAction=false&limit=20
GET /api/v1/organizations/{organizationId}/teams/{teamId}/work-desk/sections/{sectionKey}
    ?after=<signed>&projectId=&responsibilityRole=&onlyNeedsAction=false&limit=20
```

- 首屏一次返回六段，每段独立 `limit`（默认 20，上限 100），响应为 `sections[]`：
  `key / title / priority / total / truncated / items[] / nextCursor`。
- **`total` 是该段的真实全量计数**（`COUNT(*) OVER()`），不再是"窗口内行数封顶"的假计数；`truncated` 表示还有续页。
- 单段续页端点只跑一段 SQL，过滤参数必须与首屏一致，`after` 的签名域 `crewscope:work-desk-section-cursor:v1` 绑定 org/team/member/段 key/过滤指纹，30 分钟过期（410 `cursor_expired`）；换段或换 filter → 400 `invalid_cursor`。
- 行增强：TASK_EXECUTION 行带 `workItemId` / `workItemTitle`；WORK_ITEM 行带 `rowSummary`（`taskCount`、`activeTaskCount`、`pendingReviewCount`、`selectionRequired`、`currentExecutionStatus`、`waitingReason`、`observedAt`、`workItemVersion`）；等待人的 `waitingOn{principalId, displayName, role}`。
- INBOX 段由单行计数升级为真实未读行（Inbox 排序），计数为未读全量。

## 5. Inbox 来源上下文

`GET .../inbox` 行新增 `sourceContext`（读时 join，不冗余进投影——源对象改名后下一次读取即时反映）：

```json
"sourceContext": {
  "projectId": "…", "workItemId": "…", "workItemTitle": "…",
  "taskObjective": "…", "waitingOnDisplayName": "…", "targetActionKind": "OPEN_URL"
}
```

- REVIEW_REQUEST → 工作项 + 任务目标 + 等待审核人；TASK_EXECUTION / RESPONSIBILITY_ASSIGNMENT → 工作项与任务；ACTION_* → ActionBundle + 白名单 `targetActionKind`；NOTIFICATION → 全 null。
- 排序、cursor、过滤均不变（S01 冻结项）；未知 `targetActionKind` 前端 fail closed。

## 6. 主体目录全量化

对 [M9-A07 主体目录契约](M9-主体目录与成员角色API契约.md) 的扩展，原 `q`/`offset` 行为不变：

```http
GET /api/v1/organizations/{organizationId}/teams/{teamId}/principals
    ?q=|namePrefix=&types=USER,AGENT&purpose=ASSIGNMENT|AUDIT&ids=&after=&offset=0&limit=20
```

- 授权候选集先在 SQL 内收敛（ACTIVE 成员 UNION ALL 可见 Agent），过滤与分页只作用于集合内——**无论可见集多大，每一行都可达**（原实现在前 200 个 Agent 上做内存过滤）。
- 排序：`lower(display_name) COLLATE "C"`，`principalId` 打破平局——大小写折叠后同名的行有全序。
- `purpose=AUDIT`：候选放宽为全部成员历史身份（LEFT/SUSPENDED），并要求 Team 审计读权限（403 `policy_denied`）；AUDIT 历史身份不带活跃角色。
- `ids`：≤50 个 UUID 的定点回显，与 `namePrefix`/`types`/`after`/非零 `offset` 互斥（400）。
- `q` 与 `namePrefix` 同义别名：同传且相等取其一，不等 400。
- `offset` 与 `after` 同传 400；截断页恒带 `nextCursor`（cursor 链可从普通首页启动），`nextOffset` 仅 offset 模式携带；`limit` 默认 20（原 50），上限 200。
- cursor 签名域 `crewscope:principal-directory-cursor:v1`，绑定 org/team/viewer/purpose/过滤指纹，30 分钟过期 → 410 `cursor_expired`。

## 7. 错误码矩阵

| 场景 | 状态码 | `code` |
|---|---|---|
| 未知枚举/超界参数/互斥参数同传 | 400 | `invalid_request` |
| cursor scope/filter/sort 不符或旧版本 token | 400 | `invalid_cursor` |
| cursor 超过最大龄 | 410 | `cursor_expired` |
| AUDIT 目的缺少审计读权限 | 403 | `policy_denied` |
| 项目/Team 不可见 | 404/403 | 既有边界不变 |

## 8. 索引责任

`V42__work_item_query_indexes.sql`：`ix_work_item_project_updated (organization_id, team_id, project_id, updated_at DESC, id DESC)`——默认排序路径的覆盖入口。priority/dueAt 专用索引仅在 P4 规模验证（`WorkQueryScaleIntegrationTest` EXPLAIN）证明默认路径劣化时以独立迁移追加。

## 9. A05 委托编排

一次「交给 Agent 处理」= 一次只读置备 + 一条可恢复命令。责任领域原语与责任命令端点沿用既有契约（M3/M4），A05 只新增「一处置备」读端点与「分配并启动」载荷；评论路径零改动（§9.5）。

### 9.1 委托上下文（一处置备）

```http
GET /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/delegation-context
```

只读、`Cache-Control: no-store`，授权与 `GET .../responsibilities` 同口径（requireVisibleWorkItem）。一次返回表单所需的全部服务端事实（`DelegationContextService`，readOnly 无事务副作用）：

```json
{
  "workItem": { "id": "…", "projectId": "…", "version": 3, "title": "…", "status": "IN_PROGRESS" },
  "responsibilities": [ { "assignmentId": "…", "role": "OWNER", "actorPrincipalId": "…",
    "actorType": "USER", "actorDisplayName": "…", "actorAgentProfileId": null, "version": 0 } ],
  "candidates": [ { "agentProfileId": "…", "agentProfileVersion": 2, "agentPrincipalId": "…",
    "displayName": "…", "ownershipType": "USER", "runtimeRole": "CODING",
    "state": "AVAILABLE", "reason": null } ],
  "defaults": {
    "version": 1,
    "repositoryBindingId": { "value": "…", "source": "PROJECT_DEFAULT", "availability": "AVAILABLE", "reason": "…" },
    "repositoryBindingVersion": { … }, "branch": { … },
    "buildProfile": { "value": { "key": "maven-java-17", "version": 1, "profileHash": "…" }, … },
    "agentProfileId": { "value": null, "source": "PROJECT_DEFAULT", "availability": "INHERITED", "reason": "…" },
    "agentProfileRevision": { … }
  },
  "activeExecution": true,
  "permissions": { "canAssignResponsibility": true, "canDelegate": true }
}
```

- `candidates[].state` 五态：`AVAILABLE`（可分配）｜`ASSIGNED`（已是当前 ACTIVE EXECUTOR，沿用）｜`EXECUTOR_CONFLICT`（已有不同 ACTIVE EXECUTOR，reason 给出释放指引）｜`AGENT_DISABLED`（Agent 非 ACTIVE）｜`PRINCIPAL_INACTIVE`（Principal 不可用）。候选集 = ASSIGNMENT 目录语义 ∩ Agent Profile 事实。
- `defaults` 直接嵌 A04 `DefaultField{value,source,availability,reason}`（每类配置一个服务端 resolver，A03/A04 定义、A05 消费）；前端按 §3.1 优先级解析（显式选择 → 已分配 EXECUTOR → 项目默认 → 引导配置），不重复选择已解析默认值。
- `activeExecution`：当前 Task 的执行处于 `READY|RUNNING|WAITING|PAUSE_REQUESTED` 时为 true，驱动 R42「当前执行仍按原说明继续」提示（§9.5）。
- `permissions`：`canAssignResponsibility` = `responsibility:manage`；`canDelegate` = 委托权威（WorkItem Owner / EXECUTOR 本人）。模型/构建预检不在 context 内做——选定 Agent 后仍走既有 `POST .../tasks/preflight`。

### 9.2 分配并启动（扩展 DELEGATE_WORK_ITEM_TO_AGENT）

`POST .../work-items/{workItemId}/tasks` 载荷新增可选字段：

```json
{ "executorAssignment": { "agentProfileId": "…" } }
```

不新建命令类型——仍是单事务、单 202 回执的一条可恢复命令（`AgentTaskCreationService.assignExecutorIfRequested`）：

- **同 actor 沿用**：责任链已有同 Principal 的 ACTIVE EXECUTOR → 跳过分配直接沿用，此时命令只需要委托权威，不发生责任写。
- **不同 ACTIVE EXECUTOR → 422 `validation_failed`**（`agentTask.executorAssignment`：`a different Executor is already active — release it explicitly first`），无 Task 落库；本命令**从不静默替换**任何人或 Agent 的责任。
- **无 EXECUTOR**：链锁内重验 `responsibility:manage`（缺失 → 403 `policy_denied`，无 Task），随后以域原语 `ResponsibilityAssignmentService.assignExecutor` 同事务创建责任，`WORK_ITEM_EXECUTOR_ASSIGNED` 事件随命令自身的 correlation/幂等键同事务发出；分配失败与 Task 创建一起回滚，不留半提交。
- Agent Profile 不存在 → 404；Principal 非 ACTIVE 或非 Task 编排型 → 422。
- **requestHash 纳入 assignment 字段**（`agentProfileId` 或 `NONE`）：同幂等键不同载荷 = 422 幂等冲突；双击/重试同键同载荷 = 重放原回执，不二次分配、不二次建 Task。

### 9.3 TASK 恢复坐标

`CommandResult.ResourceType` 增加 `TASK`（projectScoped：teamId+projectId 必填，resourceId=Task.id）。命令完成时落 `receiptStore.saveResult`；`V47__command_result_task.sql` 重建 `ck_command_result_coordinate` 增加该分支。崩溃/超时后，执行者按幂等键查既有 `GET /command-results` 即得 Task 坐标——恢复不重复委托。

### 9.4 resultSummary 生产规则（§2 字段的填充）

`summary.resultSummary` / `resultSourceReference` 由 summary adapter 第五条批量 SQL 填充，规则与 A06「当前尝试」口径一致：

- 单当前 Task 且其当前执行终态 `COMPLETED` 才可能有值；其余情形（多 Task、未完成、失败/取消终态）恒 null。
- coding 尝试（存在 diff artifact）：`resultSummary` = 「已交付 {fileCount} 个文件变更（+{additions}/−{deletions}），测试{通过/未通过}」（join 形状镜像 coding 尝试查询）；`resultSourceReference` = `coding-attempt:{executionId}@{finalHash}`。
- 非 coding：仅 `resultSourceReference` = `runtime-artifact:{terminalResultArtifactId}`；`resultSummary` 恒 null（模型结果的呈现归 F03）。

### 9.5 仅分配、评论与 TaskIntent

- **「仅分配」复用既有责任命令端点** `POST .../work-items/{workItemId}/responsibilities/executors`（自带 `responsibility:manage`、幂等与 If-Match），不创建 Task、不产生执行——责任与启动本就是两个意图。
- **评论（R42）**：`POST .../comments` 仅落讨论记录；普通文本中的 `@ 名字` 是纯文本，不构成提及、不触发执行；前端按钮与占位文案明示「发布评论（记录讨论）」。已运行执行的补充要求不做热注入：表单提示「当前执行仍按原说明继续；补充要求请发布评论，或等本轮完成后开启新一轮」。评论发布与启动分开记录、分别幂等，重试互不重复；人工审查不因评论被绕过。
- **TaskIntent 原入口零改动**：确认链路仍不建 Task；「配置 Coding Task」深链（`?delegate=coding`）打开升级后的统一表单——责任已由确认分配，表单自动进入「启动」模式。

### 9.6 错误矩阵（A05 增量）

| 场景 | 状态码 | `code` |
|---|---|---|
| executorAssignment 指向停用/非编排 Principal | 422 | `validation_failed` |
| 不同 ACTIVE EXECUTOR 在位（需先显式释放） | 422 | `validation_failed` |
| 无 `responsibility:manage` 但携带 assignment | 403 | `policy_denied` |
| 同幂等键不同载荷（requestHash 不符） | 422 | 幂等冲突（既有语义） |
| Agent Profile 不存在 | 404 | 既有边界 |
