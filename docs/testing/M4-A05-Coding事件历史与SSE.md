# M4-A05：Coding 事件历史与 SSE

> 状态：已完成<br>
> 日期：2026-08-20<br>
> 模块：`crewscope-domain`、`crewscope-application`、`crewscope-infrastructure`、`crewscope-server`

## 目标

将 Workspace 生命周期、实时 Diff、TestEvidence 和最终 DiffArtifact 归并到 M3 的耐久 Task Timeline，使 Conversation Mode 与 Control Mode 使用同一历史、Cursor 和持续授权边界。

## 公开入口

继续使用统一 Task Event API：

```text
GET /api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/events
Accept: application/json

GET /api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/events
Accept: text/event-stream
Last-Event-ID: <opaque Task Cursor>
```

JSON 历史和 SSE 使用同一个升序耐久流。Cursor 绑定 Organization、Team、Task、Position 和 Event ID。非法或跨 Task Cursor 返回 `400 invalid_cursor`；已经被保留策略清理的位置返回 `410 cursor_expired`。

## Coding 事件

| Event Type | Aggregate | 公开事实 |
|---|---|---|
| `EXECUTION_WORKSPACE_CHANGED` | `EXECUTION_WORKSPACE` | attempt、状态、恢复代次、完成/失败分类、Workspace Version |
| `WORKSPACE_DIFF_RESET` | `WORKSPACE_DIFF` | Epoch、Sequence、Generation、完整文件摘要、Manifest Hash |
| `WORKSPACE_DIFF_DELTA` | `WORKSPACE_DIFF` | Epoch、Sequence、Generation、Upsert/Removal、Manifest Hash |
| `TEST_EVIDENCE_PUBLISHED` | `TEST_EVIDENCE` | Evidence Sequence、对应 Diff、测试统计、验收统计、Evidence Hash |
| `FINAL_DIFF_ARTIFACT_PUBLISHED` | `DIFF_ARTIFACT` | 最终 Generation、文件/行统计、Manifest Hash、Final Hash |

Workspace、Workspace Diff、TestEvidence 和最终 Diff 使用不同 Aggregate Type，防止不同版本轴互相制造回退或虚假 Projection Gap。每个事件在同一事务中追加 DomainEvent、Task Event 索引和 Outbox。Task Stream Event ID 继续由 DomainEvent ID 稳定派生并受数据库唯一键去重。

## RESET 与状态收敛

Diff Watcher 的文件系统通知只触发 Git 权威 Reconcile。内容变化发布直接后继 DELTA；Worker/Watcher 重建和 Recovery Generation 变化创建新 Epoch 并发布完整 RESET。RESET 完整替换客户端文件投影，DELTA 只应用到相同 Epoch 的直接后继 Sequence。

耐久事件写入成功后才推进 Worker 内存中的 Sequence 和 Manifest。数据库写入失败不会提前移动实时投影；同一 Reconcile 可安全重试。Task Cursor 断线追平不丢弃事件，客户端按 Event ID 去重。`projectionGap=true` 时客户端回读 M4-A04 的 attempt 权威快照。

## 终态与持续授权

SSE 在返回 `200` 前校验当前 ACTIVE Membership，之后每次轮询重新解析身份并复验 Team 与 Task Scope。成员失去权限后不能继续读取新事件。

成功或取消的 Coding attempt 先发布最终 DiffArtifact，再由 Worker 提交 Task 终态事件。Workspace 的 `COMPLETED`、`FAILED` 或 `ARCHIVED` 终态可能在 Task 终态之后由释放阶段发布，因此 Coding SSE 同时等待 Task 终态、Workspace 终态和耐久历史排空后关闭。Workspace 分配前的准备失败没有 Workspace 生命周期，允许按 Task 失败终态排空后关闭。

## 披露边界

公开 Diff 文件只包含相对路径、旧路径、变更类型、增删行、Binary、截断标记和 Patch SHA-256。以下内容不会进入 Task Timeline：

- Patch Preview 和源代码内容；
- canonical Repository/Worktree 路径；
- Container、Runtime、Worker、Lease、Fencing 和 Claim Token；
- Command argv、原始输出、Artifact 存储位置；
- AgentState、Provider 原始错误和 reasoning。

Patch、命令日志和测试报告内容由 [M4-A06 Coding Artifact 内容 API](M4-A06-Coding-Artifact内容API.md) 独立授权提供。

## 验证

专项测试覆盖：

- Workspace/Diff/Test/Final Event 的安全白名单；
- RESET/DELTA 文件投影不披露 Patch 或宿主路径；
- DomainEvent、Task Event 与 Outbox 的统一发布；
- 耐久写失败不推进内存 Sequence/Manifest；
- RESET、DELTA、Test、Final Artifact 的 SSE 顺序和终态关流；
- Last-Event-ID 断线追平、410 Cursor、跨 Task Cursor、Event ID 去重；
- Aggregate Version 单调、持续授权复验和单连接有界轮换；
- Workspace Pause/Resume/Recovery/Finalizing/Completed/Failed/Archived 生命周期发布；
- TestEvidence 与最终 DiffArtifact 在各自事实事务内发布。

专项执行命令：

```bash
./mvnw -pl crewscope-application,crewscope-infrastructure,crewscope-server -am \
  -Dtest='*M4A05*,TaskPublicEventMapperM3A05Test,TaskEventControllerM3A05Test,WorkspaceDiffEventStoreM4I08Test,WorkspaceDiffFinalizerM4I08Test,TestEvidencePublisherM4A03Test,DurableCodingWorkspaceExecutionLifecycleM4A03Test' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

专项验证共 26 项通过，0 失败、0 错误、0 跳过。

全仓验证命令：

```bash
./mvnw clean verify
```

全仓验证共 1407 项通过，0 失败、0 错误、0 跳过。文档链接检查通过，共检查 172 个 Markdown 文件；`git diff --check` 通过。
