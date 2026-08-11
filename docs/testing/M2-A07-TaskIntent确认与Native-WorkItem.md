# M2-A07：TaskIntent 确认与 Native WorkItem 原子创建

> 状态：已完成
> 日期：2026-08-11
> 模块：`crewscope-domain`、`crewscope-application`、`crewscope-infrastructure`、`crewscope-server`

## 交付目标

M2-A07 完成从对话式 TaskIntent 到传统管理 WorkItem 的原子闭环。人类 Owner 确认 READY TaskIntent 后，服务端从当前事实创建 Native WorkItem、责任关系和 ConversationWorkItemLink；Conversation Mode 与 Control Mode 读取同一组 PostgreSQL 事实。

## 确认 API

```text
POST /api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/task-intents/{taskIntentId}/confirmations
```

请求必须携带：

```text
Idempotency-Key: <command-key>
If-Match: "<task-intent-version>"
```

请求体必须为空，任何非空载荷都返回 `400 invalid_request`。客户端不能指定 WorkItem ID、Key、ProviderBinding、Owner、Executor、Gate Reviewer、Assignment 或 ConversationWorkItemLink。服务端锁定 TaskIntent 后复用确认预检，并重新验证当前 Proposal、确认人、WorkProject、Principal、TeamMember、职责分离与 `WORK_CREATE` 权限。

## 原子业务图

同一 REQUIRED 事务依次完成：

1. 预留 CommandReceipt，幂等重放直接返回原回执；
2. 锁定 TaskIntent，验证 Scope、READY 状态和强版本；
3. 读取 Conversation，锁定并验证目标 WorkProject；
4. 通过 A06 BindingResolver 解析唯一内置 connectionless `native-work-item` Binding；
5. 在 WorkProject 行锁保护下生成下一个项目内 WorkItem Key；
6. 创建 Native WorkItem、Owner、可选 Executor 与可选 Gate Reviewer；
7. 使用当前 `GateReviewerPolicyProvider` 重新执行 Gate Reviewer 资格与职责分离；
8. 执行 `READY -> CONFIRMED` 并写入 `confirmed_work_item_id`；
9. 创建 `TASK_INTENT_CONFIRMATION` 来源的 ConversationWorkItemLink；
10. 发布 WorkItem、责任与 `TASK_INTENT_CONFIRMED` 事件，写入 Conversation Event、Outbox 并完成 CommandReceipt。

任何一步失败时，TaskIntent、WorkItem、责任关系、关联、事件、Outbox 与回执全部回滚。

## WorkItem 映射与 Key 分配

- Type 固定为 `TASK`，Priority 固定为 `MEDIUM`；
- Labels 为空，DueAt 为空；
- Title 使用 Objective 规范化结果，最多 500 字符，并避免在 UTF-16 代理对中间截断；
- Description 保存完整 Objective 和 Acceptance Criteria Markdown；
- Key 使用 WorkProject Key 加项目内数字序号；Repository 读取最大数字后缀后加一；
- 调用方必须在同一事务内完成 WorkProject 行锁、`nextKey` 和 WorkItem 插入；
- `(project_id, item_key)` 唯一约束作为最终并发兜底。

## 事件与幂等

确认可能发布：

- `WORK_ITEM_CREATED`；
- `WORK_ITEM_EXECUTOR_ASSIGNED`；
- `WORK_ITEM_GATE_REVIEWER_ASSIGNED`；
- 根事件 `TASK_INTENT_CONFIRMED`。

多个事件共享同一 Correlation/Causation 关系。只有根 `TASK_INTENT_CONFIRMED` 携带命令 `Idempotency-Key`，满足现有 `ux_domain_event_idempotency` 唯一约束；CommandReceipt 指向根事件，并保存确认后的 TaskIntent Version。重放不会重新读取或创建业务事实。

## 双向查询

```text
GET /api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/work-items
GET /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/conversations
```

Conversation 方向先验证 Conversation 可见性，再验证每个关联 WorkItem 的当前可见性。WorkItem 方向先验证 WorkItem 可见性，再逐项验证 Conversation；调用者不可发现的 PRIVATE Conversation 不出现在结果中。Repository 返回跨 Organization、Team、Workspace、Conversation、WorkProject 或 WorkItem Scope 的关联时失败关闭。响应使用 `Cache-Control: no-store`。

## 验证结果

- 新增 14 项测试或测试方法；全仓 `clean verify` 共执行 693 项测试，零失败、零错误、零跳过；
- Application 测试覆盖完整业务图、幂等重放、Binding 歧义、责任创建失败、双向读取和 PRIVATE Conversation 隐藏；
- HTTP 测试覆盖空请求体确认路由、非空请求体拒绝、`Idempotency-Key`、强 `If-Match`、双向查询、非法嵌套 ID 与 `no-store`；
- PostgreSQL 测试覆盖完整确认事务、Outbox 失败全图回滚和两个并发确认生成不同 WorkItem Key；
- WorkItem Repository PostgreSQL 测试覆盖数字后缀分配，避免字符串字典序在 `KEY-9` 与 `KEY-10` 处产生错误；
- Spring 全上下文装配由全仓 `clean verify` 验证。

验证命令：

```bash
/Users/zhangkaixuan/Downloads/apache-maven-3.9.6/bin/mvn clean verify
/Users/zhangkaixuan/.nvm/versions/node/v24.13.1/bin/node scripts/check-doc-links.mjs
git diff --check
```
