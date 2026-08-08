# M1-F03：WorkItem 详情与协作前端

> 日期：2026-08-08<br>
> 状态：已完成<br>
> 工程：`crewscope-web`

## 目标

把 M1-A04 状态迁移和 M1-A05 详情、评论、ResourceLink API 接入 Work Collection，交付可深链接、可回到 Conversation、具备乐观并发反馈的 WorkItem 详情闭环。详情使用服务端一致性快照，命令完成后回读事实。

## 交付范围

### Gateway 与 Store

- `HttpWorkItemGateway` 新增详情、状态迁移、评论和 ResourceLink 四类调用；
- 详情一次读取 WorkItem、Comment 与 ResourceLink 的一致快照；
- 状态迁移同时发送独立 `Idempotency-Key` 和基于详情版本的强 `If-Match`；
- 评论与 ResourceLink 使用独立幂等命令，请求体不携带 Actor 和权限事实；
- `WorkItemStore` 为集合与详情维护独立请求版本，切换详情时旧响应不能覆盖新 WorkItem；
- 迁移成功后同时刷新详情和当前集合；评论或资源关联成功后刷新详情快照；
- 慢命令回执在详情已关闭或切换时不恢复旧 WorkItem；
- 详情命令分别暴露 `transition/comment/resource` Pending 状态和安全错误文案。

### 详情抽屉

- `WorkItemDetailDrawer` 展示 Key、标题、描述、状态、版本、类型、优先级、标签、来源、Due Date 和审计摘要；
- 评论展示作者 Principal、时间与原始内容；新增评论成功后清空草稿，失败保留草稿；
- ResourceLink 展示类型、稳定引用和标签；`EXTERNAL_URL` 仅把客户端再次确认的 HTTP/HTTPS 地址渲染为外链；
- Native `CREWSCOPE` WorkItem 根据领域状态机展示允许的目标状态；外部 Provider WorkItem 不提供本地迁移；
- `ARCHIVED` WorkItem 不显示评论和资源写入口；
- 桌面使用右侧抽屉，窄屏使用全宽详情面板。

### 深链接与 Conversation

```text
/work?...&workItem={WorkItem UUID}&focus={WorkItem Key}
/conversation?...&workItem={WorkItem UUID}&focus={WorkItem Key}
```

- `workItem` 是详情打开状态和服务端资源定位；
- `focus` 是 Conversation 与其他入口共享的人类可读上下文；
- 点击 WorkItemCard 同时写入两个参数；直接访问详情链接后，以服务端详情 Key 规范化 `focus`；
- 关闭抽屉只清除 `workItem`，保留 `focus` 供 Conversation 使用；
- Team 或 WorkProject 切换同时清除不兼容的 `workItem` 与 `focus`；
- “带到 Conversation”保留完整范围、集合视图、`workItem` 和 `focus`，返回 Work 时可以恢复同一详情。

### 并发与错误反馈

状态迁移以当前详情 `version` 发送 `If-Match`。服务端返回 `409 optimistic_lock_conflict` 时：

1. 保留尝试提交的版本与服务端 `currentVersion`；
2. 重新读取详情一致性快照；
3. 展示“检测到并发更新”和版本差异；
4. 用户确认刷新后的状态后再次选择合法迁移。

初次详情失败展示 Error State；已有详情的刷新失败保留抽屉结构和显式重试入口。评论和 ResourceLink 失败不会清空用户草稿。

## 键盘与 Focus

- 打开抽屉后焦点进入关闭按钮；
- Escape 关闭抽屉；
- Tab/Shift+Tab 在模态详情的可交互控件间循环；
- 抽屉打开期间锁定背景页面滚动；
- 从 WorkItemCard 打开后，关闭时焦点恢复到当前 DOM 中对应的卡片按钮；
- 对话、状态迁移、评论和资源表单均使用原生可访问控件与可读 Label。

## 权限边界

前端 `work:participate` 控制迁移、评论和资源关联入口，只用于界面裁剪。服务端继续校验：

- ACTIVE USER 与 ACTIVE Team Membership；
- Team Scope 或目标 WorkProject Scope 的 `WORK_PARTICIPATE`；
- Organization、Team、Workspace、WorkProject 和 WorkItem 全量 Scope；
- Native/外部来源状态所有权；
- 归档边界、安全 URL、幂等、版本和领域状态机。

## 自动化验证

```bash
cd crewscope-web
pnpm test:coverage
pnpm build
pnpm story:build
pnpm test:e2e
```

当前结果：

- Vitest：11 个测试文件、40 个测试通过；M1-F03 新增 10 个测试，覆盖详情 Gateway、强版本前置条件、Store 刷新、评论、资源、版本冲突、跨详情竞态、组件草稿和错误/终态分支；
- Coverage：Statements 84.31%、Branches 76.37%、Functions 82.30%、Lines 85.81%，全部高于 Release Gate；
- Playwright：10 个场景在桌面 1440×960 与窄屏 390×844 共执行 20 次，覆盖详情深链接、Escape、Focus 恢复、迁移、评论、资源、Conversation 跳转和乐观冲突刷新；
- TypeScript 与 Vite 生产构建通过；
- Histoire：4 个 Story、12 个 Variant 构建通过，WorkItem 目录包含 List、Board 和 Detail Drawer；
- AppShell 桌面与窄屏视觉基线继续通过；
- 文档链接：60 份 Markdown 全部通过；`git diff --check` 通过。

## 阶段边界

M1-F03 不实现 Owner、Executor、Gate Reviewer、ReviewerEligibilityPolicy、责任分配和时间线。详情中的 Conversation 按钮完成对象级跳转；M1 的 Conversation 页面只展示 M2 交互蓝图，并明确不会创建 Conversation、TaskIntent、TaskExecution 或 AgentRun。真实 Personal Agent 对话和“交给 Agent 处理”按后续里程碑交付。M1-F04 在当前详情模板中加入责任链、分配、资格提示和时间线。
