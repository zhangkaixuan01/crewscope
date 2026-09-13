# M9 行级 Review 评论 API 契约

所有路径均位于：`/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/reviews/{reviewRequestId}/comments`。

## 锚点

请求中的 `anchor` 包含 `filePath`、`side`（`OLD`/`NEW`）、`lineNumber`、`hunkHeader`、`lineContentHash` 和 `diffGeneration`。文件路径必须是仓库相对规范路径，行号从 1 开始。服务端只接受当前 Review ContextPackage 中由 `ReviewPatchHunkParser` 解析出的行。

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/comments` | 创建评论；必须带 `Idempotency-Key`，返回 `201` 与评论资源 |
| `GET` | `/comments?after=&limit=` | 按评论 ID 游标分页；响应 `{items,nextCursor}`，评论按文件/行字段分组所需 |
| `PATCH` | `/comments/{commentId}` | 仅作者可编辑；必须带 `Idempotency-Key`、`If-Match` |
| `DELETE` | `/comments/{commentId}` | 仅作者可删除；软删除并保留审计锚点；必须带 `Idempotency-Key`、`If-Match` |

评论正文最多 20,000 个字符。正文作为 Markdown 传输，前端必须使用既有 Markdown + DOMPurify 管道渲染；服务端不回显宿主路径、Credential、Prompt 或 Provider 原始错误。

评论响应公开 `id`、Review/执行标识、锚点、正文、作者 Principal ID、`anchorState`、删除标记、版本和审计时间。`anchorState` 为 `ACTIVE` 或 `OUTDATED`。当 Diff Generation、Hunk、文件或行内容发生变化时，评论保留原文并标记 `OUTDATED`，不可自动移动或删除。

软删除响应保留 `deleted=true`、锚点、作者、版本和审计时间，`content` 对外返回空字符串；原文仅保留在受控持久化边界内。

评论是 Review 的补充证据，不参与 `ReviewDecision`、Gate Eligibility 或交付状态机。跨 Team、撤权后的请求统一按既有 Team 可见性策略拒绝。`OLD`/`NEW` 坐标分别按 unified diff 的旧/新文件行号解析，并且 hunk header 必须与当前 ContextPackage 完全一致。
