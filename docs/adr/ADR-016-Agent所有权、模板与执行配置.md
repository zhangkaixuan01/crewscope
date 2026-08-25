# ADR-016：Agent 所有权、模板与执行配置

> 状态：ACCEPTED<br>
> 日期：2026-08-22<br>
> 影响里程碑：M5–M6<br>
> 关联决策：[ADR-004](ADR-004-CredentialStore与动作凭证.md)、[ADR-006](ADR-006-ProviderBinding解析与授权.md)、[ADR-011](ADR-011-AgentScopeNativeRuntime实例与恢复协议.md)、[ADR-015](ADR-015-Agent模型目录、连接与配置解析.md)

## 背景

CrewScope 已有 `PERSONAL/TEAM/SPECIALIST` AgentProfile 和 `PERSONAL_AGENT/TEAM_AGENT/SPECIALIST_AGENT` Principal。该分类同时表达所有者、交互角色和执行能力，无法准确表达以下产品事实：

- 成员拥有一个默认对话式 Personal Agent，也可以拥有多个 Coding、Reviewer 等执行 Agent；
- 同一个 Coding Template 可以创建为个人 Agent 或团队 Agent；
- 个人 Agent 执行个人任务时可以使用 USER ModelConnection，执行团队任务时需要服从团队稳定性、数据和成本策略；
- Coding、Reviewer 是可扩展的专业模板，不应固化为不断增长的核心枚举；
- 用户可以配置 Agent，但不能通过自定义 Prompt 或 Tool 绕过平台安全边界。

## 决策

### 1. 分离四个维度

| 维度 | 含义 | 稳定值或示例 |
|---|---|---|
| `AgentOwnershipType` | 谁管理 Agent、承担配置和生命周期责任 | `USER/TEAM/ORGANIZATION` |
| `AgentRuntimeRole` | Agent 在平台中的基础运行方式 | `PERSONAL_ASSISTANT/TEAM_COORDINATOR/SPECIALIST` |
| `AgentTemplateDefinition` | Agent 能做什么以及不可突破的运行边界 | `coding/reviewer/research/documentation/testing/operations` |
| `AgentExecutionScope` | 本次执行属于个人事实还是团队责任事实 | `PERSONAL/TEAM` |

`PrincipalType` 继续表示运行身份类别，兼容现有授权、Audit、Session 和事件协议。`AgentProfile` 表示一个稳定 Agent 实例，引用精确 AgentTemplateVersion，并保存所有权、展示身份和生命周期。模板能力不写入 `AgentProfileType` 枚举。

### 2. Personal Agent 与个人执行 Agent

- 平台继续为每个有效 TeamMember 创建一个默认 Personal Agent，负责对话、目标澄清、TaskIntent 和任务编排；
- 默认 Personal Agent 在成员和 Team 范围内保持唯一，不作为多个专业 Agent 的容器；
- 成员可以创建多个 USER-owned Specialist Agent，例如 Java Coding、Frontend Coding、Reviewer、Testing、Documentation 和 Research Agent；
- 每个 Agent 拥有独立 Principal、AgentProfile、AgentConfigurationVersion、AgentRuntimeSession、Memory Scope、审计和成本归属；
- 团队管理员可以从相同模板创建 TEAM-owned Agent，组织管理员可以发布组织模板、默认配置和组织级 Agent。

### 3. 模板与实例

`AgentTemplateDefinition` 使用稳定 `templateKey` 和追加 `templateVersion`，至少固定：

- Runtime Role、能力声明和所需模型能力；
- System Prompt 基线和 Structured Output Schema；
- Tool、Skill、Memory、Sandbox 和 Provider 能力策略；
- 可用所有权类型、执行范围、数据分类和预算上限；
- 用户可配置字段、管理员可配置字段和平台固定字段；
- 状态、发布者 Scope、内容 Hash 和升级兼容规则。

M5 首批运行模板为 `coding` 与 `reviewer`。领域、迁移、API 和前端按开放模板目录实现，后续增加 Research、Documentation、Testing、Operations 等模板时不修改核心 Agent 枚举。模板停用只阻止新 Agent 和新配置，历史 TaskExecution 保留固定版本证据。

成员可以修改名称、描述、受控补充指令、批准的 Skill、知识范围、模型、ProviderBinding、预算和输出偏好。成员不能替换 System Prompt 安全基线、扩大 Tool/Sandbox 权限、修改 Structured Output Schema、提交任意 Adapter/Base URL/GenerateOptions JSON 或直接写入凭证引用。

### 4. 模型连接与执行范围

连接可用范围由以下交集决定：

```text
Agent 所有权允许范围
∩ AgentExecutionScope
∩ AgentTemplate 模型能力要求
∩ Organization Policy
∩ Team Policy
∩ 当前 Principal 的 Connection 使用权
```

| Agent 所有权与角色 | PERSONAL 执行 | TEAM 执行 |
|---|---|---|
| USER-owned Personal Agent | Owner USER、授权 TEAM/ORGANIZATION | 只负责对话和编排；执行 Agent 由任务策略解析 |
| USER-owned Specialist | Owner USER、授权 TEAM/ORGANIZATION | TEAM/ORGANIZATION，默认继承 Team 对该 Template 的配置 |
| TEAM-owned Agent | TEAM/ORGANIZATION | TEAM/ORGANIZATION |
| ORGANIZATION-owned Agent | ORGANIZATION | ORGANIZATION |

`AgentConfigurationVersion` 为每个 Agent 保存两个受控模型绑定：

- `PERSONAL` Binding 可以引用 Owner 的 USER Connection，也可以引用授权的 TEAM/ORGANIZATION Connection；
- `TEAM` Binding 使用精确 TEAM/ORGANIZATION Connection，或声明 `INHERIT_TEAM_DEFAULT`；
- 缺少适用于当前执行范围的绑定时失败关闭，不把 PERSONAL Binding 隐式用于 TEAM Task；
- Team Policy 可以进一步禁止个人 Agent 参与团队任务，或限制可用模板、模型、数据区域、预算和 ProviderBinding。

每个 Configuration Revision 从 1 连续追加并引用直接前一 Revision，固定 Profile/Ownership、Owner USER Principal、TemplateVersion/ContentHash、两类 Binding、模板验证后的 Prompt/Tool/Schema、批准 Skill、Memory/Budget Policy Reference、PolicyPack、SafeGenerateOptions、配置 Hash 和创建审计。Owner USER Principal 仅用于证明 USER Connection 属于 USER-owned Agent 的实际 Owner；TEAM/ORGANIZATION-owned Agent 不保存该坐标。

PERSONAL Binding 只能是直接主/Fallback；TEAM Binding 可以是直接主/Fallback或 `INHERIT_TEAM_DEFAULT`。默认 Personal Agent 的 TEAM Binding 固定为 `ORCHESTRATION_ONLY`，避免把对话编排模型当作团队执行模型。Fallback 与 Primary 分别验证，TEAM 或 ORGANIZATION Ownership、TEAM Execution 和所有默认值均禁止 USER Connection。

ModelDefault 按 Organization/Team Scope、TemplateVersion、ExecutionScope 和连续 Revision 只追加。Team 默认只允许同 Team/Organization Connection，Organization 默认只允许 Organization Connection；解析结果在 Conversation Session 或 Task PolicySnapshot 中固定，后续默认变更不影响已开始运行。

团队任务由服务端事实判断，包括 Team Workspace/WorkProject 中的共享 WorkItem、团队责任链、团队预算或团队 SLA。谁点击创建任务不改变执行范围。个人 Agent 被委托执行团队任务时保留 Agent 身份、Template、行为配置和责任关系，模型连接按 TEAM Binding 解析。

TaskExecution 创建时生成 `ResolvedAgentExecutionConfiguration`，并将 AgentProfile、TemplateVersion、AgentConfigurationVersion、ExecutionScope、Provider、Connection、Model ID/Revision、Prompt/Tool/Skill/Policy Hash、价格和预算固定进 PolicySnapshot。运行中不重新继承默认值。

### 5. Review 独立性

- Reviewer Agent 只生成 `ADVISORY` ReviewFinding，不能提交 Gate ReviewDecision；
- USER-owned Reviewer 可以为 Owner 执行自检，也可以协助其 Owner 评审他人的任务；
- 自检 Finding 明确标记为 `SELF_REVIEW`，不能满足职责分离或 Gate Reviewer 条件；
- Gate ReviewDecision 只能由 `ReviewerEligibilityPolicy` 判定合格的 TeamMember 提交；
- Reviewer Agent 的 Owner、运行 Principal、模型连接、ContextPackage 和 Finding 全部进入审计链。

### 6. 创建、升级与停用

- `GET /agent-templates` 只返回当前 Principal 可创建且策略允许的模板；
- 创建 Agent 时服务端解析所有权、Workspace、Principal、TemplateVersion 和默认模型绑定，客户端不能指定内部 PrincipalType；
- Template 升级和 Agent 配置修改都追加新版本，不覆盖历史版本；
- Agent 停用阻止新 Conversation/Task，运行中任务在安全点根据 SafetyEnforcementOverlay 暂停或失败关闭；
- USER Owner 离开 Team 后，其个人 Agent 不能继续领取团队任务；已有团队任务进入可审计的等待重新分配状态；
- 删除使用归档语义，历史 Conversation、Task、Review、Artifact、Usage 和 Audit 继续引用原 Agent 版本。

## 兼容与迁移

`PrincipalType` 与 `AgentProfileType` 继续作为兼容身份字段。V20 为 AgentProfile 增加 Ownership、RuntimeRole 和 TemplateVersion，为 AgentRuntimeSession 增加相同身份投影与成对的可选 Configuration Revision/Hash。迁移不重写 Principal、AgentProfile、Conversation、Task、Session、AgentScope Key、StateReference 或 Artifact 的稳定 ID。

V20 只使用既有类型与 `owner_member_id` 执行确定性回填：

| 既有 AgentProfile | `AgentOwnershipType` | `AgentRuntimeRole` | `AgentTemplateVersion` |
|---|---|---|---|
| `PERSONAL` | `USER` | `PERSONAL_ASSISTANT` | `personal-assistant@1` |
| `TEAM` | `TEAM` | `TEAM_COORDINATOR` | `team-coordinator@1` |
| `SPECIALIST` 且存在 `owner_member_id` | `USER` | `SPECIALIST` | `coding@1` |
| `SPECIALIST` 且不存在 `owner_member_id` | `TEAM` | `SPECIALIST` | `coding@1` |

M2–M4 只持久化了 Coding Specialist，没有持久化可区分的 Reviewer Template。V20 禁止根据 Principal 名称、Agent 显示名称、Prompt、历史输出或其他非权威文本推断 Reviewer；M5 新建 Reviewer Agent 时显式引用 `reviewer@1`。组织所有权从 M5 新建流程开始产生，旧数据不推断为 `ORGANIZATION`。

旧 Agent 没有可证明的 M5 Connection 与双执行绑定时，迁移不合成凭证、Connection 或授权。后续配置命令通过正常的目录、权限和 Preflight 创建 `AgentConfigurationVersion`；缺失绑定保持不可用并失败关闭。已有 M2–M4 Conversation、Task 与 RuntimeSession 继续依赖其已固定的兼容身份和历史运行事实读取，不因当前默认值产生漂移。

### PolicySnapshot Schema 兼容

V20 为 PolicySnapshot 增加显式 `schema_version` 和 `agent_execution_configuration`：

- 既有快照标记为 Schema v1，保留原始列值与 `snapshot_hash`，`agent_execution_configuration` 为空；
- 禁止用 M5 新字段重新计算、覆盖或“修复”Schema v1 Hash；依赖旧 Hash 的 PlanVersion、TaskToken、Workspace、Artifact、Checkpoint 和评测证据继续闭合；
- M5 新建快照使用 Schema v2，`agent_execution_configuration` 必须是 JSON Object，并包含精确 AgentProfile、TemplateVersion、AgentConfigurationVersion、ExecutionScope、Primary/Fallback 模型与连接、价格和策略 Hash；
- Schema v2 的 `snapshot_hash` 覆盖全部 M5 坐标，任一坐标变化都创建新快照并产生新 Hash；
- Repository 读取按 Schema 版本选择复原与 Hash 校验规则，未知 Schema 版本失败关闭。

### 滚动升级

V20 的 `project_legacy_agent_profile_v20` INSERT 前触发器只兼容仍按 V19 形状写入的旧节点。当 Ownership、RuntimeRole、Template Key 和 Template Version 四个核心坐标全部缺省时，触发器按 `type + owner_member_id` 权威事实执行与迁移回填相同的投影。任何部分坐标、显式伪造坐标或新旧事实冲突都由 V20 约束拒绝，不被兼容触发器改写。

## 实现约束

1. Agent 所有权、运行角色、模板、执行范围和模型连接分别建模，禁止用 Agent 名称或 Template Key 推断所有权。
2. AgentTemplateVersion、AgentConfigurationVersion 和 PolicySnapshot 都只追加；运行事实引用精确版本和内容 Hash。
3. USER Connection 只能由 Owner 的 PERSONAL 执行使用，TEAM Task 不得隐式继承 USER Connection。
4. 用户补充指令只能进入模板声明的扩展槽位，并作为经过 XML 元字符编码的独立不可信 Prompt 分区追加在平台基线之后；平台安全指令和 Tool Policy 始终具有更高优先级。
5. Agent 创建、配置、模板升级、模型 Preflight、停用、归档和团队任务委托生成 DomainEvent、CommandReceipt 与 AuditEvent。
6. 普通成员不能创建带任意 Tool、Shell、网络或 Credential Scope 的模板；自定义模板发布属于团队/组织管理员能力并经过策略校验。
7. Agent 可见目录、模型目录和 Provider 目录均由服务端返回权限与策略交集，前端不自行拼接可用项。
8. `INHERIT_TEAM_DEFAULT` 只在 TEAM Binding 中有效；默认缺失、歧义、越权或不可用时失败关闭，禁止回退到 PERSONAL Binding。
9. Schema v1 PolicySnapshot 的 Hash 是历史证据，V20 只追加兼容坐标；Schema v2 才把完整 M5 运行坐标纳入 Hash。

## 结果

- 每个成员保留一个默认对话式 Personal Agent，同时可以创建多个专业执行 Agent；
- Coding、Reviewer 等能力可以同时存在个人版和团队版；
- 个人 Agent 可以为个人任务使用 BYOK，为团队任务使用稳定的团队/组织连接；
- Agent 类型通过版本化模板扩展，核心身份、授权、Session 和审计协议保持稳定；
- 团队任务不会因为个人 Key 欠费、撤销或成员离开而静默改变模型或继续越权运行；
- Agent 自检、Agent Advisory Review 和成员 Gate Review 具有清晰的责任边界。

## 验证

1. 同一成员创建两个 Coding Agent 和一个 Reviewer Agent，各自具有独立配置、Session、Memory、Usage 与审计。
2. USER-owned Coding Agent 的 PERSONAL Task 使用 Owner USER Connection，TEAM Task 使用 TEAM Binding 并固定进 PolicySnapshot。
3. 缺少 TEAM Binding、Team 默认或当前授权时，个人 Agent 不能领取团队任务。
4. Team Policy 禁用某 Template、模型、区域或个人 Agent 参与后，模型调用前失败关闭。
5. Template 或 Agent 配置升级不改变已有 Conversation、TaskExecution、ReviewRequest 和成本快照。
6. 自有 Reviewer Agent 的 SELF_REVIEW Finding 不能形成 Gate Approval，其他合格成员仍需提交 ReviewDecision。
7. 用户补充指令不能启用模板未声明的 Tool、网络、Provider Scope 或 Structured Output 字段。
8. 现有 M2–M4 AgentProfile、RuntimeSession、TaskExecution 与 Coding 评测证据在 V20 升级后保持可读且关系闭合。

## 重新评估条件

- 平台允许第三方发布并运行自定义 Agent Runtime 或任意代码模板；
- 团队策略允许 USER Connection 作为团队耐久任务的账单与连续性主体；
- Agent 可以跨 Organization 拥有身份、Memory 或共享配置；
- AgentScope 提供原生多租户 Agent Template、Model Binding 和 PolicySnapshot 协议。
