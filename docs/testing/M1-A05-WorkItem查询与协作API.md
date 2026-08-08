# M1-A05 WorkItem 查询与协作 API

## 1. 交付范围

M1-A05 完成 WorkItem 的读取与不可变协作闭环：

- 按 WorkProject、状态和 Keyset Cursor 查询 WorkItem；
- 在一个事务快照中读取 WorkItem、评论与 ResourceLink；
- 分别读取评论和 ResourceLink 集合；
- 追加 CrewScope 原生评论；
- 关联 Task、代码对象、Artifact 和安全外部 URL；
- 使用 ACTIVE Membership、Team/WorkProject Scope Grant 和 `WORK_PARTICIPATE` 授权；
- 使用 `Idempotency-Key` 原子提交协作事实、DomainEvent、Outbox 和 CommandReceipt。

## 2. API

```text
GET  /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items
GET  /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}
GET  /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/comments
POST /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/comments
GET  /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/resource-links
POST /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/resource-links
```

列表支持 `status`、`after` 和 `limit`。详情返回强 ETag 和 WorkItem、评论、ResourceLink 完整结构。两个 POST 接口返回统一的 `202 Accepted` CommandReceipt。

## 3. 权限与一致性

- 查询要求同 Organization 的 ACTIVE USER Principal 和目标 Team ACTIVE Membership；
- 写入要求 Team Scope 或目标 WorkProject Scope 的有效 `WORK_PARTICIPATE` Grant；
- URL 与对象的 Organization、Team、Workspace 和 WorkProject Scope 全量核对；
- 其他 WorkProject 的 Grant 不提供目标项目权限；
- Native 和外部投影 WorkItem 都接受 CrewScope 评论与 ResourceLink；
- `ARCHIVED` WorkItem 不接受新增评论和 ResourceLink；
- 详情读取在事务中形成一致快照；
- 首次命令只产生一条业务记录、一条 DomainEvent、一条 Outbox 和一条 CommandReceipt；
- 同键同请求只返回原 Receipt，同键不同请求返回 `idempotency_conflict`。

## 4. 输入安全

Comment 以领域规则去除首尾空白并限制长度。ResourceLink 引用拒绝空值、超长文本和控制字符。`EXTERNAL_URL` 仅允许绝对 HTTP/HTTPS URL，必须具有 Host，并禁止 `user:password@host` 形式的嵌入凭证。

## 5. 自动化验证

| 层级 | 验证内容 |
|---|---|
| Domain | 安全 HTTP/HTTPS URL、危险 Scheme、相对 URL、嵌入凭证、控制字符和归档边界 |
| Application | 项目/状态/Cursor 传递、完整详情快照、Membership、Scope 权限、评论与链接创建、幂等重放和冲突、事件与 Outbox |
| Server | 6 条路由、分页 Cursor、ETag、完整响应、统一 Receipt、请求校验和非法枚举/标识符 |
| PostgreSQL | 评论和 ResourceLink 原子持久化、幂等重试单份数据、单份事件/Outbox/Receipt、真实详情快照读取 |

验证命令：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
node scripts/check-doc-links.mjs
git diff --check
```
