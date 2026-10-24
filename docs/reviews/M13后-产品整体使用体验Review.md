# M13 后：产品整体使用体验 Review

日期：2026-10-20。对象：当前 CrewScope 实现（前端基线 `97d417d`，含 M10 知识闭环、M11 实时协作与 WorkGraph、M12 企业治理、M12b 前端收口、M13 后端结构收敛的全部交付），重点是 M10–M12 三个能力里程碑**从未做过整体体验评审**的产品面。

方法：沿用 [M9 后全流程 Review](M9后-全流程使用体验与竞品对照Review.md) 的取证分级与 R 编号惯例（本轮编号 R01 起自本文，与 M9 后 R01–R43 独立）。四条旅程轴（首用路径 / 核心工作循环 / 知识闭环 / 企业治理与成员）逐页代码走查 + 横切面（搜索/通知/移动端/暗色/错误恢复）自查 + 历史回归抽查；最高优先级发现另以 mock 栈 Playwright 实机复现（`pnpm dev` + mock session，非 Docker 真实栈）。**不是逐行安全/性能审计**（M12 后两轮架构 Review 已覆盖）；本轮零业务实现修改，只产出本文。

计划承接：本文 §8 给出 M14 候选主题聚类；计划冻结待用户裁定。

## 1. 结论

**底座体验已到发行水准，但三条新能力主线（M10/M11/M12）各自存在一个「最后一公里断点」：能力在服务端完整、验收门全绿，而用户在产品内走不完全程，或走到关键一跳时被丢进断头路。**

- **M12 治理线的断点是可达性**：组织设置/配额/角色授予/导出/删除全部存在且契约扎实，但入口藏在配置子壳二层，纯组织角色登录即死胡同（实机复现，R01），三个治理表单要求手输用户在产品内无从获取的 UUID（R06/R07/R08）。
- **M11 实时协作的断点是「在场但不可见」**：看板订阅了 presence 却不消费 resource_changed，他人拖卡自己看不到（R11）；对话回复没有任何收纳面（R12）——「多人在场」呈现与内容事实脱节。
- **M10 知识闭环的断点是证据链断裂**：六开关零呈现、关闭态被文案误读为「进行中」（R04）；蒸馏入口要求手抄一个产品内无处可复制的执行 UUID（R03）；注入/索引/反馈各环的证据散落或缺失（§4.3）。

与 M9 后 Review 的结构性对照：M9 时代的问题是「页面之下缺一整层基础设施」（组件、token、状态机）；那层已经建成（M9–M9b + M12b），本轮正面基线清单（§6）可以证明。本轮的问题集中在**跨页流程的接线与能力可见性**——单页质量普遍 A 档，跨页旅程频繁断线。这不是倒退，是建设重心从地基转到能力后尚未做的「能力接线」收口。

## 2. 问题总表

P1：核心可用性/可达性阻断。P2：重要流程断点或正确性呈现问题。P3：打磨项。证据性质：代码确认 / mock 实机复现 / 需实机验证。

| 编号 | 级 | 问题 | 证据 | 层 | M13b 关闭 |
| --- | --- | --- | --- | --- | --- |
| R01 | P1 | 纯组织角色登录即死胡同：默认落点被拦、侧栏全空、「返回 Today」循环、治理页无任何导航入口 | **mock 实机复现** | 前端 | F01·`1d848df7`；Q01 e2e 实机链路复绿（m12-f01-organization:259） |
| R02 | P1 | TaskIntent 确认后断头路：委托入口藏在默认折叠的「关联 WorkItem」，确认成功无任何引导 | 代码确认 | 前端 | F03·`ba43f845` |
| R03 | P1 | 蒸馏必填执行 UUID 在产品内无合法获取路径：提示语指向不存在的「复制执行 ID」功能 | 代码确认 | 前端 | F03·`ba43f845` |
| R04 | P1 | 知识六开关（vector/index/worker/retrieval/injection/skill）零 UI 呈现，关闭态被「等待后台建立索引」文案误读为进行中 | 代码确认（部分需实机） | 前后端 | A03·`cb5eb34c` + F04·`1320c40c` |
| R05 | P2 | AccessDeniedPage 把组织权限问题说成「团队权限」，org:* 权限名回退为「访问此区域」，指引循环 | 代码确认 | 前端 | F01·`1d848df7` + F12·`23b51844`（表单关联后半） |
| R06 | P2 | 组织角色授予要求手输 Principal UUID，组织内无成员目录（PrincipalPicker 仅 team scope） | 代码确认 | 前后端 | A01·`02b0fca4` + F02·`1c4b39d2` |
| R07 | P2 | 账号删除要求手输目标 accountId UUID，产品任何界面不展示 accountId | 代码确认 | 前后端 | A01 + F02 |
| R08 | P2 | 活跃会话列表无设备/地点/IP，撤销是盲选（前后端契约均只有 4 字段） | 代码确认 | 前后端 | A02·`e11911af` + F02 |
| R09 | P2 | SSO 登录成功后固定回 `/`，丢弃 returnTo 与邀请上下文（密码登录有恢复，SSO 通道没有） | 代码确认 | 前后端 | F07·`6034bd91` |
| R10 | P2 | 邀请 proof 仅存内存，刷新即失效且落到「请联系管理员」终态，无自恢复 | 代码确认 | 前端 | F07 |
| R11 | P2 | 看板订阅 presence 但不消费 resource_changed：他人操作不可见，在场提示与内容事实脱节 | 代码确认 | 前端 | F05·`2790d8e4` |
| R12 | P2 | 对话无未读收纳：列表无未读标记，Inbox 五类不含对话消息，团队回复零收纳面 | 代码确认 | 前后端 | A07·`94299d8a` + F06·`ef26dc09` |
| R13 | P2 | 阻塞徽标聚合失败时静默降级为「未被阻塞」（注释自认的取舍，列表/看板为默认视图） | 代码确认 | 前端 | F05 |
| R14 | P2 | 蒸馏只能从知识/Skill 页手动粘贴 UUID 触发，执行现场无一键蒸馏 | 代码确认 | 前端 | F03 |
| R15 | P2 | 发布后索引徽章无轮询，停在「待索引」直到手动刷新（索引作业页有 15s 自动刷新，条目页没有） | 代码确认 | 前端 | F04·`1320c40c` |
| R16 | P2 | 「可在索引作业面重试」是空头承诺：该面只有全局 rebuild/取消，无按条目重试，提示不可点 | 代码确认 | 前端 | A04·`3177b2b5` + F04 |
| R17 | P2 | 索引作业行显示 entryId/bindingId/commit 截断 UUID，无按条目筛选，用户无法对应自己的发布 | 代码确认 | 前端 | A04 + F04 |
| R18 | P2 | INJECTED 引用中 Skill 行可点回目录、知识条目行是纯文本——闭环最后一跳不对称 | 代码确认 | 前端 | F04 |
| R19 | P2 | 蒸馏溯源（origin）只在一次性回执里可见，之后详情/版本历史均不展示 | 代码确认 | 前端 | F04 |
| R20 | P2 | 检索反馈环只写不读：NOT_APPLICABLE 无聚合面，UI 明示不影响检索，用户无标记动机 | 代码确认 | 前后端 | F04 |
| R21 | P2 | 成本质量页零预算呈现；M10-F03 预算提醒（budget.enabled）无前端面，且与 M12 配额预算两套概念无互相指引 | 代码确认 | 前端 | A06·`faacbf15` + F04 |
| R22 | P2 | 对话面零注入/知识证据呈现（全文件零命中），「知识进入对话」的心智模型无落点 | 代码确认 | 前端 | F04·`842469ae` |
| R23 | P3 | e2e 真空区：成本质量页、统一搜索页零默认 e2e；治理 spec 全部 goto 直链，从未验证「如何到达」 | 代码确认 | 测试 | **Q01 关闭**：真实导航 e2e（m12-f01:258 起）/search×4/成本页 e2e 已交付 |
| R24 | P3 | 通知铃铛无未读徽标；M11 实时通道未反哺顶栏通知 | 代码确认 | 前端 | F06 |
| R25 | P3 | TaskIntent 详情展示原始 WorkProject UUID（修订表单却是 key·name） | 代码确认 | 前端 | F08·`0fe13de9` |
| R26 | P3 | Inbox 批量结果与列表行用 UUID 前 8 位标识事实 | 代码确认 | 前端 | F08 + F10·`28003cb2` |
| R27 | P3 | Activity 详情面板 Subject 是 UUID、无跳转（流内卡片反而有 references 链接） | 代码确认 | 前端 | F08 + F12·`23b51844` |
| R28 | P3 | 知识版本历史无回滚（Skill 侧有完整回滚），恢复旧版只能手工复制 | 代码确认 | 前端 | A05·`770ac20a` + F04 |
| R29 | P3 | 两个「从执行蒸馏」按钮权限门槛不一致（知识 canManage vs Skill 全员+注释） | 代码确认 | 前后端 | F04·`842469ae` |
| R30 | P3 | 非管理成员的知识空状态是纯文本死页（Skill 侧留了蒸馏入口） | 代码确认 | 前端 | F04 |
| R31 | P3 | 「重建知识索引」无确认对话框（同库其它大动作均有 ActionConfirmDialog） | 代码确认 | 前端 | F03 |
| R32 | P3 | 构建仓库索引需手填 40/64 位 commit SHA，无分支 HEAD 快捷项 | 代码确认 | 前端 | F04 |
| R33 | P3 | 会话过期后组织设置/配额深链不被恢复（restorableRouteNames 缺席） | 代码确认 | 前端 | F01 |
| R34 | P3 | WorkGraph 入口无说明、阻塞徽标不可点入图定位节点 | 代码确认 | 前端 | F05 |
| R35 | P3 | 列表视图无在场订阅（仅看板），切换时 presence 无解释地消失 | 代码确认 | 前端 | F05 |
| R36 | P3 | 在场名单触屏/窄屏不可获知全名（title 悬停 + ≤900px 面板隐藏 + 溢出无展开） | 代码确认+需实机 | 前端 | F05 |
| R37 | P3 | 顶栏 collab-pill 长文案在 ≤767 五列顶栏有拥挤风险 | 需实机验证 | 前端 | F05·`be3af3d2` |
| R38 | P3 | Inbox 处置后条目不退场，「清空队列」心智缺位 | 代码确认 | 前端 | F06 |
| R39 | P3 | 撤销组织角色确认钮非 danger 变体（团队侧一致用 danger） | 代码确认 | 前端 | F02 |
| R40 | P3 | 删除作业创建后表单仍可再次提交（重复入口） | 代码确认 | 前端 | F02 |
| R41 | P3 | 导出需手动点「生成产物」且无解释；治理作业会话内存态刷新即丢（org-deviation 已登记） | 代码确认 | 前端 | F02 |
| R42 | P3 | LineChart 无可见逐点数值（sr-only 表格），与 BarChart 柱顶数值不对称 | 代码确认 | 前端 | F08 |
| R43 | P3 | 配额拒绝指引「组织设置 → 配额与用量」与实际 IA 不符（配额是独立路由）且不可点 | 代码确认 | 前端 | F02 |
| R44 | P3 | sso-only 且 ssoAuthorizationUrl 缺失时登录页零可操作元素 | 代码确认+需实机 | 前后端 | F07 |
| R45 | P3 | 内部概念泄露进首用文案（Session/坐标/投影/原子/Required 能力，多处） | 代码确认 | 前端 | F08 |
| R46 | P3 | 无标题工作台行回退显示 UUID 前 8 位；Setup 快照版本号外露 | 代码确认 | 前端 | F08 |
| R47 | P3 | 未知 actionKey 时配置按钮静默无响应；SSO 共存下登录页初始焦点不落输入框 | 代码确认 | 前端 | F08 |
| R48 | P3 | 多 OIDC provider 不可区分（统一显示「企业单点登录」） | 代码确认 | 前端 | F07 |
| R49 | P3 | 首用落地页随登录方式漂移（注册→对话页，SSO→今日），标准第一屏未定 | 代码确认 | 产品 | F01（裁定：落点按会话权限裁决） |
| R50 | P3 | 命令面板不覆盖知识闭环四页；主题切换埋在账号设置 | 代码确认 | 前端 | F08 |

## 3. P1 详述

### R01：纯组织角色登录即死胡同（mock 实机复现）

复现路径（Playwright + mock session：`organization.role='ORG_AUDITOR'`、`teams: []`、`permissions: [org:audit-read]`）：

1. 登录默认落点 `/conversation`（`src/domains/identity/route.ts:3`）需 `conversation:use` → 被拦到 access-denied；
2. 侧栏三组导航全部按 team 粒度权限过滤后为空（`AppShell.vue:111-114`），实机快照确认 `complementary "主导航"` 只剩品牌/折叠钮/账号菜单；ScopeSwitcher 亦隐藏；
3. 「返回 Today」、品牌「CrewScope 首页」、面包屑、顶栏模式切换四类链接全部指向 `/today`（需 `scope:read`）→ 全部循环回 access-denied；
4. 治理页本身可用（非门禁路由，`router.ts:164-180` 注释自证设计），但**没有任何导航入口**：`navigationGroups`（`AppShell.vue:84-110`）无 organization-settings/quota-settings 条目，命令面板 21 个 action（`defaultActions.ts:9-31`）无一指向，用户菜单（`UserAccountMenu.vue:46-47`）只有账号/退出。唯一入口是 SettingsShell 左栏（`SettingsShell.vue:14-15`）——而纯 org 角色连一个设置页都到不了。

现有 e2e 未暴露此问题：`m12-f01-organization.spec.ts:141` 等全部 `page.goto('/settings/organization…')` 直链深跳，从未验证「登录后如何到达」。access-denied 文案问题（R05）与登录恢复缺口（R33）是同一场景的放大器。

影响人群：M12-Q02 真实栈 gate 里被授予 ORG_ADMIN/ORG_AUDITOR 的账号（B 审计员即此形状）。任何启用组织角色的部署里，这是审计员/管理员的第一眼产品体验。

整改方向：组织侧导航（org:* 角色可见的第四组或独立壳）、登录落点按权限裁决、access-denied 出口按角色给出可达页。

### R02：TaskIntent 确认后断头路

「我有件事 → Agent 在做」的最短链约 8-9 个决策点，其中最关键一跳断了：

- 意图卡承诺「确认将原子创建 WorkItem；确认结果随后使用统一委托表单…」（`TaskIntentCard.vue:198`）；
- 确认成功后 `confirmTaskIntent()`（`ConversationPage.vue:622-638`）只重载 links/tasks，无 toast、无导航、无自动展开；
- 而委托入口「配置 Coding Task」在默认折叠的「关联 WorkItem」`<details>` 里（`ConversationPage.vue:991-992`，无 `open`；注释自认 M9b-Q01「默认收起」决策——该决策做出时折叠区内还没有成为唯一的委托入口）。

用户视角：点完「预检并确认」后页面几乎无变化，下一步去哪全靠自己发现。整改方向：确认成功后展开折叠区/聚焦新关联项 + toast 指路（复用既有 configure-return 模式）。

### R03：蒸馏必填 UUID 在产品内无合法获取路径

- 两个蒸馏对话框都承诺「在执行详情或产物面板可复制执行 ID（UUID）」（`KnowledgeDistillationDialog.vue:130`、`SkillDistillationDialog.vue:117`）；
- 实际上执行详情（`TaskDetailDrawer.vue`）无复制按钮，且 ID 被截断展示（`shortIdentifier` → `value.slice(0,8)}…`，:211-213）——截断值连手工抄写都不可用；
- 全库 clipboard 助手存在于 6 处其它场景，唯独没有执行 ID。

蒸馏是知识闭环的最后一跳（§4.3 R14），其必填输入在 UI 内不可得。整改方向：执行详情加复制按钮只是下限；上限是执行现场一键蒸馏（R14）让 UUID 从用户路径上消失。

### R04：知识六开关零呈现，关闭态被误读为「进行中」

- 六开关全部是 env 级（`application.yml:356-381,391-395`），默认 false；web 端 `grep pgvector|retrieval` 零命中，运行健康面板组件枚举（`teamops/types.ts:357`）不含知识索引/向量健康；
- 关闭态用户体验：条目永远挂「待索引」徽章 + 文案「内容已保存，等待后台建立索引」（`knowledge/labels.ts:40`）——把「功能未启用」读成「马上就好」；检索关闭的唯一痕迹是事后注入面板的降级徽章「检索开关未开启」（`injection/labels.ts:25`）。

M10 的验收都在六开或对照臂下跑（正确），默认关闭部署的产品体验从未被验收覆盖。整改方向：管理员侧开关状态呈现（至少进运行健康面板）+ 关闭态的「待索引」文案改为「索引功能未启用，请联系管理员」。

## 4. P2 详述（按主题分组）

### 4.1 治理与身份（R05–R10）

- **R05**：AccessDeniedPage 的 permissionLabels（`AccessDeniedPage.vue:9-15`）无任何 org:* 条目，回退「访问此区域」；标题硬编码「需要额外的团队权限」，指引「请联系 Team Owner」——org 场景应找组织 Owner，且授予入口恰在被拒页内（循环指引）。
- **R06**：组织角色授予是自由文本 UUID 输入（`OrganizationRoleGrants.vue:179-188`）。而 `PrincipalPicker.vue:19-23` 注释自证产品原则「Principal ID 是数据库事实，不该要求成员输入」——但该组件只支持 team scope，org 面没有等价物。组织内无处查成员 UUID，授予流程第一步就断。需后端组织成员目录支撑。
- **R07**：账号删除要求「目标账号 ID（UUID）」（`OrganizationDeletionWorkflow.vue:139-148`），而 accountId 在产品任何界面不渲染。与 R06 同根：组织级主体目录缺失。
- **R08**：会话列表前后端都只有 sessionId/createdAt/lastAccessedAt/current 四字段（`identity/types.ts:109-114`、`AuthenticationSessionController.java:126-127`），「撤销其他设备」是盲选。需后端补 userAgent 派生摘要。
- **R09**：SSO 入口是整页导航（`LoginPage.vue:243-247`），成功 handler 固定回 `/`（`OidcAuthenticationSuccessHandler.java:36,79`）。密码登录有 safeLoginDestination 恢复 returnTo，SSO 通道没有；最伤的是邀请链——受邀成员 SSO 登录后被静默丢到 Today/空页，邀请流程无提示中断。SSO-only 部署里这是每个受邀成员的必经摩擦，接近 P1。
- **R10**：邀请 proof 只存内存（`invitation/store.ts:79,95`），邀请页还主动剥离 URL hash（`InvitePage.vue:46-48`）；刷新后落到「请联系团队管理员获取新链接」。安全取舍正确，但 acceptanceRecovery.ts:22-41 已示范 sessionStorage+TTL+principal 绑定的合法做法，可迁移。

### 4.2 实时协作与工作循环（R11–R13）

- **R11**：看板的 collaboration 订阅只取 presence（`WorkPage.vue:214-223`）；`changeByScope` 全站唯一消费点是详情抽屉（`WorkItemDetailDrawer.vue:231`）。结果：A 看 B 在场（头像），B 拖的卡片变化 A 看不到，直到自己操作被版本拒绝。M11 交付的 resource_changed 通道在主视图零消费——「多人在场」是半成品呈现。
- **R12**：对话列表项无未读标记（store 无 unread 字段），Inbox 五类（OWNERSHIP/EXECUTION/REVIEW/CONFIRMATION/EXCEPTION，`InboxWorkspace.vue:237-244`）不含对话消息。团队成员的回复（含 Agent 提案更新）零收纳面，回环靠逐个点开对话。M11 实时通道已具备推送基础。
- **R13**：阻塞徽标聚合失败在列表/看板静默（`WorkPage.vue:486-489` 注释自认），错误态只在 graph 视图。默认视图基于错误的「无阻塞」做优先级判断。建议至少一次性 toast。

### 4.3 知识闭环证据链（R14–R22）

闭环六环中，「写 → 发布」扎实（幂等回执、PENDING 措辞单一权威源），其余各环断点：

- **R14**：蒸馏入口全库仅知识/Skill 页两处手动表单；TaskDetailDrawer/WorkPage/ConversationPage 无 distill 动作。转化摩擦全压在裸 UUID 输入上（与 R03 复合）。
- **R15**：发布后 indexStatus 只刷一次，无轮询（对照：索引作业页有 15s 自动刷新+可见性暂停，`KnowledgeIndexPage.vue:39-43`——正面样板已在库内，未复用）。
- **R16**：失败文案承诺「可在索引作业面重试」，该面实际只有 rebuild/repository-build/cancel 三命令（`index-store.ts:33`），QUEUED 可取消、无重试；提示是纯文本不链接。
- **R17**：作业行用 entryId/bindingId/commit 前 8 位（`KnowledgeIndexJobItem.vue:41,44`），而知识列表主键是 entryKey；无按条目筛选。「我发的那条索引到哪了」不可答。
- **R18**：注入引用面板 Skill 行生成 skill-catalog 深链（`InjectionAttemptBlock.vue:55-67`），KNOWLEDGE_ENTRY 行纯文本——Skill 侧的翻页解析+miss 提示（`SkillsPage.vue:147-172`）就是现成范本。
- **R19**：origin 字段在 DTO 里（`knowledge/types.ts:48`、`skill/types.ts:45`）但只在蒸馏回执渲染一次；详情/版本历史零展示。「这条知识蒸馏自执行 X」不可追溯。
- **R20**：NOT_APPLICABLE 反馈只写不读（`InjectionReferencesPanel.vue:86` 明示不影响检索、不可撤销），无聚合面。反馈环对用户是纯成本。
- **R21**：预算提醒（budget.enabled，后端 yml:399-406 + TeamBudgetAlertScheduler）前端零呈现；成本质量页无预算行。与 M12 配额（monthlyTokenBudget）是两套概念，收到提醒时无从判断指哪个。
- **R22**：ConversationPage 全文件注入/知识零命中；注入证据唯一落点在任务抽屉。「知识进入对话」的产品叙事在对话面无任何呈现。

## 5. P3 清单说明

R23–R50 见总表；补充两点：R23（e2e 真空）是 R01 未被暴露的根因之一——治理 spec 的 goto 直链模式绕过了所有「如何到达」验证，建议下轮补「导航可达性」维度；R49（首屏漂移）与 R01 相关但独立存在（注册→conversation vs SSO→today），需要产品先裁定标准第一屏。

## 6. 做得好应保留（本轮正面基线）

1. **首用容错链**（M9b 遗产维持）：committed-registration 锁定 +「不会再次创建账号」；onboarding 幂等键 + uncertainCreates；邀请 acceptanceRecovery（sessionStorage+TTL+principal 绑定）。
2. **状态流转 enabled/reason/remedy 全链贯彻**（M9-A05 遗产维持）：ActionMenu 的 aria-disabled+原因+remedy 深链；动作列表只认服务端 availableTransitions；撤销窗口 10s 全呈现面统一。
3. **委托表单的每步解释**：预检卡片（模型/Binding/PolicySnapshot）、「去配置并返回」闭环、不可分配候选带服务端原因。
4. **知识侧的单源文案纪律**：「待索引≠未保存」收敛为一个常量四处引用；注入面板三区结构（已注入/候选/声明引用）+「读作事实而非评判」措辞原则。
5. **索引作业页的等待体验**：15s 自动刷新带开关与可见性暂停、分片进度、QUEUED-only 取消——长任务不黑盒的库内正面样板（R15 的整改应直接复用）。
6. **移动端/暗色全覆盖**：汉堡抽屉导航、底部三格、离线横幅、auth 页 680px 断点、双 token 暗色体系。
7. **历史修复零回退**：M9 后 R01（secureId 封装）、R02（工作项 Key 免填）、R06（幂等键）、R16（服务端排序）本轮逐一抽查全部在位。

## 7. 与历史 Review 的对照

| 维度 | M9 后评定 | 本轮 | 变化 |
| --- | --- | --- | --- |
| 基础设施层（组件/token/状态机） | C 级断裂（15 份 Dialog、330 处裸 title） | A（M12b 后抽查无回退） | 已修复 |
| 首用路径（本地账号） | R01–R03 阻断 | 高质量（正面基线 1） | 已修复 |
| 跨页流程接线 | R05/R11 猜测式创建 | 单页内已好，跨页断点转移（R02/R03/R14） | 部分改善、形态转移 |
| UUID 对用户暴露 | R02 时代普遍 | 团队面清零，**治理面与知识作业面回潮**（R06/R07/R17/R25–R27） | 部分回潮 |
| M10–M12 新能力体验 | —（未评审） | 三条主线各有最后一公里断点（§1） | 本轮新发现 |

## 8. 计划承接：M14 候选主题聚类（供计划冻结时裁定）

| 主题 | 覆盖 | 形态建议 |
| --- | --- | --- |
| T1 组织治理可达性与主体目录 | R01/R05/R06/R07/R08/R33/R39–R43 | 前端为主 + 后端组织成员目录/会话设备摘要两个读侧端点；纯 org 导航是核心 |
| T2 流程断头路收口 | R02/R03/R14/R16/R31 | 前端小改集合；执行详情复制 ID + 一键蒸馏 + 确认后展开 |
| T3 知识闭环证据链 | R04/R15/R17–R22/R28–R30/R32 | 前端为主 + 开关状态呈现（后端读端点或健康面板扩展） |
| T4 协作从在场到事实同步 | R11/R12/R24/R34–R37 | 看板消费 resource_changed + 对话未读收纳（后端 unread 投影） |
| T5 打磨批 | R09/R10/R13/R23/R44–R50 等 | 按需裁剪；R23（e2e 可达性维度）建议与 T1 同批 |
| SSO/邀请衔接（若 SSO 是近期部署目标则升优先） | R09/R10/R44/R48/R49 | 前后端各半 |

排期建议：T1+T2 为第一优先（P1 全部在内且纯前端占比高）；T3 第二（M10 能力的投资回收）；T4 第三（依赖后端投影）；T5 滚动。竞品对照本轮未做（与 M9 后 Review 的差异点），如需可单独补充。

## 9. 走查覆盖与边界

- 代码走查：四轴 22 页面 + 40 余组件 + 相关域层/后端命令（各轴报告文件清单入工作档案）；横切面（导航/⌘K/主题/通知/移动端/暗色）主会话自查。
- 实机复现：R01 全链（mock 栈 Playwright，2 用例绿；临时 spec 已删除，断言与快照证据在本文 §3）。Docker 真实栈未起——裁定依据：本轮 P1/P2 全部为前端呈现/接线问题，mock 栈已足够复现；后端契约类发现（R06/R07/R08/R09）已读后端源码确认字段面。
- 未覆盖：竞品实机对照、真实模型下的注入/检索动态体验（与 M13-Q01 登记的 m10-q02 gate 凭据缺口同源）、逐行安全审计。

## 10. M13b-Q01 收口台账（2026-10-24）

本文全部 50 项发现（R01–R50）经 M13b 三个波次（F01–F12 前端包 + A01–A07 后端包 + Q01 收口）全部关闭，逐行映射见 §2 总表「M13b 关闭」列；第二轮全景 Review（`M13b前-产品体验全景Review.md` §9）承载其余发现的映射。要点：

- **P1 复评 = 0**：R01→F01（`1d848df7`，组织侧导航组+落点裁决+被拒出口）且 Q01 定向 e2e 实机链路复绿（`crewscope-web/e2e/m12-f01-organization.spec.ts:259`「纯组织角色：登录落点、组织导航组与被拒出口无死胡同」，全部真实导航，直链 goto 模式已弃用）；R02/R03→F03（`ba43f845`，确认后展开+执行现场一键蒸馏，UUID 从用户路径消失）；R04→A03（`cb5eb34c`，六开关只读端点）+ F04（`1320c40c`，健康面板呈现+关闭态文案改「索引功能未启用」）。
- **R23（e2e 真空）由 Q01 维度关闭**：真实导航 e2e（m12-f01:258 起连续用例）、统一搜索页 4 用例（`e2e/m9b-f04/search-sessions.spec.ts`）、成本质量页 e2e（`m10-agent-cost` + observability 基线）已交付——「如何到达」不再是盲区。
- **验证结构**：全 reactor `./mvnw -pl crewscope-server -am verify` 0 失败 0 错误；web 侧 vitest 全量（1992）+ e2e 全量（705，基线 681+新增）+ 门禁（check:quality / check:enum-labels / check:q01 / check:sensitive / check-design-tokens / check:bundle）全绿。
- **登记遗留（唯一，已裁定不在本里程碑）**：m10-q02 release gate 凭据缺口——用户裁定明确不做（M13b 计划 §1/§5 登记），复跑指引见 `docs/testing/M13-Q01-跨包收口验收.md` §7，待凭据可用时执行。该项与本文 §9「未覆盖」的注入/检索动态体验同源。
- **M14 主题承接说明**：§8 的 T1–T5 聚类在 M13b 中已被 F01–F12/A01–A07 实质消化，T1（组织治理可达性）→F01/F02/A01/A02、T2（流程断头路）→F03/F04/A03、T3（知识证据链）→F04/A04–A06、T4（协作事实同步）→F05/F06/A07、T5（打磨批）→F07–F12；SSO/邀请衔接→F07。M14 立项时本文不再作为待办来源。
