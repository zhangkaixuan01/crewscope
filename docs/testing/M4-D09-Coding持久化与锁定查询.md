# M4-D09 Coding 持久化与锁定查询

> 完成日期：2026-08-18
> 范围：`crewscope-application`、`crewscope-infrastructure`、PostgreSQL 17

## 1. 交付结果

M4-D09 将 M4-D01 至 M4-D07 的领域 Port 接入 V14 PostgreSQL 结构，交付 9 个 Spring Bean：

| Application Port | Infrastructure Adapter | 持久化语义 |
|---|---|---|
| `RepositoryBindingRepository` | `JdbcRepositoryBindingRepositoryAdapter` | 创建、Scope 查询、列表、乐观锁更新 |
| `CodingTargetSnapshotRepository` | `JdbcCodingTargetSnapshotRepositoryAdapter` | 不可变 Revision 创建、按 Task 稳定排序查询 |
| `ExecutionWorkspaceRepository` | `JdbcExecutionWorkspaceRepositoryAdapter` | 创建、乐观锁更新、恢复和保留期锁定领取 |
| `WorkspacePolicyRepository` | `JdbcWorkspacePolicyRepositoryAdapter` | 不可变 Policy 创建与查询 |
| `WorkspacePolicyOverlayRepository` | `JdbcWorkspacePolicyRepositoryAdapter` | 首版创建、按父 Hash 的追加式条件更新 |
| `DiffArtifactRepository` | `JdbcDiffArtifactRepositoryAdapter` | 最终 Diff 根事实与文件清单原子发布 |
| `CommandEvidenceRepository` | `JdbcCommandEvidenceRepositoryAdapter` | 命令证据创建与按 Sequence 查询 |
| `TestEvidenceRepository` | `JdbcTestEvidenceRepositoryAdapter` | 测试、命令引用和验收结果原子发布 |
| `CodingCheckpointRepository` | `JdbcCodingCheckpointRepositoryAdapter` | 单调 Checkpoint 创建与最新版本查询 |

`CodingPersistenceMapper` 负责领域值与 JDBC 行之间的双向映射。Diff、Command、Test 和 Checkpoint 共享内部 `JdbcCodingArtifactRepositoryAdapter`，对 Application 层仍由四个类型安全 Port Bean 暴露，避免共享存储实现泄露到应用契约。

## 2. JDBC 选择

本阶段使用 Spring JDBC。V14 包含完整 Scope 复合外键、规范化子表、JSONB、条件写入和 PostgreSQL 行锁，SQL 需要精确表达以下行为：

- 更新语句同时校验 Scope、聚合 ID 和上一版本；
- Overlay 使用 `INSERT ... SELECT ... WHERE EXISTS` 原子校验当前父 Hash；
- Workspace 使用 `FOR UPDATE SKIP LOCKED` 领取互斥批次；
- Artifact 根表与规范化子表在同一事务内发布；
- 唯一约束名称映射为稳定领域冲突。

Adapter 使用构造器注入并由 `@Repository` 或 `@Component` 参与 Spring 装配。M4-D09 不引入 Controller、Worker、Git、Worktree 或 Sandbox 实现。

## 3. Scope、版本与冲突

所有公开查询都要求 Port 契约规定的 Organization、Team 和 WorkProject 坐标；只使用聚合 ID 不能跨 Scope 读取事实。Workspace 的后台锁定查询以 Organization 为租户边界，并进一步限制 RuntimeEnvironment 或到期时间。

RepositoryBinding 和 ExecutionWorkspace 更新使用：

```text
scope + aggregate_id + expected_version
```

受影响行数为零时，Adapter 使用完整 Organization、Team、Workspace、WorkProject Scope 复查当前版本，区分聚合不存在与版本陈旧，并抛出 `AggregateNotFoundException` 或 `OptimisticLockConflictException`。复查不能只使用 Organization 和聚合 ID，避免通过冲突类型披露其他 Team/Workspace/WorkProject 中的聚合。WorkspacePolicyOverlay 使用当前 Overlay Hash 和前一 Version 作为 compare-and-set 条件，拒绝从陈旧 Overlay 分叉。

`CodingPersistenceConflictMapper` 读取 PostgreSQL SQLState `23505` 和 V14 约束名，将并发唯一键竞争映射为稳定领域异常：

- `RepositoryBindingKeyConflictException`
- `CodingTargetSnapshotRevisionConflictException`
- `ExecutionWorkspaceAttemptConflictException`
- `DiffArtifactWorkspaceConflictException`
- `CommandEvidenceSequenceConflictException`
- `TestEvidenceSequenceConflictException`
- `CodingCheckpointSequenceConflictException`

其他完整性异常保持原始 Spring 数据访问异常，不被错误归类。

## 4. Workspace 锁定查询

`findRecoveringForUpdate` 和 `findRetentionDueForUpdate` 使用稳定排序、显式 `LIMIT` 与 `FOR UPDATE SKIP LOCKED`。多个 Worker 并发扫描时，已经被一个事务锁定的 Workspace 会被其他事务跳过，因此同一批次不会被重复领取。

行锁只在调用方事务存续期间有效。两个锁定查询使用 `Propagation.MANDATORY` 强制调用方先开启事务；没有外层事务的直接调用立即失败。后续 M4-I10/A03 的 Worker 编排必须在同一外层事务内完成“锁定、判定、条件更新或建立耐久领取事实”，不能在 Repository 返回后先提交事务再执行领取写入。

## 5. Artifact 与查询边界

DiffArtifact、TestEvidence 和 CodingCheckpoint 都包含根事实及规范化子表。创建方法使用事务包围整张对象图；任何子表写入失败都会回滚根表，数据库中不会留下半成品 Artifact。

当前 Port 查询遵守以下规则：

- Revision、EvidenceSequence 和 CheckpointSequence 使用稳定顺序；
- 单值查询依赖 V14 唯一键；
- Workspace 锁扫描强制有界 `LIMIT`；
- Diff、Command、Test 和 Checkpoint 通过面向聚合的查询一次还原完整对象图。

公开 API 的不透明 Cursor、DTO 批量投影和 N+1 门禁属于 M4-A04。D09 保持领域 Port 最小化，不提前增加面向 Web 的分页契约。V14 迁移测试已验证对应索引存在；测试不对小数据集的 PostgreSQL Planner 强制断言某一种执行计划。

## 6. 时间、JSON 与路径安全

`TIMESTAMPTZ` 参数通过 `CodingJdbcValue.timestamp` 显式转换为 UTC `OffsetDateTime`，避免 PostgreSQL JDBC 对 `Instant` 参数进行类型猜测。持久化入口使用数据库可表达的时间精度；进入 Hash 闭合事实的时间需要在上游保持相同规范化精度。

AllowedPaths、AcceptanceCriteria、CommandCatalog、argv 和 Todo 使用 Jackson 与 JSONB 往返，恢复后重新经过领域值对象校验。数据库、DTO 和日志只保存 RepositoryKey、WorkspaceKey、受管分支、归档引用与仓库内相对路径，宿主 Repository/Worktree 绝对路径不进入持久化层。

V14 原先通过 `POSITION(CHR(0) IN text_column)` 检查文本 NUL。PostgreSQL `text` 本身不能构造或保存 NUL，表达式会在正常非空文本写入时先失败。M4-D09 从 Diff Preview、Command/Test/Acceptance Summary 约束中移除了冗余 `CHR(0)` 表达式，由 PostgreSQL 与 JDBC 的文本类型边界直接拒绝 NUL。

## 7. 自动化证据

`M4D09CodingPersistenceIntegrationTest` 在真实 PostgreSQL 上覆盖 8 个场景：

1. 9 个 Port Bean 装配、RepositoryBinding 往返和版本更新；
2. 乐观锁冲突与 Organization/WorkProject 查询隔离；
3. 并发 RepositoryKey 创建只成功一次，并映射稳定领域冲突；
4. CommandCatalog 和字符串集合 JSON 往返；
5. CodingTarget、Workspace、Policy 与 Overlay compare-and-set 往返；
6. Diff、Command、Test、Checkpoint 及规范化子表完整往返；
7. 双事务 `FOR UPDATE SKIP LOCKED` 互斥领取、Workspace 条件更新和陈旧版本冲突；
8. Diff 子表强制失败时根表事务回滚。

专项验证命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  -Dtest=M4D09CodingPersistenceIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## 8. 后续边界

M4-I01 已通过类型化 `GitCommandExecutor` 实现宿主 Git 只读与 Worktree 管理基础，证据见 [M4-I01 类型化 GitCommandExecutor](M4-I01-类型化GitCommandExecutor.md)。M4-I02 继续实现受管 Repository Resolver 与 Baseline Preflight。M4-A04 在持久化 Port 之上增加公开 DTO、Cursor、批量投影和 N+1 验收。
