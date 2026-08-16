# M3-F02：Control Mode Task 列表与委托入口

## 目标

在 Work 页交付团队可观测的 Agent Task 列表，并把 WorkItem 详情中的委托入口接入 M3 耐久 Task Runtime。

## 已交付能力

- Control Mode 展示 Task 状态、Owner、当前 attempt、TaskExecution 状态和等待原因；
- `taskStatus` 和 `taskOwner` 保存在 URL，Owner 筛选由服务端在完整 Cursor 集合上执行；
- Task Cursor v3 绑定 Organization、Team、WorkProject、TaskStatus 和 Owner Principal；
- Task 列表通过责任快照联表批量返回 Owner Principal，列表查询保持单次分页读取；
- WorkItem 责任查询为 Personal/Team Agent Executor 返回当前 ACTIVE `actorAgentProfileId`；
- 委托对话框预览 Owner 与 Agent Executor，确认执行目标和有序验收标准；
- Task 创建传递强 `If-Match`、`Idempotency-Key`、服务端 AgentProfile ID 和空 ProviderBinding 集合；
- 可重试失败保留原命令与同一幂等键，成功后从受权 WorkItem→Task 关联查询恢复 Task ID；
- 创建成功刷新 Task 列表和关联缓存，并恢复 `/work?...&workItem=<id>&task=<id>` 深链接；
- 委托入口只对当前 Owner/Executor 且具备 Work Participate 权限的成员展示，服务端继续执行最终授权；
- 委托对话框打开后将初始焦点移入当前 Modal，Tab/Shift+Tab 只在最上层可用控件间循环，Escape 不会传播给背景 WorkItem 抽屉；
- Loading、Empty、Error、无 Agent Executor、桌面和窄屏状态全部有明确界面。

## 契约补强

### Task 列表

`GET /api/v1/organizations/{organizationId}/teams/{teamId}/tasks` 增加可选 `ownerPrincipalId`，响应摘要增加 `ownerPrincipalId`。该值来自 Task 创建时的责任快照，保留 Task 执行时的责任证据。

### WorkItem 责任

`GET .../work-items/{workItemId}/responsibilities` 增加 `actorAgentProfileId`。字段只在 Agent 责任具有同 Team、同 Workspace 的 ACTIVE AgentProfile 时返回。

## 安全边界

- Web 类型只保留 Task 公开摘要和 AgentProfile 产品标识；
- Claim Token、Task Token、JTI Hash、Credential、Fencing Token、原始 AgentState 和 Reasoning 不进入页面状态；
- Task ID 由创建后的受权查询恢复，浏览器不生成领域身份；
- 非可重试错误清除幂等重试上下文，新提交使用新命令身份。

## 验证

- Java 应用/API 专项：21 个测试通过；
- 真实 PostgreSQL：Task Owner 联表筛选与 AgentProfile Principal 反查 2 个专项测试通过；
- Java 全量 `clean verify`：7 个 Maven 模块、1,044 项测试通过，包含 AgentScope、Docker、Redis 和 PostgreSQL 集成链路；
- Vitest：42 个文件、180 项测试通过，其中委托对话框 4 项专项覆盖责任预览、AgentProfile 身份、失败关闭、初始焦点与键盘循环；
- `pnpm build` 通过 Vue TypeScript 与 Vite 生产构建；
- Playwright 全量：桌面/窄屏 102 项通过；其中 6 条 M3-F02 主链覆盖创建、同键重试、列表刷新、筛选、深链接和水平溢出；
- Axe 桌面/窄屏 WCAG 2.2 AA 检查通过，Work 页 Task 等待标记具有 AA 对比度；
- Vitest 覆盖委托 Modal 初始焦点、Tab/Shift+Tab 循环和 Escape 单层关闭；
- Work 列表、看板和详情的桌面/窄屏视觉基线已更新并通过；
- 133 份 Markdown 文档链接和 `git diff --check` 通过。

## 下一项

`M3-F03` 交付 Task 详情抽屉/页面，展示责任快照、当前与历史 attempt、Plan、Step、AgentRun、Lease 和 Runtime 安全事实。
