# M12b-F05：store 层与透传治理完成记录

> 基线：F04 完成态（`M12b-F04-双胞胎与骨架消解完成.md`，vitest 1895 / e2e 675）。本包为纯前端（crewscope-web），后端零改动、零迁移、零 API 契约变更。
> 计划合同：[M12b 计划](../plans/M12b-前端体验与债务收口.md) §3 F05 行（§8 P4）与 §5 验收契约；Review §5 store 层与页面级重复（AbortController 手搓、三域 isAbort/statusOf/presentError、40/60+ props 透传抽屉、2074 行 WorkPage）。

## 1. 关闭对象与非目标

关闭 F05 行六件事：①AbortController×28 → `createRequestScope`；②gateway 工具（segment/pick/etagVersion）下沉共享；③isAbort/statusOf/presentError 三域合一；④GitHub/Lark 手搓 scope 迁 `usePageRequestScope`；⑤WorkPage/ConversationPage 拆 composable；⑥两抽屉订阅化去 props 透传。

非目标：

- 六个域文件不迁 createRequestScope（裁定见 §6）：task/conversation 六文件、coding、teamobserver、activityRealtime；
- on\* props 存量渐进策略照 F04 §6 裁定执行（抽屉保留的页面函数 props 是正当用途，见 §5）；
- 竞态语义（abort 后置位不写 UI）只对迁移触达面补齐 spec，不做全量补测。

## 2. C1：三函数合一与 gateway 工具下沉

- **`api/errors.ts` 单源**：`isAbort(error)` / `statusOf(error)` / `presentError(error, fallback)` 三函数收编 task/conversation/github 三域各自的手搓副本，语义以最严格一份为准（abort 判定含 AbortSignal 原因链、status 提取经 cause 链下钻）。
- **`api/gatewayUtils.ts`**：`segment(value)`（分段 trim/空判）、`pick(value, keys)`（DTO 白名单投影）、`etagVersion(etag, label)`（ETag → 版本号，含 label 前缀错误消息统一）三件工具从 conversation/task gateway 各自的本地副本下沉；**etagVersion 的错误消息统一为带 label 前缀的单一样式**（此前两域文案分叉）。自带 spec 3 用例。
- 特化 `presentError` 保留两处（裁定见 §6）：workgraph、scope、workItemLinkStore 的领域化错误展示不强行并轨。

## 3. C2/C3：store 层迁 createRequestScope + 页面级 AbortController 收敛

- **`createRequestScope` 消费者终态：41 个调用点、26 个消费者文件**（合同「≥28」以 Review 清单的 28 处手搓 AbortController 计，全部迁移；实际收敛后调用点多于 28 是因为同文件多作用域各自建 scope）。
- 逐域迁移语义钉死：请求前置 abort 旧作用域、错误分流走 `isAbort`（静默）、卸载即取消；store 侧不再手搓 `new AbortController()` + `controller.abort()` 对。
- **GitHub/Lark 手搓 scope 迁 `usePageRequestScope`**：两设置页各自的 AbortController 生命周期删除，统一页面级 scope；Login/Register/PrincipalPicker 的零散 abort 收敛 `createRequestScope`。
- **GitHub 不迁 usePageRequestScope 的理由**（裁定）：GitHub 设置页的请求全部由 `usePageRequestScope` 的页面级 scope 覆盖后，仍有一处 token 交换流是**跨导航存续的长事务**——页面卸载不应取消它，保留独立的 createRequestScope 作用域（裁定登记 §6）。

## 4. C4：WorkPage/ConversationPage 拆 composable

六个 composable 落地，**watch 顺序等价性纪律**：composable 内 watch 的注册位置 = 原 watch 群位置，保证与其他 watch 的相对顺序不变（F04 §8-1 教训的正面应用）。

| composable | 行数 | 收编内容 | 去向 |
| --- | --- | --- | --- |
| `useWorkTaskWorkbench` | 764 | WorkPage 的任务工作台全部投影+命令包装（53 符号） | WorkPage 调用，TaskDetailDrawer 经 workbench 对象订阅（§5） |
| `useWorkSavedViews` | 100 | 看板/列表视图保存与 query 协调 | WorkPage |
| `useCollaborationPresence` | 37 | scope 化 presence/typing 订阅（acquire/release/watch） | **WorkPage 与 ConversationPage 共用**（原 useWorkBoardPresence 31 行泛化合并后删除） |
| `useConversationReadingPosition` | 161 | 已读锚点/未读计数/滚动恢复/jumpToLatest | ConversationPage（调用插原 344 行 watch 群原位） |
| `useConversationParticipants` | 70 | principalNames/participantName/messageAuthor 等人名目录 | ConversationPage（调用插原 184 行位置） |

页面行数：**WorkPage 2074 → 1238**、**ConversationPage 1450 → 1260**。

## 5. C5：抽屉订阅化（props 下降 82→6、57→17）

### 5.1 TaskDetailDrawer：82 → 6 props

**架构裁定——composable 留页面 + 单 workbench 对象 props**。完整把 `useWorkTaskWorkbench` 挪进 drawer 有两个行为差异风险，裁定不做：

1. `stopLiveTasks` 泄漏：WorkPage 的 `watch(selectedTaskId → null → stopLiveTasks)` 与 access-denied 跳转路径属页面导航领土，drawer 内无人调用会造成实时任务订阅泄漏；
2. `taskForbidden` watch 的列表级 403 监测窗口随 watch 群挪动而消失。

故 composable 留页面（watch 群生命周期零变化），drawer 经单对象 props `workbench: TaskWorkbench`（`ReturnType<typeof useWorkTaskWorkbench>`）读取投影与命令包装；drawer 内部另起 `useTaskStore()/useReviewStore()` 直读详情/命令态。**保留的 6 个 props 全部是页面拥有物**：canGateReview/canConfirmDelivery（权限判定）、principals（候选名册）、canControl、pinnedResultReference（outcome 交接）。

别名层纪律：`props.workbench.X` 中嵌套 ComputedRef 不自动解包 → script 建 computed 别名（手动 `.value`）；函数别名用 rest 透传 `(...args: Parameters<TaskWorkbench['commandTask']>)` 保留实参个数（`toHaveBeenCalledWith` 严格比较）；模板零改动。

### 5.2 WorkItemDetailDrawer：57 → 17 props

- **内部化 34 个**：workStore 直读 19（detail/detailPhase/detailErrorMessage/detailCommand\*3/availability\*3/responsibility\*6/timeline\*5）+ agentStore 直读 4（agents.phase/errorMessage/loadingMore/hasMore=nextOffset!==null）+ linkStore 直读 3（phase/associations/errorMessage）+ workStore 方法直调 8（addComment/linkResource/replaceOwner/assign\*3/releaseResponsibility/loadTimelineMore——页面原本就是 store 方法直传，drawer 内直接调用同一方法）。
- **保留 17 个全是页面拥有物**：scope/canParticipate/canDelegate/canManageResponsibility（权限与上下文）、responsibilityCandidates/responsibilityAgentCandidates（页面名册）、parallelTasks/taskSelectionRequired + onOpenTask/onOpenOutcomeReference（跨页交接）、onRetry/onContentSaved/onTransition/onRetryAvailability/onRetryResponsibilityAgents/onLoadMoreResponsibilityAgents/onRetryAssociations（页面重试/加载编排——retryDetails 同时刷多资源、loadResponsibilityAgents 带 force/loadingMore 语义，属页面协调逻辑）。

### 5.3 spec 三面工厂模式

两份 drawer spec 同款改造：`props(overrides)` 工厂从平铺 props 改为三面——①伪造 store 对象（`reactive` state 切片 + 方法位放 `flat.onXxx` 的 vi.fn，`as unknown as XxxStore`，绕过 readonly 外壳）②workbench mock（`computed(() => ({phase, value, errorMessage}))` 资源包装，TaskDetailDrawer）③drawerProps（页面拥有物）。**用例调用形态与断言逐字不变**（overrides 键名保持历史 flat 名）。mountDrawer helper 统一 provide 三 store + 可选 provide/slots/attachTo。

## 6. 裁定登记

1. **task/conversation 六文件不迁 createRequestScope**：这六个文件的请求作用域与选中态/实时流生命周期强绑定（选中切换即取消、卸载由 store 自身管理），createRequestScope 的「每次调用建新 scope」语义与它们的「选中态作用域」模型不同——强行迁移要给 scope 加选中态联动，复杂度反超收益。
2. **coding/teamobserver/activityRealtime 不迁**：coding 域走 SSE 流式（无 abort 语义）；teamobserver/activityRealtime 是全局单例订阅（无页面生命周期）。
3. **GitHub 跨导航长事务保留独立 createRequestScope**（§3）。
4. **etagVersion 错误消息统一**（§2）：并轨后消息带 label 前缀，两域文案分叉消除——属有意变更，无 UI 断言引用旧文案。
5. **workgraph/scope/workItemLinkStore 特化 presentError 保留**：三处错误展示带领域上下文（资源类型/关系方向），不是通用 fallback 的重复。
6. **identity 的 timedSession/timed 包装器保留**：定时会话刷新的作用域管理是功能本体而非样板。
7. **fork 限额中断后串行补齐**：C1-C3 阶段两个并行 fork 因 API 限额（2026-10-18 21:48 重置）429 中断，剩余工作全部串行补齐——无并行引入的合并冲突，git 工作树单线。

## 7. 竞态语义 spec 补齐（随迁移）

- `domains/quota/store.spec.ts` +2：abort 后置位不写 UI（loading 竞态）；
- `pages/LoginPage.spec.ts` +1：登录中卸载/切换的 abort 后不走路由断言；
- `pages/RegisterPage.spec.ts` +1：同款注册流竞态；
- 连同 `api/gatewayUtils.spec.ts` 3 用例，vitest 1895 → **1902**（+7，精确对账）。

## 8. 教训（本批新增）

1. **TaskStore.state 是 readonly(DeepReadonly) 外壳**：spec 里 `createTaskStore()` 后直接赋值 state 字段被 Vue 拦截（`Set operation on key "..." failed: target is readonly`）——测试须伪造 store 对象（reactive 切片 + `as unknown as XxxStore`），而不是改真 store。
2. **对象 props 里嵌套 ComputedRef 不自动解包**：workbench 单对象 props 的消费侧必须手动 `.value`；别名层用显式类型标注（= 原 props 类型）保住模板零改动。
3. **`toHaveBeenCalledWith` 实参个数严格**（F04 §8-4 复现）：函数别名若显式展开三参会以 `(arg, undefined, undefined)` 调用——rest 透传 `(...args: Parameters<...>)` 才能保住单参断言。
4. **多行 mount 改造的尾部闭合**：`props({...}) })` 两层闭合改一层时只改头部会留语法残渣——esbuild 转译期报错，比测试失败更早暴露。

## 9. 测试证据（终态）

- vitest **1902/1902（271 文件）**：基线 1895 + 竞态 4 + gatewayUtils 3，TaskDetailDrawer 10/10、WorkItemDetailDrawer 17/17 重构后全绿；
- `pnpm check:quality`（vue-tsc + eslint + stylelint + web-quality）✓；`pnpm check:q01` ✓（四矩阵）；`pnpm build` ✓（bundle budget PASS）；
- 全量 e2e：**675 passed + 7 skipped 维持**（11.0m）；
- CI 独有四门禁 ✓：design-tokens（182 文件）/ enum-labels（42 豁免 0 新增）/ sensitive / bundle；doc-links ✓（486 文件）；
- 改动全貌（70 改动文件 +1262/−2547，另 8 个新文件 1194 行：5 composables 1132 + errors.ts/gatewayUtils.ts/gatewayUtils.spec.ts 62）：净删约 1285 行。

## 10. 验收计数（§5 契约逐条）

- **「createRequestScope 消费者 ≥28」**：终态 **41 个调用点、26 个消费者文件**（Review 清单 28 处手搓 AbortController 全部迁移；不迁清单与理由见 §6）；
- **「抽屉 props 数量下降值入档」**：TaskDetailDrawer **82 → 6**（−76）、WorkItemDetailDrawer **57 → 17**（−40），两抽屉合计 **−116**；
- **「竞态语义单测（abort 后置位不写 UI）随迁移补齐」**：quota 2 + Login 1 + Register 1（§7）。

## 11. 复跑入口

```sh
cd crewscope-web
pnpm lint && pnpm test && pnpm check:q01 && pnpm build
pnpm test:e2e                              # 675 维持
node ../scripts/check-design-tokens.mjs && pnpm check:enum-labels && pnpm check:sensitive && pnpm check:bundle
node ../scripts/check-doc-links.mjs
```
