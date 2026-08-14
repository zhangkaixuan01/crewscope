# M3-D01：Task 领域模型

> 日期：2026-08-13<br>
> 状态：已完成<br>
> 模块：`crewscope-domain`、`crewscope-application`

## 目标

建立耐久执行的业务根对象 `Task`，固化执行来源、可复现输入和创建时责任事实，并为 M3-D02 的执行尝试、M3-D08 的数据表和 M3-A01 的“交给 Agent 处理”用例提供稳定领域边界。

## 聚合边界

`Task` 表示围绕一个 WorkItem 发起的一次 Agent 业务执行。一个 WorkItem 可以先后或并行发起多个 Task，每个 Task 拥有独立的业务状态、执行尝试链和审计事实。

Task 保存以下事实：

```text
TaskId
WorkItemScope（Organization / Team / Workspace / WorkProject）
TaskSource
TaskResponsibilitySnapshot
TaskStatus
CurrentTaskExecutionId（可选）
TaskCancellation（可选）
Version
AuditMetadata
```

Task 创建后，Scope、来源、输入引用、责任快照、创建 Principal 和创建时间保持不变。状态、当前有效尝试、取消事实、Version 和修改审计通过领域方法推进。

## 来源与可复现输入

每个 Task 只有一个 `TaskSource`。来源始终绑定一个 WorkItem，并固化创建时的 `WorkItemId + WorkItemVersion`。

| 来源类型 | Conversation | 输入引用 |
|---|---|---|
| `WORK_ITEM` | 空 | `WorkItemId + WorkItemVersion` |
| `CONVERSATION` | 必填 | `MessageId + MessageSequence` 或 `TaskIntentId + ProposalRevision` |

Conversation 来源校验 Conversation、Message/TaskIntent 与 WorkItem 的 Organization、Team、Workspace 一致。Message 和已确认 TaskIntent 提供不可变、可定位的输入版本，Task 不复制任意大段对话文本。

来源使用单值对象表达，领域模型不会产生多个 Primary Source。补充材料在后续阶段通过 RuntimeArtifact 和 WorkGraph 关联。

## Conversation 关联

`ConversationTaskLink` 是 Conversation 与 Task 的多对多关系。关系使用 ConversationId 与 TaskId 生成稳定 ID，同一组合只有一条关系，支持命令重试幂等。

Task 的 Conversation 来源需要同时创建 `SOURCE` Link。已有 Task 可以通过 `MANUAL` Link 加入其他可见 Conversation。Link 校验 Organization、Team、Workspace 一致，并保存 WorkProjectId、WorkItemId，供两个方向的查询和数据库复合外键使用。

## 责任快照

创建 Task 时，应用层锁定 WorkItem 责任链并读取全部 Active `ResponsibilityAssignment`。`TaskResponsibilitySnapshot` 校验并固化：

- WorkItemScope 与 WorkItemId；
- Assignment ID、Assignment Version、Role；
- Principal ID、Principal Type、TeamMember ID；
- AssignedAt 与 AcceptedAt；
- 快照生成时间。

快照要求恰好一个 Active Owner、至少一个 Active Executor，并保留全部 Active Reviewer。USER Reviewer 表示 Gate Reviewer 候选责任，SPECIALIST_AGENT Reviewer 表示 Advisory Reviewer 责任；Gate 效力和职责分离仍由已经生效的 `ReviewerEligibilityPolicy` 与策略证据裁决。

源 Assignment 后续释放、替换或修改不会改变已有 Task 的快照。重试是否沿用或重新生成责任与授权事实由 M3-A04 定义。

## 生命周期

Task 业务状态使用 `task.TaskStatus`，并遵循 ADR-001：

```text
CREATED -> ACTIVE -> WAITING -> COMPLETED
CREATED / ACTIVE / WAITING -> CANCELLED
ACTIVE / WAITING -> FAILED
WAITING -> ACTIVE
```

首个 TaskExecution 绑定后，Task 从 `CREATED` 进入 `ACTIVE`。新尝试替换当前有效尝试时，调用方必须提交预期的旧 `TaskExecutionId` 和 Task Version，防止并发重试覆盖。Task 只保存当前有效尝试引用；attempt、parentExecutionId、领取状态和失败分类由 M3-D02 的 TaskExecution 承担。

业务状态同步必须引用当前有效 TaskExecution。`COMPLETED`、`CANCELLED` 关闭 Task。`FAILED` 保留当前失败尝试，通过显式绑定新的 TaskExecution 回到 `ACTIVE`，旧尝试事实保持不变。取消保存取消人、取消时间和规范化原因。命令级重复提交由 Idempotency-Key 处理，聚合拒绝关闭状态再次修改。

## 应用层边界

M3-D01 定义以下持久化 Port：

- `TaskRepository`：创建、乐观锁更新、按 ID 查询、按 WorkItem/Conversation 查询；
- `ConversationTaskLinkRepository`：创建稳定关系、按组合查询、按 Conversation/Task 查询。

正式 JPA Adapter 与双向可见性查询分别在 M3-D09、M3-A06 实现。M3-A01 在单个事务中创建 Task、首个 TaskExecution、责任快照和来源 Link。

## 阶段边界

M3-D01 不创建数据库迁移、JPA Entity、Controller、TaskExecution 聚合和 TaskDefinition。M3-D02 接入完整执行尝试状态机，M3-D08 建立 V10 表结构。

## 验证

专项单元测试覆盖：

- Organization、Team、Workspace、WorkProject Scope 闭合；
- WorkItem 与 Conversation 两类来源形状及单一来源；
- 同一 WorkItem 创建多个独立 Task；
- ConversationTaskLink 稳定身份和双向 Repository Port；
- Owner、Executor、Reviewer 快照完整性、Assignment Scope/状态/版本；
- 快照集合与创建事实不可变；
- 首个尝试绑定、预期旧尝试校验和当前尝试切换；
- 业务状态同步、失败后新尝试、取消事实和关闭状态保护；
- Task Version 与 AuditMetadata 单调推进。

验证命令：

```bash
./mvnw --batch-mode --no-transfer-progress -pl crewscope-domain,crewscope-application -am test
./mvnw --batch-mode --no-transfer-progress test
node scripts/check-doc-links.mjs
git diff --check
```
