# M6-D05 Team Observer 领域与启用契约

> 任务：`M6-D05`<br>
> 日期：2026-08-25<br>
> 状态：完成<br>
> 前置契约：M5-D01 至 M5-D05

## 1. 交付目标

M6-D05 建立每个 Team 唯一的内置只读 Team Observer：

- Organization 发布的固定 `team-observer@1` AgentTemplate；
- 确定性 Team Agent Principal 和 TEAM-owned AgentProfile；
- 默认 `DISABLED` 且不生成 ModelConnection/AgentConfiguration；
- 当前 TEAM 配置、管理权限与模型 Preflight 通过后的显式启用；
- 五类只读 Tool 和对应投影数据范围；
- 成员范围化 `TeamSummaryRequest/Result` 与固定五段结构。

本任务定义领域、Port 和应用编排契约。V28 迁移由 M6-D09 实现，AgentScope Template Registry、Model Factory、Tool 与 Structured Output 运行时由 M6-I07 实现，对话与摘要 API 由 M6-A05 实现。

## 2. `team-observer@1` 固定模板

模板坐标为：

```text
Template             team-observer@1
Publisher Scope      ORGANIZATION
Runtime Role         TEAM_COORDINATOR
Ownership            TEAM
Execution Scope      TEAM
Declared Capability  team.summary.read
Model Capabilities   model.tool-calling + model.structured-output
Approved Skills      empty
Member Slots         empty
Administrator Slots  MODEL_BINDING + BUDGET
```

固定 Tool 集合为：

```text
team.activity.read
team.inbox.summary.read
workitem.summary.read
task.summary.read
artifact.summary.read
```

模板不含写 Tool、Provider 动作 Tool、任意 Skill 和成员补充 Prompt 入口。Structured Output 固定包含 `progress`、`blockers`、`reviewBacklog`、`pendingConfirmations`、`anomalies` 五个数组。任意 Prompt、Tool、Skill、Schema、Ownership 或 Execution Scope 扩展都会使内置模板验证失败。

## 3. 默认 Team Agent 身份

`TeamObserverInitialization` 使用 Team ID 分别派生 Principal ID 和 AgentProfile ID：

```text
io.crewscope/default-team-observer/principal/{teamId}
io.crewscope/default-team-observer/profile/{teamId}
```

初始化要求 ACTIVE Team、当前 Team Owner USER、默认 ACTIVE Team Workspace 和精确内置模板。创建结果为：

```text
Principal Type       TEAM_AGENT
Principal Visibility TEAM
Agent Ownership      TEAM
Runtime Role         TEAM_COORDINATOR
Principal Status     DISABLED
Profile Status       DISABLED
Model Configuration  absent
```

`DefaultTeamObserverRepository.initializeIfAbsent` 以 Organization/Team 串行化并发，Principal 和 Profile 同时提交或同时回滚。重试、并发初始化和迁移重放收敛为同一对 ID。持久化重建会重新校验 Principal/Profile 状态、Scope、Ownership、Template 和 Workspace 坐标。

通用 Agent 创建和生命周期入口不处理内置 Team Observer，防止人工创建第二个 Profile 或绕过启用 Preflight。

## 4. 配置与启用门禁

`DISABLED` AgentProfile 可以接受新的追加式 AgentConfiguration，`ARCHIVED` Profile 仍保持不可配置。Team Observer 的当前配置必须满足：

1. Configuration 绑定当前 Observer Profile、TEAM Ownership 和 `team-observer@1`；
2. PERSONAL Model Binding 为空；
3. TEAM Model Binding 存在，可使用精确 DIRECT Binding 或 `INHERIT_TEAM_DEFAULT`；
4. 当前用户具有 Team `AGENT_MANAGE`；
5. `TeamObserverModelPreflight` 解析当前 TEAM/ORGANIZATION ModelConnection、Catalog、Policy 与模型能力并验证可用；
6. Principal 和 Profile 使用强版本在同一 Repository 操作中同步转为 `ACTIVE`。

配置缺失、PERSONAL Binding、USER ModelConnection、Preflight 失败、权限缺失、状态不一致和版本冲突均保持 Observer `DISABLED`。

## 5. TeamSummary 披露边界

`TeamSummaryRequest` 绑定 Organization、Team、当前 ACTIVE TeamMember 和每段 1 至 50 条的上限。每次读取投影前重新校验成员资格。

`TeamSummaryEntry` 显式保存 Organization、Team、可见 Member、Section、DataScope、有界摘要和内部证据路径。摘要和路径都拒绝 Unicode 格式控制字符，避免双向文本与零宽字符造成界面欺骗。DataScope 只能来自：

```text
TEAM_ACTIVITY
TEAM_INBOX_SUMMARY
WORK_ITEM_SUMMARY
TASK_SUMMARY
ARTIFACT_SUMMARY
```

每个 DataScope 只能贡献到批准的摘要 Section。Evidence Path 只接受无 Scheme、Query、Fragment、反斜杠、明文/百分号编码路径穿越和空白/控制字符的内部路径，打开证据时继续进行服务端授权。

`TeamSummaryResult` 要求 ACTIVE 内置 Observer，并对五个 Section 逐条检查 Organization、Team、Member 可见性、DataScope 和数量上限。跨 Team、跨成员、外部 URL、不允许的 DataScope 和超限输出全部失败关闭。

## 6. 验证

专项测试：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-domain,crewscope-application -am \
  -Dtest=TeamObserverDomainM6D05Test,DefaultTeamObserverServiceM6D05Test \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：12 个测试通过，0 Failure，0 Error，0 Skip。

通用 Agent 管理联合回归额外覆盖内置 Team Observer 不进入可实例化模板目录，并且不能通过通用创建入口生成第二个 Profile。

覆盖：

- `team-observer@1` 固定 Ownership、Execution Scope、Tool、Skill、Schema 和配置槽；
- 每 Team 稳定 Principal/Profile ID、默认禁用和迁移重建；
- 篡改模板、错误 Workspace、跨 Team Repository 结果和第二实例拒绝；
- `DISABLED` Profile 配置、缺失配置拒绝启用、TEAM Binding 和 Preflight；
- Principal/Profile 同步生命周期与失败 Preflight 零写入；
- 通用模板目录和 Agent 创建入口防绕过；
- ACTIVE 成员、固定五段摘要、DataScope、跨成员拒绝和内部证据路径。

## 7. M7 新 Team 运行时补建回归

M7 开放注册会在 V28 执行后持续创建 Organization 和 Team，因此 Observer 不能只依赖
历史迁移回填。运行时使用同一个 `TeamObserverProvisioningService` 完成三条路径：

1. Team 创建交易内持久化内置 Template 和确定性禁用 Principal/Profile；
2. 应用就绪后扫描存量 ACTIVE Team 并幂等补建；
3. 每次摘要执行前再次补建，覆盖启动时模型暂不可用和并发首次调用。

首次配置存在一个独立的激活 Preflight 边界：仅对精确内置 `team-observer@1` 使用待提交的
ACTIVE Profile 快照验证模型，持久 Profile 和 Principal 在 Preflight 成功前保持 `DISABLED`。
普通禁用 Agent 继续使用原有 ACTIVE 执行门禁。已有 Configuration 不会被自动覆盖；没有安全
可选的 TEAM/ORGANIZATION 模型时 Observer 保持禁用，应用启动和用户注册不受阻断。

回归命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-application -am \
  -Dtest=TeamCreationServiceTest,TeamObserverProvisioningServiceTest,\
AgentConfigurationApplicationServiceTeamObserverTest,DefaultTeamObserverServiceM6D05Test,\
DefaultAgentTemplateCatalogInitializerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：17 个测试通过，0 Failure，0 Error，0 Skip。当前本地 PostgreSQL 运行事实同时验证
Observer Principal/Profile 均为 `ACTIVE`、Configuration Revision 为 `1`，TEAM DIRECT
Binding 指向健康的 `deepseek/deepseek-v4-flash`，未读取或记录 Credential。
