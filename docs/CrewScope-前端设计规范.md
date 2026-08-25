# CrewScope 前端设计规范

> 文档版本：v1.5<br>
> 对应设计：`CrewScope 团队协作式 AI 工作执行平台设计文档 v5.1`<br>
> 适用工程：`crewscope-web`  
> 技术基线：Vue 3、TypeScript、Vite

## 1. 设计目标

CrewScope 前端服务于技术团队中的人机协作执行。界面需要让成员快速理解五件事：目标是什么、谁负责任、谁在执行、当前需要什么决策、交付证据在哪里。

设计目标：

1. 对话可直接发起工作，管理页面可稳定维护和检索工作；
2. 成员、Personal Agent、Team Agent 和 Specialist Agent 的身份与动作清晰可辨；
3. 责任、进度、风险、Review、授权和审计信息保持可见；
4. 流式执行具备实时反馈，刷新、断线和恢复后回到一致事实；
5. 高信息密度场景仍具备清晰层级、键盘效率和可访问性；
6. 建立独立、稳定、可扩展的 CrewScope 视觉语言。

## 2. 产品界面模型

### 2.1 Conversation Mode

Conversation Mode 是自然语言工作入口，由以下区域组成：

- 对话流：成员消息、Agent 消息、澄清、计划、协作与确认卡片；
- 上下文栏：Team、WorkProject、WorkItem、仓库、文件、Provider 和参与者；
- 执行画布：Plan、Step、ToolCall、终端、Diff、测试、Artifact 和成本；
- 决策区：Review Gate、Confirmation、Handoff、Takeover、Pause、Resume 和 Cancel；
- 责任区：Owner、Executor、Reviewer、Agent Presence 和当前阻塞方。

成员可以从任意执行对象打开 Control Mode 详情，也可以把 Control Mode 中的对象带回对话继续处理。

### 2.2 Control Mode

Control Mode 是传统 Web 管理入口，覆盖：

- Team、Member、WorkProject、WorkItem 和 Responsibility；
- Inbox、Review、Handoff、Takeover 和 Notification；
- Task、Execution、Artifact、Action、Activity 和 Audit；
- Agent、Skill、Provider、Connection、Runtime 和 Usage；
- Policy、权限、风险、配额和组织设置。

列表、看板、表格、WorkGraph 和时间线是同一份事实的不同投影。视图切换保留筛选、排序与选中对象。

### 2.3 同事实联动

```text
Conversation Mode ── Command / Query ──┐
                                      ├─ Application / Domain ─ Projection
Control Mode ─────── Command / Query ──┘
       ↑                                      │
       └──── AG-UI / Team Event / Cursor ─────┘
```

AG-UI 展示增量输出，领域 Query 展示最终事实。页面在接收 Command Receipt 后等待目标投影追上 `domainEventId/committedVersion`，随后清理 optimistic state。

### 2.4 能力成熟度与预览语义

界面中的业务状态、执行状态和交付证据必须来自已接入的服务端事实。页面不得使用演示数据模拟真实 Conversation、TaskIntent、TaskExecution、AgentRun、工具调用或制品结果。

尚未接入的能力统一使用“原型预览”或“规划中”语义，并同时满足以下要求：

- 在页面主要状态区直接标明能力阶段，不只在局部显示“演示数据”；
- 使用“交互蓝图”“计划示例”“预期证据”等静态说明，不使用“执行中”“运行中”“已完成”等实时状态；
- 不展示运行 ID、完成比例、耗时、文件变更数、测试结果和在线 Agent 等可能被理解为真实事实的数据；
- 所有按钮明确说明当前是否产生服务端命令或外部副作用；
- 接入真实能力后，以领域 Query、AG-UI 事件、Command Receipt 和持久化 Artifact 替换预览内容。

M1 的 Conversation 页面属于 M2 交互蓝图，只负责验证双入口、Scope 与 Focus 联动，不创建或暗示已创建 Conversation、TaskIntent、TaskExecution 和 AgentRun。

## 3. 信息架构

### 3.1 全局框架

```text
AppShell
├── ScopeSwitcher：Organization / Team / WorkProject
├── PrimaryNavigation
│   ├── Today：团队首页 / 我的工作 / 待处理
│   ├── Work：WorkGraph / WorkItem / WorkProject
│   ├── Collaborate：Conversation / Inbox / Review
│   ├── Observe：Task / Activity / Audit
│   ├── Capabilities：Agent / Skill / Provider / Connection
│   └── Governance：Member / Policy / Usage / Settings
├── ContextHeader：面包屑 / 状态 / 责任 / 主动作
├── MainWorkspace：页面主内容
├── ContextDrawer：责任 / 执行 / Review / Artifact
└── CommandLauncher：搜索 / 跳转 / 新建 / 快捷动作
```

导航分组随权限裁剪，URL 始终保留当前 Team 和目标对象。对话入口在所有工作页面可见。

M1-F01 固化首批管理路由：

```text
/today        当前 Team/WorkProject 的当日工作入口
/work         WorkProject 范围与后续 WorkItem 管理入口
/team/members Team Membership 管理
/control      保留 Query 并兼容跳转到 /today
```

`team` 与 `project` Query 使用服务端 UUID。Scope Store 按 Team → WorkProject 顺序恢复范围；未知范围回落到第一个可访问对象并替换为规范 URL。切换 Team 清除旧 Project 与 Focus，切换 Project 保留当前页面。Conversation 与管理入口复用完整范围 Query。

M4-F01 固化 Coding 对象深链接：

```text
/work?team=<teamId>&project=<projectId>&workItem=<workItemId>&task=<taskId>&attempt=<executionId>&workspace=<workspaceId>
```

`task` 依赖 `team + project`，`attempt` 依赖 `task`，`workspace` 依赖 `attempt`。Coding Store 在恢复后读取服务端 attempt 事实并校验 Workspace 归属。关闭 Coding 焦点移除 `attempt` 与 `workspace`，保留 Task、WorkItem、筛选和页面模式。Organization、Team 与 WorkProject 共同形成 Repository、BuildProfile、attempt 和 Evidence 的缓存分区。

M4-F02 固化 WorkProject Repository 设置路由：

```text
/settings/repositories?team=<teamId>&project=<projectId>
```

路由要求 Repository 管理权限。页面从服务端受管 Repository Catalog 选择稳定 Key，并从创建候选中排除当前 WorkProject 已存在的 RepositoryBinding，提供 Draft/Existing Preflight、创建、启用和停用。概览中的“可绑定”数量使用 AVAILABLE Catalog 与当前 Binding Key 的差集；Catalog 未进入 Ready 或刷新失败时关闭新建、Draft Preflight 和提交。桌面使用事实行，窄屏沿 Repository、状态、版本、审计、Preflight 和操作顺序阅读；创建入口同时存在于 ContextHeader 与页面内容区。命令失败保留原 Idempotency Key 供安全重试，409/412 丢弃陈旧命令并回读 Binding 列表与详情。页面只展示 Repository Key、Branch、Commit 截断值、状态、版本和成员安全审计摘要。

M4-F03 在 WorkItem 委托和 TaskIntent 确认结果中复用统一 CodingTarget 表单。表单默认选择第一个 ACTIVE RepositoryBinding、其默认分支、`.` AllowedPaths 与第一个服务端 BuildProfile，提交前必须完成显式 Ref Preflight；Repository、Ref、AllowedPaths 或 Profile 任一变化都会立即失效旧 Preflight。成员可以关闭 Coding 开关创建兼容的通用 Agent Task。草稿仅保存稳定 ID、短 Ref、仓库相对路径与公开 Profile 坐标，并以 Organization、Team、WorkProject、WorkItem 分区保存在 SessionStorage；成功后清除，损坏、跨 Scope 或失效选项按当前服务端默认值恢复。可重试创建锁定表单并沿用 Task Store 保存的原命令和 Idempotency Key。

TaskIntent 确认继续使用空请求体，先原子创建 WorkItem 和责任链。确认结果卡向当前 Owner 提供“配置 Coding Task”入口，携带 Conversation、WorkItem 与最新持久 USER Message 坐标进入同一委托表单。服务端逐次复验 WorkItem 责任、Repository Scope、Binding 状态、BuildProfile 和 Ref；Conversation 与 Control 两个入口共享同一 Coding Task 创建流程。

M5-F05 在这份统一委托表单中增加 Agent 与 Configuration 选择。候选只取当前 WorkItem 责任链中的 Personal Agent 和 Team Agent Executor，Agent 目录只补充 Ownership、RuntimeRole、生命周期和当前 Revision。选择变化触发 Task 路由的服务端 Preflight，旧请求与旧结果立即失效；创建按钮仅在 CodingTarget 与 Model Preflight 都通过时可用，并提交 Preflight 返回的精确 Configuration Revision。

Preflight 卡展示服务端推导的 PERSONAL/TEAM、Binding Source、Primary/Fallback Provider/Model、Connection Owner Type、Catalog/Price Revision、PolicyPack Version 和 Resolution Hash。TEAM 结果显示 USER Key 禁用边界。Billing Subject 未进入公开 DTO 时，成本主体区域显示“服务端已固定、当前 API 未披露”，不能从 Connection Owner、Agent Ownership 或 Team 默认推导。Credential、Endpoint、Prompt、Tool Payload 和完整策略载荷不进入 Store、DOM、URL、错误信息或草稿。

委托草稿按 Organization、Team、WorkProject 和 WorkItem 分区保存在 SessionStorage，只包含目标、验收文本、AgentProfile ID 与公开 Revision；创建成功后清除。可重试失败冻结表单并复用 Task Store 中的原命令与 Idempotency-Key。Task Retry 留空沿用父 attempt 固定配置，填写正整数 Revision 时提交显式切换并由服务端重新 Preflight。Modal 初始焦点位于对话框容器，避免窄屏自动滚离 Agent/Preflight 上下文，并支持 Escape 与 Focus Trap。

M4-F04 在 Task 详情顶部提供统一 Execution Studio。页面从 Coding Store、Task Runtime Store 和服务端公开投影组合不可变基线、Workspace/Sandbox、Coding Agent、当前 Plan/Step、最近结构化 CommandEvidence、资源预算和恢复代次。attempt 选择写入 `attempt` Query，服务端返回的 Workspace ID 写入 `workspace` Query；恢复时先验证完整 Team、WorkProject、Task、attempt 和 Workspace 归属，再切换 Task Runtime 与 Coding 事实。Conversation 和 Control 入口进入同一 Task URL，读取同一事实。

Execution Studio 的 Loading、非 Coding Empty、Error、Forbidden、Recovering 和 Terminal 使用独立稳定状态。Recovering 展示恢复代次和对账含义，Terminal 展示完成原因或稳定失败码与证据保留语义。资源预算同时显示耐久命令与变更文件用量，以及 CPU、内存、PID、命令时长、输出、写入、单文件、Diff 和测试修复上限。浏览器状态只保存公开坐标与摘要。

M4-F05 在 Execution Studio 下方提供 Diff Explorer。桌面使用文件树与 Patch 双栏，窄屏保持文件树、选中文件、Patch 的顺序阅读。文件树按路径层级展示新增、修改、删除、重命名、复制和二进制状态，顶部显示文件数、增删行和 Diff Generation；超过 400 个匹配文件时只渲染前 400 个并要求继续按路径筛选。

Diff Explorer 从统一 Task Timeline 重放 `WORKSPACE_DIFF_RESET/DELTA`。RESET 完整替换当前 Epoch，DELTA 只接受相同 Epoch 的直接后继 Sequence，重复事件忽略，缺失、乱序和浏览器 Cursor 过期停止增量合并并回读 attempt 权威 DiffManifest。实时流只接受 canonical 仓库相对路径、固定变更枚举、非负安全整数、Boolean 与 64 位十六进制 Patch Hash；任一嵌套文件事实不满足形状时停止合并。最终 Patch 通过独立授权 Artifact API 按 256 KiB 分页读取，前端复验连续 Range、固定总大小、ETag、Artifact SHA-256 和完整 UTF-8，并只按解码后的精确 `diff --git` Header 坐标定位单文件；Patch 正文、`+++` 和 Rename 文本不参与文件选择。Binary、实时未终态和超出预算使用明确空态。

M4-F06 在 Diff Explorer 下方提供 Evidence 只读面板。桌面左侧选择 CommandEvidence，右侧依次阅读命令事实、日志、TestEvidence、测试统计、Acceptance 和测试报告；窄屏按命令列表、命令详情、日志、测试与验收顺序排列。命令事实显示 Kind、Tool Key、Termination、Exit Code、执行时长、Timeout、摘要和稳定失败分类。测试事实显示 Total、Passed、Failed、Errors、Skipped，并按 Criterion Index 排列验收结果。

日志与测试报告只从 Task、attempt、evidence 固定关系入口读取，每页 64 KiB。前端复验连续 Range、固定总大小、ETag、Content-Type、服务端文件名、Artifact Size、SHA-256 和完整 UTF-8；分页失败保留已经验证的字节，重试从原 Offset 继续。下载名从 `Content-Disposition` 读取，移除路径段，并拒绝空值、`.`、`..`、控制字符和超过 255 字符的名称。页面仅使用纯文本插值展示内容，常见 Token、Password、Secret 和 API Key 形态增加显示层遮蔽，完整内容校验后使用通过校验的服务端文件名下载。面板没有命令输入、编辑、重跑、任意 Shell、任意 URL 或任意 Artifact ID 入口。

M4-F07 在 Execution Studio 中提供 Coding 进度与执行控制。进度轨道固定为准备、分析与计划、代码变更、测试与修复、交付五个阶段，并从 Workspace、当前 PlanVersion、DiffManifest、Command/TestEvidence 与 CodingResult 的最新公开事实确定位置。轨道提示该位置是阅读投影，TaskExecution 与 Workspace 状态继续承担执行事实。

进度面板展示当前 Plan Todo、最近 Step Checkpoint、当前 Agent Run 的 State Snapshot 摘要、当前 Step、Checkpoint 连续性缺口、最新 TestEvidence 序号与 WorkspacePolicy 修复预算上限。TestEvidence 序号表达证据发布顺序，公开 API 当前未披露 Specialist 已用修复轮次，前端不推导该数值。当前 Coding attempt 与 TaskExecution ID 对齐后嵌入 M3 `TaskControlPanel`；历史 attempt 保持只读，对齐中的当前 attempt 显示同步状态。Pause、Resume、Cancel 与 Retry 沿用 `If-Match`、`Idempotency-Key`、409/412 回读、原命令重试、离线关闭、确认对话框和焦点恢复协议。

M5-F06 在 Evidence 面板下方提供 Review Workbench。`review` Query 保存当前 ReviewRequest ID，并依赖完整 `team + project + task + attempt` 坐标；修订列表按 revision 倒序展示当前与失效历史。Review Store 使用 Organization、Team、Task、attempt 和 ReviewRequest 分区列表与强 ETag 详情，Scope 或 attempt 切换取消旧请求，重复的同坐标同步复用在途请求。Task/Coding 规范 Query 写入引发的短暂 loading 不取消同一 Review 读取。

Workbench 依次展示 SELF_REVIEW/失效提示、不可变 ContextPackage、Baseline/Delivery、精确 Diff 范围、对应 TestEvidence 与 Acceptance、Agent Findings 和成员 Gate。Agent Finding 固定标记 `ADVISORY`，包含严重级别、类别、主张、建议修复与服务端 Evidence；点击路径和行号后选择 Diff Explorer 的对应文件，滚动并聚焦只读 Patch 区。`SELF_REVIEW` Finding 可用于修复，不能形成 Gate Approval。`INVALIDATED/DIFF_CHANGED` 历史保留 Finding、Decision 与修改轮次，只读且不显示新命令。

Reviewer 执行只在 OPEN/IN_PROGRESS 且在线时启用。Gate Decision 只向持有 ACTIVE USER Reviewer 责任的当前成员开放，支持 `COMMENTED`、`APPROVED`、`CHANGES_REQUESTED` 和 `REJECTED`；最终 Eligibility、职责分离和 Review 当前性始终由服务端复验。命令绑定详情 ETag，使用独立 Idempotency-Key；可重试失败复用原键，409/412 回读权威详情。`CHANGES_REQUESTED` 使用 modification 路由并展示连续 Round。

Gate 对话框提供 Escape、Focus Trap、理由必填和 4,000 字符上限。桌面将 Diff 与 Test 并排，窄屏依次阅读；两种视口共享同一语义 DOM，并通过 Axe WCAG 2.2 AA 与独立视觉基线。Review DTO 白名单排除 Patch 正文、Prompt、Credential、模型原始输出和 Reasoning。A05 未公开 Reviewer PolicySnapshot 选择目录，因此空态只说明服务端编排边界，不提供 UUID 输入，也不从 Agent、责任链或 Task PolicySnapshot 推导。

M5-F07 在 Review Workbench 后提供 GitHub Delivery。页面同时读取当前成员可用的 TEAM GitHub App 与 USER OAuth Connection 安全投影，并从当前 Connection 下选择 ACTIVE Team ProviderBinding 和服务端 DELIVERABLE Repository Catalog 项。Repository 使用稳定外部 ID；页面没有 owner/repo、Remote URL、分支、Connection Secret、Grant 或任意 Action 参数输入。Catalog 同步与 Remote Preflight 绑定 Connection Version，Preflight 同时绑定 Team Binding 与 Repository ID。

只有当前未失效 Review 的成员 `APPROVED` Decision、HEALTHY Authorization 和 Remote Preflight 同时成立时才能规划 ActionBundle。浏览器只提交 ReviewDecision、ProviderBinding、Repository、可选 Expected Remote Head、PR Title 与 Body；受管 Branch、Delivery Head、Base、Draft、风险、依赖和 Digest 由服务端生成。`STALE` Bundle 保留只读证据并重新开放规划表单。

ActionBundle 依次展示 Repository/Review/Baseline/Delivery、完整 Bundle Digest、Push Branch 与 Create Draft PR 两个 Stage。Confirmation Dialog 再次显示 Version 与完整 Digest，并要求成员显式勾选后提交强 ETag 与 Digest。可重试失败复用原 Idempotency-Key；409/412 回读权威 Bundle，不对旧 Digest自动确认。CommandReceipt 只保留 Correlation ID，不在浏览器生成 Confirmation、Dispatch、Receipt 或 ExternalResult。

Push 与 PR 分别展示 Dispatch、Receipt 和 ExternalResult。Push 成功而 PR 失败时保持两个独立结果，刷新只回读 Webhook/主动查询合并后的单调外部事实，不触发 Push。`UNKNOWN/RECONCILING` 显示只查询对账、尝试次数和下次时间，`MANUAL_REVIEW` 只向合格 Owner 开放有证据的失败终结。浏览器不提供 Dispatch 创建、Claim、Heartbeat、Lease、Fencing 或 Worker 执行入口。

离线保留已加载 Review、Catalog、ActionBundle 与结果，同时关闭 Catalog 同步、Preflight、Plan、Confirm、Cancel 与人工终结。外部对象只展示类型与安全身份 Hash；公开 API 未返回规范 PR URL 时不构造链接，未来外链只接受没有用户名或密码的 `https:` URL。Desktop 使用选择器、多列事实和分步轨道，窄屏按 Connection、Plan、Bundle、Push、PR、Confirmation 顺序阅读，并使用独立双视口视觉基线。

M4-F08 将 Repository Settings 与 Execution Studio 纳入统一页面完成门禁。Repository 离线时保留已加载事实并关闭绑定、Preflight、启停和刷新；绑定面板聚焦首个字段，Escape 关闭后使用稳定触发器标识恢复当前 DOM 焦点。Execution Studio 固定提供 Ready、Recovering、Terminal、Offline、Loading、Empty 和 Error 组件状态。Preflight、状态同步和分页错误使用相应 Live Region，CodingTarget 动效服从 Reduced Motion。

M4 页面在 desktop Chromium 与 390×844 narrow Chromium 执行交互、视觉和 Axe WCAG 2.2 AA 回归。Histoire 保存 Repository 五种状态与 Coding Execution 七种状态。Artifact 权限边界只消费当前 Task/attempt 的 Patch、Command Log 与 Test Report 状态，历史 attempt 缓存不能改变后续 Task 的权限导航。浏览器公开状态继续排除宿主路径、容器坐标、Token、Lease/Fencing、AgentState、State Reference、Checkpoint Hash 和 reasoning。实现与门禁见 [M4-F08 前端全状态与质量门禁](testing/M4-F08-前端全状态与质量门禁.md)。

M5 页面复用相同桌面/窄屏、视觉和 Axe 门禁，并将 Agent、Model、Review、Action 的代表状态纳入独立 Histoire Story。Review Gate 与外部写操作确认框必须在最上层模态内执行初始焦点、Tab 环、Escape 和焦点恢复。Web 敏感字段门禁扫描公开 DTO、组件状态与 Story，API Key 只允许作为不进入 Store 的单向命令输入。实现与验证见 [M5-F08 前端全状态与质量门禁](testing/M5-F08-前端全状态与质量门禁.md)。

M5 增加 Agent 与模型设置路由：

```text
/settings/agents?team=<teamId>
/settings/agents?team=<teamId>&agent=<agentProfileId>&configurationRevision=<revision>
/settings/models?team=<teamId>&provider=<providerKey>&ownerType=<USER|TEAM|ORGANIZATION>&connection=<connectionId>
```

`/settings/agents` 面向当前 TeamMember，展示唯一默认 Personal Agent、成员创建的 USER-owned Specialist 和有权查看的 TEAM-owned Agent。成员从服务端批准的 AgentTemplate 创建 Coding/Reviewer Agent；列表展示 Ownership、RuntimeRole、TemplateVersion、当前 Configuration Revision、PERSONAL/TEAM Binding 与生命周期状态。任务和成本只从按 Agent 授权的服务端聚合投影读取，在投影交付前显示明确的未接入说明，不在浏览器端扫描 Task 推导数字。DeepSeek 显示为 DeepSeek，OpenAI-compatible 仅作为管理详情中的 Adapter 元数据。

Agent 创建与配置表单只展示 Template 声明的可配置槽位。PERSONAL 与 TEAM Binding 分区使用主模型与 Fallback 独立选择器；TEAM Binding 只显示 TEAM/ORGANIZATION Connection 或“继承团队默认”。Fallback 候选集排除主模型并重新执行能力、数据和成本策略过滤。保存前执行 Model Preflight，确认卡显示个人/团队任务适用范围、新 Conversation 生效、已有 Conversation 保持原版本和运行中 Task 继续使用 PolicySnapshot。已有 Conversation 只在服务端返回安全可刷新状态时提供“切换到新配置”命令。

`/settings/models` 面向当前 Organization 用户展示版本化 Provider/Catalog、Region、Retention、能力和价格；当前成员管理自己的 USER Connection，活动 TeamMember 查看 TEAM Connection 安全投影，Team Provider Manager 执行 TEAM 写操作，平台管理员管理 ORGANIZATION Connection。Credential 只在创建和轮换表单中单向输入；页面只显示 Credential Version、验证时间、健康和稳定失败码。浏览器 Store、URL、Toast、Telemetry 和错误详情不保存 Key、Credential Reference、原始 Endpoint 私有参数和 Provider 原始错误。Team/Organization Model Default、允许列表、预算和完整 Audit 时间线没有公开管理 API 时展示明确只读缺口，不提供本地假配置。

路由守卫和按钮权限来自当前会话，只负责裁剪界面和给出明确的 Access Denied 反馈。所有资源读取和命令继续由服务端执行 Membership、Role Scope 与 Principal 校验。

### 3.2 页面模板

| 模板 | 适用页面 | 结构 |
|---|---|---|
| Team Pulse | Today、团队首页 | 责任摘要、待处理、风险、运行中任务和团队动态 |
| Collection | WorkItem、Task、Inbox、Audit | 筛选栏、视图切换、结果区和详情抽屉 |
| Work Detail | WorkItem、Task、Review | 上下文头、主事实、时间线和责任/制品侧栏 |
| Execution Studio | Conversation、Coding Task | 对话、执行画布和上下文抽屉的可调整多面板 |
| Configuration | Agent、Provider、Policy、Settings | 设置导航、表单、权限说明和变更记录 |

### 3.3 多视图策略

- List：默认通用视图，适合快速扫描和移动端；
- Board：按状态、责任人或阶段组织工作；
- Table：适合字段密集、批量比较和治理场景；
- WorkGraph：展示依赖、阻塞和上下游交付；
- Timeline：展示执行、协作、Review、动作和审计顺序。

M1 交付 List 与 Board，Table 随字段体系稳定后交付，WorkGraph 和执行 Timeline 按对应里程碑交付。

### 3.4 M1 WorkItem Collection 契约

M1-F02 的 `/work` 以当前 Team/WorkProject 为集合范围，List 与 Board 共享 `WorkItemStore` 和 `WorkItemCard`。List 用于紧凑扫描，Board 按 Status 分列并在窄屏横向滚动。集合通过显式“加载更多”消费服务端不透明 Cursor，不推断总数和页码。

URL 使用 `view/status/type/priority` 保存可分享视图。状态筛选进入服务端 Query；A05 尚未提供类型和优先级参数，因此两者只筛选当前已加载集合，续页数据进入后再次应用。缺失或非法值规范化为 `list/all/all/all`，规范化在 Team/WorkProject 恢复完成后执行。

创建表单提交 Native WorkItem Command，客户端只发送业务字段和 `Idempotency-Key`。创建者、Owner、权限和 Scope 事实由服务端解析。收到 Receipt 后刷新集合，不使用客户端构造对象冒充已提交事实。

创建表单的 WorkItem Key 仅根据当前已加载集合给出可编辑建议。Cursor 尚未加载完整时客户端不保证建议值全局可用，服务端项目范围唯一约束负责最终裁决；后续可由专用 Key Suggestion API 替代本地建议。

### 3.5 M1 WorkItem Detail 契约

M1-F03 使用 `workItem={UUID}` 控制详情抽屉，使用 `focus={WorkItem Key}` 在 Conversation 与 Control Mode 之间共享对象上下文。卡片打开详情时同时写入两项；关闭详情只清除 `workItem`；Team 或 WorkProject 切换清除两项。

详情从 A05 一次读取 WorkItem、Comment 和 ResourceLink 一致性快照。状态迁移使用详情版本作为强 `If-Match`，收到 Receipt 后刷新详情和集合。评论与资源命令成功后刷新详情，不用客户端临时对象替代服务端事实。`409 optimistic_lock_conflict` 触发详情回读，并同时展示提交版本和服务端当前版本。

详情抽屉是模态对象视图：打开后移动焦点、约束 Tab、支持 Escape、锁定背景滚动，关闭后把焦点恢复到发起卡片。桌面从右侧覆盖，窄屏占满可用宽度。外部 Provider WorkItem 的状态由来源系统管理；非归档对象仍可追加 CrewScope 评论和 ResourceLink。

### 3.6 M1 Responsibility 与 Timeline 契约

M1-F04 在详情打开时并行读取 A06 ACTIVE ResponsibilityAssignment 和 A07 第一屏业务时间线。详情、责任链、时间线分别维护 Loading、Empty、Error 和 Ready 状态；对象切换或抽屉关闭后，旧请求不能回写当前视图。时间线使用专用不透明 Cursor，续页按 `eventId` 去重，失败保留已展示活动。

责任组件直接消费服务端 DTO。Owner 替换同时提交当前 Assignment ID 与 Version；Executor/Reviewer 释放使用 Assignment Version 的强 `If-Match`。`REVIEWER + USER` 表达 Gate Reviewer，`REVIEWER + SPECIALIST_AGENT` 表达无 Gate 效力的 Advisory Reviewer。前端只提示明显的 Owner/Executor 职责冲突，ReviewerEligibilityPolicy 与 PolicyPack 降级由服务端裁决。

人类候选来自 ACTIVE TeamMember。A06 尚未提供 Agent 目录查询，M1 使用折叠的高级 Principal ID 输入，不构造模拟 Agent。WorkItem 列表契约也未返回责任摘要，M1 在详情展示完整责任；卡片的 Owner/Executor 摘要等待集合 Query 提供批量投影后交付，避免逐卡 N+1 请求。

“与 Personal Agent 讨论”保留对象与 Scope Query 进入 Conversation。“交给 Agent 处理（规划中）”只说明后续 TaskExecution 接入，不创建客户端假任务或虚假运行状态。

### 3.7 M2 Conversation 状态与可访问性契约

Conversation 使用 Loading、Empty、Error、Offline、Reconnecting 和 Cancelled 六类明确状态。Loading 与 Reconnecting 标记忙碌，Error 紧急播报，其他动态变化礼貌播报。Message 历史不作为整体 Live Region，独立状态节点只播报最新变化。

离线提示使用浏览器网络信号，不替代服务端事实。页面保留已加载事实和按 Conversation 分区的草稿；离线时 Textarea 保持可编辑，发送按钮禁用，联网后不自动提交草稿。

从列表选中 Conversation 后聚焦详情标题，窄屏返回后恢复原列表按钮。新建弹窗使用初始焦点、Tab 焦点陷阱、Escape 关闭和触发元素恢复；创建成功后聚焦新 Conversation 标题。

## 4. 视觉身份

### 4.1 色彩 Token

初始 Token 作为实现基线，后续只通过 Token 调整主题：

```css
:root {
  --cs-brand-950: #15231d;
  --cs-brand-800: #263a31;
  --cs-brand-600: #3f7257;
  --cs-brand-300: #8ed5a7;
  --cs-brand-200: #b8efca;
  --cs-canvas: #f3f5f2;
  --cs-surface: #ffffff;
  --cs-surface-subtle: #fafbf9;
  --cs-border: #dce2dd;
  --cs-text: #17202a;
  --cs-text-muted: #68766e;
  --cs-info: #3f78b5;
  --cs-agent: #7257b5;
  --cs-warning: #b7792c;
  --cs-danger: #b84c4c;
  --cs-success: #43845e;
}
```

语义状态从上述基色派生文字、背景和边框三档。风险、责任和审批状态同时提供图标与文字。

### 4.2 字体

- UI 与正文：`Inter, ui-sans-serif, system-ui, sans-serif`；
- 品牌标题与关键空状态：`Georgia, ui-serif, serif`；
- ID、代码、Commit、日志和数值：`ui-monospace, SFMono-Regular, monospace`；
- 工作页面正文默认 14px，辅助信息 12px，标题依次为 18/24/32px；
- 数字指标启用 tabular numbers，日志和 Diff 保留等宽对齐。

Serif 只用于低频识别元素，表格、表单、导航和执行信息使用 Sans Serif。

### 4.3 空间、形状与层级

- 间距基数为 4px，常用间距为 4/8/12/16/24/32；
- 控件高度为 32/36/40px，触摸目标不低于 44px；
- 圆角为 8/12/16px，Badge 可使用全圆角；
- 工作区主要依靠边框和 Surface 层级，阴影只用于浮层、拖拽和焦点对象；
- 内容区最大宽度由页面模板决定，执行画布和数据表不设置文章式窄宽度。

### 4.4 动效

- Hover 与 Focus：120ms；
- 抽屉、Popover 和面板切换：160–200ms；
- 执行状态迁移：200–240ms；
- 流式内容使用低干扰增量反馈，避免持续闪烁和大面积骨架动画；
- `prefers-reduced-motion` 下关闭位移、缩放和非必要循环动画。
- 全局在 Reduced Motion 下取消平滑滚动，并将必要 Transition 与动画压缩至近即时。

## 5. CrewScope 核心组件

| 组件 | 必备信息 | 主要使用位置 |
|---|---|---|
| `ResponsibilityChain` | 角色、主体、有效期、来源、冲突和待接手状态 | WorkItem、Task、Review、Handoff |
| `AgentPresence` | Agent 类型、状态、当前步骤、模型/Runtime、接管入口 | 对话、执行画布、团队首页 |
| `AgentTemplateCard` | Template 名称、版本、RuntimeRole、能力、可用 Ownership/ExecutionScope、Tool/Skill 摘要和不可创建原因 | 我的 Agent、Agent 创建向导 |
| `ModelSelectionField` | 厂商、Model ID、能力、区域、价格、Connection Owner、健康和不可选原因 | Agent 的 PERSONAL/TEAM Binding 设置 |
| `ModelConnectionCard` | Scope Owner、Provider、Region、账单主体、凭证状态、健康、验证、轮换与撤销 | 模型与凭证设置 |
| `AgentConfigurationHistory` | Configuration Revision、主/Fallback 模型、变更人、变更时间、生效范围和配置 Hash | Agent 设置与 Audit |
| `WorkItemCard` | M1-F02：Key、目标、状态、类型、优先级、标签、Due Date；责任摘要等待集合批量投影，避免逐卡 N+1 | 列表、看板、对话引用 |
| `WorkItemDetailDrawer` | 一致性详情、版本、合法迁移、评论、ResourceLink、责任链、时间线、并发冲突和 Personal Agent 跳转 | WorkItem List/Board 详情 |
| `CodingTargetFormSection` | ACTIVE RepositoryBinding、Baseline Ref、AllowedPaths、精确 BuildProfile、Preflight、Scope 化草稿和通用任务切换 | WorkItem 委托、TaskIntent 确认结果 |
| `CodingExecutionStudio` | 不可变基线、Workspace/Sandbox、Coding Agent、Plan/Step、最近结构化命令、资源预算、恢复代次和终态保留语义 | Task 详情、Conversation/Control 双入口 |
| `CodingDiffExplorer` | 层级文件树、变更类型、累计统计、RESET/DELTA 状态、断线对账和授权单文件 Patch | Task 详情、Execution Studio |
| `TaskTimeline` | PlanVersion、Step、Tool、等待、恢复、耗时和成本 | Task 详情、执行抽屉 |
| `ReviewGateCard` | Reviewer、资格、检查项、Finding、Decision | Inbox、对话、WorkItem 详情 |
| `ActionReceiptCard` | 动作、风险、确认人、外部回执、对账状态 | 对话、Task、Audit |
| `ArtifactPreview` | 类型、版本、来源、哈希、预览和下载策略 | Diff、报告、测试证据 |
| `TeamActivityItem` | Actor、动作、目标、时间、来源和 Correlation | 首页、Activity、详情时间线 |

组件 Props 使用领域 DTO，不在视图内部重建责任、权限和状态机规则。组件在完整页、抽屉和对话卡片中共享状态语义。

## 6. 关键交互

### 6.1 对话、任务与 Diff 联动

1. 对话消息创建或引用 WorkItem；
2. 确认 TaskIntent 后打开 Execution Studio；
3. Plan 与 Step 在执行画布持续更新；
4. 文件变化进入 Diff 索引，选中文件定位到对应 Step 和 ToolCall；
5. 行内评论生成 Review Context 或后续 WorkItem；
6. 测试证据与验收标准并列展示；
7. Review Gate 通过后显示 PlannedAction 和 Action Receipt。

工作区默认突出当前需要人处理的决策，运行日志和低层工具输出按需展开。

### 6.2 责任与协作

- 任何 WorkItem 和 Task 都可在一屏内确认 Owner、Executor 和 Gate Reviewer；
- Responsibility 变化先预览影响，再提交 Command；
- 请求协助、Handoff 和 Takeover 使用结构化卡片，展示范围、证据、权限和截止时间；
- 当前阻塞方显示在页面头、卡片和时间线中；
- Agent 执行与人工责任使用不同视觉标识，人工责任主体始终可追溯。

### 6.3 反馈与错误

- Command 立即返回进行中反馈，并通过 Receipt 跟踪最终状态；
- 乐观锁冲突展示当前事实与用户草稿，支持重新应用有效变更；
- 网络断开显示最后同步时间、各事件流 Cursor 和恢复状态；
- 长任务提供 Pause、Resume、Cancel 和 Takeover，按钮按权限与状态显示；
- 危险动作展示目标、范围、身份、风险、回滚/补偿方式和确认有效期。

## 7. 响应式与可访问性

### 7.1 响应式

| 宽度 | 布局策略 |
|---|---|
| `>= 1440px` | 展开导航、主工作区和上下文抽屉，可用三面板 Execution Studio |
| `1024–1439px` | 折叠导航，上下文抽屉按需覆盖，保留双面板执行区 |
| `768–1023px` | 单主栏，详情与执行区使用页签，表格降级为列表 |
| `< 768px` | 聚焦查看、评论、审批、确认和接管；复杂图与 Diff 提供摘要和跳转 |

桌面端优先交付，所有新组件从 M0 起保留窄屏行为，不使用全局固定最小宽度阻断页面访问。

窄屏 Composer 使用 16px 输入字号避免移动浏览器自动缩放，发送按钮最小高度为 42px，底部间距包含 `safe-area-inset-bottom`。

### 7.2 可访问性

- 键盘可到达所有交互控件，焦点顺序与视觉顺序一致；
- Focus Ring 清晰可见，Dialog/Drawer 正确管理焦点与 Escape；
- 语义 HTML 和 ARIA 描述覆盖状态、进度、错误与流式区域；
- 正文和关键控件达到 WCAG 2.2 AA 对比度；
- 状态不单独依赖颜色，图表提供文本摘要；
- 新消息、任务状态变化和错误使用可控的 Live Region，避免重复播报。

## 8. 竞品参考与独立性

### 8.1 吸收范围

从 `vibe-kanban` 研究：

- 对话、执行、Diff 和 Git 上下文的联动关系；
- 工具状态、进程状态和执行反馈的可见性；
- 高信息密度研发工作台与快捷操作。

从 `multica` 研究：

- 同一工作数据的列表、看板、表格和 Swimlane 表达；
- Agent、Runtime、Usage、Member 和 Project 的管理控制面；
- 个人工作与团队治理信息的层次组织。

### 8.2 CrewScope 差异

CrewScope 的页面围绕以下对象形成独立体验：

- 人与 Agent 的 Responsibility Chain；
- 团队协作请求与 Contribution；
- 可暂停、恢复、接管的 Task Timeline；
- 人员资格与职责分离约束下的 Review Gate；
- 外部副作用的 PlannedAction、Confirmation 和 Action Receipt；
- 面向团队负责人的 Activity、Audit、Risk 和 Cost 观测面。

### 8.3 实现边界

1. 不复制竞品源码、DOM 层次和组件切分；
2. 不复制 CSS Token、Tailwind Class 组合、主题、图标组合和动效参数；
3. 不复制侧栏分组、页面命名、路由、文案、空状态和品牌资产；
4. 参考模式先写成用户目标，再映射到 CrewScope 领域模型和设计 Token；
5. Vue 组件在 CrewScope 工程中独立实现，并保留设计决策记录；
6. 评审时并排检查参考截图与 CrewScope 截图，确认布局、视觉和任务流具备明显差异。

## 9. 工程规范

### 9.1 目录建议

```text
src/
├── app/                 # AppShell、Router、全局 Provider
├── design/              # Token、图标适配、基础样式
├── components/          # 通用 UI 组件
├── domains/             # Work、Conversation、Task、Review 等领域 UI
├── pages/               # 路由页面与页面编排
├── stores/              # 客户端状态与服务端投影缓存
├── api/                 # Client、DTO、错误与 Cursor
└── test/                # Fixture、Mock Server 与测试工具
```

### 9.2 状态规则

- 服务端事实进入 Query Cache/Store，临时面板、选择和草稿进入本地状态；
- URL 保存可分享的范围、筛选、视图和选中对象；
- AG-UI 增量态与领域投影态分开保存，通过 ID 和版本关联；
- 枚举显示文案、图标和语义色集中映射；
- 权限隐藏提升可用性，服务端授权仍是安全边界。

### 9.3 组件工作台

M0 建立 Histoire 组件工作台，至少覆盖 Token、基础控件与 CrewScope 核心卡片。每个领域组件提供正常、加载、空、错误、无权限、冲突和长内容状态。

## 10. 测试与验收

### 10.1 自动化

- Vitest：Token 映射、状态格式化、组件行为和可访问性断言；
- Histoire：组件状态目录与人工视觉检查；
- Playwright：Conversation/Control 互跳、视图恢复、责任分配和核心纵向流程；
- 截图测试：AppShell、Team Pulse、Collection、Work Detail 和 Execution Studio 关键尺寸；
- 构建检查：TypeScript、Vite Build、路由懒加载和包体预算。

### 10.2 页面完成定义

一个页面完成需要满足：

1. 对应领域事实、Command、Query、权限和错误状态已接入；
2. Loading、Empty、Error、Forbidden、Conflict、Offline、Reconnecting 和 Cancelled 状态可用；
3. Conversation Mode 与 Control Mode 之间具有对象级跳转；
4. 键盘、Focus、对比度、Live Region 和 Reduced Motion 通过检查；
5. 桌面、平板和移动降级策略经过验证；
6. 核心路径具备 Vitest 和 Playwright 覆盖；
7. 截图已完成视觉回归；
8. 与参考产品的布局、视觉、组件和任务流差异已在 PR 中说明。

### 10.3 Agent 创建与配置

- Agent 创建只提交服务端批准的 Template 坐标、USER/TEAM Ownership 和显示名称；命令回执没有 Profile ID 时，刷新后只对唯一新增 ID 执行跳转；
- USER Agent 由 Owner 配置，TEAM Agent 的写操作要求 `agent:manage`。按钮权限用于界面守卫，服务端授权是最终边界；
- 详情按 Profile、当前 Configuration、不可变历史、模型可选交集和 Preflight 分资源加载，单个派生事实失败不伪造替代事实；
- 首次 Configuration 使用 `If-Match: "0"`，后续使用当前 Revision 强 ETag；失败重试复用 Idempotency-Key，输入变化生成新 Key；
- Skill 候选只来自 Template 公共白名单。Memory/Budget 没有公开目录时只展示并保留当前引用，不提供任意 UUID 输入；
- Credential、Endpoint、API Key、System Prompt、Tool Payload 和 Structured Output Schema 不进入 Agent 页面状态；Key 的单向录入集中在模型与凭证页；
- 桌面使用内嵌详情，移动端提供页面内创建入口和 Bottom Sheet。对话框必须实现初始焦点、Escape、Focus Trap 与关闭后焦点恢复。

### 10.4 模型与凭证管理

- Provider/Catalog 只展示服务端公开白名单字段；ModelConnection DTO 排除 Endpoint、Credential ID、API Key、Metadata、Adapter 和 Provider 原始响应；
- USER Connection 由当前 Owner 管理；TEAM Connection 对活动成员可见，写操作要求 `provider:manage` 界面权限并由服务端 `PROVIDER_MANAGE` 最终授权；ORGANIZATION 管理入口只向平台管理员展示；
- 创建和轮换 Key 只存在于 Dialog 局部状态。未修改输入的失败重试复用 Idempotency-Key，输入变化换 Key，成功、关闭、Escape、Scope 切换和卸载立即清空；
- 验证、轮换、停用和撤销使用详情强 ETag、当前 Credential Version 和 Idempotency-Key；409/412 回读权威详情；
- 撤销只接受稳定原因枚举并要求不可恢复确认；SUSPENDED 在 activate API 交付前不显示伪恢复按钮；
- 健康只显示稳定状态与失败码。Command Receipt 的 Correlation ID 是后续统一 Audit 页入口，不冒充完整审计时间线；
- 桌面使用目录、卡片和内嵌详情，移动端使用单列与 Bottom Sheet；动态 Map Resource 必须通过 reactive proxy 修改，避免已挂载视图停留在 Loading。

### 10.5 Task 委托与模型预检

- Agent 下拉框只呈现当前责任链允许的 AgentProfile；前端选择不修改 ResponsibilityAssignment；
- “当前配置”必须在 Preflight 后转换为精确 Revision 再提交，避免配置在预检与创建之间漂移；
- Preflight Cache Key 包含 WorkProject、WorkItem、AgentProfile 和 Revision，Scope 或输入变化取消旧请求并清空结果；
- Preflight 失败按稳定 `reason` 映射无敏感信息的修复提示；无 Team Binding、默认缺失/歧义、Owner 离队、责任变化、Agent/Principal 不可用均失败关闭；
- 模型来源与成本主体分别表达。Connection Owner Type 是模型来源事实，Billing Subject 只在服务端公开后展示；
- Conversation Mode 与 Control Mode 复用同一组件、Task Gateway、Task Store、CodingTarget 表单和草稿协议，不维护两套委托状态机。

### 10.6 GitHub Delivery 与 ActionBundle

- GitHub Connection、ProviderBinding 与 Repository Catalog 是三层独立选择；Repository 必须来自当前 Connection 的 DELIVERABLE Catalog；
- Authorization Health 同时展示 Connection、Grant、Credential、Profile、Webhook、RateLimit 与可交付仓库摘要，浏览器不接触 Secret 或 Provider 原始错误；
- Plan 前必须存在当前 `APPROVED` ReviewDecision 与同选择的 Remote Preflight；Review Approval 与 Action Confirmation 是两次独立的人类决策；
- Bundle 详情保留强 ETag，Confirmation 发送完整 Digest；Digest、Version 或当前事实变化后旧命令失败关闭并重新规划；
- Push/PR 独立展示 Dispatch、Receipt 和 ExternalResult，部分成功、UNKNOWN、RECONCILING 与 MANUAL_REVIEW 不折叠为一个布尔状态；
- 同键重试复用原请求，成功后回读权威事实；CommandReceipt、Webhook 和 Timeline Event 不直接构造业务结果；
- 外链只允许无凭证 `https:`，公开 DTO 未提供 URL 时仅展示安全 Hash，不猜测 PR 地址。

## 11. 分阶段落地

| 里程碑 | 前端重点 |
|---|---|
| M0 | Design Token、基础组件、AppShell、Router、API Client、Histoire、Vitest、Playwright 和截图基线 |
| M1 | Team/WorkProject、WorkItem List/Board、责任链、详情与传统管理闭环 |
| M2 | Conversation Mode、TaskIntent、对话卡片及与 WorkItem 的双向跳转 |
| M3 | Task 列表、Task Timeline、Pause/Resume/Cancel、断线与 Cursor 恢复 |
| M4 | Execution Studio、Diff、只读命令与日志证据、测试证据、Artifact 和 Agent Presence |
| M5 | Personal Agent 模型选择、团队/组织模型与凭证管理、Review Gate、Confirmation、Action Receipt、GitHub Draft PR 交付链 |
| M6 | Team Pulse、Inbox、Activity、Audit、Usage、风险与飞书通知状态 |

每个里程碑同步交付 Conversation Mode 与 Control Mode 中与该能力相关的入口，避免形成只能对话或只能管理的孤立功能。
