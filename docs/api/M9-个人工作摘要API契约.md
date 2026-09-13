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

固定 section 顺序为 `HUMAN_GATE`、`REVIEW`、`BLOCKED`、`WORK_ITEM`、`TASK_EXECUTION`、`INBOX`。前五个 section 共用最多 500 条责任项的响应预算，并按优先级顺序分配；单个查询分支最多读取 501 行用于设置 `truncated`。`truncated=true` 表示仍有更多数据，应通过 `/work` 查看完整列表；`INBOX.total` 是当前未读条目数，`INBOX.items` 使用一个聚合入口。`availableActions` 在 M9-A03 返回安全的空集合，M9-A05 接入动态动作可用性后再填充。所有 `route` 都是站内稳定坐标；不会返回 Credential、Endpoint、宿主路径、Prompt 或外部 URL。

个人工作摘要是派生查询，不新增待办状态、已读字段或个人待办表。权限沿用 `WorkItemAccessPolicy` 的当前 Team Membership 边界；撤权后下一次请求立即不可见。
