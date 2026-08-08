# M1-D07：Team 与责任数据迁移

> 日期：2026-08-08<br>
> 状态：已完成<br>
> 模块：`crewscope-infrastructure`

## 目标

使用 `V6__team_work_and_responsibility.sql` 落地 M1 已完成的 Team、默认 Personal Agent、WorkProject、WorkItem、Comment、ResourceLink 和 ResponsibilityAssignment 领域事实。数据库负责 Scope 引用完整性、责任并发唯一性、乐观锁字段和可检索索引。

## 升级策略

V6 同时支持空库全量迁移和 V5 原地升级：

- V5 Team 无法可靠推断 Owner，`owner_member_id/default_workspace_id` 允许成对为空；
- 新 Team 在初始化事务内写入 Owner 与默认 Team Workspace，两列必须成对存在；
- Team 到 Owner、默认 Workspace 的循环引用使用 `DEFERRABLE INITIALLY DEFERRED` 外键，事务提交时完成最终校验；
- 现有 WorkProject 回填为 `ACTIVE`；
- 现有 WorkItem 的 `labels` 回填为空 JSON 数组，`due_at` 保持为空；
- 对 V5 已有枚举和来源字段的收紧约束使用 `NOT VALID`，新写入立即受约束，同时保留历史合法升级路径；
- 所有迁移只向前追加，不修改 V1 至 V5。

## 表与字段

### Team

`team` 增加：

```text
owner_member_id
default_workspace_id
```

完整 Scope 外键保证 Owner 是当前 Team 的 TeamMember，默认 Workspace 是当前 Team 的 Workspace。两列成对为空或成对存在。

### AgentProfile

`agent_profile` 保存：

```text
id
organization_id / team_id / workspace_id
agent_principal_id / owner_member_id
profile_type / default_profile / status
version
created_at / created_by_principal_id
updated_at / updated_by_principal_id
```

一个 Agent Principal 只对应一个 Profile。Personal Profile 必须绑定 TeamMember；默认 Profile 必须是 Personal Profile。部分唯一索引保证每个成员最多存在一个 `ACTIVE + PERSONAL + default_profile=true` Profile。

### WorkProject 与 WorkItem

`work_project` 增加 `status`，V5 数据回填为 `ACTIVE`，保留已有 `version` 与审计字段。

`work_item` 增加：

```text
labels JSONB
due_at TIMESTAMPTZ
```

Labels 必须是 JSON 数组且最多 20 项。WorkItem 的类型、状态、优先级和来源使用明确枚举，来源与外部引用保持成对语义。WorkItem 增加完整 Scope 唯一键，供 Comment、ResourceLink 和 Assignment 使用复合外键。

### Comment 与 ResourceLink

`work_item_comment` 保存完整 WorkItem Scope、作者、Markdown、来源、外部 ID 和审计字段。外部 Comment 必须携带外部 ID，同一 WorkItem 内的 Provider 外部 ID 唯一。

`work_item_resource_link` 保存完整 WorkItem Scope、资源类型、稳定引用、可选标签和审计字段。两张表都通过复合外键证明其 WorkItem Scope 一致。

### ResponsibilityAssignment

`responsibility_assignment` 保存：

```text
id
organization_id / team_id / workspace_id / project_id / work_item_id
role
actor_principal_id / actor_type / actor_member_id
status
assigned_by_principal_id / assigned_at / accepted_at
released_by_principal_id / released_at
version
created_at / created_by_principal_id
updated_at / updated_by_principal_id
```

数据库约束保证：

- USER Actor 必须携带匹配该 Principal 的 TeamMember，Agent Actor 不携带 TeamMember；
- Owner 只能是 USER；Executor 可以是 USER 或 Agent；Reviewer 可以是 USER 或 SPECIALIST_AGENT；
- ACTIVE Assignment 不携带释放信息，RELEASED Assignment 必须同时携带释放人和释放时间；
- 接受时间不早于分配时间，释放时间不早于接受时间；
- Version 非负，修改时间不早于创建时间；
- 每个 WorkItem 最多一个 ACTIVE Owner；
- 同一 WorkItem、Role、Actor 最多一个 ACTIVE Assignment。

## 并发与查询

部分唯一索引是责任并发写入的最终裁决点。D08 使用 WorkItem 行锁实现 `lockResponsibilityChain`，在同一事务内串行完成 Owner 替换、Executor/Reviewer 变更和 ReviewerEligibilityPolicy 检查，并把唯一索引与乐观锁冲突映射为稳定业务冲突。

索引覆盖：

- AgentProfile 的 Team、Owner、状态查询；
- WorkItem 的 Labels GIN 和 DueAt 查询；
- Comment 的 WorkItem 时间线与作者查询；
- ResourceLink 的 WorkItem 与资源反查；
- Assignment 的 Subject、Role、Actor 和状态查询。

## 验证

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure \
  -Dtest=V6TeamWorkResponsibilityMigrationIntegrationTest test

./mvnw --batch-mode --no-transfer-progress clean verify
node scripts/check-doc-links.mjs
git diff --check
```

验证覆盖空库迁移、V5→V6 数据保留、表/列/约束/索引结构、Team 延后外键、AgentProfile 唯一默认 Profile、WorkItem Scope、Comment/ResourceLink 来源规则、Assignment Actor 资格、释放状态、唯一 Active Owner 和唯一 Active Role/Actor。

M1-D07 新增 4 个 PostgreSQL 集成测试，全部通过，0 失败、0 错误、0 跳过。V5 专项迁移测试改为显式锁定 target 5，避免后续版本改变其阶段语义。

全仓回归：7 个 Maven 模块全部构建成功，后端共 300 个测试通过，0 失败、0 错误、0 跳过。

## 阶段边界

M1-D07 只实现数据库迁移与数据库约束测试。Entity、Mapper、Repository Adapter、WorkItem 责任链行锁和持久化异常映射由 M1-D08 完成；API、DomainEvent、AuditEvent 和 PolicyPack 持久化由后续任务完成。
