# M5-S02 Agent 所有权与配置升级验证记录

> 验证对象：M2–M4 AgentProfile、Principal、Conversation/Task RuntimeSession、PolicySnapshot<br>
> CrewScope 模块：`crewscope-domain`、`crewscope-application`<br>
> 验证日期：2026-08-22

## 1. 验证目标

1. 证明 Agent 所有权、运行角色、模板和执行范围可以从旧身份枚举中正交拆分；
2. 冻结默认 Personal、个人 Coding、个人 Reviewer 和团队 Coding 四类 Agent 实例；
3. 冻结 PERSONAL/TEAM 双模型绑定、Team 默认继承和 USER Connection 隔离规则；
4. 证明既有 Principal/Profile ID、Conversation/Task Session、AgentScope Key、StateReference 和 PolicySnapshot 证据可以无损读取；
5. 为 M5-D01、M5-D04、M5-D05 与 V20 迁移提供确定性契约。

本 Spike 使用测试内升级投影和配置解析形状验证契约，没有提前增加 M5 生产领域类型、Repository 或迁移。

## 2. 现有模型结论

M2–M4 已有：

```text
PrincipalType
  USER / PERSONAL_AGENT / TEAM_AGENT / SPECIALIST_AGENT

AgentProfileType
  PERSONAL / TEAM / SPECIALIST
```

这些类型已经进入权限、事件、Session、AgentScope 状态和持久化协议，继续承担兼容身份。M5 在旁边增加业务维度：

```text
PrincipalType / AgentProfileType
  + AgentOwnershipType
  + AgentRuntimeRole
  + AgentTemplateVersion
  + AgentConfigurationVersion
```

AgentTemplate 表达能力，Ownership 表达生命周期责任，RuntimeRole 表达基础运行方式，ExecutionScope 表达一次执行的责任范围。四者互不推断。

## 3. V20 确定性回填

| 既有 Profile 事实 | Ownership | RuntimeRole | TemplateVersion |
|---|---|---|---|
| `PERSONAL` | `USER` | `PERSONAL_ASSISTANT` | `personal-assistant@1` |
| `TEAM` | `TEAM` | `TEAM_COORDINATOR` | `team-coordinator@1` |
| `SPECIALIST + owner_member_id` | `USER` | `SPECIALIST` | `coding@1` |
| `SPECIALIST + no owner_member_id` | `TEAM` | `SPECIALIST` | `coding@1` |

M2–M4 没有持久化 Reviewer Template。显示名称为 `Reviewer Agent` 的旧 Specialist 仍映射为 `coding@1`，禁止从名称、Prompt 或输出推断模板。Reviewer 从 M5 开始通过创建命令显式引用 `reviewer@1`。

回填保留 Principal ID、AgentProfile ID、旧 Principal Owner、OwnerMember、默认标记和 Profile Type。旧数据不推断 Organization Ownership，也不合成无法证明的 Connection、凭证或授权。

## 4. 实例形状

自动化样本建立四个独立实例：

| 实例 | Ownership | RuntimeRole | Template | 默认 Agent |
|---|---|---|---|---|
| 默认 Personal | USER | PERSONAL_ASSISTANT | `personal-assistant@1` | 是 |
| 个人 Coding | USER | SPECIALIST | `coding@1` | 否 |
| 个人 Reviewer | USER | SPECIALIST | `reviewer@1` | 否 |
| 团队 Coding | TEAM | SPECIALIST | `coding@1` | 否 |

四个实例具有独立 Principal 与 AgentProfile。Coding Template 可以同时创建 USER-owned 和 TEAM-owned 实例；Coding/Reviewer 由模板区分，不扩展 PrincipalType 或 AgentProfileType。

## 5. 模型绑定矩阵

| Agent | PERSONAL 执行 | TEAM 执行 |
|---|---|---|
| USER-owned Personal Assistant | Owner USER、授权 TEAM/ORGANIZATION | `ORCHESTRATION_ONLY`，由任务策略选择 Specialist |
| USER-owned Specialist | Owner USER、授权 TEAM/ORGANIZATION | 精确 TEAM/ORGANIZATION 或 `INHERIT_TEAM_DEFAULT` |
| TEAM-owned Agent | TEAM/ORGANIZATION | TEAM/ORGANIZATION 或 `INHERIT_TEAM_DEFAULT` |
| ORGANIZATION-owned Agent | ORGANIZATION | ORGANIZATION |

解析约束：

- USER Connection 必须属于 Agent 的 USER Owner；
- TEAM Execution 禁止使用 USER Connection；
- TEAM-owned/ORGANIZATION-owned Agent 禁止使用 USER Connection；
- PERSONAL Binding 不能声明 `INHERIT_TEAM_DEFAULT`；
- TEAM Binding 不回退到 PERSONAL Binding；
- Team 默认缺失、歧义、越权、撤销或能力不符时失败关闭；
- 默认 Personal Agent 在 TEAM Scope 负责对话和编排，不直接解析执行模型。

测试矩阵覆盖 USER、TEAM、ORGANIZATION 三类 Connection、直接绑定、Team 默认继承、编排态、Owner 不匹配与失败关闭路径。

## 6. 历史运行与 PolicySnapshot

测试使用现有生产领域 API 创建并复原：

- Personal Conversation 与 AgentRuntimeSession；
- Task、TaskExecution 与 TaskAgentRuntimeSession；
- PolicySnapshot 与 ProviderBinding 集合。

复原后原 Session ID、AgentProfile ID、AgentScope Key、StateReference、ProviderBinding 与 Snapshot Hash 保持一致。

V20 使用两种 PolicySnapshot Schema：

| Schema | 使用范围 | M5 坐标 | Hash 规则 |
|---|---|---|---|
| v1 | M2–M4 既有快照 | Template/Configuration/ExecutionScope 为空 | 原 Hash 原样保留 |
| v2 | M5 新建快照 | 精确且完整 | 全部 M5 坐标进入规范 Hash |

V20 禁止为旧快照补字段后重算 Hash。PlanVersion、TaskToken、Workspace、Artifact、Checkpoint 和评测证据继续引用原 v1 Hash。新任务使用 v2；未知 Schema 版本读取失败关闭。

## 7. 自动化验证

测试类：

```text
crewscope-domain/src/test/java/io/crewscope/domain/task/
  AgentOwnershipM5S02CompatibilityTest.java
```

覆盖四组场景：

1. 四类正交 Agent 实例与稳定旧身份；
2. V20 确定性回填以及禁止按显示名称推断 Reviewer；
3. PERSONAL/TEAM 绑定完整矩阵和 USER Connection 隔离；
4. Conversation/Task Session、运行键、StateReference 和 PolicySnapshot v1/v2 兼容。

专项命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-domain \
  -Dtest=AgentOwnershipM5S02CompatibilityTest test
```

结果：

```text
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

M5-S01 与 M5-S02 联合专项：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-server -am \
  -Dtest=AgentScopeDynamicModelM5S01IntegrationTest,AgentOwnershipM5S02CompatibilityTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：M5-S01 真实多连接集成测试 `2 / 2`、M5-S02 兼容测试 `4 / 4` 通过。

全仓回归：

```bash
./mvnw --batch-mode --no-transfer-progress test
```

```text
Tests run: 1524, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

回归覆盖 Domain、Application、AgentScope Adapter、Integration、Infrastructure 和 Server 全部模块，并实际运行 PostgreSQL、Redis、Flyway 与 Docker Sandbox 集成测试。文档链接门禁检查 192 份 Markdown 文件，`git diff --check` 同步通过。

## 8. 后续实现边界

- M5-D01 落地 Ownership、RuntimeRole、TemplateDefinition/Version 与 AgentProfile 扩展；
- M5-D04 落地 AgentConfigurationVersion、PERSONAL/TEAM Binding 与 Team 默认；
- M5-D05 落地执行范围判定、配置解析和 PolicySnapshot v2；
- M5-D10 落地 V20 确定性回填、Schema 版本与空库/升级迁移测试。

本 Spike 不创建生产表、不建立真实 Connection、不迁移凭证，也不改变正在运行的 M2–M4 Session。

## 9. 结论

M5-S02 验证通过。现有身份类型可以保持兼容，同时增加正交的 Ownership、RuntimeRole、TemplateVersion 和 ConfigurationVersion。个人 Specialist 可以为个人任务使用 Owner Connection，为团队任务使用独立 TEAM Binding；旧 Agent、Session 与 PolicySnapshot 证据无需改写即可继续读取。V20 的确定性映射、Reviewer 新建边界、绑定失败关闭规则和 PolicySnapshot Schema 版本规则已冻结。
