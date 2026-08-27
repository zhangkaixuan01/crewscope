# M6-F03 我的 Inbox UI

> 状态：已完成<br>
> 日期：2026-08-27<br>
> 模块：`crewscope-web`

## 1. 交付结果

M6-F03 将 M6-A02 的成员专属 Inbox 投影交付到传统管理入口：

- `/inbox` 提供 `OWNERSHIP`、`EXECUTION`、`REVIEW`、`CONFIRMATION`、`EXCEPTION` 五类视图；
- 页面直接展示服务端总数、未读数、分类型计数、优先级、截止时间、来源 Revision 和成员处置版本；
- Source Status 与 Disposition 使用闭集筛选，历史分页消费不透明 Cursor 并按 InboxItem ID 去重；
- `inboxItem` Query 控制详情，Team Scope 切换清除旧 Inbox 深链接；
- AppShell 提供“我的 Inbox”真实导航，Histoire 提供 Ready、Detail、Conflict、Loading、Empty、Forbidden、Offline 和 Cursor Expired 状态；
- Playwright 新增 Desktop 与 390×844 Narrow 视觉基线。

## 2. 成员、计数与来源安全边界

浏览器不提交 Member ID。服务端根据当前 Principal 和 Team Membership 解析唯一活动成员；前端 Store 使用 Organization + Team 作为缓存和请求代次边界。五类总数和未读数完全消费服务端计数 API，不扫描当前列表反向推导。计数请求加载或失败时页面分别显示同步中或不可用，未知计数不会显示为权威零值。

来源跳转不从 Inbox 公开事实自行拼接。页面按 InboxItem ID 调用 Target API，Gateway 只接受 `/work` 和 `/settings/integrations` 站内路由，并拒绝外部 URL、协议相对 URL、Fragment 和未批准路径。离线时已加载公开事实保持可读，来源解析与处置命令保持关闭。

## 3. 强版本、幂等与恢复

Inbox 详情要求 HTTP Header 强 ETag、Body ETag 与 Disposition Version 完全一致。`READ/ACTED/ARCHIVED` 命令只提交新状态并携带当前 `If-Match` 与 Idempotency-Key：

- 可重试传输失败保留原命令与 Idempotency-Key，供成员安全重试；
- 409 冲突回读当前列表、服务端计数和强版本详情；
- 回读后的重新确认属于新命令并生成新的 Idempotency-Key；
- 成功后回读列表、计数和详情，刷新页面继续显示服务端持久化处置；
- 来源投影重建与成员处置分离，页面只消费合并后的当前 Generation 事实，不用本地状态掩盖重建结果。

页面覆盖 Loading、Empty、Error、Forbidden、Offline、CursorExpired、Conflict 和计数局部失败。详情打开后焦点进入标题；视图、筛选、详情、来源与处置均支持键盘。Desktop 使用列表/详情双列，Narrow 使用横向五类视图与详情优先单列。

## 4. 验证

验证命令：

```bash
cd crewscope-web
pnpm exec vitest run \
  src/components/domain/InboxWorkspace.spec.ts \
  src/domains/teamops/gateway.spec.ts \
  src/domains/teamops/store.spec.ts
pnpm exec playwright test e2e/m6-inbox.spec.ts
pnpm test
pnpm build
pnpm story:build
pnpm check:sensitive
pnpm test:e2e
cd ..
node scripts/check-doc-links.mjs
git diff --check
```

验证结果：

- Inbox 专项 Vitest：3 个测试文件、25 项测试通过；
- Vitest 全量：77 个测试文件、359 项测试通过；
- Inbox Playwright：Desktop/Narrow 共 6 项通过，覆盖五类视图、服务端计数、Cursor 重叠去重、强 ETag 冲突、Idempotency-Key 语义、刷新持久化、Offline、键盘、授权来源、Axe 与视觉；
- Playwright 全量：160 项通过；
- Histoire：10 个 Story、56 个 Variant 构建通过；
- TypeScript 与 Vite 生产构建通过；
- Web 敏感字段检查：20 个生产文件、10 个 Story 通过；
- Markdown 链接和 `git diff --check` 通过。

新增视觉基线：

- `m6-inbox-desktop-chromium.png`；
- `m6-inbox-narrow-chromium.png`。

下一任务为 `M6-F04`。
