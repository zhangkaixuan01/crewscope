# M4-F04：Execution Studio 基础观察面

## 1. 交付范围

M4-F04 在 Task 详情交付 Coding execution 的统一观察入口：

- 不可变 Repository Key、Baseline Commit 与 Managed Branch；
- Workspace 状态、公开 ID、更新时间和恢复代次；
- Sandbox 网络、只读根层、CPU、内存、PID 与 BuildProfile；
- Coding Agent Run、Profile 版本和公开 Session 摘要；
- 当前 Plan Revision、当前 Step 和运行轮次；
- 最近一条结构化 CommandEvidence；
- 命令、变更文件、写入、单文件、输出、Diff 和测试修复预算；
- Recovering、Terminal 与兼容非 Coding Task 的稳定状态。

Execution Studio 位于 Task 详情顶部。M3 Task 控制、Timeline、责任、Runtime Plan、AgentRun 和 Lease 详情继续保留，成员先读取 Coding 概览，再按需进入耐久执行事实。

## 2. 双入口与深链接

Conversation Task 卡和 Control Mode Task 列表进入同一 Task 详情路由：

```text
/work?team=<teamId>&project=<projectId>&workItem=<workItemId>
      &task=<taskId>&attempt=<executionId>&workspace=<workspaceId>
```

页面按 Organization、Team 和 WorkProject 激活 Coding Store。Task 打开后读取当前 Coding attempt 与历史列表，选择当前或历史 attempt 后读取精确 attempt、Task Runtime 与 CommandEvidence。Workspace ID 只取自服务端所选 attempt 详情，并写回规范 URL。

深链接恢复依次复验 Team、WorkProject、Task、attempt 和 Workspace。Workspace 归属不一致进入稳定 Error；不完整或跨 Scope 坐标进入失败关闭状态。关闭 Task、返回 WorkItem 或进入 Conversation 时移除 `attempt` 和 `workspace`，保留上层协作上下文。

## 3. 状态语义

- Loading：读取当前 attempt、Workspace、Sandbox 与 Runtime 公开事实；
- Empty：Task 或 attempt 使用通用 Agent 模式，没有 CodingTargetSnapshot；
- Error：Coding 查询或深链接校验失败，已加载事实继续可见；
- Forbidden：任一 Coding 资源返回 403 时进入统一 Access Denied 页面；
- Recovering：Workspace 显示恢复代次和资源对账语义；
- Terminal：完成、失败或取消原因与证据保留语义保持可见；
- Attempt Switch：Task Runtime、Coding attempt、CommandEvidence 和 URL 坐标同步切换。

Task 事件触发的权威事实刷新与 Pause、Resume、Cancel、Retry 成功后同时刷新 Coding Store。重试读取先失效当前 Task 的 Coding 缓存，再恢复同一深链接选择。

## 4. 浏览器安全边界

Execution Studio 只消费 M4-F01 的显式 DTO 白名单。浏览器状态包含公开 ID、逻辑状态、Repository Key、Commit、Managed Branch、资源上限、Evidence 摘要和 Hash。

以下数据停留在服务端与 Worker：

- 宿主 Repository、Worktree 和 Managed Root 路径；
- 容器 ID、名称、镜像和挂载；
- typed argv、工作目录和环境变量；
- Task Token、Claim Token、Credential、Lease 和 Fencing；
- AgentState、Reasoning、内部 Checkpoint 载荷和 Artifact Storage URI。

## 5. 自动验证

- `pnpm test`：49 个测试文件、210 项测试通过；
- `pnpm build`：Vue TypeScript 检查与 Vite 生产构建通过；
- Playwright 完整回归：desktop-chromium 与 `390×844` narrow-chromium 共 110 项通过；
- Axe WCAG 2.2 AA、键盘焦点、Reduced Motion 和 Task 详情视觉基线通过；
- E2E 覆盖 Conversation/Control 双入口、当前与历史 attempt、Workspace URL 恢复、非 Coding Empty、403、Recovering、资源预算和结构化命令；
- 安全探针确认宿主路径、容器标识、Token、typed argv 和 Runtime Credential 未进入页面。

## 6. 下一项

M4-F05 在 Execution Studio 交付文件树、变更状态、单文件 Patch、累计统计和实时 Diff Stream。
