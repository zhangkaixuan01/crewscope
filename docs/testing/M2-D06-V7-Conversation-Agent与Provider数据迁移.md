# M2-D06：V7 Conversation、Agent 与 Provider 数据迁移

> 日期：2026-08-08<br>
> 状态：已完成<br>
> 模块：`crewscope-infrastructure`

## 目标

使用 `V7__conversation_agent_and_provider_binding.sql` 将 M2-D01 至 M2-D05 的领域契约落到 PostgreSQL，建立 Conversation、Personal Agent Runtime Session 与 Provider 授权事实的租户隔离、审计、幂等和并发裁决边界，为 M2-D07 JPA Adapter 与 M2-I01 BindingResolver 提供稳定数据库契约。

## 数据表

V7 新增 11 张表：

```text
conversation
conversation_participant
message
task_intent
conversation_work_item_link
agent_runtime_session
provider_definition
provider_implementation
connection
connection_grant
provider_binding
```

同时为 `credential_secret` 增加 Organization 复合候选键，为 `agent_profile` 增加 Runtime Binding 复合候选键。迁移不修改 V1 至 V6 文件，不回填虚构 Conversation、Provider 或 Binding。

## Conversation 与 TaskIntent

Conversation 保存完整 Organization、Team、Workspace、Owner TeamMember、Owner USER、Personal Agent、可见性、最后消息序号、版本和审计。复合外键证明 Owner Member 属于精确 USER 与 Team，Personal Agent 属于当前 Team。

Participant 保存 USER 或 Agent 的稳定参与事实。部分唯一索引保证同一 Conversation 与 Principal 最多存在一个 active Participant；成员复合外键同时证明 TeamMember 与 USER 身份匹配。只有 MEMBER 可以进入 LEFT 并保留历史边界。

Message 使用 `(conversation_id, sequence)` 保存不可重复的单调序号，使用 `(conversation_id, client_message_key)` 部分唯一索引裁决客户端重试。作者必须匹配精确 Participant Principal；SYSTEM_NOTICE 不伪造作者。原始内容和创建审计保持不变，撤回与脱敏使用独立状态、操作者、时间和 Reason Code，完整原因进入 AuditEvent。

TaskIntent 展开保存目标 WorkProject、目标、验收标准和 Owner/Executor/Gate Reviewer 资格，数据库校验职责形状与 Gate Reviewer 分离。终态决策字段必须成组出现；CONFIRMED 必须指向同 Scope WorkItem，确认 WorkItem 部分唯一，确保一个 WorkItem 不被多个 TaskIntent 重复认领为确认结果。

ConversationWorkItemLink 通过完整 WorkItem Scope 外键和 Conversation/WorkItem 唯一对保存真实双向关联。

## AgentRuntimeSession

AgentRuntimeSession 闭合 Conversation Owner、Personal Agent、AgentProfile 与 Team Workspace，保存：

```text
AgentProfile Version Snapshot
AgentScope userId / sessionId
crewscope:agent-state:v1:{runtimeSessionId}
ACTIVE / DISABLED / ARCHIVED
Version / Audit
```

部分唯一索引保证同一 Conversation、Owner TeamMember 与 Personal Agent 最多存在一个 active Session。AgentScope Key 与 State Reference 全局唯一。AgentProfile 版本是读取时比较的快照；外键只约束稳定 Profile 身份和 Scope，因此 Profile 正常升级不会被历史 Session 阻塞。

## Provider、Connection 与 Binding

ProviderDefinition 和 ProviderImplementation 保存稳定 Key、类型、接口/实现版本、能力、连接要求、Connector、状态、聚合版本和审计。Implementation 通过复合外键绑定同 Organization 的 Definition、ProviderType 与接口版本。

Connection 使用结构化 Owner 列表达 USER、TEAM 或 ORGANIZATION，引用 Organization 内 CredentialSecret，不保存凭证明文。ConnectionGrant 同时固化 Connection Owner 与 Grantee；数据库允许自身授权和 Organization 向更窄 Owner 下放，拒绝 TEAM/USER 向其他 Owner 扩权。能力与资源集合使用 JSONB，并校验非空能力以及 unrestricted/显式资源的互斥形状。

ProviderBinding 闭合 Workspace 或 WorkProject、Owner、Definition、Implementation、Connection、Grant、执行身份、有效能力/资源范围和默认用途。外部 Implementation 的 `connection_requirement=REQUIRED` 强制 Connection、Grant、版本快照和执行身份同时存在；connectionless Implementation 强制全部为空。Grant 复合外键证明 Binding Owner 是精确 Grantee。部分唯一索引保证同一解析层级最多一个 active 默认 Binding，同时允许多个非默认候选由 Resolver 返回歧义并失败关闭。

Binding 保存 Definition、Implementation、Connection 和 Grant 的聚合版本快照。数据库外键只约束稳定身份、类型、Owner 和 Scope，不引用可变聚合的当前版本；依赖聚合推进版本后，BindingResolver 比较快照并使旧 Binding 失效，不阻塞聚合更新。

## 自动化验证

专项验证：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  -Dtest=V7ConversationAgentProviderMigrationIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：新增 7 个 PostgreSQL 17 Testcontainers 测试全部通过，失败、错误、跳过均为 0。覆盖：

- 空库 V1→V7 全量迁移；
- 带 AgentProfile、WorkItem 和 CredentialSecret 数据的 V6→V7 单步升级；
- 非默认 `search_path` 仍只在 `crewscope` Schema 建表；
- 复合 Scope 外键和审计字段；
- active Participant、消息序号与客户端消息键并发唯一性；
- Participant 与 Message 作者绑定，拒绝伪造作者；
- active AgentRuntimeSession、AgentProfile Scope 与版本快照升级；
- TaskIntent 决策形状和唯一确认 WorkItem；
- Provider Owner、Grant 下放、Binding connection shape 和唯一 active 默认 Binding；
- Session/Binding 版本快照不阻塞依赖聚合推进版本。

全仓回归：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

结果：7 个 Maven 模块构建成功，497 个后端测试全部通过，失败、错误、跳过均为 0。AgentScope Harness、Docker Sandbox、PostgreSQL、Redis、Flyway、JPA、Spring 装配、Server API 与 Native/GitHub/Lark Provider Adapter 回归通过。

文档与差异检查：

```bash
node scripts/check-doc-links.mjs
git diff --check
```

## 后续任务

M2-D07 基于 V7 实现 Conversation、Participant、Message、TaskIntent、ConversationWorkItemLink、AgentRuntimeSession、Provider Registry、Connection、Grant 与 Binding 的 JPA Entity、Mapper、Repository Adapter、锁查询和 Keyset Cursor。M2-I01 在此基础上实现只读 BindingResolver。
