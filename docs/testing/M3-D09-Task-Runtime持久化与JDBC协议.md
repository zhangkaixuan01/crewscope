# M3-D09 Task Runtime 持久化与 JDBC 协议

## 结论

M3-D09 已完成。V10 的耐久 Task Runtime 数据模型已有完整的 JPA Entity、领域 Mapper、Repository Adapter，以及面向 READY Queue、Claim、Heartbeat、Phase Switch、Release 和 Sweeper 的 PostgreSQL JDBC 协议。

## Repository Port

应用层提供 17 个持久化 Port：

- Task、ConversationTaskLink、TaskExecution；
- PolicySnapshot、SafetyEnforcementOverlay、PlanVersion、StepExecution；
- ExecutionRuntime、RuntimeWorker、ExecutionLease；
- TaskCredentialGrant、TaskAgentRuntimeSession、AgentRun、AgentInterrupt；
- RuntimeArtifact、AgentStateSnapshot；
- TaskExecutionQueue。

基础设施层使用 13 个生产 Adapter 实现这些 Port；核心 Task/Runtime Adapter 组合实现五个无方法签名冲突的 Port，其余有独立事务语义或返回类型冲突的 Port 分开实现。

`TaskExecutionQueueRepository` 固定 READY 顺序为：

```text
priority DESC, notBefore ASC, createdAt ASC, executionId ASC
```

Keyset Cursor 保存完整排序元组。查询按 Organization 强制隔离，可选 Team 过滤，使用数据库权威时间判断 `not_before`，单批限制为 1–200 条。

多个 Port 的查询方法参数相同但返回领域类型不同，Java 无法仅按返回类型区分方法。因此 Policy、Overlay、Plan、Step、Grant、Session、Run、Interrupt、Artifact 和 Snapshot 使用独立 Adapter，保持类型边界与事务语义清晰。

## JPA 与 JDBC 分工

JPA 负责聚合父行、领域往返映射、Organization Scope 查询和乐观锁。JDBC 负责以下需要明确 SQL 语义的路径：

- Task 责任快照、Plan Step/Todo、Credential Tool/Provider、AgentRun Segment 等规范化子表；
- READY Queue Keyset 查询和 `FOR UPDATE SKIP LOCKED` 批量锁；
- TaskExecution 与 ExecutionLease 的 Claim、Heartbeat、Prepare/Run 切换、Release 条件更新；
- Lease 与 CredentialGrant 过期扫描；
- Task-side AgentRuntimeSession 的确定性 `INSERT ... ON CONFLICT DO NOTHING`。

Claim、Phase Switch 和 Release 在一个 Spring 事务内同时更新 TaskExecution 与 ExecutionLease。任一步骤的版本、状态或 Owner 条件不成立，整个事务回滚。Heartbeat 只更新 Lease Version，不放大 TaskExecution Version。

READY Claim、Lease Sweeper 和 Credential Sweeper 的锁查询必须运行在调用方开启的外层事务中。调用方在同一事务内完成 `FOR UPDATE SKIP LOCKED`、权威时间复验、条件状态迁移和提交，不能在 Repository 方法返回并释放行锁后再处理候选。锁方法使用强制事务传播约束，遗漏外层事务会立即失败。

Task-side AgentRuntimeSession 的确定性初始化命中既有 ID 后，重新校验完整不可变身份：WorkItem Scope、Task、TaskExecution、可选 StepExecution、Purpose、Agent Principal、AgentProfile ID、AgentScope Key 和 AgentState Reference。AgentProfile Version、Session Status、聚合 Version 与 Audit 是生命周期内可变事实，不属于确定性身份。身份不一致时失败关闭，不能把碰撞误判为幂等重试。

## 数据协议修正

- Hibernate 7 对 PostgreSQL `CHAR(64)` Hash 字段使用 `@JdbcTypeCode(SqlTypes.CHAR)`，读取时统一去除定长空格；
- JDBC 的 `TIMESTAMPTZ` 参数统一使用 `OffsetDateTime`，ResultSet 通过 `OffsetDateTime` 重建 `UtcTimestamp`；
- 可空源状态不再使用 `:sourceStatus IS NULL`，仅在存在源状态时动态加入谓词，避免 PostgreSQL 无法推断空参数类型；
- TaskExecution Priority 的领域与数据库范围统一为 `0..100`；
- AgentRun Segment 使用 UPSERT，只允许更新状态和结束时间，保留既有 Segment 身份及 Interrupt 外键；
- Snapshot 发布先提交旧 CURRENT 为 SUPERSEDED，再插入新 CURRENT，符合部分唯一索引；
- CredentialGrant 轮换先终止并 Flush 当前 Grant，再创建范围闭合的 Replacement。
- ExecutionLease 的持久状态固定为 `ACTIVE/RELEASED`；正常释放与 Sweeper 过期都写 `RELEASED`，具体语义由 `release_reason` 区分，过期写 `EXPIRED` Reason。

## PostgreSQL 自动化验证

`M3TaskRuntimePersistenceIntegrationTest` 提供 6 个真实 PostgreSQL 场景：

1. Task、责任快照、Policy、Overlay、Plan、Todo、Step 的领域往返、跨 Scope 拒绝和乐观锁；
2. Runtime、Worker 的 JSONB 能力和稳定 Key；
3. READY Queue 排序、完整 Keyset Cursor、索引查询计划和双事务 `SKIP LOCKED`；
4. Claim、Lease 过期扫描与双事务锁跳过、Heartbeat、Phase Switch、正常/过期 Release、旧 Heartbeat 拒绝和两步事务回滚；
5. TaskCredentialGrant Tool/Provider JSONB 范围、使用计数、JTI 查询、乐观锁、轮换和过期扫描；
6. Task-side Session 幂等初始化与不可变身份碰撞、AgentRun Segment、Interrupt/Resume、RuntimeArtifact、CURRENT/SUPERSEDED Snapshot 和恢复候选顺序。

专项验证命令：

```bash
DOCKER_CONFIG=/tmp/crewscope-testcontainers-docker-config \
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  -Dtest=M3TaskRuntimePersistenceIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

最终验证结果：

- Maven 全仓回归：7 个模块、851 个测试通过，0 Failure、0 Error、0 Skipped；
- 文档链接检查：115 个 Markdown 文件通过；
- `git diff --check` 与新增文件尾随空白检查通过。

## 后续边界

M3-I01 基于 Runtime 和 Worker Repository 实现注册、稳定 Worker Identity、Heartbeat、容量、Drain 与失联判定。M3-I02 在 READY Queue 和 ExecutionLease 条件更新协议上实现 Claim Scheduler。
