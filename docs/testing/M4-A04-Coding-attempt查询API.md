# M4-A04 Coding attempt 查询 API

## 1. 交付范围

M4-A04 为 Task 的当前与历史 TaskExecution 提供同一套 Coding 只读事实：

- ExecutionWorkspace 安全摘要；
- WorkspacePolicy 派生的 Sandbox 与操作预算摘要；
- 最终 Diff Manifest、文件清单和 Patch Artifact 元数据；
- Cursor 分页的 CommandEvidence 与 TestEvidence；
- 最终 Diff 与成功 TestEvidence 精确闭合后合成的耐久 Coding Result；
- 非 Coding Task 的 `coding=false` 与空详情。

领域聚合写 Repository 保持不变。`CodingAttemptQueryPort` 是 application 所有的公开读模型端口，PostgreSQL Adapter 直接投影白名单字段。

## 2. HTTP 契约

| 方法 | 路径 | 结果 |
|---|---|---|
| `GET` | `/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/coding` | 当前 attempt；尚未创建 attempt 时 `currentAttempt=null` |
| `GET` | `/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/coding-attempts` | 全部 attempt，按 attempt 升序返回 |
| `GET` | `/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/coding` | 指定当前或历史 attempt |
| `GET` | `.../attempts/{executionId}/coding/commands?after=&limit=` | CommandEvidence Keyset 页 |
| `GET` | `.../attempts/{executionId}/coding/test-evidence?after=&limit=` | TestEvidence Keyset 页 |

所有响应使用 `Cache-Control: no-store`。成员身份先通过 `WorkItemAccessPolicy.requireVisibleTeam`，Task、TaskExecution 和 Coding 投影继续复验完整 Scope。错误 TaskExecution 隐藏为资源不存在。

## 3. Cursor 与查询预算

Command 与 TestEvidence Cursor 编码以下固定字段：

```text
version + organizationId + teamId + taskId + taskExecutionId
        + evidenceId + collection + evidenceSequence
```

Cursor 不能跨 Organization、Team、Task、attempt 或集合复用。`limit` 范围为 1 至 100，查询读取 `limit + 1` 行判断下一页，不执行 Count SQL。

查询次数为固定上限：

| 查询 | SQL 次数 |
|---|---:|
| Task 全部 Coding attempt | 2：Workspace/Policy/Diff/计数根投影 + 全部 DiffFile 批量投影 |
| 指定 Coding attempt | 2：根投影 + DiffFile 批量投影 |
| CommandEvidence 页 | 1 |
| TestEvidence 页 | 3：根投影 + Command 引用 + Acceptance/引用 |

DiffFile、CommandEvidence、TestEvidence 和 Acceptance 数量增长不会触发逐对象 SQL。

## 4. 公开 DTO 白名单

公开 Workspace/Sandbox DTO 包含状态、Repository Key、Git Commit/Managed Branch、恢复代次、保留期、Fingerprint、网络模式、只读根层、资源预算、操作预算和 BuildProfile 坐标。

公开 DTO 不包含：

- canonical Repository/Worktree 路径；
- Workspace Key 与 Archive Ref；
- Docker Container ID、Container Name 和宿主挂载；
- Runtime ID、Worker ID、Lease、Claim Token 与 Fencing Token；
- Artifact 内部存储位置；
- AgentState、模型上下文与 reasoning；
- Command 原始 argv、物理工作目录和 Sandbox 内部标识。

Coding Result 使用 `schemaVersion=1`，返回 Workspace、CodingTarget、DiffArtifact 和 TestEvidence 的权威 ID/Hash。模型生成的过程状态不进入查询 DTO。

## 5. 验证

专项验证包含：

- `TaskCodingQueryServiceM4A04Test`：4 项，覆盖权限前置、当前空状态、历史批量拼装和跨 Task attempt；
- `CodingEvidenceCursorCodecM4A04Test`：2 项，覆盖 canonical 往返和五类跨流复用；
- `TaskCodingQueryControllerM4A04Test`：3 项，覆盖非 Coding 语义、响应缓存策略、DTO 敏感字段探针和 Cursor 集合隔离；
- `M4D09CodingPersistenceIntegrationTest`：真实 PostgreSQL 覆盖 Workspace/Sandbox 空证据流，以及 Diff/Command/Test/Acceptance/Coding Result 完整对象图投影。

验证命令：

```bash
./mvnw -pl crewscope-application,crewscope-server -am \
  -Dtest=TaskCodingQueryServiceM4A04Test,CodingEvidenceCursorCodecM4A04Test,TaskCodingQueryControllerM4A04Test \
  -Dsurefire.failIfNoSpecifiedTests=false test

./mvnw -pl crewscope-infrastructure -am \
  -Dtest=M4D09CodingPersistenceIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

./mvnw clean verify
```
