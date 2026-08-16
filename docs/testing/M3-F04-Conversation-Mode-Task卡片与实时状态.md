# M3-F04：Conversation Mode Task 卡片与实时状态

## 目标

在 Conversation Mode 展示可恢复、可跳转、可实时更新的耐久 Task 事实，让团队成员在持续对话时仍能看到真正执行中的任务、责任人、attempt、等待原因和终态。

## 已交付能力

- Conversation 工作区在 TaskIntent 与已确认 WorkItem 之后展示独立 Task 区域；
- 同一 Conversation 的多个可见 Task 并列展示目标、Task 状态、Owner、当前 attempt、TaskExecution 状态、等待原因、关联来源和关联时间；
- Task 区域与消息列表使用独立 DOM，Personal Agent 瞬时文本流不会替换或隐藏 Task 卡片；
- 非终态 Task 各自建立 SSE 连接，事件到达后强制回读 Conversation/Task 关联摘要；
- Conversation、WorkItem 与 Task 跳转保留 Team、Project、Conversation、WorkItem 和 Task 查询坐标；
- Task 详情展示当前成员可见的关联 Conversation，并支持返回对应对话；
- 从 Conversation 的已确认 WorkItem 进入委托时，URL 携带最新持久 USER Message；委托页明确展示来源，创建命令把该坐标作为 `conversationSource` 提交；
- Task 创建成功后可从关联 Conversation 返回并恢复卡片，页面刷新后继续使用服务端关联事实；
- Task 关联响应补齐 Owner 与当前等待原因，卡片不执行逐 Task 补查。

## 实时与恢复契约

- 每个 Task 使用独立 AbortController、Cursor、`eventId` 集合和 `domainEventId` 集合；
- Cursor 的 SessionStorage 键包含 Organization、Team 和 Task，跨 Scope 不复用；
- 事件去重集合有界，SSE Payload 只触发失效，不直接拼装 Task 状态；
- 关联摘要是 Task 卡片唯一事实源，SSE 重放不会造成状态倒退；
- 410 清除对应 Cursor、标记投影缺口、强制回读并从当前事实重新连接；
- Scope、Conversation、Task 集合变化和页面卸载会取消旧连接；
- `COMPLETED`、`FAILED`、`CANCELLED` 不建立或继续保持实时连接；
- PRIVATE Conversation 只渲染服务端关联 API 返回的 Task，客户端不推断隐藏关联；
- Gateway 继续执行公开字段白名单，Claim Token、Task Token、Hash、Credential、原始 AgentState、内部 Reasoning 和 Tool 原始载荷不进入 Web 状态。

## 交互与视觉

- 桌面卡片使用紧凑横向事实布局，窄屏改为顺序阅读和整行 Task 主操作；
- `ACTIVE`、`WAITING`、`COMPLETED`、`FAILED`、`CANCELLED` 使用已有语义色，不新增高饱和主色；
- 实时、连接中、重连和不可用只作为卡片辅助状态，不覆盖业务状态；
- Task 详情中的关联 Conversation 位于责任快照之前，窄屏保持 Task、关联上下文、责任、attempt、Runtime 的语义顺序；
- `conversation-tasks-desktop-chromium.png`、`conversation-tasks-narrow-chromium.png` 和更新后的窄屏 Task 详情截图作为视觉基线。

## 验证

- Vitest：37 个文件、153 项测试通过；
- `ConversationTaskCards` 覆盖多个 Task、运行/等待/终态语义、等待原因、独立实时状态、Loading、Error、陈旧事实保留和双入口事件；
- Task Gateway 覆盖 SSE URL、Accept、Cursor、Task Scope 校验和公开字段白名单；
- Task Store 覆盖多 Task 独立流、Cursor 存储、事件/领域事件去重、410 恢复、强制关联回读、Task 移除和连接取消；
- Task 详情测试覆盖当前可见 Conversation 展示与跳转；
- `TaskAssociationControllerM3A06Test` 验证 Conversation Task 摘要包含 Owner 与等待原因；
- Playwright 84 项全部通过；桌面/窄屏覆盖多个 Task、刷新恢复、创建后出现、PRIVATE 服务端过滤、等待到终态更新、三向跳转、Conversation Cursor 与 Task Cursor 隔离，以及 Agent 瞬时流期间卡片保持可见；
- `./mvnw test` 的 7 模块 Maven Reactor 全部通过，包含 PostgreSQL 与 Redis Testcontainers 集成测试；
- Vue TypeScript、Vite 生产构建、135 份 Markdown 文档链接和 `git diff --check` 通过。

## 下一项

`M3-F05` 交付 Task Pause、Resume、Cancel、Retry 控件、影响说明、权限显隐和强版本冲突后的事实刷新。
