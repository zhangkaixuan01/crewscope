# M2-D07：M2 JPA 持久化适配

## 目标

将 M2-D01 至 M2-D06 的 Conversation、AgentRuntimeSession 和 Provider 领域契约落到 V7 PostgreSQL 数据结构，提供可由后续 Application Service 组合的持久化 Port、显式锁、Keyset 查询和并发裁决能力。

## 实现范围

Application 新增或扩展以下 Port：

- Conversation、ConversationParticipant、Message、TaskIntent 和 ConversationWorkItemLink Repository；
- AgentRuntimeSession 查询、更新和 `initializeIfAbsent`；
- ProviderDefinition、ProviderImplementation、Connection、ConnectionGrant 和 ProviderBinding Repository；
- Conversation 列表、Message 历史和 ProviderBinding 候选查询对象。

Infrastructure 新增 11 个 V7 JPA Entity、两个 Persistence Mapper 和三个 Repository Adapter。Entity 只保存标量 UUID、枚举字符串、时间、版本、审计字段及 JSONB 值，不声明 ORM 关系。所有读取和更新显式携带可信 Scope，乐观锁失败后的实际版本查询也使用相同 Scope，避免错误分支泄露其他租户或 Workspace 的状态。

提交前整体 review 将更新失败语义统一为失败关闭：Conversation Scope 内聚合的写入谓词和版本回查同时匹配 Organization、Team、Workspace 和 ID。相同 ID 在错误 Scope 下返回 `AggregateNotFoundException`，只有正确 Scope 内的过期版本返回 `OptimisticLockConflictException`。

## 映射契约

- Conversation、Participant、TaskIntent、AgentRuntimeSession 和 Provider 可变事实保留完整版本与 AuditMetadata；
- Message 和 ConversationWorkItemLink 保持原始内容与创建审计不可变；Message 新建时写入 `VISIBLE`，撤回和脱敏留给专用审核读模型；
- TaskIntent 展开保存 WorkProject、目标、验收标准、Owner、Executor、Gate Reviewer 和决策事实；
- Provider 能力与资源集合使用 Hibernate JSON 类型映射 PostgreSQL JSONB，并在领域重建时重新执行非空、形状和 Scope 校验；
- ProviderBinding 保存 Definition、Implementation、Connection 和 Grant 的固定版本，候选查询不代替 M2-I01 的当前事实闭合校验。

## 并发与幂等

### 消息追加

Application Service 在事务内调用 `ConversationRepository.lockById`，基于锁定快照分配下一条 MessageSequence，再原子更新 Conversation 并创建 Message。数据库 `(conversation_id, sequence)` 唯一约束作为最终裁决。客户端重试键按 Conversation 唯一，重复相同 Message ID 返回已提交消息，不允许相同键关联另一条消息。

### TaskIntent 确认

普通 `update` 不接受 CONFIRMED 状态。`confirm` 必须同时携带已创建的 WorkItem ID，版本条件更新额外要求数据库当前状态为 READY，并在同一写入中保存确认决策和 `confirmed_work_item_id`。行锁、状态谓词、版本和唯一索引共同保证一个 Intent 只产生一个确认关联。

### AgentRuntimeSession 初始化

`initializeIfAbsent` 锁定父 Conversation 行，在锁内先读取确定性 Session ID 和当前 active Session。并发初始化最终返回同一个已提交 Session，active Binding 唯一索引和 AgentScope Key 唯一约束继续提供数据库兜底。

### ProviderBinding 候选

候选查询固定 Organization、Team、Workspace、Owner、ProviderType 和 ACTIVE 状态。指定 WorkProject 时同时返回该 Project 与 Workspace 层候选；未指定时只返回 Workspace 层。查询使用 V7 resolver 索引，M2-I01 负责优先级、当前版本、Connection/Grant 可用性和同级歧义失败关闭。

## PostgreSQL 验证

`M2JpaPersistenceIntegrationTest` 包含 8 个测试，覆盖：

1. Conversation、Message 映射、客户端幂等和两类 Keyset 分页；
2. Conversation 乐观锁冲突及实际版本；
3. 两路并发消息追加得到连续且唯一的 `1, 2` 序号；
4. 两路 TaskIntent 确认只有一条 WorkItem 关联成功；
5. 两路 AgentRuntimeSession 初始化返回同一 Session 且数据库只有一行；
6. Workspace/WorkProject ProviderBinding Scope 候选及 resolver 索引查询计划；
7. Connection、ConnectionGrant、能力资源 JSONB、生命周期更新和版本检查。
8. Participant、TaskIntent 和 ProviderBinding 在错误 Team/Workspace Scope 下失败关闭，不暴露实际版本。

专项验证：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  -Dtest=M2JpaPersistenceIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：8 个 PostgreSQL 测试通过，失败、错误、跳过均为 0。

全仓回归：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

结果：7 个 Maven 模块构建成功，505 个后端测试全部通过，失败、错误、跳过均为 0。AgentScope Harness、Docker Sandbox、PostgreSQL、Redis、Flyway、Spring 装配、Server API 和 Provider Adapter 回归通过。

## 后续任务

下一项为 M2-S01，验证受 CrewScope 身份和 Scope 控制的 AG-UI Bridge，固定服务端 Agent、Conversation、Session 与 Tool 边界，并更新 ADR-005。
