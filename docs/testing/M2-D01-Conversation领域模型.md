# M2-D01：Conversation 领域模型

> 日期：2026-08-08<br>
> 状态：已完成<br>
> 模块：`crewscope-domain`

## 目标

建立成员与 Personal Agent 持续对话的稳定领域事实，定义 Conversation、Participant、Message 与 ConversationWorkItemLink 的身份、Scope、作者和审计边界，为 M2-D02 的可见性、参与者生命周期、消息顺序与 Cursor 提供基础。

## Conversation

`Conversation` 保存：

```text
ConversationId
OrganizationId / TeamId / WorkspaceId
Owner TeamMemberId / USER PrincipalId
Personal Agent PrincipalId
Title
PRIVATE / TEAM
ACTIVE / ARCHIVED
Version
AuditMetadata
```

创建规则：

- Workspace 必须是 Owner Member 所在 Team 的 ACTIVE Team Workspace；
- Owner 必须是该 Team 的 ACTIVE TeamMember，且对应同一 ACTIVE USER Principal；
- Personal Agent 必须是该成员在当前 Workspace 的 ACTIVE 默认 Personal Agent；
- Owner USER 与 Personal Agent 使用不同 PrincipalId；
- Title 归一化且不超过 200 个字符；
- 创建者、Owner、Team、Workspace 和 Personal Agent Scope 在初始化时闭合；
- `PersonalConversationInitialization` 一次生成 Conversation、Owner Participant 和 Personal Agent Participant，供后续应用事务原子提交。

## Participant

`ConversationParticipant` 区分：

```text
OWNER
MEMBER
AGENT
```

USER Participant 保存 TeamMemberId，Agent Participant 不保存 TeamMemberId。初始 Owner 和 Personal Agent 都处于 ACTIVE 状态。

Participant ID 由以下稳定输入生成：

```text
ConversationId + PrincipalId
```

同一加入命令重试得到相同 ID，Owner 与 Agent 因 Principal 不同而保持不同身份。持久化还原会重新验证稳定 ID、角色形态、Join Actor、JoinedAt、LEFT 状态和 LeftAt/Audit 时间一致性。M2-D02 增加正式加入、退出和可见性裁决用例。

## Message

`Message` 是不可变的已提交内容，首批类型：

```text
USER_MESSAGE
AGENT_MESSAGE
SYSTEM_NOTICE
```

作者规则：

- USER_MESSAGE 只能由匹配的 ACTIVE USER Participant 创建；
- AGENT_MESSAGE 只能由匹配的 ACTIVE Agent Participant 创建；
- Participant 必须属于同一 Conversation 和完整 Scope；
- SYSTEM_NOTICE 不伪造 Author 或 Participant，可信 Emit Actor 保存在 AuditMetadata；
- 已署名 Message 的 CreatedBy 必须与 Author Principal 一致；
- Markdown 内容归一化、非空且不超过 50,000 个字符。

消息序号、流式状态和历史 Cursor 由 M2-D02 扩展；M2-D01 保持已提交 Message 内容不可变。

## ConversationWorkItemLink

`ConversationWorkItemLink` 保存 Conversation、WorkProject、WorkItem、创建来源和创建 Principal。来源包括：

```text
TASK_INTENT_CONFIRMATION
MANUAL
WORK_ITEM_DISCUSSION
```

关联规则：

- Conversation 与 WorkItem 必须位于同一 Organization、Team 和 Workspace；
- Conversation 必须 ACTIVE，WorkItem 必须可以继续协作；
- 创建 Principal 必须 ACTIVE 且位于相同 Organization/Team Scope；
- Link ID 由 `ConversationId + WorkItemId` 稳定生成；
- 同一关联重试得到相同 ID，数据库阶段再以唯一约束完成最终并发裁决；
- `ConversationTaskLink` 随 M3 Task 聚合建立，本阶段不创建悬空 Task 引用。

## 审计与边界

- Conversation、Participant 和 Link 保存创建 Principal 与时间；
- Conversation 和 Participant 保存 Version，支持后续乐观并发；
- Participant 的 LeftAt 不得早于 JoinedAt，Audit UpdatedAt 不得早于 LeftAt；
- 领域层校验 Principal 状态与 Scope，Membership 权限和具体操作授权由 Application Service 裁决；
- 本任务不增加数据库迁移、Repository、API、AgentScope Runtime 或前端实现。

## 验证

专项命令：

```bash
./mvnw --batch-mode --no-transfer-progress -pl crewscope-domain -am clean test
```

结果：Domain 模块 148 个测试通过，0 失败、0 错误、0 跳过。其中 M2-D01 新增 22 个测试：

- Conversation：6 个；
- ConversationParticipant：5 个；
- Message：7 个；
- ConversationWorkItemLink：4 个。

覆盖 Personal Conversation 完整初始化、PRIVATE/TEAM、标题归一化、停用成员、归档 Workspace、跨 Team Personal Agent、稳定 Participant ID、角色形态、终态时间、USER/Agent/System 消息、作者资格、跨 Conversation Participant、非 ACTIVE Principal、内容约束、稳定 Link ID、跨 Workspace 和跨 Organization 防护。

全仓回归：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

结果：7 个 Maven 模块构建成功，427 个后端测试通过，0 失败、0 错误、0 跳过。AgentScope Harness、Docker Sandbox、PostgreSQL、Redis、Flyway、Spring Context 和 Server API 回归全部通过。

文档检查：

```bash
node scripts/check-doc-links.mjs
git diff --check
```

## 后续

M2-D02 基于本任务实现 PRIVATE/TEAM 可见性判定、Participant 加入/退出、Conversation 状态迁移、消息单调序号与历史 Cursor 规则。
