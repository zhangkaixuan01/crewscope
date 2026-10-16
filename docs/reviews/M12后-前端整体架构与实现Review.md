# CrewScope M12 后前端整体架构与实现 Review

> 评审基线：`91caeb5`（2026-10-16，M12 主线收官 + last-owner 并发修复之后）<br>
> 评审范围：`crewscope-web/src` 全部 **424 个非测试源文件**（.ts/.vue，排除 `*.spec.ts` 与 `env.d.ts`）——`api` / `app` / `components` / `composables` / `design` / `domains` / `pages` / `spikes`，另覆盖 `e2e/` 结构、`scripts/` 前端门禁、`package.json` 依赖面<br>
> 评审方式：三路并行——①主评审跑全量依赖图审计（自写脚本：可达性/孤儿/SCC 循环/跨层违规/composables 使用数）；②29 个页面 .vue 全文逐个通读（含 WorkPage 2080 行、ConversationPage 1453 行）；③components 约 200 个文件按六维模式（button/select/table/keydown/backdrop/role/props/emits）全扫 + 大文件精读。**两份子报告的关键断言已逐条经 grep/wc 抽查复核，本文数字以复核后为准**<br>
> 微交互注记：评审基线之上已落一笔微交互包（`f45db0c`：BaseButton 按压回弹+圆角 md + BaseDialog/BaseDrawer 进出场动画，见 §5.4），本文其余发现不受其影响<br>
> 第二轮增补（2026-10-17）：§10 交互与样式设计走查（灵动视角）——23 个页面状态的基线截图视觉走查 + 灵动度量化审计，与第一轮代码层发现互补<br>
> 评审角色：前端架构 + 组件库 + 交互工程；第二轮增补为交互/视觉设计视角<br>
> 结论：**分层架构、类型卫生、设计系统 token 化已达 A 档；核心债务集中在一件事——「复制代替抽象」。模态外壳手写 25 份、下拉 6 套、查询工具 7 份、双胞胎组件 6 组、store 样板 28 份。修复是收敛的：接通 2 个已建成的死基础件、下沉 2 个 composable、抽 6 个泛型骨架，可一次性消化约 4000+ 行重复，并顺带消灭全部已知的键盘/层叠一致性缺陷。**

---

## 1. 总体评价

### 1.1 记分卡

| 维度 | 评分 | 依据 |
|---|---:|---|
| 分层架构与依赖方向 | A- | `api/domains/composables/components/pages` 边界干净：domains→pages 与 components→pages 各 0 条生产违规（仅 1 条 story dev 工具）；但存在 1 组循环 `api/creationRecovery.ts ↔ domains/scope/types.ts` |
| 类型卫生 | A | 全 424 文件 `as any` 仅 1 处、裸 `any` 3 处、`@ts-ignore` 0 处；store 统一 InjectionKey + `use*Store()`；`openapi.ts` 作为漂移金丝雀挂进 CI（非死代码） |
| 状态管理与竞态防护 | B+ | `createRequestScope`（epoch+channel abort+coordinate）+ `usePageRequestScope`（20 个消费者）体系优秀；但 GitHubSettings/LarkSettings 两页手搓绕过、QuotaSettings/OrganizationSettings 无守卫、约 28 个 store 手写 AbortController 样板未走统一入口 |
| 设计系统落地 | A- | token 全覆盖（色彩/字号/间距/圆角/动效/缓动），5 道前端门禁 + 4 道 CI 独有门禁；缺口：utility 类未沉淀（`.mono`×8、`.dialog-icon`×8、`.eyebrow`×33、`@keyframes spin`×4 份各自复制） |
| **组件复用** | **D+** | 手写模态外壳+焦点陷阱 **25 份/24 文件**（4+ 种行为变体），BaseDialog 仅 1 个消费者、BaseDrawer 0 个；双胞胎组件 6 组；6 套手写下拉而键盘完整的 BaseDropdown 是死代码；同构 props（online×30/errorMessage×31）零公共类型 |
| **控件契约一致性** | **C** | 裸 `<button>` 202 处 vs BaseButton 412 处；原生 `<select>` 27 文件 vs BaseSelect 8 文件；原生 table 5 处 vs BaseTable 5 处；on* 回调 props 约 120 处与 typed emits 双范式并存 |
| 交互与可访问性 | B- | 图标钮 aria-label 覆盖率极高（纯图标无名钮仅 1 处）、auth 目录是 id 关联范本；但 ConfirmHost 确认框无焦点陷阱/Esc、BaseTabs 与 ModelSettingsPage 的 tablist 声明了 ARIA tab 语义却无方向键、2 个 team 弹窗 Esc 会穿透层叠、`title=` 376 处 vs BaseTooltip 6 个消费者 |
| 三态覆盖与反馈 | B+ | StatePanel 体系（loading/empty/error/forbidden/offline）覆盖绝大多数页面，check-empty-states 门禁守住空态 actionability；缺口：Observability 质量统计节空态缺失、骨架屏仅 TodayPage 1 页、加载更多按钮 1 处手搓 |
| 死代码治理 | C+ | 真死代码：2 个 composable（useDialog/useDirtyGuard 别名）、1 个死文件（domains/task/route.ts）、1 个生产零引用组件（AuthCheckbox）、6 个零引用基础件、WorkPage 整段看板死样式（6 个类）；另有 21 story + 5 spikes + 2 预览属 dev 工具（Histoire，不入产物包，非债） |
| 测试与 e2e 基建 | B | 663 条 e2e + 46 处四断点视觉基线 + 门禁矩阵完整；结构性问题：mock 基建锁死在 app-shell.spec.ts 单文件 5188 行、Playwright 截图默认冻结动画（动效无法被基线守护，只能靠 DOM 断言/录屏） |

### 1.2 一句话结论

M8 时的问题是「地基不存在」——四年前的 Review 记录了 `components/base/` 仅 2 个组件、Transition 用量 0、330 处裸 title。M12 后的全面复查显示：**地基已经建成了，但没有接线**。BaseDialog/BaseDrawer/BaseDropdown/AuthCheckbox 全部实现完整（甚至带焦点陷阱与键盘合约），却分别只有 1、0、0、0 个消费者；与此同时 24 个文件手写了 25 份模态外壳、6 套下拉、约 120 处 on* props。**每一处手写副本都比对应基础件更差**（少了 isTopmostModal、少了方向键、少了 aria 语义），且已经开始漂移成 4 种行为变体。这解释了本评审最核心的判断：当前前端不需要「补能力」，需要「接线 + 拆副本」——一次收敛动作能同时消灭重复代码与已知的一致性缺陷，是全仓性价比最高的一批修复。

### 1.3 与 M8 后 Review 的对照（进步面）

M8 记分卡中的前端 D/F 项在 M9–M12 已系统性修复，本次复核确认：字号 token 化（不再有 ≤11px 主体文案）；禁用原因体系（check-disabled-reasons 门禁 + aria-describedby，QuotaSettingsPage.vue:252-259 是范本）；移动端导航可达（narrow project 全覆盖）；`document.title`/aria-current/面包屑已接入；StatePanel 空态 actionability 有专门门禁（check-empty-states + 豁免清单）；Transition 从 0 到 4 处（ToastHost/CommandPalette/BaseDialog/BaseDrawer）；脏态跟踪 useDirtyForm 13 个消费者。**这些不再重复展开，本文只记 M12 时点仍存在的问题。**

---

## 2. 架构与依赖（引用面）

### 2.1 分层与依赖方向：干净，但有 1 组循环依赖

全量依赖图（424 文件）实测跨层违规仅 2 条：

- **循环依赖（唯一一组 SCC）**：`api/creationRecovery.ts ↔ domains/scope/types.ts`。types.ts 定义资源形状，creationRecovery 引它做恢复语义，而 types.ts 又从 creationRecovery 导入（推断为错误信封/恢复标记类型）。api 层引用 domains 本身就违反「api 不依赖上层」的项目自定规则（dep-audit 判定），循环加剧了它。**修法**：把双方共同依赖的形状（错误信封或资源接口）下沉到 `api/` 或独立 `shared/` 模块，断开环。
- `components/RepositorySettings.story.vue → pages/RepositorySettingsPage.vue`：story dev 工具引用页面做演示，生产边界仍干净，可在 story 清理时顺手消除。

其余方向全部正确：domains→pages 0 条、components→pages 0 条（生产代码）、composables 不引用 pages/components。

### 2.2 孤儿文件 33 个的精确分类（main.ts 不可达）

| 类别 | 数量 | 清单 | 判定 |
|---|---:|---|---|
| Histoire story | 21 | `components/*.story.vue` 20 个 + `design/Tokens.story.vue` | dev 工具（`.histoire/` 在、`pnpm story` 脚本在），不入产物包，**非债**；但其中 4 个 story 是死基础件的唯一「用户」（见 §2.3） |
| 早期预览 | 2 | `stories/M7AuthStatePreview.vue`、`domains/demo/fixtures.ts` | story 依赖链，同上 |
| Spike | 5 | `spikes/m4/*` 3 个、`spikes/m7/*` 2 个 | 自带独立 main.ts，不可达是设计使然，**非债** |
| 漂移金丝雀 | 1 | `api/generated/openapi.ts` | **非死代码**：check-openapi-drift 以它为断言基准（controller 指纹漂移即红），误删会拆掉一道门禁 |
| **真死代码** | **4** | `composables/useDialog.ts`（11 行，0 引用）、`composables/useDirtyGuard.ts`（useDirtyForm 的导出别名，0 引用）、`domains/task/route.ts`（taskRouteSelection 0 引用）、`components/auth/AuthCheckbox.vue`（仅 spec 与 story 预览引用，生产 0） | 建议删除；AuthCheckbox 尤其可惜——它是全库 id 关联范本（useId + aria），却从未被任何生产表单采用 |

### 2.3 死基础件：8 个 base 组件中 6 个零引用

| 组件 | 生产引用 | 备注 |
|---|---:|---|
| BaseCard | 0 | 无消费者 |
| BaseDrawer | 0 | **完整实现（Teleport+焦点陷阱+Esc+滑入动画）却零接线**——而 2 个 585/581 行的抽屉全部手写（§3.1） |
| BaseDropdown | 0 | **键盘合约完整（方向键/Home/End/Esc/Tab）却零接线**——而 6 套手写下拉大多没有方向键（§3.3） |
| BaseEmptyState | 0 | 已被 StatePanel 的 empty 态实质取代 |
| BasePopover | 0 | 且自身误用 role="dialog"（§4.2） |
| BaseRadio | 0 | 无消费者 |
| BaseTag | 仅 Foundation.story | 演示专用 |
| BaseTabs | 2（KnowledgeEntryDetail/SkillDetail） | 唯一有真实用户者，但缺方向键与 aria-controls（§4.2） |

`base/index.ts` 桶导出以上全部——「看起来可用」的误导性大于便利性（全库无人从桶导入，barrel 本身 0 引用）。**修法**：BaseDrawer/BaseDropdown 按 §3 方案接线；BaseCard/BaseEmptyState/BasePopover/BaseRadio/BaseTag 删除并同步桶；BaseTabs 修键盘后保留。

### 2.4 composables 使用数全景（依赖图实测）

21 个 composable 全部有消费者（除 §2.2 两个死代码外）。值得注意的低频高价值件：`useVirtualList`（1）、`useResizablePane`（1）、`useRelativeTime`/`formatRelativeTime`（各 1，疑似同义两件）、`useRouteIntent`（1）。`usePageRequestScope` 20 个消费者是全库最成功的抽象；`useDirtyForm` 13 个、`useFocusTrap`/`useConfirm`/`useClipboard`/`useToast` 各 4–5 个。**结论：composable 层健康，问题在「该用它的地方没用」（§3.5、§4.4）而非「造了没人用」。**

### 2.5 依赖面与工具链

- 运行时依赖仅 6 个（vue/router/pinia 系 + @lucide/vue + 极少工具件）——对 39 个 domain 的系统而言极其克制，无高亮/图表/日期库等重型依赖（图表是手写 SVG，M12-F02 先例）。
- `as any`×1、裸 `any`×3、`@ts-ignore`×0（424 文件）——类型纪律全库一流。
- Vue 3.5+ `useId()` 已在 4+ 处正确使用（AuthField/AuthCheckbox/StatusTransitionMenu/ConversationComposer），但 3 处仍用 `Math.random()` 生成 DOM id（BasePopover/BaseTooltip/PrincipalPicker:43）——SSR 不安全且同页可碰撞，统一换 `useId()`。

---

## 3. 复用与重复（本次最大发现）

### 3.1 模态外壳：25 份手写焦点陷阱 vs BaseDrawer 0 消费者【全仓最高优先级】

`grep "event.key !== 'Tab'"` 实测 **25 处/24 文件**（account/AccountWorkspace.vue 内部自己复制了自己两份：190 与 247 附近）。逐份手写了 backdrop + keydown 焦点陷阱 + 关闭钮 + footer 按钮区 + `*-backdrop` 样式：

- **大 dialog**：AgentCreateDialog:101、SkillCreateDialog:95、KnowledgeCreateDialog:97、SkillDistillationDialog:108、KnowledgeDistillationDialog:120、ModelCredentialDialog:149、RepositoryBuildDialog:102、DelegateToAgentDialog:247、ConversationCreateDialog:56、WorkItemCreateDialog:101
- **大 drawer**：TaskDetailDrawer:208、WorkItemDetailDrawer:285（两者连 body overflow 锁都各写一份：190-200 ≡ 267-277）
- **二线确认框**：OperationsWorkspace:180、TaskControlPanel:127、OrganizationRoleGrants:123、ModelConnectionDetail:78、SkillActionConfirmDialog:69、LarkNotificationAdmin:201、ReviewWorkbench:237、ActionDeliveryWorkbench:187
- **team**：MemberLifecycleDialog:54、ResponsibilityHandoverDialog:69、TeamInvitationManager:147
- **唯一正确示范**：WorkProjectCreateDialog.vue 全文件 53 行——BaseDialog 一行完成外壳（:32），业务只写表单体

**漂移已造成行为分叉（复制的直接代价）**：

1. 焦点可聚焦集查询至少 4 种变体——只查 `button:not(:disabled), select`（MemberLifecycleDialog:55）/ 加 input+select（ModelCredentialDialog:150-152）/ 再加 `[tabindex]:not([tabindex="-1"])` 过滤与标题特判（LarkNotificationAdmin:202-208）/ 完整版；
2. **Esc 层叠穿透**：11 个文件带 `isTopmostModal` 层叠判断（ModelCredentialDialog:142 等），而 MemberLifecycleDialog:48-64 与 ResponsibilityHandoverDialog:63-79 没有——两层弹窗叠开时内层 Esc 会穿透关闭外层；
3. 手写版 160–260 行/个，合计约 **2500 行可被 BaseDialog/BaseDrawer 直接吸收的脚手架**；`.dialog-backdrop` 样式 10 处/8 文件、`.dialog-icon` 8 处、关闭钮 34 处全部随迁消失。

**修法**：以 WorkProjectCreateDialog 为模板迁移 24 个文件（抽屉侧给 BaseDrawer 补 width prop）；把 isTopmostModal 判断下沉进 useFocusTrap（composable 当前 5 个消费者，一次修复全部层叠语义）。按「先修 isTopmost 下沉 → 高频 dialog → 二线确认框走 useConfirm」顺序分批，每批跑全量 e2e + 46 视觉基线。

### 3.2 双胞胎/多胞胎组件：6 组

| 组 | 成员 | 已漂移的证据 |
|---|---|---|
| 列表三胞胎 | KnowledgeEntryList(172)/SkillList(144)/KnowledgeIndexJobList(139) | 状态梯 computed 逐字相同（49-54 ≡ 37-42 ≡ 46-52：initialLoading/hasItems/forbidden/hardError/offline）；行按钮 CSS 同构 |
| dialog 四胞胎 | SkillCreateDialog(194)/KnowledgeCreateDialog(214)/SkillDistillationDialog(242)/KnowledgeDistillationDialog(260) | handleKeydown 逐行相同（87-109/89-111/100-122/112-130）；header/icon/footer CSS 逐字相同 |
| 抽屉对 | TaskDetailDrawer(585)/WorkItemDetailDrawer(581) | Esc+Tab 陷阱 202-221 ≡ 279-298；body 锁、backdrop 各一份 |
| 版本历史对 | KnowledgeVersionHistory/SkillVersionHistory | `.version-history__*` 样式逐行相同（102-121 vs 118-135）；**Skill 侧有回滚按钮而 Knowledge 侧没有**——分叉已产生功能差 |
| 详情页对 | KnowledgeEntryDetail/SkillDetail | props 十项同名同序（17-29 vs 17-31）；loadServerDraft 逐字相同 |
| 草稿编辑器对 | KnowledgeDraftEditor/SkillDraftEditor | useDirtyForm 配置（含四条中文文案）逐字相同（48-54 vs 39-45） |

**修法**：抽 `BaseListShell`（状态梯+行骨架+整行选择按钮）、`BaseVersionHistory<T>`、`BaseDetailPage<T>`、`BaseDraftEditor<T>`——泛型化 resource/command props。双胞胎消解后，Skill↔Knowledge 两域加第三域（如未来的 Agent 技能市场）时只需注入 labels/store。

### 3.3 下拉菜单：6 套手写 vs 键盘完整的 BaseDropdown 死代码

- 质量谱系：**唯一合格**：action/ActionMenu.vue:74-85（完整方向键+Home/End+aria-disabled+焦点归还）；**部分实现**：ScopeSwitcher:46-61（document click+Esc）、SavedViewsControl:63-89、StatusTransitionMenu；**不合格**：MemberActionMenu:90-120（role="menu" 无方向键）、UserAccountMenu:24-38（无外点关闭、无方向键）、ModelOptionPicker:26-44（无 aria-haspopup、弹出层无 listbox/option 语义、无外点、无方向键）。
- **修法**：以 ActionMenu 合约为蓝本复活 BaseDropdown（或抽 BaseMenu），6 处收敛；ModelOptionPicker 优先补语义。

### 3.4 页面层重复（29 页全读）

1. **查询参数工具 ≥7 份（页面层最严重的重复）**：normalizeQuery/oneOf/uuidQuery/revisionQuery/queryValue 六件套在 SkillsPage:372-402 与 KnowledgeBasePage:327-357 **逐行相同（含同一条 UUID 正则）**；InboxPage:249-270、KnowledgeIndexPage:189-205、AuditPage:195-207、LarkSettingsPage:256-264 各持有子集；ActivityPage:129、ObservabilityPage:132-134、SearchPage:130 各有 queryValue 变体。**已漂移**：queryValue 返回 `''` 还是 `null` 两派并存。修法：抽 `src/utils/queryParams.ts` 全站替换。
2. **SkillsPage ↔ KnowledgeBasePage 整页结构性双胞胎**：除工具函数外，attemptKeyFor（255-261 vs 222-228）、runCommand（304-330 vs 259-285）、refreshFacts（332-339 vs 287-294）、idleVersionPage 全部同构。且已分叉：Skills 有统一 SkillActionConfirmDialog（264-302），KnowledgeBase 手搓删除框（418-429，见 §4.2）。修法：抽 `useEntryLibraryPage` 泛型 composable。
3. **内联设置编辑生命周期 ×2**：QuotaSettingsPage:41-118 ≡ OrganizationSettingsPage:47-132（createFormCommandKeys+attemptKey+startEditing/cancelEditing/save+savingReason+markClean 时序逐行对应）。修法：抽 `useInlineSettingEditor`——第三个设置项出现前抽，成本最低。
4. **15s 自动刷新+visibilitychange ×2**：KnowledgeIndexPage:169-187 ≡ OperationsPage:76-94（startTimer/stopTimer/onVisibilityChange/离线不轮询注释全对应）。修法：抽 `useAutoRefresh(callback, 15_000)`。
5. **tab 导航 4 种实现**：TeamMembersPage:268-283 与 OrganizationSettingsPage:142-164 同构（RouterLink+active class+aria-current+replace），ModelSettingsPage:385-387 用 role=tablist 第三种，LarkSettingsPage:239 用 patchQuery 第四种。修法：抽 `QueryTabs.vue`。
6. **幂等命令 attemptKey ×5**：Inbox:38、Skills:255、KnowledgeBase:222、Quota:110、Organization:120。与 3 合并抽 `useIdempotentCommand`。
7. **auth 页 timedRequest ×2**：LoginPage:158-177 ≡ RegisterPage:233-252（AbortController+operationGeneration+超时异常）。OnboardingPage:35 只有 disposed 标志、连请求取消都没有。修法：抽 `useAuthTimedRequest`。
8. **月份导航按钮 CSS ×2**：ObservabilityPage:271-274 ≡ QuotaSettingsPage:357-360（.month-nav 四条逐行复制）。
9. **CSV 导出内联**：AuditPage:155-177（downloadCsv/csvCell/BOM/文件名 23 行）——移 `src/utils/csvExport.ts`。
10. **保存视图入口解析 ×2**：TodayPage:124-152 ≡ WorkPage:200-238（URL 入口 vs 保存视图同一解析模式）。

### 3.5 domain 层重复（gateway/store 工具）

1. **gateway 私有工具四件套跨域复制**：`segment`（分页切片）约 20 份、`pick`（字段挑选）约 8 份、`etagVersion`（If-Match 版本号）5 份、`requireStrongEtag` 5 份——且已漂移（各域实现细节不一）。修法：抽 `domains/shared/gatewayUtils.ts`（或 `api/gatewayKit.ts`）。
2. **错误/中断三件套三域逐字重复**：`teamops/errors.ts` 与 `task`、`coding` 域 storeUtils 里的 isAbort/statusOf/presentError 逐字相同。修法：随 gateway 工具一并下沉。
3. **约 28 个 store 手写 AbortController+epoch 样板**：api/requestScope.ts 的 `createRequestScope`（32 行，纯函数）就是为此而生，composables/usePageRequestScope.ts 已覆盖页面侧；store 侧无对应薄封装。修法：store 内部 load 全部改走 `createRequestScope`，每 store 净删 30–50 行。
4. **DTO 边界防护两套范式**：task 域 gateway 用 `pickRecord+publicScalar` 运行时白名单（全仓唯一），其余 38 域用盲断言映射（`as` 直转）。白名单是更强的契约防护，但推广前先抽公共工具，避免第 39 份手写。

---

## 4. 交互与可访问性

### 4.1 控件契约分裂（数字全为实测）

| 控件 | 原生/手写 | 基础件 | 说明 |
|---|---:|---:|---|
| button | 202 处 | BaseButton 412 处 | 覆盖率 67%；裸钮反复形态：①图标+文字小操作钮（BaseButton 已有 #icon slot 可直接换，CodingDiffExplorer:568 等）②**分段/开关钮 aria-pressed 15 处/10 文件**（ConversationComposer:93 编辑/预览、CodingDiffExplorer:619 Split/Unified 等）——最值得沉淀 BaseSegmented ③下拉触发器 7 处（随 BaseMenu 收敛）④role=alert 内的内联重试/继续加载文字钮（值得 BaseInlineAction）⑤列表整行选择钮（随 BaseListShell 消化）⑥关闭钮 34 处（BaseDialog 化后自然消失） |
| select | 27 文件 | 8 文件 | RepositoryBuildDialog:12 证明 BaseSelect 可承载级联场景；TaskListPanel:72-73（原生 select+on* props）与同目录 SkillList（BaseSelect+emits）是同层两种范式 |
| table | 5 处 | 5 处 | AuditExplorer:232-242 原生 table 无 caption——BaseTable 有 caption prop 可直接解决 |
| checkbox/radio | 若干 | BaseCheckbox/BaseSwitch 有 | InboxWorkspace:330、OrganizationExportWorkflow:100、OrganizationRoleGrants:159-161 |

同构 props 零公共类型：online×30、errorMessage×31、submitting×12、disabled×17、loading×7——`base/types.ts` 全部导出仅 1 个类型（SemanticTone）。修法：增加 DisabledProps/LoadingProps/OnlineProps/ErrorMessageProps 交叉复用。on* 回调 props 约 120 处（TaskDetailDrawer 约 40 个）与 typed emits 并存——渐进迁移，真正的查询函数 props（itemsFor/isSelected）保留但去掉 on 前缀以示区分。

### 4.2 键盘合约缺口（按影响面排序）

1. **feedback/ConfirmHost.vue:6**：中心确认框 role="alertdialog" 却**无焦点陷阱、无 Esc、无初始焦点**——所有 useConfirm 消费者（5 个）拿到的确认框键盘不闭环。这是最讽刺的一条：中心化设施本身的质量低于它想替代的手写版。修法：接 useFocusTrap + Esc（半天工作量，收益面全部确认流）。
2. **base/BaseTabs.vue**：role=tablist/tab 无 aria-controls、无方向键——两个真实用户（KnowledgeEntryDetail/SkillDetail）的 tab 只能 Tab 硬移。对照：action/ActionMenu:74-85 有完整 roving tabindex 实现可抄。
3. **ModelSettingsPage.vue:385-387**：owner-tabs 声明 role=tablist 但全文件 keydown 计数 0——ARIA 语义对屏幕阅读器承诺了键盘模式却无法兑现，**比不用 role 更糟**。修法：补 roving tabindex，或降级为 aria-pressed 按钮组（ObservabilityPage month-nav 的做法）。
4. **MemberActionMenu:135 / UserAccountMenu:48**：role="menu" 无方向键；ModelOptionPicker 弹出层无任何 role。
5. **KnowledgeBasePage:418-429 手搓删除确认框**：自管 backdrop/alertdialog/手动焦点，未走 useConfirm/BaseDialog——双胞胎页面已分叉（Skills 侧走统一 ConfirmDialog）。

### 4.3 Esc 层叠穿透（2 文件，随 §3.1 一并修复）

team/MemberLifecycleDialog:48-64、team/ResponsibilityHandoverDialog:63-79 的陷阱无 isTopmostModal 判断且不 stopPropagation——两层弹窗叠开时内层 Esc 关闭外层。domain 侧 11 个文件已有成熟模式，下沉进 useFocusTrap 后全站统一。

### 4.4 提示层：title= 376 处 vs BaseTooltip 6 个消费者

全 src `title="` 实测 376 处（M8 时 330 处，绝对数还在涨——新页面仍在用裸 title）。BaseTooltip 生产消费者 6 个（WorkItemsToolbar/WorkItemsBulkBar/TaskDetailDrawer/TaskOutcomeBlock/ScopeSwitcher/RelativeTime）。裸 title 的问题：无延迟、不可样式、触屏不可达、键盘焦点不触发。修法：不必全量替换——高频操作区（工具栏/批量条/看板卡）优先，376 处按页面分批；check 门禁可仿 check-empty-states 加「新增 title= 需豁免登记」绊线防增量。

### 4.5 三态与竞态缺口

1. **ObservabilityPage.vue:241-243**：质量统计节只有 loading/error 两分支，`v-else-if="quality.value"` 意味着 ready 且空时整节不渲染——同页 months(152)/detail(210) 都有空分支，此处缺失。
2. **SearchPage.vue:82-92**：对象类型筛选连点无去抖、不同 query 的旧请求不 abort（防重只有 loadedQueryKey 拦相同 query），快速勾选 4 个类型真实发出 4 次请求。修法：watch 内 150-300ms debounce 或 store 层 abort 前序。
3. **GitHubSettingsPage.vue:359-365**：导入轮询固定 1200ms、无退避、不感知页面可见性（对比 KnowledgeIndexPage:177-187 有 visibilitychange 暂停）。修法：并入 useAutoRefresh + 退避上限。
4. **QuotaSettingsPage:141-152 / OrganizationSettingsPage:89-96**：watch 触发即 load，无 capture/isCurrent 守卫（org 级 id 来自 principal，实际风险低，但统一模式成本低）。
5. **GitHubSettingsPage:124-151 / LarkSettingsPage:53-79 手搓 createRequestScope**：手工序列化 identityKey+自建 2-3 个 scope+手动 invalidate/dispose+flush:'sync' watch——完整复刻 usePageRequestScope 内部实现却拿不到其 A→B→A 续接守卫；composable 日后修缺陷这两页不受益。修法：改用 usePageRequestScope（GitHub 页迁移后预计净删 150+ 行）。
6. **AgentSettingsPage.vue:446-454**：加载更多手搓 `<button class="load-more">` 非 BaseButton，pending 仅靠文案；ConversationPage:1066-1074 已示范 `<BaseButton :loading>`。
7. **正面样例（对齐用）**：SearchPage:159-166 失败保留旧结果的 stale-while-retry；LoginPage:153-156 错误焦点；WorkPage:1126-1142/1280-1312 抽屉关闭焦点恢复；TodayPage:317-320 看板键盘操作；双击防护普遍到位。

### 4.6 深链与 URL 状态

1. **QuotaSettingsPage:124,158-163**：非法/未来月份深链静默回退 anchor 月但 URL 不回写——用户复制地址栏分享出去的不是他正在看的月份。对照正例 ObservabilityPage:74-86 会回写并注释了保留深链的理由。修法：无效 month 时 router.replace 删除该键。
2. **LarkSettingsPage:274**：`:key="pageCoordinate()"` 整体重挂 LarkNotificationAdmin——身份一变即销毁重建，未提交的筛选输入/滚动/焦点全丢。修法：靠 store.activateScope+capture 清数据，去掉 :key。
3. **ActivityPage:85-93**：排序方向 query→state 与 state→query 双向 watch，靠一处比较防自激回路——单向数据流被打破，后续加排序键极易漏防。修法：只留 query→state，用户操作直接改 query。
4. 其余深链实现一致性良好：AgentSettings/ModelSettings 经 domains/settings/route、RepositorySettings/TeamMembers 用 useRouteFocus、AuditPage 深链兜底（97-102）均为正例。

---

## 5. 样式与设计系统

### 5.1 token 体系：A 档，圆角阶梯已冻结

色彩/字号/间距/密度/圆角（8/12/16/pill）/阴影/动效（--cs-motion-fast 120ms、base 180ms、--cs-ease-out）全部 token 化，design-tokens 门禁禁字面量。`prefers-reduced-motion` 在 base.css 全局归零。样式行数占源码约 9%，健康。

### 5.2 utility 类未沉淀（重复选择器 Top 实测）

`.error` 17 处/14 文件、`.active` 14/11、`.dialog-backdrop` 10/8（随 §3.1 消失）、`.wide` 9/5、`.mono` 8/7（等宽短 ID 展示——最典型的该全局化候选）、`.dialog-icon` 8/8（42px 圆角图标块）、**`@keyframes spin` 定义了 4 份**（BaseButton:57、StatePanel:73 等）、`.field-error` 5/4、`.selection-actions` 5/2、`.section-heading` 5/5；`.eyebrow` 出现在 33+ 文件。修法：`.mono/.spin/.dialog-icon/.eyebrow/.section-heading` 沉淀为 design 层全局 utility 类；keyframes spin 只留一份。

### 5.3 死样式

**WorkPage.vue:2077-2079 整段看板死样式**：模板（1725-2074）实测零引用的类共 6 个——`.work-board`/`.board-column`/`.drop-target`/`.drop-rejected`/`.work-content`/`.work-list`（看板已由 WorkItemsWorkspace 子组件渲染，scoped 样式本就无法作用其内部）；767px 媒体查询同样失效。仍在用的 `.work-pins/.load-more/.scope-rule` 保留。修法：删除 6 个死类规则，误导后续维护者以为看板仍在本页。

### 5.4 微交互包（已保留，`f45db0c`）

评审期间实现并验证的微交互包已提交：BaseButton 按压回弹（:active scale .97+transform transition，radius sm→md）、BaseDialog 面板 .96 起身进出场、BaseDrawer 按停靠侧滑入滑出；时长/曲线全部走冻结的 motion token，`prefers-reduced-motion` 经 base.css 全局归零。验证：25 对四断点截图像素 diff（每张 148–1169 px 变更）、4 段录屏、DOM 实测 matrix(0.97)、design-tokens 门禁 PASS。

**基线盲区实测（评审中新发现）**：e2e matrix 以 `contextOptions.reducedMotion: 'reduce'` 运行且 `maxDiffPixelRatio: 0.02`（1440×960 下约 2.7 万像素容忍度）——静态圆角变化（148–1169 px/张）也远低于阈值，663 条 e2e 全过、158 个存量快照零重写、**无需也不应重生成基线**。这说明盲区比「动画被冻结」更宽：微交互级改动（含静态微调）整体落在视觉基线敏感度之下，守护只能靠 DOM 级断言（如 `getComputedStyle` 的 transform）或录屏——建议为按压回弹/抽屉滑入各补一条 DOM 级 e2e 断言入栅栏（已列入 §8 P5）。

**结构性注记**：全站 Transition 仅 4 处且全在基础件/反馈层（ToastHost/CommandPalette/BaseDialog/BaseDrawer），页面级零动效——这是「不灵动」感受的结构性原因；§3.1 的基础件迁移会把焦点层的动效一致性顺带给到 24 个手写外壳。

---

## 6. 页面层专项（29 个 .vue 全读）

### 6.1 巨型组件

1. **WorkPage.vue 2080 行**（script 1-1723，协调 12+ 个 store：work/task/coding/review/delivery/injection/teamOps/graph/link/agent/collaboration/scope）。可拆 composable：`useCodingStudioSync`（1328-1374）、`useReviewWorkbenchSync`（1376-1407）、`useDeliveryWorkbenchSync`（1409-1432）、`useWorkBulkOperations`（956-1001）、`useWorkResourceSelection`（331-467，含聚合约 20 个错误状态的 taskForbidden 443-467）；委派逻辑 1630-1704 移 domain 层。
2. **WorkPage 模板向两个抽屉透传约 40/60 个 props**（WorkItemDetailDrawer 1907-1983、TaskDetailDrawer 1985-2072）——页面成了纯转发层，新增一个任务字段要改 store→页面→抽屉三层。修法：抽屉直接订阅对应 store（页面只传 id 与 open），或 provide/inject 传入 selection 上下文。
3. **ConversationPage.vue 1453 行**：草稿 Map+f05Storage（93-95/199-220）与四组焦点追踪（130-133/437-565）各约 100 行内联——抽 `useConversationDrafts` 与 `useConversationFocus`；isForbidden 聚合 9 个 store 错误（880-890）改为各 store 暴露 forbidden 计算属性。
4. **GitHubSettingsPage.vue 661 行**：15+ 个手工 loading/pending/error ref（65-88）+3 个手搓 scope（127-130）——迁移 usePageRequestScope+store 化导入状态后预计净缩 150+ 行（见 §4.5.5）。

### 6.2 草稿机制三套并存

useDirtyForm 的 draftScope（13 个消费者，F05 能力）／ConversationPage 手工草稿 Map+f05Storage（93-95/199-220，自动落盘但无离开提示）／RepositorySettingsPage 手工 readRepoBindingDraft（121-135）。自动落盘设计本身可保留，但应收敛到同一机制，避免第四套出现。

### 6.3 正面样例（后续对齐的内部基准）

StatePanel 体系全覆盖、SearchPage stale-while-retry、SetupPage 状态机卡片（M8 起就是配置体验范本）、ObservabilityPage 深链保留策略注释（81-83）、InvitePage/AccountPage/ActivityPage 对 capture/captureSelection/captureIdentity 的正确分用。

---

## 7. 测试与 e2e 基建

1. **mock 基建锁死单文件**：app-shell.spec.ts 5188 行承载全部 mock 编排——本次审查自身就被其 4173 端口与 fixture 连锁咬过（M12-F01/F03 的教训已在案）。修法：按域拆 fixture 模块（m12-f03 已证明 session 契约加字段会连锁 16 个 fixture，拆分后连锁半径收窄）。
2. **视觉基线与动效**：46 处 toHaveScreenshot 是回归安全网，但 animations:'disabled' 使动效成为基线盲区（§5.4）。
3. **门禁矩阵**：check-empty-states（带豁免清单）/check-disabled-reasons/check-dialog-focus/check-design-tokens/check-openapi-drift/check-enum-labels/check-sensitive/check-bundle——覆盖面在同类项目中罕见。缺口：**前端分层无门禁**（check-module-boundaries 只管 Java）——§2.1 的循环依赖靠本次人工脚本才暴露。修法：把 dep-audit 的规则（api→domains 禁止、循环检测）固化为 check-front-boundaries.mjs 入 CI。

---

## 8. 修复路线图（按性价比分批）

| 批次 | 内容 | 预期收益 |
|---|---|---|
| **P0 立刻**（半天–1 天/项） | ①ConfirmHost 接 useFocusTrap+Esc；②BaseTabs 补 roving tabindex+aria-controls；③ModelSettingsPage tablist 补键盘或降级 aria-pressed；④MemberLifecycle/ResponsibilityHandover 的 Esc 穿透（先打补丁，§3.1 下沉后自然吸收）；⑤Observability 质量节补空态；⑥Quota 非法月份深链回写；⑦删除 4 个真死代码+WorkPage 死样式+6 死基础件（BaseDrawer/BaseDropdown 除外——它们是 P1 的弹药） | 消灭全部已知键盘/层叠缺陷与死代码，零迁移风险 |
| **P1 第一批**（1–2 周） | ①isTopmostModal 下沉 useFocusTrap；②24 文件迁移 BaseDialog/BaseDrawer（WorkProjectCreateDialog 为模板，抽屉加 width prop） | 净删约 2500 行；34 关闭钮/10 份 backdrop 样式/8 份 dialog-icon 随迁消失；层叠语义全站统一 |
| **P2 第二批** | ①复活 BaseDropdown/BaseMenu 收敛 6 套下拉；②select/table 迁移 BaseSelect/BaseTable；③抽 queryParams.ts 替换 ≥7 份；④useAutoRefresh 吸收 2 份轮询+GitHub 退避 | 同一交互 6 种键盘行为→1 种；查询工具漂移止住 |
| **P3 第三批** | ①抽 BaseListShell/BaseVersionHistory/BaseDetailPage/BaseDraftEditor 消解 6 组双胞胎；②useEntryLibraryPage 合并 Skills/KB 两页；③useInlineSettingEditor/useIdempotentCommand；④on* props 渐进迁 emits；⑤base/types.ts 公共 props 类型 | 双胞胎功能分叉止住；第三域接入成本减半 |
| **P4 第四批** | ①store AbortController×28 改走 createRequestScope；②gateway 工具（segment/pick/etag）下沉共享；③isAbort/statusOf/presentError 三域合一；④GitHub/Lark 手搓 scope 迁 usePageRequestScope；⑤WorkPage/ConversationPage 拆 composable；⑥抽屉改订阅 store 去 40/60 props 透传 | store 层净删约 1000 行；竞态语义统一到一处 |
| **P5 随手** | Math.random→useId（3 处）；keyframes spin 收敛；.mono/.eyebrow utility 化；SearchPage 去抖；AgentSettings load-more 换 BaseButton；LarkSettings 去 :key 重挂；ActivityPage 单向化；微交互 DOM 级 e2e 断言（§5.4 盲区守护）；check-front-boundaries 门禁固化 | 零散但每项 <半天 |

**总量估计**：P1–P4 合计可净删约 4000–5000 行重复/样板，且 P0+P1 完成后「同一交互多种行为」类缺陷在全仓绝迹。每批完成后跑全量 e2e（663）+ 46 视觉基线 + check:quality 全家桶；P1 涉及视觉面需重生成基线。

---

## 9. 评审证据与覆盖声明

- **覆盖**：`crewscope-web/src` 424 个非测试源文件 100% 进入审查范围——依赖图脚本全量建模（可达性/孤儿/SCC/跨层/composables 使用数）；29 个页面 .vue 全文通读；components 12 个子目录约 200 文件六维模式全扫（button/select/table/keydown/backdrop/role/props/emits）+ 大文件（>200 行的 21 个 domain 文件）精读；composables/api/design 全读；domains 抽读 task/skill/teamops/scope 四域全量 gateway/store/errors。
- **口径与复核**：两份子报告（pages/components）的全部高严重度断言经主评审逐条 grep/wc 抽查复核，本文数字以复核后为准——手写陷阱 25 处/24 文件（精确）、BaseDialog 消费者 1（WorkProjectCreateDialog:6，带 .vue 后缀 import）、BaseDrawer 0、死基础件 6、裸 button 202 vs BaseButton 412（components+pages+App.vue 行级 grep）、`title="` 376、Transition 4、BaseSkeleton 生产 1 页、AuthCheckbox 生产 0。WorkPage 死样式经模板区逐类比对修正为 **6 个死类**（子报告记 4 个——.work-content/.work-list 同样零引用）。
- **非债注记**：21 story+2 预览+5 spikes 属 Histoire/dev 工具（不入产物包）；openapi.ts 是 check-openapi-drift 金丝雀；check-empty-states 豁免清单是门禁设计的一部分。
- **评审脚本**：依赖图审计脚本（Tarjan SCC+可达性）存于 job tmp（dep-audit.mjs），P5 的 check-front-boundaries 门禁可直接以其为母本。

---

## 10. 第二轮增补：交互与样式设计走查（灵动视角，2026-10-17）

> 以交互/视觉设计视角（十年 B 端工作台设计经验）复审。素材：158 张 e2e 基线截图中选 24 张代表页——3 张单页走查 + 6 个 2×2 拼接网格（其中 1 网格图像工具解析失败弃用），实得 **23 个页面状态**的视觉走查；叠加灵动度量化审计（grep 口径同 §9，components+pages 的 .vue）与既有代码层发现。**所有视觉结论逐条回源码裁定，误报剔除见 §10.3.5。**

### 10.1 设计记分卡（第二轮专项）

| 维度 | 评 | 一句话依据 |
|---|---|---|
| 视觉层级 | B+ | 页面骨架清晰（eyebrow→H2→meta 的三段式全站贯彻），但工作台族普遍「区块等权、主操作不突出」 |
| 排版与密度 | B | 知识族接近 Notion 品质；设置页过松（单屏密度低）、监控族偏紧，两端摆动 |
| 色彩克制度 | B+ | 色板克制、语义色成系统；但橙黄系语义过载（评审中/等待中共用琥珀域） |
| 组件形态一致性 | B- | base 层同构良好；域层 6 套下拉/4 种模态行为（§3/§4 已入档）在视觉上同样可见 |
| 状态与反馈设计 | C | 加载态多为纯文字、骨架屏全站仅 1 页、空态质量参差（Observability 质量节无空态，§6） |
| 微交互与灵动 | D+ | hover 盲区 54 文件、按压反馈几乎为零、动效词汇表基本未启用（§10.2） |
| 暗色主题适配 | B- | 结构一致，但 diff 暗色格文字对比度可读性一般 |
| 页面家族气质 | C+ | 设置页（白卡细边、疏朗）与工作台（深色区头、高密度）是两套气质，缺一个统一的「CrewScope 感」 |
| 文案与提示 | B | 中文文案克制专业；`title=` 376 处原生提示是体验下限（§4 已入档） |

**总评**：这是一个「静态有品质、动态近乎沉默」的界面。token 体系、色板克制度、信息架构都是好设计的底子；缺的是**对用户存在的回应**——鼠标悬停没有回声、按下没有手感、内容变化没有动线。灵动感不是加装饰，而是把已有的静态品质翻译成时间维度上的确认感。

### 10.2 灵动度量化基线：三个结构性缺口

| 指标 | 数量 | 口径 |
|---|---|---|
| `cursor: pointer` | 154 处 | 可点元素总量级 |
| `:hover` | 76 处 | 悬停反馈规则 |
| `:active` | 5 处 | 按压反馈（含微交互包 `f45db0c` 的 BaseButton） |
| `transition:` | 29 处 / 22 文件 | 过渡声明 |
| `@keyframes` / `animation:` | 18 处 / 9 文件 | 关键帧动画 |
| `:focus-visible` | 45 处 / 30 文件 | 键盘焦点样式 |

- **缺口一：hover 盲区（最重）**——154 处 `cursor:pointer` 对 76 处 `:hover`，**54 个文件声明了可点却无任何悬停反馈**，其中 8 个在 base 层：BaseTabs / BaseSwitch / BaseCheckbox / BaseRadio / BaseTag / BaseDialog / BaseDrawer / AuthCheckbox。这不是零散遗漏而是系统性缺口：连视图切换器（WorkItemsToolbar 的 List/Board/Graph）都只有 active 态无 hover 态。用户在屏幕上移动鼠标时，界面一半的「可点」不回应。
- **缺口二：按压反馈几乎为零**——`:active` 全站 5 处。除微交互包的 BaseButton 外，其余按钮按下时视觉上纹丝不动（裸 button 202 处，§9）。
- **缺口三：动效词汇表已建成、未启用**——tokens.css 备好了完整时长阶（`--cs-motion-fast: 120ms` / `base: 180ms` / `slow: 260ms`，tokens.css:243-245）且 `prefers-reduced-motion` 下全部归零（tokens.css:386-388），但全站仅 22 文件用到 transition、9 文件有关键帧；`Transition` 组件 4 处全在 base 层。**TypingIndicator 是全站唯一「活着」的组件**。动效合规成本为零（走 token 自动获得 reduced-motion 适配），低密度纯粹因为没人用。
- 缺 `--cs-ease-in`（离开曲线）：token 表只有 `ease-out`（入场）与 `ease-in-out`；离开若复用 ease-out 会拖泥带水——建议补 `cubic-bezier(.4, 0, 1, 1)`。
- 与 §5.4 互为闭环：动效缺位不会被视觉基线抓到（`maxDiffPixelRatio: 0.02` 盲区），**基线测不出「死」，正如测不出「活」**——灵动性守护只能靠 DOM 级断言（§8 P5 已列）。

### 10.3 视觉走查发现（按主题，标注证据页）

**10.3.1 层级与主操作**（工作台族普遍问题）
1. 评审工作台（review-workbench）：**Approve 与 Reject 视觉权重相当**——一个是推进主操作、一个是终止操作，等权摆放让正确路径不显眼。建议 Approve 实心主色、Reject 降为 secondary/ghost（危险语义保留在 hover 态）。
2. 执行工作台（execution-studio）：**状态双轨**——「DURABLE CONTROL 执行控制」区与中部「增量 Attempt 2 · 执行中」重复表达同一状态，用户需要自行对齐两处。建议合并为单一权威状态区，控制区只放操作不放状态复述。
3. 工作详情（work-detail）：元信息网格过于均匀（等距等权的三列卡片），缺主次；右上操作按钮组权重相同，无主 CTA。
4. 模型设置（model-settings）：分组标题字号差异过小，扫读依赖分隔线而非字号——层级应该先于线条被读到。

**10.3.2 密度与排版**
1. 设置族表单间距偏松，单屏信息密度低，长表单滚动成本高（model-settings / agent-configuration）。
2. 会话页（conversation）：消息气泡行宽约 400px 偏窄，且右对齐气泡贴容器边缘；时间戳/序号建议统一弱化（12px、次级灰）。
3. 依赖图（work-graph）：节点集中在画布左侧 1/3，右侧大面积留白，构图失衡——初始视口应居中或自动 fit。
4. 筛选区三个 select 顶部对齐标签 12px（work-toolbar），标签与控件的视觉绑定偏弱。
5. 交付工作台（action-delivery）右侧步骤导航 12px 偏小，建议 14px + 编号。

**10.3.3 一致性**
1. **设置页与工作台两套气质**：设置页白卡+细边+疏朗，工作台深色区头+高密度。治理入口（/settings/organization、/settings/quota）是 M12 新面，趁 P1 收敛时统一「区头 + 卡片」的语言。
2. 会话页用户/Agent 仅靠色彩区分（绿/白气泡）——色觉障碍下无冗余线索，建议补头像或形状标记（与 §4 可访问性发现同源）。
3. 暗色 diff（diff-highlight-dark）：文字对比度可读性一般，语义色在暗色域的亮度阶需要单独校准。

**10.3.4 状态与反馈设计**
1. 工作列表（work-list）：成功/失败状态色对比不醒目，语义色在浅底上饱和度不足。
2. 骨架屏全站仅 1 页使用（§9）——加载态大多是「加载中…」文字。首屏与列表滚动是骨架屏的两个天然位置。
3. 看板卡片徽章与正文断点不明（徽章字号与正文差太小），建议徽章降为 11px+大写字距（work-board）。

**10.3.5 误报剔除（走查纪律的例证）**
- 「+ 新建工作项按钮被状态筛选下拉遮挡/裁切」——**两次独立视觉走查报告，源码排除**：`.create-work-item { flex: 0 0 auto }`（WorkItemsToolbar.vue:130）不收缩，工具栏 grid 三列在 1440px 下无挤压，1050px 以下有专门折叠降级（:138）。AI 视觉走查把「按钮紧邻筛选区」误读为裁切——**本节所有发现均以此纪律处理**：视觉报告只立假设，源码与计算样式定谳。

### 10.4 灵动路线图（动效设计规范）

原则：**全部走既有 token**（时长阶 120/180/260ms + ease-out；reduced-motion 自动归零），不新造词汇；每处动效回答一个问题——「用户刚做了什么，界面如何确认」。

**第一档：即时反馈层（低风险高回报，可与 P0 同批）**

| 位置 | 动效 | 参数 |
|---|---|---|
| base 层 8 控件 hover（§10.2 缺口一名单） | 背景色/边框色过渡 | `background-color var(--cs-motion-fast) var(--cs-ease-out)` |
| 视图切换器 List/Board/Graph（WorkItemsToolbar） | hover 补 `--cs-surface-accent` 背景 | 同上 |
| 列表行（work-list / knowledge / skills / activity） | hover 背景条 + 左缘 2px 主色指示 | 120ms；指示条用 scaleY 展开 |
| 看板卡片、知识卡片 | hover 升起：`translateY(-1px)` + shadow 从 hairline 升到 float | 120ms，禁用期间不累积（transition 只挂 transform/shadow） |
| 全站按钮按压 | BaseButton 的 `scale(.97)` 模式推广到 icon button / 菜单项 | 已有 token（`f45db0c`） |

**第二档：结构层动效（配合 P1/P2 收敛，组件迁移随迁）**
1. 模态落场：BaseDialog 的 `scale(.96) translateY(10px)` 起身已是范本（`f45db0c`）——P1 迁移 24 份手写模态时**动效随组件自动获得**，这是「先收敛后灵动」的复利。
2. 抽屉滑入：BaseDrawer 按停靠侧 translateX 已实现（`f45db0c`）；建议离开加速：补 `--cs-ease-in` token 后离开曲线换 ease-in，体感更利落。
3. 下拉展开：P2 复活 BaseDropdown 时统一 `scale(.97)→1` + opacity，transform-origin 设在锚点侧。
4. 页签切换：BaseTabs 内容区 120ms fade + 4px 上移，配 P0 的 roving tabindex 一起交付。

**第三档：标志性瞬间（差异化体验，1–2 处足矣，勿多）**
1. 依赖图节点拖拽：边跟随 + 松手后节点弹性归位（`--cs-motion-slow` 260ms + ease-out）——工作台族的记忆点。
2. 会话流新消息：120ms fade + 8px 上移入场（TypingIndicator 已有伙伴感，补上到达感）。
3. 执行进度条带：进行中的 attempt 加呼吸态（opacity .85↔1，900ms 循环，同 spin 周期）——「系统在干活」的持续信号。

**守护**：以上全部走 token 即自动获得 reduced-motion 适配（tokens.css:386-388 已全局归零）；第一档完成后的回归面（hover 背景条/卡片升起）超出视觉基线敏感度（§5.4 盲区），须按 §8 P5 补 DOM 级断言（`getComputedStyle` 断言 transition 属性存在），而非依赖截图。

### 10.5 与 §8 路线图的关系

- §10.2 缺口一（54 文件 hover 盲区）并入 **P0/P1**：base 层 8 控件 hover 补齐是半天工作量，收益全站可见；域层随 P1/P2 组件迁移随迁。
- §10.3.1 主操作层级（评审工作台 Approve/Reject 等 4 条）并入 **P1 尾部**：纯样式调整，趁 P1 触碰这些页面时一并做。
- §10.4 第二档与 P1/P2 天然同批（动效随组件收敛自动获得）；第三档独立排期，建议 M13。
- `--cs-ease-in` token 补齐与 keyframes spin 收敛（P5 已列）同批。
