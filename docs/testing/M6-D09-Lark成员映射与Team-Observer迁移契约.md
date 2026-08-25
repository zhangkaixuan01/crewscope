# M6-D09 Lark 成员映射与 Team Observer 迁移契约

> 任务：`M6-D09`<br>
> 日期：2026-08-25<br>
> 状态：完成<br>
> 前置契约：M6-D04、M6-D05、M6-D08、ADR-022

## 1. 交付目标

M6-D09 通过 `V28__lark_mapping_and_team_observer.sql` 完成两组 PostgreSQL 持久化能力：

- `LarkExternalTenant`、短期 `LarkMemberVerificationProof` 与管理员确认的 `LarkMemberMapping`；
- 符合完整初始化条件的既有 ACTIVE Team 的 Organization 级 `team-observer@1`、确定性 TEAM_AGENT Principal 与 TEAM-owned AgentProfile。

本任务只建立迁移、数据库约束和回填。Lark HTTP Client、持久化 Adapter、通知 Worker、Team Observer AgentScope Runtime 和 HTTP API 由 M6-I03 至 M6-I07、M6-A04 和 M6-A05 实现。

## 2. Lark 外部身份持久化

V28 新增三张表：

1. `lark_external_tenant` 保存 Organization、Connection/Grant 当前授权快照、精确 `tenant_key`、Provider Version、验证时间和强版本；ID 使用与 Java `UUID.nameUUIDFromBytes` 一致的 UUID v3 规则，由 Organization 与 Connection 确定性派生；
2. `lark_member_verification_proof` 保存 Organization、Team、ProviderBinding、Connection、Grant、ExternalTenant 的完整坐标，以及精确 Open ID、Union ID、Provider Version 和最多 15 分钟确认窗口；
3. `lark_member_mapping` 保存 TeamMember、完整授权快照、验证来源、管理员和标准审计字段，状态只允许从 `ACTIVE` 单调终结为 `REVOKED` 或 `INVALIDATED`。

复合外键闭合 Organization、Team、Member、ProviderBinding、Connection、Grant 和 ExternalTenant。业务 Version 是授权计划的不可变快照，由应用在使用前与当前聚合版本复验，不作为指向可变当前行的外键。

数据库按领域 Value Object 规则校验：

- `tenant_key`：`[A-Za-z0-9][A-Za-z0-9_-]{0,127}`；
- `open_id`：`ou_[A-Za-z0-9_-]{1,120}`；
- `union_id`：`on_[A-Za-z0-9_-]{1,120}`；
- Provider Version 为 1 至 200 个非控制字符；
- Proof 满足 `verified_at < valid_until <= verified_at + 15 minutes`；
- Mapping 的确认审计时间不早于 Proof 验证时间。

两个部分唯一索引分别保证同一 `organization + team + member` 最多一个 ACTIVE Mapping，以及同一 `organization + tenant_key + open_id` 最多一个 ACTIVE Mapping。终态行保留身份与授权证据并释放活动唯一键，允许管理员完成显式替换。Tenant、Proof 和 Mapping 历史禁止删除；Tenant 与 Proof 的 `INVALIDATED` 为不可复活终态；Proof 身份不可修改；Mapping 只有状态、终结原因、强版本和修改审计可以在一次合法终结中变化。

V27 `notification_planned_action` 新增 Organization、Team、Member、Mapping ID 闭合外键。该外键使用 `NOT VALID` 接受 V27 先于 Mapping 表产生的历史授权快照，同时对 V28 后的新写入立即执行检查。Mapping Version 继续作为计划时快照，由通知 Planner 和 Worker 在 M6-E04/M6-I03 复验。

## 3. 既有 Team Observer 回填

回填只选择同时满足以下条件的 Team：

- Team 为 `ACTIVE`，Owner Member 与 Default Workspace 已完整初始化；
- Owner Member 为 `ACTIVE`；
- Owner Principal 为可行动的 `ACTIVE USER`；
- Default Workspace 为该 Team 的 `ACTIVE TEAM` Workspace。

每个符合条件的 Organization 创建一个不可变 Organization-scoped `team-observer@1`。Capability Hash、Policy Hash、Structured Output Schema Hash 和按 Organization 动态计算的 Content Hash 使用与 Java 领域对象相同的长度前缀 SHA-256 规范；测试直接与 `TeamObserverTemplate.create(...)` 比较。

每个符合条件的 Team 使用以下 Java 同源命名空间生成 UUID v3：

```text
io.crewscope/default-team-observer/principal/{teamId}
io.crewscope/default-team-observer/profile/{teamId}
```

Principal 固定为 `TEAM_AGENT + TEAM visibility + DISABLED`，Owner 为 Team Owner USER。Profile 固定为 `TEAM + TEAM ownership + TEAM_COORDINATOR + team-observer@1 + DISABLED`，绑定 Default Workspace，且没有 Owner Member。部分唯一索引保证每 Team 最多一个内置 Observer，延迟约束保证 Principal/Profile 生命周期同步，模板和 Profile 历史禁止删除。

迁移不创建 ModelConnection、AgentConfigurationVersion 或 Model Binding。管理员仍需显式配置 TEAM 模型、通过 Preflight 并同步启用 Principal/Profile。

## 4. 兼容、冲突与回滚

空库、非默认 Current Schema 和 V27→V28 均使用同一迁移路径。Flyway 第二次执行为零迁移。未完整初始化、非 ACTIVE、Owner 不可行动或默认 Workspace 不可用的旧 Team 保持原状，由后续显式初始化流程处理。

`team-observer@1` 是 Organization 级全局保留坐标。V28 在计算可回填 Team 之前扫描所有 Organization 的既有坐标；某个 Organization 当前没有完整 Team，不得跳过模板冲突检查。如果既有 `team-observer@1`、候选 Team 的确定性 Principal ID 或 Profile ID 与内置契约冲突，V28 失败并回滚整个事务，不覆盖或猜测既有数据。`pgcrypto` 作为 PostgreSQL 官方扩展提供 SHA-256；部署数据库用户需要具备首次安装该扩展的权限，后续迁移只复用已安装扩展。

## 5. 验证

专项迁移门禁：

```bash
./mvnw -pl crewscope-infrastructure -am \
  -Dtest=V28LarkMappingTeamObserverMigrationIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：5 个测试通过，0 Failure，0 Error，0 Skip。覆盖空库、非默认 Schema、V27→V28、重复执行、Java/SQL 稳定 ID 与 Hash 一致、完整 Team 回填、部分 Team 跳过、Model/Configuration 零新增、Mapping 双唯一、跨 Scope 外键、Proof/Mapping 历史保护、终态替换、通知 Mapping 兼容外键，以及无候选 Team 组织的保留模板冲突时整体事务回滚。

关联回归使用 V28、V27、Flyway 基线、Lark Mapping 领域、Team Observer 领域与服务测试，结果为 39 个测试通过，0 Failure，0 Error，0 Skip。索引兼容修复后，V28 与 M2 Binding Resolver 索引契约联合验证为 5 个测试通过。

Infrastructure Reactor 全量门禁：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am test
```

结果：Domain 569 个、Application 471 个、Infrastructure 586 个，共 1,626 个测试通过，0 Failure，0 Error，0 Skip。
