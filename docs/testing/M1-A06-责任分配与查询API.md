# M1-A06 责任分配与查询 API

## 1. 交付范围

M1-A06 将 D05/D06 的责任领域规则接入认证、授权、幂等、事件和 Web API：

- 查询 WorkItem 当前 ACTIVE Owner、Executor 和 Reviewer；
- 查询创建时自动建立的初始 Owner，并以原子替换保持唯一 ACTIVE Owner；
- 分配 USER 或 Team Agent Executor；
- 分配经过职责分离校验的 USER Gate Reviewer；
- 分配 SPECIALIST_AGENT Advisory Reviewer；
- 以 Assignment Version 释放非 Owner 责任；
- 原子提交 ResponsibilityAssignment、DomainEvent、Outbox 和 CommandReceipt。

## 2. API

```text
GET  /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/responsibilities
POST /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/responsibilities/owner
POST /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/responsibilities/executors
POST /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/responsibilities/gate-reviewers
POST /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/responsibilities/advisory-reviewers
POST /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/responsibilities/{assignmentId}/releases
```

Native WorkItem 创建时已经具有 Owner。Owner 替换同时包含 `actorPrincipalId`、`expectedAssignmentId` 和 `expectedVersion`；不带期望 Assignment 的首次设置仅兼容尚未补全责任的导入或遗留数据。释放接口要求 `If-Match` 强 ETag。所有 POST 返回统一 `202 Accepted` CommandReceipt。

## 3. 权限与可信事实

- 查询要求当前 USER 在目标 Team 具有 ACTIVE Membership；
- 写入要求 Team Scope 或目标 WorkProject Scope 的 `RESPONSIBILITY_MANAGE`；
- 其他 WorkProject Scope Grant 不提供目标 WorkItem 权限；
- URL 和 WorkItem/Assignment 的 Organization、Team、Workspace、WorkProject Scope 必须一致；
- 请求中的 Principal ID 只用于定位，Principal 类型、状态、Team Scope 和 Membership 由服务端 Repository 解析；
- Gate Reviewer Policy 由服务端 Provider 提供，客户端不能提交降级策略；
- ACTIVE Gate Reviewer 不能随后成为 Owner 或 Executor，Owner/Executor 也不能绕过策略成为 Gate Reviewer。

## 4. 并发与事件

Owner 替换锁定 WorkItem 责任链，同时比较当前 Assignment ID 和 Version。该比较防止旧客户端在 Owner 已经历替换后使用相同版本覆盖新责任。两个请求从同一个 Owner 期望并发替换时，只有一个事务成功，另一个返回 `responsibility_version_conflict`。

Executor/Reviewer 释放使用 Assignment 乐观锁。每个首次成功命令提交一条业务事件、一条 Outbox 和一条 CommandReceipt；幂等重放不新增 Assignment 或事件。事件包括：

```text
WORK_ITEM_OWNER_ASSIGNED
WORK_ITEM_OWNER_REPLACED
WORK_ITEM_EXECUTOR_ASSIGNED
WORK_ITEM_GATE_REVIEWER_ASSIGNED
WORK_ITEM_ADVISORY_REVIEWER_ASSIGNED
WORK_ITEM_RESPONSIBILITY_RELEASED
```

Gate Reviewer 事件携带服务端 ReviewerEligibilityDecision。PolicyPack 降级启用时，事件包含 PolicyPack ID、版本、冲突角色和原因，供 M1-A07 时间线与 Audit 使用。

## 5. 自动化验证

| 层级 | 验证内容 |
|---|---|
| Domain/Application | 唯一 Owner、ABA 期望、Executor 重复分配、释放版本、职责双向分离、Advisory Reviewer、项目权限、幂等事件和显示名解析 |
| Server | 6 条路由、Owner 期望组合、统一 Receipt、强 `If-Match`、非法标识符和 `no-store` 查询 |
| PostgreSQL | Assignment/Event/Outbox/Receipt 原子提交、幂等单份数据、真实责任链查询、同一 Owner 期望的并发替换串行化 |

验证命令：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
node scripts/check-doc-links.mjs
git diff --check
```
