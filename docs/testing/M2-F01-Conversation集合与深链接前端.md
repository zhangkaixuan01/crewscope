# M2-F01：Conversation 集合与深链接前端

> 日期：2026-08-11<br>
> 状态：已完成<br>
> 工程：`crewscope-web`

## 目标

把 M2-A01 Conversation 创建、集合和详情 API 接入 Web，替换 M1 的 Conversation 蓝图数据，形成可刷新恢复、可切换 Team、可在桌面和窄屏使用的真实会话入口。

## 交付范围

### Gateway 与类型契约

- 接入 Team Scope 下的 Conversation 集合、详情与创建 API；
- 集合固定查询 ACTIVE Conversation，支持不透明 `after` Cursor、有限 `limit` 和请求取消；
- 详情返回 Conversation 与当前 Participant，不补造客户端参与者；
- 创建请求只包含 `title` 与 `visibility`，每次命令使用独立 `Idempotency-Key`；
- Owner、Personal Agent、Workspace、Conversation ID 和审计事实全部读取服务端响应。

### Store 与竞态隔离

- Collection 和 Detail 使用独立 Phase、错误、AbortController 与请求版本；
- Scope Key 由 Organization 与 Team 组成，Team 变化立即取消旧集合和详情请求；
- 同步版本阻止 Scope 规范化期间的旧 Conversation 深链接在 Query 已清除后重新选中；
- Cursor 续页按 Conversation ID 去重，并保留当前集合；
- 创建成功后强制重载集合，优先选择标题相同的新增服务端事实；
- Store 不生成临时 Conversation ID，不根据 CommandReceipt 推断业务对象。

### 页面与 URL

- 使用 `team`、`project` 和 `conversation` Query 保存稳定 Scope 与选中对象；
- 深链接和页面刷新恢复 Conversation 详情及 ACTIVE Participant；
- Team 切换清除不兼容的 `conversation` Query；
- 桌面显示会话列表、详情和 Participant 观察面；
- 窄屏在会话列表与详情间切换，并提供“返回对话列表”入口；
- PRIVATE 与 TEAM 使用不同图标和可见范围说明；
- 空态提供创建入口，错误态提供安全提示和重试入口；
- 前端路由权限拒绝与 API `403` 都进入 Access Denied 页面。

### 当前阶段边界

M2-F01 只展示真实 Conversation 与 Participant。Message 历史、Composer 和发送状态进入 M2-F02；AG-UI 流式回复与断线恢复进入 M2-F03；Clarification 与 TaskIntent 进入 M2-F04。页面不构造 Message、AgentRun、执行进度、工具结果、Diff 或 Artifact。

## 自动化验证

```bash
cd crewscope-web
pnpm test:coverage
pnpm build
pnpm story:build
pnpm test:e2e
```

当前结果：

- Vitest：15 个测试文件、66 个测试通过；M2-F01 新增 11 个测试，覆盖 Gateway 路由、Cursor 续页与去重、Idempotency-Key、深链接、空集合、创建后服务端事实恢复、Team 切换、同步竞态、请求取消和 Conversation 路由权限；
- Coverage：Statements 86.27%、Branches 80.11%、Functions 85.71%、Lines 87.74%，全部高于前端 Release Gate；
- TypeScript 检查与 Vite 生产构建通过；
- Histoire：4 个 Story、12 个 Variant 构建通过；
- Playwright：21 个场景在桌面 1440×960 与窄屏 390×844 共执行 42 次；M2-F01 新增 6 个场景并在两个视口覆盖加载、空态、创建、Scope 切换、深链接、刷新、API 403 和列表操作，既有 WorkItem 到 Conversation 跳转同步回归通过；
- Conversation 桌面与窄屏视觉基线已更新，Axe WCAG 2.2 AA 自动检查通过；
- 文档链接和 `git diff --check` 纳入阶段最终检查。

## 阶段边界

M2-F01 完成真实 Conversation 集合、创建与对象恢复。下一项 M2-F02 接入 A02 的 Message 历史、Cursor、Composer、幂等发送和安全 Markdown，不改变本阶段已经固定的 Team/Conversation Scope、权限与竞态边界。
