# M11-F02 WorkGraph 可视化前端验证

> 交付日期：2026-10-11。三笔提交：C1 机制层（`ef1a9d2` domains/workgraph 五文件 + main.ts 组装）、C2 集成层（`2940021` 4 组件 + 接线 + e2e + 基线）、C3 文档 + 真实栈 + 全量门禁。零后端改动，全部消费 [M11-WorkGraphAPI契约](../api/M11-WorkGraphAPI契约.md) 的六个端点。

## 验收矩阵（主计划 :122 原文）

| 验收 | 证据 |
|---|---|
| 图在 500 节点下可交互 | `layout.ts` `GRAPH_NODE_LIMIT=500`；≤500 且宽屏走手写 SVG Canvas（无图形库，静态渲染 + viewBox 缩放）；`WorkGraphWorkspace.spec.ts`「degrades to the hierarchy list beyond 500 nodes」用 501 行证明阈值边界；`WorkGraphCanvas.spec.ts` 5 用例锚定选中/键盘缩放（`+/-/0`，钳制 [0.5, 2.5]）与 sr-only 等价清单 |
| 键盘可完成关系的添加与移除 | 面板表单为原生 `select` + `BaseButton`（Tab 天然可达）；节点为 focusable `role=button` 组，Enter/Space 选中；移除为两击武装（transition-confirm 先例）。`WorkItemRelationsPanel.spec.ts` 7 用例：三句式提交方向（BLOCKS 双向 / SPLIT_FROM）、SPLIT_FROM 第二来源 UI 短路、两击移除第二击才发 DELETE |
| 图视图有非图形的等价表达（可访问性） | Canvas 下方 sr-only `ul[aria-label="依赖图节点清单"]`，每节点一行「{标题},{状态},被 {N} 项阻塞」；超限降级的分层列表本身就是完全等价表达。e2e 断言清单文本逐行可见 |
| 390px 下降级为列表 | 767px `matchMedia`（jsdom 无实现时按宽屏，窄屏由 e2e narrow 390×844 覆盖）：narrow 项目断言 `.graph-canvas` 数量为 0、分层列表可见；聚焦子图在窄屏仍保持列表 +「返回全图」（`WorkGraphWorkspace.spec.ts` e2e 同断言） |
| Axe 与视觉门禁通过 | e2e graph 视图四 tag axe 扫描通过（含 `role=group` 修正：svg 内嵌交互节点时 `role=img` 是 nested-interactive serious 违规）；4 张新 darwin 基线 + 既有全量基线通过（见基线策略） |

## 关键实现事实

- **图数据 = 活跃阻塞 DAG**（拍板 1）：`GET work-graph/blocked-work-items` 分页累积（limit 100 × 翻页，`MAX_PAGES=10` 硬停，触顶显式上报「图规模已达读取上限」）。节点 = 被阻塞行 + 其 blocker 窗口成员；边 = BLOCKS。SPLIT_FROM 谱系与邻接事实在选中项的关系面板（relations 端点）呈现，不混入 DAG。
- **规模路由（ADR-033 阈值）**：`showCanvas = (!exceedsLimit || focusCenterId !== null) && !narrow` —— 聚焦子图（深度 ≤2、节点 ≤200）永远回图形态，手机聚焦仍为列表 +「返回全图」。
- **永不提交 DEPENDS_ON**：面板三句式只发 BLOCKS / SPLIT_FROM；DEPENDS_ON 的等价性由面板注记文案解释。
- **状态着色收敛**：`workItemStatusTone` 单一定义替换 WorkItemCard / DetailDrawer / WorkDeskBoardCard 三份散落拷贝（映射值不变，零像素差）。
- **无 store 渲染安全**：组件 `inject(KEY, null)` —— drawer 独立挂载（旧测试）无 store 时呈现空态不报错。
- **命令幂等**：create/remove 走 `secureId()` 幂等键（`api/commandGateway.ts` 指纹缓存，uncertain 重试复用同 key）；关系命令无 If-Match（信封 version 0 描述边而非端点版本，A02 契约）。

## e2e（mock 层，app-shell.spec.ts）

- mock 架构：`workGraphRelations` 数组为唯一事实，blocked 行与邻接投影全从它派生，POST/DELETE 端点是唯一写者 —— 202 后回读自动看到新事实（模拟服务端行为）。
- 4 个新测试（desktop + narrow 双项目）：
  1. DAG 渲染 + 缩放 + 节点事实 + 手机列表形态；
  2. \>500 降级分层列表 + 行聚焦回图（desktop 进子图画布，narrow 留列表带「返回全图」）；
  3. 键盘添加关系（selectOption + 添加按钮 → 三句式 → 徽标出现）；
  4. 两击移除（武装态文本变化按 class locator 断言）→ 徽标消失。
- 全量 app-shell：**210/210 通过**。

## 基线策略（对拍板 3 的执行偏差）

拍板 3 预估更新 6 张 darwin 旧基线（work-list / work-board / work-board-collaboration × desktop/narrow）。实际：工具栏第三按钮「依赖图」的像素差异远小于 `maxDiffPixelRatio 0.02` 容差，**全部旧基线自动通过，git 无任何旧基线改动**——不需要更新。新增 4 张 darwin 基线：`work-graph`（desktop/narrow）、`work-graph-node-detail`（desktop）、`work-graph-degraded`（desktop）。

## 偏差记录

| 偏差 | 理由 |
|---|---|
| 入口为 WorkPage 第三视图 `view=graph`，而非主计划 §10.4 预估的新增 WorkGraph 页面（拍板 2） | 权限、导航、390px 骨架全继承既有 WorkPage；与 list/board 集合视图同壳切换，零新路由；对 §10.4 已作偏差声明 |
| 旧基线未更新（拍板 3 预估 6 张） | 差异在 2% 容差内自动通过；基线保持产品视觉真相不引入无意义重写 |

## 真实栈（`scripts/m11-f02-real-stack-gate.sh`，18089）

四服务 team-beta 栈（amd64 模拟，同 I01c 形态），真实 OPEN 注册 → 命令 API 建项目 + 3 工作项 + 2 BLOCKS + 1 SPLIT_FROM → `e2e/m11-real/workgraph.spec.ts` 5 用例：

1. 图视图渲染真实 DAG（3 节点 2 边 + sr-only 清单逐项断言）；
2. 关系面板以真实边陈述句子（「链头」阻塞本项 / 本项阻塞「链尾」）；
3. 真实 409 环拒绝（tail BLOCKS head 成环 → 契约文案「这条关系会形成循环阻塞，无法创建。」）→ 合法边添加 → 整页 reload 后句子仍在（持久化事实非乐观幽灵）→ 两击移除；
4. SPLIT_FROM 第二来源表单短路（option disabled + 「本项已有拆分来源」，不发请求）；
5. 列表徽标「被 1 项阻塞」×2（聚合权威值）。

结果：**5/5 通过**（2026-10-11，`CREWSCOPE_M11F02_PHASES=up,specs ./scripts/m11-f02-real-stack-gate.sh`，specs 14.7s；镜像 build 阶段同日先跑）。两处测试自身修正（非产品缺陷）：marker 中文对齐（waitForListItem 以标题子串定位）；原生 `<option disabled>` 在 chromium 的可访问性状态读取怪癖改以属性+文案断言（`toBeDisabled` 对 option 报 enabled 而 attribute 存在，快照已证属性与文案正确）。

## 门禁

- vitest：245 文件 / 1685 测试通过（22 个新组件测试）。
- `vue-tsc --noEmit` 零错；eslint 零错。
- `check-module-boundaries` / `check-web-enum-labels` / `check-design-tokens` / `check-web-bundle-budget`（最大 chunk 420372 bytes）全部 PASS。
- 真实栈 gate：见上节。
