# M2-A01：Conversation 应用与 API

> 日期：2026-08-10<br>
> 状态：已完成<br>
> 模块：`crewscope-domain`、`crewscope-application`、`crewscope-infrastructure`、`crewscope-server`

## 目标

交付 Conversation 创建、列表、详情、参与者管理和消息历史的应用层与 HTTP 纵向切片，让 Web 前端只使用 CrewScope 服务端解析的 TeamMember、Workspace 和 Personal Agent 事实。

## 应用契约

- 创建 Conversation 时从当前 ACTIVE TeamMember 解析 Team 默认 Workspace、默认 Personal Agent Profile 与 Agent Principal；
- Conversation、OWNER Participant、AGENT Participant、DomainEvent、Outbox 与 CommandReceipt 在同一事务提交；
- PRIVATE 列表只返回当前 USER 的显式 Participant，TEAM 列表对当前 ACTIVE TeamMember 可发现；
- 详情返回 Conversation 与完整 Participant 生命周期快照，不可见资源统一按不存在处理；
- 消息历史按 Sequence 降序分页，Cursor 与 Conversation 路由绑定；
- LEFT Participant 的历史查询在数据库层应用包含式 `leftAt` 截止条件；
- Owner 以 `COLLABORATION_REQUEST` 权限管理普通 Participant，Participant 可以退出自身；
- Participant 变更先锁定 Conversation 行，同一 Conversation 的加入、重新加入和退出串行提交；
- 归档 Conversation、停用 Membership 和停用 Principal 立即失败关闭。

## HTTP 契约

```text
POST   /api/v1/organizations/{organizationId}/teams/{teamId}/conversations
GET    /api/v1/organizations/{organizationId}/teams/{teamId}/conversations
GET    /api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}
POST   /api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/participants
DELETE /api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/participants/{participantId}
GET    /api/v1/organizations/{organizationId}/teams/{teamId}/conversations/{conversationId}/messages
```

POST 与 DELETE 必须携带 `Idempotency-Key`，返回统一 `202 CommandReceiptResponse`。查询返回 `Cache-Control: no-store`。列表 Cursor 编码 `updatedAt + conversationId`，Message Cursor 编码 `conversationId + sequence`，均使用带版本的规范 Base64URL 二进制格式。

## 实施结果

- `ConversationApplicationService` 交付创建、可见列表、详情、消息历史、加入与退出六个用例；
- 创建过程由服务端解析 Owner、默认 Workspace 和默认 Personal Agent，并原子提交业务事实、事件、Outbox 与命令回执；
- JPA 列表查询在 SQL 层按 `viewerPrincipalId` 收口 PRIVATE Conversation，Message 查询在 SQL 层执行 `leftAt` 截止；
- HTTP 边界交付六条 Team Scope 路由、统一身份解析、输入校验、幂等回执和 `no-store` 缓存策略；
- Conversation 与 Message Cursor 使用带版本、固定长度、规范 Base64URL 编码，并拒绝跨 Conversation Message Cursor；
- Spring Boot 使用显式 `@Configuration` 与构造器装配应用服务和 `ConversationVisibilityPolicy`。

## 验证结果

- `ConversationApplicationServiceTest`：7 项通过，覆盖默认 Personal Agent、PRIVATE 隐藏、LEFT 历史截止、Owner 管理、成员自行退出、OWNER 保护和归档只读；
- `M2JpaPersistenceIntegrationTest`：10 项 PostgreSQL 集成测试通过，其中可见列表 Keyset 与历史截止条件在 PostgreSQL 17 实测；
- `ConversationControllerTest`、`ConversationCursorCodecTest`、`ApplicationCompositionConfigurationTest`：8 项通过，覆盖六条路由、Cursor、缓存头、回执和 Spring 装配；
- 全仓 `/Users/zhangkaixuan/Downloads/apache-maven-3.9.6/bin/mvn clean verify`：620 项测试通过，0 Failure、0 Error、0 Skipped；
- `node scripts/check-doc-links.mjs`、`git diff --check` 与源码卫生扫描通过。
