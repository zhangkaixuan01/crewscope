# M9-A02：统一搜索 API 契约

## Endpoint

`GET /api/v1/organizations/{organizationId}/teams/{teamId}/search`

请求必须通过当前登录成员的 Team Membership 校验。`organizationId`、`teamId` 与可选 `projectId` 均由服务端解析并校验，客户端不能指定查询成员身份。

## Query 参数

| 参数 | 必填 | 约束 |
| --- | --- | --- |
| `q` | 是 | 去除首尾空白后 1–100 个字符；`%`、`_`、`!` 按字面量匹配 |
| `projectId` | 否 | WorkProject UUID；仅对项目归属对象应用 |
| `types` | 否 | 可重复参数：`WORK_ITEM`、`CONVERSATION`、`TASK`、`REPOSITORY_BINDING`、`AGENT`、`TEAM_MEMBER`；省略表示全部 |
| `after` | 否 | 服务端签发的不透明 keyset cursor |
| `limit` | 否 | 1–50，默认 20 |

## Response

```json
{
  "items": [
    {
      "objectType": "WORK_ITEM",
      "objectId": "…",
      "projectId": "…",
      "title": "修复登录",
      "subtitle": "CREW-1",
      "status": "OPEN",
      "updatedAt": "2026-09-13T04:00:00Z",
      "route": "/work?team=…&project=…&workItem=…",
      "snippet": "修复登录"
    }
  ],
  "nextCursor": "…"
}
```

结果按 `updatedAt DESC, objectId DESC` 排序，响应最多返回 `limit` 条；存在更多结果时返回 `nextCursor`。搜索对象来自权威表的派生查询，使用 `pg_trgm` 索引，不引入独立搜索服务。私有 Conversation 与 Personal Agent 仅对拥有者可见，Team 对象遵循当前成员权限。

`route` 只允许服务端生成的站内坐标。响应不包含 Credential、Endpoint、宿主路径、Prompt、原始 Provider 错误或任意外部 URL。无匹配结果返回 200 和空 `items`，非法参数返回稳定 `invalid_request`/`invalid_cursor` 错误信封。
