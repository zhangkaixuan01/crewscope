# M5-F07 GitHub Delivery Workbench

## 1. 交付结果

M5-F07 在 Task 详情的 Review Workbench 后交付 GitHub Delivery 工作台，连接 A06 GitHub 授权目录与 A07 ActionBundle 执行边界：

- 同时读取当前成员可用的 TEAM GitHub App 与 USER OAuth Connection 安全投影，优先选择当前 Team 的 ACTIVE Connection；
- 从 Connection 下选择 ACTIVE Team ProviderBinding 和服务端 DELIVERABLE Repository Catalog 项，不接受手填 owner/repo、Remote URL、Binding ID 或任意分支；
- 显示 Authorization、Grant、Credential、Profile、Webhook、RateLimit 与 Catalog 的成员安全健康摘要；
- 以 Connection Version 执行 Catalog Synchronize 和 Binding-pinned Remote Preflight；
- 只有当前未失效 ReviewRequest 的成员 `APPROVED` Decision 与 Remote Preflight 同时成立时才允许规划 ActionBundle；
- 服务端生成受管 Push Branch 和 Draft PR 依赖图，页面逐项展示风险、Digest、有效期、依赖与精确参数；
- 精确 Confirmation 要求成员再次勾选完整 Bundle Digest，并提交当前 Bundle Version、Digest 与独立 Idempotency-Key；
- Push 与 Draft PR 分别展示 Dispatch、Receipt 和 ExternalResult，Push 成功而 PR 失败时保留 Push 成功事实；
- `UNKNOWN / RECONCILING / MANUAL_REVIEW` 明确表达只查询对账、尝试次数和人工队列，不提供直接 Dispatch、Claim 或重放入口；
- Webhook 与主动查询结果通过刷新权威详情进入页面，浏览器不从事件或 CommandReceipt 构造成功；
- 人工终结只支持 Owner 在确认 Provider 审计无外部对象后提交 `MANUALLY_FAILED`，绑定 Dispatch 强版本与原幂等键重试。

## 2. 浏览器数据边界

`delivery` 领域按 `organizationId + teamId + taskId + executionId` 隔离 GitHub 与 Action 资源。Connection、Binding、Repository、Health、Bundle 列表和强 ETag 详情使用独立 Resource；Scope 或 attempt 切换推进 generation、取消请求并阻止晚到响应写回。

Gateway 对 A06/A07 响应执行显式字段白名单。浏览器状态排除 Credential ID/Secret、Token、Webhook Secret、Provider Endpoint、任意 Remote URL、Grant 内部坐标、Worker ID、Lease、Fencing Token、内部幂等键、原始外部 ID、Business Key、Observation Key 和 Provider 原始错误。ExternalResult 只展示对象类型、安全身份 Hash、单调版本、来源和时间。

Action 规划只提交已批准 ReviewDecision ID、已选择 ProviderBinding ID、Catalog 的稳定 Repository ID、可选 Expected Remote Head、PR Title 与 Body。Branch、Delivery Head、Base、Draft 标记、依赖和风险由服务端生成并在预览中复验。公开 API 当前不返回规范 Draft PR URL 或 Number，因此页面展示 Receipt/ExternalResult 结果与安全 Hash，不从 Repository 名称和 Hash 猜测外链。未来外链只接受无用户名、无密码的 `https:` URL。

## 3. 命令与冲突协议

- Plan、Confirm、Cancel 和 Manual Resolution 每次新意图生成独立 Idempotency-Key；可重试失败保存原闭包并复用原 Key；
- Confirm 使用详情 ETag 对应的 Bundle Version，并发送页面展示的完整 64 位小写 SHA-256 Digest；
- Cancel 使用 Confirmation Version，只撤回仍未执行的 ACTIVE Confirmation；
- Manual Resolution 使用 Dispatch Version，只在 `MANUAL_REVIEW` 且说明不少于 10 个字符时开放；
- 409/412 立即丢弃陈旧命令并回读 Bundle 列表和强 ETag 详情；Digest、Review、责任、Binding、Grant、Policy、Safety、CodingTarget 或 Repository 变化后旧确认不可重试；
- Command 成功后只保存 Correlation ID 作为证据入口，并回读服务端权威 Bundle；客户端不乐观创建 Confirmation、Dispatch、Receipt 或 ExternalResult。

## 4. 状态与交互

工作台按 Connection/Repository、Action Plan、Bundle Facts、Push、Draft PR、Confirmation 的顺序阅读。Desktop 使用多列选择与参数摘要，390×844 Narrow 使用相同语义 DOM 降级为单列。

离线时保留已经读取的 Review、Catalog、Bundle 和回执，关闭 Catalog 同步、Preflight、Plan、Confirm、Cancel 与人工终结。Confirmation Dialog 显示 Repository、Version 与完整 Digest，要求显式勾选确认。`STALE` Bundle 展示失效原因并重新开放规划表单；旧 Bundle 保持只读证据。

`UNKNOWN` 与 `RECONCILING` 显示“只查询、不盲目重放”说明。Push Receipt 已成功而 PR Receipt 失败时，两个 Stage 保持独立结果；后续 Webhook 更新只推进 PR ExternalResult，不改写 Push Receipt。

## 5. 自动化验证

专项 Vitest 覆盖：

1. Connection/Binding/Repository 默认选择和 Remote Preflight；
2. Action 与 GitHub DTO 白名单、强 ETag 和稳定 Catalog ID；
3. 未批准 Review 失败关闭；
4. Digest 变化后的 412 权威回读；
5. Confirmation 同键重试且无乐观 Dispatch；
6. Push 成功、PR 失败的部分成功事实；
7. Webhook ExternalResult 单调刷新；
8. Scope 竞态隔离、离线关闭写操作和安全外链。

Playwright Contract Fixture 覆盖 TEAM Connection、Binding、Catalog、Health、Remote Preflight、Plan、Confirm 与权威详情，验证完整流程、Push/PR 分步结果、Webhook 更新、Desktop/Narrow 和视觉基线。主页面 Axe 门禁继续使用 WCAG 2.2 AA 标签。

验证命令：

```bash
pnpm --dir crewscope-web test
pnpm --dir crewscope-web build
pnpm --dir crewscope-web test:e2e
node scripts/check-doc-links.mjs
git diff --check
```

验证结果：70 个 Vitest 文件、309 项测试通过；150 项双视口 Playwright 全部通过；生产构建、Axe WCAG 2.2 AA、2 份 GitHub Delivery 视觉基线、文档链接与格式门禁通过。

## 6. 下一任务

下一任务是 `M5-F08`：收口 M5 模型、Agent、Review 与 Action 的全状态、响应式、键盘、ARIA Live、Reduced Motion、Histoire、视觉和敏感字段扫描。
