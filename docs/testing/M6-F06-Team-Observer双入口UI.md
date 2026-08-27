# M6-F06 Team Observer 双入口 UI 验证记录

## 1. 交付范围

M6-F06 在 Conversation Mode 的 `/conversation?assistant=team-observer` 交付对话式 Team Observer，在 Control Mode 的 `/team/observer` 交付只读团队摘要。两个入口复用一个 Team Observer Gateway、一个 Organization + Team 隔离 Store 和同一个 `TeamObserverWorkspace`，展示固定 Agent 身份、只读说明、进展、阻塞、Review、待确认、异常和持续授权证据。

Team Observer 保持独立于 Personal Conversation。浏览器不创建、选择或伪造 Personal Conversation，不把 Observer Session、Invocation、摘要或错误写入 Conversation Message、TaskIntent 和 WorkItem Link Store。

## 2. 流式、Scope 与安全边界

- Session 创建后只提交 `instruction` 与 `maxItemsPerSection`；Agent、Profile、Model、Provider、Connection、Tool、Skill、身份和写命令字段没有浏览器输入面；
- SSE 只接受 `STARTED`、`SUMMARY_COMPLETED`、`CANCELLED`、`FAILED`，校验响应 Header 与事件 Invocation 一致，并按 Sequence 去重；
- Transport 断开或无终态完成时使用相同 Session、相同 Invocation 调用 Resume，不启动第二次模型调用；Resume 改变 Invocation 时失败关闭；显式 Cancel 才产生业务取消；
- Team 切换 Abort 旧请求、递增 Store Generation 并清除 Session、Invocation、Sequence、Summary 与 URL 坐标；即使旧 Gateway 无视 AbortSignal 晚返回，也不能污染新 Team；
- 五段摘要全部使用纯文本插值，不解析 Markdown、HTML 或模型生成链接。Prompt 攻击字符串在 DOM 中保持文本；Provider 和模型错误只进入稳定公开错误文案；
- Evidence 按索引重新调用后端授权。Gateway 要求路径属于当前 Organization/Team，并且精确匹配 Activity、Inbox、WorkItem 或 Task 规范 API 资源；随后映射为批准的 `/activity`、`/inbox`、`/work` 路由。跨 Scope、外部、协议相对、Query、Fragment、编码、遍历和未知资源路径失败关闭。

## 3. 产品状态与可访问性

- Conversation 入口提供有界问题输入、字符计数、生成、运行状态、同调用恢复和取消；Control 入口提供生成、刷新与五段只读摘要；
- 两个入口共享同一 Store，模式切换不重复调用模型，摘要、生成时间、Observer Profile 和 Invocation 保持一致；
- Loading、Running、Reconnecting、Completed、Cancelled、Error、Offline 和 Empty 使用稳定状态；离线保留已生成摘要并关闭生成、恢复和证据解析；
- Desktop 使用五段双列态势布局，异常段横跨全宽；Narrow 使用同语义单列，固定底部模式导航不遮蔽可操作证据；
- 原生 Textarea、Button、Heading、Status/Alert、ARIA Label 和键盘行为通过双视口 Axe。

## 4. 自动验证

主要命令：

```bash
pnpm --dir crewscope-web test
pnpm --dir crewscope-web build
pnpm --dir crewscope-web story:build
pnpm --dir crewscope-web test:e2e
pnpm --dir crewscope-web check:sensitive
```

M6-F06 专项验证：

- Gateway、Store 与 Component Vitest `7 / 7`，覆盖固定请求体、SSE 身份、同 Invocation Resume、跨调用 Sequence 重置、Scope 晚到隔离、Prompt 纯文本化和 Evidence 再授权；
- Playwright Desktop/Narrow `2 / 2`，同一纵向用例覆盖断流 Resume、双入口一致、Prompt 攻击、Evidence 跳转、Axe 与四张视觉基线；
- Production Build 通过；Histoire Build `13` 个 Story、`80` 个 Variant，新增 Team Observer Conversation、Control、Reconnecting、Empty 与 Offline Cached 五种状态。
- 全量 Vitest `400 / 400`、全量 Playwright/视觉/Axe `172 / 172`、Web 敏感字段门禁通过，覆盖 `20` 个生产文件与 `13` 个 Story。

视觉基线：

- `crewscope-web/e2e/m6-team-observer.spec.ts-snapshots/m6-team-observer-conversation-desktop-chromium.png`
- `crewscope-web/e2e/m6-team-observer.spec.ts-snapshots/m6-team-observer-conversation-narrow-chromium.png`
- `crewscope-web/e2e/m6-team-observer.spec.ts-snapshots/m6-team-observer-control-desktop-chromium.png`
- `crewscope-web/e2e/m6-team-observer.spec.ts-snapshots/m6-team-observer-control-narrow-chromium.png`

## 5. 结论

M6-F06 完成。团队成员可以从对话和传统管理两个入口使用同一个只读 Team Observer，在不扩大 Personal Conversation、模型配置或写工具边界的前提下获得五段可核验团队摘要。下一任务为 `M6-F07`。
