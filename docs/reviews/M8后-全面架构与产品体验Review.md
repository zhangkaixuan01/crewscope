# CrewScope M8 后全面架构与产品体验 Review

> 部署更新（2026-09-19）：当前默认采用四服务 HTTP Compose，API 内含 Worker，Coding 使用本机 Docker Socket；自动生成 env 密钥，从源码构建。本文旧七/十服务、外部 Secret、强制 TLS/Digest、Socket Proxy 与观测栈描述仅保留历史范围，不是当前启动条件。当前操作以 [单机运维手册](../runbooks/Team-Beta单机运维手册.md) 为准。

> 评审基线：`ca8a90d`（M8 九个工作包本地实现完成，`M8-Q02` 为 `LINUX_RUNTIME_RECOVERY_PASS`）<br>
> 状态注记（2026-09-17）：本节评审时记的「待签名 Tag」状态已废弃——供应链发行统一标记为 `SUPPLY_CHAIN_RELEASE_DEFERRED`，产品开发期间不打 Tag，下文凡把正式发行作为前置的表述同样不再生效<br>
> 评审范围：`crewscope-domain` / `application` / `agentscope` / `integration` / `infrastructure` / `server` / `web` / `docs` / `deploy`<br>
> 参考基线：`vibe-kanban`（执行工作台与 Git 联动）、`multica`（多视图管理与 Agent 控制面）、`agentscope-java`（运行时能力面）<br>
> 评审角色：全栈架构 + 产品体验 + 技术项目管理<br>
> 结论：**工程质量已达发行水准，产品体验是当前唯一的结构性短板。M9 必须是前端体验里程碑。**

---

## 1. 总体评价

### 1.1 记分卡

| 维度 | 评分 | 依据 |
|---|---:|---|
| 领域建模与分层架构 | A | 24 个领域包、32 个应用包，Port/Adapter 边界清晰，`crewscope-domain` 零框架依赖 |
| 可靠性与恢复语义 | A | Outbox、Lease/Fencing、Projection Generation、Dead Letter、V26..V36 恢复边界、121 项固定故障收敛 |
| 安全与治理边界 | A | 公开字段白名单、签名 Cursor、强 ETag、Idempotency-Key、Docker Socket Proxy 隔离、固定攻击集 |
| 工程与发布治理 | A- | 579 个 Java 测试类、206 个 HTTP 端点、三层 CI、SBOM/Provenance/Cosign；正式签名 Tag 未落地 |
| 文档与决策留痕 | A | 26 个 ADR、9 个里程碑清单、24 个 Spike 记录、完整 testing 证据链 |
| **前端首屏与任务可见性** | **D** | 首页 134 行，自述"工作事实仍由各业务 API 提供"，不展示任何真实待办；服务端无按责任人过滤、无跨项目聚合 |
| **前端信息架构** | **C** | 14 项导航单层平铺，"Operate/System" 分组为永久禁用占位 |
| **前端视觉与排版** | **D** | 906 处字号声明中 743 处（82%）`≤11px`、680 处 `≤10px`、486 处 `≤9px`、43 处 `7px`，而规范正文 14px 仅 12 处；无字号/间距 Token |
| **前端交互深度** | **C-** | Cmd+K 与通知为无 handler 的装饰按钮；无全局快捷键、无拖拽、无虚拟滚动 |
| **状态流转交互** | **D** | 流转 = `<select>` 选枚举 + 点"提交流转"（七步操作）；看板按状态分列但卡片不可拖；Review 四个强弱悬殊的结论等权平铺；前端手抄了一份领域状态机（`types.ts:176`），后端 17+ 个 `ALLOWED_TRANSITIONS` 零对外暴露 |
| **前端基础组件与反馈** | **D** | `components/base/` 仅 2 个组件；Dialog 重复实现 15 次、焦点陷阱 9 次；无 Toast、无 Tooltip 组件、330 处裸 `title=`、仍用原生 `confirm()`、`<Transition>` 用量为 0 |
| **代码 Review 体验（核心场景）** | **D** | Diff 无语法高亮（无高亮库依赖）、无行号、无分栏、无行级评论，仅按 `+`/`-` 前缀着色 |
| **列表与数据操作** | **D** | 全站零排序控件、零批量选择；31 处时间全为绝对时间且 `Intl.DateTimeFormat`/`toLocaleString` 混用 |
| **上下文与偏好留存** | **D** | 22 条路由零面包屑；`localStorage.setItem` 仅 1 处，刷新即丢筛选与视图；35/36 个空状态无下一步动作 |
| **配置体验（各种配置）** | **D** | 14 个配置界面 173/212（82%）字号 `≤11px`，表单输入框 `font: 10px`、标签 9px、错误提示 8px；全站零脏态跟踪，切 Team 会 `router.replace` 静默丢弃整屏表单；参数边界仅存在于校验函数，界面只给红框不给文案；Revision 历史看不出版本差异且后端不返回历史版本全量载荷；配置面零搜索；API Key 无明文切换且提交未 trim；9 个配置入口无统一外壳 |
| **禁用态可解释性** | **F** | 218 个 `:disabled` 绑定中，带 `title` 说明原因的为 **0** |
| **移动端可达性** | **F** | `@media (max-width: 767px)` 把整条导航 `display: none`，替代品只有两格底栏——**14 个菜单中 12 个在移动端无任何入口可达**；`.context-header__actions` 同时被隐藏，页面主操作一并消失；全站零抽屉/汉堡菜单。四断点视觉基线因为只比像素、不断言可达性而全绿 |
| **观察类菜单的行动闭环** | **D** | Activity / Team Observer / 审计 / 运维四页（占导航 29%）停在"把数据显示出来"：看完无动作可做、无处可去；Team Observer 整页 30 行且非 `ready` 态不渲染任何内容 |
| **成员管理完整度** | **D** | 只能加人，不能改角色、不能移除（后端无端点）；表格无"角色"列却有一列内部 ETag `v{n}`；加人=粘贴 UUID |
| **浏览器与导航元数据** | **F** | `document.title` 全站 **0** 处（多标签页无法区分）；生产导航 **0** 处 `aria-current`，仅靠 `:class="{ active }"`（Axe 不报此项）；17 个页面页眉 100% 中英混排；54 处后端枚举裸插值 |
| **Agent 能力纵深** | **C+** | AgentScope 的 memory / rag / protocol(MCP·A2A) / scheduler / skills 扩展全部未接入 |
| 国际化与多端 | D | 无 i18n 层，中英文案硬编码混排；无桌面/移动端 |

### 1.2 一句话结论

CrewScope 的**后端是一个可以直接进企业的系统**，前端是一个**能跑通所有流程但不愉悦的管理后台**。竞品在产品力上的领先不来自功能覆盖——CrewScope 的功能覆盖已明显超过 `vibe-kanban`——而来自**每一次点击的手感**：登录即见待办、字号可读、快捷键完整、面板可拖拽、主题可切换、命令面板可直达。

其中首屏问题最值得单独强调：`OWNER / EXECUTOR / REVIEWER` 责任链是 CrewScope 相对全部竞品最强的领域资产，却在用户最先看到的页面上完全不可见。**把最强的差异化能力藏在三层导航之后，是本次评审发现的最大产品浪费。**

第二轮深度评审（§3.5）进一步发现：前端缺的不是页面，而是**页面之下的一整层基础设施**——基础组件层、反馈闭环、列表能力、偏好留存四块地基全部缺位，导致每个页面都在重新发明 Dialog、重新写焦点陷阱、重新格式化时间。这既解释了为什么"功能都有但不好用"，也意味着**修复是收敛的**：补齐这四层地基能同时改善全部 22 个页面，而逐页打磨则永远补不完。

第三轮针对状态流转的专项查证（§3.5）暴露了一个**比缺组件更根本的建模错误**：CrewScope 把领域状态机原样翻译成了表单字段——用户想"提交评审"，界面要求他"在下拉里选中 `IN_REVIEW` 然后点提交"。这是**用系统的内部结构替用户表达意图**，与 §3.3 首屏问题同源。更要紧的是，这个问题横跨三层：交互层（下拉/看板不可拖）、契约层（前端手抄了一份领域状态机，漂移无信号）、语义层（后端只回答"能不能"，不回答"为什么不能"）。**只把下拉换成按钮解决不了后两层。** 值得注意的是，正确范式在项目内部已经存在——`TaskControlPanel` 的动作按钮组是对的，只是从未推广到 WorkItem 与 Review。

第四轮把"各种配置"当成一个独立产品场景审了一遍（§3.7），结论是**配置面是全部问题的汇聚点，而不是边缘地带**：它承载了全站最密的小字号（173/212 声明 `≤11px`，表单输入框 10px、错误提示 8px）、唯一一处会静默吞掉整屏输入的路由行为（切 Team 即 `router.replace`，而全站脏态跟踪为零）、最有价值却缺失的视图（不可变 Revision 历史看不出版本差异，且后端不返回历史版本全量载荷）、以及最多的后端词汇泄漏（`ACTIVE` / `ZERO_RETENTION` / `TEAM_SUBJECT` 直接当界面文案）。**这很致命，因为配置是新用户的必经路径**——产品给人的第一印象不是 Today 也不是看板，而是这条路上的每一次卡顿。而这里同样有一个内部正确答案：`SetupPage.vue` 是全站配置体验最好的一页，六个枚举翻译函数、进度条、Next step 单卡聚焦、无权限时显示责任方，**它已经把状态流转所需的 `reason` + `remedy` 范式实现了一遍**，只是没有推广到另外十三个配置界面。

与之同源的一个全站数字值得单独列出（§3.8）：**218 个 `:disabled` 绑定，带原因说明的是 0 个。** 产品在每一个"你现在不能做这件事"的时刻都选择了沉默。

第五轮把导航里**其余每一个菜单**当成独立产品逐个走查（§3.9），判据是"我为什么来 / 我能做什么 / 看完去哪"三问。结论分三层。**第一层是同一批地基缺口的第二次显影**——`StatePanel` 没用全（Team Observer 与 404 在非就绪态下一片空白）、枚举映射没有（54 处裸枚举）、`reason`/`remedy` 没有（无权访问页因为路由守卫丢掉 `requiredPermission` 而说不出缺哪个权限）、URL 状态不统一（审计页筛选进 URL 可分享，同类的 Activity 页 Actor 筛选不进）。**第二层是两个此前从未被任何一轮评审或任何一道门禁覆盖的新缺口**：其一，**移动端 14 个菜单里 12 个不可达**——`@media (max-width: 767px)` 把整条导航 `display: none`，只留两格底栏，全站没有抽屉也没有汉堡菜单，而四断点视觉基线因为只比像素不断言可达性，把"导航消失"当成正确基线固化了；其二，**产品在浏览器里没有名字**——`document.title` 从未被设置，生产导航零 `aria-current`。**第三层是对前几轮两个结论的修正**：真实首屏不是 Today 目录而是 `{ path: '/', redirect: { name: 'conversation' } }` 指向的**空对话框**（比"目录"更差——目录至少说明系统里有什么）；字号欠账比 §3.1 记录的更大（容错重测得 743/906 处 `≤11px`、680 处 `≤10px`，而不是 576 处）。

本轮还再次印证了那条主线：**Activity 页需要的服务端筛选参数早就存在**（`TeamActivityController:63-68` 已接受 `categories`/`eventTypes`/`actorPrincipalIds`），前端却在已加载的那一页上做客户端过滤，导致筛选选项取决于翻了几页、计数会骗人、"加载更多"与筛选互相打架——**一行后端都不用改**。整轮 16 个菜单里真正缺后端能力的只有一处：**成员的角色与移除**（读侧角色字段归 `M9-A07`，写侧的改角色/移除是领域增量，建议放 M11）。

同时必须点出**最被低估的一个缺口**：`CodingDiffExplorer` 是本产品价值交付的最后一环——Agent 干得好不好，全靠用户在 Diff 里判断——但它目前是全站最原始的组件：没有语法高亮、没有行号、没有分栏、不能在某一行留下意见。**在一个以"让 Agent 替你写代码"为卖点的产品里，读代码的体验落后于任何一个代码托管平台，是比首屏问题更致命的错配。**

---

## 2. 后端与架构 Review

### 2.1 值得保留的设计（不要在后续重构中破坏）

1. **Domain 零框架依赖**。`crewscope-domain` 承载状态机与不变量，`TaskExecution`（1086 行）、`ExecutionWorkspace`（937 行）是纯 Java 聚合。这是项目最有价值的资产。
2. **Readiness 是派生视图而非第二套状态**（ADR-026 / M8-A01）。避免了配置状态双写这一类经典陷阱，M9+ 的任何"进度/健康/评分"视图都必须沿用此原则。
3. **三流事件协议**（ADR-021）。Conversation / Task / Team Activity 三条流的 Cursor、Generation 与合并协议统一，是后续实时协作的地基。
4. **执行隔离信任边界**（M8-I03）。Worker 不再挂载宿主 Docker Socket，改用受限 Proxy。这是同类开源项目普遍缺失的安全动作。
5. **证据链文化**。每个里程碑都有 Spike → ADR → 执行清单 → testing 证据的闭环，任务关闭必须提交运行证据。

### 2.2 需要在 M9–M10 处理的架构问题

| 编号 | 问题 | 证据 | 影响 | 建议里程碑 |
|---|---|---|---|---|
| `BE-01` | **无 OpenAPI/契约生成**。206 个端点的 DTO 在前端由 19 个 `domains/*/types.ts`（合计 2479 行）手写镜像 | `rg springdoc` 无生产依赖；`crewscope-web/src/api/types.ts` 仅 14 行 | 后端 DTO 变更不会在前端编译期失败，只能靠测试与人工 Review 兜底；是长期返工的主要来源 | **M9-I01** |
| `BE-02` | **单体 Controller 过厚**。`OperationsController` 1084 行、`GitHubConnectionApplicationService` 1145 行 | 文件行数统计 | M8-E01 已拆出解析/编解码职责，但编排入口仍是巨型类，新增能力会继续堆叠 | M9 顺带、M11 收口 |
| `BE-03` | **AgentScope 能力面仅用了约 40%**。`memory`（长期记忆）、`rag`、`extensions-protocol`（MCP/A2A）、`extensions-skills`、`extensions-scheduler` 全部未引用 | `rg "import io.agentscope"` 仅覆盖 model/event/message/agent/harness/tool/middleware/state/agui/skill/permission | Agent 每次任务都从零理解代码库与团队约定，无法沉淀"团队知识"，这是与竞品拉开差距的最大机会 | **M10 主线** |
| `BE-04` | **无全局搜索能力**。`search` 仅存在于 Audit 的筛选语义 | Controller 清单 | 用户无法跨 WorkItem / Conversation / Task / Artifact 检索，规模上去后产品不可用 | **M9-A02** |
| `BE-07` | **无个人工作聚合能力**。`WorkItemQueryController` 仅支持 `status` + Cursor，挂在单个 WorkProject 路径下；无按责任人过滤、无跨项目聚合、无"待我行动"统一视图 | `rg "RequestParam" WorkItemQueryController.java` 仅 3 个参数 | 首页无法呈现"我的工作"，用户必须逐项目翻找自己的待办；责任链这一核心差异化资产在产品上不可见 | **M9-A03** |
| `BE-09` | **动作可用性不对外暴露**。17+ 个聚合各自持有 `private static final ALLOWED_TRANSITIONS`，但无任何端点或 DTO 返回"当前可执行哪些流转"，更不返回"为什么不能执行"；前端只能手抄状态机（`domains/workitem/types.ts:176`）并用三个 if 猜测不可用原因 | `grep -rn "allowedTransitions\|availableActions\|nextStates" crewscope-{domain,application,server}/src/main` → 无结果；`WorkItem.java:23` vs `types.ts:176` 两份重复定义 | 状态机漂移无任何信号（DTO 漂移会编译失败，这个不会），用户走完七步操作才在提交时拿到 4xx；禁用态无法解释原因，而原因本身往往就是用户要执行的下一步 | **M9-I01（生成）+ M9-A05（可用性）** |
| `BE-08` | **Review 只能整体表态，无法定位到代码行**。既有 `ReviewDecision` 只承载整体裁决，无按 `(文件, 行)` 锚定的评论能力 | Review 相关 Controller 与 DTO 无 `filePath`/`lineNumber` 字段 | Review 意见无法落到具体代码位置，只能在对话里用自然语言描述"第几个文件大概哪一段"，是 Agent 返工沟通成本的主要来源 | **M9-A04** |
| `BE-10` | **配置历史只返回摘要，不返回版本全量载荷**。`AgentConfigurationHistoryItem` 只有绑定与 `configurationHash`，`supplementalInstructions`、`approvedSkillKeys`、`generateOptions`、`memoryPolicy`/`budgetPolicy`、`policyPack*` 全部缺失；这些字段只在 `CurrentAgentConfiguration` 里，且仅限当前版本。同时 Setup Readiness 只覆盖 6 个能力，不含模型连接健康、凭证过期、成员与运行配置 | `domains/agent/types.ts:64-75` vs `:93-110`；`SetupPage.vue:41-46` 的 `SetupCapability` 枚举共 6 项 | 不可变版本历史这一后端亮点在产品上无法兑现——**前端即使想做版本对比也拿不到数据**；配置一旦就绪就再无常驻健康视图，凭证过期与连接撤销无处可见 | **M9-A06** |
| `BE-11` | **团队成员只能加、不能管**。`TeamController` 只有 `POST /{teamId}/members` 与 `GET /{teamId}/members`，无角色变更、无移除、无停用端点；同时成员 DTO `TeamMemberSummary` 不含 `roles`，前端连"这个人是什么角色"都显示不出来 | `grep "Mapping" TeamController.java` → 6 个端点无一涉及成员变更；`application/team/` 下有 `AddTeamMemberCommand` 但无 Remove/ChangeRole 对应物；`domains/scope/types.ts:42-51` 无 `roles` 字段 | 读侧：成员页无法显示角色，只能显示 `status`/`joinMethod` 与内部 ETag `v{n}`。写侧：成员离职、角色调整只能靠 DBA 改库，对一个以"团队责任链"为核心的产品是功能性缺口 | **读侧 M9-A07**；**写侧 M11**（属领域增量，不塞进 M9） |
| `BE-05` | **无 i18n 基础设施**。错误码有稳定 code，但公开文案由前端硬编码 | `ApiErrorResponse` + 前端中文字面量 | 无法面向非中文团队与开源社区发行 | M11 |
| `BE-06` | 实时通道只有 SSE，无双向 WebSocket | `TeamActivitySseSession` 等 | 多人同屏协作（在线光标、正在输入、同屏接管）无法实现 | M11 |

> `BE-01` 是**优先级最高的后端工程债**：它不产生用户可见故障，但每个前端里程碑都要为它多付 15–20% 的成本。M9 在开始前端大改之前必须先把它关掉。

---

## 3. 前端全面 Review（本次评审重点）

### 3.1 视觉与排版：规范与实现的严重漂移

设计规范 `CrewScope-前端设计规范.md` §4.2 明确要求：

> 工作页面正文默认 14px，辅助信息 12px，标题依次为 18/24/32px

实际实现的字号分布（`crewscope-web/src` 全量 `.vue`）：

| 字号 | 出现次数 | 规范定位 |
|---:|---:|---|
| 7px | 20 | 规范中不存在 |
| 8px | 164 | 规范中不存在 |
| 9px | 213 | 规范中不存在 |
| 10px | 179 | 规范中不存在 |
| 11px | 62 | 规范中不存在 |
| 12px | 42 | 辅助信息 |
| 14px | 10 | **正文** |
| 16px+ | 82 | 标题 |

**`≤10px` 合计 576 处，`=14px` 仅 10 处。**整个产品的正文实际运行在 8–10px

> **第五轮修正**：上表用 `font-size: ` 前缀统计，漏掉了 `font:` 简写（配置面大量使用 `font: 10px/1.4`）。容错重测后的真实分布见 §3.9.4——**全站 906 处声明中 743 处（82%）`≤11px`，`≤10px` 的真实数量是 680 处（多出 104 处），`≤9px` 486 处，7px 43 处，而 14px 只有 12 处。** 结论方向不变，但欠账规模比本节记录的更大，`M9-Q01` 的字号门禁必须用容错正则而不是前缀匹配。，是设计规范的 57%–71%，也低于所有主流 Web 产品的最小可读基线（12px）。

这不是"信息密度高"，而是**用密度换取了可读性、可访问性与专业观感**。直接后果：

- 用户在 27 寸显示器上需要前倾阅读，长时间使用疲劳；
- WCAG 与常见企业采购的可访问性检查会直接失败；
- 视觉上给人"未完成的内部工具"印象，而非可售卖产品；
- 高信息密度反而**降低**了扫读效率——字号过小时人眼无法快速分层。

**根因**：`tokens.css` 只定义了 color / radius / shadow / transition，**没有任何 font-size、line-height、spacing、z-index Token**。因此每个组件作者自行拍板，结果是 20 种字号、556 处硬编码 `padding`。

### 3.2 信息架构：单层平铺 + 永久禁用占位

`AppShell.vue:55-75` 的导航结构：

```text
Workspace（实际包含全部 14 项）
  Today / Setup Center / Work / Activity / 我的 Inbox / Team Observer /
  运行与发布 / 审计中心 / 团队成员 / Agent 中心 / 模型与凭证 /
  飞书与通知 / GitHub 集成 / 仓库设置
Operate
  WorkGraph            ← disabled 占位
System
  治理与设置            ← disabled 占位
```

问题：

1. **分组名与内容不符**。7 个设置类入口（Agent / 模型 / 飞书 / GitHub / 仓库 / 成员 / 审计）被放进 "Workspace"，而真正的 "Operate" 和 "System" 组里只有两个点不动的灰色按钮。
2. **日常高频与低频同权重**。用户每天进入的 Today / Work / Conversation，与一年配置一次的"飞书与通知"在视觉上完全平级。
3. **永久禁用项消耗信任**。`futureNavigation` 里的两项从 M1 至今未实现，却持续占据导航；用户第一次点击失败后，会对整个界面的可点击性产生怀疑。
4. **命名语言不统一**。`Today` / `Setup Center` / `Work` / `Activity` / `Team Observer` 是英文，`我的 Inbox` / `运行与发布` / `审计中心` / `模型与凭证` 是中文，`Agent 中心` 是混排。

### 3.3 首屏：登录后看到的不是工作（第五轮修正：甚至不是目录，是一个空对话框）

> **第五轮修正（详见 §3.9.3）**：本节原先假定登录后落在 Today。实际 `app/router.ts` 第一条路由是 `{ path: '/', redirect: { name: 'conversation' } }`——**根路径重定向到 Conversation，用户看到的是一个还没有任何消息的空对话框**；`OnboardingPage.enterConversation()` 走完引导后同样跳 Conversation。本节对 Today 的分析全部成立，但"首屏是目录"这个描述要改成"首屏是空对话框，目录还要再点一下才能到"——比原结论更差：目录至少说明系统里有什么，空对话框只要求用户开始输入，而新用户此刻并不知道该输入什么。

`TodayPage.vue` 共 134 行，是全站最短的主页面之一。它的自述文案（`TodayPage.vue:65`）明确了自己的定位：

> Today 聚合当前 Team 与 WorkProject 的责任、决策和执行入口；**工作事实仍由各业务 API 提供**。

即：首页**有意不展示任何真实工作数据**。它实际渲染的是可用 WorkProject 数量、团队成员数量、Setup 就绪度，以及"新建项目 / 进入对话 / 打开 Work"三个跳转按钮。

用户登录后的第一屏回答的是"这个系统里有什么"，而不是"我今天要做什么"。要看自己的待办，用户必须：选 Team → 选 WorkProject → 进 Work 页 → 在看板里找自己的卡片；待我 Review 的在另一个页面，待我决策的 Human Gate 在第三个页面，未读通知在第四个页面。

服务端同样不支持这件事：

| 能力 | 现状 | 证据 |
|---|---|---|
| 按责任人过滤工作项 | ❌ | `WorkItemQueryController` 仅 `status` + Cursor 两个过滤参数 |
| 跨 WorkProject 聚合 | ❌ | 查询路径挂在单个 WorkProject 下 |
| "待我行动"统一视图 | ❌ | Review / Gate / Inbox 三套独立查询，无聚合入口 |

对一个以"团队责任链"为核心差异化的产品，**首屏不呈现责任是定位与实现的错位**——`OWNER / EXECUTOR / REVIEWER` 是 CrewScope 相对竞品最强的领域资产，却在用户最先看到的地方完全不可见。

### 3.4 交互深度：三个"装饰性"控件

| 控件 | 位置 | 现状 |
|---|---|---|
| 全局搜索 `⌘K` | `AppShell.vue:224` | `<button type="button">` 无 `@click`、无 `keydown` 监听。**完全不可用，但显示了快捷键提示** |
| 通知铃铛 | `AppShell.vue:227` | `<button type="button" aria-label="通知">` 无 `@click`。点击无反应 |
| 看板拖拽 | `WorkPage.vue:1122` | 看板列仅渲染卡片，无 `draggable` / `dragstart` / `drop`。状态流转必须进详情抽屉 |

**显示一个 `⌘K` 提示却不响应任何按键，比不显示更伤产品信任。**

其余交互缺口：

- **无全局快捷键系统**。全仓 `keydown` 监听全部集中在 Dialog 的 Esc 与焦点陷阱内，没有一个导航或操作级快捷键。对照 `vibe-kanban` 的 `registry.ts`：`g s` 转设置、`w d` 复制工作区、`v c` 切换 Changes 面板、`x p` 建 PR、`y p` 拷路径——一套完整的 Linear 式序列键。
- **无虚拟滚动**。消息历史、Activity 流、Audit 列表、Diff 文件树在长列表下全量渲染。竞品用 `react-virtuoso` / `@tanstack/react-virtual`。
- **面板宽度不可调**。三栏 Conversation 与 Work 使用固定 `grid-template-columns`。竞品用 `react-resizable-panels` 让用户自己决定 Diff 区和对话区的比例——这在 Coding 工作流里是刚需。
- **无暗色模式**。`tokens.css:2` 硬编码 `color-scheme: light`，全仓无 `prefers-color-scheme` 分支。面向开发者的工具没有暗色模式是硬伤。
- **无动效语言**。有 `--cs-transition-fast/panel` 两个 Token，但流式输出、状态迁移、卡片进出没有统一的动效实现，执行过程"跳变"而非"流动"。

### 3.5 状态流转：把领域状态机当成了表单字段

这是第三轮评审专门查证的一块，结论比预期严重——**问题不在"看板不能拖拽"，而在流转这个动作本身的建模方式错了**。

#### 3.5.1 现状：选一个枚举值，然后提交

`WorkItemDetailDrawer.vue:249` 是唯一的状态流转入口：

```vue
<div v-if="canTransition" class="transition-control">
  <select v-model="transitionTarget" aria-label="目标状态">
    <option v-for="status in transitions" :key="status" :value="status">{{ statusLabels[status] }}</option>
  </select>
  <BaseButton size="small" :loading="commandPending === 'transition'" @click="submitTransition">提交流转<ArrowRight :size="13" /></BaseButton>
</div>
```

要把一个工作项从 `IN_PROGRESS` 推进到 `IN_REVIEW`，用户需要：**在看板/列表里点开卡片 → 等抽屉加载 → 找到"状态流转"区 → 展开下拉 → 在若干枚举里挑一个 → 点"提交流转" → 关闭抽屉**。七步，其中六步是与意图无关的操作开销。用户的真实意图只有一个词："提交评审"。

同样的形态出现在 Review 结论（`ReviewWorkbench.vue:318`）：

```vue
<select ref="decisionSelect" v-model="decisionType">
  <option value="COMMENTED">COMMENTED · 留言</option>
  <option value="APPROVED">APPROVED · 通过</option>
  <option value="CHANGES_REQUESTED">CHANGES_REQUESTED · 请求修改</option>
  <option value="REJECTED">REJECTED · 拒绝</option>
</select>
```

四个**语义与后果截然不同**的决策（其中 `REJECTED` 是不可逆的强动作）被压平成一个下拉的四个等权选项，选项标签还带着裸枚举名。用户在做最重要的判断时，界面给出的呈现和填一个"省份"字段没有区别。

#### 3.5.2 项目内部已经有正确答案，只是没有推广

这一点是本次评审最值得强调的：**CrewScope 不需要从竞品学这个范式，它自己已经写对了一次。** `TaskControlPanel.vue:178-211`：

```vue
<BaseButton @click="openDialog('PAUSE', $event)"><CirclePause :size="13" />暂停</BaseButton>
<BaseButton @click="openDialog('RESUME', $event)"><Play :size="13" />恢复</BaseButton>
<BaseButton @click="openDialog('RETRY', $event)"><RotateCcw :size="13" />重试</BaseButton>
<BaseButton @click="openDialog('CANCEL', $event)"><CircleStop :size="13" />取消</BaseButton>
```

一个动作一个按钮，带图标，带动词标签，点了直接进确认。这是对的。

| 场景 | 交互范式 | 评价 |
|---|---|---|
| Task 执行控制 | 动作按钮（暂停/恢复/重试/取消） | ✅ **正确，应作为全站样板** |
| WorkItem 状态流转 | `<select>` + "提交流转" | ❌ 两步式表单 |
| Review 结论 | `<select>` + 理由 + "确认提交" | ❌ 两步式表单，强弱动作等权 |

**同一个产品里存在两套相互矛盾的动作范式，而更好的那套只覆盖了一个页面。** 修复方向不是发明新东西，是把 `TaskControlPanel` 的范式推广到 WorkItem 与 Review。

#### 3.5.3 看板像看板，但不能像看板一样用

看板视图是**已经存在**的（`WorkPage.vue:101` 有 `list | board` 双视图，`1122` 行按状态分列渲染）：

```vue
<section v-for="status in boardStatuses" :key="status" class="board-column">
  <WorkItemCard v-for="item in itemsFor(status)" :item="item" layout="board" @select="selectItem" />
```

卡片上唯一的事件是 `@select`——打开抽屉。**列已经按状态分好了，卡片就摆在目标列旁边，但用户不能把它拖过去。** 这比没有看板更违背直觉：界面用空间布局暗示了"可以移动"，交互却要求走表单。

#### 3.5.4 最严重的一层：前端手抄了一份领域状态机

`domains/workitem/types.ts:176` —— 注释坦白了它是什么：

```ts
/** Mirrors the native WorkItem aggregate state machine for action discovery only. */
export const allowedWorkItemTransitions: Readonly<Record<WorkItemStatus, readonly WorkItemStatus[]>> = {
  BACKLOG: ['READY', 'CANCELLED'],
  IN_PROGRESS: ['IN_REVIEW', 'BLOCKED', 'CANCELLED'],
  // ...
}
```

对照后端权威定义 `WorkItem.java:23`：

```java
private static final Map<WorkItemStatus, Set<WorkItemStatus>> ALLOWED_TRANSITIONS = Map.of(
        WorkItemStatus.BACKLOG, EnumSet.of(WorkItemStatus.READY, WorkItemStatus.CANCELLED),
        WorkItemStatus.IN_PROGRESS, EnumSet.of(WorkItemStatus.IN_REVIEW, WorkItemStatus.BLOCKED, WorkItemStatus.CANCELLED),
        // ...
```

两份目前**恰好一致**，但这是人工维护的巧合，不是工程保证：

| 事实 | 证据 |
|---|---|
| 后端有 17+ 个聚合各自维护 `ALLOWED_TRANSITIONS` | `WorkItem`、`Principal`、`UserAccount`、`TeamMember`、`TeamRole`、`AgentProfile`、`AgentTemplateDefinition`、`RuntimeWorker`、`LoginIdentity`、`AccountOrganizationBinding` … |
| 全部为 `private static final`，**零个对外暴露** | `grep -rn "allowedTransitions\|availableTransitions\|availableActions\|nextStates" crewscope-{domain,application,server}/src/main` → **无任何结果** |
| 前端只能手抄，且只抄了 WorkItem 一个 | `grep -rn "allowedWorkItemTransitions"` → 定义 1 处、消费 1 处 |
| 无任何一致性测试 | 前后端状态机之间不存在契约或测试关联 |

这是 `BE-01`（无契约生成）的一个**未被发现的、且更危险的实例**。DTO 漂移在 M9-I01 之后会编译失败；**状态机漂移不会有任何信号**——后端收紧一条边，前端下拉里照旧列出那个选项，用户选了它，点提交，拿到一个 4xx。**问题在最后一步才暴露，而这一步已经消耗了用户七次操作。**

#### 3.5.5 缺的是"为什么不能"，不只是"能不能"

当前 `canTransition` 为假时，显示一句静态兜底文案（`WorkItemDetailDrawer.vue:250`）：

```
item.source !== 'CREWSCOPE' ? '外部 Provider 工作项由来源系统管理状态。'
  : item.status === 'ARCHIVED' ? '已归档工作项没有后续状态。'
  : '当前账号没有参与工作项的界面权限。'
```

三分支覆盖不了真实世界的"不能"：Reviewer 未指派、职责分离冲突、前置 Gate 未通过、有未解决的阻塞、Task 正在执行中。**动作可用性在后端是多因子裁决，在前端被简化成了三个 if。** 用户看到的是一句笼统的"没有权限"，而真正的原因（比如"需要先指派 Reviewer"）本身就是他要执行的下一步——这个信息被丢掉了。

#### 3.5.6 小结：三个层次的问题

| 层次 | 问题 | 归属 |
|---|---|---|
| 交互层 | 两步式下拉 + 提交，看板不可拖，强弱动作等权 | 前端 |
| 契约层 | 前端手抄状态机，无生成、无门禁、静默漂移 | 构建期 |
| 语义层 | 后端只回答"能不能"，不回答"为什么不能"与"缺什么" | 后端 |

**只修交互层是不够的**：把下拉换成按钮，按钮列表的来源仍然是那份手抄的常量；而按钮该不该禁用、禁用了怎么解释，后端今天给不出答案。三层必须一起修。

#### 3.5.7 交互形态的选择：一个动作模型，四个呈现面

"把下拉换成按钮"只是最直接的替代方案，不是最好的。逐个比较后选定的方案是**一个动作模型 + 四个呈现面**：动作只在 `ActionRegistry` 里定义一次，在不同上下文里用最省力的方式呈现。

**主推：状态徽章即动作入口。** `StatusBadge` 已经出现在列表行、看板卡片、抽屉头部、详情页——几乎每一个"有状态的东西"旁边都有它。把它升级为动作入口：有可用动作时，徽章获得一个悬停/聚焦才出现的 chevron 提示；点击或回车打开一个紧凑浮层，列出动词短语形式的可用动作，不可用的动作在原位展示 `reason` 与 `remedy`。

选它的理由是结构性的：

- **不占新的版面**——复用已经贴在每个状态旁边的元素，不需要在密集的列表行里再挤出按钮位；
- **一次组件升级同时覆盖全部场景**——列表、卡片、抽屉、详情页不必各写一遍；
- **心智模型完全对齐**——显示状态的东西就是改变状态的东西；
- **只读用户零变化**——无可用动作时徽章保持今天的静态形态，不产生"点了没反应"的假入口；
- **两步到位**——点徽章 → 点动作，从七步降到两步。

**三个互补面（不是备选项，是同一批动作在不同上下文的投影）：**

| 呈现面 | 用在哪里 | 代价 |
|---|---|---|
| 主动作按钮 | 抽屉/详情页头部，把当前最高价值的那一个动作从浮层里提升为真实按钮（如"提交评审"），沿用 `TaskControlPanel.vue:178-211` 的既有范式 | 1 步 |
| 看板拖拽 | 看板的空间语义兑现；拖起时即把不可落入的列变灰，而不是落下后报错 | 1 次拖动 |
| ⌘K 与列表快捷键 | 动作注册进命令面板并按当前聚焦对象过滤；列表上下文给高频动作单键加速器，加速器在徽章浮层里标注 | 1–2 键 |

四个面读同一份 `ActionRegistry` 条目，所以"一个动作"在整个产品里只有一处定义——这是把动作抽出来的真正收益，而不只是换个控件。

**必须配套的安全counterweight：撤销窗口。** 全站今天 `undo` 用量为零（`grep -rn "undo"` 无结果）。把流转从七步降到一两步，意味着误触的概率同比上升——拖错一列、点错一行，代价从"几乎不可能"变成"很容易"。因此成功执行可逆流转后，Toast 需提供一个有时限的"撤销"：

- 它**不是新的后端概念**，而是执行反向流转边——仅当生成的状态机里存在该反向边、且 `availableActions` 当时允许时才出现；
- 它走完整的命令路径，产生正常的事件与审计记录，不做本地回滚、不做软删除；
- 不可逆动作（`REJECTED`、`ARCHIVED`）没有撤销，改为二次确认——**代价不对称的动作，交互代价也必须不对称**。

### 3.6 体验细节的系统性缺口（第二轮深度评审补充）

第一轮评审聚焦排版、架构与三个装饰控件。第二轮逐项查证交互细节后，发现更普遍的问题：**产品缺少一整层"基础体验设施"**。以下全部有可复现证据。

#### 3.5.1 基础组件层几乎不存在

```text
components/base/      BaseButton.vue、StatusBadge.vue、types.ts   ← 只有 2 个组件
components/feedback/  GlobalErrorBanner.vue、StatePanel.vue       ← 只有 2 个组件
```

没有 Input、Select、Textarea、Checkbox、Radio、Switch、Dialog、Drawer、Tooltip、Popover、Tabs、Table、Toast、Skeleton。直接后果：

| 后果 | 数据 |
|---|---|
| Dialog 各自实现 | **15 个** `.vue` 各自写 `role="dialog"` |
| 焦点陷阱各自实现 | **9 个** `.vue` 各自处理 focusable 元素查询 |
| 表单控件各自实现 | 每个表单自行处理校验时机（`@change` 20 处、`@input` 6 处，无统一规则） |

这是 680 处 `≤10px` 字号与 556 处硬编码间距的**同一个根因的另一个面**：没有可复用的基础层，每个页面都在重新发明。

#### 3.5.2 缺少操作反馈闭环

| 缺口 | 证据 | 体验后果 |
|---|---|---|
| 无全局 Toast | `feedback/` 目录无 Toast 组件；`successMessage` 仅 8 处且各页自行渲染 | 用户完成保存、创建、删除后缺少统一确认，不确定操作是否生效 |
| 无 Tooltip 组件 | Tooltip 组件 **0 个**，原生 `title="..."` **330 处** | 原生 title 有 ~1 秒延迟、样式不可控、**在触摸设备上完全不显示**——330 处提示在移动端全部消失 |
| 使用原生 `confirm()` | `GitHubSettingsPage.vue:246` `window.confirm(...)` | 破坏视觉一致性，无法样式化，部分浏览器可被用户全局禁用 |
| 无进入/离开动效 | `<Transition>` 组件 **0 处**，`@keyframes` 仅 9 处 | 列表增删、面板展开、卡片出现全是"闪现"，执行过程跳变而非流动 |

`prefers-reduced-motion`（11 个文件）与 `aria-live`（25 处）做得不错，但**没有动效可降级**——降级的前提是先有动效。

#### 3.5.3 列表能力缺失

| 能力 | 现状 |
|---|---|
| 排序 | **0 处**。所有列表按服务端固定顺序呈现，用户无法按更新时间、状态、责任人重排 |
| 批量操作 | **0 处**。无多选、无批量改状态、无批量指派 |
| 相对时间 | **0 处**。31 处时间格式化全是绝对时间戳，且格式不统一（`Intl.DateTimeFormat` 24 处 + `toLocaleString` 7 处） |

对一个执行类产品，"这个任务 3 分钟前刚跑完"和"2026-09-12 14:23:01"传达的信息密度完全不同。

#### 3.5.4 空状态是死胡同

`StatePanel` 提供了 `<slot name="action" />`，设计是对的。但实际使用：

```text
引用 StatePanel 的文件：36 个
使用 action slot 的文件：1 个
```

**35 个空状态只告诉用户"这里是空的"，不告诉用户"该怎么做"。** 新用户在每个空页面都会卡住。

#### 3.5.5 代码阅读与 Review 体验薄弱

这是本项目的**核心场景**，却是体验最弱的一环。生产依赖仅 5 个（`@lucide/vue`、`dompurify`、`markdown-it`、`vue`、`vue-router`），无任何语法高亮库。

`CodingDiffExplorer.vue` 的实现：

| 能力 | 现状 | 证据 |
|---|---|---|
| 语法高亮 | ❌ | 无 shiki/prism/highlight.js 依赖 |
| 行号 | ❌ | `grep -c "lineNumber\|oldLine\|newLine"` → 0 |
| 并排（split）视图 | ❌ | 仅 unified 视图 |
| 行级评论 | ❌ | 全仓行级评论相关代码 1 处 |
| 变更分类着色 | ✅ 仅按 `+`/`-` 前缀 | `CodingDiffExplorer.vue:102-103` |

一个让 Agent 写代码、让人 Review 代码的产品，Reviewer 看到的是**无高亮、无行号、无法逐行评论的纯文本 Diff**。这是与 `vibe-kanban` 差距最直观的地方。

#### 3.5.6 导航上下文与偏好

| 缺口 | 证据 | 后果 |
|---|---|---|
| 无面包屑 | **0 处** | 22 条路由，深层页面（如某 Task 的 Execution Studio）无位置感，无法快速回到上级 |
| 偏好零持久化 | `localStorage.setItem` 仅 **1 处** | 筛选、视图模式、列宽、折叠状态每次刷新全部重置 |
| 断点碎片化 | **38 种**不同 `max-width` 取值（180px 到 1420px） | 与字号问题同根因：无断点 Token，每个组件自行拍板，响应式行为不可预测 |

筛选状态入 URL 做得不错（`router.replace/push` 81 处），这是正确的方向，应保留并扩展。

### 3.7 配置体验：能力齐备，但每一步都在跟你较劲（第四轮专项评审）

配置面是 CrewScope 功能最完整的区域之一：Agent 配置有不可变 Revision 历史、模型连接有凭证轮换与健康度、Setup Center 有能力就绪清单与责任方。但它同时是**产品体验问题最集中的区域**——因为配置是用户第一次使用产品时必经的路径，而这条路径上的每一个细节都没有被当成产品来打磨。

#### 3.7.1 配置面是排版问题的震中，不是边缘

§3.1 统计了全站 `≤10px` 字号（容错重测后为 680 处，见 §3.9.4）。把范围收窄到 14 个配置界面（5 个设置页 + Setup/Account/成员/运行 + 5 个配置组件）后，比例更触目：

| 配置界面 | `≤11px` / 全部字号声明 |
|---|---:|
| `AgentConfigurationPanel.vue` | **29 / 33** |
| `LarkNotificationAdmin.vue` | **27 / 30** |
| `ModelSettingsPage.vue` | **19 / 22** |
| `AgentSettingsPage.vue` | 17 / 23 |
| `ModelConnectionDetail.vue` | 15 / 19 |
| `GitHubSettingsPage.vue` | 14 / 18 |
| `TeamMembersPage.vue` | 13 / 15 |
| `RepositorySettingsPage.vue` | 12 / 14 |
| 其余 6 个 | 33 / 38 |
| **合计** | **173 / 212（82%）** |

最关键的是**表单控件本身**。`AgentConfigurationPanel.vue:497` 的实际声明：

```css
.binding-fields select, .preference-fields select,
.preference-fields input, .preference-fields textarea {
  font: 10px var(--cs-font-sans);   /* 输入框正文 10px */
}
.binding-fields label, .preference-fields > label { font-size: 9px }  /* 字段标签 9px */
.field-warning { font-size: 8px }                                      /* 字段警告 8px */
.save-actions > span { font-size: 8px }                                /* 保存说明 8px */
```

即：**用户在 10px 的输入框里填 Temperature 和 Top P，字段标签 9px，出错提示 8px。** 而同文件 `:499` 的 `@media (max-width: 600px)` 里把输入框改成了 `font-size: 16px`——说明作者知道 16px 才是可用的输入框字号，只是把它当成"移动端防缩放的补丁"，而不是桌面端也该有的基线。**桌面端的配置表单比移动端难读，这是一个被反向优化的结果。**

#### 3.7.2 填了半天的表单会被静默丢弃

全站检索 `dirty|unsaved|beforeunload|onBeforeRouteLeave|hasChanges`（`.vue` + `.ts`，排除 spec）**只有 2 个结果，都在 `InvitePage.vue` 且不是用于未保存保护**。也就是说：**整个产品没有任何一处脏态跟踪。**

这在配置面的后果是具体且严重的。`AgentConfigurationPanel` 的表单包含补充指令（上限 16384 字）、批准 Skill 多选、Temperature / Top P / Max output tokens / Max attempts / Seed / Reasoning / 缓存 / 并行 Tool Call、以及 PERSONAL 与 TEAM 两套模型绑定。而 `AgentSettingsPage.vue:93-101` 的 Team 切换 watcher 会主动执行：

```ts
if (teamChanged) {
  await router.replace({ name: 'agent-settings', query: withAgentSettingsRoute(...) })
}
```

用户填完一屏参数，顺手在 ScopeSwitcher 里切了个 Team——**路由被 replace，表单被重建，输入全部消失，没有任何提示。** 点另一个 Agent、点 Revision 历史、浏览器后退，结果相同。

#### 3.7.3 参数有边界，但界面从不说出来

`AgentConfigurationPanel.vue:456-460` 的校验函数明确知道每个参数的合法区间：

| 字段 | 代码中的实际边界 | 界面告诉用户的 |
|---|---|---|
| Temperature | `optionalDecimal(…, 0, 2, true)` | placeholder "模型默认" |
| Top P | `optionalDecimal(…, 0, 1, false)` | placeholder "模型默认" |
| Maximum attempts | `optionalInteger(…, 1, 10)` | 无 |
| Maximum output tokens | `optionalInteger(…, 1, 10000000)` | placeholder "模型默认" |

三个问题叠加：

1. **边界不可见**。没有 `min`/`max`/`step` 属性——全站 `min=` 仅 2 处、`max=` 仅 1 处、`step=` 仅 2 处，所以既没有界面提示，也没有浏览器原生校验和步进器。
2. **校验迟到**。全部绑定写作 `:aria-invalid="submitted && !optionalDecimal(...)"`——`submitted` 之前不给任何反馈，用户填错要等到点了保存才知道。
3. **报错无文案**。`aria-invalid` 只驱动一条 CSS：`[aria-invalid='true'] { border-color: var(--cs-danger) }`。**用户看到一个红框，但没有任何文字说明错在哪、应该填什么范围。** 全站 `aria-invalid` 用了 33 处，而可见字段级错误文案（`field-error` / `fieldProblem` 一类）只有 26 处，且集中在认证页。

#### 3.7.4 有不可变版本历史，却看不出两个版本差在哪

Agent 配置的 Revision 机制是后端设计的亮点：不可变、可追溯、被 Conversation/Task/Retry 固定引用。前端也有 Revision 侧栏。但选中一个历史版本后，`AgentConfigurationPanel.vue:412-415` 只渲染三样东西：

```text
PERSONAL 绑定 / TEAM 绑定 / Configuration Hash 前 16 位
```

**看不到这个版本的补充指令、批准 Skill、Temperature、Top P、Reasoning、缓存策略，更看不出它和上一版之间改了什么。** 一个以"配置可追溯"为设计目标的系统，最有价值的那个视图——版本对比——完全不存在。

而且这不只是前端没做：`domains/agent/types.ts:64-75` 的 `AgentConfigurationHistoryItem` 本身就**不含**这些字段，只有 `CurrentAgentConfiguration`（`:93-110`）才有，且仅限当前版本。**今天前端即使想做 diff 也拿不到数据**——这是一个由前端诉求驱动的后端接口缺口。

#### 3.7.5 配置面零搜索，模型选择被塞进 `<select>`

对 5 个设置页与配置组件检索 `type="search"`、搜索 placeholder、过滤控件：**零结果**。没有模型目录搜索、没有 Agent 列表搜索、没有 Skill 搜索、没有仓库搜索。

模型选择的实现是 `AgentConfigurationPanel.vue:438`：

```html
<select v-model="bindings[scope].primary">
  <option v-for="model in models(scope)" :value="modelKey(model)">
    {{ optionLabel(model) }} · {{ optionPrice(model) }}
  </option>
</select>
```

一个原生 `<select>`，选项文案靠字符串拼接。后果与 §3.5 的状态流转同源——**`<select>` 又一次被当成了万能控件**：不能搜索、不能按 Provider 分组、不能显示健康度徽章与能力标签、不能展示上下文窗口与价格的对比。而 `ModelSettingsPage.vue` 的模型卡片网格恰好把这些信息都展示得很好——两个界面在讲同一件事，一个用卡片，一个用一行拼接的字符串。

Skill 选择同理（`:453`）：`<span class="mono">{{ key }}</span>`——**只给原始 Skill Key，不说这个 Skill 是做什么的**，用户要靠 key 猜语义。

#### 3.7.6 凭证输入：正确实现已存在，但没用在最需要的地方

`components/auth/AuthPasswordField.vue` 是一个做得很完整的密码字段：`Eye`/`EyeOff` 切换、`aria-pressed`、`aria-label` 随状态变化。但它只用在认证页。

用户粘贴 API Key 的地方是 `ModelCredentialDialog.vue:167`：

```html
<input type="password" autocomplete="new-password" autocapitalize="off"
       spellcheck="false" maxlength="1048576" placeholder="仅在本次提交中使用" />
```

安全属性都对，但**没有明文切换**。用户把一串上百字符的 Key 粘进全掩码输入框，无法在提交前核对；验证失败时也无法判断是 Key 抄错了还是网络问题。

更实际的一个问题在 `:102`：校验用 `apiKey.value.trim()`，提交用 `apiKey: apiKey.value`——**未 trim**。从网页或终端复制 Key 时带上的尾随换行或空格会原样进入请求，导致一次莫名其妙的验证失败。这是 API Key 配置失败最常见的成因，而修复只需一行。

同一个对话框里，凭证过期时间是 `type="datetime-local"` 且无 `min`（`:162`），可以填一个已经过去的时间。

#### 3.7.7 配置面到处是要手抄的标识符，而复制按钮只存在于一个地方

全站剪贴板调用只有 1 处：`components/team/TeamInvitationManager.vue:108`。那一处实现得很好——复制态图标切换、"已复制"反馈、失败时提示手动选择。

而配置面遍布需要带到别处去用的标识符：Connection ID、`configurationHash`、Agent ID、Provider key、Region、飞书 Open ID、仓库 URL、Webhook 地址。**全部只能手工选中拖拽复制**，其中 `configurationHash` 还被截断显示为 `slice(0, 16) + '…'`——**界面上显示的那个值根本不完整，复制也复制不到完整值。**

#### 3.7.8 后端词汇直接当界面文案

配置面大量把原始枚举当文案渲染：

| 位置 | 渲染内容 | 用户看到 |
|---|---|---|
| `ModelSettingsPage.vue:271` | `{{ provider.status }}` | `ACTIVE` |
| 同上 | `{{ connection.status }}` / `{{ connection.healthStatus }}` | `SUSPENDED` / `UNHEALTHY` |
| 同上 | `Retention {{ retentionMode }}` · `Training {{ trainingUsagePolicy }}` | `ZERO_RETENTION` / `NOT_USED_FOR_TRAINING` |
| 同上 | `Billing {{ connection.billingSubjectType }}` | `TEAM_SUBJECT` |
| `AgentConfigurationPanel.vue:453` | `{{ key }}`（Skill） | `coding.patch.apply` |

同时 eyebrow 一律英文且与页面其余中文混排：`Settings · Model governance`、`Trusted model plane`、`Server registry`、`Owner-scoped credentials`、`Governance delivery`、`One-way credential input`、`Immutable history`。

**而正确范式就在同一个代码库里**：`SetupPage.vue:34-60` 有完整的 `statusLabel` / `statusTone` / `capabilityLabel` / `capabilityDescription` / `reasonLabel` / `actionLabel` 六个映射函数，把每一个后端枚举翻译成人话——包括把 `PERSONAL_AGENT_CONFIGURATION_REQUIRED` 翻成"Personal Agent 尚未完成模型配置"。**一个页面做对了，另外十三个页面各自裸奔。**

#### 3.7.9 配置面自己也有永久占位

§3.2 指出导航里有两个从 M1 起就禁用的占位项（M9 已决定移除）。同类问题在配置面还有三个，`ModelSettingsPage.vue:308`：

| 卡片 | 徽章 | 文案 |
|---|---|---|
| Team 模型默认 | `API 待交付` | "领域已定义 AgentModelDefault，管理 API 尚未交付。" |
| Provider / Catalog 允许列表 | `只读边界` | "Agent Preflight 会执行治理求交集，策略编辑 API 尚未交付。" |
| 预算与配额 | `API 待交付` | "当前没有公开 Budget Policy 目录与编辑契约。" |

诚实是好的，但**把三张"以后会有"的卡片常驻在配置页上，等于每次进页面都提醒用户产品没做完**。要么给出明确的里程碑归属，要么移出主视图。

#### 3.7.10 Setup Center 做得最好，但它的覆盖面停在 6 个能力

必须点明：`SetupPage.vue` 是全站配置体验最好的一页——就绪进度条、Next step 单卡聚焦、每项能力带状态/原因/责任方/行动按钮、无权限时显示"请联系 XX"而不是空白。**它已经把 §3.5.5 要求的 `reason` + `remedy` 范式实现了一遍。**

它的局限在覆盖面和生命周期：

1. **只覆盖 6 个能力**（`PERSONAL_CONVERSATION`、`TEAM_TASK`、`CODING_REVIEW`、`GITHUB_DRAFT_PR`、`LARK_NOTIFICATIONS`、`TEAM_OBSERVER`），5 个跳转目标不含 `/settings/models`、`/account`、`/team/members`、`/operations`；
2. **只在"还没就绪"时有价值**。`requiredReady` 之后这一页就退化为一屏绿色对勾，而配置会随时间失效——凭证过期、连接被撤销、健康度转 `UNHEALTHY`、Provider 目录修订——这些变化没有任何常驻入口能看到；
3. **配置是 5 条平铺路由**（`/settings/repositories`、`/settings/agents`、`/settings/models`、`/settings/integrations/lark`、`/settings/integrations/github`），加上 `/setup`、`/account`、`/team/members`、`/operations` 共 9 个配置类入口，**没有统一的 Settings 外壳、没有配置导航、没有跨配置搜索**。用户要改一个设置，先得记住它在哪一页。

### 3.8 禁用即沉默，以及其他全站小交互欠账

§3.5.5 指出后端不回答"为什么不能"。查证后发现这不只是状态流转的问题，而是**全站一致的沉默**：

| 现象 | 数据 | 后果 |
|---|---:|---|
| `:disabled` 绑定总数 | **218** | — |
| 其中同元素带 `title` 说明的 | **0** | **每一个禁用控件都不解释自己为什么禁用。** 用户看到一个灰按钮，只能自己猜是权限、状态、网络还是数据没准备好 |

`ModelSettingsPage.vue:243` 的 `:disabled="!canOpenCreate"` 是个典型：它同时意味着"没选 Team"和"Provider 目录还没加载好"两种完全不同的处境，而用户一个都看不到。**§3.5.5 为状态流转要求的 `reason` + `remedy`，应当上升为全站规则。**

另外三项小欠账：

| 项 | 证据 | 后果 |
|---|---|---|
| 滚动位置不恢复 | `app/router.ts:156` — `scrollBehavior: () => ({ top: 0 })`，无条件回顶、忽略 `savedPosition` | 从长列表点进详情再返回，位置丢失，用户每次都要重新滚到原处 |
| 全站零撤销 | `grep -rn "undo"` → 无结果 | 任何误操作都只能靠反向操作补救，而反向操作路径本身就是七步的（§3.5） |
| 数字无千分位 | `toLocaleString` 仅 **5** 处 | Token 数、上下文窗口等大数字裸显示（`ModelSettingsPage` 的模型卡片做对了，其余场景没有） |

零 `draggable`、零 `dblclick`/`contenteditable`（无内联重命名）已在 §3.4 与 §3.5.3 记录，此处不再重复。

### 3.9 全站菜单逐项评审：每个菜单都要能回答三个问题（第五轮专项评审）

前四轮分别审了排版、信息架构、首屏、状态流转与配置。本轮把导航里**其余每一个菜单**当成独立产品逐个走了一遍，判据固定为三个问题：

1. **我为什么来这里**——这个页面回答我的哪一个问题；
2. **我在这里能做什么**——除了看，还能不能动；
3. **看完我去哪**——有没有下一步，还是死胡同。

三个问题里有一个答不上来，这个菜单就只是**数据的陈列柜**，不是产品功能。

#### 3.9.1 逐菜单结论

| 菜单 | 为什么来 | 能做什么 | 去哪 | 关键缺口 |
|---|:-:|:-:|:-:|---|
| Today | ⚠️ | ❌ | ✅ | 见 §3.3：只展示"系统里有什么"，不展示"我要做什么" |
| Setup Center | ✅ | ✅ | ✅ | **全站体验最好的一页**（枚举有中文、进度有条、无权限时显示责任方、Next step 单卡聚焦）；但覆盖面停在 6 项能力，一旦就绪即失去价值（§3.7.10） |
| Work | ✅ | ⚠️ | ✅ | 见 §3.5：看板不可拖、流转要走七步 |
| Activity | ✅ | ❌ | ❌ | 筛选只作用于"已加载的那一页"；筛选选项本身由已加载数据推导；实时状态直接显示 `live` / `cursor-expired`；Actor 筛选要求手填 Principal ID 且不进 URL（同页 Category 却进了 URL）；详情面板倾倒原始 payload 键名；条目不能跳到对应的工作项 |
| 我的 Inbox | ✅ | ✅ | ⚠️ | 五种条目类型是**互斥 Tab，没有"全部"视图**——而每类计数已经算好了，用户要回答"我一共有几件事"必须点五次；处置动作文案翻译是对的（全站正面样本） |
| Team Observer | ❌ | ❌ | ❌ | 整页 30 行，`v-if="phase === 'ready' && scope"` 之外**不渲染任何东西**：加载中与未选团队时是一片空白，无 `StatePanel`、无说明、无下一步 |
| 运行与发布 | ✅ | ✅ | ⚠️ | 全站唯一的轮询点（15 秒硬编码），且**零 `visibilitychange` 处理**——切到后台的标签页照样每 15 秒打一次请求；界面上没有"上次刷新于"，用户无法判断自己看的是不是旧数据 |
| 审计中心 | ✅ | ⚠️ | ⚠️ | 筛选条件全部进 URL（**做得对，可把一次查询发给同事**），但 11 个筛选项里 6 个要求粘贴 UUID；导出只有 JSON 一种格式、文件名固定为 `crewscope-audit-export.json`（同一天导出三次得到三个同名文件），而合规场景最常要的是 CSV |
| 团队成员 | ✅ | ❌ | ❌ | **只能加人，不能改角色、不能移除**——后端确实没有这两个端点；表格里**没有"角色"列**（成员 DTO 不含 `roles`），却有一列 `v{version}`，把内部 ETag 当用户信息展示；加人=粘贴 UUID；Principal ID 被截断且不可复制 |
| Agent 中心 · 模型与凭证 · 飞书与通知 · GitHub 集成 · 仓库设置 | ✅ | ✅ | ⚠️ | 见 §3.7 配置专项；另有后端词汇直接当界面文案（`RepositoryBinding`、`Repository Catalog`、`LOCAL_MANAGED`） |
| Conversation（**实际首屏**） | ⚠️ | ✅ | ⚠️ | 见 §3.9.3——根路径重定向到这里，用户登录后先看到一个空对话框 |
| Account | ✅ | ✅ | ❌ | 改完密码**被静默踢到登录页**（`signOutLocally()` + `router.replace('login')`），全程没有一句解释 |
| Onboarding / Invite | ✅ | ✅ | ⚠️ | 走完引导跳的是 Conversation（空对话框），而不是刚创建好的工作区 |
| 404 | ⚠️ | ❌ | ⚠️ | 整页 13 行、**不带 AppShell**（导航整条消失，用户被"弹出"产品），唯一出口是"返回对话" |
| 无权访问 | ⚠️ | ❌ | ❌ | **说不出缺的是哪个权限**——路由守卫跳转时丢掉了 `requiredPermission`；页面把 `route.query.from` 的原始路径直接打在屏幕上；没有"申请权限 / 联系 Owner"任何动作（这却是全站唯一用了 `StatePanel` 的 `#action` 插槽的页面） |

一个结构性观察：**菜单的体验质量与它离"配置"还是离"观察"有关。** 配置类页面至少都能改东西（问题在呈现，§3.7）；而观察类页面——Activity、Team Observer、Audit、Operations——普遍停在"把数据显示出来"，**看完之后没有任何动作可做、也没有任何地方可去**。这四个页面加起来占了导航的 29%。

#### 3.9.2 移动端：14 个菜单里 12 个根本到不了

这是本轮最严重的发现，且**现有门禁完全测不出来**。

`AppShell.vue` 的响应式规则（`:288-320`）：

```css
@media (max-width: 767px) {
  .app-shell__rail, .mode-switcher, .command-search { display: none; }
  .context-header__actions { display: none; }
  .mobile-mode { position: fixed; inset: auto 0 0; display: grid; grid-template-columns: 1fr 1fr; }
}
```

两个后果：

1. **导航整条被 `display: none`，替代品只有一个两格底栏（对话 / 工作台）。** 全站没有任何汉堡菜单、抽屉或折叠导航（`grep -rn "drawer\|hamburger\|nav-toggle"` 无命中）。也就是说在手机和窄窗口上，**Inbox、Activity、审计、成员、Agent、模型、飞书、GitHub、仓库、运维、Setup、Team Observer 这 12 个菜单没有任何入口可以到达**——不是难用，是不可达。
2. **`.context-header__actions` 也被隐藏**，于是每个页面的主操作按钮在移动端一并消失。即使用户靠手输 URL 到了某个页面，他也做不了那个页面最主要的事。

为什么四断点视觉基线没发现：**视觉基线比的是像素，不是可达性。** 390px 下截图确实和上次一致——因为导航从一开始就是隐藏的，隐藏状态被当成了正确基线固化下来。`M9-Q01` 必须补一条**可达性断言**（在 390px 下逐个菜单可点达），而不是只补一张截图。

#### 3.9.3 入口不是目录，是一个空对话框——§3.3 的结论要修正

§3.3 写的是"登录后看到的是目录"。查证路由后必须修正：`app/router.ts` 的第一条是

```ts
{ path: '/', redirect: { name: 'conversation' } }
```

**根路径重定向到 Conversation，而不是 Today。** `OnboardingPage.enterConversation()` 走完引导后也是 `router.replace({ name: 'conversation' })`。所以真实的首屏不是"系统里有什么"的目录，而是**一个还没有任何消息的空对话框**。

这比原结论更严重：目录至少告诉用户系统里有什么，空对话框只告诉用户"请开始输入"——而新用户此刻恰恰不知道该输入什么。`M9-F07`（个人工作台首页）因此还要多做一件事：**把根路径的落点从 Conversation 改到工作台首页**，并让 Conversation 成为一个用户主动选择进入的模式，而不是默认着陆点。

#### 3.9.4 排版问题比 §3.1 记录的更严重

§3.1 的 576 处 `≤10px` 是用 `font-size: ` 前缀统计的，**漏掉了 `font:` 简写**（而配置面恰好大量使用 `font: 10px/1.4`）。用容错正则重测全站 `.vue`：

| 字号 | 出现次数 |
|---:|---:|
| 7px | 43 |
| 8px | 214 |
| 9px | 229 |
| 10px | 194 |
| 11px | 63 |
| 12px | 44 |
| 13px | 15 |
| **14px（规范正文）** | **12** |
| ≥15px | 92 |

**修正后的数字：全站 906 处字号声明中 743 处（82%）`≤11px`，其中 486 处 `≤9px`、43 处 `7px`；而规范规定的正文 14px 只有 12 处。** 也就是说产品的实际正文字号是 8–9px，不是原先估计的 8–10px；`≤10px` 的真实数量是 **680** 处，比 §3.1 记录的 576 多出 104 处。字号最集中的七个文件：`TaskDetailDrawer`(37/43)、`AgentConfigurationPanel`(29/33)、`ReviewWorkbench`(28/30)、`LarkNotificationAdmin`(27/30)、`AuditExplorer`(27/30)、`OperationsWorkspace`(25/29)、`ConversationPage`(24/32)——**恰好就是用户停留时间最长的七个界面**。

#### 3.9.5 把 UUID 当输入法

本轮统计：13 个生产界面文件里出现 31 处 `UUID` 字样，另有 4 处直接用 `Principal ID` 当字段标签。典型场景包括：加成员、Agent Executor 指派、Advisory Reviewer 指派、TaskIntent 执行者、审计的 6 个主体筛选、Activity 的 Actor 筛选。

关键在于**数据早就在手边**：`TeamMemberSummary` 已经带 `displayName`，`scopeStore.state.members` 在前端已经加载完毕。用户被要求去别处复制一个 36 位十六进制串，只为了填一个界面自己已经知道答案的字段。这是"推广已有答案"这条主线在本轮的又一次出现。

唯一真正缺后端的是**主体目录**：审计的 `agentPrincipalIds` / `initiatorIds` / `actorIds` 可能指向用户也可能指向 Agent，需要一个可列举的主体查询才能做成选择器——对应 `M9-A07`。

#### 3.9.6 语言与元数据：产品在浏览器里没有名字

| 事实 | 数据 | 后果 |
|---|---|---|
| 页面 `eyebrow` 全为英文，标题全为中文 | 17 个页面 100% 中英混排（如 `Collaborate / Read-only team summary` 配"团队观测"） | §3.2 的"命名语言不统一"不只在导航，而是**每一页的页眉都在混排** |
| 后端枚举裸插值 | 54 处 | 用户读到 `ACTIVE`、`LOCAL_MANAGED`、`cursor-expired`、`OWNERSHIP` |
| `document.title` 从未被设置 | **0 处** | 开多个标签页时全部显示同一个标题，用户无法在标签栏里区分"审计"和"成员"；也无法用浏览器历史检索 |
| 生产导航零 `aria-current` | `AppShell.vue` 的主导航（`:182`）、模式切换（`:216`/`:219`）、移动底栏（`:251`/`:252`）全部只用 `:class="{ active }"` | 屏幕阅读器用户听不出当前在哪个菜单。**Axe 不会报这一条**（它不检查语义化的当前项标记），所以既有可访问性门禁全绿 |

全站 12 处 `aria-current` 中 9 处在 spike/story 固件里，真正的生产命中只有 `InboxWorkspace`、`CodingProgressControl`、`AgentSettingsPage` 三处——**正确写法在项目里存在，只是没用在最主要的导航上。**

#### 3.9.7 筛选只作用于"已加载的那一页"，而服务端参数早就在那儿

`ActivityPage.vue:35` 的 `filteredItems` 在 `store.state.teamActivity.value`（即已加载的那一页）上做客户端过滤，`:45` 的 Category 选项也从这一页的数据推导，`:113` 的 `{{ filteredItems.length }} / {{ total }}` 显示的是"已加载的 N 条里匹配 M 条"。三个连带后果：

- **筛选选项取决于你翻了多少页**——某个类别只出现在第三页时，它在筛选器里根本不存在；
- **计数会骗人**——用户看到"3 / 50"，以为全团队只有 3 条该类别事件；
- **"加载更多"与筛选互相打架**——加载更多按未筛选的游标取下一页，取回来再被客户端筛掉，用户点三次可能一条新的都不增加。

而服务端**早就支持服务端筛选**：`TeamActivityController:63-68` 已接受 `workItemId`、`categories`、`eventTypes`、`actorPrincipalIds`、`after`、`limit`；`AuditController:59-71` 接受 11 个筛选参数。**这是纯前端欠账，一行后端都不用改。** 审计页做对了（筛选下推服务端且进 URL），Activity 页没有——同一个代码库里的两种做法。

#### 3.9.8 本轮小结：菜单的问题是同一批地基缺口的第二次显影

本轮 16 个菜单的缺口，几乎全部能归到前四轮已经识别的地基上：`StatePanel` 没用全（Team Observer、404）、`labels.ts` 没有（54 处裸枚举）、格式化器没有（计数、时间）、`reason`/`remedy` 没有（无权访问页说不出缺哪个权限）、偏好与 URL 状态不统一（Activity 的 Actor 筛选不进 URL）。

**唯一的新缺口是移动端可达性**（§3.9.2）和**浏览器级元数据**（§3.9.6）——这两项此前从未被任何一轮评审或任何一道门禁覆盖过。唯一真正缺后端能力的是**成员角色与移除**（读侧角色字段归 `M9-A07`，写侧的改角色/移除属于领域增量，建议放 M11 而不是硬塞进 M9）。

### 3.10 代码可维护性

| 问题 | 数据 |
|---|---|
| 页面组件过大 | `WorkPage.vue` 1357 行、`ConversationPage.vue` 1059 行（含 script/template/style） |
| CSS 压成单行 | 20+ 文件存在单行超 400 字符的 `<style scoped>`，`CodingExecutionStudio.vue:286` 一行约 3400 字符 |
| 无原子样式/工具类 | 556 处硬编码 `padding`，相同的卡片样式在 30+ 组件中重复实现 |
| 无 ESLint | `package.json` 的 `lint` 实为 `vue-tsc --noEmit`，只做类型检查，无代码规范检查 |

单行 CSS 让 `git diff` 完全不可读——任何样式改动都表现为整行重写，Code Review 失效。这与项目其余部分的高工程标准形成强烈反差。

### 3.11 前端做得好的地方（必须保留）

1. **领域分层干净**。`domains/*/{gateway,store,types}.ts` 的 19 个域划分清晰，gateway/store 分离，与后端 Query/Command 一一对应。
2. **状态完备性**。`StatePanel` 统一了 Loading / Empty / Error / Forbidden / Offline / CursorExpired 六态，每个工作台都完整覆盖——这是很多商业产品都没做到的。
3. **无障碍基础扎实**。Skip Link、`aria-live`、焦点陷阱、Axe 门禁、双视口 Playwright 视觉基线，248 项 E2E 全绿。
4. **安全意识前置**。`check-web-sensitive-fields.mjs` 扫描公开 DTO 字段泄漏，前端明确声明"筛选不构成授权边界"。
5. **离线与冲突处理**。草稿保留、强 ETag 冲突回读、Cursor 过期恢复——这些细节说明作者真正理解分布式前端。

---

## 4. 竞品对照

### 4.1 能力矩阵

| 维度 | CrewScope | vibe-kanban | multica |
|---|---|---|---|
| 团队责任链 / Owner·Executor·Reviewer | **✅ 独有** | ❌ | 部分 |
| 审计中心 / 合规证据 | **✅ 独有** | ❌ | ❌ |
| Human Gate / 动作回执对账 | **✅ 独有** | ❌ | 部分 |
| 耐久执行（Lease/Fencing/恢复） | **✅ 领先** | 部分 | 部分 |
| Coding Sandbox 隔离 | **✅ 领先** | 部分 | ✅ |
| 状态流转交互 | ❌ 下拉 + 提交（七步） | ✅ 卡片直接拖拽 / 一键动作 | ✅ 动作按钮 |
| 看板拖拽改状态 | ❌ 有看板无拖拽 | ✅ | ✅ |
| Diff 语法高亮 / 行号 / 分栏 | ❌ 三项全无 | ✅ | ✅ |
| Diff 行级评论 | ❌ | 部分 | ✅ |
| 全局 Toast / Tooltip / 统一确认 | ❌ 三项全无 | ✅ Radix + sonner | ✅ shadcn |
| 列表排序 / 批量操作 | ❌ | ✅ | ✅ |
| 配置表单未保存保护 | ❌ 全站零脏态跟踪 | ✅ | ✅ |
| 配置版本对比 | ❌ 有不可变历史但看不出差异 | 无此概念 | 部分 |
| 配置项搜索 | ❌ 零搜索控件 | 部分 | ✅ |
| 禁用控件原因说明 | ❌ 218 个 disabled / 0 个说明 | ✅ Tooltip | ✅ |
| 移动端导航可达性 | ❌ 12/14 菜单不可达 | ✅ 抽屉导航 | ✅ 两端完整 |
| 浏览器标签标题 | ❌ `document.title` 0 处 | ✅ | ✅ |
| 导航当前项语义标记 | ❌ 0 处 `aria-current` | ✅ | ✅ |
| 服务端筛选下推 | ⚠️ 参数已存在但 Activity 页忽略 | ✅ | ✅ |
| 成员角色管理 | ❌ 只能加人，无角色/移除 | 无此概念 | ✅ |
| 列表筛选可分享（进 URL） | ⚠️ 审计页做对，其余不一致 | ✅ | ✅ |
| 相对时间展示 | ❌ 全为绝对时间 | ✅ | ✅ |
| 面包屑 / 偏好记忆 | ❌ | ✅ | ✅ |
| 命令面板（⌘K） | ❌ 装饰 | ✅ `cmdk` | ✅ |
| 序列快捷键 | ❌ | ✅ 完整注册表 | ✅ |
| 可拖拽分栏 | ❌ | ✅ | ✅ |
| 虚拟滚动 | ❌ | ✅ | ✅ |
| 暗色模式 | ❌ | ✅ | ✅ |
| i18n | ❌ | ✅ | ✅ 多语言 |
| 桌面 / 移动端 | ❌ | ✅ | ✅ 两端 |
| 长期记忆 / RAG | ❌ | ❌ | 部分 |
| MCP 生态接入 | ❌ | ✅ | ✅ |

### 4.2 结论

**CrewScope 的产品定位是对的，功能纵深是领先的，输在交付层的完成度。**

竞品的技术选型（Radix/shadcn + Tailwind + cmdk + resizable-panels + virtuoso + next-themes）本质上是**用成熟组件生态换取交互深度**。CrewScope 选择了零 UI 依赖的手写路线，在可控性上有价值，但代价是所有高级交互都要自己实现，而目前一个都没实现。

**不建议**推倒重来换 React/Tailwind——那会丢掉 248 项 E2E 基线、19 个域的干净分层和全部可访问性资产。**建议**在 Vue 3 + 原生 CSS 的既有路线上，补齐设计系统与交互内核这两块被跳过的地基。

但"零 UI 依赖"这条原则需要做一处有限度的松动：**语法高亮不应自己写**。这是一个有明确正确答案、且实现成本远高于收益的领域（词法分析 + 数十种语言语法 + 增量渲染）。§4.1 新增的十二行对照说明，CrewScope 与竞品的差距已不只是"缺高级交互"，而是**在自己的核心场景（读代码、评代码）上落后于通用代码托管平台**。因此 M9 允许且仅允许为语法高亮引入一个新生产依赖，并在 `M9-S01` 中用体积、双主题 Token 对齐、不外发代码、虚拟滚动兼容、可降级五条约束把这个依赖框死——其余全部体验缺口（Toast、Tooltip、Dialog、Skeleton、排序、批量、相对时间、面包屑、偏好）继续自建，因为它们都是薄组件，自建的可控性收益大于依赖成本。

---

## 5. 风险清单

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| 字号整改引发全量视觉基线失效 | 高 | 中 | M9 一次性重拍基线，并在同一 PR 内完成；先建立字号门禁再改样式 |
| 无 OpenAPI 导致 M9 前端重构中 DTO 漂移 | 中 | 高 | `M9-I01` 前置，作为 M9 其余任务的硬依赖 |
| 前端大改破坏既有 Coverage 与 Axe 门禁 | 中 | 高 | 组件级迁移，每次迁移保持 Story + Spec 同步；Coverage 阈值只允许单向 ratchet |
| M10 引入 RAG/Memory 扩大攻击面 | 中 | 高 | 沿用现有 Ownership/ExecutionScope 交集模型，知识库继承 Team 边界，新增固定攻击集 |
| 里程碑范围膨胀 | 高 | 中 | M9 明确不做 i18n、不做移动端、不做 MCP；这些属于 M11 |
| M9 因第二轮追加的 3 个工作包而超期 | 高 | 中 | 周期由 6–8 周上调为 8–10 周；波次按"首屏 → Diff → 其余"排优先级，压缩排期时按 `F07` > `F08` > `F04/F05/F06` 顺序保留 |
| 引入语法高亮依赖带来包体积与供应链风险 | 中 | 中 | `M9-S01` 用五条约束（体积 ≤30KB gzip、语言包懒加载、双主题 Token 对齐、代码不外发、可降级）选型；纳入 SBOM 与既有依赖审计；`M9-Q01` 增加包体积预算门禁 |
| 前后端状态机静默漂移（已存在，非新增风险） | 中 | 高 | `M9-I01` 由 domain 的 `ALLOWED_TRANSITIONS` 生成前端常量并加漂移门禁，彻底消除手抄；生成前先跑一次一致性比对，确认当前两份确实无差异 |
| 动作可用性 API 被误用为授权边界 | 中 | 高 | `M9-A05` 明确其为**可发现性**接口，服务端在实际执行时仍逐次完整裁决；集成测试证明伪造可用性响应不能绕过任何校验 |
| 行级评论被误用为第二套审批状态 | 中 | 高 | ADR-029 明确评论是 Review 的补充证据，不参与裁决；`M9-A04` 以回归测试证明评论存在与否不改变任何 Review 判定 |
| 配置表单加脏态保护后阻塞既有自动跳转 | 中 | 中 | `M9-F10` 只拦截用户主动导航；Team 切换这类系统性 Scope 变更仍强制生效，但必须先把当前表单落为本地草稿并在新 Scope 下给出恢复入口；`M9-Q01` 用 E2E 证明切 Team 不会被守卫卡住 |
| 配置版本对比被误当成配置回滚入口 | 中 | 高 | `M9-A06` 只提供历史 Revision 的**只读**全量载荷，不提供任何"应用历史版本"的写路径；回滚仍走正常的追加新 Revision 流程，历史保持不可变 |
| 全站补禁用原因时把内部细节泄漏给无权限用户 | 中 | 高 | `reason` 取自 `M9-A05` 冻结的稳定枚举而非异常消息；无权限场景只返回责任方（沿用 `SetupPage` 的 `responsibleParty` 口径），不返回内部状态；纳入 `M9-Q01` 的公开字段扫描 |
| 撤销窗口被误解为软删除或第二套状态 | 中 | 高 | 撤销实现为一条**反向流转命令**，走完整命令路径并产生事件与 Audit；窗口过期即不可撤销，不保留任何隐藏中间态；`REJECTED`/`ARCHIVED` 不提供撤销，改为二次确认 |
| 移动端导航重做与四断点视觉基线冲突 | 高 | 中 | 当前 390px 基线固化的是"导航已隐藏"的状态，补抽屉导航必然导致全量重拍；`M9-F11` 与 `M9-F02` 同批提交并一次性重拍，且新增的是**可达性断言**（390px 下逐菜单可点达）而不是又一张截图——`M9-Q01` 明确区分"像素门禁"与"可达性门禁"两类 |
| 筛选从客户端下推到服务端后结果集发生变化 | 高 | 中 | Activity 页当前的计数与筛选选项都基于已加载页，下推后数字必然变大（这是修正而非回归）；`M9-F11` 在迁移前先记录两种口径的差异并写进 PR 说明；E2E 断言改为"筛选结果与服务端全量一致"，不再断言旧的 `N / M` 口径 |
| 成员管理写路径被顺手塞进 M9 | 中 | 高 | 改角色/移除会触碰权限模型、事件流与 Audit，属领域增量；M9 **只做读侧**（`M9-A07` 补 `roles` 字段 + 主体目录查询），写侧明确放 M11 并在成员页以"角色调整请联系 Owner"显式说明能力边界，而不是给一个点了没反应的按钮 |
| 根路径落点改动影响既有 E2E 与深链接 | 中 | 中 | `/` 由 Conversation 改为工作台首页会使所有依赖"登录后落在对话页"的 E2E 失败；`M9-F07` 与 `M9-F11` 同批修改 E2E 并保留 `/conversation` 具名路由不变（只改默认落点，不移除任何入口） |
| 基础组件重建导致 22 个页面同时回归 | 中 | 高 | `M9-F01` 保持既有组件 API 向后兼容；按组件逐个迁移，每次迁移同批更新 Story/Spec/基线，不留半迁移状态 |

---

## 6. 后续里程碑建议

| 里程碑 | 主题 | 核心命题 | 建议周期 |
|---|---|---|---:|
| **M9** | 产品体验重构与设计系统 | 把"能用"变成"好用"：个人工作台首页、**状态流转从表单改为动作（状态徽章即动作入口 + 状态机生成 + 动作可用性 API + 撤销窗口）**、**各种配置的体验重构（可读字号、未保存保护、参数边界可见、版本对比、配置搜索、统一外壳）**、设计 Token 体系与基础组件层、反馈闭环（Toast/Confirm/Tooltip/Skeleton）、Diff 阅读器与行级 Review 评论、列表能力（排序/批量/相对时间）、信息架构重构、**全站菜单体验对齐（移动端导航可达、页面元数据、观察类菜单补行动闭环、UUID 改选择器、服务端筛选下推）**、交互内核（⌘K/快捷键/分栏/虚拟滚动/主题）、契约生成 | 8–10 周 |
| **M10** | Agent 智能跃迁与知识闭环 | 让 Agent 越用越懂团队：长期记忆、代码库 RAG、Skill 沉淀、多 Agent 协同、成本与质量可观测 | 8–10 周 |
| **M11** | 协作规模化与开放生态 | 从单团队到多团队：实时协同、WorkGraph、MCP/插件、i18n、桌面端 | 8–10 周 |
| **M12** | 企业化与多租户治理 | 从自托管到可售卖：多 Organization、SSO/SCIM、配额计费、K8s 执行拓扑、SLO | 10–12 周 |

详细执行清单见：

- [M9：产品体验重构与设计系统](../plans/M9-产品体验重构与设计系统.md)
- [M10：Agent 智能跃迁与知识闭环](../plans/M10-Agent智能跃迁与知识闭环.md)
- [M11：协作规模化与开放生态](../plans/M11-协作规模化与开放生态.md)
- [M12：企业化与多租户治理](../plans/M12-企业化与多租户治理.md)

---

## 7. 立即可执行的低成本修复

以下二十八项不依赖 M9 排期，可在任何一次提交中顺带完成，收益/成本比极高：

| 项 | 动作 | 成本 |
|---|---|---|
| 1 | 移除 `futureNavigation` 的两个 disabled 占位项 | 10 分钟 |
| 2 | 移除 `⌘K` 按钮上的 `<kbd>` 提示，或直接隐藏该按钮直到 M9 实现 | 10 分钟 |
| 3 | 隐藏无 handler 的通知铃铛 | 5 分钟 |
| 4 | 导航文案统一为中文（`Today`→`今日`，`Work`→`工作`，`Activity`→`动态`，`Setup Center`→`配置中心`，`Team Observer`→`团队观测`） | 20 分钟 |
| 5 | `tokens.css` 增加 `--cs-text-xs/sm/base/lg` 四个字号 Token 并在 `base.css` 设置 `body { font-size: var(--cs-text-base) }`，为 M9 铺路 | 30 分钟 |
| 6 | 新增 `formatRelativeTime()` 工具函数并先在 Inbox、Activity、Task 列表三处替换绝对时间（悬浮仍显示完整时间） | 1 小时 |
| 7 | `CodingDiffExplorer` 补上新旧双列行号——不依赖任何新库，只是渲染 hunk header 已有的行号信息 | 1–2 小时 |
| 8 | 给首页、Inbox、Work 三处空状态填上 `StatePanel` 的 `action` 插槽（"导入仓库"/"新建工作项"/"浏览团队工作"） | 1 小时 |
| 9 | 加一个跨语言一致性测试：把 `WorkItem.ALLOWED_TRANSITIONS` 导出为 JSON 固件，断言与 `allowedWorkItemTransitions` 逐项相等——在 M9-I01 的正式生成方案落地前先把漂移**变成可见的红灯** | 1–2 小时 |
| 10 | `WorkItemDetailDrawer` 的下拉改为动作按钮组（把 `transitions` 直接渲染成按钮，点击即进确认），复用 `TaskControlPanel` 已有的确认对话框模式——不依赖任何后端改动，七步降到三步 | 半天 |
| 11 | `ReviewWorkbench` 的结论下拉改为四个按钮，`REJECTED` 用 `variant="danger"`，去掉标签里的裸枚举名 | 1–2 小时 |
| 12 | `ModelCredentialDialog.vue:102` 提交前对 API Key 做 `trim()`——粘贴凭证时带上的尾随换行是凭证验证失败最常见、也最难自查的成因 | 5 分钟 |
| 13 | 同一对话框复用已有的 `components/auth/AuthPasswordField.vue`，让 API Key 支持明文切换；并给过期时间的 `datetime-local` 补 `min` | 半小时 |
| 14 | `AgentConfigurationPanel` 的四个数值字段补 `min`/`max`/`step`，并把区间写进标签（Temperature 0–2、Top P 0–1、重试上限 1–10）——边界早已写在校验函数里，只是没说给用户听 | 半小时 |
| 15 | `app/router.ts:156` 改为 `scrollBehavior: (to, from, saved) => saved ?? { top: 0 }`，恢复从详情返回长列表时的滚动位置 | 5 分钟 |
| 16 | 把 `SetupPage.vue:34-60` 的六个枚举翻译函数提到 `domains/*/labels.ts`，先在 `ModelSettingsPage` 替换 `status`/`healthStatus`/`retentionMode`/`billingSubjectType` 四处裸枚举 | 1–2 小时 |
| 17 | 给配置面最常被手抄的三个标识符（Connection ID、Agent ID、完整 `configurationHash`）加复制按钮，直接复用 `TeamInvitationManager.vue:108` 已有的复制态实现 | 1 小时 |

| 18 | 17 个页面的 `eyebrow` 全部中文化（`Collaborate / Read-only team summary` → `协作 · 团队只读概览`），消除"英文页眉 + 中文标题"的全站混排 | 20 分钟 |
| 19 | 给 22 条路由的 `meta` 加 `title`，在 `router.afterEach` 里设 `document.title`——当前全站 0 处，开多个标签页时用户无法区分"审计"和"成员" | 20 分钟 |
| 20 | `AppShell.vue` 的主导航（`:182`）、模式切换（`:216`/`:219`）、移动底栏（`:251`/`:252`）补 `aria-current="page"`，写法直接抄同项目 `InboxWorkspace.vue:165` | 15 分钟 |
| 21 | `TeamObserverPage.vue` 把 `v-if="phase === 'ready' && scope"` 之外的分支接上 `StatePanel` 六态——当前加载中与未选团队时整页空白 | 20 分钟 |
| 22 | 删掉 `TeamMembersPage` 表格里的 `v{{ member.version }}` 列——内部 ETag 不是用户信息；腾出的位置留给后续的"角色"列 | 5 分钟 |
| 23 | 路由守卫跳转 `/access-denied` 时把 `requiredPermission` 一并带进 query，页面显示"缺少 X 权限"而不是只回显原始路径——当前守卫把它丢掉了，导致无权访问页**说不出缺的是哪个权限** | 半小时 |
| 24 | `OperationsPage` 的 15 秒轮询加 `visibilitychange` 守卫（后台标签页暂停），并在界面上显示"上次刷新于"——当前是全站唯一轮询点且零可见性处理 | 半小时 |
| 25 | `ActivityPage` 的 Category 筛选改为下推到服务端**已经存在**的 `categories` 参数（`TeamActivityController:63-68`），同时修掉"筛选选项由已加载页推导"与"`N / M` 计数只反映已加载页"两个连带问题 | 1–2 小时 |
| 26 | `ActivityPage` 的 Actor 筛选从自由文本改为基于已加载的 `scopeStore.state.members`（已带 `displayName`）的选择器，并把选中值写进 URL query（与同页 Category 口径一致） | 1–2 小时 |
| 27 | `InboxPage` 增加一个"全部"视图（`itemTypes` 传全集而非单值）——每类计数已经算好了，用户当前要回答"我一共有几件事"必须点五次 | 1 小时 |
| 28 | `NotFoundPage` 套上 `AppShell`——当前 404 会让整条导航消失，用户被直接"弹出"产品，唯一出口是"返回对话" | 半小时 |

> 第 2、3 项遵循一条原则：**宁可不给功能，不可给假功能。**
>
> 第 6–8 项遵循另一条原则：**先让最高频的三个位置变好，再谈全站统一。** 三项都不引入新依赖、不改后端、不动数据模型。
>
> 第 9–11 项是状态流转的「无后端依赖前置改造」：第 9 项把已存在的漂移风险变成红灯，第 10、11 项只把下拉换成按钮（按钮来源仍是那份手抄常量，但交互步数立刻从七步降到三步）。**完整修复仍需 `M9-I01` + `M9-A05` + `M9-F05` 三者配合**，但这三项可以今天就做，且不会与后续方案冲突。
>
> 第 12–17 项是配置面的同类前置改造，合计约一天，且全部不依赖后端。其中只有第 12 项是**修缺陷**而非改体验（未 `trim` 的凭证会静默验证失败）；第 14、16、17 项则是把项目里已经写对的实现——校验函数里的参数边界、`SetupPage` 的枚举翻译、`TeamInvitationManager` 的复制态——搬到还没用上的地方。**本轮评审的多数修复都属于"推广已有答案"，而不是"引入新方案"。**
>
> 第 18–28 项是第五轮菜单走查的产物，合计约一天半，同样全部不依赖后端。其中第 20、21、25、26 项仍是"推广已有答案"：`aria-current` 的正确写法在 `InboxWorkspace` 里、`StatePanel` 六态在其余 21 个页面里、服务端筛选参数在 `TeamActivityController` 里、成员 `displayName` 在前端 store 里——**四处答案都已经存在，只是没用在这里。** 唯一没有内部答案可抄的是移动端导航（§3.9.2 的 12/14 不可达），它需要新建抽屉组件，因此归入 `M9-F11` 而不是本节。

---

## 8. 评审证据

| 结论 | 复现命令 |
|---|---|
| 字号分布 | `rg -o "font-size: (\d+)px" crewscope-web/src -g '*.vue' -r '$1' --no-filename \| sort -n \| uniq -c` |
| 硬编码间距 | `rg -c "padding: [0-9]" crewscope-web/src -g '*.vue' --no-filename \| wc -l` |
| 无暗色模式 | `rg "prefers-color-scheme" crewscope-web/src` → 无结果 |
| 无虚拟滚动 | `rg "virtual\|IntersectionObserver" crewscope-web/src -g '*.vue' -g '*.ts'` → 无生产结果 |
| 无看板拖拽 | `rg "draggable\|dragstart" crewscope-web/src -g '*.vue'` → 无结果 |
| 装饰按钮 | `rg -n "command-search\|icon-button" crewscope-web/src/components/layout/AppShell.vue` |
| 无 OpenAPI | `rg "springdoc\|swagger" --type xml .` → 无结果 |
| 首页不含工作事实 | `wc -l crewscope-web/src/pages/TodayPage.vue` → 134；`sed -n '65p' crewscope-web/src/pages/TodayPage.vue` |
| 无责任人过滤 | `rg -n "RequestParam" crewscope-server/src/main/java/io/crewscope/server/api/WorkItemQueryController.java` → 仅 `status` / `after` / `limit` |
| 流转是下拉 + 提交 | `sed -n '249p' crewscope-web/src/components/domain/WorkItemDetailDrawer.vue` → `<select v-model="transitionTarget">` + `提交流转` |
| Review 结论同为下拉 | `sed -n '318p' crewscope-web/src/components/domain/ReviewWorkbench.vue` → 四个等权 `<option>`，含裸枚举名 |
| 正确范式已存在但未推广 | `sed -n '178,211p' crewscope-web/src/components/domain/TaskControlPanel.vue` → 暂停/恢复/重试/取消四个动作按钮 |
| 看板有列无拖拽 | `sed -n '1121,1126p' crewscope-web/src/pages/WorkPage.vue` → 按状态分列，卡片仅 `@select` |
| 前端手抄状态机 | `sed -n '175,184p' crewscope-web/src/domains/workitem/types.ts` vs `sed -n '23,45p' crewscope-domain/src/main/java/io/crewscope/domain/workitem/WorkItem.java` |
| 状态机零对外暴露 | `grep -rn "allowedTransitions\|availableTransitions\|availableActions\|nextStates" --include="*.java" crewscope-domain/src/main crewscope-application/src/main crewscope-server/src/main` → 无结果 |
| 后端状态机分散数量 | `grep -rn "ALLOWED_TRANSITIONS\|TRANSITIONS = Map.of" --include="*.java" crewscope-domain/src/main \| grep -c "Map.of\|=$"` → 17+ 个聚合 |
| 基础组件层缺失 | `ls crewscope-web/src/components/base crewscope-web/src/components/feedback` → 仅 `BaseButton`/`StatusBadge`/`types.ts` 与 `GlobalErrorBanner`/`StatePanel` |
| Dialog 重复实现 | `grep -rl 'role="dialog"' crewscope-web/src --include="*.vue" \| wc -l` → 15 |
| 焦点陷阱重复实现 | `grep -rl "focusable\|Tab.*preventDefault" crewscope-web/src --include="*.vue" \| wc -l` → 9 |
| 无 Toast / 无 Tooltip 组件 | `grep -rn "useToast\|Tooltip" crewscope-web/src --include="*.vue"` → 无结果；`grep -rho 'title="' crewscope-web/src --include="*.vue" \| wc -l` → 330 |
| 原生 confirm | `grep -n "window.confirm" crewscope-web/src/pages/GitHubSettingsPage.vue` → 246 行 |
| 零过渡动效 | `grep -rc "<Transition" crewscope-web/src --include="*.vue"` → 0；`grep -rho "@keyframes" crewscope-web/src \| wc -l` → 9 |
| 零排序 / 零批量 | `grep -rn "sortBy\|orderBy\|selectedIds\|批量" crewscope-web/src --include="*.vue"` → 无结果 |
| 时间格式不统一 | `grep -rho "Intl.DateTimeFormat\|toLocaleString" crewscope-web/src \| sort \| uniq -c` → 24 / 7，且无任何相对时间实现 |
| 空状态是死胡同 | `grep -rl "StatePanel" crewscope-web/src --include="*.vue" \| wc -l` → 36；`grep -rn 'slot="action"\|#action' crewscope-web/src --include="*.vue" \| wc -l` → 1 |
| Diff 无高亮/行号/分栏/行评论 | `cat crewscope-web/package.json`（生产依赖仅 `@lucide/vue`、`dompurify`、`markdown-it`、`vue`、`vue-router`，无高亮库）；`sed -n '100,105p' crewscope-web/src/components/domain/CodingDiffExplorer.vue`（仅按 `+`/`-` 前缀着色） |
| 零面包屑 | `grep -rn "breadcrumb\|Breadcrumb" crewscope-web/src` → 无结果（22 条路由） |
| 偏好不持久化 | `grep -rn "localStorage.setItem" crewscope-web/src \| wc -l` → 1 |
| 断点碎片化 | `grep -rho "max-width: [0-9]*px" crewscope-web/src \| sort -u \| wc -l` → 38（180px–1420px） |
| URL 状态管理（做得好，需保留） | `grep -rho "router.replace\|router.push" crewscope-web/src \| wc -l` → 81 |
| 配置面字号集中度 | 对 14 个配置界面执行 `grep -oE "font(-size)?: *[0-9.]+px\|font: *[0-9]+ *[0-9]+px"`，再 `awk` 取 ≤11 的条数 → **173 / 212（82%）** |
| 配置表单输入框 10px | `grep -n "font: 10px" crewscope-web/src/components/domain/AgentConfigurationPanel.vue`（标签 `9px`、`.field-warning` `8px`、保存提示 `8px`）；同文件 `@media (max-width: 600px)` 把输入框放大到 `16px`——**移动端比桌面端更易读** |
| 全站零脏态保护 | `grep -rnE "dirty\|unsaved\|beforeunload\|onBeforeRouteLeave\|hasChanges" crewscope-web/src --include="*.vue" --include="*.ts" \| grep -v spec` → 仅 `InvitePage.vue` 两行且非此用途 |
| 切 Team 静默丢表单 | `sed -n '93,101p' crewscope-web/src/pages/AgentSettingsPage.vue` → `teamChanged` 时主动 `router.replace` 并把 `agentId`/`configurationRevision` 置空 |
| 参数边界不可见 | 区间只存在于校验函数（temperature 0–2、topP 0–1、attempts 1–10、maxOutputTokens 1–10000000）；`grep -rhoE ' (min\|max\|step)="' crewscope-web/src --include="*.vue" \| wc -l` → 5 |
| 红框无文案 | `grep -rho 'aria-invalid="' crewscope-web/src --include="*.vue" \| wc -l` → 33；对应的可见字段级错误文案仅 26 处且集中在认证页 |
| 历史版本载荷缺失 | `sed -n '64,75p' crewscope-web/src/domains/agent/types.ts`（`AgentConfigurationHistoryItem`）vs `sed -n '93,110p'`（`CurrentAgentConfiguration`）→ 历史项不含 `supplementalInstructions`/`approvedSkillKeys`/`generateOptions`/`memoryPolicy`/`budgetPolicy`/`policyPack*` |
| 配置面零搜索 | `grep -rn 'type="search"' crewscope-web/src/pages/*SettingsPage.vue crewscope-web/src/components/domain/Agent*.vue crewscope-web/src/components/domain/Model*.vue` → 无结果 |
| 模型选择塞进 select | `grep -n "<select" crewscope-web/src/components/domain/AgentConfigurationPanel.vue` → 模型绑定用原生 `<select>`，选项文案由名称与价格字符串拼接 |
| API Key 无明文切换且未 trim | `grep -n 'type="password"' crewscope-web/src/components/domain/ModelCredentialDialog.vue`（无 reveal 按钮）；同文件提交处 `apiKey: apiKey.value` 未 `trim`；而 `components/auth/AuthPasswordField.vue` 已有正确实现 |
| 过期时间可选过去 | `grep -n 'datetime-local' crewscope-web/src/components/domain/ModelCredentialDialog.vue` → 无 `min` 约束 |
| 全站仅一处复制按钮 | `grep -rn "clipboard.writeText" crewscope-web/src --include="*.vue"` → 仅 `TeamInvitationManager.vue:108` |
| 配置面裸枚举当文案 | `grep -n "provider.status\|retentionMode\|billingSubjectType" crewscope-web/src/pages/ModelSettingsPage.vue` → `ACTIVE`/`ZERO_RETENTION`/`TEAM_SUBJECT` 直接上屏，且与英文 eyebrow 混排 |
| 枚举翻译范式已存在 | `sed -n '34,60p' crewscope-web/src/pages/SetupPage.vue` → `statusLabel`/`statusTone`/`capabilityLabel`/`capabilityDescription`/`reasonLabel`/`actionLabel` 六个映射函数，**一个页面做对了，十三个裸奔** |
| 配置面永久占位 | `grep -n "待交付\|只读边界" crewscope-web/src/pages/ModelSettingsPage.vue` → 三张治理占位卡片，与 M9 已决定移除的导航占位同类 |
| Setup 覆盖面有限 | `grep -n "SetupCapability\|goAction" crewscope-web/src/pages/SetupPage.vue` → 仅 6 项能力、5 个跳转目标，不含 `/settings/models`、`/account`、`/team/members`、`/operations` |
| 配置入口平铺无外壳 | `grep -n "settings\|account\|operations" crewscope-web/src/app/router.ts` → 9 个配置类路由平铺，无 Settings 外壳与二级导航 |
| 禁用态零解释 | `grep -rho ':disabled="[^"]*"' crewscope-web/src --include="*.vue" \| wc -l` → **218**；其中同一标签内带 `title=` 说明原因的 → **0** |
| 禁用态语义合并 | `grep -n 'canOpenCreate' crewscope-web/src/pages/ModelSettingsPage.vue` → "未选团队"与"供应商目录未加载"两种完全不同的原因共用一个禁用态 |
| 滚动位置不恢复 | `sed -n '156p' crewscope-web/src/app/router.ts` → `scrollBehavior: () => ({ top: 0 })`，忽略 `savedPosition` |
| 全站零撤销 | `grep -rn "undo\|撤销" crewscope-web/src --include="*.vue"` → 无结果 |
| 数字无千分位 | `grep -rc "toLocaleString" crewscope-web/src --include="*.vue" \| grep -v ":0"` → 仅 5 个文件 |
| 移动端导航被整条隐藏 | `sed -n '288,320p' crewscope-web/src/components/layout/AppShell.vue` → `@media (max-width: 767px)` 下 `.app-shell__rail`、`.mode-switcher`、`.command-search`、`.context-header__actions` 全部 `display: none`，替代品 `.mobile-mode` 只有 `grid-template-columns: 1fr 1fr` 两格 |
| 全站零抽屉/汉堡菜单 | `grep -rniE "drawer\|hamburger\|nav-toggle\|off-canvas" crewscope-web/src/components/layout` → 无命中；14 项 `primaryNavigation`（`AppShell.vue:56-69`）在 767px 以下全部无入口 |
| 根路径落点是空对话框 | `grep -n "path: '/'" crewscope-web/src/app/router.ts` → `{ path: '/', redirect: { name: 'conversation' } }`；`grep -n "enterConversation" crewscope-web/src/pages/OnboardingPage.vue` → 引导完成后同样 `router.replace({ name: 'conversation' })` |
| 字号容错重测 | `grep -rhoE "font(-size)?: *[0-9.]+px\|font: *[0-9.]+px" crewscope-web/src --include="*.vue" \| grep -oE "[0-9.]+px" \| sort -V \| uniq -c` → 906 处总计；7px **43**、8px **214**、9px **229**、10px **194**、11px **63**、14px **12**（`≤11px` 743 处 = 82%，`≤10px` 680 处，`≤9px` 486 处） |
| 浏览器标签无标题 | `grep -rn "document.title" crewscope-web/src` → **0** |
| 生产导航零 `aria-current` | `grep -rn "aria-current" crewscope-web/src --include="*.vue"` → 12 处，其中 9 处在 `spikes/`、`stories/` 固件；生产命中仅 `InboxWorkspace.vue:165`、`CodingProgressControl.vue`、`AgentSettingsPage.vue`；`AppShell.vue:182/216/219/251/252` 全部只用 `:class="{ active }"` |
| 页眉中英混排 | `grep -rho 'eyebrow="[^"]*"' crewscope-web/src --include="*.vue" \| wc -l` → 17，逐条检查全为英文（如 `Collaborate / Read-only team summary`），而同一页 `title` 全为中文 |
| Team Observer 非就绪即空白 | `cat crewscope-web/src/pages/TeamObserverPage.vue`（全文 30 行）→ 仅 `<TeamObserverWorkspace v-if="scopeStore.state.phase === 'ready' && scope">`，无 `v-else`、无 `StatePanel` |
| Inbox 无"全部"视图 | `grep -n "itemTypes\|inboxItemTypes" crewscope-web/src/pages/InboxPage.vue` → `filter` 恒为 `itemTypes: [itemType.value]` 单值，5 个类型互斥；`InboxWorkspace.vue:126-130` 的处置文案映射是正确范式 |
| Activity 客户端筛选 | `sed -n '35,45p;111,118p' crewscope-web/src/pages/ActivityPage.vue` → `filteredItems` 与 Category 选项均从 `store.state.teamActivity.value`（已加载页）推导，`:113` 的 `N / M` 亦然；`actorFilter` 是本地 `ref('')`，不进 URL |
| 服务端筛选参数早已存在 | `sed -n '59,72p' crewscope-server/src/main/java/io/crewscope/server/api/TeamActivityController.java` → 已接受 `workItemId`/`categories`/`eventTypes`/`actorPrincipalIds`/`after`/`limit`；`sed -n '55,75p' .../AuditController.java` → 11 个筛选参数 |
| 审计页筛选做对了 | `grep -n "route.query\|router.replace" crewscope-web/src/pages/AuditPage.vue` → 全部筛选项经 URL query 驱动并回写，链接可分享（**全站正面样本**） |
| 审计导出仅 JSON 且文件名固定 | `sed -n '115,126p' crewscope-web/src/pages/AuditPage.vue` → `type: 'application/vnd.crewscope.audit-export+json'`、`anchor.download = 'crewscope-audit-export.json'`，无 CSV、文件名不含时间或筛选条件 |
| 成员页无角色、有内部版本号 | `grep -n "member.version\|member.status\|member.joinMethod" crewscope-web/src/pages/TeamMembersPage.vue` → 表格五列为 成员/状态/加入方式/加入时间/`v{version}`，无角色列；`sed -n '42,51p' crewscope-web/src/domains/scope/types.ts` → `TeamMemberSummary` 无 `roles` 字段 |
| 成员无变更端点 | `grep -n "Mapping" crewscope-server/src/main/java/io/crewscope/server/api/TeamController.java` → 仅 `POST /{teamId}/members` 与 `GET /{teamId}/members`；`ls crewscope-application/src/main/java/io/crewscope/application/team/` → 有 `AddTeamMemberCommand`，无 Remove/ChangeRole 对应物 |
| 唯一轮询点且零可见性处理 | `grep -rn "setInterval" crewscope-web/src --include="*.vue" --include="*.ts" \| grep -v spec` → 仅 `OperationsPage.vue` 一处 `15_000`；`grep -rn "visibilitychange" crewscope-web/src` → **0** |
| 改密码静默登出 | `grep -n "signOutLocally\|router.replace" crewscope-web/src/pages/AccountPage.vue` → 改密成功后直接 `signOutLocally()` + 跳 `login`，无任何解释文案 |
| 404 不带 AppShell | `cat crewscope-web/src/pages/NotFoundPage.vue`（全文 13 行）→ 无 `AppShell`，唯一出口 `返回对话` |
| 无权访问页说不出缺哪个权限 | `grep -n "access-denied" crewscope-web/src/app/router.ts` → 守卫返回 `{ name: 'access-denied', query: { from: to.fullPath } }`，`requiredPermission` 被丢弃；`cat crewscope-web/src/pages/AccessDeniedPage.vue` → 直接回显 `route.query.from` 原始路径 |
| UUID 当输入法 | `grep -rn "UUID" crewscope-web/src --include="*.vue" \| grep -v spikes \| grep -v stories \| cut -d: -f1 \| sort \| uniq -c` → 13 个文件 31 处；另 `grep -rni "principal id"` → 4 个文件以 `Principal ID` 当字段标签；而 `TeamMemberSummary` 已带 `displayName` |
| 仓库设置泄漏后端词汇 | `grep -n "RepositoryBinding\|Repository Catalog\|LOCAL_MANAGED" crewscope-web/src/pages/RepositorySettingsPage.vue` → 三处后端词汇直接作为用户可见文案 |
| AgentScope 使用面 | `rg -o "import io\.agentscope\.[a-z]+\.[a-z]+" crewscope-agentscope/src/main --no-filename \| sort -u` |
| 后端规模 | `rg -o "@(Get\|Post\|Put\|Patch\|Delete)Mapping" crewscope-server/src/main/java --no-filename \| wc -l` → 206 |
