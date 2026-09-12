# CrewScope 里程碑执行清单

本目录把《CrewScope 实施计划》的里程碑拆成可领取、可验证、可关闭的执行任务。

## 1. 文档层次

```text
CrewScope-实施计划.md     里程碑、范围、依赖、周期和 Release Gate
CrewScope-前端设计规范.md 前端信息架构、视觉 Token、组件与验收规则
plans/M*-*.md            当前及下一个里程碑的可执行 Backlog
adr/ADR-*.md             跨模块关键技术决策
```

总实施计划保持稳定。里程碑任务清单在进入该里程碑前完成细化，后续里程碑只保留 Feature 级范围，避免基于尚未验证的技术假设过早拆分。

## 2. 任务类型

| 类型 | 用途 |
|---|---|
| `SPIKE` | 验证接口、拓扑或风险，输出可复现证据与决策 |
| `TASK` | 单模块、单适配器或单基础能力实现 |
| `FEATURE` | 可演示的纵向产品能力 |
| `HARDENING` | 安全、性能、并发、恢复和故障测试 |

## 3. 编号规则

```text
M{里程碑}-{工作流}{序号}
```

工作流代码：

| 代码 | 工作流 |
|---|---|
| `S` | Spike 与架构验证 |
| `D` | 领域与数据库 |
| `E` | 事件、Outbox 与投影 |
| `I` | 基础设施与外部适配 |
| `A` | API、认证与服务端 |
| `F` | 前端 |
| `Q` | 测试、质量与发布 |

## 4. 任务大小

- 单个 `TASK` 目标工期为 0.5–2 个工作日；
- 单个 `SPIKE` 目标工期为 1–3 个工作日；
- 业务建设里程碑中，超过 3 个工作日且存在独立产品出口或风险边界的任务在开始前继续拆分；
- 产品化、重构和发布收口里程碑可以使用 3–10 个工作日的完整工作包，内部通过 Checklist 和小提交推进，不为单个类、页面、脚本或测试重复编号；
- 一个任务只设置一个主要交付结果和一个责任人；
- 任务关闭时必须提交代码、测试和运行证据。

## 5. Definition of Ready

任务进入开发前满足：

1. 目标、范围和非目标明确；
2. 前置任务已完成或拥有可用 Stub；
3. 涉及的 ADR 已接受；
4. 数据库、API、事件和 Structured Output 变更已识别；
5. 验证方式可以自动执行或形成明确人工证据。

## 6. Definition of Done

任务完成时满足：

1. 实现已合并并通过编译；
2. 复杂逻辑、公开 API 和安全边界包含必要注释；
3. 单元、集成、契约或端到端测试覆盖本任务风险；
4. 权限、幂等、并发、失败和恢复路径完成验证；
5. API、事件、配置、迁移和错误码文档同步更新；
6. 日志、指标、Trace 和 Audit 覆盖关键路径；
7. `./mvnw clean verify` 与相关前端检查通过；
8. 任务卡记录验证命令、结果和 Artifact 链接。

## 7. WorkItem 映射

任务进入 CrewScope 或 GitHub 时使用：

```text
title       [M0-S01] HarnessAgent 最小调用验证
type        SPIKE / TASK / FEATURE / HARDENING
milestone   M0
owner       唯一 TeamMember
labels      workstream、module、risk
depends_on  任务 ID 列表
acceptance  执行清单中的验证条件
evidence    Test、PR、Artifact、ADR 和演示链接
```

任务状态由实际 WorkItem 管理，Markdown 保存计划基线和拆分口径。

## 8. 当前执行清单

- [M0：工程与数据基线](M0-工程与数据基线.md)
- [M1：Team、WorkItem 与责任基础](M1-Team与WorkItem.md)
- [M2：Conversation 与 Personal Agent](M2-Conversation与Personal-Agent.md)
- [M3：耐久 Task Runtime](M3-耐久Task-Runtime.md)
- [M4：AgentScope 原生 Coding Agent](M4-AgentScope原生Coding-Agent.md)
- [M5：Agent 模型、个人执行 Agent、Review 与 GitHub Draft PR](M5-Agent模型与Review交付.md)
- [M6：团队观测、飞书通知与 MVP 发布](M6-团队观测与MVP发布.md)
- [M7：开放用户体系与登录体验](M7-开放用户体系与登录体验.md)
- [M8：产品化与工程收口](M8-产品化与工程收口.md)
- [M9：产品体验重构与设计系统](M9-产品体验重构与设计系统.md)
- [M10：Agent 智能跃迁与知识闭环](M10-Agent智能跃迁与知识闭环.md)
- [M11：协作规模化与开放生态](M11-协作规模化与开放生态.md)
- [M12：企业化与多租户治理](M12-企业化与多租户治理.md)

M9–M12 由 [M8 后全面架构与产品体验 Review](../reviews/M8后-全面架构与产品体验Review.md) 导出。M9、M10 为可执行粒度，M11 为 Feature 级基线（进入前需按 `M11-S01` 结论细化），M12 只冻结方向与边界、不作为可领取清单使用。

M8 功能状态为 `M8_FUNCTIONAL_COMPLETE`：`M8-S01`、`M8-A01`、`M8-A02`、`M8-F01`、`M8-E01`、`M8-I01`、`M8-I03`、`M8-Q01`、`M8-Q02` 已完成实现与验证。`M8-I02` 的 GHCR、Cosign、SBOM、Provenance 和受保护 Tag 属于 `SUPPLY_CHAIN_RELEASE_DEFERRED`，不阻断当前产品功能开发。详见 [M8-Q02 Release Gate](../testing/M8-Q02-Release-Gate.md)、[M8-I03 运维可观测、备份与执行隔离](../testing/M8-I03-运维可观测备份与执行隔离.md)、[M8-I02 正式发行与镜像供应链](../testing/M8-I02-正式发行与镜像供应链.md)、[M8-I01 依赖与配置治理](../testing/M8-I01-依赖与配置治理.md) 和 [M8-Q01 质量反馈与分层门禁](../testing/M8-Q01-质量反馈与分层门禁.md)。

M0 至 M7 已完成，M8 的九个工作包已全部完成本地实现，`M8-Q02` 本机发布预检通过，下一阶段是真实 Linux amd64 发行候选验证。M4 的 44 个任务和 [M4-Q04 Release Gate](../testing/M4-Q04-Release-Gate.md) 已全部关闭；最终 DeepSeek 真实模型固定矩阵为 29 / 36、端到端成功率 80.56%，CrewScope 自修改闭环与质量门禁通过。M4 全量门禁为 Maven 1517 / 1517、Vitest 237 / 237、Playwright/视觉/Axe 126 / 126。

M5 的 48 个任务和 [M5-Q04 Release Gate](../testing/M5-Q04-Release-Gate.md) 已全部关闭。当前已交付模型/Agent 配置与动态 AgentScope Model、个人/团队/Specialist Factory、Reviewer 证据和持久化闭环，以及 GitHub App/OAuth 身份验证、Repository Catalog/Preflight、受管 Mirror、AskPass/Lease Push、Draft PR 查询幂等、Webhook 去重、Action Worker、UNKNOWN/过期 Lease Fenced 对账、人工队列与终结、Fencing/Receipt 原子事务、V26 Claim 恢复和条件 Spring 装配。前端已闭合 Agent 与模型管理、Task 委托和配置预检、Review Workbench、GitHub Delivery Workbench，并完成全状态、响应式、键盘焦点、ARIA、Histoire、双视口视觉、Axe 与敏感字段 CI 门禁。最终门禁为 Maven 1862 / 1862、Vitest 311 / 311、Playwright/视觉/Axe 150 / 150；M5-Q01 固定攻击 84 / 84 被阻断，M5-Q02 固定故障 48 / 48 收敛，M5-Q03 Reviewer 质量门禁通过。

M6 的 50 个任务已全部完成，覆盖 Activity、Inbox、Audit、影子投影重建、三流 Cursor、固定模板 Lark 通知、只读 Team Observer、OTel/Prometheus、部署、备份恢复、故障、负载和 MVP Release Gate。[M6-Q04 MVP Release Gate](../testing/M6-Q04-MVP-Release-Gate.md) 已关闭，CrewScope Team Beta MVP Release 决定为 `PASS`。

M7 的 39 个任务已全部完成：4 个 Spike、8 个领域/迁移任务、8 个基础设施任务、7 个应用/API 任务、8 个前端任务和 4 个质量任务。范围包括单 Organization 自托管本地账号、可配置开放注册、Spring Session Redis、正式登录/注册页、首次 Team Onboarding、默认 Personal Agent、一次性邀请链接、Operator/监控凭证分离、认证固定攻击集和 V30→V32 升级门禁。当前已交付注册、登录、Session 投影、当前账号管理、Onboarding、邀请、安全路由、正式 `/login`、`/register`、`/onboarding`、`/account` 与 `/invite`，以及真实 AuthStore、启动 Session 恢复、Router Guard、401 统一恢复、跨标签退出、跨账号 Store 隔离、账号强 ETag/密码 Step-up/会话撤销、邀请创建/列表/撤销、Fragment 内存证明、已有账号登录接受、新账号原子注册入 Team、闭合 DTO/Gateway/错误/Audit/Spring 装配合同，以及扩展 Coverage、Histoire、双视口 Playwright/Axe、视觉、README/Demo 和敏感字段 CI 收口。Q01 已通过 128/128 固定认证攻击、Java 194/194 与 Web 61/61；Q02 已通过 72/72 固定并发故障样本与 Java 140/140；Q03 已使用真实 PostgreSQL/Redis、生产 Web 和两个独立 BrowserContext 完成双用户邀请、双 Personal Agent、Conversation、重启、Audit、Session 过期与恢复，Desktop/Narrow `2 / 2 passed`；Q04 已通过 Maven 3056/3056、Vitest 652/652、三 Profile 真实 E2E、部署恢复、文档与生产依赖门禁，M7 Release Gate 本地结论为 `PASS`。

M8 使用 9 个较大的完整工作包推进产品化与工程收口，不再复用 M0–M7 的细粒度拆分。功能范围包括 Team Setup Readiness 与 Setup Center、核心职责和 Port 收口、依赖与配置治理、Trace/告警/自动备份/TLS 运维加固、全生产代码 Coverage、分层 CI 和功能 Release Gate。GHCR、Cosign、SBOM、Provenance 与受保护 Tag 的正式供应链发行标记为 `SUPPLY_CHAIN_RELEASE_DEFERRED`，待产品稳定后处理。M8 不新增业务领域，也不包含 Kubernetes 高可用、企业身份、插件市场或 Autopilot。

M9 起进入产品力建设阶段。M8 后的全面 Review 结论是：后端工程质量已达发行水准，产品体验是当前唯一的结构性短板。因此 M9 不新增业务领域，集中解决四类问题：**首屏不呈现工作**（`TodayPage.vue` 仅 134 行且自述"工作事实仍由各业务 API 提供"，服务端也缺按责任人过滤与跨项目聚合，导致责任链这一核心差异化资产在产品上不可见）、设计系统缺失（`tokens.css` 无字号/间距 Token，导致 576 处 `≤10px` 字号与自身设计规范的"正文 14px"严重漂移）、信息架构单层平铺、交互内核缺位（⌘K 与通知为无 handler 的装饰控件，无全局快捷键、无拖拽、无虚拟滚动、无暗色模式），并前置补齐 OpenAPI 契约生成这一最高优先级后端工程债。其中个人工作台首页（`M9-A03` + `M9-F07`）是 M9 产品价值最高、最先被用户感知的交付，优先级高于命令面板。

第二轮深度评审（Review §3.6）在上述四类问题之外，又追加了三个工作包，因为发现前端缺的不是页面而是**页面之下的一整层基础设施**：`components/base/` 只有 2 个组件，导致 Dialog 被重复实现 15 次、焦点陷阱 9 次；无全局 Toast、无 Tooltip 组件（330 处裸 `title=`）、仍在用原生 `window.confirm()`、`<Transition>` 用量为 0；全站零排序、零批量选择、零相对时间；36 个 `StatePanel` 引用中只有 1 处使用 `action` 插槽，35 个空状态是死胡同；22 条路由零面包屑、`localStorage.setItem` 仅 1 处、38 种互不对齐的断点值。最关键的是 `CodingDiffExplorer` ——读 Diff 是本产品价值交付的最后一环，但它无语法高亮、无行号、无分栏、无行级评论。因此新增 `M9-F01` 的基础组件与反馈闭环扩容、`M9-F08` + `M9-A04`（Diff 阅读器与行级 Review 评论，后者是 M9 唯一由前端诉求驱动的后端写接口）、`M9-F09`（列表能力与偏好持久化基线），并把 M9 周期由 6–8 周上调为 8–10 周。压缩排期时的优先级顺序是 `F07` > `F08` > `F04`/`F05`/`F06`。

第三轮针对状态流转的专项评审（Review §3.5）暴露了一个比"缺组件"更根本的建模错误：**领域状态机被当成了表单字段**。推进一个工作项需要七步（进列表 → 点开卡片 → 等抽屉 → 找到流转区 → 展开 `<select>` → 挑一个枚举 → 点"提交流转"，`WorkItemDetailDrawer.vue:249`）；Review 结论同形，且不可逆的 `REJECTED` 与 `COMMENTED` 在四个等权 `<option>` 里平铺（`ReviewWorkbench.vue:318`）；看板已按状态分列却不可拖拽（`WorkPage.vue:1122` 只有 `@select`）。更深的两层是：前端**手抄了一份领域状态机**（`domains/workitem/types.ts:176`，注释自述为 mirror），而后端 17+ 个聚合的 `ALLOWED_TRANSITIONS` 全部 `private`、零个对外暴露，漂移不会有任何信号；后端只回答"能不能"、不回答"为什么不能"，前端只能用三个 `if` 猜测原因（`WorkItemDetailDrawer.vue:250`）。因此新增 `M9-A05`（动作可用性投影，`availableActions[]` 携带 `enabled`/`reason`/`remedy`）并扩写 `M9-I01`（从领域 `ALLOWED_TRANSITIONS` 生成 `api/generated/state-machines.ts`，手抄归零）、`M9-F05`（下拉改动作按钮 + 看板拖拽 + Review 动作分级，操作步数 7 → ≤ 2）、`M9-F07`、`M9-S01`、`M9-Q01`（新增两条门禁：禁止用 `<select>` 承载流转目标、禁止前端手写状态机常量），M9 工作包由 17 个增至 18 个。**本项改造只改流转的呈现与发现方式，不增删任何一条流转边**；周期仍为 8–10 周。Review §7 的第 9–11 项是三条无后端依赖的前置改造，当期即可先行。

第四轮评审针对**各种配置**与全站小交互（Review §3.7、§3.8）：配置是新用户的必经路径，却是欠账最集中的地方。14 个配置界面的 212 处字号声明中 173 处（82%）≤11px，表单输入框是 `font: 10px`、标签 9px、校验文案 8px，而同文件的 `@media (max-width: 600px)` 把输入框放大到 16px——**桌面端比移动端更难读**；全站零脏态跟踪，而 `AgentSettingsPage.vue:93-101` 切换 Team 时主动 `router.replace`，把含 16384 字补充指令与技能勾选的表单静默清空；参数边界只活在校验函数里（`min`/`max`/`step` 全站 5 处），33 处 `aria-invalid` 只画红框不给文案；历史 Revision 只显示 `configurationHash` 前 16 位，且历史 DTO 本身不含全量载荷，前端无法构造差异视图；配置面零搜索，模型选择再次用原生 `<select>` 承载；API Key 无明文切换且提交未 `trim`（凭证验证失败最常见成因）；全站仅一处复制按钮；`ACTIVE`/`ZERO_RETENTION`/`TEAM_SUBJECT` 直接上屏，而 `SetupPage.vue:34-60` 已有六个正确的枚举翻译函数——**一个页面做对了，十三个裸奔**；9 个配置入口平铺无外壳。同轮还查出一条全站规则性缺陷：**218 处 `:disabled` 绑定中带原因说明的为 0**，`M9-A05` 的 `reason` + `remedy` 必须上升为全站规则。因此新增 `M9-A06`（配置历史全量载荷 + 配置健康派生查询 + 配置项检索，全为只读且不提供应用历史版本的写路径）与 `M9-F10`（配置体验十项重构），并扩写 `M9-S01`、`M9-F01`（`StatusBadge` 升级为动作入口）、`M9-F05`（四个呈现面 + 撤销窗口）、`M9-Q01`（禁用原因、配置字号、裸枚举、脏态守卫四条新门禁），M9 工作包由 18 个增至 20 个。状态流转的最终交互形态定为"**一个动作模型，四个呈现面**"——状态徽章即动作入口为主（只升级一个组件即覆盖列表行/看板卡片/详情抽屉/首页卡片四处），配卡片主动作按钮、看板拖拽、⌘K 三条辅助路径，并以**撤销窗口**作为安全砝码；撤销实现为一条已存在的反向流转边，不引入任何新状态，不可逆动作改为二次确认。周期仍为 8–10 周；Review §7 的低成本修复由 11 项增至 17 项。

第五轮评审把导航里**其余每一个菜单**当成独立产品逐个走查（Review §3.9），判据是"我为什么来 / 我能做什么 / 看完去哪"三问。结论分三层。**第一层是同一批地基缺口的第二次显影**：`StatePanel` 没用全（`TeamObserverPage` 整页 30 行，`ready` 之外不渲染任何东西；404 页 13 行且不带 AppShell）、枚举映射没有（54 处裸插值）、`reason`/`remedy` 没有（路由守卫丢掉 `requiredPermission`，无权访问页说不出缺哪个权限）、URL 状态不统一（审计页筛选进 URL 可分享是正面样本，Activity 页 Actor 筛选却不进）。**第二层是两个此前从未被任何评审或门禁覆盖的新缺口**：其一，**移动端 14 个菜单里 12 个不可达**——`@media (max-width: 767px)` 把整条导航 `display: none`，只留两格底栏，全站零抽屉零汉堡，同断点还隐藏了 `.context-header__actions` 使每页主操作一并消失，而四断点视觉基线因为只比像素不断言可达性，把"导航消失"当成正确基线固化了；其二，**产品在浏览器里没有名字**——`document.title` 全站 0 处，生产导航 0 处 `aria-current`（Axe 不检查这一项）。**第三层是对前几轮两个结论的修正**：真实首屏不是 Today 目录而是 `{ path: '/', redirect: { name: 'conversation' } }` 指向的空对话框；字号欠账容错重测后是 906 处声明中 743 处（82%）≤11px、680 处 ≤10px、486 处 ≤9px、43 处 7px，而规范正文 14px 仅 12 处——原记录的"576 处 ≤10px"少算了 104 处。本轮还再次印证主线："推广已有答案"这次连后端都适用——Activity 需要的服务端筛选参数在 `TeamActivityController:63-68` 早已存在，前端却在已加载的那一页上做客户端过滤，导致筛选选项取决于翻了几页、计数会骗人、"加载更多"与筛选互相打架。因此新增 `M9-F11`（全站菜单体验对齐十二项：移动端抽屉导航与页面动作槽、页面元数据、根路径落点、观察类页面出口、Team Observer/404/无权访问三页重做、Inbox 全部视图、筛选下推服务端、主体选择器替代 UUID、成员页重构、轮询可见性守卫、审计 CSV 导出、改密不静默登出）与 `M9-A07`（主体目录与成员角色只读查询），并扩写 `M9-Q01`（移动端可达性、页面元数据、禁止 UUID 输入、禁止游标分页下客户端筛选四条新门禁，合计十二条），M9 工作包由 20 个增至 22 个，周期仍为 8–10 周；Review §7 的低成本修复由 17 项增至 28 项。16 个菜单里真正缺后端能力的只有一处——**成员的角色变更与移除**（`TeamController` 只有加人与列表两个端点，成员 DTO 连 `roles` 字段都没有）：读侧归 `M9-A07`，写侧因涉及新领域命令、事件与责任转移语义，作为 `M11-D02` 放入 M11，**不塞进 M9**。

为降低实现偏差，四份计划各自追加了 `## 10.` 实施细则附录，详细程度按「距离实现的远近」分三档：**M9 §10 是完整的防偏差基线**（红线清单、精确 Token 取值与其对《前端设计规范》§4.2–4.4 的对齐、前后端文件落点表、类名族对照、API 与迁移边界、门禁与 E2E 文件清单、ADR 与契约的精确文件名、二十条「不要这样做」、十条 PR 完成定义），并在其中裁决了三处会直接导致实现分叉的冲突：组件前缀统一为 `Base*`（`CsTooltip`/`CsSkeleton` 正文已改写为 `BaseTooltip`/`BaseSkeleton`，`Cs` 只作 CSS 变量前缀；`StatusBadge.vue` 因 36 处引用保留现名）、第三档动效时长取 220ms 而非正文的 260ms（对齐规范 §4.4 的 200–240ms 带并沿用代码库已有的 `--cs-transition-*` 命名）、`M9-A05` 动作可用性**不得**落在 `application/action`（实测 `domain/action` 有 58 个类、`application/action` 是飞书 ActionDelivery），改落 `application/workitem` 且类名以 `WorkItemTransition` 开头。**M10 §10 是依赖与边界基线**（pgvector 需三处 compose 镜像同步替换且必须有无 pgvector 的降级路径、扩展模块只许依赖不许 fork、五个迁移一包一文件、五个特性开关默认关闭且关闭态页面不得留白、新页面必须把菜单清单从 14 同步到 18）。**M11/M12 §10 刻意只写边界**，因为两者的大工作包按各自 §9 必须在 Spike 之后再细化，凡未定之处一律标注「由 `S01` 决定」并列为 Spike 出口的必答项——M11 列了十项、M12 列了十项（含「M12 是否拆为 M12 + M13」这一判断本身）。

M10 面向 AgentScope 能力面的纵深使用：当前仅引用 model/event/message/agent/harness/tool/middleware/state/agui/skill/permission，`memory`、`extensions-rag`、`extensions-skills`、`extensions-protocol`、`extensions-scheduler` 全部未接入，Agent 每次任务都在从零理解同一个代码库。M10 建立 Team Knowledge / Repository Index / Agent Memory 三层知识模型与 Skill 沉淀闭环。

M11 解决多人同屏协作、WorkGraph、MCP 双向接入、i18n 与桌面端；M12 解决多租户隔离、企业身份、配额计量、K8s 执行拓扑与 SLO。M11、M12 的任务粒度在进入各自里程碑前依据 Spike 结论再细化。
