# M2-A05：TaskIntent 与确认预检

> 状态：已完成
> 日期：2026-08-11
> 模块：`crewscope-application`、`crewscope-agentscope`、`crewscope-infrastructure`、`crewscope-server`

## 交付目标

M2-A05 把 AgentScope Structured Output、结构化澄清和 TaskIntent 人工复核连接为可管理的业务事实。最终确认与 WorkItem 创建继续由 M2-A07 在同一事务实现。

## 澄清契约

生产 Personal Agent Toolkit 注册只读内置 Tool：

```text
request_clarification
```

Tool 输入包含 `ClarificationRequestV1`。首次调用固定进入 AgentScope Permission ASK；服务端保存当前 Interrupt Token，Web Resume 只提交 1–10 个字段回答：

```json
{
  "answers": {
    "repository": "crewscope-java",
    "branch": "main"
  }
}
```

Field Key 必须符合 `[a-z][a-z0-9_]{0,63}`，单个回答最多 1,000 字符。Bridge 以服务端 Pending `ToolUseBlock` 为基线，只写入 `answers`，保留原 Tool Name 与 Tool Call ID。内置 Tool 校验回答只能指向已声明问题，并要求所有 Required 问题获得回答；随后把规范化答案作为 Tool Result 交给模型。客户端不能提交 Tool、ConfirmResult、PermissionRule、Session、replyId 或 Tool Call ID。

Resume 的请求哈希使用排序后的 Field Key 与规范化回答。相同 `Idempotency-Key` 和相同回答重放原 Segment；相同 Key 对应不同回答返回 `idempotency_conflict`，不会再次进入 AgentScope。回答同时以安全 Markdown 形成 USER Message，保留 Conversation 可见历史。

## TaskIntent 提交契约

AgentScope 仅在执行成功完成并通过 Bean Validation 后产生 `TaskIntentOutputCandidate`。应用层在 `RUN_FINISHED` 可见前执行：

```text
验证 Invocation / Segment / Conversation
  -> 锁定 Conversation 并验证 AGENT Participant
  -> 重新解析 WorkProject、Principal 与 TeamMember
  -> Domain 创建 DRAFT
  -> DRAFT 转为 READY
  -> 写 TASK_INTENT_PROPOSED DomainEvent
  -> 写 Conversation Event 与 Outbox
  -> 提交事务
  -> 发布 RUN_FINISHED
```

TaskIntent ID 从 Invocation 与 Segment 稳定派生。完全相同的 Candidate 重放返回已提交事实；相同稳定 ID 携带不同 Scope、Conversation、Agent 或 Proposal 时失败关闭，不产生第二组事件。

## HTTP 契约

```text
GET  /api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/task-intents/{taskIntentId}
POST /api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/task-intents/{taskIntentId}/revisions
POST /api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/task-intents/{taskIntentId}/confirmation-previews
POST /api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/task-intents/{taskIntentId}/rejections
```

- 所有读取先通过嵌套 Conversation 可见性，不泄露跨 Team 或跨 Conversation ID；
- GET 与确认预检返回 `Cache-Control: no-store` 和当前强 ETag；
- 修订与拒绝要求 `Idempotency-Key` 和 `If-Match`，返回 `202 CommandReceipt`；
- 确认预检要求 `If-Match`，重新解析当前 Project、Principal、Membership 和职责分离事实；
- 修订、拒绝和确认预检只允许当前 Proposal 中的人类 Owner；
- 完整修订执行 `READY -> DRAFT -> READY`，Proposal Revision 增加 1，Aggregate Version 原子增加 2；
- 确认预检调用未落库的领域确认迁移验证版本、READY 状态、Proposal 一致性和 Owner，不修改 TaskIntent，不创建 WorkItem，不写事件；
- ReviewerEligibilityPolicy 依赖真实 WorkItem，M2-A07 创建 WorkItem 与责任关系时再次执行完整 Gate Policy；
- `/confirmations` 路由、`READY -> CONFIRMED`、WorkItem、责任和 ConversationWorkItemLink 均由 M2-A07 交付。

## 事件与事务

`TASK_INTENT_PROPOSED`、`TASK_INTENT_REVISED` 和 `TASK_INTENT_REJECTED` 同时进入 DomainEvent、Conversation Event 与 Outbox。TaskIntent Payload 实现显式 Conversation 归属接口，Conversation Event Repository 不从任意 JSON 或聚合 ID 推断所属会话。

修订的两个版本写入、DomainEvent、Conversation Event、Outbox 和 CommandReceipt 位于同一 REQUIRED PostgreSQL 事务。Outbox 故障时 DRAFT 中间状态、最终 READY、事件和 Receipt 全部回滚。

## 验证结果

- 新增 18 项测试或测试方法；全仓 `clean verify` 共执行 665 项测试，零失败、零错误、零跳过；
- Application 测试覆盖 Candidate 稳定提交、内容冲突重放、完整修订、当前事实确认预检、版本冲突、Owner 权限、拒绝重放和 Schema 边界；
- AgentScope 测试覆盖生产澄清 Tool 的 Required/Declared Field 校验，以及结构化 `answers` 到 Pending Tool、Tool Result 和后续 Structured Output 的真实恢复链路；
- HTTP 测试覆盖 TaskIntent DTO、ETag、`no-store`、Receipt、结构化 Resume、客户端 Tool 字段注入、缺失 Header、Weak/多值/通配 ETag 和 Bean Validation；
- PostgreSQL 测试覆盖完整修订的两次版本推进和 Outbox 故障全回滚；
- Spring 装配测试确认 `TaskIntentApplicationService` 只有一个生产 Bean。

验证命令：

```bash
/Users/zhangkaixuan/Downloads/apache-maven-3.9.6/bin/mvn clean verify
node scripts/check-doc-links.mjs
git diff --check
```
