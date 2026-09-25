# M9b：委托与工作摘要契约

> 状态：2026-09-25 A06 已实现（工作项执行摘要、服务端稳定排序/筛选/分页、WorkDesk 分段分页、Inbox 来源上下文、主体目录全量化）；A05 委托编排尚未交付<br>
> 依据：[M9b 实现边界与验收契约](../plans/M9b-实现边界与验收契约.md) §4.1/§1.1、[M9b 核心流程计划](../plans/M9b-核心流程与使用体验收口.md)<br>
> 交付与验证：[A06 完成记录](../testing/M9b-A06-工作摘要与稳定查询完成.md)

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

## 9. A05 委托编排（占位）

A05 交付责任（Responsibility）领域模型与启动编排：委托表单、责任人候选联动、启动时的工作项/Task/执行三层事务。届时将补充：

- 责任分配写路径与候选集端点；
- `resultSummary` / `resultSourceReference` 的生产侧填充；
- 委托创建命令的幂等与恢复语义。

在此之前，本契约中的目录 `purpose=ASSIGNMENT` 仅服务既有表单的责任人候选展示。
