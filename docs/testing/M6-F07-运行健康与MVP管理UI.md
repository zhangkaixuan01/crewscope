# M6-F07 运行健康与 MVP 管理 UI 验证记录

## 1. 交付范围

M6-F07 交付独立 `/operations` Control Mode 页面。当前 Team 成员读取 Projection、Outbox、Dead Letter、Cursor、Notification 五组件低基数摘要；管理员在同页读取 Organization Projection 诊断、三类 Recovery Candidate，并执行影子重建、验证、代际切换、取消和失败命令。页面同时提供 Activity、Inbox、Team Observer、Audit、Lark/Notification 的权限过滤演示证据入口。

证据入口表达可核验位置，不生成静态“已通过”结论，也不伪造完整 MVP 执行结果。

## 2. 权限、DTO 与命令边界

- 路由只要求 `scope:read`，普通成员可以查看 Team Scope 健康；只有 `operations:manage` 主体才请求和展示 Organization Diagnostics。服务端 Organization Administrator 校验保持最终授权边界；
- 健康 Mapper 要求五个固定组件各出现一次，Health 只接受 `HEALTHY / DEGRADED / ATTENTION_REQUIRED / UNAVAILABLE`，所有计数为非负安全整数；
- Projection Name、Generation/Rebuild 状态、UUID、版本、FailureCode 与时间采用闭集解析；Shadow Generation、Status、Version、RebuildJob ID 和 Version 必须同时出现或同时缺失；
- Recovery 响应中的 `action/referenceHash/confirmation` 只服务展示与强确认。Gateway 按 Candidate Type 重新构造请求 Target，避免严格后端闭集拒绝响应辅助字段；
- Projection Command Body 只从当前诊断返回的 Definition、Pointer、Active/Shadow Generation、Job 和 Version 坐标构造。动作没有服务端确认短语时不显示，不从状态或名称猜测短语；
- 强确认模态要求逐字输入服务端短语。每次打开新命令创建新的 Idempotency-Key，同一未改变输入的传输重试复用原 Key；成功或冲突后回读健康与诊断，不自动重放旧命令；确认输入、FailureCode 和 Idempotency-Key 不进入 URL 或持久状态；
- Team Scope 切换由 TeamOps Store Abort 旧请求、递增 Generation 并清除 Health、Diagnostics、Command 与模态坐标，旧响应无法污染新 Team。

## 3. 刷新、离线与可访问性

- 在线且开启时每 15 秒回读健康与管理员诊断，同时保留显式刷新；离线暂停定时器、保留最后事实并关闭所有写命令；
- 首屏 Loading/Error 使用稳定 StatePanel；已有摘要刷新失败时保留缓存并显示非阻断状态，诊断失败时关闭管理员命令；
- 强确认模态打开后聚焦 Heading，Tab/Shift+Tab 保持在模态内，Escape 关闭并恢复触发器焦点；Pending 期间禁止关闭和重复提交；
- Desktop 使用五列健康卡和并排 Generation，Narrow 使用单列/双列降级和纵向 Generation；两种视口共用语义 DOM，服从 Reduced Motion；
- Axe WCAG 2.2 AA、双视口视觉和键盘强确认流程通过。

## 4. 自动验证

主要命令：

```bash
pnpm --dir crewscope-web test
pnpm --dir crewscope-web build
pnpm --dir crewscope-web story:build
pnpm --dir crewscope-web test:e2e
pnpm --dir crewscope-web check:sensitive
```

M6-F07 专项验证：

- Gateway 与 Component Vitest `32 / 32`，覆盖 DTO 白名单、五组件/状态/坐标失败关闭、Recovery Target 重构、成员/管理员分层、精确确认、命令强坐标、离线缓存与焦点恢复；
- Playwright Desktop/Narrow `2 / 2`，覆盖五组件、管理员 Projection、权限证据入口、精确确认、幂等键、命令回读、Axe 与两张视觉基线；
- Production Build 通过；Histoire Build `14` 个 Story、`85` 个 Variant，新增 Member Health、Administrator、Offline Cached、Loading、Empty Recovery 五种状态；
- 全量 Vitest `409 / 409`、全量 Playwright/视觉/Axe `174 / 174`，Web 敏感字段门禁通过，覆盖 `20` 个生产文件与 `14` 个 Story。

视觉基线：

- `crewscope-web/e2e/m6-operations.spec.ts-snapshots/m6-operations-desktop-chromium.png`
- `crewscope-web/e2e/m6-operations.spec.ts-snapshots/m6-operations-narrow-chromium.png`

## 5. 结论

M6-F07 完成。CrewScope 已具备成员可见的低基数运行健康、管理员强确认 Projection/Recovery 管理和不伪造结论的一键 MVP 证据入口。下一任务为 `M6-F08`。
