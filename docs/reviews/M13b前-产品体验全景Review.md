# M13b 前：产品体验全景 Review（第二轮）

日期：2026-10-20。对象：当前 CrewScope 实现（基线 `ed4f3db`，与第一轮同一代码面）。第一轮见 [M13 后产品整体使用体验 Review](M13后-产品整体使用体验Review.md)。

触发：第一轮入档后用户追加两点——「现在还有个比较重要的地方，就是对话区占比有点少，感觉拥挤」（登记为 **R51**，沿用第一轮 R 编号序列）；「最后再整体全面review一下产品体验，我要最好的产品体验」。本轮即后者：以业界最好产品体验为标尺，对产品做第二轮全景走查。

方法：三轴并行只读走查——**布局与信息架构**（L 轴）、**交互反馈与状态**（I 轴 + C 跨页模式）、**可访问性·移动端·文案**（A 轴）——覆盖 `src/app/router.ts` 全部 29 条页面路由 + 2 条 redirect 及横切机制（路由滚动行为、toast、剪贴板、焦点生命周期、触控目标、术语与枚举呈现）。取证分级沿用第一轮：**代码确认 / 需实机（需量测）**；P1 三条均经主会话二次复核源码（§7）。本轮零代码修改、零实机复现；标注「需量测」的尺寸结论进入 M13b 布局验收量测清单。计划承接见 §9——全部发现并入 [M13b-产品体验收口](../plans/M13b-产品体验收口.md)。

## 0. 编号约定

- 第一轮编号 R01–R50 不变；用户补充 **R51**（对话区空间配比）为第一轮序列的追加项，叙述归入本文布局轴。
- 第二轮轴内编号：**L**（布局/IA）、**I**（交互反馈/状态）、**C**（跨页交互模式）、**A**（可访问性/移动端/文案）、**S**（系统级横切问题）。S1–S5 来自布局轴；S6–S14 来自可访问性轴（走查报告原编号 R01–R09，本文统一改为 S 编号，避免与第一轮 R01–R50 冲突）。
- 里程碑工作包编号带 `M13b-` 前缀；本文引用时省略前缀。第二轮 A01/A02 与工作包 A01/A02 含义不同，易混处写「二轮 A01」。

## 1. 结论

单页质量普遍 A/B 档（29 路由 6A/17B/6C，§3），底座——AppShell fill 布局、状态阶梯、焦点与动效基线、键盘基础件——扎实，与第一轮结论一致。第二轮暴露的是三层系统性欠账：

1. **空间配比**：桌面 1440×900 的真实内容区只有 1156×720，但 9 个页面在 context header 下再叠 hero，首条数据从 178–206px 才开始；扫描型页面的「过滤三明治」把首屏压到 3–6 行（Inbox 列表前 276px chrome、Work 约 330px、Activity 约 250px）；设置区双导航在 900 宽合计吃掉 30% 视口。用户感到的「拥挤」（R51）是对话页的局部表现——默认左 24%/右 22% 侧栏 + 740px 消息列上限 + 默认展开的结构块——其背后是全站没有内容宽度与扫描工具栏的统一策略（S1/S2/S5）。
2. **状态契约**：批量操作是唯一绕过单条安全规则（不可逆二次确认 / 可逆 10 秒撤销）的执行面（I01，P1）；错误面板「有没有下一步」没有契约（C2）、计数用已加载数冒充总数（I02/I12）、撤销只活在自动消失的 toast 里且键盘不可达（I09）、刷新策略保留旧内容与整块替换两套并存（C5）。
3. **语义与触控**：命令面板 listbox 语义破损，键盘/读屏用户永远拿不到「最近访问」（A01，P1）；「添加已有用户」名不副实（A02，P1）；选中态大量只靠 class、44px 触控系统性缺口、表单错误不关联、原始枚举与中英混用跨页存在（S3、S6–S14）。

第一轮解决「用户能不能走完全程」，第二轮解决「走得舒不舒服、稳不稳」；两轮共同构成 M13b 的全部输入。

## 2. 总量与分布

| 轴 | P1 | P2 | P3 | 系统级 |
| --- | --- | --- | --- | --- |
| L 布局/IA（L01–L16，另 R51 记 P2） | 0 | 10 | 6 | S1–S5 |
| I 交互反馈/状态（I01–I13） | 1 | 7 | 5 | —（并入 C） |
| C 跨页交互模式（C1–C5） | 0 | 4 | 1 | — |
| A 可访问性/移动端/文案（A01–A27） | 2 | 16 | 9 | S6–S14 |
| **合计** | **3** | **37 + R51** | **21** | **14** |

第二轮 P1×3：I01（批量绕过安全规则）、A01（命令面板语义）、A02（添加已有用户）。第一轮 P1×4（R01–R04）不变。

## 3. 覆盖矩阵（29 路由 + 2 redirect）

尺寸基准 1440×900：侧栏 244 + workspace 左右各 20，内容区 **1156×720**（900 − 58 topbar − 82 context header − 40 padding）；900 宽内容区 784；390 宽 358。

| 路由 | 页面 | 档 | 一句话布局评价 |
| --- | --- | --- | --- |
| `/login` | `crewscope-web/src/pages/LoginPage.vue` | A | AuthLayout 46vw 分栏 + 单卡表单，680 以下单列，首屏焦点明确。 |
| `/register` | `.../RegisterPage.vue` | A | wide AuthCard 双列表单，680 以下折单列；状态分支独立不叠层。 |
| `/invite` | `.../InvitePage.vue` | A | 邀请工作区独占整页，不套应用 chrome，任务单一。 |
| `/onboarding` | `.../OnboardingPage.vue` | A | 单列 stepper，账号/团队/入口三步线性推进。 |
| `/account` | `.../AccountPage.vue` | B | SettingsShell 的 190px 列只承载 4 个页内锚点，主体仍是长滚动，左侧偏重。 |
| `/conversation` | `.../ConversationPage.vue` | C | 左 24% + 消息列 740px 上限 + 右 22%，三栏把消息压成窄带（R51，§4.17）。 |
| `/search` | `.../SearchPage.vue` | B | 980px 居中阅读宽度合理，但 intro + 筛选卡把结果推到约 270px 之下（L12）。 |
| `/today` | `.../TodayPage.vue` | B | 分区清晰、全宽承载看板；但四块同权，首屏信息量大（I02/I03）。 |
| `/setup` | `.../SetupPage.vue` | B | hero、next-step、goal、health 四块等权堆叠，像清单而非引导（I07）。 |
| `/work` | `.../WorkPage.vue` | B | 列表行密度与对齐好；工具栏两行 + 置顶项目条把首行推到约 330px（L08）。 |
| `/activity` | `.../ActivityPage.vue` | C | 页头、hero、流头部三次重复「团队活动」，实时徽标出现两次（L05）。 |
| `/inbox` | `.../InboxPage.vue` | C | 列表前 276px chrome，1440×900 仅约 3 行可见；行内没有可读对象标题（L01/L02）。 |
| `/team/observer` | `.../TeamObserverPage.vue` | B | hero + note + 状态 + composer + summary 多段，但每段职责明确。 |
| `/knowledge` | `.../KnowledgeBasePage.vue` | C | 5fr/4fr 固定分栏，未选中时右侧 44% 永久空白（L03）。 |
| `/knowledge/index` | `.../KnowledgeIndexPage.vue` | A | 单列全宽作业列表，筛选与作业一一对应，没有多余 hero。 |
| `/skills` | `.../SkillsPage.vue` | C | 与 Knowledge 相同的 5fr/4fr 空列问题（L03）。 |
| `/observability` | `.../ObservabilityPage.vue` | C | 10 列明细表，费用在第 8 列；1280 下需横向滚动，390 下只能在表内平移（L10）。 |
| `/operations` | `.../OperationsPage.vue` | C | 工作区页面却套 SettingsShell，出现 190px 设置目录并丢掉 210px 内容宽度（L06）。 |
| `/audit` | `.../AuditPage.vue` | B | 表格本身规整；hero + 4+4 筛选 + footer 在表格前占约 200px。 |
| `/team/members` | `.../TeamMembersPage.vue` | B | 成员表列宽合理，850 以下隐藏低价值列；hero + 目录头仍偏高。 |
| `/settings/organization` | `.../OrganizationSettingsPage.vue` | B | 三 tab + 卡片结构稳定；页内 tab 与 SettingsShell 目录并存。 |
| `/settings/quota` | `.../QuotaSettingsPage.vue` | B | 单卡配置 + 指标网格层级正确，缺「当前用量/上限」汇总；单月失败死路（I05）。 |
| `/settings/repositories` | `.../RepositorySettingsPage.vue` | B | 执行默认配置面板排在仓库概览之前，页面主题被前置任务打断（L09）。 |
| `/settings/agents` | `.../AgentSettingsPage.vue` | B | hero + 类型入口 + 分组清楚；4 个汇总数字放进 3 列网格（L11）。 |
| `/settings/models` | `.../ModelSettingsPage.vue` | B | 220px 主从目录 + 模型卡合理；hero 与 context header 重复主题。 |
| `/settings/integrations/lark` | `.../LarkSettingsPage.vue` | B | 三 tab 管理面完整；SettingsShell 目录与主 rail 重复（S3）。 |
| `/settings/integrations/github` | `.../GitHubSettingsPage.vue` | B | 表单/连接目录/详情纵向三段顺序合理；创建表单展开时把目录推低。 |
| `/access-denied` | `.../AccessDeniedPage.vue` | B | 页头、权限卡、StatePanel 三块说同一件事，宽度还不一致（L14）。 |
| `/not-found` | `.../NotFoundPage.vue` | C | `min-height:100vh` 放在已扣掉 140px chrome 的 workspace 内，必然多出一屏滚动（L13）。 |

两条 redirect（`/` 与 `/control` → today）不计入矩阵。

## 4. 布局与信息架构轴（L01–L16、R51、S1–S5）

### L01（P2）Inbox：列表前 276px 全是 chrome，1440×900 只看得到约 3 行
- 位置：`crewscope-web/src/components/domain/InboxWorkspace.vue:250-286`、`:401-408`
- 现象：overview 面板 76px 只重复「总数/未读数」（这两个数字在 ALL 视图卡上已完整出现）；5 张视图卡 82px，描述被 `nowrap + ellipsis` 截成一行；toolbar 约 82px；合计 276px，720px workspace 只剩 444px，按每行 140–150px 估算首屏约 3 行（需量测）；390×844 下约 2 行。
- 改进：删除 overview；五张视图卡压成 44–48px segmented tabs（标签 + 计数）；toolbar 压成单行；列表区 ≥ workspace 60%；≤980 不用 3 列网格（5 卡会排成 3+2 留空格）。

### L02（P2）Inbox 行首是类型和 revision，「关于哪条工作」完全没渲染
- 位置：`InboxWorkspace.vue:331-341`；DTO `crewscope-web/src/domains/teamops/types.ts:133-143`
- 现象：行首 strong 是 itemType 标签，第二行是 `source.type · revision N`，footer 是 `source.id.slice(0, 8)`。DTO 已携带 `sourceContext.workItemTitle`、`taskObjective`、`waitingOnDisplayName`、`targetActionKind`，但全库零消费。与第一轮 R26 的「UUID 前 8 位」根因不同：可读上下文根本没有进入视图。
- 代价：同一类型的 5 条 Review 请求在列表里长得完全一样，必须逐条打开详情才知道是哪项工作、在等谁。
- 改进：主文本改 `workItemTitle`（缺失回退类型标签），第二行 `taskObjective` 或 `waitingOnDisplayName`，类型降为 badge，footer 换截止时间/来源状态。

### L03（P2）Knowledge / Skills 的 5fr/4fr 在未选中时把 44% 宽度留空
- 位置：`crewscope-web/src/pages/KnowledgeBasePage.vue:206`、`:229`（`aside v-if="selectedEntryId"`）、`:292`；`SkillsPage.vue:378`
- 现象：网格轨道不随 `v-if` 回收。1440 下列表约 642px、右侧约 498px 纯空白且无占位说明；900 下列表 426px、空白 342px。
- 改进：未选中单列（`minmax(0,1fr)`），选中后切 5fr/4fr；若保留双栏，右栏放稳定占位说明，不要 500px 空白。

### L04（P2）知识/Skill 列表把不透明 key 当作唯一主文本
- 位置：`crewscope-web/src/components/domain/KnowledgeEntryList.vue:126`、`:147-151`；`SkillList.vue:122-128`；`domains/knowledge/types.ts:21-42`、`domains/skill/types.ts:16-20,32-45`
- 现象：行主文本是 `entryKey`/`skillKey`（mono 字体）；`draft.title`/`draft.name` 已在 DTO 里但未使用；已发布条目 Summary 无 effective title，属服务端摘要缺字段。
- 改进：主行用标题/名称（发布态需 Summary 补 `effectiveTitle`/`effectiveName`，接口配合）；副行 `key · 类别 · revision`，key 降为 muted 小字。

### L05（P2）Activity 页有三层页头，实时状态出现两次
- 位置：`crewscope-web/src/pages/ActivityPage.vue:141-159`；`components/domain/ActivityStream.vue:99-108`
- 现象：AppShell 已渲染 h1；页面再渲染 76px summary 卡（含实时 StatusBadge）；ActivityStream 内部又渲染一组标题 + 第二个实时徽标。事件列表从约 330px 才开始。
- 改进：保留 AppShell h1 与唯一实时徽标（进 context header actions）；hero 降为 toolbar 一行；删除流内部 header，标题用 `aria-label`。

### L06（P2）Operations 是工作区页面，却套了设置页外壳
- 位置：`crewscope-web/src/pages/OperationsPage.vue:10,83`；`components/settings/SettingsShell.vue:60-71`
- 现象：「运行与发布」在主导航「工作」组，但页面用 `SettingsShell`，渲染 190px 设置目录（10 项与主 rail 重复），内容宽度从 1156 降到 946。
- 改进：改用 AppShell + 页内 section tabs（健康/诊断/投影），或明确迁入「配置」组。

### L07（P2）SettingsShell 的 190px 目录与主 rail 重复，且只在 767 以下折叠
- 位置：`SettingsShell.vue:60-61`、`:71`、`:79`；`components/layout/AppShell.vue:403-411`
- 现象：1100–768 区间 rail 折叠成 76px 但设置目录仍 190px：900 宽两份导航合计 266px（30% 视口），内容只剩 574px。
- 改进：设置目录按 4 组收拢或删除与 rail 重复项；折叠断点从 767 提前到 1100。

### L08（P2）Work 工具栏两行 + 置顶条，首行工作项被推到约 330px
- 位置：`components/domain/WorkItemsToolbar.vue:76-126`、`:135`；`pages/WorkPage.vue:1017`、`:1044`
- 现象：第一行同时放 eyebrow、h2、结果数、saved views、新建、3 筛选、3 视图按钮；第二行 sort-control 独占（5 个排序按钮）；工具栏约 150px；context header 已显示项目名，toolbar 再说一次。
- 改进：压成 56px 单行；删 toolbar 内 eyebrow/h2；排序收进一个「排序：」菜单；saved views 移 context header actions；置顶项目条并进工具栏（需量测验收）。

### L09（P2）仓库设置页先放「项目默认执行配置」，再放本页概览
- 位置：`pages/RepositorySettingsPage.vue:270-274`；`components/domain/ProjectExecutionDefaultsPanel.vue:109-128`
- 现象：进入「仓库设置」第一块是完整的执行默认配置表单（含保存按钮），第二块才是仓库绑定概览。
- 改进：顺序改为 概览 → 来源说明 → 创建绑定 → 绑定目录 → 执行默认配置（或折叠为 disclosure 默认收起）。

### L10（P2）成本明细 10 列，费用在第 8 列，数字全部左对齐
- 位置：`pages/ObservabilityPage.vue:200-236`；`components/base/BaseTable.vue:19`
- 现象：列依次为 角色、Provider/模型、价格修订、Attempt、输入、输出、缓存、费用、事实、计价；`td` 统一 `text-align: left`；1280 内容区 996px 必然横向滚动，1440 临界（需量测）；390 只能在表内平移。
- 改进：压 6 列（Tokens 三行堆叠同格、费用主数字加粗）；数值列右对齐 + `tabular-nums`；首列 sticky；≤767 改卡片。

### L11（P3）Agent 概览把 4 个汇总数字放进 3 列网格
- 位置：`pages/AgentSettingsPage.vue:359-364`、`:469`
- 现象：`repeat(3, minmax(94px,1fr))` 承载 4 项，第四项孤行。
- 改进：桌面 4 列；≤1100 用 2×2；≤520 纵向列表。

### L12（P3）搜索页先讲一句口号，再给搜索框
- 位置：`pages/SearchPage.vue:153-169`、`:195`
- 现象：`search-intro`（eyebrow + h2 + 说明 + scope note）之下才是 64px 搜索表单，再下是独立筛选 panel；首条结果在视口约 410px。
- 改进：搜索框紧贴 context header（可 sticky），intro 降为辅助行或 placeholder；类型 chips 并进搜索框同一行。

### L13（P3）404 页在 workspace 内再设 100vh，必然产生第二屏滚动
- 位置：`pages/NotFoundPage.vue:11`、`:16`
- 现象：AppShell 已占 58+82+20，`.not-found` 再设 `min-height:100vh`；文档高度 ≥100vh+140px；且页头 h1、页内 h1、StatePanel 标题三处重复（与二轮 A05 同源）。
- 改进：`min-height:100%` 或 `calc(100dvh - 140px)`；只保留一个 h1；正文一段说明 + 两个按钮。

### L14（P3）Access Denied 用三块结构说同一件事，且宽度不一致
- 位置：`pages/AccessDeniedPage.vue:20-35`、`:40`
- 现象：context header h1 → 760px 权限卡（h2 + 权限名 + 服务端说明）→ 全宽 StatePanel（返回按钮）。第一轮 R05 记的是文案问题，本条是结构重复与宽度断裂，根因不同。
- 改进：合并为一个 640–760px 居中卡片：h2 + 所需权限 + 一句说明 + 并排两个按钮（返回 Today / 切换 Team）；删除额外 StatePanel。

### L15（P3）桌面端面包屑最后一项与 h1 逐字重复
- 位置：`components/layout/AppBreadcrumb.vue:15-18`、`:30-40`
- 现象：默认 items 为 `[CrewScope, route.meta.title]`，第二项与紧随其下的 h1 同名；占 24px 高 + 8px margin，把 context header 撑到 82px；仅 ≤767 隐藏。
- 改进：层级 >2 才渲染面包屑且末项用 section 名；一级页面不渲染——context header 可从 82px 收到 64px，全站首屏多出约 18–20px。

### L16（P3）索引作业在手机端 5 个 header action 排成 3 行
- 位置：`pages/KnowledgeIndexPage.vue:178-184`；`AppShell.vue:428-432`
- 现象：header actions 含自动刷新 checkbox、上次刷新时间、刷新、重建索引、构建仓库索引；≤767 每个 button `flex:1 1 calc(50% - 8px)`，header 可从 72px 涨到约 190px；两个破坏性/低频动作与「刷新」同权。
- 改进：header 只留「刷新」+「构建」；「重建」与自动刷新收进 overflow 菜单；≤767 header actions 最多两行。

### R51（P2，用户补充）对话区占比少、感觉拥挤
- 位置：`crewscope-web/src/pages/ConversationPage.vue:99-100`（`leftPane 24% / rightPane 22%` 默认）、`:1211`（grid `var(--conversation-left-pane,24%) 8px minmax(0,1fr) 8px var(--conversation-right-pane,22%)`）、`:1203`（`.message-list max-width:740px`、`.message-row max-width:min(82%,620px)`）、`:970`（TaskIntent `<details open>`）、`:991`、`:1004`（关联/结构块默认展开）
- 现象（按 1440×900 推算，需量测验收）：内容区 1156px，左右栏默认 24%/22% ≈ 277px/254px，加两道 8px 分隔后消息列仅约 609px；气泡上限 `min(82%,620px)` 实际 ≈499px；顶部还有 TaskIntent/关联 WorkItem 等默认展开的结构块占高。消息主区被三面挤压。
- 改进（M13b-F09 契约）：左右栏默认收窄（20%/18%）且偏好版本 v1→v2；≤1440 右栏计算态折叠（无内容时）；消息列上限 900px、气泡 760px；结构块折叠为单行摘要；新增专注模式（⌘⇧F 进入 / Esc 退出，`aria-pressed`）；四视口量测断言消息区高度 ≥ workspace 62%、消息列 ≥700px。

### S1（系统级）context header + 页面 hero：9 个页面在首屏重复「我是谁」
AppShell 已提供 breadcrumb + eyebrow + h1 + actions（82px）；Inbox、Activity、Audit、TeamMembers、Repository、Agent、Model、TeamObserver、Setup 又各自渲染 76–104px hero。真实内容从 **178–206px** 才开始。→ 统一为 44–56px summary strip（数字与一句状态放标题同行），列表页不再出现独立 hero（契约 §7）。

### S2（系统级）扫描型页面的「过滤三明治」
Inbox 276px、Audit 约 200px、Activity 约 250px、Work 约 200px、Observability 多层 panel——列表/表格前堆 2–4 层，1440×900 只能看到 3–6 行数据。→ 抽统一 `ScanToolbar`（≤56px：结果数 / ≤3 筛选 / 排序菜单 / 视图切换 / 主行动；高级筛选进 popover）。

### S3（系统级）设置区两套并行导航
主 rail「配置」组与 SettingsShell 11 项目录指向同一批页面；1440 下内容 946px，900 下两份导航 266px。→ 只保留一套；折叠断点 767→1100。

### S4（系统级）「列表 + 详情」有四种行为
Knowledge/Skills 永远双栏（未选保留 44% 空列）；Inbox/Activity/Audit 选中后才出现 330–370px 右栏；Conversation 三栏固定比例；手机端列表/详情互斥。→ 统一规则：默认单栏列表 → 选中后右侧 320–360px 详情 → ≤900 详情覆盖/下移；不用「永久空栏」表达「尚未选择」。

### S5（系统级）内容宽度没有统一策略
Search 980、Activity/Inbox/TeamObserver 1240、Audit 1280、其余全宽、Conversation fill——≥1600 显示器上页面在四种宽度间横向跳动。→ 定义 `--cs-content-max`（如 1360）与 `--cs-reading-max`（如 980），由 page-shell 统一应用；表格/看板页允许 full-bleed。

## 5. 交互反馈与状态轴（I01–I13、C1–C5）

### I01（P1）批量状态变更绕过单条操作的安全规则
- 位置：`crewscope-web/src/pages/WorkPage.vue:552-561`；`components/domain/WorkItemsBulkBar.vue:103-111`；`domains/workitem/list.ts:101-118`；`domains/workitem/store.ts:511-573`
- 现象：勾选多条后批量栏出现目标；只要任一选中行提供 `DANGER` 强度动作即合并进批量按钮；单击立即逐条提交，无第二次确认；完成后只弹 toast，`state.undoOffer` 不为批次建立。单条路径由 `useTransitionConfirm.ts:39-52` 对不可逆要求二次点击、`useUndoOffer` 提供 10 秒撤销——批量是唯一绕过这两条规则的执行面。
- 改进：`bulkTransitionTargets` 保留 `reversible`；不可逆目标先经确认对话框（明示「将影响 N 项、不可恢复」）；可逆批次提供批次撤销或明示「批量不提供撤销」；danger 颜色不得是唯一警示。

### I02（P2）Today 顶部数字用「已加载行数」，与 chip 的服务器总数矛盾
- 位置：`crewscope-web/src/pages/TodayPage.vue:459-463`，对照 `:161-165`、`:524`、`:628`
- 现象：任一分组分页后 `section.total > section.items.length` 时，顶部「需要你行动 N 项」用已加载数，下方同组 chip 用服务器总数；加载到 0 行时「推进中」显示 0 而总数可能非 0。
- 改进：顶部三项统一用 `actionRequiredTotal` / `sectionTotal(...)`（chip 已是正确写法）；要表达已加载必须显式标注。

### I03（P2）Today「加载更多」失败完全静默
- 位置：`TodayPage.vue:177-187`；错误写入 `domains/workdesk/store.ts:71-100` 的 `sectionErrorMessage`（页面零消费）
- 现象：下一页失败后按钮 spinner 结束、行数不增，无 toast、无内联错误、无重试；多分组共用 `deskLoading`，一个分组失败全部按钮一起停转。
- 改进：分组 footer 渲染该组 `sectionErrorMessage` + 「重试本页」（只重试该组）；沿用 `SearchPage.vue:187` 的「以上结果已保留 / 重试本页」模板。

### I04（P2）Search 空结果的「清除筛选」在无筛选时是无效按钮
- 位置：`crewscope-web/src/pages/SearchPage.vue:178`、`:103-106`
- 现象：无结果且 `selectedTypes` 为空时仍渲染「清除筛选」；点击提交同一 query（还可能用输入框草稿重搜一次），页面无任何变化。
- 改进：仅 `selectedTypes.length > 0` 时渲染；否则提供「清空关键词」或隐藏 action；空态文案按真实原因分支。

### I05（P2）Quota 单月用量失败是死路，重选同月也不重试
- 位置：`pages/QuotaSettingsPage.vue:263`、`:111-118`
- 现象：面板只写「该月用量读取失败。」无 `@retry`；`selectMonth` 同月只 `router.replace` 同一 query，watch 不触发，必须切月再切回。
- 改进：错误面板加「重试该月」（`store.loadUsageMonth` 对非 ready 月份会重新请求）；同月点击显式 reload。

### I06（P2）责任交接预览失败被伪装成「还没选角色」
- 位置：`pages/TeamMembersPage.vue:189-198`；`components/team/ResponsibilityHandoverDialog.vue:86-87`
- 现象：`previewResponsibilities` 失败时 `handoverPreviewCount` 置 null，页面显示「选择角色后显示在途责任数量」；用户可在不知道影响面的情况下继续创建交接。
- 改进：预览 loading/error 作为显式状态区分于「未选择」；失败显示「无法读取在途责任数量」+ 重试。

### I07（P3）Setup 的「选择此目标」只改变高亮，不改变承诺的下一步
- 位置：`pages/SetupPage.vue:59-74`、`:194-203`
- 现象：`goalCards` 的 `next` 计算完全不依赖 `selectedGoal`；文案却承诺「目标只影响这页展示的下一步」。
- 改进：`nextAction`/卡片 `next` 由 `selectedGoal` 派生，或删除选择器改两个直接入口；纯视觉选择必须改掉承诺文案。

### I08（P2）行级操作失败后只留 4 秒 toast，没有持久结果
- 位置：`WorkPage.vue:698-700`；`TodayPage.vue:389-391`；toast 时长 `composables/useToast.ts:14-16`
- 现象：行菜单动作被拒绝/失败（版本冲突、超时）后唯一反馈是 toast，4 秒消失；行上无失败标记、无重试；`failed` 时用户不知道命令是否已落地。
- 改进：结果持久化到行级状态（批量结果条 `WorkItemsBulkBar.vue:133-148` 已是正确形态）；失败附「刷新确认」动作，danger toast 不自动消失。

### I09（P2）撤销只存在于自动消失的 toast，键盘用户难以到达
- 位置：`domains/workitem/useUndoOffer.ts:19-31`；`components/feedback/ToastHost.vue:8-12`；`App.vue:62-65`
- 现象：可逆转换成功的撤销入口只在 toast 里，时长等于撤销窗口且无 hover/focus 暂停；`ToastHost` 在 RouterView 之前，正向 Tab 不可达；读屏仅 polite（需实机确认播报时序）。
- 改进：toast hover/focus 暂停计时；危险 tone 用 assertive；提供键盘可达的内联撤销入口（受影响行/工具栏，绑定 `undoTransition`）。

### I10（P3）Observability 明细/质量错误面板没有局部重试
- 位置：`pages/ObservabilityPage.vue:209`、`:242`（对照月份错误 `:149-152` 有重试）
- 改进：明细/质量面板绑定 `@retry` 只重载对应资源；页头刷新只作全局兜底。

### I11（P3）TeamObserver 打开证据时点击行没有可见进度
- 位置：`components/domain/TeamObserverWorkspace.vue:73-87`、`:166-170`
- 现象：点击「查看证据」后按钮文字不变、无 spinner/`aria-busy`；所有证据按钮一起禁用，解释只有 sr-only 文本。
- 改进：被点击行用 `:loading` + `aria-busy`，行内「正在打开…」；其他行保持可点或给出可见禁用原因。

### I12（P3）Search 的「找到 N 条结果」实际是已加载数
- 位置：`SearchPage.vue:180`、`:187`
- 改进：改「已加载 N 条」或用服务端总数；`AuditExplorer.vue:184` 的「N 条已加载」是正确模板。

### I13（P3）路由切换后没有焦点落点
- 位置：`app/router.ts:253-257`（afterEach 只更新 title）；`AppShell.vue:319`（main 有 `tabindex="-1"` 但不主动聚焦）
- 现象：键盘/读屏用户切页后停留在被卸载的触发元素或 body 上。
- 改进：path 变化时聚焦 `#main-workspace` 或新页 h1 并播报标题；仅 query/hash 变化不抢焦点。

### C1（P2）全局 `scrollBehavior: { top: 0 }` 同时破坏列表上下文与设置页锚点
- 位置：`app/router.ts:229`；受影响：`InboxPage.vue:119-121`、`AuditPage.vue:118-129`、`ActivityPage.vue:119-120`、`SearchPage.vue:79`、`QuotaSettingsPage.vue:116`；锚点侧 `SettingsShell.vue:61`、`AccountPage.vue:44-48`
- 现象：同页仅 query 变化（开关详情、切筛选、切月）也回顶部；`/account#security` 只更新高亮不滚动。关闭详情后找不到原来的行。
- 改进：`savedPosition` 恢复（前进/后退）；`to.hash` 用 `{ el, top: 80 }`；同 path 仅 query 变化保留滚动；仅 path 变化回顶部；配 `scroll-margin-top`（具体滚动量需实机）。

### C2（P2）「局部失败有没有下一步」没有统一契约
- 位置：`components/feedback/StatePanel.vue:36-58` 只在调用方接 `@retry` 时渲染重试；带重试：Observability 月份、Quota 整体、Inbox 详情；无重试：Observability 明细/质量、Quota 单月、Inbox 处置命令；完全静默：Today 加载更多（I03）。
- 改进：StatePanel 错误态约定「必须有恢复动作或显式不可恢复原因」；可用包装组件/单测强制（error/offline 必须传 `@retry` 或 `retryable=false` + reason）；局部错误只重试局部。

### C3（P2）复制操作三种反馈形态，失败多数静默
- 位置：`composables/useClipboard.ts:7-21` 失败只返回 false；图标切换型：`ConversationPage.vue:1041`、`TeamMembersPage.vue:323`、`SafeMarkdown.vue:52-64`；文字切换型：`AgentConfigurationPanel.vue:478`；显式错误型（唯一正确）：`TeamInvitationManager.vue:107-115`、`:181-188`
- 现象：非安全上下文/权限拒绝时点击复制无任何结果，随后粘贴出旧内容；成功也无读屏播报。
- 改进：统一 `CopyButton`/`copyWithFeedback`：成功 `role="status"` 播报「已复制」；失败显示「无法复制，请手动选择」并聚焦错误摘要；实机覆盖权限被拒场景。

### C4（P2）内联详情面板的打开/关闭焦点生命周期不一致
- 位置：打开聚焦标题：`InboxWorkspace.vue:126-132`、`AuditExplorer.vue:127-134`；Activity 无：`ActivityPage.vue:119`、`:172`；关闭三者都不还原焦点；对照 `composables/useFocusTrap.ts:67` 的抽屉还原模型。
- 改进：抽 `useDetailPaneFocus(trigger, heading)`：打开聚焦详情标题、关闭还原触发按钮；移动端 `scrollIntoView`。

### C5（P3）「刷新/切换数据源」两种策略：保留旧内容 vs 整块替换
- 位置：整块替换：`SetupPage.vue:161-177`、`domains/observability/store.ts:101-116` + `ObservabilityPage.vue:208-243`；保留旧内容（正确）：`SearchPage.vue:175`、`TodayPage.vue:472,515-520`、`OperationsWorkspace.vue:195,227`
- 改进：统一 stale-while-refresh：有旧数据时保留内容 + `aria-busy` + 「正在刷新」；仅首次加载用全页面板；loading 面板设最小高度减 CLS（跳动幅度需实机）。

## 6. 可访问性·移动端·文案轴（A01–A27、S6–S14）

### A01（P1）命令面板 listbox 语义与键盘顺序缺陷
- 位置：`crewscope-web/src/components/action/CommandPalette.vue:239-251`、`:98`
- 现象：`role="listbox"` 容器内直接放 `section/ul/button`，按钮无 `role="option"`，`aria-selected` 挂在普通 button 上不被读屏识别；搜索框无 `role="combobox"`/`aria-controls`/`aria-activedescendant`，方向键只改 `selectedIndex` 而 DOM 焦点留在输入框；`selectableCount` 排除 `recentItems`——「最近访问」永远无法键盘选中；「搜索全部」是 listbox 内的普通按钮，破坏子项契约。
- 改进：按 DOM 顺序建立 option 索引（含最近访问）；option 唯一 id + 输入框 `aria-activedescendant`；分组 `role="group"` + `aria-labelledby`；「搜索全部」移出 listbox；或整体改标准 combobox + listbox 模式。

### A02（P1）「添加已有用户」名不副实
- 位置：`crewscope-web/src/pages/TeamMembersPage.vue:294-298`（标题与说明）；`:45`（`rejoinCandidates` = `member.status !== 'ACTIVE'`）
- 现象：标题承诺「添加已有用户」、说明写「同一 Organization 下的 ACTIVE USER Principal ID」，实际选项只含停用成员的重新加入；无候选时提示「新成员请切换到加入邀请」，与任务承诺矛盾。错误提示 `role="alert"` 无 id、未关联字段。根因与第一轮 R06 同源：无组织成员目录（M13b-A01 后端补齐后此页换组织目录选人器）。
- 改进：改为服务端可搜索的用户选择器（姓名/用户名/Principal ID）；错误提示补 id 并 `aria-describedby` 关联。

### A03（P2）通知偏好切换 Team 不检查 dirty，未保存开关静默丢失
- 位置：`crewscope-web/src/components/account/TeamNotificationPreferenceCard.vue:39`、`:125`
- 现象：`watch(selectedTeamId)` 立即 reload 覆盖草稿；保存成功徽标无 `aria-live`。
- 改进：dirty 时拦截提示「先保存或放弃更改」或改自动保存；保存状态入 `role="status"`。

### A04（P2）审计「关联链」打开/关闭无焦点管理，事件类型仍是原始枚举
- 位置：`components/domain/AuditExplorer.vue:262-266`；`pages/AuditPage.vue:130-140`
- 改进：打开聚焦面板标题、关闭还原触发按钮、Esc 关闭；`eventType` 走统一映射。

### A05（P2）404 页双 main、双 h1、标题层级跳跃
- 位置：`pages/NotFoundPage.vue:11`
- 现象：AppShell 已有 `<main>` 与 h1；页面内再渲染 `<main class="not-found">` 与第二个 h1；StatePanel 固定 h3，层级从 h1 跳到 h3。
- 改进：删内层 main 与重复 h1；页面内 h2；StatePanel 标题层级可配置（见 A16）。

### A06（P2）safe-area 全套缺失
- 位置：`crewscope-web/index.html:5`（viewport 缺 `viewport-fit=cover`）；`AppShell.vue:433`（底部导航无安全区）；`ToastHost.vue:15`（固定 `bottom:16px`）；`BaseDrawer.vue:30`（footer 无安全区）
- 现象：`env(safe-area-inset-bottom)` 实际为 0；Toast 与 60px 底栏重叠。
- 改进：加 `viewport-fit=cover`；底部导航 `padding-bottom: env(safe-area-inset-bottom)`；Toast `bottom: calc(16px + env(...))`（移动端叠加底栏高度）；抽屉/对话框 footer 同。

### A07（P2）toast 时长与位置：带操作的 toast 4 秒消失且移动端被底栏遮挡
- 位置：`ToastHost.vue:13-15`；`composables/useToast.ts:14-16`
- 改进：带操作 toast ≥10 秒或保持到用户操作；hover/focus 暂停；移动端底部偏移含底栏 + 安全区（与 I09/A06 同笔）。

### A08（P2）成员页触控目标 20/30px，完整 ID 只在 title 里
- 位置：`pages/TeamMembersPage.vue:313`、`:323`、`:386`、`:391`
- 现象：复制按钮 20×20、关闭 30×30；完整 Principal ID 触屏/键盘不可获得；成员名单元格非 `rowheader`。
- 改进：图标按钮 ≥44px 命中区（视觉可小时用 `.touch-target`）；完整 ID 可展开或复制后可见；成员名 `role="rowheader"`。

### A09（P2）账号页双导航且缺「通知偏好」，无 hash 时侧栏无当前项
- 位置：`components/account/AccountWorkspace.vue:221-224`；`SettingsShell.vue:22`、`:31-33`
- 改进：删重复内部导航或同源生成；补「通知偏好」；hash 项滚动监听 + `aria-current="location"`；无 hash 时「账号」标当前。

### A10（P2）组织删除区：标题歧义、初始即 `aria-invalid`、全零 placeholder
- 位置：`pages/OrganizationSettingsPage.vue:264`；`components/domain/OrganizationDeletionWorkflow.vue:146-147`
- 现象：「账号删除」未说明删除的是指定用户账号；输入框初始为空即 `aria-invalid=true`；placeholder 是全零 UUID 增加误填风险。与第一轮 R07（UUID 无获取路径）同区，本条是文案与校验时序。
- 改进：标题「删除指定用户账号（不可恢复）」；换用户选择器（M13b-A01/F02）；仅提交/失焦后置 `aria-invalid`；格式提示放字段下方。

### A11（P2）蒸馏对话框要求手抄 UUID + 自发明 Key
- 位置：`components/domain/KnowledgeDistillationDialog.vue:117-147`；`SkillDistillationDialog.vue`；`KnowledgeBasePage.vue:201`
- 现象：入口是页面级「从执行蒸馏」，对话框却要求手粘执行 UUID 并自定 Key；无执行选择器/最近执行/带入路径。与第一轮 R03（UUID 无获取路径）同区，本条是对话框形态。
- 改进：两步选择器（先选最近执行，再预览并自动生成可编辑 Key）；手粘收进「高级」；文案改「选择一次执行，系统会生成知识条目草稿」。

### A12（P2）GitHub 连接卡 `role="listitem"` 覆盖 button 语义，选中态只靠 class
- 位置：`pages/GitHubSettingsPage.vue:624-625`
- 改进：`<li><button>` 或 div 包裹原生 button；补 `aria-pressed`/`aria-current`。

### A13（P2）模型 Provider 与连接归属选择无选中语义
- 位置：`pages/ModelSettingsPage.vue:345-350`；`components/domain/ModelCredentialDialog.vue:152-154`
- 改进：Provider 用 `aria-pressed`；归属用 radiogroup/radio + `aria-checked`。

### A14（P2）Observability 筛选 select 被 `min-height:0` 压到 <44px；占位符 `aria-label` 挂在无角色 span 上
- 位置：`pages/ObservabilityPage.vue:225`、`:289`
- 改进：删 `min-height:0` 覆盖；占位改 `aria-hidden="true"` + sr-only 文本。

### A15（P2）Auth 页 skip link 目标不可编程聚焦
- 位置：`components/auth/AuthLayout.vue:27`
- 现象：链接指向 `#identity-primary`，但 main 无 `tabindex="-1"`；激活后只滚动，焦点仍留在链接上。
- 改进：main 加 `tabindex="-1"` 并聚焦；对齐 AppShell `#main-workspace` 模式。

### A16（P2）StatePanel 标题永远 h3，全站层级跳跃
- 位置：`components/feedback/StatePanel.vue:52`
- 改进：增加 `headingLevel` 属性（默认 h2）；或渲染 `p > strong` 保留 live region 语义。

### A17（P2）搜索结果行是 button 不是链接；计数与状态伪装
- 位置：`pages/SearchPage.vue:180`、`:184`、`:138-141`
- 现象：无法 Cmd+点击/新标签打开；「找到 N 条」是已加载数（与 I12 同源）；`SPECIALIST` 未本地化；未知状态回退成「状态已更新」掩盖真实状态。
- 改进：改 `RouterLink`；计数与 I12 同笔；`SPECIALIST` 本地化；未知状态显示「状态未知」并给详情。

### A18（P2）Activity 中英混用 + 实时状态不在 live region
- 位置：`pages/ActivityPage.vue:154-156`、`:172-178`；`components/domain/ActivityStream.vue:105-107`、`:129`
- 现象：可见筛选标签「Category/Actor」、详情字段 Event/Category/Actor/Subject；`eventType` 原样输出；断线/重连不播报；关闭详情无焦点返回（与 C4 同笔）。
- 改进：「类别/成员」等中文映射；枚举走 labels；实时状态入 `role="status"`；详情焦点生命周期统一。

### A19（P3）BaseListShell 离线提示不随状态渲染
- 位置：`components/shared/BaseListShell.vue:90`——「离线时续页读取保持关闭」无 `v-if="offline"`，在线也显示。
- 改进：仅离线渲染。

### A20（P3）视图切换容器 aria-label 挂无角色 div；按钮英文且无选中语义
- 位置：`components/domain/WorkItemsToolbar.vue:93`、`:98-103`；`WorkItemsWorkspace.vue:65`、`:68`
- 改进：容器 `role="group"` + label；List/Board/Graph 改「列表/看板/图谱」+ `aria-pressed`。

### A21（P3）飞书管理 nav 选中态只靠 class
- 位置：`components/domain/LarkNotificationAdmin.vue:323-324`
- 改进：tablist/tab/`aria-selected` 或 `aria-current`。

### A22（P3）快捷键帮助与偏好恢复无可见入口
- 位置：`app/shortcuts.ts:52`；`composables/usePreference.ts:55-76`
- 改进：账号菜单/账号页加「键盘快捷键」；外观设置旁加「恢复默认外观」。

### A23（P3）评论为空时「让 Agent 处理」禁用原因只在 title
- 位置：`components/domain/WorkItemDetailDrawer.vue:492`
- 改进：常驻可见提示或 `aria-describedby` 关联可见说明。

### A24（P3）导出空态是工程细节文案；校验和截断值触屏不可得
- 位置：`components/domain/OrganizationExportWorkflow.vue:166`、`:191`、`:200`
- 改进：「当前会话没有导出作业。历史导出记录可在审计中心查看」；校验和提供复制或可展开完整值。

### A25（P3，需实机）对话框/抽屉无软键盘避让
- 位置：`components/base/BaseDialog.vue:40`、`BaseDrawer.vue:30`
- 改进：监听 `visualViewport` 调整浮层高度；输入聚焦 `scrollIntoView`；390×844 与横屏实测。

### A26（P3，需实机）审计移动端表格 `display:block` 可能破坏表格语义
- 位置：`components/domain/AuditExplorer.vue:292`
- 改进：保留表格语义用 CSS grid 重排，或显式补 role；VoiceOver/NVDA 实测。

### A27（P3，需实机）图表 SVG 与 sr-only 数据表可能双读
- 位置：`components/charts/LineChart.vue:92-93`、`:136-153`；`BarChart.vue:73-74`、`:104-106`
- 改进：有数据表时视觉 SVG `aria-hidden="true"`；只保留一个可访问表示。

### S6（系统级，原 R01）中英混用与内部概念跨页存在
全量实例（除第一轮已列）：Activity（Category/Actor/Event/Team pulse/Shared facts）；Audit（Outcome/Initiator/Agent Principal/Subject UUID/Correlation/Cross-object trace）；Operations（Backlog/In flight/Dead Letter/Idempotency-Key/ACTIVE-SHADOW/Strong confirmation）；Repository（Repository Key/Preflight/CodingTarget/Managed Root）；Model（Provider/Server registry/Retention/Capability）；Lark（Credential boundary/Proof Receipt）；Agent（roster/PERSONAL-TEAM/Reviewer Specialist/Task Delivery Summary）；Members（MEMBER_MANAGE/If-Match/Team Ready）；Invitation/AuthBrand（Team execution/Your work. Your agent. One crew.）；Onboarding（「原子准备」「原子初始化请求」）；Quota（rollup/预占/口径）；路由标题（「我的 Inbox」「Skill 目录」「Agent 中心」）。→ 建立产品术语表；中文为主；技术名只在「高级/开发者」区域括注。

### S7（系统级，原 R02）原始枚举与技术字段直出
`ActivityPage.vue:177-178`（eventType + payload key）；`ActivityStream.vue:129`；`AuditExplorer.vue:191-192,238,251,274`（筛选与详情枚举）；`SearchPage.vue:138-141`（SPECIALIST/伪状态）；Operations/Repository/Quota 大量枚举直出。→ 一律经 `enumLabel`；未知值显示「未知（原始值）」；payload key 中文映射，原始 key 只进「技术详情」折叠。

### S8（系统级，原 R03）选中/当前状态只靠视觉 class
List/Board/Graph（`WorkItemsToolbar.vue:99-103`）、Provider（`ModelSettingsPage.vue:346`）、连接归属（`ModelCredentialDialog.vue:152-154`）、飞书视图（`LarkNotificationAdmin.vue:324`）、Agent 归属（`AgentCreateDialog.vue:106-109`）、版本按钮（`AgentConfigurationHistoryPanel.vue:33`）、GitHub 连接（`GitHubSettingsPage.vue:625`）。正确母本：Inbox 视图 `aria-current`、Repository owner `aria-pressed`、Observability 月份 `aria-pressed`。→ 单选用 radio 语义；切换用 tab/`aria-pressed`；导航用 `aria-current`。

### S9（系统级，原 R04）44px 触控系统性缺口
`design/base.css:183-191` 只覆盖部分元素且无 `min-width`。缺口清单：导航链接 37/31/42/38px；图标按钮 20–32px（复制、关闭、抽屉、对话框、密码显隐等十余处）；输入与 select 24–40px（Observability `min-height:0` 覆盖、搜索框 38px 等）。→ 移动端对 `a, button, input, select, textarea, summary, [role=...]` 统一 `min-height: var(--cs-touch-min)`；图标按钮补 `min-inline-size`；删 `min-height:0` 覆盖；孤立小链接用 `.touch-target`。

### S10（系统级，原 R05）表单错误、必填、说明文本关联不完整
`TeamMembersPage.vue:298`（alert 无 id）；`RepositorySettingsPage.vue:331-333`；`ModelCredentialDialog.vue:160`；`AccountWorkspace.vue:261`；`WorkItemDetailDrawer.vue:492`；`OrganizationDeletionWorkflow.vue:146`；`FormField.vue` 必填标记 `aria-hidden` 且无自动 `aria-required`；`BaseCheckbox` 无 describedby/invalid 透传。→ 统一字段契约：id + `aria-describedby` + `aria-invalid` + required；错误摘要 `role="alert"` 并聚焦；校验时机 blur/submit 后。

### S11（系统级，原 R06）`aria-label` 挂在无角色元素上被忽略
`AppShell.vue:279`（mode-switcher div）；`WorkItemsToolbar.vue:93,98`；`WorkItemsWorkspace.vue:65,68`；`ObservabilityPage.vue:225`（span）；`InboxWorkspace.vue:277`（i）；`AccountWorkspace.vue:217`；`AuthBrandPanel.vue:18`。→ 需要语义补 role；装饰用 `aria-hidden`；文字替身用 sr-only 文本。

### S12（系统级，原 R07）详情/关联面板焦点打开与返回
Activity 详情、Audit 关联链均无焦点管理（与 C4 同源合并处理）。→ 打开聚焦标题、关闭还原触发、Esc 关闭；query 驱动面板处理 popstate 焦点。

### S13（系统级，原 R08）文案一致性
「展示名/展示名称」；角色英文与中文混用；List/Board/Graph vs 列表/看板/图谱；创建/添加/新建、移除/停用/撤销不统一；Inbox/通知/通知偏好/团队动态/团队活动/Activity 多套叫法。→ 术语表 + 共享 labels；同一动作一个动词。

### S14（系统级，原 R09）禁用原因对键盘/读屏不可达
`OperationsWorkspace.vue:239-244,255-256`、`RepositorySettingsPage.vue:251,323-330`、`LarkNotificationAdmin`：原因在 sr-only + `aria-describedby`，但按钮 `disabled` 不可聚焦读不到。正确母本：`KnowledgeEntryDetail.vue:147` 按钮旁可见 `disabledReason`。→ 原因可见或关联到可聚焦元素；必要时保持可聚焦、激活时解释。

## 7. P1 三条详述（主会话复核记录）

1. **I01 批量绕过安全规则——成立**。`WorkPage.vue:552-561` 的 `runBulkTransition` 直接调 `workStore.transitionRows`；`WorkItemsBulkBar.vue:103-111` 批量按钮 `@click="runTarget(target)"` 无任何确认；`domains/workitem/list.ts:101-118` `bulkTransitionTargets` 计算了 `strength` 但页面未用于确认/撤销分叉；单条规则完整（`useTransitionConfirm.ts:39-52` 不可逆二次点击 + `useUndoOffer` 10 秒撤销）。批量是唯一绕过面，且批量影响 N 项、后果大于单条——契约倒挂。
2. **A01 命令面板——成立**。`CommandPalette.vue:98` `selectableCount = savedViewItems + availableEntries + objectItems`（排除 recentItems）；`:239-251` `role="listbox"` 内是普通 button + `aria-selected`。⌘K 是全站高频入口，键盘/读屏用户在此被降级为二等用户，「最近访问」永远不可键盘选中。
3. **A02 添加已有用户——成立**。`TeamMembersPage.vue` 标题区承诺添加组织内已有用户，`:45` 候选过滤 `status !== 'ACTIVE'` 只含停用成员。与第一轮 R06 同根因（无组织成员目录端点，`PrincipalDirectoryController` 路径含 `{teamId}` 且 SQL 按 team 过滤）——M13b-A01 补目录后此页一并换选人器，一个后端能力关两个 P1/P2。

## 8. 做得好应保留（第二轮正面基线）

1. **AppShell fill 模式**：`height:100dvh` + flex column 把真实剩余视口交给 workspace（`AppShell.vue:397-400`）——对话类页面正确地基。
2. **状态阶梯与保留旧结果**：`BaseListShell` + `StatePanel` 统一 loading/forbidden/offline/error；Search 刷新失败保留上次结果、续页失败限制在局部（`SearchPage.vue:175-188`）。
3. **工作项列表行密度与看板键盘**：三列对齐网格、12/16px padding、标题单行截断；`useBoardDrag.ts:80-119` Space 拾起/方向键选列/Enter 放下并实时旁白。
4. **响应式表格两种正确处理**：Audit ≤780 转 `data-label` 卡片、TeamMembers ≤850 隐藏低价值列（`AuditExplorer.vue:292`、`TeamMembersPage.vue:389`）。
5. **详情抽屉内联命令错误**：失败留在动作旁、`role="alert"` + 重试、pending 统一禁用（`WorkItemDetailDrawer.vue:258,382-414`）。
6. **单条状态转换的服务端可逆性驱动模型**：`useTransitionConfirm` + `useUndoOffer` + Store 10 秒窗口——批量应复用而不是另起一套（I01 整改母本）。
7. **对话发送的乐观更新与提交后校验**：clientId/idempotencyKey/baselineSequence 管理 pending，无法确认时给「消息已提交，但暂时无法从历史确认」并保留重试（`messageStore.ts:134-200`）。
8. **邀请复制的失败处理**：`TeamInvitationManager.vue:107-115,181-188` 显示失败 + 手动复制指引 + 焦点移到 alert——C3 统一母本。
9. **Operations 强确认**：手输短语 + expected version + 幂等键，危险操作的良好实现。
10. **焦点与动效基线**：统一 `:focus-visible`（对比度实测 5.63/9.43）、全局 reduced-motion、`color-scheme` 双主题；skip link + 单一 h1 + `#main-workspace` 构成正确跳转路径。
11. **图表可访问性方向正确**：sr-only 数据表、可见柱值、非颜色区分参考线、超限降级为表格。
12. **依赖图键盘等价**：`WorkGraphCanvas.vue:75-126` canvas role=group + 缩放键 + sr-only 节点清单 + 节点 Enter/Space。
13. **BaseMenu 键盘契约完整**：Arrow/Home/End/Esc 归还焦点、`aria-haspopup/expanded`。
14. **认证字段与危险确认焦点**：AuthField 接线完整；AuthErrorSummary `role=alert` 自动聚焦；ConfirmHost 初始焦点在「取消」安全侧。
15. **移动端/暗色全覆盖**（第一轮正面基线 6 维持）：汉堡抽屉、底部三格、离线横幅、双 token 暗色。
16. **动效全局尊重 `prefers-reduced-motion`**（`design/base.css:227-239`）。

## 9. 与 M13b 工作包映射

| 工作包 | 承接（第二轮） | 承接（第一轮） | Q01 状态 |
| --- | --- | --- | --- |
| S01 | 本文入档 | Review 文档已入档（`ed4f3db`） | 已交付·`ed4f3db` |
| A01 后端·组织成员目录 | 二轮 A02 根因侧 | R06/R07 | 已交付·`02b0fca4` |
| A02 后端·设备摘要 | — | R08 | 已交付·`e11911af` |
| A03 后端·知识开关端点 | — | R04 | 已交付·`cb5eb34c` |
| A04 后端·条目重试与过滤 | — | R16/R17 | 已交付·`3177b2b5` |
| A05 后端·知识回滚 | — | R28 | 已交付·`770ac20a` |
| A06 后端·预算读面 | — | R21 | 已交付·`faacbf15` |
| A07 后端·未读投影 V71 | — | R12 | 已交付·`94299d8a` |
| F01 组织导航与登录落点 | — | R01/R05/R33/R49 | 已交付·`1d848df7` |
| F02 治理表单选人化 | A10 | R06/R07/R08/R39–R43 | 已交付·`1c4b39d2` |
| F03 流程断头路 | A11 | R02/R03/R14/R31 | 已交付·`ba43f845` |
| F04 知识闭环可见性 | L04（effectiveTitle 后端补摘要同笔） | R04/R15–R22/R28–R30/R32 | 已交付·`1320c40c`+`842469ae` |
| F05 协作事实同步 | — | R11/R13/R34–R37 | 已交付·`2790d8e4`+`be3af3d2` |
| F06 对话未读与徽标 | — | R12/R24/R38 | 已交付·`ef26dc09` |
| F07 SSO 与邀请衔接 | — | R09/R10/R44/R48 | 已交付·`6034bd91` |
| F08 打磨批 | A17 部分（SPECIALIST/伪状态与 R26/R27 同笔） | R25–R27/R42/R45–R47/R50 | 已交付·`0fe13de9` |
| F09 布局系统与对话区 | **R51 + S1/S2/S4/S5 + L15** | — | 已交付·`06cc96eb`+`e70d2025` |
| F10 页面级 IA 与扫描效率 | **L01–L16** | R26（行首标识与 L02 同笔） | 已交付·`28003cb2` |
| F11 交互反馈与状态契约 | **I01–I13 + C1–C5** | — | 已交付·`90943cf0`+`99982b93`+`3f112513` |
| F12 可访问性/移动端/术语 | **二轮 A01–A27 + S6–S14** | R05 后半（表单关联）/R04 触控等 | 已交付·`23b51844` |
| Q01 收口 | 两轮全量台账 + 四视口量测 + axe + 批量安全断言 | R01 实机链路翻绿、R23 e2e 可达性维度 | 已交付·本笔（见第一轮 Review §10 台账） |

**Q01 收口结论（2026-10-24）**：本文全部发现（3 P1 / I01/A01/A02 + 其余 L/C/A/S 条目）经 A01–A07、F01–F12 全部交付关闭，逐包提交见上表「Q01 状态」列；第二轮 **P1 = 0**。§8 标注「需量测/需实机」的 9 处已分别进入 F09/F11/F12 的验收量测与 e2e（含 Q01 四视口量测与关键页 axe）。「未覆盖」四项（竞品对照/真实栈动态/读屏实机/视觉稿对照）作为后续里程碑候选边界保留。 已交付·本笔（见第一轮 Review §10 台账） |

## 10. 走查覆盖与边界

- 覆盖：29 路由 + 2 redirect（§3 矩阵）；横切机制（滚动行为/焦点/toast/剪贴板/触控/术语/枚举呈现）；三轴合计约 570 次源码读取，未修改任何文件。
- 证据性质：全部条目代码确认；P1 三条主会话二次复核（§7）。标注「需量测/需实机」共 9 处：L01（可见行数）、L08（首行位置）、L10（1440 临界）、R51（对话区四视口）、C1（滚动量）、C5（跳动幅度）、I09（读屏播报）、A25–A27（软键盘/移动表格/图表双读）——全部进入 F09/F11/F12 的验收量测清单。
- 未覆盖：竞品实机对照（第一轮同样未做）；Docker 真实栈动态行为；读屏实机（VoiceOver/NVDA）；视觉稿对照（本轮以代码与 CSS 推算为主）。
- 与第一轮的分工：第一轮按用户旅程找断点（四轴 + mock 栈实机取证），第二轮按页面与机制找质量欠账（三轴、纯代码走查）。两轮相近项已在条目内标注边界与根因差异（L14 vs R05、A10 vs R07、A11 vs R03/R14、L02 vs R26、A17/I12 vs R26/R27），不重复计数。
