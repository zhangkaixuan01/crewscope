# M13d 前：性能与加载 Review

日期：2026-10-24。对象：CrewScope 前端全量——29 条路由与入口链（main/App/AppShell/router）、~28 个域 store/gateway 的请求模式、渲染性能面（虚拟化/watch/动画/格式化）、构建产物与资源（dist 2.8M 全量归因）；基线 `212bd2d2`（M13c 收口之后）。

评审主旨（用户裁定，延续 M13 系列 Review 先行模式）：**轴=性能与加载**——找出机械可修的加载浪费与渲染浪费，锚定已系统化做对的正面基线，需实测或产品取舍的登记存疑。零业务实现修改，只产出本文。

方法：四路并行扫描（路 A 路由与首屏 / 路 B 数据获取与请求模式 / 路 C 渲染性能 / 路 D 构建产物与资源）+ 主会话横切量化（懒加载计数、dist Top、性能基建盘点、deep watch 逐处复核）并对高杠杆点亲自定性。**路 C 因 API 账户限额中断，由主会话压缩完成**（7 面中 6 面；字体/图片面由路 D 证据覆盖：零 webfont、唯一静态资源为 favicon），覆盖深度低于其余三路，已在 §7 边界如实登记。与前轮正交：M13 看后端结构、M13b 看产品体验、M13c 看文案密度——本文只看「快不快、省不省」。

判例基线（全文沿用）：

- **问题**：机械可修的浪费——可并行却串行、重复拉已缓存数据、管道已建未接线、死代码、急切加载非首屏必需品。
- **保留**：做得好的正面基线——懒加载、游标分页、取消基建、竞态防护、轮询单源、增量推送、stale-while-refresh。
- **存疑**：需实测数据或产品取舍才能定案（缓存 TTL、刷新间隔、keep-alive 权衡、manualChunks 重划收益）。

## 1. 结论

**请求纪律与构建卫生是这个代码库的强项，性能债集中在三个「机制已建成但没接完/没关上」的单点**：main 急切安装全部域、ConversationPage 卸载即清缓存、gateway 的 AbortSignal 三 store 未接线。

四路合计约 100 条判定：**问题 15 / 保留 66 / 存疑 19**。正面基线密度罕见——29/29 路由懒加载、全部 list 端点游标分页零「拉全量」、请求取消基建（requestScope/epoch/版本门）全覆盖、轮询收敛到 useAutoRefresh 单源、三条实时通道全部守卫式建连、SSE/WS 增量为主写后定向重读为辅、148,929B 生成代码完全摇树、零 webfont 零图片资源、预算门禁接入 CI。M9b–M13c 多轮收敛的机制资产在位。

问题面则高度收敛：15 条里 9 条属三族——「gateway 支持 signal 但 workitem/scope/workdesk 三个 store 没接」（B39/B40/B41）、「可并行/可短路的小串行」（A11/A12/A15/A16）、「死代码」（B16 checkKey 链、A30 死 import、C01 冗余 deep）。真正的结构性问题只有两个：main chunk 把 ~28 个域的 gateway+store 全部急切安装（D01，同时 main 已贴单 chunk 上限 87.1%）与 ConversationPage 卸载全量 reset 抹掉 store 缓存能力（A21，全站最重页的来回切换=每次全量重拉+SSE 重建）。

三个最高杠杆单点（一处修改消一类问题）：

1. **main 急切安装**（D01+D20）：单页消费域（observability/quota/teamobserver/setup 等）的 install 从 main.ts 搬进对应页面模块——首屏急切 JS 从 557KB（gz ~171KB）直接减一截，同时解除 main 距 512K 上限仅余 66KB 的贴线预警。
2. **ConversationPage 卸载 reset**（A21）：六个 store 全量 reset 使 scopeKey 缓存能力形同虚设——改「只断订阅不清缓存」，来回切对话从「list+detail+messages+links+tasks 全量重拉+SSE 重建」变为缓存命中。
3. **AbortSignal 接线**（B39/B40/B41）：gateway 全签名支持、管道已在，三个 store 调用点接上即完成——快速切 project/filter 时在途全页请求不再白跑。

一句话总评：**这不是一个「没做性能」的代码库，是一个「性能机制建成度 90%、接线完成度 70%」的代码库**——M13d 的主体是把线接完、把门关上，而不是新建机制。

## 2. 问题总表

本轮无 P1（无功能性阻断——纯性能与加载）。P2：系统性、影响首屏或最高频路径。P3：单点浪费与清理。

| 编号 | 级 | 位置 | 一句话 | 路 |
| --- | --- | --- | --- | --- |
| D01 | P2 | src/main.ts:11-74 | ~28 域 gateway+store 全部急切安装进 main（445,936B/gz 127,611B），单页消费域白进首屏 | D |
| D20 | P2 | scripts/check-web-bundle-budget.mjs | main 445,936/512,000=87.1%、JS 总量 92.66% 双贴线，再塞一个中型域即触顶（与 D01 同根因） | D |
| A21 | P2 | src/pages/ConversationPage.vue:482-489 | unmount 六 store 全量 reset→来回切对话每次全量重拉+SSE 重建（store 本有 scopeKey 缓存被抹掉） | A |
| A08 | P2 | src/domains/scope/store.ts:151-152 + AppShell.vue:142-225 | 每次导航 synchronize 无条件先翻 loading 相位，缓存全命中也闪一拍 loading 面板 | A |
| B39 | P2 | src/domains/workitem/store.ts:264,280,301,390 | load/refresh/loadMore/loadDetails 未传 signal（gateway 全支持），竞态靠版本丢弃但请求照跑 | B |
| B40 | P3 | src/domains/scope/store.ts:139,192,201,234 | 同 B39，scope store 零 signal 使用 | B |
| B41 | P3 | src/domains/workdesk/store.ts:54-74 | 无 AbortController 仅 generation 丢弃（轻微，单请求） | B |
| A11 | P3 | src/domains/conversation/store.ts:176-182 | select() 内 readReceipt await 阻塞 detail→messages 链中段（失败被静默吞=非必要阻塞） | A |
| A12 | P3 | src/domains/conversation/store.ts:78-90 | 深链 `?conversation=` 消息首绘串 list→detail→messages 三跳，detail 按 id 直取可并行 | A |
| A15 | P3 | src/domains/setup/store.ts:85-88 | setupStore 无 ready 相位缓存，每次进 Today 重拉 readiness | A |
| A16 | P3 | src/pages/AuditPage.vue:86-88 | loadCorrelation 只依赖 URL chainId，却串在首批 Promise.all 之后 | A |
| B08 | P3 | src/domains/scope/store.ts:226-248 | loadMembers 无 in-flight 复用（对照 ensureTeams:136 已有模式），WorkPage 首帧双发 | B |
| B16 | P3 | src/components/domain/WorkProjectCreateDialog.vue:8-14 | checkKey prop 4 处传入 0 调用——key 预检链整条死掉（代号已改自动生成） | B |
| A30 | P3 | src/pages/TodayPage.vue:12 | BaseSkeleton 死 import（全站零骨架使用，见 §5 存疑 A31） | A |
| C01 | P3 | src/components/domain/CodingDiffExplorer.vue:190 | watch reviewComments deep 监听但回调只做真值判断，deep 冗余 | C |

## 3. 分路详述

### 3.1 路 A：路由与首屏加载（7 问题/19 保留/5 存疑）

- **懒加载与滚动**：29 条路由全部 `() => import` 单页单 chunk 无重复（A01）；scrollBehavior 四分支完备含 savedPosition/hash/query 保滚动（A02）。保留。
- **路由守卫零数据预取**（A03）：beforeEach 仅会话恢复/权限门/query 规范化，数据获取全部下沉页面 watch——简单可测，保留（预取机会见 §4 存疑）。
- **入口链干净**（A05/A06/A07）：零 @font-face 系统字体栈+内联主题脚本防 FOUC；首屏唯一强制网络请求是会话恢复，~40 store 构造零 IO；CommandPalette 数据推迟到 onOpen。保留。
- **页面获取模式统一**（A09）：watch immediate 驱动+store 缓存，无 onMounted 串行阻塞渲染；并行化纪律好（Conversation 三路 A10、Work 五路 A13、Today 三路 void 发射 A14、Operations/Agent/Model 均 Promise.all）。保留。
- 问题详述：A08（synchronize 无条件 `state.phase='loading'`——缓存全命中也翻相位，phase 门控页面每次换页闪 loading 面板）；A11（回执阻塞消息首绘——失败静默吞证明非必要）；A12（深链三跳串行）；A15（setup 无 ready 短路）；A16（correlation 可并入首批 Promise.all）；A21（**全站最重无 keep-alive 重复拉取点**：unmount reset 六 store+断 SSE，最重页 1413 行每次重进全量冷启）；A30（死 import）。
- 值得单列的保留：A17（Search loadedQueryKey 防双发+250ms 防抖）、A22/A23（Work/Agent/Model 重进入 ready/queryKey 短路零重拉）、A32（非首载刷新一律 stale-while-refresh 不闪 loading）。

### 3.2 路 B：数据获取与请求模式（5 问题/26 保留/6 存疑）

- **激活链无假串行**（B01-B07）：scope 三跳有真实数据依赖且双缓存；各页激活后 Promise.all/fan-out 并行；scope 变更=epoch+abort+清缓存三连是正确语义（防跨 Team 数据泄漏）。
- **分页全覆盖**（B24）：全部 list 端点有 cursor/keyset/offset（页大小 50），零「拉全部再前端分页」；WorkPage 筛选排序全走 URL query→服务端（B25），前端零过滤。
- **轮询单源收敛**（B17-B21）：useAutoRefresh 唯一实现+pauseWhenHidden 内建+active 闸（scope/online/开关），4 消费者无裸 setInterval 绕行；待索引徽章轮询全绿自然停摆（B20）；task 工作台是 SSE 事件驱动 350ms 合并重读而非轮询（B22）。
- **实时通道**（B30/B31）：Activity SSE ingest+durable cursor 断线补发；collaboration WS resource_changed→stale-while-refresh 重读第一页+acknowledgeVersion 回声去重；批量行命令逐行串行是注释明确的设计决策、批末一次重拉、零执行零重拉。
- **竞态防护双保险**（B38/B42/B43）：requestScope channel 级 AbortController+epoch+坐标 capture 基建完备，主力 store 全部接入——缺口只在 workitem/scope/workdesk 三处（B39-B41 问题）。
- 问题详述：B08（loadMembers 缺 in-flight promise 复用——同文件 ensureTeams:136-147 已有现成模式可照抄）；B16（checkKey 僵尸接口链：4 处传入 0 调用+scope store checkWorkProjectKey 死链）。
- **纠错登记**：任务背景曾按字面 grep 判「debounce 仅 2 处」，实测实时输入去抖四处（SearchPage 250ms/CommandPalette 180ms/PrincipalPicker 200ms/SettingsFieldSearch 250ms）+ 提交式搜索——无每次击键请求场景。本文以实测为准。

### 3.3 路 C：渲染性能（主会话压缩完成：1 问题/8 保留/2 存疑）

- **虚拟化覆盖**：useVirtualList 仅 ConversationPage（唯一无界列表：消息流）。其余列表（Activity/Audit/Inbox/Knowledge/Skill/WorkBoard）全部游标 50 有界+手动 loadMore（路 B B24 佐证）——有界不虚拟化是合理形态，保留；用户连点多页后的千行级 DOM 登记存疑。
- **deep watch 13 处逐处定性**（主会话复核）：usePreference/useScopedUserState 2 处为持久化写入（对象小、必要）；表单快照同步族 9 处（ResponsibilityPanel ×2/ModelBindingSection ×2/PreferencesSection ×2/AgentConfigurationPanel/ContentEditor/LarkNotificationAdmin——对象为表单规模，可接受）；AuditExplorer:143 props 同步（小对象）。仅 CodingDiffExplorer:190 deep 冗余（C01 问题）。
- **动画卫生**：`transition: all` 全库零命中；@keyframes 仅 7 处；prefers-reduced-motion 14 处覆盖。保留。
- **computed 卫生**：computed 内副作用反模式零命中。保留。
- **时间刷新成本**：RelativeTime 19 文件使用，每实例 60s tick 仅本地 Date.now 更新（无网络）且有 document.hidden 守卫。保留。
- **模板内格式化**：toLocaleString 家族在模板直接调用 22 处（图表/恢复面板/编辑器/workbench 等）——组件每次重渲染重算，无响应式缓存。频率低收益小，登记存疑（机械可修但不值专项）。

### 3.4 路 D：构建产物与资源（2 问题/13 保留/6 存疑）

- **main 归因**（D01）：main.ts:11-74 急切 import 安装约 28 个域的 HttpXxxGateway+installXxxStore+apiClient+actionRegistry+CommandPalette——observability 仅 ObservabilityPage 消费、quota 仅 QuotaSettingsPage、teamobserver 仅 TeamObserverWorkspace、setup 仅 Setup/Onboarding，却全进首屏。改法：单消费域 install 搬进页面模块（页面入口先 install 再 inject），跨切域（identity/scope/conversation/collaboration/task）保留 main。
- **140K 共享块正名**（D02）：useScopedUserState chunk 不是那个 2,904B 组合函数——是 Rollup 共享桶（markdown-it 119,092B+dompurify 29,828B+多页共享组件），SafeMarkdown 被 4 页消费，放置正确。
- **WorkPage 350K+160K css 归因**（D03）：Work 台全家桶（双 Drawer/双 Workbench/DiffExplorer/Delegate 等 48 模块）+prism 核心 58K，15 门语言已按需动态 import——业务密度所致非捆绑事故。
- **零浪费证据**（D06/D07/D08/D12）：跨 chunk 模块零重复；openapi.ts 148,929B 纯类型完全摇树；token 单点定义零内联零跨 chunk 重复；fixture/演示数据零泄漏。
- **预算门禁**（D19/D20）：单 chunk ≤512K/总量 ≤2M 接入 CI；main 87.1%/总量 92.66% 双贴线——D01 是最直接减压阀。
- **配置机会登记**（D21-D24，只登记不实施）：build.target 未显式设、modulePreload polyfill 可省 801B、cssCodeSplit 已生效无需动、chunkSizeWarningLimit 可降至 350K 提前亮牌。

## 4. 存疑登记（需实测或产品裁定，共 19 条）

| 编号 | 主题 | 取舍点 |
| --- | --- | --- |
| A04 | manualChunks 现仅 vue/vue-router | icons/markdown 桶与路由预取需 A/B 构建实测（联动 D09/D25） |
| A24 | Inbox/Audit/Activity 进门必 force 刷新 | 「进门必新鲜」是产品语义，改缓存需裁定 |
| A25 | keep-alive 全库零使用 | 先做 A21 缓存保留，收益不够再评估 |
| A29 | collaboration WS 引用归零不断连 | 有意设计（注释在案）；闲置保活成本需实测 |
| A31 | 全站零内容形骨架（loading 皆 spinner 面板） | 感知性能取舍；BaseSkeleton 原语已备未用 |
| B19 | GitHub 导入 1.2s 轮询间隔 | 无量化依据，实测后可放宽 |
| B27 | workgraph DAG 串行翻页至 10 页×100 | 近上限时延时可感；并发窗口或服务端聚合 |
| B28 | publishedKeys 拉全 500 上限 | Skill 常态 >200 需服务端搜索 |
| B33 | 成员命令后全量重拉成员表 | 量小（<100 行）可接受 |
| B36 | 待索引徽章轮询整页强刷 | 需后端轻量状态端点配合 |
| B37 | Inbox 无 realtime 跨成员刷新 | 实时性要求需产品确认 |
| C-1 | 模板内格式化 22 处 | 收益小不值专项，顺手修 |
| C-2 | 有界列表连点多页后 DOM 增长 | 千行级实测后再定 |
| D03′ | Work 台双 Drawer defineAsyncComponent | 收益需实测 |
| D09 | 61 个 <2KB lucide 单图标 chunk（39,216B 合计） | icons 桶会改首屏集合，需 A/B |
| D11 | WorkPage css 159K 拆分粒度 | 随 D03′ 一并实测 |
| D14 | Inter 列字体栈首位但永不加载 | 删或真正引入子集，二选一 |
| D17 | spike 产物 51,342B 随产线 dist 发布 | e2e 直航所需；改走 dev 管线是构建工程活 |
| D25 | icons/markdown 桶 manualChunks 重划 | 与 A04/D09 同源，A/B 构建后定 |

## 5. 做得好应保留（正面基线）

1. **懒加载全覆盖**：29/29 路由单页单 chunk；页面专属代码归 chunk 干净（跨 chunk 零重复）。
2. **首屏关键路径极小**：唯一强制网络请求是会话恢复；无 webfont；内联主题脚本防 FOUC；命令面板数据推迟到打开。
3. **分页纪律**：全部 list 端点 cursor/keyset/offset（50/页），零全量拉取；Work 筛选排序全服务端。
4. **取消与竞态基建**：requestScope（channel AbortController+epoch+坐标）+ 全 store 版本门；SSE/WS 断流 abort、cursor 续读、410 自愈。
5. **轮询单源**：useAutoRefresh 唯一实现+隐藏暂停+active 三重闸；徽章轮询自然停摆；SSE 驱动合并重读替代轮询。
6. **增量优先**：Activity SSE ingest、collaboration resource_changed 版本去重、stale-while-refresh 刷新不闪 loading 不清屏。
7. **去抖全覆盖**：实时输入四处 180-250ms 去抖或提交式；Search loadedQueryKey 防双发。
8. **构建卫生**：vendor 手工限定 vue+vue-router；markdown/dompurify/prism 按消费图落位（语言按需动态）；生成代码完全摇树；token 零内联；预算门禁在 CI。
9. **渲染卫生**：transition:all 零、keyframes 仅 7、reduced-motion 14 处覆盖、computed 零副作用、deep watch 全部可辩护（12/13）。
10. **重进入短路**：Work/Agent/Model ready/queryKey 缓存短路零重拉（Conversation 是唯一反例=A21）。

## 6. 计划承接建议（M13d 工作包草案，供计划冻结时裁定）

> **2026-10-24 回填**：P01–P05 已全量实施交付，实施记录与架构决策见 [M13d 计划](../plans/M13d-性能与加载收口.md)；main chunk 445.9 KB → 109.1 KB（−75.5%）。路 C 模板内格式化 22 处未做（独立裁定）。

| 包 | 内容 | 预估 |
| --- | --- | --- |
| P01 main 减压 | D01 单消费域 install 搬页面模块（逐域验证 DI 时序）+ D20 预算复跑 + 顺手 D22/D24 配置项 | 中 |
| P02 路由切换缓存 | A21 ConversationPage 只断订阅不清缓存（或定向 keep-alive）+ A08 synchronize 缓存命中不翻 loading + A15/A16 小并行/短路 | 中 |
| P03 取消接线 | B39/B40/B41 store 调用点接 signal（照 conversation 模式）+ B08 in-flight 复用（照 ensureTeams 模式） | 小 |
| P04 消息首绘链 | A11 回执 fire-and-forget + A12 深链 detail 与 list 并行 | 小 |
| P05 清理与验证 | B16 死链删除、A30 死 import、C01 冗余 deep + 全量验证（vitest/e2e/七门禁/bundle 预算）+ 快照归因 | 小 |
| 存疑池 | §4 的 19 条按需实测后逐条裁定制单 | — |

整改红线：取消/竞态/去抖等机制语义不弱化（只接线不重造）；stale-while-refresh 与「进门必新鲜」（A24）产品语义不动；实时通道建连/退避/degraded 分级不动；预算门禁阈值不放宽（D20 靠 D01 解压不靠提额）。

## 7. 走查覆盖与边界

- 覆盖：路 A 全读 router.ts+入口链+10 代表页面获取序列+SSE/WS 建连时机；路 B 全扫 8 域 store 激活链+重复请求+去抖+轮询+分页+写后读+取消竞态；路 D dist 全量归因（chunk 字面量定位/重复检测/gzip 表/门禁余量）+资源清单。全部结论带 file:line 或命令输出证据。
- **路 C 压缩覆盖**（API 限额中断后主会话补救）：deep watch 13 处逐处、动画/reduced-motion/computed 副作用/格式化/RelativeTime/虚拟化覆盖定性完成；**未做**大组件模板逐行渲染成本分析（WorkPage 二维循环复杂度仅定性）与 Chrome DevTools 实机渲染 profiling——若 M13d 冻结需补，应作为 P05 的一部分或单独实测量测。
- 未覆盖：后端接口延迟与 SQL 面（M13 后端轴范围）；真实网络下的瀑布实测（本文全部静态取证）；移动端低端机 profiling；Service Worker/HTTP 缓存策略（部署面）。
- 与前轮分工：M13 结构/M13b 体验/M13c 文案均不动，本文编号 A/B/C/D+路内序号独立。
