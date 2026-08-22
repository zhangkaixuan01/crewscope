# M5：Agent 模型、个人执行 Agent、Review 与 GitHub Draft PR 执行清单

> 对应总计划：[CrewScope 实施计划](../CrewScope-实施计划.md) M5<br>
> 前置条件：M4 Release Gate 通过，ADR-004、ADR-006、ADR-015、ADR-016、ADR-017、ADR-018、ADR-019 已接受<br>
> 目标周期：6–8 周，按纵向波次推进<br>
> 目标结果：成员可创建和配置个人执行 Agent，团队可治理模型与共享 Agent；Coding 结果经过独立 Reviewer Advisory、成员 Gate Review 和精确确认后，由 GitHubSourceCodeProvider 创建可审计 Draft PR<br>
> 当前进度：`M5-S01` 至 `M5-S05` 已完成，下一任务为 `M5-D01`（2026-08-22）

## 1. 出口结果与范围

M5 完成后具备：

- Model Registry、模型目录、版本化价格、USER/TEAM/ORGANIZATION ModelConnection 与 CredentialStore 集成；
- 默认 Personal Agent、USER-owned Specialist、TEAM-owned Agent 的独立配置和模型治理；
- 版本化 AgentTemplate 目录，首批交付 `coding` 与 `reviewer`，数据与 API 支持后续模板扩展；
- 成员创建多个个人 Coding/Reviewer Agent，管理员从相同模板创建团队 Agent；
- PERSONAL/TEAM 两类执行模型绑定、Team 默认继承、Model Preflight、Fallback 和 PolicySnapshot 固定；
- Reviewer Specialist、ContextPackage、ReviewRequest、ReviewFinding 与 TeamMember Gate ReviewDecision；
- GitHub Connection、Repository 资源选择、受管远端同步、Push Branch、Create Draft PR 与 Webhook 对账；
- ActionBundle、PlannedAction、Confirmation、ActionReceipt、UNKNOWN/Reconcile 与完整 Audit；
- `我的 Agent`、`模型与凭证`、任务委托、Review Workbench 和 Draft PR 确认结果前端；
- 模型、凭证、Review、外部动作的固定安全集、故障集、质量集和 M5 Release Gate。

M5 的普通成员只能从平台或组织/团队批准的 AgentTemplate 创建 Agent。成员不能发布任意 Runtime、System Prompt、安全策略、Tool、Shell、网络、MCP 或 Credential Scope。M5 不开放 Plugin 市场、任意自定义 Agent 代码、自动合并 PR、生产部署、GitHub Issue 双向同步、定时 Autopilot 或跨 Organization Agent。

## 2. 产品闭环

```text
Organization/Team Admin 配置模型目录、连接、允许列表和 Team 默认
  -> 成员在“我的 Agent”从 Coding/Reviewer Template 创建个人执行 Agent
  -> 成员分别配置 PERSONAL 与 TEAM 执行模型绑定并完成 Preflight
  -> 从 Conversation 或 WorkItem 选择 Agent 执行个人或团队 Coding Task
  -> TaskExecution 固定 Template/Agent/Configuration/Model/Connection/价格与策略
  -> Coding Specialist 交付 DiffArtifact 与 TestEvidence
  -> 平台生成绑定精确基线、Diff、测试和验收事实的 ReviewRequest
  -> Reviewer Specialist 读取最小 ContextPackage 并提交 ADVISORY Finding
  -> 合格 TeamMember 提交 APPROVED / CHANGES_REQUESTED / REJECTED Gate Decision
  -> APPROVED 后生成 Push Branch + Create Draft PR 的 ActionBundle
  -> Owner 审查精确参数与风险并确认
  -> Action Worker 使用动作级凭证 Push 并创建 GitHub Draft PR
  -> ActionReceipt、Webhook/Reconcile、WorkItem、Conversation、Timeline 与 Audit 收敛
```

Conversation Mode 和 Control Mode 操作同一 Model、Agent、Review 和 Action 事实。模型 Key、GitHub Token、AskPass、Credential Reference、内部 Endpoint 参数和 Provider 原始错误不进入 Agent 上下文、浏览器状态、日志、Artifact 或公开事件。

## 3. 核心决策

### 3.1 Agent 所有权与模板

- `AgentOwnershipType` 表达 `USER/TEAM/ORGANIZATION`，`AgentRuntimeRole` 表达 Personal Assistant、Team Coordinator 或 Specialist；
- `AgentTemplateDefinition` 使用稳定 Key 和追加 Version，固定 Runtime、Prompt 基线、Tool/Skill/Schema、模型能力与策略边界；
- 默认 Personal Agent 保持每成员唯一；成员可以创建多个 USER-owned Specialist；
- M5 首批生产模板为 `coding` 和 `reviewer`，新增模板不扩展核心 Agent 枚举；
- USER-owned Reviewer 的自检只产生 `SELF_REVIEW` Advisory，不满足 Gate Review 职责分离。

### 3.2 PERSONAL 与 TEAM 模型绑定

- PERSONAL Binding 可以使用 Owner USER Connection 或授权的 TEAM/ORGANIZATION Connection；
- TEAM Binding 使用精确 TEAM/ORGANIZATION Connection，或继承 Team 对当前 Template 的默认；
- 团队任务由 Workspace、WorkProject、责任、预算和 SLA 事实判定，不由发起人身份推断；
- USER-owned Agent 可以执行团队任务，但必须存在有效 TEAM Binding、Team 默认和当前成员责任授权；
- TaskExecution 固定完整 `ResolvedAgentExecutionConfiguration`，在途任务不跟随默认值或 Agent 当前配置漂移。

### 3.3 Review 与外部动作

- Reviewer Agent 只能给出 Advisory Finding，Gate Decision 由合格 TeamMember 提交；
- ReviewRequest 绑定 Baseline、Delivery Commit、DiffArtifact、TestEvidence、Acceptance 与 ContextPackage Hash；
- Diff 或证据变化使已有 ReviewRequest 和 Gate Decision 失效；
- Push 与 Create Draft PR 是两个有顺序依赖的 PlannedAction，一个 Confirmation 只覆盖精确 ActionBundle Digest；
- Agent、Controller 和浏览器不能直接持有 GitHub 写凭证，Action Worker 在执行窗口换取动作级凭证；
- 超时或回执丢失进入 `UNKNOWN/RECONCILING`，通过远端 Branch Head、PR Head SHA 和外部 Operation ID 对账。
- Dispatch 只在事务提交后可领取，Lease 接管使用递增 Fencing Token；每个动作只有一个逻辑 Receipt，Webhook、主动查询和人工证据合并到同一 ExternalResult。

## 4. 依赖顺序

```text
M5-S01 -> M5-D02..D05 -> M5-I01..I05 -> M5-A01..A04 -> M5-F01..F05
M5-S02 -> M5-D01,D04,D05 -> M5-I05 -> M5-A02..A04 -> M5-F02..F05
M5-S03 -> M5-D06,D07 -> M5-I06,I07 -> M5-A05 -> M5-F06
M5-S04 + S05 -> M5-D08,D09 -> M5-I08..I12 -> M5-A06..A08 -> M5-F07

M5-D01..D05 -> M5-D10 -> M5-I01
M5-D06..D09 -> M5-D11 -> M5-I07,I11
M5-A01..A04 -> M5-F01..F05
M5-A05 -> M5-F06
M5-A06..A08 -> M5-F07
M5-F02..F07 -> M5-F08

安全边界完成 -> M5-Q01
恢复与对账完成 -> M5-Q02
模型/Reviewer 质量基线完成 -> M5-Q03
全部能力 -> M5-Q04
```

## 5. Spike 与架构验证

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M5-S01` | SPIKE | M4-Q04, ADR-015 | agentscope/server | 已完成：对照 AgentScope Java 2.0.0 源码验证动态 Model 工厂、OpenAI-compatible/OpenAI Provider、Tool + Structured Output、Formatter、GenerateOptions、Retry/Fallback 和 Spring 多 Adapter 装配，冻结受信 Adapter SPI | [M5-S01 AgentScope 动态模型与多连接验证记录](../spikes/M5-S01-AgentScope动态模型与多连接验证记录.md)；2 个真实 Loopback HTTP 集成测试证明 DeepSeek/OpenAI 双 Connection、Tool、两类 Structured Output、Retry 和 Fallback 不串 Endpoint、Key、Model 或配置 |
| `M5-S02` | SPIKE | ADR-016 | domain/application | 已完成：用现有 M2–M4 AgentProfile、Principal、Session 和 PolicySnapshot 验证 Ownership、RuntimeRole、TemplateVersion、PERSONAL/TEAM Binding 与 V20 无损升级形状，冻结确定性回填与 PolicySnapshot v1/v2 规则 | [M5-S02 Agent 所有权与配置升级验证记录](../spikes/M5-S02-Agent所有权与配置升级验证记录.md)；4 个专项场景证明默认 Personal、个人 Coding/Reviewer、团队 Coding 正交，TEAM 执行不使用 USER Connection，原 ID、Session、StateReference 与 v1 Hash 保持可读 |
| `M5-S03` | SPIKE | M4-D07, M4-Q03, ADR-017 | agentscope/application | 已完成：冻结 Reviewer Specialist Prompt、最小 ContextPackage、ReviewFindingListV1、证据引用、严重级别、规范 Fingerprint、SELF_REVIEW 与 TeamMember Gate 边界 | [M5-S03 Reviewer 证据与 Gate 边界验证记录](../spikes/M5-S03-Reviewer证据与Gate边界验证记录.md)；5 个专项场景证明正确/缺陷/无关样本重复判定、真实 Diff/Test/Acceptance 证据闭合、重复 Finding 合并且 Agent Gate 字段与命令均被拒绝 |
| `M5-S04` | SPIKE | ADR-004, ADR-006, M4-I01 | infrastructure | 已完成：冻结 TEAM-owned GitHub App 与 USER-owned OAuth 身份、Repository Catalog、AskPass/HTTP 凭证注入、受管 Mirror、远端 Head/Lease Push 幂等和 Draft PR 查询对账协议 | [M5-S04 GitHub 连接与 Draft PR 验证记录](../spikes/M5-S04-GitHub连接与Draft-PR验证记录.md)；5 个 Loopback/真实 Git 场景证明凭证零披露与清理、Catalog/RateLimit、相同 Branch/Head 和响应丢失不重复 Push/PR、远端冲突/Non-fast-forward、安全错误与最小权限 |
| `M5-S05` | SPIKE | S04, ADR-007 | domain/infrastructure | 已完成：冻结 ActionBundle Digest、动作依赖、确认失效、事务提交后 Dispatch、Lease/Fencing、超时 UNKNOWN、Webhook/主动查询和人工对账协议 | [M5-S05 ActionBundle 与外部结果对账验证记录](../spikes/M5-S05-ActionBundle与外部结果对账验证记录.md)；6 个专项场景证明事务回滚零写入、Push/PR 不确定结果查询收敛、旧 Worker 拒绝、Webhook 乱序去重、人工终态不可逆且每个动作只有一个逻辑 Receipt |

## 6. 领域、迁移与持久化契约

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M5-D01` | TASK | S02 | domain/application | 实现 AgentOwnership、AgentRuntimeRole、AgentExecutionScope、AgentTemplateDefinition/Version、可配置槽位、能力与策略 Hash；扩展 AgentProfile 为模板实例并保持默认 Personal 唯一 | Ownership/Role/Template 正交、版本追加、Scope、模板停用、用户补充指令、Tool/Schema 不可扩大和历史兼容测试 |
| `M5-D02` | TASK | S01 | domain/application | 实现 ModelProviderDefinition、ModelCatalogEntry/Revision、ModelCapability、Region/DataPolicy、ModelPriceSchedule 与目录生命周期 | Provider/Adapter 分离、Revision、价格时间片无重叠、能力、区域、停用、Hash 与历史价格不可变测试 |
| `M5-D03` | TASK | D02, ADR-004 | domain/application | 实现 USER/TEAM/ORGANIZATION ModelConnection、Credential Subject、Billing Subject、Endpoint/Region、健康、验证、轮换、停用、撤销和乐观版本 | 所有权、跨 Scope、Key 单向输入、轮换稳定身份、撤销、健康并发和明文禁止测试 |
| `M5-D04` | TASK | D01..D03 | domain/application | 实现 AgentConfigurationVersion、PERSONAL/TEAM ModelBinding、主/Fallback、Team 默认继承、Prompt/Skill/Memory/预算引用和配置 Hash | 两类 Binding 隔离、USER Key 禁入 TEAM、Fallback 独立校验、追加 Revision、客户端字段白名单和 Hash 防篡改测试 |
| `M5-D05` | TASK | D04, M3-D04 | domain/application | 实现 ResolvedAgentExecutionConfiguration、模型可选交集、Model Preflight、Team/Organization 默认、PolicySnapshot 固定和 Safety Overlay 收紧 | 执行范围判定、所有权、能力、数据、区域、预算、配额、责任与连接权限交集；默认歧义和不可用均失败关闭 |
| `M5-D06` | TASK | S03, M4-D05..D07 | domain/application | 实现 ContextPackage、ReviewSubject、ReviewRequest、精确 Diff/Test/Acceptance/Template/Policy 引用、状态机、版本和失效规则 | 最小上下文、Hash、重复创建、Diff/证据变化失效、跨 Task/attempt/Scope 和陈旧 Review 拒绝测试 |
| `M5-D07` | TASK | D06 | domain/application | 实现 ReviewFinding、FindingLocation/Evidence、ReviewerMode、ReviewDecision、ReviewerEligibilityPolicy 和修改轮次 | 严格 Finding、重复合并、SELF_REVIEW、Agent Advisory、成员 Gate、职责分离、CHANGES_REQUESTED/REJECTED/APPROVED 测试 |
| `M5-D08` | TASK | S05, D07 | domain/application | 实现 ActionBundle、PlannedAction、ActionKind、ActionDependency、ActionDigest、风险、前置 Review/责任/Provider/Policy 事实和失效规则 | 参数或基线变化 Digest 改变；未批准 Review、旧 Diff、撤权 Binding 和过期策略不能生成或确认动作 |
| `M5-D09` | TASK | D08 | domain/application | 实现 Confirmation、ActionDispatch、ActionReceipt、ExternalResult、UNKNOWN/RECONCILING、幂等键、重试与人工终结状态机 | 一次确认只覆盖精确 Bundle；重复 Dispatch/Receipt、部分成功、超时、取消、补偿和终态不可逆测试 |
| `M5-D10` | TASK | D01..D05 | infrastructure | 新增 `V20__model_catalog_agent_template_and_configuration.sql`，落地模型目录、连接、模板、Agent Ownership、双执行绑定、配置版本、默认和升级回填 | 空库、V19→V20、非默认 search_path、既有 M2–M4 Agent/Profile/Session/Policy 数据升级、约束与索引测试 |
| `M5-D11` | TASK | D06..D09 | infrastructure | 新增 `V21__review_action_and_github.sql`，落地 ContextPackage、Review、ActionBundle、PlannedAction、Confirmation、Dispatch、Receipt、Reconcile 和 GitHub 扩展 | 空库、V20→V21、部分成功、外部唯一键、Append-only 决策/回执、复合 Scope 外键与查询索引测试 |

## 7. 基础设施与 AgentScope

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M5-I01` | TASK | D10 | infrastructure | 实现 Model Registry、Connection、Template、AgentProfile 扩展、Configuration、Default 和 Policy 查询的 PostgreSQL Adapter 与锁定写入 | 完整对象图往返、分页、N+1、并发 Revision、唯一默认、跨 Scope 与 Spring 独立装配测试 |
| `M5-I02` | TASK | D03, ADR-004 | infrastructure/server | 将 ModelConnection 接入 CredentialStore，提供创建、验证、轮换、撤销、健康探测和短生命周期 Provider Credential Handle | 明文零持久化/零返回/零日志；撤销并发、错误脱敏、句柄关闭、旧版本失效和 Audit 测试 |
| `M5-I03` | FEATURE | S01, D02..D05 | agentscope/server | 实现 AgentScopeModelFactory、Provider Adapter Registry、DeepSeek/OpenAI-compatible 与备用 Provider Adapter、受控 Formatter/GenerateOptions | 多租户并发不串线；Tool/Structured Output、Retry、Fallback、Usage、取消和 Provider 安全错误集成测试 |
| `M5-I04` | TASK | I01..I03 | application/server | 实现模型目录可选交集、配置解析、Preflight、短 TTL 健康缓存、Team 默认和 ResolvedModelSelection 到 PolicySnapshot 的装配 | 禁用、撤销、过期、能力不符、区域、预算、歧义和缓存失效在 AgentScope 前失败关闭 |
| `M5-I05` | FEATURE | D01,D04,D05,I03 | agentscope/application | 实现 TemplateRegistry、Personal/Team/Specialist Agent Factory 和按 TemplateVersion 创建受控 AgentScope 实例；复用 M4 Coding Runtime | 多个个人 Coding/Reviewer Agent 的 Principal/Profile/Session/State 隔离；模板边界不可被用户补充指令扩大 |
| `M5-I06` | FEATURE | S03,D06,D07,I03 | agentscope/application | 实现 ContextPackageBuilder、Reviewer Specialist、严格 ReviewFinding 输出、Evidence Resolver、Finding 去重和有界修复请求摘要 | 固定 Review 语料、真实 M4 Diff/Test Artifact、无证据结论拒绝、SELF_REVIEW 标记和恢复测试 |
| `M5-I07` | TASK | D11,I06 | infrastructure | 实现 ContextPackage、ReviewRequest/Finding/Decision Repository、查询投影、事件、Outbox、Audit 与 Diff 变化失效监听 | 并发 Review、重复 Finding、旧 Decision、事件发布失败、投影重建、跨 Scope 和历史查询测试 |
| `M5-I08` | FEATURE | S04,D11,ADR-006 | infrastructure | 实现 GitHub Provider Adapter、Connection Grant、Repository Catalog/Binding Preflight、GitHub App/OAuth 身份与权限校验 | 安装范围、仓库权限、默认分支、Fork/Archived、组织策略、限流和敏感响应脱敏测试 |
| `M5-I09` | FEATURE | I08, M4-I01..I03 | infrastructure | 实现受管 GitHub Mirror/Remote、动作级 AskPass、基线/远端 Head 复验、Push Branch 和同 Head 幂等 | 凭证不进入仓库配置/进程列表/日志；Non-fast-forward、保护分支、超时与重复 Push 测试 |
| `M5-I10` | FEATURE | I08,I09 | infrastructure | 实现 Create Draft PR、已有 PR 发现、Head/Base/Commit/标题正文校验、Webhook 验签去重和状态对账 | 响应丢失、重复请求、Webhook 乱序、远端 Head 漂移、关闭/重开和唯一 Draft PR 测试 |
| `M5-I11` | FEATURE | D08,D09,I09,I10 | infrastructure/application | 实现 Outbox 后 Action Worker、依赖调度、Credential Handle、Dispatch/Receipt 事务边界和 Push→PR 两步执行 | 事务提交前零外部调用；崩溃点、重复领取、旧 Lease、部分成功和并发 Worker 不重复外部动作 |
| `M5-I12` | TASK | I11 | infrastructure/server | 实现 UNKNOWN Reconcile、Webhook/主动查询合并、启动对账、人工队列、模型/Review/Action Trace、指标和低基数健康摘要 | 冷启动、限流、GitHub 不可用、Receipt 丢失、对账超时、人工终结和 Runtime Fleet 诊断测试 |

## 8. 应用服务与 API

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M5-A01` | FEATURE | I01..I04 | application/server | 提供 Model Provider/Catalog 查询和 USER/TEAM/ORGANIZATION Connection 创建、列表、验证、轮换、停用、撤销 API | 目录交集、管理员权限、Owner 权限、Key 单向输入、幂等、ETag、Receipt、错误信封和路径/凭证不披露测试 |
| `M5-A02` | FEATURE | D01,I01,I05 | application/server | 提供 AgentTemplate Catalog、个人/团队 Agent 创建、列表、详情、启停、归档和配置历史 API | 默认 Personal 不可重复、多个 Specialist、模板策略、Owner/管理员权限、Principal 原子创建和历史引用测试 |
| `M5-A03` | FEATURE | A01,A02,I04 | application/server | 提供 Agent PERSONAL/TEAM Binding 配置、主/Fallback、受控偏好、Model Preflight 和 Conversation 安全点刷新 API | USER Key/TEAM 隔离、继承预览、生效范围、配置 Revision、活动调用、Interrupt、幂等和并发测试 |
| `M5-A04` | FEATURE | D05,A02,A03,M3-A01 | application/server | 扩展 Task 委托，解析 Personal/Team ExecutionScope、选择个人/团队 Agent、责任资格和最终 PolicySnapshot；Retry 支持显式换配置 | 个人/团队任务判定、Agent 可用性、成员离队、TEAM Binding、默认歧义、在途固定和 Retry 审计测试 |
| `M5-A05` | FEATURE | I06,I07,M4-A04..A06 | application/server | 提供 ReviewRequest 创建/列表/详情、Reviewer 执行、Finding、Gate Decision、修改请求和重新 Review API | Context 授权、Agent/成员边界、Reviewer Eligibility、SELF_REVIEW、Diff 失效、ETag、幂等和 Artifact 关系测试 |
| `M5-A06` | FEATURE | I08 | application/server | 提供 GitHub Connection、Repository Catalog、Binding/Remote Preflight、Webhook 状态和授权健康 API | OAuth/App Scope、Team 管理权限、资源过滤、跨仓库、撤销、限流和敏感字段白名单测试 |
| `M5-A07` | FEATURE | D08,D09,I11 | application/server | 提供 ActionBundle 预览、Confirmation、取消、Receipt、外部结果和 UNKNOWN Reconcile API；强制 Owner/责任/Review 当前事实 | 精确 Digest、风险展示、一次确认、旧页面、重复确认、部分成功、禁止直接 Dispatch 和强 ETag 测试 |
| `M5-A08` | TASK | I12,A05..A07 | application/server | 扩展 Task Timeline、Conversation 卡片、Runtime Fleet、Actuator 和平台运维 API，关联 Agent 配置、Review、Action 与 GitHub 结果 | 成员安全摘要、管理员诊断、低基数指标、Trace/Audit、游标、持续授权和敏感内部状态不披露测试 |

## 9. 前端工作台

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M5-F01` | TASK | A01..A03 | web | 建立 Model/Connection/AgentTemplate/AgentProfile/Configuration Gateway、公开 DTO、Store、缓存、错误与设置路由 | DTO 白名单、Scope 切换、Cursor、ETag、旧请求隔离、凭证不缓存和配置版本测试 |
| `M5-F02` | FEATURE | F01,A02 | web | 交付“我的 Agent”列表，区分默认 Personal、个人 Specialist 和团队 Agent，展示 Template、状态、模型绑定、任务与成本摘要 | Empty/Loading/Error/Forbidden、多 Agent、禁用/归档、桌面/390×844、键盘、Axe 和深链接测试 |
| `M5-F03` | FEATURE | F01,A02,A03 | web | 交付 Agent 创建向导和详情设置：选择批准 Template、名称、补充指令、Skill/知识范围、PERSONAL/TEAM Binding、主/Fallback 和 Preflight | Coding/Reviewer、无可用模板、USER BYOK、继承团队默认、能力不符、未保存 Key、版本生效说明和同键重试测试 |
| `M5-F04` | FEATURE | F01,A01 | web | 交付“模型与凭证”管理页，支持目录、Connection 创建/验证/轮换/停用、Team 默认、允许列表、区域、价格、预算和健康 | 普通成员/管理员差异、Key 单向输入、轮换清空、撤销确认、冲突刷新、健康失败和审计入口测试 |
| `M5-F05` | FEATURE | F01,A04,M4-F03 | web | 扩展 Conversation/WorkItem Task 委托，选择个人/团队 Agent，展示 PERSONAL/TEAM 范围、实际模型来源、成本主体和 PolicySnapshot 预检 | 团队任务 USER Key 禁用、无 TEAM Binding、成员离队、继承默认、Retry 换配置、草稿恢复和双入口测试 |
| `M5-F06` | FEATURE | A05,M4-F05..F07 | web | 交付 Review Workbench：Context 摘要、Diff/Test/Acceptance、Finding 文件定位、SELF_REVIEW、Reviewer 执行、Gate Decision 和修改轮次 | 旧 Diff 失效、Reviewer Eligibility、Advisory/Gate 区分、键盘审阅、桌面/窄屏、Axe 和视觉测试 |
| `M5-F07` | FEATURE | A06,A07,F06 | web | 交付 GitHub Connection/Repository 选择、ActionBundle 风险与参数审查、精确确认、Push/PR 分步状态、UNKNOWN/Reconcile 和 Draft PR 结果 | 未批准 Review、Digest 变化、重复确认、Push 成功 PR 失败、离线、Webhook 更新、外链安全和同键重试测试 |
| `M5-F08` | HARDENING | F02..F07 | web | 收口模型、Agent、Review、Action 全状态、响应式、键盘、ARIA Live、Reduced Motion、Histoire、视觉和 Axe WCAG 2.2 AA | Vitest 覆盖率不低于既有门槛；双视口 Playwright、视觉、Axe、Story 和敏感字段扫描全部通过 |

## 10. 测试、评测与发布

| ID | 类型 | 依赖 | 涉及模块 | 实施内容 | 验证 |
|---|---|---|---|---|---|
| `M5-Q01` | HARDENING | D01..D11,I01..I11,A01..A07,F01..F07 | all | 建立模型、凭证、Template、Agent 配置、Review、Confirmation、GitHub 与 Artifact 固定攻击集 | 跨 Owner/Scope、USER Key 注入团队任务、Prompt 扩权、Tool 扩权、伪造 Finding/Decision、确认欺骗、SSRF、Webhook 伪造和凭证泄漏阻断率 100% |
| `M5-Q02` | HARDENING | I02..I12,A03..A08 | all | 注入模型停用/限流、凭证撤销、成员离队、Reviewer 退出、Diff 变化、Push/PR 超时、回执丢失、Webhook 乱序和 Worker 崩溃 | 固定故障恢复/对账率 `>=95%`；团队任务不回退 USER Key；外部重复 Push/PR/Receipt 为 0；UNKNOWN 最终进入确定状态或人工队列 |
| `M5-Q03` | HARDENING | S03,I03,I06,F08 | all/evaluation | 冻结多模型兼容与 Reviewer 质量集，记录 Provider/Model/Template/Prompt/Skill/成本/延迟、Finding 准确性、证据引用和误报 | DeepSeek 与备用 Provider 协议门禁通过；Reviewer 固定缺陷召回、证据有效率和严重度基线达标；Agent 无 Gate Decision 越权 |
| `M5-Q04` | HARDENING | Q01,Q02,Q03 | all/docs/ci | 执行 M5 Release Gate，审查模型、Agent、迁移、Spring/AgentScope、Review、GitHub、Action、前端、M0–M4 回归、依赖和文档 | 后端、V20–V21、Docker、模型/Reviewer 评测、安全、故障、GitHub Fixture、Vitest、Playwright、Axe、视觉、依赖、链接和格式全部通过；形成版本化报告 |

## 11. 纵向实施波次

| 波次 | 任务 | 可演示结果 |
|---|---|---|
| W0 契约验证 | S01–S05 | 动态模型、Agent Template、Reviewer 和 GitHub 动作协议冻结 |
| W1 模型与 Agent | D01–D05、D10、I01–I05、A01–A04、F01–F05 | 成员创建个人 Coding/Reviewer Agent，分别配置个人/团队模型并委托任务 |
| W2 Review | D06–D07、I06–I07、A05、F06 | Coding 结果进入 Reviewer Advisory 和成员 Gate Review，修改轮次可追踪 |
| W3 GitHub 交付 | D08–D09、D11、I08–I12、A06–A08、F07 | 成员确认 ActionBundle，平台幂等 Push 并创建 Draft PR，UNKNOWN 可对账 |
| W4 发布 | F08、Q01–Q04 | 安全、故障、模型/Reviewer 评测与 Release Gate 关闭 M5 |

前端不等待全部后端完成后集中开发。每个波次先冻结 DTO、错误、事件、权限与恢复契约，后端提供真实 API 或固定 Contract Fixture，前端在同一波次完成 Store、页面、全状态和自动化测试。

## 12. Release Gate

M5 完成需要同时满足：

1. 默认 Personal Agent 保持唯一，成员可创建多个相互隔离的个人 Coding/Reviewer Agent；
2. Agent Ownership、RuntimeRole、Template 和 ExecutionScope 分别建模，不能通过名称或模板推断权限；
3. PERSONAL Task 可以使用 Owner USER Connection，TEAM Task 不得隐式使用 USER Connection；
4. Team/Organization ModelConnection、默认配置、允许列表、能力、区域、预算和配额在 AgentScope 调用前完成服务端交集校验；
5. AgentTemplate、AgentConfiguration、模型目录和价格只追加版本，既有 Conversation/Task/Review 不随当前配置漂移；
6. TaskExecution 固定精确 Template、Agent、Configuration、Provider、Connection、Model Revision、价格与策略 Hash；
7. 普通成员不能通过补充指令、Template 参数或前端请求扩大 Tool、Sandbox、网络、Provider 或凭证权限；
8. ReviewRequest 绑定精确 Baseline、DiffArtifact、TestEvidence、Acceptance 和 ContextPackage Hash，事实变化后旧 Review 失效；
9. Reviewer Agent 只能生成 Advisory Finding，SELF_REVIEW 不能形成 Gate Approval；
10. Gate Decision 只能由 ReviewerEligibilityPolicy 判定合格的 TeamMember 提交；
11. 未通过 Gate Review 或 ActionBundle 未精确确认时，GitHub 写操作数量为 0；
12. Push 和 Create Draft PR 使用动作级凭证、精确 Digest、依赖顺序和唯一 Receipt，凭证公开泄漏为 0；
13. 重复调度、响应丢失和 Webhook 重放不产生重复 Push、Draft PR 或 Receipt；
14. UNKNOWN Action 在固定时间内进入确定状态或人工队列，Push 成功/PR 失败不重复 Push；
15. Conversation Mode 与 Control Mode 展示同一 Agent 配置、Review、Action 和 GitHub 事实；
16. 模型/Reviewer 固定质量集、安全攻击集和故障集达到门槛；
17. M0–M4 全量回归、V20–V21、后端、前端、Docker、GitHub Fixture、依赖和文档门禁全部通过。

## 13. 开工与提交顺序

推荐按以下节点实施和审查：

1. `M5-S01` 至 `M5-S05`：冻结动态模型、Agent Template、Reviewer 和 GitHub 动作协议；
2. `M5-D01` 至 `M5-D05`、`M5-D10`：完成模型、Agent、配置与 V20；
3. `M5-I01` 至 `M5-A04`、`M5-F01` 至 `M5-F05`：完成模型/Agent 纵向闭环；
4. `M5-D06` 至 `M5-I07`、`M5-A05`、`M5-F06`：完成 Review 纵向闭环；
5. `M5-D08`、`M5-D09`、`M5-D11`、`M5-I08` 至 `M5-A08`、`M5-F07`：完成 GitHub 动作闭环；
6. `M5-F08`、`M5-Q01` 至 `M5-Q04`：完成质量与 Release Gate。

每个提交节点先整体 Review，先修正文档与契约，再修正实现并运行相应门禁。任务完成证据保存到 `docs/spikes`、`docs/testing` 或 `docs/evaluations`，文件名以任务 ID 开头。
