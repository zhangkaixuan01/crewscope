# M2-F05 Conversation 与 WorkItem 双向跳转

## 交付范围

M2-F05 将 Conversation Mode 与 Control Mode 连接到同一个服务端关联事实。

- 新增只读 `ConversationWorkItemLinkGateway`，对应 M2-A07 的两个受权限保护查询；
- 新增共享 Store，使用资源键、请求版本和 AbortController 隔离 Conversation/WorkItem Scope；
- 本地 TaskIntent 确认成功或收到同级成员的确认事件后强制重读关联 API，不从 CommandReceipt 推断 WorkItem 身份；
- Conversation Mode 展示服务端返回的已确认 WorkItem Key、标题和状态；
- Control Mode 的 WorkItem 详情展示可发现的关联 Conversation，并与 Owner、Executor、Reviewer 责任链同屏；
- 双向跳转保留 Team、Project、Conversation、WorkItem 和 Focus 查询坐标；
- 页面刷新使用 URL 与服务端当前事实恢复，不依赖路由前的内存状态；
- 离开 Conversation 或 Work 路由时清理共享关联查询，重新进入后回读当前服务端事实；
- 被服务端策略过滤的 PRIVATE Conversation 不生成链接。

## 产品边界

Conversation 中的“查看执行”表示进入已创建的 Native WorkItem，不表示 Agent 已开始后台执行。WorkItem 中的“返回对话”只对可发现的 Conversation 出现。通用“与 Personal Agent 讨论”入口继续保留，用于进入 Conversation Mode 后选择或创建其他对话。

## 自动化验证

- Gateway 测试覆盖两个嵌套 Scope 路径和只读请求；
- Store 测试覆盖双向读取、空结果隐藏、`403` 保留和跨方向旧请求取消；
- 组件测试覆盖 WorkItem 入口、Conversation 返回、空事实和安全错误重试；
- Playwright 在桌面与窄屏覆盖确认后回读、跳转、双向返回、责任事实、查询坐标和刷新恢复；
- Conversation 视觉基线增加浅绿色“已确认工作项”卡；
- Histoire 增加 Confirmed WorkItem 和 Linked Conversation 组件变体。

验证结果：

- Vitest：29 个测试文件、110 个测试全部通过；
- 前端覆盖率：Statements 87.20%、Branches 83.31%、Functions 85.65%、Lines 88.16%；
- TypeScript 检查和 Vite 生产构建通过；
- Histoire：5 个 Stories、17 个 Variants 构建通过；
- Playwright：桌面与窄屏共 64 个场景全部通过；
- WCAG 2.2 AA 自动检查和浅绿色视觉回归通过；
- `ConversationWorkItemLinkControllerTest`：2 个服务端双向 API 契约测试通过；
- 文档链接检查：99 个 Markdown 文件通过；
- `git diff --check` 通过。

## 验证命令

```text
cd crewscope-web
pnpm test:coverage
pnpm build
pnpm story:build
pnpm test:e2e
cd ..
node scripts/check-doc-links.mjs
git diff --check
```
