# M9-A03：个人工作摘要 API 契约

## Endpoint

`GET /api/v1/organizations/{organizationId}/teams/{teamId}/work-desk`

Query 参数：

- `projectId`：可选的 WorkProject 作用域；
- `responsibilityRole`：`OWNER`、`EXECUTOR` 或 `REVIEWER`；
- `onlyNeedsAction`：默认 `false`，只返回需要当前成员处理的条目。

请求必须通过当前登录成员的 Team Membership 校验。服务端按 Team/Project 作用域派生结果，不接受成员 ID 作为客户端参数。

## Response

```json
{
  "organizationId": "…",
  "teamId": "…",
  "projectId": null,
  "generatedAt": "2026-09-13T04:00:00Z",
  "sections": [
    {
      "key": "HUMAN_GATE",
      "title": "等我决策",
      "priority": 1,
      "total": 1,
      "truncated": false,
      "items": [
        {
          "objectType": "HUMAN_GATE",
          "objectId": "…",
          "projectId": "…",
          "title": "…",
          "status": "OPEN",
          "updatedAt": "2026-09-13T04:00:00Z",
          "responsibilityRole": "REVIEWER",
          "needsAction": true,
          "urgency": "HIGH",
          "progress": null,
          "availableActions": [],
          "route": "/work?team=…&project=…&review=…"
        }
      ]
    }
  ]
}
```

固定 section 顺序为 `HUMAN_GATE`、`REVIEW`、`BLOCKED`、`WORK_ITEM`、`TASK_EXECUTION`、`INBOX`。前五个 section 共用最多 500 条责任项的响应预算，并按优先级顺序分配；单个查询分支最多读取 501 行用于设置 `truncated`。`truncated=true` 表示仍有更多数据，应通过 `/work` 查看完整列表；`INBOX.total` 是当前未读条目数，`INBOX.items` 使用一个聚合入口。所有 `route` 都是站内稳定坐标；不会返回 Credential、Endpoint、宿主路径、Prompt 或外部 URL。

`availableActions` 自 M9-A05 起填充为**当前成员可执行**的动作对象数组，元素形状与 `M9-状态流转可用性API契约.md` 的响应完全一致（服务端复用同一个响应类型，因此两个面不可能序列化漂移）。口径有两点：

- **只含 `enabled` 的动作。** 工作台列表行无法呈现按钮的禁用状态与原因，因此它拿到的是一个更短的诚实列表，而不是一个带禁用项的长列表；禁用原因仍可在 WorkItem 详情面板看到（那里拉取全量）。
- **只有 WorkItem 行会有内容。** `TASK_EXECUTION`、`REVIEW_REQUEST`、`HUMAN_GATE`、`INBOX` 行恒为空数组，`BLOCKED`/`WORK_ITEM` 行里由外部 Provider 管理的工作项也为空数组。判定依据是仓储给出的事实（项目、状态、来源），不是把 `status` 字符串当约定来猜。

权限按项目求值，但每个请求只读取一次成员角色与授权，读取次数与页面条目数、项目数无关。可用性信息不构成授权边界：执行期由 `WorkItemCommandService` 重新校验。

个人工作摘要是派生查询，不新增待办状态、已读字段或个人待办表。权限沿用 `WorkItemAccessPolicy` 的当前 Team Membership 边界；撤权后下一次请求立即不可见。
