# M3-F03：Task 详情与 Runtime 安全事实

## 目标

在 Control Mode 交付面向团队成员的 Task 详情抽屉，让负责人理解任务目标、责任快照、每次执行尝试、AgentScope 执行进度和 Runtime 健康状态。

## 已交付能力

- Task 目标、验收标准、状态、来源、创建时间和 WorkItem 上下文；
- Owner、Executor 和 Reviewer 不可变责任快照；
- 当前与历史 TaskExecution attempt 切换，展示状态、等待/失败原因、执行者、优先级和尝试上限；
- 所选 attempt 的当前/历史 PlanVersion、公开 Markdown 摘要和 Todo 状态；
- StepExecution 完成数、Run attempt、Checkpoint 和 `WAITING_RUNTIME` 原因；
- AgentSession、AgentRun、Segment 和 continuity gap 摘要；
- ExecutionLease 阶段、状态、截断 Runtime/Worker 标识、Heartbeat/过期时间和释放原因；
- AgentStateSnapshot 与 AgentInterrupt 的数量、状态和恢复坐标摘要；
- 成员安全 Runtime Fleet 健康、容量、Worker 失联数、等待 Runtime 数和聚合原因；
- 桌面双栏和窄屏顺序阅读，支持 Escape、焦点约束、关闭后恢复来源控件；
- 关闭 Task 抽屉后保留 WorkItem、Team、WorkProject 和筛选 URL 上下文。

## 数据与隔离契约

- Runtime Facts 使用 `taskId:executionId` 缓存键，当前与历史 attempt 不共用投影；
- Team Scope 切换取消进行中请求，并清空 Task Runtime Facts 与 Fleet 缓存；
- Fleet 读取使用成员级 `/runtime-health`，详情页不请求运维级 `/runtime-health/operations`；
- Task 详情、attempt、Runtime Facts 和 Fleet 均使用显式响应白名单；
- 执行密钥、Hash、凭证、原始 AgentState、内部 Reasoning、Tool 原始参数和结果不进入 Web 类型、Store 或 DOM。

## 验证

- Vitest：36 个文件、145 项测试通过；
- `TaskDetailDrawer` 组件测试覆盖状态语义、PlanVersion 切换、步骤进度、Worker 失联、`WAITING_RUNTIME`、continuity gap、敏感字段缺失、语义顺序和键盘关闭；
- Task Gateway 测试验证 Runtime Fleet 成员摘要白名单丢弃运维级 Worker 明细；
- Task Store 测试验证 attempt 独立缓存、Fleet 缓存和跨 Team 清理；
- Playwright 全量 78 项通过，桌面/窄屏主链覆盖 Task 深链接、attempt 切换、双栏/顺序布局、关闭保留 WorkItem 和敏感值不可见；
- Task 详情桌面与窄屏视觉基线已生成并通过；
- `pnpm build` 通过 Vue TypeScript 检查和 Vite 生产构建。
- 134 份 Markdown 文档链接与 `git diff --check` 通过。

## 下一项

`M3-F04` 在 Conversation Mode 交付 Task 卡片和实时状态，并提供 Conversation、WorkItem 与 Task 双向跳转。
