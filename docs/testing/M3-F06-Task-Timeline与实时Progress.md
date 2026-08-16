# M3-F06：Task Timeline 与实时 Progress

## 目标

在 Task 详情中把耐久 Task Event、受控 AgentRun 公开事件、当前 Runtime 事实和恢复状态组合为团队可理解的 Timeline，并在断线、Cursor 过期、慢流轮换和终态收敛时保持一致。

## 已交付能力

- Task 详情在执行控制之后展示 Timeline，窄屏保持 Task、控制、Timeline、关联、责任、attempt、Runtime、Plan、Step、AgentRun、Lease 的语义顺序；
- 历史 API 按耐久流顺序进入页面，不使用可能倒退的 `occurredAt` 重新排序；界面只为阅读方便倒序展示最近事实；
- Worker Progress 与 AgentRun Progress 合并为当前 attempt 的公开进度卡，支持可选百分比和安全摘要；Heartbeat、`TEXT_DELTA` 与 Usage 不形成时间线噪声；
- `TASK_EXECUTION_RECOVERY_STARTED`、`AGENT_RUN_RESUMED`、`RECOVERING`、AgentRun continuity gap 和投影缺口形成明确恢复标识；
- 详情先追平历史，再从历史 `nextCursor` 建立 SSE；未加载历史的 Conversation Task 卡继续使用按 Organization、Team、Task 分区的 SessionStorage Cursor；
- SSE 新事件实时追加到已有历史，同时只作为 Task/Runtime 权威事实回读信号；350ms 合并窗口避免高频事件触发重复查询；
- `eventId` 与 `domainEventId` 在历史分页、SSE 重放和 410 从头恢复时双重去重；慢流关闭后从最新 Cursor 重连；
- 410 清除浏览器 Cursor、标记投影缺口并从流头恢复；完整终态历史不再建立 SSE，运行中 Task 回读终态后立即停流；
- Timeline 公开载荷在浏览器 Gateway 再执行一次事件类型白名单。Claim/Task Token、Hash、Credential、Fencing、原始 AgentState、内部 Reasoning、Tool 参数与 Provider 原始错误不进入页面状态；
- 独立 polite ARIA Live Region 以 900ms 合并最新有效事件，初次历史不播报，Heartbeat 和文本增量不播报；Reduced Motion 下 Progress 过渡完全关闭。

## 事实与交互边界

- Task Event 数组顺序是权威耐久顺序，时间戳只用于显示；
- Timeline 可以即时展示公开事件，Task、TaskExecution、Plan、Step、AgentRun 和 Lease 状态只接受 API 回读结果；
- 选择历史 attempt 时按 `taskExecutionId` 过滤执行事件，Task 级事实继续保留；
- 错误与重连期间保留已加载 Timeline 和 Progress，不使用空白替换陈旧但有效的事实；
- Timeline 最多展示最近 40 条映射后事件，历史 Cursor 与 SSE 共同保证断点续读。

## 验证

- Vitest 40 个文件、170 项测试通过；新增 Timeline 映射、组件、Gateway 白名单和 Store 续传专项测试；
- 映射测试覆盖输入顺序、乱序时间戳、attempt 过滤、事件/Domain 双重去重、Worker/Agent Progress、Heartbeat/TextDelta 降噪、Recovery/Resume 和未知载荷；
- Store 测试覆盖历史 Cursor 优先、SSE 追加、SessionStorage 降级、410、独立连接、重放去重、终态历史停流和 Scope 取消；
- 组件测试覆盖 Progress、恢复缺口、重连、最新事件倒序、初次历史静默和 900ms ARIA Live 合并；
- Playwright 96 项桌面/窄屏测试覆盖历史追平、410 从头恢复、重复 Domain、慢流重连、公开字段脱敏、RECOVERING/continuity gap、终态停流和 Reduced Motion；
- Vue TypeScript、Vite 生产构建、Maven 全量测试、Markdown 链接和 `git diff --check` 通过。

## 下一项

`M3-F07` 完成 Task 页面全状态、键盘、Axe、桌面/窄屏和视觉回归硬化。
