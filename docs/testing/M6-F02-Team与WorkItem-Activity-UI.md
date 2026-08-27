# M6-F02 Team 与 WorkItem Activity UI

> 状态：已完成<br>
> 日期：2026-08-27<br>
> 模块：`crewscope-web`

## 1. 交付结果

M6-F02 将 M6-A01 的公开 Activity 投影交付到传统管理入口：

- `/activity` 提供 Team Activity Stream、Category/Actor 筛选、历史分页与事件详情；
- WorkItem 详情在业务时间线后嵌入 Compact Activity Stream；
- `ActivityStream` 展示 EventType、Actor、Subject、Outcome、发生时间和类型化证据链接；
- AppShell 提供真实 Activity 导航，并在 Team Scope 切换时清除旧 Event 深链接；
- Histoire 新增 Activity 全状态 Story，Playwright 新增 Desktop 与 390×844 Narrow 视觉基线。

## 2. 实时恢复与一致性

`ActivityRealtimeStore` 先消费权威 Snapshot 的恢复坐标，再连接 Team SSE。恢复规则为：

- 耐久 Cursor 以 Organization + Team 分区保存；
- Scope 切换中止旧连接并递增传输代次，旧 Team 晚到响应和帧不能回写；
- SSE Activity 与 JSON Activity 使用同一公开 DTO Mapper；
- Activity Event ID 是浏览器去重身份，重复补发不会形成重复列表项；
- Activity 通过形状校验并成功合并后推进耐久 Cursor；格式错误的 JSON 或公开 DTO 不推进 Cursor；
- `410 cursor_expired` 清理旧 Cursor，页面显式回读 Snapshot 后从新坐标恢复；
- Offline 保留已加载事实，Connecting/Reconnecting 显示补发状态，Forbidden 和 Invalid Response 停止自动恢复并给出稳定状态。

## 3. 页面状态与交互

Activity Stream 覆盖 Loading、Empty、Error、Forbidden、Offline、CursorExpired、Connecting、Live 和 Reconnecting。离线、补发与局部错误期间保持缓存事实可读；没有缓存时使用独立 StatePanel。

证据链接使用当前 Team/WorkProject Query 构造站内深链接。WorkItem 与 Conversation 进入对应页面，其余引用进入 Activity 事件详情。链接、事件详情、关闭按钮和历史分页均可通过键盘操作。AppShell 顶栏使用单独命名的 Region，页面保留唯一 Banner Landmark。

Desktop 使用 Activity 与事件详情双列布局。Narrow 使用单列阅读顺序并提升触控目标高度。WorkItem 详情复用同一 Activity 组件的 Compact 模式和同一公开 DTO。

## 4. 验证

验证命令：

```bash
cd crewscope-web
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

- Vitest：76 个测试文件、343 项测试通过；
- M6 Activity Playwright：Desktop/Narrow 共 4 项通过，覆盖 Snapshot、SSE Resume、重复事件、Offline、键盘、Axe 与视觉；
- Playwright 全量：154 项通过；
- Histoire：9 个 Story、47 个 Variant 构建通过；
- TypeScript 与 Vite 生产构建通过；
- Web 敏感字段检查：20 个生产文件、9 个 Story 通过；
- Markdown 链接和 `git diff --check` 通过。

新增视觉基线：

- `m6-activity-desktop-chromium.png`；
- `m6-activity-narrow-chromium.png`。

下一任务为 `M6-F03`。
