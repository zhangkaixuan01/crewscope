# M2-F03：AG-UI 流式回复与 Conversation Event 恢复

> 日期：2026-08-11<br>
> 状态：已完成<br>
> 工程：`crewscope-web`

## 目标

把 M2-A03 Personal Agent Invocation 与 M2-A04 Conversation Event 接入 Conversation 工作区，建立公开文本流、持久事实收口、断线/刷新恢复、去重、缺口追平和显式取消的纵向链路。

## 交付范围

### SSE 与 Gateway

- `CrewScopeApiClient.open` 复用 JSON、CSRF、命令头和安全错误边界，为流式 Gateway 返回已验证的 Response；
- SSE 分帧器支持 LF/CRLF、多行 `data`、注释、无终止空行和任意网络分块；
- Invocation 使用 POST `text/event-stream`，只提交 `message` 与 `Idempotency-Key`，读取服务端 `X-CrewScope-Invocation-Id`；
- Conversation Event 使用 GET SSE，通过不透明 `after` Cursor 恢复；
- Cancel 只提交服务端 Invocation ID、安全 Reason 和独立 `Idempotency-Key`。

### Invocation 与恢复 Store

- Owner 的 Composer 直接调用 Personal Agent Invocation，避先 POST Message 再导致 USER Message 重复；
- 非 Owner ACTIVE Participant 继续使用 M2-A02 Message 追加路径，不驱动 Owner Personal Agent；
- 只处理 `RUN_STARTED`、`TEXT_MESSAGE_CONTENT`、`RUN_INTERRUPTED`、`RUN_FINISHED` 和 `RUN_ERROR`；
- AG-UI 与 Conversation Event 使用独立有界 `eventId` 去重集合，相同字面 ID 不会跨流误吞事件；
- 首个终态结束 Segment 消费，后续帧不会改写终态；流式公开文本总量不超过 50,000 字符；
- 缺失或越界的 Clarification 和非法 `RUN_FINISHED` 状态进入安全错误边界；
- Reasoning、Tool、State、Custom 和未知 AG-UI 事件不进入客户端状态或 DOM；
- 断线重连与网络失败后的显式重试均复用原 `Idempotency-Key`，按 `eventId` 去除重放前缀；
- SessionStorage 仅保存 Message、作者、基线 Sequence 和 Idempotency-Key，刷新后重新校验结构与长度；异常恢复记录在发起网络请求前清除，合法记录使用服务端重放恢复公开文本；
- HTTP 订阅断开不触发 Cancel，用户取消只调用显式 API。

### Conversation Event 与事实收口

- 每个 Conversation Scope 保存自己的最后 Cursor，Scope 切换取消旧订阅和 Invocation HTTP 连接；
- Cursor 只保存和原样回传，`410 cursor_expired` 清除旧坐标并强制回读当前 Message 事实；
- 单流按 `eventId` 去重，跨持久流按 `domainEventId` 去重，两类集合都使用有界内存；
- Aggregate Version 只在同一 Aggregate 内判断重复、旧事件与缺口，不声称跨聚合全局顺序；
- `CONVERSATION_MESSAGE_POSTED`、Agent 成功终态与投影缺口触发最新 Message 回读；
- 流式 Agent 气泡在相同持久 AGENT Message 出现后消失，最终页面只保留服务端事实。

### 交互与阶段边界

- 消息区展示连接、运行、重连、取消、成功、中断和安全错误状态；
- 活动调用提供显式取消，可重试网络错误提供同键“重新连接”；
- 流式回复使用现有安全 Markdown 边界，窄屏 Composer 与底部导航保持不重叠；
- M2-F03 不提交 Clarification Answers，不展示/修订/确认 TaskIntent，不在客户端构造 AgentRun、Tool 或执行事实。

## 自动化验证

```bash
cd crewscope-web
pnpm test:coverage
pnpm build
pnpm story:build
pnpm test:e2e
```

当前结果：

- Vitest：22 个测试文件、88 个测试通过；新增 SSE 分帧、Realtime Gateway 与 Store 专项测试；
- Coverage：Statements 87.27%、Branches 80.88%、Functions 86.18%、Lines 88.43%，全部高于前端 Release Gate；
- TypeScript 检查与 Vite 生产构建通过；
- Histoire：4 个 Story、12 个 Variant 构建通过；
- Playwright：30 个场景在桌面 1440×960 与窄屏 390×844 共执行 60 次，全部通过；
- M2-F03 新增 5 个端到端场景，覆盖公开 Token Stream、Reasoning 隔离、断线同键重放去重、刷新恢复、Conversation Cursor 续传和显式取消；
- Conversation 全部场景在桌面与窄屏共 32 次专项复验通过；
- 视觉基线、Axe WCAG 2.2 AA、文档链接和 `git diff --check` 纳入阶段最终检查。

## 下一阶段

M2-F04 接入 M2-A05 Clarification Request/Resume、TaskIntent 预览、完整修订、拒绝、确认预检、强 ETag 和版本冲突恢复，继续复用本阶段的 Invocation、流式去重、持久事件和事实收口边界。
