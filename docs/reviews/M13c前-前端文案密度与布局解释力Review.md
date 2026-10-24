# M13c 前：前端文案密度与布局解释力 Review

日期：2026-10-24。对象：CrewScope 前端全部 27 个路由页面 + 高频领域组件（174 个 domain 组件中按页面挂载关系覆盖 ~50 个）+ 横切基础件（StatePanel/CommandPalette/ToastHost/BaseDialog/ScanToolbar/AppShell/SettingsShell），基线 `482fcada`（M13b-Q01 收口之后）。

评审主旨（用户裁定）：**avoid unnecessary secondary labels and descriptions — let the layout do the explaining.** 避免不必要的次级标签与描述，让布局自己解释。

方法：四路并行全量扫描（设置族 12 页 / 工作族 6 页+大组件 / 知识族 5 页+对话框与注入面板 / 认证横切 6 页+基础件+命令面板数据源），每处文案按统一判例基线三判（冗余/保留/存疑）；主会话另做横切量化（重复文案族全库 grep 计数、「这里」布局自指扫描、eyebrow/note 总量、StatePanel 机制核实）并对高杠杆点亲自定性。**本轮零业务实现修改，只产出本文**；与前两轮（M13 后四旅程轴、M13b 前三轴）正交——本轮只看「文字说了多少」，不看流程接线与语义契约（那是前两轮的完成面）。

判例基线（全文沿用）：

- **保留**：解释数据滞后/投影延迟；解释不可见的行为后果（幂等、单向提交、撤销窗口、不可逆范围 N 项、跨页保留、离线缓存、成本后果）；a11y 必需 sr-only；术语表契约要求的技术详情折叠区；防误读单源文案首现；输入格式契约。
- **冗余**：复述标题/分组/列头语义；hint 复述 label 或 placeholder；解释布局位置（「在这里/下方」）；同一信息页内出现 ≥2 次；解释显而易见的操作；面向开发者的实现细节或路线图语。
- **存疑**：安全文案中面向用户有价值的一半；需产品一次性裁决的全局模式（eyebrow、样板句）。

## 1. 结论

**这个代码库的文案问题不是「写得差」，是「说多了」：布局已经说清楚的地方，文字又说了一遍。**

四路合计 587 条判定：**冗余 108（18%）/ 保留 331（56%）/ 存疑 138（24%）**。保留面扎实得罕见——幂等回执、撤销窗口、跨页保留、离线缓存、成本后果、不可逆范围确认等「不可见行为」文案纪律良好（正面基线 §5），这是 M9–M13b 逐步建成的资产。冗余面则高度收敛：**108 处全部落进 8 个机械模式，其中 4 个模式（同页重复 / 命令面板描述 / loading 复述 / 副标题复述）占 2/3 以上，且绝大多数是「删掉即可」的零风险改法**——不需要重写，只需要删。

三个最高杠杆单点（一处修改消一类问题）：

1. **StatePanel 默认 description 兜底渗出**（S1）：调用点只传 title 时，`loading/empty/forbidden/recovering` 四态的通用填充句（「这里还没有可显示的工作。」等）自动出现在具体上下文里，与语境不匹配还白占一行。改为「无传入不渲染」即消掉整类渗出。
2. **命令面板 24 条 action 的 description 复述 title**（W2）：14 条纯复述（「打开团队成员/查看 Team Membership」），外加 `view.*` 与 `nav.*` 同路由双条目——纯数据修改。
3. **「X 始终属于明确的 Organization 与 Team」样板句 ×10 页**（W5）：空态标题「请选择 Team」已表达全部信息，十个页面的 scope 空态各自再写一遍变体。

一句话总评：**文案密度是最后一块没有过「布局解释力」检验的产品面**——删除 108 处冗余后，页面高度普遍可再收一行到三行，而不会损失任何行为信息。

## 2. 问题总表

本轮无 P1（无可用性阻断——纯密度与打磨）。P2：系统性、影响每日常用面或需要架构/数据级修改的成批问题。P3：单点冗余与措辞打磨。

| 编号 | 级 | 模式 | 计数 | 证据 | 层 |
| --- | --- | --- | --- | --- | --- |
| W1 | P2 | 同一信息页内 2–4 次重复（汇总条/入口卡/分组描述/字段 hint/多状态面板互相复述） | ~30 | 代码确认（§3.1） | 前端 |
| W2 | P2 | 命令面板 description 复述 title + view/nav 同路由双条目 | 14+4 | 代码确认（§3.2） | 前端 |
| W3 | P2 | loading 态描述复述显而易见（auth 四层复述 + 全站「正在读取…」句式） | ~20 | 代码确认（§3.3） | 前端 |
| W4 | P3 | 副标题/subtitle 复述标题、表单字段或列头 | ~15 | 代码确认（§3.4） | 前端 |
| W5 | P3 | 空态描述复述可见按钮/入口；「X 始终属于…」样板句 ×10 页 | ~13 | 代码确认（§3.5） | 前端 |
| W6 | P3 | 布局自指（「在这里/下方/这里展示」，布局位置本身即信息） | 12+ | 代码确认（§3.6） | 前端 |
| W7 | P3 | 开发者实现细节/路线图语进入用户文案 | ~10 | 代码确认（§3.7） | 前端 |
| W8 | P2 | StatePanel 默认 description 填充句 + 调用点渗出 | 4+渗出面 | 代码确认（§3.8） | 前端 |
| S1 | P2 | StatePanel 架构：默认值应改「无传入不渲染」（W8 的根治） | 1 处组件 | 代码确认（§4.1） | 前端 |
| S2 | P2 | 命令面板 `view.*`/`nav.*` 同路由双条目（W2 的结构半） | 4 条目 | 代码确认（§4.2） | 前端 |
| S3 | P3 | 重复文案族未单源化（8 个族逐字重复 2–11 处） | 8 族 | 代码确认（§4.3） | 前端 |
| S4 | P3 | 英文装饰 eyebrow 44 处，其中与中文标题逐字同义/循环内/详情面板内 ~25 处超出了术语表裁定的「页头品牌位」范围 | 44（25 存疑收敛） | 代码确认（§4.4） | 前端 |

## 3. 模式详述

### 3.1 W1：同一信息页内 2–4 次重复（最大类，~30 处）

机制说明只讲一次就够；状态面板只报状态本身。以下是全部实例（按重复组归并）：

- **SearchPage.vue:167/172/173/179**——「当前 Team 与权限范围内」句在范围注记、idle 空态、loading 态、无结果空态出现 **4 次**。改法：只留 :167 一处。
- **AgentSettingsPage.vue:352/359/365 + :49/55/61 + :57**——「Personal Agent 承接对话 / Specialist 执行专业任务 / TEAM 连接经责任链承接」的分类学在汇总条 note、两张入口卡副标题、三个分组副标题、空态描述出现 **3 组互述**。改法：分类学讲一次（保留 :49 一处即可），入口卡/分组/空态不再复述。
- **QuotaSettingsPage.vue:207/223 ↔ :191**——「留空=不限制」在编辑态总说明与两个字段 reason 三重重复。
- **TeamObserver 只读承诺 ×4**——TeamObserverPage.vue:35 横幅 + TeamObserverWorkspace.vue:106 汇总条 + :178 空态 + :181 取消空态，「只读/不会修改团队事实」出现 4 次。改法：保留 guardrail small（:109「不能修改任务、Review、配置或外部系统」）一处，其余删除。
- **GitHubSettingsPage.vue:591/600/607/609/612/615**——「Token 单向提交/不回显」在表单头副标题、placeholder、details 高级项说明、两个字段 placeholder、表单尾 hint 出现 4–6 次；:622 与 :638 的「Catalog 用于 Push / Draft PR」页内两遍。改法：单向提交讲一次（:591），其余删；:638 小节副标题删。
- **SetupPage.vue:287 ↔ :296**——「不做双向同步」迁移面板导语与边界条两遍（后者还带仓库 docs 路径，见 W7）。
- **OnboardingWorkspace.vue:73/85/146–148**——完成卡 description「Team、Owner 权限…已由服务端逐一确认」与步骤清单、就绪区、预告项副标四说一事。
- **两个蒸馏对话框**——KnowledgeDistillationDialog.vue:144 ↔ :184、SkillDistillationDialog.vue:128 ↔ :168，subtitle 与字段 hint 重复；DelegateToAgentDialog.vue:402 ↔ :410 同型。
- **RegisterPage.vue:318**「创建账号后直接加入邀请你的团队」复述表单卡 kicker「加入团队」。
- **Login/Register loading 卡**（与 W3 复合）：kicker+title+desc+status 四层，见 §3.3。
- 其余单处重复：ModelSettingsPage.vue:405 三卡副标题+「服务端生效」badge ×3 重复 :404；OperationsWorkspace.vue:215–219 五个证据链接 small 复述目标页标题；ConversationPage.vue:1275–1278 欢迎空态描述复述在场按钮。

### 3.2 W2：命令面板 description 复述 title（14 条）+ 双条目（S2）

`src/app/defaultActions.ts` 24 条 action 全量过检：14 条 description 是 title 的同义改写（「打开团队成员 / 查看 Team Membership」「进入对话工作区 / 打开对话」「切换到今日视图 / 查看个人工作摘要」…），`view.today:30` 等 4 条 `view.*` 与对应 `nav.*` 同路由同语义——面板内出现两组同类条目，问题不止在 description 层。`:35`（执行控制）与 `:26`（运行与发布）description 逐字相同且同路由。

改法：description 字段改为可选、仅在与 title 增加信息时填（如「搜索工作、成员或 Agent」这种补范围的保留）；`view.*` 四条并入对应 `nav.*`（快捷键语义保留在 keybinding 层）。注意 e2e 锚定：命令面板 option 定位与 recent 断言在 m9b-f04/local-trust/palette spec（M13b-F12 教训：改面板结构先全仓 `rg 'li\[role=option\]' e2e/`）。

### 3.3 W3：loading 态描述复述显而易见（~20 处）

两类形态：

- **auth 卡四层复述**（6 处）：LoginPage.vue:230/234、RegisterPage.vue:272/276、OnboardingWorkspace.vue:62/66——loading 卡已有 kicker+title，「检查服务端登录配置后继续。」「正在准备安全登录…」两层描述说的是同一件事。改法：loading 卡只留 kicker+title，desc/status 两层删除。
- **「正在读取当前 Team 的 X」句式**（~14 处）：KnowledgeEntryList.vue:87、SkillList.vue:58、KnowledgeIndexJobList.vue:85、InjectionReferencesPanel.vue:67、TodayPage.vue:533、ConversationPage.vue:918/1018/1058/1085、InboxWorkspace.vue:344、ActivityStream.vue:112、ConversationTaskCards.vue:99、DelegateToAgentDialog.vue:445、OperationsWorkspace.vue:179。列表区标题/骨架屏已表达「正在读」，描述句几乎无增量。改法：StatePanel 只传 title（配合 S1 的「无传入不渲染」），骨架屏场景直接删 description。

### 3.4 W4：副标题复述标题/表单/列头（~15 处）

「把下方布局念一遍」型：

- 对话框 subtitle：KnowledgeDistillationDialog.vue:144（复述「从执行蒸馏知识」）、SkillDistillationDialog.vue:128、RepositoryBuildDialog.vue:100（复述三个字段分组）。
- 面板/分区副标题：TaskListPanel.vue:70（复述表格可见列）、ConversationPage.vue:906（「选择一个对话继续协作」复述列表语义）、TeamObserverWorkspace.vue:156 五分区 description 中 3 条纯复述分区名（「阻塞/正在妨碍交付的事实」）、ModelSettingsPage.vue:341（逐个列出目录卡片已展示的字段名）、TeamMembersPage.vue:316（复述成员表列头）、AccountWorkspace.vue:216（复述下方三个分区标题）、AuditExplorer.vue:196（复述表格列头）、AgentSettingsPage.vue:55/61。
- 徽标复述徽标：ModelSettingsPage.vue:405 治理三卡「服务端生效」badge ×3 重复 :404 副标题。
- 修饰性权威声明：ConversationTaskCards.vue:92 span「服务端事实」（标题旁装饰性背书，无数据）。

改法：删除副标题；分区 description 仅在增信息时保留（对照：TeamObserver「待确认 → 等待成员确认的受控动作」这类有增量，留）。

### 3.5 W5：空态复述可见按钮 + 样板句（~13 处）

- **样板句族「X 始终/属于明确的 Organization 与 Team」×10 页**：AgentSettingsPage:331、AuditPage:203、GitHubSettingsPage:583、LarkSettingsPage:259、ModelSettingsPage:331、OperationsPage:93、TeamObserverPage:31（变体）、KnowledgeBasePage:263、KnowledgeIndexPage:251、SkillsPage:287、ObservabilityPage:177（变体「用量与成本始终属于…」）。空态标题「请选择 Team」+ScopeSwitcher 已表达全部语义。改法：统一删描述、只留标题（InboxPage:274 的同句含禁用刷新的 aria-describedby 原因，需保留语义挂到别处）。
- **空态第二句复述在场按钮**：WorkPage.vue:1123（「创建 WorkProject 后即可管理…」创建按钮就在空态里）、TodayPage.vue:548/579/604、WorkItemsWorkspace.vue:75（「让团队目标进入可追踪的执行流」口号式复述）、LarkNotificationAdmin.vue:349、AgentSettingsPage.vue:57、AuditExplorer.vue:249（「调整时间或组合筛选后重新查询」筛选表单就在上方）、RepositorySettingsPage.vue:356。

### 3.6 W6：布局自指（12+ 处）

「会出现在这里」「在下方/上面的看板上」「这里展示」——布局位置本身就是信息：

AgentSettingsPage:51、AgentMemorySection.vue:71/105、ObservabilityPage:218/318、WorkGraphWorkspace.vue:156、ConversationParticipantsPanel.vue:51（placeholder「…将在这里展示」——placeholder 应提示输入而非描述布局）、KnowledgeIndexJobList.vue:90、TeamInvitationManager.vue:207、ConversationPage.vue:1097（summary 后缀「可折叠」复述 details 三角标记）、TodayPage.vue:604（「可以直接在下面的看板上推进」）、TeamObserverPage.vue:35 前半、ObservabilityPage:318。改法：一律删除；空态留一句话标题+（若有）按钮。主会话横切 grep：可见文案「这里」12 处，其中 8 处为空态「会出现在这里」、4 处为页头「这里只/展示」。

### 3.7 W7：开发者实现细节/路线图语（~10 处）

- 实现枚举：TeamMembersPage.vue:366（「ACTIVE Membership、Team Scope Role、If-Match 版本与目标 Principal 校验」）、OperationsWorkspace.vue:179（「低基数摘要」）/187（「不暴露事件载荷、成员身份或错误原文」的内部措辞）、ModelConnectionDetail.vue:150（「服务端仍会在每次命令中重新校验权限」）。
- 路线图语：ModelConnectionDetail.vue:156（「统一 Audit 查询 API 交付后接入」）、KnowledgeCreateDialog.vue:131 与 KnowledgeDraftEditor.vue:107（「渲染增强在后续切片提供」placeholder ×2）。
- 品牌区技术栈：AuthBrandPanel.vue:38「基于 AgentScope Java 2.0」。
- 面向开发的路径：SetupPage.vue:296（「完整说明见仓库 docs/guides/M9b-…」）。
- 混合句拆分候选（前半删后半留）：RepositorySettingsPage.vue:276（「页面只持有稳定 Repository Key」是实现视角，「仓库由受信 Worker 管理」是用户事实）、:286/287（概念词典段+「Worker 负责 Remote URL、凭证和本地仓库路径」重复）、GitHubSettingsPage.vue:644 后半、TeamMembersPage.vue:303 后半、QuotaSettingsPage.vue:315（「后端无多月端点」）。

另：子代理报告一处 153 字演示长文「领域组件必须覆盖长内容…」（疑似 Histoire 演示文案），定性核实为 histoire story demo 文案、不在产品路由渲染路径，**不计问题**，记录在案防误报。

### 3.8 W8：StatePanel 默认 description 填充句（4 条）+ 渗出

StatePanel.vue:34–41 九态默认对象中四条是纯填充句：loading「正在获取最新团队事实。」、empty「这里还没有可显示的工作。」、forbidden「当前身份没有访问此范围的权限。」、recovering「Runtime 正在从最近的耐久检查点恢复执行。」。调用点普遍只覆写 title（如「正在加载本月预算」），此时默认 description 兜底渲染——通用句出现在具体语境，既不匹配又多一行。**注意红线**：:39 offline 默认 description 是 C5/stale-while-refresh 契约的一部分（组件注释 :16 自证 + e2e 锚定），conflict/cancelled 的默认句也是行为语义——这三态不动。改法见 S1。

## 4. 结构性发现

### 4.1 S1：StatePanel 默认值改「无传入不渲染」

`title?: string` / `description?: string`（:8-9）当前默认空串+per-state 默认对象（:34-42）。改法：per-state 默认对象去掉 loading/empty/forbidden/recovering 四态的 description（或默认对象只留 title），模板对空 description 不渲染节点；offline/conflict/cancelled/error 四态的默认句保留（契约/行为语义）。影响面：全部 ~40 个 StatePanel 调用点自动减行；需要 e2e 复跑锚定 StatePanel 断言的用例（m6/m9b 系列 grep `description=` 先列清单）。

### 4.2 S2：命令面板双条目

`view.today/view.conversation/view.work/view.work-graph`（defaultActions.ts:30-34）与 `nav.*` 同路由。工作台内已有视图切换（F09 后 toolbar 视图切换组），面板双条目是历史层积。改法二选一：并入 nav.*（快捷键 `v t` 等挂在 nav 条目上）；或 view.* 保留但仅在对应页面上下文出现。倾向前者（面板分组「视图」可整体删除）。

### 4.3 S3：重复文案族未单源化（8 族）

跨组件逐字重复、应收敛为 labels.ts 常量或组件 props 的族（主会话全库 grep 计数为权威）：

| 族 | 处数 | 位置 |
| --- | --- | --- |
| 403「服务端没有为当前身份解析出这个 Team 的活动成员。」 | 6 | KnowledgeEntryList/SkillList/KnowledgeIndexJobList/InjectionReferencesPanel/InboxWorkspace/AgentMemorySection |
| 「离线期间（管理/控制/续页读取）保持关闭」 | 11 | 知识族 5 + BaseDetailPage/BaseListShell + AuditExplorer 等 |
| knowledgeIndexStatusHint（「待索引≠未保存」族）渲染点 | 10 | 详情头/版本行/回执/列表（单源常量已存在，渲染点收敛是另一回事——同屏最多 3 处） |
| 「回滚不是删除…」impact | 2 | KnowledgeBasePage:327 ↔ SkillsPage:223（跨页近似） |
| 幂等重放回执句 | 2 | 两蒸馏对话框逐字同 |
| frontmatter 契约 hint | 2 | SkillCreateDialog:109 ↔ SkillDraftEditor:89 逐字同 |
| 「修改草稿并再次发布即可恢复生效」 | 2 | SkillsPage:222 ↔ SkillDraftEditor:36 逐字同 |
| 「Task 详情（的执行区）可一键复制/带入」 | 3 | 两蒸馏 hint + ExecutionSourcePicker:95 |

收敛原则：同屏只渲染一次（hint 渲染点做 v-if 首现）；跨屏逐字同句提升为共享常量（团队域 labels.ts 已是词表入口）。

### 4.4 S4：英文装饰 eyebrow 的边界重裁

全库 44 处 `class="eyebrow"`。术语表（docs/product/术语表.md）已裁定「eyebrow 装饰性英文眉题是设计规范允许的风格元素」——但本轮证据显示实际用法超出了「页头品牌位」的合理范围：

- **合理保留**（页头/品牌位，首次出现的中文语境）：SettingsShell「Settings · 配置中心」、AppShell eyebrow、AuthBrandPanel kicker、NotFound/AccessDenied 页眉。
- **超出范围**（~25 处，建议收敛）：详情面板/抽屉内的小节眉题（WorkItemDetailDrawer ×6「Facts/Accountability/Discussion/WorkGraph nodes/Activity」、TaskDetailDrawer ×4「Unified workspace/Accountability snapshot/Member-safe fleet/Linked context」）、对话框眉题（DelegateToAgentDialog ×2、KnowledgeDistillationDialog、WorkItemCreateDialog）、循环内固定眉题（SearchPage:185「匹配对象」每个 v-for 结果组头重复——这条同时违反 W1）、与中文标题逐字同义的（AccountPage:158「账号 · 身份安全」vs「账号设置」）。

裁决建议：eyebrow 限「页面级页头 + 品牌位」；面板/抽屉/对话框/循环内一律删（中文 h2/h3 已经是标题，英文小字悬在上面是双重标题）；`Task intent · Revision N`、`{projectKey} · 工作项`、`Durable Task · {key}` 三处含数据的保留数据部分、去英文标签部分。

## 5. 做得好应保留（本轮正面基线）

1. **不可见行为文案是资产**：331 条保留判定的主体——幂等回执（「该命令此前已受理，幂等重放」×多域）、撤销窗口（「还在撤销窗口内（N 秒）」）、跨页保留（「已选 N 项（跨页保留）」）、离线缓存（「已加载事实和本地草稿会保留」）、成本后果（「重新生成会消耗新的模型时间与用量」）、不可逆范围（「退出全部设备…包括当前设备」）。这些一字不动。
2. **sr-only 与禁用原因纪律**：a11y 附加文案几乎全部走 sr-only/aria-describedby（WorkItemsToolbar 作用域说明、全局禁用成因），未混入可见文案。
3. **输入契约句式统一**：「N–M 个字符，例如 xxx」格式 hint 全站一致（Key/skillKey/密码/失败码）。
4. **单源常量意识已在**：knowledgeIndexStatusHint、registrationModeLabel、activity 词表等证明机制存在——S3 只是把机制补到 8 个漏网族。
5. **布局解释力的库内样板**：GitHub 连接卡（icon+login+身份小字+状态徽标一行）、fact-grid 四格、WorkDeskBoardCard 纯数据卡面、批量条资格比例「(9/12)」、Composer「Enter 发送 · Shift+Enter 换行」——这些地方布局在说话、文字零废话，是本轮整改的目标形态。
6. **StatePanel 调用点覆写率高**：绝大多数调用点传了具体 title——问题只在默认值兜底机制（S1），不在调用习惯。

## 6. 计划承接建议（工作包草案，供 M13c 计划冻结时裁定）

| 包 | 内容 | 预估 |
| --- | --- | --- |
| C01 单点架构 | S1 StatePanel 默认改不渲染 + S2 命令面板双条目并入 + W2 description 清理（含 e2e 锚定清单） | 小 |
| C02 重复收敛批 | W1 全部（Search/AgentSettings/Quota/TeamObserver/GitHub/Setup/Onboarding/蒸馏×2/Delegate）+ S3 八族单源化 | 中 |
| C03 删除批 | W3 loading 层 + W4 副标题 + W5 样板句/空态二句 + W6 布局自指（纯删，零行为变化） | 中 |
| C04 措辞批 | W7 拆分句（前半删后半留）+ S4 eyebrow 边界收敛 | 小 |
| C05 验证 | vitest/e2e 全量 + 快照基线受影响清单（删行会改页面高度——F12 快照连锁教训：先 rg 断言再批量重写） | — |

整改红线：§5 正面基线 1–3 的行为语义文案一字不动；offline/conflict/cancelled/error 四态默认句不动；术语表契约的技术详情折叠区不动。

## 7. 走查覆盖与边界

- 覆盖：27 路由页面全量（含薄壳页的一级子组件）；domain 组件按页面挂载关系覆盖 ~50 个（174 个中的高频面，未覆盖的为对话框/小部件类，冗余模式与已覆盖面同构）；横切基础件 7 个 + 命令面板数据源逐条。四路扫描合计 587 条判定（冗余 108/保留 331/存疑 138），主会话横切 grep 复核了全部跨文件族的权威计数。
- 证据性质：全部代码确认（静态文案走查）；未跑实机（纯文案密度问题，代码即完整证据；删行后的布局效果需在 C05 用四视口量测复核）。
- 未覆盖：后端文案（API 错误 message/通知正文）；邮件/飞书模板；e2e 断言文案的连锁面（C01–C04 实施时按 F12 教训逐批 rg）；竞品文案密度对照。
- 与前两轮的分工：M13 后 Review 看旅程断点、M13b 前 Review 看布局与语义契约，本轮只看文字量与布局解释力的重叠——三论正交，本文编号 W1–W8/S1–S4 独立。

## 8. M13c 整改回填（2026-10-24，C01–C05 完成）

整改基线 `8db4ca74`（M13c 计划冻结）之后五包全量交付，逐项关闭标注：

| 编号 | 处置 | 提交 |
| --- | --- | --- |
| S1 | ✅ StatePanel 改「无传入不渲染」：九态默认对象去 loading/empty/forbidden/recovering 四态 description；BaseListShell loadingDescription/emptyDescription 同契约改可选（C03 连带） | C01 `8fde9b18` |
| S2 | ✅ `view.*` 四条并入 `nav.*`，快捷键语义挂 nav 条目 | C01 |
| W2 | ✅ 24 条 action description 逐条清理：纯复述 title 删、补范围的留 | C01 |
| W8 | ✅ 随 S1 根治，~40 调用点零改动自动减行 | C01 |
| W1 | ✅ 13 组同页重复全部收敛（Search 范围句/AgentSettings 分类学/Quota 留空/TeamObserver 只读/GitHub Token 单向/Catalog/Setup 边界/Onboarding 完成态/两蒸馏/Delegate/Model 治理卡/Operations 证据链接/Conversation 欢迎态/Register） | C02 `8c48519e` |
| S3 | ✅ 3 族单源化（403 句与幂等回执 → `domains/shared/stateCopy.ts`；frontmatter hint 与恢复生效 → `skill/labels.ts`，文案一字未改）；5 族登记不合并（裁定 3/4） | C02 |
| W3 | ✅ auth 卡 desc/status 两层 ×3 页 + 「正在读取」描述 ×14 删；孤儿 CSS 同步清 | C03 `3326e7a0` |
| W4 | ✅ 9 组副标题/分区描述删（有增量的待确认/异常两条保留）；两蒸馏 subtitle 翻案（裁定 1） | C03 |
| W5 | ✅ 样板句 13 处 + 空态二句 8 处删；InboxPage:274 按红线 7 处理（裁定 5） | C03 |
| W6 | ✅ 10 处布局自指删（ConversationParticipantsPanel 预告 div + 孤儿 import/CSS 连带清） | C03 |
| W7 | ✅ 9 组拆句/删除（TeamMembers/Operations/ModelConnectionDetail/两蒸馏 placeholder/AuthBrandPanel/Setup/Quota/Repository/GitHub） | C04 `6bb47b8c` |
| S4 | ✅ 裁决「限页面级页头 + 品牌位」：面板级 eyebrow ×24、section-heading/抽屉 ×16、对话框属性 ×10 删；数据三处保数据去英文标签；保留清单见裁定 6 | C04 |

裁定登记（存疑项最终裁决，均经实测复核）：

1. **两蒸馏 subtitle 翻案保留**（W4）：C02 已删同信息 hint，subtitle 成为单源——「删其一」意图已满足，不删第二处。
2. **TeamObserverWorkspace:178 翻案保留**：C02 删 :106 汇总条后，:178 成为唯一「重新授权」路径说明。
3. **待索引 hint 族不收敛**（S3）：实测 BaseVersionHistory hint 槽只对 selected 渲染一次，每屏 ≤2 处在容忍线内，不引入 v-if 首现复杂度。
4. **近似句族不合并**（S3）：回滚 impact（跨页近似非逐字）、Task 详情复制 ×3（变体）、离线关闭句 ×11（变体族）——逐字同句才单源化，变体承载语义差异。
5. **InboxPage:274 红线 7**：`id="inbox-scope-reason"` 与刷新按钮 aria-describedby 接线保留，scope 语义由面板 title「请选择 Team」承担；check-disabled-reasons 对 aria-describedby 上下文豁免。
6. **中文/数据眉题保留清单**（S4）：AgentConfigurationPanel:454、WorkGraphWorkspace:141（纯数据）、TodayPage:492、AccessDeniedPage:45、SetupPage:262「能力清单」——均非英文装饰，不属收敛面。

红线核对（计划 §3 七条）：正面基线行为语义文案、StatePanel offline/conflict/cancelled/error 四态默认句、术语表技术详情折叠区、sr-only/aria-describedby/禁用原因、输入格式契约句式、空态一句标题+按钮、InboxPage aria 语义——全部未动，删除的均为冗余层。

C05 验证记录：vitest 全量 1992/1992 绿（275 文件）；e2e 全量 704 passed/13 skipped，唯一失败 e2e/m9b-f03/reading-anchors.spec.ts:250 判定**负载时序 flake**（全量 run1 绿 / run2 偏差 881px / run3 偏差 793px 值非确定；单独复跑 ×3 恒绿；M13c 纯文案删行不触碰虚拟列表机制，无因果通路——400ms resize 收敛窗在 4-worker 满载下击穿）；快照基线 38 张重写，全部为纯高度收缩（ratio 0.03–0.05，零增行、零断言失败），逐一归因到删行面；七门禁（check:quality / enum-labels / q01 / sensitive / design-tokens / bundle / doc-links）全绿（quality 需先清 8 处 patch 残留行尾空格）。净改动：C01–C04 合计 79 文件 +218/−229（src 77 文件 +216/−227）+ C05 快照 38 张与行尾清理 7 文件。
