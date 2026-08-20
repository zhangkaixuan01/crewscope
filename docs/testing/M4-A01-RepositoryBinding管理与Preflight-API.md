# M4-A01 RepositoryBinding 管理与 Preflight API

> 实现日期：2026-08-19

## 1. 交付范围

M4-A01 提供 WorkProject 级受管代码仓库绑定能力。API 使用完整的 Organization、Team、WorkProject 路由范围，支持 RepositoryBinding 创建、列表、详情、Preflight、启用和停用。

公开协议只使用稳定 `RepositoryKey`、Git Ref、Commit、状态、版本和审计字段。受管仓库根目录、仓库绝对路径、Git 原始输出和操作系统用户信息停留在 Infrastructure 内部。

## 2. API

基础路径：

```text
/api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/repository-bindings
```

| 方法 | 路径 | 用途 | 并发协议 |
|---|---|---|---|
| `POST` | `/` | 创建 `LOCAL_MANAGED` Binding | `Idempotency-Key` |
| `GET` | `/` | 列表 | `Cache-Control: no-store` |
| `GET` | `/{bindingId}` | 详情 | 强 `ETag` |
| `POST` | `/preflight` | 创建前按 Key 和 Ref 检查 | 只读 |
| `POST` | `/{bindingId}/preflight` | 重新检查已有 Binding | 只读 |
| `POST` | `/{bindingId}/activate` | Preflight 后启用 | `Idempotency-Key` + 强 `If-Match` |
| `POST` | `/{bindingId}/disable` | 停止新 CodingTarget 选择 | `Idempotency-Key` + 强 `If-Match` |

创建和状态命令返回统一 `202 CommandReceiptResponse`。每次请求先复验当前管理权限，再预留或重放 Receipt；幂等重放返回原 Receipt 并携带 `Idempotency-Replayed: true`。版本冲突进入统一 `409` 错误信封并返回 `currentVersion`。

## 3. 授权与范围

- ACTIVE Team Member 可以读取当前 Team 和 WorkProject 内的 Binding；
- 内置 `TEAM_OWNER`、`TEAM_ADMIN` 和平台管理员可以创建、Preflight、启用和停用；
- Team Lead、普通成员、Auditor、失效 Membership 和 WorkProject Scope Grant 不能修改仓库绑定；
- URL 与持久化 Organization、Team、Workspace、WorkProject、Binding 任一不一致时按资源不存在处理；
- 平台管理员仍使用完整 WorkProject Scope 查询，不跳过租户与路由范围校验。

## 4. 命令与 Preflight

创建流程固定为：

```text
认证身份
  -> WorkProject Scope 与管理员授权
  -> RepositoryKey 唯一性检查
  -> Managed Repository + Default Ref Preflight
  -> RepositoryBinding
  -> DomainEvent
  -> Outbox
  -> CommandReceipt
```

上述业务事实通过外层必需事务提交。数据库唯一约束继续裁决并发创建。启用命令先对当前 RepositoryKey 和默认 Ref 重新执行 Preflight，再以 `expectedVersion` 条件更新 Binding。

停用只阻止后续 CodingTargetSnapshot 选择。已固化的 CodingTargetSnapshot、历史 Workspace、Diff 和 TestEvidence 保持可追溯。

## 5. 部署与错误

Worker/All Profile 使用 `ManagedRepositoryBindingPreflightAdapter` 连接 M4-I02 的 `BaselinePreflight`。Pure Server Profile 使用稳定的不可用实现，Preflight 和依赖 Preflight 的命令返回：

```text
503 repository_preflight_service_unavailable
```

仓库不存在、仓库无效和 Ref 无效返回稳定的 `422 repository_preflight_*` 错误。响应消息和 Details 不包含宿主路径或原始 Git 输出。
可重试的 Preflight 服务不可用和 Git 命令故障返回稳定 `503`，客户端可以在基础设施恢复后重试。

## 6. 自动验证

```bash
/Users/zhangkaixuan/Downloads/apache-maven-3.9.6/bin/mvn \
  --batch-mode --no-transfer-progress \
  -pl crewscope-server -am \
  -Dtest='*M4A01*,ManagedRepositoryConfigurationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

验证结果：21 项相关测试通过，覆盖：

- 管理员、平台管理员、普通成员权限；
- 创建、幂等重放、事件、Outbox 和 Receipt；
- 列表、详情、完整 Scope 和 ETag；
- 创建前与已有 Binding Preflight；
- 启用、停用、强 If-Match 和版本冲突；
- Infrastructure 到 Application 的路径脱敏；
- Worker/All 与 Pure Server 失败关闭 Spring 装配；
- 统一安全错误信封和无宿主路径 DTO。

M4-A02 将在 WorkItem 表单和 Conversation TaskIntent 确认流程中选择 ACTIVE RepositoryBinding，并原子固化 CodingTargetSnapshot。
