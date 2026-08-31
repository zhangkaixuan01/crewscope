# M5-F02 Agent 中心列表

## 交付结果

M5-F02 在 Control Mode 交付 `/settings/agents` Agent 中心，使普通 TeamMember 可以查看当前 Team 授权范围内的 Agent：

- 唯一默认 Personal Agent；
- 当前成员拥有的 USER-owned Coding、Reviewer 及其他 Specialist；
- 当前 Team 可发现的 TEAM-owned Agent。

导航中的“Agent 中心”使用成员可读的 Scope 权限显示，服务端仍在每次列表和配置读取时执行 Organization、ACTIVE Membership、Owner 和 Team 可见性校验。页面始终提供“个人 Agent”和“团队 Agent”两个类型入口；Team Agent 为零时保留团队空状态、权限说明和 WorkItem Executor 使用路径。F02 的原始阶段不提供创建、配置与生命周期命令，这些交互由 M5-F03 补齐。

## 页面契约

页面以卡片分组呈现 Agent 稳定事实：

- 显示名称、Ownership、RuntimeRole、Template Key/Version、当前 Configuration Revision；
- ACTIVE、DISABLED 和 ARCHIVED 都保留在目录，分别显示“运行中”、“已禁用”和“已归档”；
- 读取当前 Configuration 的 PERSONAL/TEAM 主模型与 Fallback；单个配置摘要失败不隐藏整个 Agent 目录；
- 深链接使用 `team + agent + configurationRevision`，不可见或不存在的 Agent 不会恢复旧 Scope 状态；
- Agent 卡片是原生链接，支持 Tab 聚焦、Enter 激活和浏览器原生深链接行为。

Loading、Empty、Error 和 Forbidden 使用共享 `StatePanel`。列表级错误失败关闭；已加载的分类、生命周期和配置事实不由浏览器乐观生成。

## 任务与成本边界

M5-A08 `TaskDeliverySummary` 只支持按 Task 或 Conversation 查询，没有按 Agent 授权、币种归一和计费版本固定的聚合坐标。F02 不遍历 Task 分页来推导任务数、Token 和成本，页面明确告知当前聚合投影尚未接入。后续服务端投影必须先完成 Agent 可见性、币种和 Price Revision 语义，再将统计接入列表。

## 安全与状态边界

- 页面只消费 M5-F01 白名单 DTO；API Key、Credential Reference、Endpoint、System Prompt、Tool/Schema Payload 不进入 DOM 或 Store。
- Agent Store 在 `organizationId + teamId` 变化时取消旧请求并清空缓存。
- 本阶段修复动态 Record 资源的 Vue 响应性边界：资源写入 reactive Record 后必须重新读取 Proxy，避免异步 Configuration 已成功但页面长期停留在 Loading。

## 验证

- `AgentSettingsPage.spec.ts` 覆盖三类 Agent、多 Agent、模型/Fallback、Loading、Empty、Error、Forbidden、DISABLED、ARCHIVED、不可见深链接、键盘焦点和敏感字段不泄露。
- Playwright 在 desktop Chromium 和 390×844 narrow Chromium 验证真实 HTTP Fixture、深链接、键盘、无水平溢出和敏感内容扫描。
- 双视口保存 `agent-settings-desktop-chromium.png` 与 `agent-settings-narrow-chromium.png` 视觉基线。
- Agent 页面进入 Axe WCAG 2.2 AA 主页面集。
- 前端全量 59 个测试文件、265 项 Vitest 通过；双视口 Playwright 全量 130 项通过；`vue-tsc` 与 Vite 生产构建通过。

## 下一任务

M5-F03 使用当前列表、Store 与深链接坐标交付 Agent 创建向导和详情设置。
