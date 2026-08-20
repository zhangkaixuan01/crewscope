# M4-F07：Coding 进度与执行控制整合

> 状态：已完成<br>
> 日期：2026-08-20<br>
> 模块：`crewscope-web`

## 目标

在 Execution Studio 中整合 Coding 阶段、Plan Todo、Checkpoint、State Snapshot、测试与修复预算，以及当前 Task attempt 的 Pause、Resume、Cancel 和 Retry 控件。成员在同一观察面读取执行进度并提交受版本保护的控制命令。

## 进度投影

Coding 进度固定为五个阶段：

1. 准备：ExecutionWorkspace 已建立；
2. 分析与计划：当前 PlanVersion 已发布；
3. 代码变更：DiffManifest 已产生文件事实；
4. 测试与修复：Test、Verify、Acceptance CommandEvidence 或 TestEvidence 已发布；
5. 交付：平台权威 CodingResult 已固化。

阶段由最新公开事实确定，TaskExecution 与 Workspace 状态继续承担执行事实。中断的当前阶段显示失败或取消状态。历史 attempt 使用自身的 Workspace、Plan、Diff、Evidence 和 Result 投影。

## Todo、Checkpoint 与测试事实

- 当前 PlanVersion 的 Todo 按进行中、阻塞、待处理、完成顺序展示；
- 最近 Step Checkpoint 展示 Sequence 与稳定 Code；
- State Snapshot 限定在当前 Agent Run，展示 Snapshot Sequence 与 Checkpoint Sequence；
- 当前 Step 展示 Plan Step Key 与耐久状态；
- Agent Run continuity gap 使用明确警告并提示以服务端恢复结果为准；
- 最新 TestEvidence 展示 Evidence Sequence、测试摘要和通过状态；
- 修复预算展示 WorkspacePolicy 的 `maxTestRepairRounds` 上限。

TestEvidence Sequence 表达证据发布顺序。同一 Specialist 修复轮次可以产生多条证据，前端不使用 Sequence 推导已用修复轮次。当前公开 API 未单独披露精确已用轮次，页面明确说明该边界。

浏览器状态排除 AgentState、State Reference、Checkpoint Hash、reasoning、Token、Lease 与 Fencing。

## 执行控制

当前 Coding attempt 的 Execution ID 与 TaskExecution ID 对齐后复用 M3 `TaskControlPanel`：

- RUNNING 提供 Pause 与 Cancel；
- PAUSED 提供 Resume 与 Cancel；
- FAILED 或 CANCELLED 提供 Retry；
- 历史 attempt 保持只读；
- 当前事实对齐期间显示同步状态并关闭命令入口；
- 只读成员和离线状态关闭写命令。

命令继续使用 `If-Match` 强版本与 `Idempotency-Key`。网络失败保留原命令和原键；409/412 回读 Task、attempt 与 Runtime 事实；确认对话框关闭或完成后恢复触发按钮焦点。Retry 保留失败 attempt，并选中服务端创建的后继 attempt 与 Workspace。

## 响应式与可访问性

桌面使用五阶段横向轨道和 Todo/Checkpoint、测试/控制双栏。390×844 窄屏按阶段、Todo、Checkpoint、测试、控制顺序阅读。阶段使用语义列表和 `aria-current="step"`，同步状态使用 Live Region，历史状态提供文本说明。Checkpoint 警告色满足 WCAG 2.2 AA 对比度。

## 验证

专项 Vitest 覆盖阶段计算、Todo、公开 Checkpoint、Snapshot 白名单、修复预算语义、历史只读和离线控制。Playwright 双视口覆盖当前/历史 attempt、命令执行中控制、409/412 冲突回读、离线、只读成员、原键重试、Retry 后继 attempt、确认焦点恢复和视觉基线。

验证命令：

```bash
cd crewscope-web
pnpm test
pnpm build
pnpm exec playwright test
```

全量 Vitest 共 53 个测试文件、230 项测试通过。前端 TypeScript 检查与生产构建通过。Playwright 在 desktop Chromium 与 390×844 narrow Chromium 共 120 项通过，包含 M4-F07 控制协议、视觉回归与 WCAG 2.2 AA 门禁。Markdown 链接检查与 `git diff --check` 同步通过。
