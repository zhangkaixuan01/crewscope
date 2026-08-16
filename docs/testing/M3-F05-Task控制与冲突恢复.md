# M3-F05：Task 控制与冲突恢复

## 目标

在 Control Mode 为有当前控制责任的团队成员提供 Pause、Resume、Cancel 和 Retry，并以服务端耐久事实处理请求中、断网、并发冲突、终态竞态和新 attempt 切换。

## 已交付能力

- Task 详情在摘要后展示独立执行控制卡，当前 attempt 的状态决定可用操作；
- `RUNNING` 支持 Pause，`PAUSED` 支持 Resume，允许取消的非终态支持 Cancel，可重试失败且未耗尽次数时支持 Retry；
- 操作确认展示对当前执行、外部副作用、审计证据和 attempt 历史的影响；
- Pause 与 Cancel 要求 1–500 个不含控制字符的团队可见原因；
- 当前 WorkItem 的 ACTIVE Owner 或 Executor 且具有参与权限时才展示操作；只读成员不获得控制入口；
- 离线时保留已读取事实并禁用命令提交，网络恢复后由成员重新确认；
- 每条新命令生成独立幂等键，可重试网络错误保留原命令与原幂等键；请求期间阻止重复点击；
- 命令 pending 期间不修改 Task 或 attempt 本地状态，成功后统一回读 Task、attempt、Runtime、关联与列表事实；
- 409 与 412 显示提交版本和当前服务端版本，清除陈旧重试命令并强制回读；终态竞态按回读结果移除失效操作；
- Retry 保留失败 attempt，由服务端创建后继 attempt，回读后自动选择新的 current attempt 及其 Runtime Facts；
- Cancel 确认、关闭按钮、背景关闭和 Escape 均恢复触发按钮焦点；确认弹窗限制键盘焦点且 Escape 不关闭外层 Task 详情。

## 协议与事实边界

- 成员命令调用 `/tasks/{taskId}/attempts/{executionId}/{operation}`，使用 attempt 强版本 `If-Match` 与 `Idempotency-Key`；
- Pause/Cancel 只提交结构化 `reason`，Resume/Retry 不提交请求体；
- CommandReceipt 不用于拼装业务状态，SSE 也不覆盖命令后的权威回读；
- Scope 或 Task 切换递增命令 generation，旧请求回执不能污染新详情；回执后的详情、Runtime、关联和列表刷新在每个异步阶段继续校验同一 generation，不得因旧命令将页面切回原 Scope；
- Claim Token、Task Token、Hash、Credential、原始 AgentState、内部 Reasoning 和 Tool 原始载荷不进入控制类型、Store 或界面。

## 交互与视觉

- 控制卡使用浅色品牌边框与低饱和背景，位于 Task 摘要和关联上下文之间；
- 窄屏按 Task、控制、关联、责任、attempt、Runtime 顺序阅读，控制按钮与底部固定入口无重叠；
- 冲突、离线与网络错误使用独立语义反馈，操作按钮只反映请求态，不展示乐观伪状态；
- `task-detail-desktop-chromium.png` 与 `task-detail-narrow-chromium.png` 固化桌面和窄屏视觉基线。

## 验证

- Vitest 42 个文件、180 项测试通过；Task Store 20 项专项包含命令回执前与多阶段回读期间的 Scope 切换隔离；
- 控制组件覆盖状态/权限显隐、影响说明、原因与控制字符校验、离线、冲突、原命令重试和 Cancel 焦点恢复；
- Store 覆盖 pending 期间事实不变、重复提交、原幂等键重试、409/412、刷新失败、终态竞态、Retry 后继 attempt，以及 Scope/Task 在命令响应前或刷新期间切换的隔离；
- Playwright 102 项桌面/窄屏测试通过，覆盖 Cancel 请求态、冲突终态回读、Retry 新 attempt、离线和只读成员；视觉基线通过；
- `TaskCommandControllerM3A04Test` 4 项通过，验证前端依赖的成员 Task Command API 契约；
- Vue TypeScript、Vite 生产构建、Markdown 文档链接和 `git diff --check` 通过。

## 下一项

`M3-F06` 交付 Task Timeline、实时 Progress、恢复标识和 SSE Cursor 续传。
