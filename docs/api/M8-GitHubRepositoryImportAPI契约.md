# M8-A02 GitHub 仓库导入 API 契约

GitHub 仓库导入只接受已验证 Connection Catalog 的稳定标识。浏览器不会提交 Remote URL、Token、Endpoint、宿主机路径或 owner/repo 拼接值。

GitHub Connection 的团队 ProviderBinding 公开 `grantId` 与 `grantVersion` 稳定坐标，供导入命令绑定当前 Team 的授权范围；该坐标不包含凭证或远程地址。

## 创建导入任务

```http
POST /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/github-imports
Idempotency-Key: <opaque-key>
Content-Type: application/json
```

```json
{
  "connectionId": "<uuid>",
  "connectionVersion": 0,
  "grantId": "<uuid>",
  "grantVersion": 0,
  "externalRepositoryId": "123456",
  "repositoryKey": "web-app",
  "defaultBranch": "main"
}
```

服务端重新检查 Team Connection、Connection Version、Grant、Allowlist、Catalog 新鲜度、WorkProject 管理权限和 Repository Key 唯一性。Repository Key 是单个部署的物理受管仓库身份，同一 Key 只执行一次 GitHub 导入；其他 WorkProject 需要复用时，从受管 Repository Catalog 绑定已存在的 Key，不重复导入。成功创建后由 Worker 在受管 Root 下初始化 bare mirror，抓取默认分支并创建现有 `LOCAL_MANAGED` RepositoryBinding。

## 查询、取消和重试

```http
GET  /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/github-imports/{jobId}
POST /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/github-imports/{jobId}/cancel
POST /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/github-imports/{jobId}/retry
```

任务状态：`REQUESTED`、`PREFLIGHTING`、`IMPORTING`、`READY`、`FAILED`、`CANCELLED`。`READY` 必须携带 Binding ID 且不携带失败码；`FAILED/CANCELLED` 必须携带稳定失败码且不携带 Binding ID；运行中状态不携带终态字段。响应只返回稳定状态、进度、失败原因码和可选 Binding ID；Git 原始输出、Secret、Remote URL 和本地路径始终留在 Worker 边界内。

取消只在 `REQUESTED/PREFLIGHTING` 阶段接受，并通过数据库条件更新与 Worker Claim 原子竞争。进入 `IMPORTING` 后已开始 Git I/O，取消返回稳定 `409 github_conflict`，避免将已产生的受管仓库或 Binding 伪装为已取消。`FAILED` 任务使用 Retry 重新复验当前 Connection、Grant 和 Catalog。

`READY` 表示仓库已通过 canonical containment、Worker Owner、bare 格式和默认分支基线校验，并已出现在 WorkProject 受管 Repository Catalog 中，可以继续创建 CodingTarget。
