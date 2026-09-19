# M9 后：全流程使用体验与竞品对照 Review

日期：2026-09-19。对象：当前 CrewScope 实现，重点是首次使用、操作顺序、交互、展示，以及支撑这些体验的后端逻辑。

计划承接：[M9b：核心流程与使用体验收口](../plans/M9b-核心流程与使用体验收口.md)，15 个主线工作包逐项覆盖 R01–R43（首轮 17 项，§10 产品细查 15 项，§11 菜单逐操作 7 项，§12 迁移体验 4 项）。本文保留审查时的证据与行号；后续文档整理可能改变行号，不能把计划当作修复结果。

验收方式调整：按用户要求取消真人招募、访谈、理解度及迁移意愿门槛，改为自动化/编码代理执行的固定浏览器场景；历史 M9 真人测评要求不再由 M9b 继承。真实后端、外部集成授权及业务人工审批规则仍保留。

## 1. 结论与审查边界

**M9 改好了不少基础组件和局部操作，但尚未把产品串成一条足够简单、可靠的新用户路径。当前不能认为 CrewScope 的前端比 Multica 或 Vibe Kanban 更好用。**

问题不只在视觉层：默认 HTTP 部署与浏览器 API 不兼容、工作项编号由前端猜测、GitHub 授权绑定没有页面创建入口，都会直接影响主流程。另有并发请求串状态、创建后猜测目标对象、重试命令键不稳定等正确性问题。仅改文案、隐藏几个字段，不能解决这些问题。

建议先完成一次“核心流程收口”，再扩大 Agent 智能能力。保留责任链、权限校验、版本固定、审查与外部副作用确认；把这些机制从用户必懂的操作步骤，改成系统默认处理、必要时解释的能力。

### 1.1 本次基线

| 对象 | 本地版本 | 用途 |
| --- | --- | --- |
| CrewScope | `aa82f11`，加当前工作区已有修改 | 实际审查对象，包含近期部署简化与文档更新 |
| Multica | `8c4f4328f` | 对照任务创建、Agent 指派、引导、草稿与共享工作流 |
| Vibe Kanban | `d5cbb5380` | 对照 Coding 工作区、创建并启动、Diff、后续对话与 PR |

竞品结论来自这些本地版本的源码与已有截图，不代表对其线上最新版做了实机验收。两者运行架构、部署前提与 CrewScope 不同：本地 CLI/runtime 的配置负担不能忽略，也不能据此要求 CrewScope 取消服务端的安全边界。

本轮覆盖主用户路径的页面、关键组件、store/gateway、相关后端命令与配置，并复核已有桌面/窄屏截图。**不是整个仓库逐行的安全、性能或所有故障恢复审计。**浏览器连接因认证令牌不可用，未进行当前实例的实机点击验收；未启动之前已停用的服务、未调用真实模型、未向 GitHub 写入。

审查之后按用户要求形成 M9b，并同步计划索引、后续依赖及现状说明；本轮未修改业务实现或部署配置。

### 1.2 M9 已有成果，应继续保留

- 默认根路由已到 Today；页面标题、导航状态、搜索与深链接已有实现。
- 人员选择已大量替代 UUID 输入；工作项状态操作、批量操作、空状态与错误恢复比旧版完整。
- 设计 token、表单约束、焦点管理、移动端导航、可调整面板已有基础。
- Coding Diff、高亮、行级评论、证据定位、任务恢复状态已存在，不应再按“没有 Diff/Review”描述现状。
- 后端责任快照、成员审批、版本固定、幂等回执及外部结果不确定时的对账，是值得保留的基础。

**但“UUID 输入为零”不等于“没有要求用户处理唯一标识”；“组件都存在”也不等于“空环境能顺利走通”。**

## 2. 问题总表

P1：核心可用性阻断，应优先修复。P2：重要正确性、产品流程或使用效率问题，应进入本轮收口。下表不把设计建议伪装成已经实机复现的缺陷。

| 编号 | 优先级 | 问题 | 证据性质 | 主要改动层 |
| --- | --- | --- | --- | --- |
| R01 | P1 | 远端 HTTP 下直接使用 `crypto.randomUUID()` | 代码确认；缺失 API 条件模拟复现，未实机 HTTP 验收 | 前端、部署验收 |
| R02 | P1 | 工作项 Key 必填，且用当前列表最大值加一 | 代码确认 | 前后端 |
| R03 | P1 | GitHub 导入依赖的 ProviderBinding 无页面创建入口 | 前后端路径确认 | 前后端 |
| R04 | P2 | GitHub 连接切换时旧响应覆盖新选择 | 提取实际函数，乱序响应模拟复现 | 前端 |
| R05 | P2 | 创建对话后按列表差集/标题猜目标 | 提取实际函数，同名并发模拟复现 | 前后端 |
| R06 | P2 | 部分创建重试每次换幂等键 | 代码确认 | 前端、接口验收 |
| R07 | P2 | 项目、仓库、GitHub 配置仍要求过多技术字段 | 页面与接口确认，含设计判断 | 前后端 |
| R08 | P2 | 页面无法完整接入自定义模型服务 | 当前默认目录与管理接口确认 | 后端配置、前端 |
| R09 | P2 | Coding 构建方案实际只内置 Maven/Java 17 | 当前配置确认 | 后端、前端、说明 |
| R10 | P2 | 首次引导未按能力就绪推进，配置动作落点不准 | 页面路由确认 | 前后端 |
| R11 | P2 | 指派、委托、Coding 配置拆成多个用户步骤 | 页面确认，产品设计判断 | 前后端 |
| R12 | P2 | 页面展示顺序优先展示系统结构而非用户任务 | 模板与已有截图确认，设计判断 | 前端 |
| R13 | P2 | 审查与 PR 确认要求理解过多内部机制 | 页面确认，设计判断 | 前后端 |
| R14 | P2 | 对话草稿跨页面丢失，附件/Slash 的交互承诺不足 | 代码确认 | 前端，附件需后端 |
| R15 | P2 | 团队成员加入之后缺少撤权与角色管理闭环 | 接口、页面、M11 计划确认 | 前后端 |
| R16 | P2 | 窄屏首屏操作密度过高，排序只作用于已加载集合 | 模板/截图确认，设计判断 | 前后端 |
| R17 | P2 | 测试和完成描述不能证明真实首用路径成立 | 本轮测试、静态门禁、Release Gate 对照 | 测试、文档 |

## 3. 需要优先修复的正确性问题

### R01：HTTP 部署可启动，但常用页面可能无法提交

**触发条件**：按 README 用远端 `http://服务器地址:8080`，通过普通非回环 HTTP 来源访问。`crypto.randomUUID()` 通常只在安全上下文提供；`localhost` 的特殊待遇会掩盖该问题。

证据：

- [README](../../README.md) 第 118 行明确给出远端 HTTP 访问方式。
- [RegisterPage.vue](../../crewscope-web/src/pages/RegisterPage.vue) 第 162 行在 `try` 外调用 `window.crypto.randomUUID()`，失败甚至不会走现有注册错误呈现。
- onboarding、conversation、workitem 以及多处配置命令同样直接调用；未找到统一兼容封装。
- 将实际注册 UUID 语句放在“有 crypto、无 randomUUID”的环境执行，得到 `TypeError: window.crypto.randomUUID is not a function`。这是条件模拟，不是远端浏览器实测。

建议：统一安全随机 ID 工具，优先 `randomUUID`，缺失时使用 `getRandomValues` 生成标准 UUID；不能以 `Math.random` 代替。错误统一进入可见反馈。保留可选 TLS，不应为绕过前端兼容问题重新把证书设为部署前置条件。

验收：非 localhost HTTP 下，注册、创建团队、对话、工作项、配置保存均可提交。HTTP 兼容不等于传输安全，公网传输敏感内容仍应建议 HTTPS，但说明建议与硬性要求的区别。

### R02：用户仍需要处理工作项唯一 Key，而且默认编号会冲突

证据：

- [WorkItemCreateDialog.vue](../../crewscope-web/src/components/domain/WorkItemCreateDialog.vue) 第 37–42、118–120 行：Key 参与有效性判断，并且作为可编辑输入展示。
- [WorkPage.vue](../../crewscope-web/src/pages/WorkPage.vue) 第 763–770 行：从 `workStore.state.items` 中取最大后缀加一。
- [WorkItemCommandService.java](../../crewscope-application/src/main/java/io/crewscope/application/workitem/WorkItemCommandService.java) 第 145、203–213 行：接受客户端 Key，锁项目后仅检查冲突。
- 对话提案确认路径的 [TaskIntentConfirmationService.java](../../crewscope-application/src/main/java/io/crewscope/application/conversation/TaskIntentConfirmationService.java) 第 243 行已经调用 `workItemRepository.nextKey(...)`。

影响：已加载列表可能只是筛选结果或一页数据；两个用户也可能得到相同默认值。数据库拒绝重复能保护数据，但用户会遇到“填写内容正确却创建失败”，还被迫理解编号规则。

建议：普通创建不再要求 Key，统一使用服务端事务内编号分配。复用已有分配规则与项目锁，不再造第二套。幂等重放返回原工作项，不再分配新号。显式编号若为数据导入所必需，应单独放在有权限的导入能力中。

验收：用户只填标题也能创建；带筛选、分页未加载完、两个用户并发、提交响应丢失重试时，编号都由系统保证。编号可以只读展示和复制，不必隐藏用户有用的 `CREW-123`。

### R03：GitHub 导入的前置授权步骤没有接通

证据：

- [GitHubSettingsPage.vue](../../crewscope-web/src/pages/GitHubSettingsPage.vue) 第 88、165–173、408 行要求 ACTIVE 且带 Grant 的绑定，否则不能导入。
- [delivery/gateway.ts](../../crewscope-web/src/domains/delivery/gateway.ts) 只有读取 `listBindings`，没有创建绑定的方法；生产页面未发现调用相应 POST 的入口。生成的 OpenAPI 路径声明不等于页面已接线。
- [GitHubConnectionController.java](../../crewscope-server/src/main/java/io/crewscope/server/api/GitHubConnectionController.java) 第 136–157 行已有创建绑定 API。
- [GitHubConnectionApplicationService.java](../../crewscope-application/src/main/java/io/crewscope/application/github/GitHubConnectionApplicationService.java) 第 219–256 行创建的是 Credential、Connection、Grant；Binding 由独立 `bind` 命令创建。
- [GitHubRepositoryImportAuthorizationService.java](../../crewscope-application/src/main/java/io/crewscope/application/github/GitHubRepositoryImportAuthorizationService.java) 第 89–105 行确实拒绝没有 ProviderBinding 的导入。

同时，GitHub 页面提示去仓库设置创建 **RepositoryBinding**，但导入所缺的是 **ProviderBinding**。仓库设置管理受管仓库绑定，不能代替前者；从空环境出发会被引向错误前置步骤。

建议：在明确授权范围的前提下，串成“连接 GitHub → 验证身份 → 选择仓库与授权用途 → 建立授权绑定 → 导入 → 关联当前项目”。现有实体可保留，由应用服务编排并为中途失败提供继续入口；不要通过跳过授权检查来修页面。

验收：只有一个新团队、无预置绑定、无需 curl/改库/复制 ID，能通过页面导入首个仓库。外部授权失败可重试，不能残留用户无从处置的半完成状态。

### R04：快速切换 GitHub 连接会显示错连接的仓库

[GitHubSettingsPage.vue](../../crewscope-web/src/pages/GitHubSettingsPage.vue) 第 101–143 行没有请求代次或取消保护。选择 A 后再选 B，B 先返回、A 后返回时，`selectedId` 保持 B，但仓库、健康和 Binding 被 A 的响应覆盖。后续 await 还读取实时变化的 `scope.value`，切 Team 时也有上下文混用风险。

本轮提取实际 `selectConnection` 函数执行受控异步模拟，结果为：`selectedId=B`、`repositories=A/repo`、`bindings=A`。**这证明 UI 状态串线，不等于已证明后端跨团队越权。**

建议：统一捕获 organization/team/connection/project 上下文；取消旧请求，并在每次提交状态前校验请求代次。导入任务轮询和创建回执也需做同类检查。复用 conversation 等 store 已有的隔离模式。

验收：A→B、Team A→B、打开导入后切项目、离开页面四类乱序响应都不能污染当前状态。

### R05：创建对话后不应从列表里猜新建对象

[conversation/store.ts](../../crewscope-web/src/domains/conversation/store.ts) 第 188–205 行忽略创建结果，刷新列表后用“新 ID 且同标题”，再退化为“任意新 ID”来选择对话。

[CommandReceiptResponse.java](../../crewscope-server/src/main/java/io/crewscope/server/api/CommandReceiptResponse.java) 第 11–15 行只有命令、事件、版本、关联 ID，不含资源定位。前端因此无法可靠地确定创建结果。

同名并发创建时可能打开另一条对话；读侧尚未收敛时可能找不到自己的新对话。受控模拟中，新列表包含两条同名对话且其他对话排在前面，实际函数返回了 `other-created` 而非 `mine-created`。

建议：创建 API 返回安全的结果资源 ID/Location，或提供按命令 ID 查询结果的正式接口。首次执行和幂等重放返回同一定位；读侧延迟应显示“创建成功，正在准备”，而非猜测列表。

验收：并发同名、空列表、列表分页、投影延迟、响应重放下都精确打开本次资源。不要强制标题唯一来规避设计问题。

### R06：幂等能力没有一致传递到用户的重试操作

[workitem/store.ts](../../crewscope-web/src/domains/workitem/store.ts) 第 255–273 行每次 `create` 都生成新键；[conversation/store.ts](../../crewscope-web/src/domains/conversation/store.ts) 第 195 行和 [GitHubSettingsPage.vue](../../crewscope-web/src/pages/GitHubSettingsPage.vue) 第 173、235 行也有相同模式。

如果服务端已经提交但响应丢失，再点击提交并非原命令重试。工作项可能变成 Key 冲突，对话等对象可能重复创建。

不是所有模块都有此问题：消息发送保留 pending 的原键；项目创建、部分任务/交付操作也已有专门重试逻辑，应复用这些正确模式。

建议：把“一个用户意图”的 payload、命令键、提交状态、回执作为统一对象；不确定失败时保留原请求，明确新建或修改 payload 时才产生新键。用户按钮只需“重试”或“查看已创建内容”，不必解释“使用原 Idempotency-Key”。

## 4. 首次使用与配置体验

### R07：除了工作项，还残留不合理的技术字段负担

| 页面 | 当前要求 | 建议 |
| --- | --- | --- |
| 创建项目 | 先填 2–10 位大写 Key、等待可用性检查，再填名称 | 名称优先；自动建议短代号并由服务端处理冲突，高级选项允许主动修改 |
| GitHub 导入 | 可编辑且必填 Repository Key，默认只取仓库短名 | 主展示 `owner/repo`；内部标识自动分配，用外部稳定 ID 去重，避免同名仓库冲突 |
| GitHub 连接 | Account ID、Token、Repository Allowlist；界面称 OAuth/App | 标准授权/安装流程或身份自动解析；手动 Token 作为明确标注的高级方式 |

证据：[WorkProjectCreateDialog.vue](../../crewscope-web/src/components/domain/WorkProjectCreateDialog.vue) 第 147–167 行；[GitHubSettingsPage.vue](../../crewscope-web/src/pages/GitHubSettingsPage.vue) 第 150、215–235、365–381、411 行。

注意：当前有权限边界，不应为减少字段而自动扩大仓库授权。应让用户选择可读的仓库与授权用途，系统处理 ID、Grant 和 Binding。这里确认的是当前页面并非标准 OAuth/App 授权交互，不据此断言整个服务端完全没有相关协议代码。

### R08：模型连接页更像凭据录入，而非完整的模型服务接入

当前默认平台目录由 [DefaultPlatformModelCatalogInitializer.java](../../crewscope-application/src/main/java/io/crewscope/application/model/DefaultPlatformModelCatalogInitializer.java) 第 32–35、67–75 行初始化为 DeepSeek 指定模型与固定官方 Endpoint。

[ModelCatalogController.java](../../crewscope-server/src/main/java/io/crewscope/server/api/ModelCatalogController.java) 只提供列表读取；[ModelCredentialDialog.vue](../../crewscope-web/src/components/domain/ModelCredentialDialog.vue) 选择已有 Provider/Catalog、录入 API Key，没有创建自定义 Base URL/模型目录的完整流程。

因此，普通部署者不能仅通过当前页面把已有 New API 代理或其他兼容 Endpoint 加入动态模型目录。仓库确实存在 `AGENTSCOPE_OPENAI_BASE_URL` 等 starter 配置及 compatible adapter，**不能误说后端完全不支持自定义地址**；但这些配置不等于当前按 Agent 锁定配置版本、按连接动态装配的页面管理链路已打通。

建议：管理员配置一次“连接名称、API 地址、密钥、可用模型”，支持测试连接与能力识别；成员只选可用模型。自定义私有代理需显式允许的网络范围、重定向与凭据转发约束，不能让任意成员向任意内网地址发起探测。未知价格显示“未配置/估算不可用”，不应被伪装成准确账单；预算策略需另行明确。

### R09：通用 Coding 的使用承诺大于当前构建方案范围

[TaskApplicationConfiguration.java](../../crewscope-server/src/main/java/io/crewscope/server/config/application/TaskApplicationConfiguration.java) 第 130–164 行只注册 `maven-java-17`，命令为 compile/test/verify；没有在当前页面看到构建方案创建能力。[CodingTargetFormSection.vue](../../crewscope-web/src/components/domain/CodingTargetFormSection.vue) 第 198–208 行只能选现有方案、手填 Ref/路径并预检。

README 第 120 行“项目构建配置在页面中按需填写”容易被理解成可以配置项目真正需要的构建方式。对于 Node、Python、Gradle、多模块或非 Java 17 仓库，这不是一个通用闭环。

建议先准确声明支持范围；然后按需求补仓库栈识别、项目级默认构建方案、可版本化的受控命令配置。默认分支从仓库读取，构建目录和允许修改范围可预填，但用户能确认。不能为简化操作取消命令边界、沙箱隔离或镜像版本固定。

### R10：引导应围绕用户目标推进，而不是让用户在设置页间找入口

证据：

- [OnboardingPage.vue](../../crewscope-web/src/pages/OnboardingPage.vue) 第 150–157 行完成后进入 conversation，未按模型就绪区分下一步。
- [RegisterPage.vue](../../crewscope-web/src/pages/RegisterPage.vue) 第 178 行也存在直接 conversation 落点；不能仅因根路由已改 Today 就认为所有首次进入路径一致。
- [SetupPage.vue](../../crewscope-web/src/pages/SetupPage.vue) 第 95–101 行把“创建项目”指向 Today，把“导入仓库”指向 repository-settings，而实际 GitHub 导入界面在 github-settings。
- 同一页同时展示必需能力、配置健康四项和完整能力清单；对新用户而言部分解释重复，且含较多领域词。

建议：第一步选择“先与 Agent 对话”或“让 Agent 修改仓库”。对话只补模型等必要项；Coding 才补仓库、构建与交付授权。使用后端 readiness 的事实，但返回能执行的动作与准确目标，不只是领域页面名称。跳转保存草稿和 returnTo，完成配置后返回原任务。

验收：空环境无需读架构文档或手调 API，即可得到第一条真实回复；也能独立完成第一个 Coding 任务。就绪状态必须代表当前目标能执行，而非只有对应实体已存在。

## 5. 核心工作流、展示与操作顺序

### R11：从“交给 Agent”到执行仍有不必要的手动拆分

当前路径大致是：创建 WorkItem → 在责任链分配 Agent → 委托时再次选择责任链 Agent 与配置版本 → 决定是否 Coding → 选择仓库/Ref/路径/构建方案 → 手动预检 → 创建 Task → 在另一个任务区域观察。

证据：[DelegateToAgentDialog.vue](../../crewscope-web/src/components/domain/DelegateToAgentDialog.vue) 第 285–330 行；[WorkItemDetailDrawer.vue](../../crewscope-web/src/components/domain/WorkItemDetailDrawer.vue) 的责任链与委托入口；[TaskIntentCard.vue](../../crewscope-web/src/components/domain/TaskIntentCard.vue) 第 187 行确认提案后仍需进入 Coding 委托表单。

这套领域拆分对后端有价值，但普通用户的目标是“把这件事交给某个 Agent”。不应要求先学习 WorkItem、Task、Attempt、责任事实与配置修订之间的关系。

建议：

1. 对用户以工作项为主对象，包含“对话、执行、改动、验收、历史”；Task/Attempt 保留为内部执行记录。
2. “交给 Agent”直接选择有权限的候选 Agent，默认带入项目仓库、可用配置与构建方案。
3. 需要新增责任分配时明确告知，并由后端在权限检查后原子处理分配与启动，或返回可恢复的结果；不能静默覆盖别人已承担的责任。
4. 默认版本由系统固定并展示简明摘要；高级选项展开后才显示修订、策略等细节。
5. 看板卡片直接体现运行中、等我回答、等我审查、失败可重试，不要求上下两个列表手动对应。

### R12：展示优先级仍偏系统说明，核心内容被挤压

已查看现有基线截图及对应模板，主要不是颜色问题，而是信息顺序问题：

- **对话**：关联工作项、关联任务默认展开并排在消息历史之前。[ConversationPage.vue](../../crewscope-web/src/pages/ConversationPage.vue) 第 935–988 行。已有桌面截图中两块关联卡占据主要聊天视区，消息不是首屏焦点；左右栏默认也消耗大量宽度。虽可折叠，默认状态仍需优化。
- **Agent 配置**：完整配置大表单很长，主要参数、连接、策略、历史和对象列表的优先级不够清楚。应先选 Agent，再显示模型/角色/状态等常用项，高级参数按需展开。
- **任务详情**：Execution Studio、Diff、证据、Review、交付及运行时事实纵向叠加。[TaskDetailDrawer.vue](../../crewscope-web/src/components/domain/TaskDetailDrawer.vue) 第 261–478 行。组件齐全，但难在一个视区完成理解与动作。
- **全局导航**：对话/工作台模式入口、左侧各业务页、各设置页与设置内导航并存；配置项占据主导航较多空间。新用户难区分日常工作与管理员维护。

建议的顺序：

| 场景 | 首屏必须看见 | 次级信息 |
| --- | --- | --- |
| Today | 需要我处理的事、阻塞原因、下一步按钮 | 全队统计、配置诊断 |
| 对话 | 最新消息、输入框、当前待确认事项 | 关联对象摘要、参与者列表 |
| 工作项 | 目标、负责人、Agent 进度、下一步 | 历史、内部执行记录 |
| 执行工作区 | 当前步骤、对话/输出、Diff 或测试结果 | 沙箱、配置版本、哈希、完整事件 |
| 设置 | 当前对象、常用配置、是否生效 | 参数边界、历史修订、诊断 |

建议将日常一级入口收敛到“今日、工作、对话”，Inbox 可聚合为待我处理入口；搜索保留全局快捷入口；团队管理与设置归组。运维/审计按角色可达，但不应支配普通成员首页。

### R13：审查与发布应让用户确认影响，而不是确认 Digest

[ActionDeliveryWorkbench.vue](../../crewscope-web/src/components/domain/ActionDeliveryWorkbench.vue) 第 285–309 行要求选择 Connection、Team Binding、Repository，并显式同步/预检；第 397–398 行要求用户确认完整 Digest。[ReviewWorkbench.vue](../../crewscope-web/src/components/domain/ReviewWorkbench.vue) 同时展示较多 Context、Hash、Gate 等概念。

版本绑定和防漂移是正确的；让用户检查哈希不能替代对实际变更的理解。

建议：审查默认展示目标是否达成、变更文件、测试结论、风险、阻塞项以及“批准/要求修改”。“创建草稿 PR”默认从当前任务带出仓库和分支，生成可编辑标题与说明；后台完成授权预检和动作规划，确认界面清楚展示“将推送哪个分支、在哪个仓库创建什么 PR、是否合并”。

执行仍绑定原版本、原差异和授权快照，事实变化必须重新确认。失败与外部结果不确定要分开表达；保留查询与对账，不以“一键”之名重复外部写入。内部摘要、Receipt、Digest 放入“技术详情”。

### R14：对话草稿和工具按钮尚未达到可靠编辑器体验

- [ConversationPage.vue](../../crewscope-web/src/pages/ConversationPage.vue) 第 71、117–119 行草稿是组件内 Map；[App.vue](../../crewscope-web/src/App.vue) 使用普通 RouterView。组件卸载后草稿不会保留，也未见对话页离开保护。页面内切换会话可以保留，不能混同为跨页面/刷新可恢复。
- [ConversationComposer.vue](../../crewscope-web/src/components/domain/ConversationComposer.vue) 第 86 行附件按钮在正常编辑时可点击，但没有处理动作；“即将支持”只在 aria-label 中，视觉用户看见的是“附件”。
- 同文件第 58–61、87 行 Slash 操作只是插入 `/`，没有可发现的命令选择与参数引导。不能把它呈现成已经完整支持的命令工具。

建议：按账号/团队/会话保存草稿，登出清理并注意共享设备隐私；发送失败保留内容，切换设置后可继续。附件未实现前隐藏或明确禁用并显示原因；实现后支持上传状态、失败重试和删除。Slash 若暂不提供命令体系就去掉入口，不做“可点击但没作用”的承诺。

现有输入法 composing 判断、消息失败重试和部分草稿恢复已经存在，应保留，而不是重写掉。

### R15：成员生命周期是团队可用性，不宜长期放在后面

[TeamController.java](../../crewscope-server/src/main/java/io/crewscope/server/api/TeamController.java) 当前有创建、成员添加/列表、初始化等接口，没有成员角色变更、移除/停用的写侧闭环；[TeamMembersPage.vue](../../crewscope-web/src/pages/TeamMembersPage.vue) 主要是列表、重新加入和邀请。

审查时 [M11 计划](../plans/M11-协作规模化与开放生态.md) 已明确记录该缺口。因此这是已知未交付能力，不是把 M9 已完成的成员展示改进再次判为失败。形成 M9b 后，基础实现已前移至 M9b-A07，M11 只承接未来新通道撤权，当前仍未实现。

对真实团队，成员离开后不能正常撤权，是比多人光标等增强能力更基础的需求。建议将最小的角色调整、停用/移除、最后 Owner 保护与责任交接提前。必须同时处理 Team 范围授权、实时连接、在途任务及未完成责任，不能只从名单删一行，也不建议用改库作为正常运维方式。

成员页的“添加已有用户”说明仍写 Principal ID，但控件已经是重新加入候选下拉。标题、说明和实际能力也应同步，优先突出“邀请新成员”，避免误导。

### R16：移动端已经可达，但首屏效率仍不足；排序语义需要继续收口

现有 `work-board-narrow` 基线中，页头、筛选、视图切换和排序占据首屏大部分高度；看板需要横向移动，Agent Tasks 又在更下方。**不能说移动端没有入口，但“能点到”不等于“常用动作顺手”。**

建议窄屏默认列表，保留当前状态筛选与一个主要动作，其余筛选折叠；工作项卡片直接显示下一步和执行状态。复杂 Diff 不强行挤成桌面多栏，应切换为文件列表/单栏差异/固定审查操作。

现有排序已明确提示只作用于已加载内容，这不是隐瞒全局排序的 bug；但用户点“优先级”通常希望看到项目里最紧急的事项，而非当前页排序。建议后端提供稳定排序与 cursor 契约；如果暂不支持，应避免让局部排序主导“优先处理”的判断。

## 6. 与 Multica、Vibe Kanban 的实质比较

比较的是可见用户路径，不使用主观分数或未经测试的耗时数字。

| 维度 | CrewScope 当前 | Multica 本地实现 | Vibe Kanban 本地实现 | 判断 |
| --- | --- | --- | --- | --- |
| 第一次得到结果 | 创建团队后仍需模型、Agent 等多页配置 | 引导组织 workspace/runtime，连接后进入实际协作上下文 | 已配置 coding agent 的前提下，选择 repo/执行器并描述目标 | CrewScope 缺目标导向的连续引导；竞品也有运行前提 |
| 创建工作 | 手工表单要求 Key；对话提案还需继续配置 | 普通创建提交标题/描述等；快速创建直接提交 prompt + agent/squad | 从首条描述派生名称，创建并启动 workspace | CrewScope 没有输入效率优势 |
| 指派与启动 | 责任分配和委托启动分开 | 快速创建以 Agent/Squad 为执行入口 | create-and-start 接口直接创建执行上下文 | 可以保留内部责任实体，但合并用户操作 |
| 执行中补充上下文 | 会话、工作项、任务详情有关联但分散 | issue 与 Agent 协作围绕同一工作对象 | 工作区内持续对话、查看代码改动 | CrewScope 需要统一工作区 |
| Diff/审查 | 已有 Diff、语法高亮、行评论和人审 | 不据此认定没有审查/审批能力 | 对话、Changes、Preview、Git 侧栏同一布局 | CrewScope 主要差距是可发现性与布局，不是缺组件 |
| PR | 强证据与授权确认，但内部字段负担重 | 本轮不对其完整 PR 闭环作超出取证范围的排名 | PR 对话框支持分支选择、标题/说明初始化 | CrewScope 应借鉴默认值与业务确认方式 |
| 草稿与默认值 | 部分页面有，部分仅局部内存 | 创建面板跨模式共享草稿并防止旧成功回调误清草稿 | 创建草稿、repo 默认值、成功后清理 | CrewScope 一致性不足 |
| 责任与治理 | 显式责任链、配置固定、成员 Gate、证据和外部对账 | 也有权限、审批、运行管理等能力 | 重点是开发执行工作区，也有相关控制能力 | CrewScope 有差异化基础，不能宣传为竞品完全没有治理 |

### 6.1 竞品取证位置

以下是本地 checkout 的相对路径，便于按上面的固定版本复核；不是对线上服务的调用记录。

Multica：

- `packages/views/modals/create-issue.tsx:512`：普通创建 payload 不要求用户提供 issue 唯一编号。
- `packages/views/modals/quick-create-issue.tsx:418–468`：以 prompt、Agent/Squad 和可选项目等信息快速创建；第 541 行附近保护提交期间变化的草稿。
- `packages/views/onboarding/onboarding-flow.tsx:84–98`：runtime 已连接与跳过连接的不同落点；并非无前提运行。

Vibe Kanban：

- `packages/web-core/src/shared/components/CreateChatBoxContainer.tsx:224–268`：描述派生名称、绑定 repo/branch、创建并启动、成功后清理草稿。
- `packages/web-core/src/shared/hooks/useCreateWorkspace.ts:17–39`：调用 `createAndStart` 并更新工作区查询。
- `packages/web-core/src/pages/workspaces/WorkspacesLayout.tsx:239–303`：对话、Changes、Preview 与侧栏。
- `packages/web-core/src/shared/dialogs/command-bar/CreatePRDialog.tsx:105–170`：PR 标题/描述初始化与目标分支默认值。

### 6.2 CrewScope 怎样才能形成真正的使用优势

不建议复制竞品所有功能。优先做一个明确的优势：**用户只说清楚目标和责任，系统自动把执行、审查、交付及可追溯证据串起来。**

可验证的优势应是：

1. 比单纯 Coding 工作区更清楚“谁需要处理、为何阻塞、下一步是什么”。
2. 比分散的 issue/运行记录更容易在同一处看目标、讨论、代码、测试和交付结果。
3. 权限、模型、仓库配置由管理员一次设置，成员后续操作少填字段、少跳页。
4. 失败后能清楚区分“未提交”“已提交等待同步”“执行失败”“外部结果待确认”，并给出安全的继续方式。

这些是建议的产品目标，当前实现还不能宣称已经优于竞品。

## 7. 后端应如何配合简化

| 后端改动 | 解决的问题 | 不应破坏的约束 |
| --- | --- | --- |
| 统一编号分配与创建结果定位 | R02、R05：不手填、不猜列表 | 原子提交、幂等重放、唯一性与授权 |
| 提供面向用户意图的编排命令 | R03、R11：连接/授权/导入、分配/启动不拆成琐碎页面步骤 | 明确用户授权、可恢复状态、不能静默扩大权限 |
| 模型和构建方案受控管理 | R08、R09：可配置实际使用的服务与项目 | 凭据隔离、网络访问限制、配置版本固定、沙箱边界 |
| 就绪度返回可执行的下一步 | R10：知道缺什么、谁能改、如何继续 | 后端仍最终判权；可见性与可执行性区别对待 |
| 工作项汇总当前执行与待办 | R11、R12：不用前端拼两个列表 | WorkItem 与 Task/Attempt 可继续独立建模 |
| 按人可理解的内容规划并确认交付 | R13：确认仓库/分支/差异/PR，而非手审哈希 | 原快照绑定、过期失效、外部结果对账 |
| 成员生命周期及责任交接 | R15：团队能正常加入、调整、退出 | 最后 Owner、在途执行、订阅与 Team 范围撤权 |
| 稳定的服务端排序 | R16：真正按全量优先级/更新时间查看 | 分页稳定、筛选一致、权限过滤 |

不需要因为这些问题重写所有领域模型或改换前端框架。优先补少数应用服务、读模型和 API 合同，再收敛界面。

## 8. 推荐的目标使用路径与实施顺序

### 8.1 目标路径

普通对话：注册/加入团队 → 配好一个可用模型 → 直接输入目标 → 得到回复。无需仓库、GitHub、构建配置。

Coding 首次设置：连接模型 → 选择/导入仓库 → 确认项目构建默认值 → 就绪。模型、仓库和策略后续复用，不在每个任务重复要求填写。

日常 Coding：输入任务 → 选择 Agent（有默认值）→ 开始 → 在同一工作区观察与补充 → 审查差异/测试 → 确认创建草稿 PR。系统处理编号、内部绑定、快照与技术预检；有风险的影响仍明确展示。

### 8.2 修复批次

1. **先让主路径正确**：R01–R06，优先 HTTP、编号、GitHub 缺失绑定，再解决串状态、错误定位和重复提交。每项应先加对应失败用例。
2. **让空环境和常见项目能用**：R07–R10，补默认值、准确配置入口、模型服务接入和构建支持范围；同步纠正 README 的能力描述。
3. **让日常操作顺手**：R11–R14、R16，统一工作区、调整信息顺序、简化审查发布、可靠草稿和窄屏布局。
4. **真实团队开放前补齐**：R15 的最小撤权闭环；可以和第 2/3 批并行，不应等到复杂协作增强全部完成后再做。
5. **按真实路径验收后再结项**：R17。M10 的知识/记忆等工作不必全部停止，但不能以新能力完成代替当前首用与交付链路的修复。

后续按用户要求已将建议转成 M9b：M10 正式前置改为 M9b-Q02，成员生命周期基础提前，M11 接新实时通道；详见计划中的依赖与边界。不宣称上述建议已经实现。

## 9. R17：验证结果、门禁盲区与下一轮验收

### 9.1 本轮实际执行

| 检查 | 结果 |
| --- | --- |
| `crewscope-web`：`pnpm test` | 160 个测试文件、924 个测试全部通过 |
| `crewscope-web`：`pnpm lint` | `vue-tsc --noEmit` 通过；这不是 ESLint 全套检查 |
| `crewscope-web`：`pnpm check:q01` | 7 个检查脚本通过；UUID 输入/原生弹窗等静态计数为零 |
| 提取实际 GitHub 选择函数，控制响应顺序 | 复现 B 被选中但显示 A 的 repo/binding |
| 提取实际对话创建函数，模拟同名并发结果 | 复现选中非本次创建的对话 |
| 执行实际注册 UUID 语句，模拟缺失方法 | 复现 TypeError |
| 既有截图检查 | 桌面工作台、对话、Agent 配置、Review 与窄屏工作台；不是本轮实时截图 |

未执行：生产/远端 HTTP 浏览器验证、空数据库全链路、真实 GitHub App/PR、真实模型、双真实用户协作、后端全量测试与压测。理由是本轮为只读产品审查，且未启动服务、未取得可用浏览器连接；上述缺口不能由 mock 或已有快照替代。

### 9.2 为什么 M9 门禁通过仍能发现这些问题

[check-m9-q01.mjs](../../scripts/check-m9-q01.mjs) 的 `uuidInputs` 检查关注 input/textarea 的 placeholder 或 aria-label 中 UUID/Principal ID 字样，不会证明 WorkItem Key、Project Key 或外部 Account ID 不再要求用户处理。

[M9-Q02 Release Gate](../testing/M9-Q02-Release-Gate.md) 在审查时的空环境 Setup、双用户协作、Draft PR、首屏理解度、Diff 评论可发现性、配置路径六项均未执行。后续按用户要求取消真人测评，首屏/行评/配置等功能改由 M9b 固定浏览器场景补证；取消不等于通过，不要把本地测试 PASS 改写成这些路径也完成了。

README 可以说明 M9 功能已实现，但应同时明确“真实首用/外部交付验收未完成”和当前已知限制。构建方案、模型接入等说明必须按实际可操作能力描述，不用“按需填写”覆盖不存在的管理入口。

### 9.3 建议补充的验收清单

- [ ] 远端非回环 HTTP 完成注册、团队、对话、工作项、配置保存。
- [ ] 普通用户无需填写 UUID、幂等键、内部仓库 Key；项目短代号自动提供，允许高级编辑。
- [ ] 筛选/分页状态下以及两人并发创建工作项，服务端正确分配编号。
- [ ] 创建请求已提交但响应丢失，重试不重复创建且定位原对象。
- [ ] 同名对话并发、投影延迟下，始终打开本次创建的对象。
- [ ] 切 Team/项目/连接的乱序请求不回写旧上下文，轮询同样隔离。
- [ ] 空环境通过页面完成 GitHub 授权绑定与仓库导入，不预置 ProviderBinding。
- [ ] 使用一个明确支持的模型服务和构建栈完成真实任务；若支持自定义代理，验证其权限和网络限制。
- [ ] 首次对话不被 GitHub/构建等无关配置阻塞；配置后回到原任务和原草稿。
- [ ] 看板可直接知道当前 Agent 状态、谁需要行动及下一步，不依赖第二列表人工对应。
- [ ] 空账号场景通过正式浏览器入口完成创建、Diff 审查、要求修改；页面展示 PR 目标与实际影响，不要求输入内部实体标识。
- [ ] 390px 下完成常用操作，筛选不长期挤占首屏，关键按钮可达且不被固定栏遮挡。
- [ ] 离开页面去配置后草稿保留；登出清理敏感草稿；不存在正常可点击但无动作的占位按钮。
- [ ] 成员停用/移除后该 Team 的访问和执行权限失效，最后 Owner 保护与责任交接可验证。
- [ ] 以固定浏览器场景补齐 M9 未完成的功能路径证据，记录请求/结果、失败步骤与截图；真人测评不再继承，未执行的技术验证保持待执行。

**结项标准是用户能顺利完成真实工作，而不是把文档中的术语换成中文，或只让静态扫描继续归零。**

## 10. 第二轮：从用户使用与产品角度细查

### 10.1 方法与结论

本轮同日补充，仍基于当前工作区。按“我来做什么 → 找到入口 → 输入/选择 → 确认影响 → 看见结果 → 出错继续 → 离开后回来”检查 23 个页面及其实际使用的组件。源码、模板、样式与受控函数模拟用于验证用户会遇到什么，不把组件重构、文件长度等代码偏好算作产品问题。

**新增问题主要不是缺少按钮，而是按钮、选择、状态与用户预期不一致。** 用户看到“已查看”应能相信审的是这一版；看到已选人员应能确认交给谁；点击重试应真的重试；关闭输入不应悄悄丢工作；换账号不应看到上一个人的对象标题。这些正确性与信任问题优先于装饰性美化。

本轮未恢复服务、未新增实机浏览器验收、未执行外部写操作。额外查看的 Inbox 窄屏、登录桌面图片属于仓库历史快照，仅辅助识别布局与阅读顺序，不能代表当前版本逐像素实测。下列标“设计改进”的内容需原型及固定浏览器场景验证，无需真人；标“代码确认”的内容也不等于完成真实使用验收。

### 10.2 新增问题总表

| 编号 | 优先级 | 用户遇到的问题 | 依据 | 主责包 |
| --- | --- | --- | --- | --- |
| R18 | P1 | 换账号后，“最近访问”仍可能显示前一账号的工作标题 | 缓存与登出路径代码确认；不等于后端越权读取 | F05 |
| R19 | P1 | 长对话滚到后面可能没有消息，阅读位置不可靠 | 实际虚拟列表函数条件模拟；未浏览器复现 | F03 |
| R20 | P2 | 快捷搜索干扰搜索页，重试/续页结果不符合预期 | 调用链确认；真实 store/路由受控模拟 | F04 |
| R21 | P2 | 已选择的人看不见，想找的 Agent 可能根本没出现在候选里 | 选择器与目录分页代码确认 | F04 |
| R22 | P1 | 不同任务或新一轮改动错误沿用文件“已查看”标记 | 路径级全局存储代码确认；不是审批绕过 | F03 |
| R23 | P1 | 评论可能随切文件绑定到新上下文，未保存也会关闭输入框 | 实际提交函数受控模拟；未向后端提交 | F03 |
| R24 | P2 | 对话之外，评论/审查意见关闭后仍会丢失 | 组件局部状态与关闭/重开路径确认 | F05 |
| R25 | P2 | 不能点却看不到原因，错误页有无动作的“刷新事实” | 实际消费页面、组件参数与事件确认 | F04 |
| R26 | P2 | 批量处理不知道哪些成功，筛选后隐藏的选择可能仍参与操作 | Inbox 批处理与选择状态代码确认 | F04 |
| R27 | P2 | 中文输入、键盘选择、触屏查看完整信息不一致 | 事件处理与 Tooltip 消费路径确认；叠层需实机验收 | F04 |
| R28 | P2 | 面板拖动反向，坏偏好数据可能让工作区打不开 | 实际面板函数受控模拟 | F04 |
| R29 | P2 | 接受邀请后仍按新增项/同名猜团队，落点不确定 | 邀请完成路径代码确认 | A07 |
| R30 | P2 | “首页”、全局范围与页面筛选的含义不一致 | 路由/模板确认，含信息架构判断 | F04 |
| R31 | P2 | 普通成员想关自己的通知，却被带到管理员配置权限边界 | 路由与个人偏好入口确认 | A07 |
| R32 | P2 | 长时间打开页面后，“刚刚”“今天”可能过期仍不更新 | 时间格式化与 Today 缓存逻辑确认 | F04 |

### R18：共享设备上的“最近访问”不是普通设备偏好

用户场景：A 查看敏感工作后退出，B 在同一个浏览器登录，打开快捷搜索。

[CommandPalette.vue](../../crewscope-web/src/components/action/CommandPalette.vue) 第 33、52–60、105–136 行把对象标题、路由和 ID 写进全局 `cs.pref.device.command-recent.v1`；恢复时只校验最近动作的可用性，不重新授权最近对象。[main.ts](../../crewscope-web/src/main.ts) 的匿名态清理重置 store，但未清理这一缓存。因此旧标题可能直接显示；这里确认的是**本地标题/路径暴露风险，不声称后端允许越权访问资源**。

建议/验收：主题密度可按设备保存，工作内容必须按账号/团队隔离；登出、撤权清理或重新授权后再展示，不留下旧标题。A/B 登录、切 Team、撤权后开面板均验证。最近对象保留真实类型和稳定 ID；修复当前再次点击后重复拼接 `WORK_ITEM:` 前缀、生成重复历史的问题。归 F05，F04 消费，A07 提供撤权通知。

### R19：长对话首先要“读得完整”，再谈虚拟化性能

[ConversationPage.vue](../../crewscope-web/src/pages/ConversationPage.vue) 第 80–84、220–234、908–1006 行将可变高度 Markdown 消息按 108px 虚拟化，且滚动容器中在消息前还有提案/关联卡。[useVirtualList.ts](../../crewscope-web/src/composables/useVirtualList.ts) 用整个容器的 `scrollTop / itemHeight` 算消息索引，没有测量真实高度、扣除前置内容或限制最大起始索引。

受控模拟：50 条消息，假定每条渲染 400px，容器高 600px、底部 `scrollTop=19400`，实际函数返回 0 条可见消息和 18468px 顶部占位。说明算法不适合当前内容形态，不是实机尺寸测量。

建议/验收：使用测量高度的方案或受限非虚拟回退；短句、长代码、表格混合，前置卡折叠、流式新增、窗口变窄、加载更早消息都不漏消息、不跳空。用户阅读旧消息时不强制跳底，回到最新有明确入口；按消息锚点恢复位置，而非盲信旧像素。归 F03，F05 负责位置数据的账号/对象隔离。

### R20：搜索应保持“我搜的是什么、结果属于谁”

[SearchPage.vue](../../crewscope-web/src/pages/SearchPage.vue) 第 40–75、121 行与 [CommandPalette.vue](../../crewscope-web/src/components/action/CommandPalette.vue) 第 65–71、109–121 行共享同一个 SearchStore：打开快捷搜索直接清空搜索页结果；关闭后原页面 query 没变化，不会自动恢复。搜索页“重试”只 replace 相同 query，真实内存路由模拟的 query watcher 触发次数为 0。用户改了输入但未提交时，加载更多使用新输入配旧 cursor。

[search/store.ts](../../crewscope-web/src/domains/search/store.ts) 第 15–31 行清空查询不作废在途请求；模拟确认清空后旧结果会重新出现。续页失败还将整页切成 error，已取得结果被模板隐藏。

建议/验收：页面搜索与快捷搜索独立会话；输入草稿与已提交条件分开；重试显式请求，续页严格使用已提交条件/cursor；失败保留结果，只重试失败页。清空、A→B→A、乱序、取消、开关面板、浏览器前进后退均不串结果。归 F04；F01 提供请求隔离规则，不依赖增加服务。

### R21：选择器要让用户确认“选中了谁”，而不只是藏起 UUID

[PrincipalPicker.vue](../../crewscope-web/src/components/domain/PrincipalPicker.vue) 第 55–61 行仅在 `modelValue` 变化时从当前 `entries` 恢复 chip；初始传入已有 ID 但候选未加载时没有显示名称，后来 entries 到达也不会重新解析。第 85–101 行只取首 20 项，再在客户端筛 `kind`，没有继续加载：前页均为成员时可能提示没有 Agent，即便后页存在。组件也没有在 Scope 改变时主动清理旧候选。

建议/验收：按已授权 ID 解析当前选择，加载/不可用/被停用明确显示，不能表现得像没选。类型过滤发生在分页前，提供搜索与续页，不要求用户事先知道准确名字。验证 20+ 混合成员/Agent、重名、长名、停用、恢复草稿、切团队以及不同权限。F04 负责完整选择体验及目录 API 的必要增量，A07 负责生命周期语义；不扩大目录可见权限。

### R22：文件“已查看”必须属于当前审查版本

[CodingDiffExplorer.vue](../../crewscope-web/src/components/domain/CodingDiffExplorer.vue) 第 58、119–121、474 行只把 `path` 写进全局 `cs.pref.diff.viewed-files.v1`。另一个仓库同名文件，或同文件生成新一轮 Diff，都会沿用该标记。

用户后果：本应重新检查的变更看起来已读，可能漏审；该标记目前不是后端审批许可，不能表述为自动绕过 Gate。

建议/验收：按账号、任务/执行、审查快照及文件内容版本记录；内容变化自动恢复待看，允许取消已看；明确“个人阅读进度，不等于批准”。跨任务同名文件、修改轮次、切账号、回看旧轮次逐项验证。归 F03，复用 F05 隔离存储合同。

### R23：评论草稿不能跟着当前文件悄悄换目标

[CodingDiffExplorer.vue](../../crewscope-web/src/components/domain/CodingDiffExplorer.vue) 第 153–183 行打开评论只保存行和 side，提交时再从**当前** `selectedFile` 和 `projection.generation` 取文件/代次。文件列表仍能切换，草稿没有绑定原文件。实际提交函数模拟：在 A 文件/第 1 代打开评论后，切到 B/第 2 代，发送参数变成 B/2。服务器可能拒绝不匹配锚点，不能据此断言已写错评论，但用户意图已经在前端丢失。

同一函数在 `onAddComment` 返回 null 时也关闭草稿；[review/store.ts](../../crewscope-web/src/domains/review/store.ts) 第 197–200 行在没有选中 Review 等条件下确实返回 null。模拟得到“关闭输入、无错误、无保存结果”。

建议/验收：打开时固定文件、行、执行、Review 和版本，切换后保留原目标或明确要求重新定位，不能静默改绑。仅收到确定成功结果才关闭；未满足审查前提不展示可正常提交的入口，失败保留文本和就地说明。A/B 同行内容、版本更新、无 Review、离线、请求晚到均测试。归 F03，F01 提供提交身份/异步隔离，F05 管草稿。

### R24：草稿承诺需覆盖评论与审查意见，不仅是聊天输入框

[WorkItemDetailDrawer.vue](../../crewscope-web/src/components/domain/WorkItemDetailDrawer.vue) 第 112–117 行将评论与关联资源放在局部 ref，关闭抽屉后销毁；[ReviewWorkbench.vue](../../crewscope-web/src/components/domain/ReviewWorkbench.vue) 第 134–142 行每次打开决策框重置 rationale；Diff 重新点评论行同样清空文本。当前未看到这些入口有与对话同等的未保存保护。

建议/验收：为工作项评论、行级评论、审查意见、委托描述和非敏感设置建立统一的离开规则：自动草稿或明确保留/放弃确认，不要求每个表单都长期存储。切对象不能继承另一对象的意见；关闭、刷新、去配置再回来、失败重试都不静默丢输入。凭据不能进入持久化草稿；清除与期限可解释。归 F05；F03/F04 集成。

### R25：错误与禁用状态必须帮人继续，不是只满足属性检查

具体证据：

- [ModelSettingsPage.vue](../../crewscope-web/src/pages/ModelSettingsPage.vue) 第 252–254 行、[RepositorySettingsPage.vue](../../crewscope-web/src/pages/RepositorySettingsPage.vue) 第 277–281 行将部分禁用原因放在 `sr-only`；有 `aria-describedby` 不代表视觉用户能看到为什么不能继续。
- [AccountPage.vue](../../crewscope-web/src/pages/AccountPage.vue) 第 73 行给 StatePanel 传 `message`，而实际组件只接收 `description`，具体错误没有按预期呈现。
- [StatePanel.vue](../../crewscope-web/src/components/feedback/StatePanel.vue) 所有 error/conflict 默认显示“刷新事实”；[InboxWorkspace.vue](../../crewscope-web/src/components/domain/InboxWorkspace.vue) 第 272 行来源解析错误没有绑定 retry，按钮可见却无恢复动作。

建议/验收：区分缺配置、无权限、离线、正在处理、冲突、失效链接、部分失败；关键原因就近可见并给一个有效下一步。只在实际接通恢复动作时显示按钮，按业务叫“重新搜索/重新加载来源/检查连接”，不统一塞“刷新事实”。成功反馈也说清保存、验证通过、执行完成的区别。归 F04，各 A 包负责真实补救动作。

### R26：批量处理要告诉用户“哪些完成，哪些还需要我处理”

[InboxPage.vue](../../crewscope-web/src/pages/InboxPage.vue) 第 188–198 行循环处理时忽略逐项 boolean，只留下共享 command 的最后状态；[InboxWorkspace.vue](../../crewscope-web/src/components/domain/InboxWorkspace.vue) 第 100–115、221–224 行选择集独立于筛选，批量按钮只因离线禁用，没有批次进行中反馈。用户切筛选后仍可能操作当前不可见的已选项，也看不到清晰的部分失败清单。

第 89–92 行“清除类型与处置筛选”连续发出两次独立路由修改，存在后一次保留旧参数的竞争，应一次提交完整筛选。现有页面已说明 Inbox 处置不改变来源，值得保留；但“标记已处理”是主按钮，真正业务操作叫“打开来源”，仍应把“去审查/去确认/去处理”前置，并明确个人收纳不等于任务完成。

建议/验收：冻结批次选择及 Scope、显示数量/范围/进度、逐项结果；只重试失败且仍获授权的项目，结果未知先查询，不重做成功项。分页保留选择与切筛选/团队清理采用明确规则；一次清筛选真的清掉两项。F04 主责，F01 负责恢复身份，A06 提供可读待办标题与目标动作；不强加批量后端大事务。

### R27：中文、键盘与触屏应能完成同一套任务

[CommandPalette.vue](../../crewscope-web/src/components/action/CommandPalette.vue) 第 76–85 行和 [PrincipalPicker.vue](../../crewscope-web/src/components/domain/PrincipalPicker.vue) 第 122–141 行处理 Enter 未检查 `isComposing`。命令面板把最近访问显示在前面，但箭头选择计数不含它们；人员下拉关闭后仍可从旧 entries 处理 Enter。用户输入中文确认候选字时不应触发选人或跳页。

[BaseTooltip.vue](../../crewscope-web/src/components/base/BaseTooltip.vue) 依赖 hover/focus，描述关系放在 wrapper，文本本身往往不能获得焦点；Search 等页面的绝对时间仅包在非焦点文本 Tooltip 中。移动端不能假设存在 hover。`useFocusTrap` 在移动菜单和命令面板都有实际消费者，但没有统一顶层弹层判断，需补叠层场景验收，不把尚未实机复现的焦点冲突写成事实。

建议/验收：IME 组合期间 Enter 只确认输入；箭头顺序与可见顺序一致，最近访问可用键盘选，列表关闭后不选隐藏项；模型/成员弹层打开、退出、返回焦点一致。触屏/键盘能看到完整时间、长名称与禁用原因，不靠悬浮才知关键事实。菜单→搜索→确认弹层只由最上层处理 Esc/Tab，关闭后回到合理位置。归 F04。

### R28：工作区布局应可预测，偏好坏了也能恢复

[ConversationPage.vue](../../crewscope-web/src/pages/ConversationPage.vue) 第 1090–1098 行的右侧分隔条复用 [useResizablePane.ts](../../crewscope-web/src/composables/useResizablePane.ts) 第 34–53 行左侧算法。受控模拟：1000px 容器向右拖 100px，右栏比例从 22% 增为 32%，对应边界反而向左移动。

[usePreference.ts](../../crewscope-web/src/composables/usePreference.ts) 默认接受任意 value，面板读取 `.ratio` 未做结构校验；存储 `{version:1,value:null}` 的模拟触发 TypeError。用户不应因为一次旧偏好/损坏存储被挡在整个工作区外。

建议/验收：左右分隔条按所在边界移动，键盘方向一致，可折叠、可重置；窄屏不露出无效拖动控件。空值、旧版本、错误类型、存储不可用时回到安全布局；跨标签更新有界，不反复写回/累积监听。归 F04；不把设备布局与 R18 的业务历史混成一类存储。

### R29：邀请成功要精确进入被邀请团队

[InvitePage.vue](../../crewscope-web/src/pages/InvitePage.vue) 第 51–66 行接受邀请后以团队列表差集、团队同名匹配、首个团队依次猜落点。在并发加入、已加入后的重放、同名团队等条件下，这不是确定性定位。问题类似 R05，但属于独立的加入团队用户路径。

建议/验收：接受结果返回当前用户获授权的目标 Team 定位，或通过正式回执查询；刷新会话未成功时显示“加入结果待同步”并继续，不重新消费邀请，也不随便送到别的团队。登录/注册中转保留邀请意图；过期、撤销、已接受、切账号各有明确说明。A07 负责结果契约，F02 接首用落点；不在匿名预览扩大敏感团队信息。

### R30：用户应始终知道“我现在在哪个范围，回首页去哪”

[AppShell.vue](../../crewscope-web/src/components/layout/AppShell.vue) 第 107、236 行把当前全部 query 复制到不同导航，且标“CrewScope 首页”的 Logo 去对话，根路由却去 Today；[TodayPage.vue](../../crewscope-web/src/pages/TodayPage.vue) 使用独立 `deskProject`，默认全部项目，与全局项目选择并非同一范围。[SearchPage.vue](../../crewscope-web/src/pages/SearchPage.vue) 标题宣称跨团队检索，但实际请求是当前团队/项目。这不是要求取消跨项目 Today，而是要清楚交代例外。

建议/验收：Logo/首页一致；顶层导航只携带目标允许的 Scope 参数，对象深链接保留明确目标，页面过滤器不串页；今日明确“当前团队全部项目”或选定项目，搜索明确实际范围。切团队/项目、去设置返回、无权限/404 返回、浏览器前进后退均可预测。普通用户优先见工作与下一步，审计/运行诊断按职责后置，保留历史链接。归 F04，复用 R10/R12，不另造导航系统。

### R31：个人通知控制与集成管理应分开

[LarkNotificationAdmin.vue](../../crewscope-web/src/components/domain/LarkNotificationAdmin.vue) 第 351–359 行有“我的通知偏好”，但唯一生产入口 [router.ts](../../crewscope-web/src/app/router.ts) 第 137–139 行要求 `providerManage`。普通成员想关闭自己的飞书通知或设置勿扰，被管理集成的权限门槛挡住。账号页没有对应入口。

建议/验收：个人偏好放账号/Inbox 可达位置，普通成员只能控制自己，管理员连接/凭据/映射/团队投递继续独立判权。复核现有 NotificationPreference API，必要时补最小 self-service 权限合同，不能只放开整页管理权限。勿扰使用可理解的中文和时区，不让关闭通知受“至少选一种类型”的无关条件阻碍。集成页按连接→健康→成员对应→投递排布；需要 App ID/open_id 的高级配置注明来源与安全用途，不将外部身份码和普通任务输入混在一起。A07 负责本人权限合同，F04 负责入口/文案，Q02 用普通成员验证。

### R32：“刚刚”“今天”和“仍在运行”要有可信的时间依据

[formatRelativeTime.ts](../../crewscope-web/src/composables/formatRelativeTime.ts) 在调用时读取 Date.now；[useRelativeTime.ts](../../crewscope-web/src/composables/useRelativeTime.ts) 实际只是重导出，没有响应式时钟。[TodayPage.vue](../../crewscope-web/src/pages/TodayPage.vue) 第 106 行 `todayStart` 为无响应式依赖的 computed，跨午夜仍可能使用昨日零点。相对时间依赖其他更新才重绘，不适合让用户据此判断等待多久。

建议/验收：共享有界时钟更新相对时间、到期与日界，后台休眠恢复时重算；绝对时间可键盘/触屏查看并注明时区。运行/连接状态区分最新观测与实时连接情况，不用绿色连线暗示任务必定在推进；离线明确最近同步时间，“草稿已保存”只在实际保存成功后出现。F04 主责，A06 提供摘要观测时间，F03 接运行呈现；不因此提前引入 M11 WebSocket。

### 10.3 全站页面覆盖与产品验收矩阵

下表覆盖当前 `src/pages` 全部 23 个页面。覆盖表示检查了入口、内容/主动作、关键状态及其相关源码，不代表逐页面实机验收通过。没有新增独立编号的页面继续纳入原问题或全站规则，不为凑数量制造缺陷。

| 页面 | 用户来这里要完成的事 | 本期重点与归属 |
| --- | --- | --- |
| Login | 登录并回到原工作/邀请 | 账号/密码反馈、过期会话返回、键盘与密码管理器；R25/R27/R29，F02/F04 |
| Register | 创建账号后开始使用 | HTTP、字段校验、邀请与注册模式、确定下一步；R01/R10/R29，F01/F02 |
| Invite | 知道加入谁并完成加入 | 目标团队、身份、角色、过期/重放和精确落点；R29，A07/F02 |
| Onboarding | 建立首个可用空间 | 名称先行、默认编号、先对话/先 Coding；R07/R10，A01/F02 |
| Setup | 知道缺什么、配完返回 | 连通性不是实体存在；角色边界、步骤可继续；R03/R08–R10/R25，F02 |
| Today | 首屏呈现当前待办、责任与主动作 | 待我操作优先、跨项目范围、时间与全量排序；R11/R12/R16/R30/R32，A06/F04 |
| Work | 创建/委托/跟进并交付工作 | 自动编号、摘要、草稿、批处理、统一审查工作区；R02/R11/R13/R22–R26，A01/A05/A06/F03/F05 |
| Conversation | 表达目标、读回复、继续协作 | 长消息、输入安全、最新消息与关联卡顺序、栏宽；R05/R14/R19/R24/R28，F03/F05 |
| Search | 快速找回明确对象 | 查询不串、重试有效、条件/分页一致；R18/R20/R27/R30，F04/F05 |
| Inbox | 分清待办，去处理而不只是收纳 | 任务标题/下一步、来源与处置区分、部分成功；R12/R25/R26，A06/F04 |
| Activity | 看变化并进入相关工作 | 人/时间/动作可读、筛选恢复、分页失败保留内容；R12/R16/R25/R27/R32，F04 |
| TeamObserver | 找团队阻塞并采取行动 | 指标口径、观测时间、可授权下钻、无数据/无权限区分；R12/R25/R30/R32，A06/F04 |
| TeamMembers | 邀请、找人、调整与交接 | 重名识别、生命周期、邀请结果、撤权反馈；R15/R21/R29，A07/F04 |
| AgentSettings | 知道用哪个 Agent、能做什么 | 先对象后配置、候选完整、配置有效性、未保存保护；R08/R12/R21/R24，A03/F04/F05 |
| ModelSettings | 接好模型并确认能用 | 常用字段/高级字段、能力/费用未知、验证反馈；R08/R25，A03/F04 |
| RepositorySettings | 选仓库并成为可运行默认值 | 无内部 Key、预检原因、栈支持、改配返回；R03/R07/R09/R25，A02/A04/F02 |
| GitHubSettings | 授权导入/恢复过期连接 | 真实身份、最小授权、连续进度/恢复；R03/R04/R07/R13，A02/F02 |
| LarkSettings | 管理通知连接并排障 | 管理与本人偏好分开、身份字段有出处、投递结果；R07/R25/R31，A07/F04 |
| Audit | 找谁做了什么并安全追溯 | 名称优先、长 ID 复制/展开、筛选/时间/导出范围；R12/R21/R27/R30/R32，F04 |
| Operations | 判断故障并按权限恢复 | 普通待办与运维诊断区分、风险影响、真实进度；R12/R25/R30/R32，F03/F04 |
| Account | 管理自己的资料、安全与偏好 | 错误可读、本人通知入口、主题密度恢复；R18/R25/R28/R31，A07/F04/F05 |
| AccessDenied | 知道缺什么权限并回到可用区域 | 已有权限说明保留，少讲内部守卫，安全保留 Scope/原目标；R25/R30，F04 |
| NotFound | 从无效链接恢复找工作 | 说明链接失效/目标不可用，不泄漏存在性；返回/搜索保留有效 Scope；R25/R30，F04 |

### 10.4 样式与展示：以可读、可找、可操作验收

继续使用 M9 的 token、语义色、密度和基础组件，不再起一轮纯换肤。旧字号/焦点/暗色对比度已修的部分不重复登记为现存缺陷。以下是 R12/R16/R25/R27/R28 的跨页验收细则，不是又一组新功能：

| 维度 | 用户可观察的完成标准 |
| --- | --- |
| 首屏层级 | 能辨认对象名称、当前状态、下一步；不先读几屏统计、筛选或内部元数据才看到内容 |
| 主次动作 | 每个任务区域有明确主操作；危险操作与普通继续操作分组，确认描述具体对象/影响；不让“标记已处理”冒充实际审批 |
| 信息密度 | 普通模式名称/状态/责任/下一步优先；英文眉题、ID、版本、Hash 下沉详情；长标题/中文/无空格路径不挤没主按钮 |
| 色彩与状态 | 浅色/深色/系统跟随、两种密度都可读；状态同时有文字，不仅靠红绿；禁用看得懂原因，加载不闪成空态 |
| 表单 | 有持久标签、单位/示例/必填说明；先告知前提，校验就近，失败不清空；自动默认值说明来源，可在适当权限下调整 |
| 溢出与浮层 | 320px 为降级下限，390/767/1100/桌面为主矩阵；200% 缩放、长名称、代码横滚、菜单边缘不挡主操作，Tooltip 不作为唯一信息渠道 |
| 移动端 | 软键盘打开后输入和提交仍可达，安全区/底栏不遮挡，列表/详情可以单栏切换；不要求手机拖拽或 hover 才能完成任务 |
| 连续性与反馈 | 刷新保留已加载内容、局部显示失败；成功、等待同步、结果未知不同；返回列表保留合理筛选/位置，切对象不带错草稿 |

### 10.5 本轮补充验证与实施边界

执行方式为 Node 读取当前 TypeScript，转译并调用实际 store/composable/函数；网络、容器尺寸与回执为受控输入，未改业务代码：

| 检查 | 本轮观察 |
| --- | --- |
| 搜索清空后旧响应到达 | 状态回到 ready，旧结果再次出现 |
| 页面结果存在时模拟快捷面板清空 | 共享结果变成 idle/null |
| Vue Router 内存路由 replace 同一查询 | 监听查询变化次数为 0，不能触发页面现有重试逻辑 |
| 长消息条件下虚拟列表 | 50 条样本在给定底部位置返回 0 条可见消息 |
| 右栏分隔条向右拖动 | 22% → 32%，与右栏边界预期相反 |
| 面板偏好 value=null | 读取 ratio 抛 TypeError |
| 打开评论后切文件/代次 | 实际提交参数使用新的 B 文件/第 2 代 |
| 评论回调返回 null | 草稿关闭且无就地错误 |

以上用于建立需要修复的反例，不能作为修复后的通过证据。已有 924 项测试结果见 §9，不能用其 PASS 抵消新发现。关闭问题时需将反例变成固定回归，并补浏览器操作与结果证据，不要求真人参与。

本轮另跑现有 `useVirtualList`、`useResizablePane`、`usePreference`、`search/store`、`PrincipalPicker`、`CodingDiffExplorer` 的 Vitest：6 个文件、24 项测试通过。这说明现有用例未覆盖上述反例，不代表问题已修复。文档校验：400 个 Markdown 文件的本地链接检查通过；`git diff --check` 通过；15 个父包无循环依赖、依赖表与图的前置关系一致、R01–R32 映射完整、23 页无遗漏。

与竞品对照的产品目标不变：减少配置往返、同处完成工作、清楚知道下一步。本轮没有重新测竞品最新版，不新增“已领先”的宣传。固定首用场景需验证入口可达、配置往返、目标定位、输入保留和失败恢复，而不只比较页面外观；不再要求真人评测。

## 11. 第三轮：菜单内逐操作审查

按用户要求，不仅保留 23 页覆盖表，还把 **15 个一级菜单、9 个配置二级菜单及全局/搜索/账号/认证入口**展开到实际操作。详见 [M9b 菜单逐操作产品 Review](M9b-菜单逐操作产品Review.md)：逐项记录查看、筛选、创建、编辑、提交、取消、批量、重试、危险确认、深链接与返回，分别标明保留、优化或缺口。共用组件不等于各页面接线已经通过。

| 编号 | 优先级 | 用户操作问题 | 主责包 |
| --- | --- | --- | --- |
| R33 | P2 | 审计导出按旧条件请求，勾选行没有实际用途 | F04 |
| R34 | P2 | 审计按人/对象筛选仍要 UUID，非首屏详情链接不能独立恢复 | F04，含必要只读 API |
| R35 | P1 | 飞书验证回执与当前选择组合，映射/轮换目标未固定 | F01，F04 集成 |
| R36 | P2 | GitHub 导入取消状态错误，轮询失败/离开后缺少原任务恢复路径 | A02，F02 集成 |
| R37 | P2 | 模型连接可停用但缺少受权重新启用命令与页面 | A03 |
| R38 | P2 | 团队观测刷新仅回读旧摘要，首次失败后缺少当前页重试入口 | F04 |
| R39 | P2 | 工作项创建后缺少修改标题、说明、优先级、标签和到期时间 | A01 |

上述证据、建议和逐操作验收在该文档 §2–§5，已纳入 M9b。新增写侧仅为基本字段编辑与既有模型连接 activate 的闭环等必要增量，不扩大为跨项目移动、删除或新通知平台。本轮仍未启动服务、未实机点击、未向外部系统写入；条件模拟不能作为修复通过证据。

## 12. 第四轮：从 Multica 用户迁移意愿出发

详见 [Multica 用户迁移体验 Review](M9b-Multica用户迁移体验Review.md)。本轮对照同一 Multica 本地提交的源码/说明与既有截图，按试用、日常创建、补充要求、成果接回、次日回访和团队推广审查。不是实时线上竞品测试，也不把更多功能等同于更愿意迁移。

| 编号 | 优先级 | 新增问题 | 主责包 |
| --- | --- | --- | --- |
| R40 | P2 | Markdown 表格结构/表头被清洗丢失，工作描述/普通评论与聊天阅读能力不一致 | F03，F04/F05 集成 |
| R41 | P2 | 临时筛选/最近访问不能替代个人命名视图与主动固定工作入口 | F04，F05 存储 |
| R42 | P2 | 普通评论、对话与执行的发送目标、触发意图和上下文版本缺少统一产品合同 | A05，F03 接线 |
| R43 | P2 | 输入框固定“预算 32,000”并非实际模型事实，字符/token/费用语义混淆 | A03，F03 接线 |

R26 另升级为可恢复的个人收纳闭环，含领域/API 必要增量，不再只以不可恢复提示关闭；R11/R13 增补可信成果摘要，R10/R17 增补单项目试用说明与固定迁移场景验证，无真人对照要求。视觉、所有菜单和微交互要求详见新 Review §4–§7，已映射 M9b；15 个父包不变但需重新估算工作量。

结论仍是当前不足以推荐全面替代。争取的优势是成果、证据、责任和下一步集中可见；以固定场景验证高频操作及展示，不把通过测试等同于真人迁移意愿。附件、CLI 订阅/环境和全量历史迁移未支持时明确限制，不宣传已兼容。
