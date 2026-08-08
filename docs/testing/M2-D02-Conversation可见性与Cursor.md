# M2-D02：Conversation 可见性与 Cursor

> 日期：2026-08-08<br>
> 状态：已完成<br>
> 模块：`crewscope-domain`、`crewscope-application`

## 目标

完成 Conversation 的可见性裁决、Participant 生命周期、归档状态、消息单调序号及列表与历史 Keyset Cursor 契约，为 M2-D07 持久化分页和 M2-A01 Conversation API 提供稳定规则。

## 可见性契约

| Conversation | 主体 | 发现 | 读取 | 写入 |
|---|---|---:|---:|---:|
| PRIVATE | ACTIVE Participant | 是 | 是 | ACTIVE Conversation 可写 |
| PRIVATE | 非 Participant | 否 | 否 | 否 |
| TEAM | 当前 ACTIVE TeamMember | 是 | 是 | 否 |
| TEAM | ACTIVE Participant | 是 | 是 | ACTIVE Conversation 可写 |
| PRIVATE / TEAM | LEFT Participant | 是 | 仅 `leftAt` 及之前 | 否 |
| PRIVATE / TEAM | 停用 TeamMember 或 Principal | 否 | 否 | 否 |
| PRIVATE / TEAM | 显式 AGENT Participant | 是 | 是 | ACTIVE Conversation 可写 |
| ARCHIVED | 原有可读主体 | 是 | 是 | 否 |

`ConversationVisibilityPolicy` 同时校验 Organization、Team、TeamMember、Principal 和 Participant Scope。Agent 必须通过匹配的显式 `AGENT` Participant 进入 Conversation。`ConversationAccessDecision` 绑定 ConversationId，历史裁决无法读取其他 Conversation 的 Message。

Application Service 查询访问时必须解析并传入该用户已有的 Participant；LEFT 事实优先于 TEAM 的普通成员读取资格，防止退出者通过省略 Participant 绕过历史边界。

## Participant 生命周期

普通 TeamMember 的状态迁移为：

```text
加入：ACTIVE MEMBER
退出：ACTIVE -> LEFT
重新激活：LEFT -> ACTIVE
```

规则：

- 加入只接受同一 Team 的 ACTIVE TeamMember 和 ACTIVE USER；
- ParticipantId 继续由 `ConversationId + PrincipalId` 稳定生成；
- 加入记录 `joinedByPrincipalId`、`joinedAt` 和创建审计；
- 退出记录包含式历史边界 `leftAt`，推进 Version 和修改审计；
- 重新激活清空 `leftAt`，保留原 ParticipantId、joinedAt 和 createdAt；
- Owner 必须使用 OWNER Participant，不能以 MEMBER 身份重复加入；
- OWNER 和初始 Personal Agent Participant 始终保持 ACTIVE，不允许退出；
- 重复退出、重复激活、停用 Membership、身份或 Scope 不一致均稳定拒绝；
- 具体“谁可以邀请、移除或重新激活成员”的权限由后续 Application Service 按 Team Role 裁决，领域层只接受可信 ACTIVE Actor 并校验 Scope。

## Conversation 状态与消息序号

Conversation 支持：

```text
ACTIVE -> ARCHIVED
PRIVATE <-> TEAM（仅 ACTIVE）
```

归档是终态，保留历史读取并拒绝消息追加与可见性变更。状态与可见性变更都会推进 Version、UpdatedAt 和 UpdatedBy。

消息序号由 Conversation 聚合分配：

```text
第一条：1
后续：lastMessageSequence + 1
最大值：Long.MAX_VALUE
```

每次追加原子返回 `ConversationMessageAppend`，其中 Conversation 快照的 `lastMessageSequence` 必须等于 Message Sequence，同时推进 Conversation Version 和活动时间。`Message.post` 与 `Message.systemNotice` 保持包内可见，外部调用方不能绕过 Conversation 自行指定序号。数据库阶段由 M2-D07 使用行锁或等价原子策略及唯一约束裁决并发追加。

## Cursor 契约

Conversation 列表采用：

```text
updatedAt DESC, conversationId DESC
```

`ConversationListCursor(updatedAt, id)` 只接受排序位置之后的更旧记录。同一 UpdatedAt 使用 canonical UUID 文本降序裁决，与 PostgreSQL UUID keyset 顺序保持一致。

Message 历史采用 Conversation 内序号降序：

```text
sequence DESC
```

`ConversationMessageCursor(conversationId, sequence)` 与 Conversation 路由绑定，只接受更小 Sequence；跨 Conversation 复用 Cursor 直接拒绝。Base64URL 编解码属于 M2-A01/M2-A04 的 API 适配职责，本任务保持类型化 Cursor 值。

## 验证

专项命令：

```bash
./mvnw --batch-mode --no-transfer-progress -pl crewscope-domain,crewscope-application -am clean test
```

结果：Domain 161 个测试、Application 101 个测试通过，0 失败、0 错误、0 跳过。M2-D02 新增 15 个测试，并改造原有 Message 测试从 Conversation 聚合入口追加：

- Conversation 状态、审计、消息序号与溢出：3 个；
- Participant 加入、退出、重新激活及保护角色：4 个；
- PRIVATE/TEAM/LEFT/Agent/Archive 可见性：6 个；
- Conversation 与 Message Cursor：2 个。

全仓回归：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

结果：7 个 Maven 模块构建成功，442 个后端测试通过，0 失败、0 错误、0 跳过。AgentScope Harness、Docker Sandbox、PostgreSQL、Redis、Flyway、Spring Context 和 Server API 回归全部通过。

文档与差异检查：

```bash
node scripts/check-doc-links.mjs
git diff --check
```

## 后续

M2-D03 实现 `TaskIntentV1`、`ClarificationRequestV1`、Intent 版本和 DRAFT/READY/CONFIRMED/REJECTED/EXPIRED 生命周期。M2-D07 按本任务 Cursor、消息序号和并发边界实现数据库约束与 Repository Adapter。
