# M5-F06 Review Workbench

## 1. 交付结果

M5-F06 在 Task 详情中交付可恢复的 Review Workbench，打通 A05 ReviewRequest、M4 Diff/Test/Acceptance 与成员 Gate：

- 按 `organizationId + teamId + taskId + executionId` 读取 Review 修订历史，以 `reviewRequestId` 恢复详情深链接；
- 展示不可变 ContextPackage、Baseline/Delivery、DiffArtifact Hash、Changed Paths、精确 TestEvidence 与 Acceptance；
- Agent Finding 固定标记 `ADVISORY`，展示严重级别、类别、主张、建议修复与证据位置；
- Finding 路径与行号可定位到同一 Task 的 Diff Explorer，只读 Patch 继续使用 M4 Artifact 边界；
- `SELF_REVIEW` 明确不能形成 Gate Approval；
- 支持 Reviewer 运行/恢复，以及成员 `COMMENTED / APPROVED / CHANGES_REQUESTED / REJECTED`；
- `CHANGES_REQUESTED` 使用 modification 路由并展示连续修改轮次；
- `INVALIDATED / DIFF_CHANGED` Review 保留 Finding、Decision 与 Round 历史，只读且无新命令；
- Gate 资格提示来自当前 ACTIVE USER Reviewer 责任，服务端继续执行最终 Eligibility 与职责分离复验。

## 2. 浏览器契约

`HttpReviewGateway` 只接收 A05 成员安全 DTO。详情响应要求强 ETag 精确等于 body version；Patch、Prompt、Credential、模型原始输出、Tool Payload 和 Reasoning 不进入类型、Store 或 DOM。

Review Store 按 Team、Task、attempt 和 ReviewRequest 隔离资源。Scope 切换取消在途请求并推进代次，旧 Team 或旧 attempt 响应不能写回当前页面。同坐标的并发恢复复用在途读取，避免 Task/Coding 多个 watcher 相互取消请求；初始资源写入后通过 Vue reactive record 更新，保证异步状态可见。

Reviewer Execute、Decision 和 Modification 命令提交当前详情版本对应的 `If-Match` 与独立 `Idempotency-Key`。可重试失败保存原命令并复用原键；409/412 丢弃陈旧命令并回读权威列表与详情；403 进入共享 Access Boundary。客户端不乐观生成 Finding、Decision 或 ModificationRound。

## 3. Reviewer PolicySnapshot 编排边界

A05 创建与重新 Review 要求 `reviewerPolicySnapshotId`，但当前公开 API 没有向浏览器提供可选择的 Reviewer PolicySnapshot 目录。F06 不提供原始 UUID 输入，也不从 Agent、责任链或 Task Executor PolicySnapshot 推导该坐标。

ReviewRequest 由服务端绑定 Reviewer PolicySnapshot、最终 Diff 与精确 TestEvidence 后进入 Workbench。当前空态明确说明该编排边界。后续若增加创建入口，应先提供受授权、可选择且带版本/用途约束的服务端 Reviewer PolicySnapshot 投影。

## 4. 可访问性与响应式

- Gate Dialog 支持 Escape、Focus Trap、提交理由必填和 4,000 字符上限；
- Desktop 将 Diff 与 Test 并排，390×844 Narrow 按 Context、Diff、Test、Finding、Gate 顺序阅读；
- 失效状态使用可读 Alert，命令错误保留可重试语义；
- Axe 使用 WCAG 2.2 AA 标签检查 Workbench 与 Gate Dialog；
- 独立视觉基线：`review-workbench-desktop-chromium.png`、`review-workbench-narrow-chromium.png`。

## 5. 自动化验证

专项 Vitest 覆盖 Gateway 白名单与 ETag、Store Scope/竞态/幂等/冲突、Workbench Advisory/Gate/失效/修改、Task Drawer 集成。Playwright Contract Fixture 覆盖列表、详情、execute、decisions 和 modifications，并检查：

1. Context、Diff、Test 与 Acceptance 精确关联；
2. `SELF_REVIEW` 与 `ADVISORY` 文案；
3. Finding 文件与行号定位；
4. Reviewer Execute 的强版本和幂等键；
5. 键盘 Gate Dialog 与 `CHANGES_REQUESTED` 修改轮次；
6. `DIFF_CHANGED` 历史只读；
7. 无 USER Reviewer 的资格提示与服务端 403 失败关闭；
8. Desktop/Narrow、Axe 和视觉回归。

验证命令：

```bash
pnpm --dir crewscope-web test
pnpm --dir crewscope-web build
pnpm --dir crewscope-web test:e2e
git diff --check
node scripts/check-doc-links.mjs
```

验证结果：67 个 Vitest 文件、296 项测试通过；146 项双视口 Playwright 中 146 项通过；生产构建、Axe WCAG 2.2 AA、2 份 Review 视觉基线、文档链接与格式门禁通过。

## 6. 下一任务

下一任务是 `M5-F07`：GitHub Connection/Repository 选择、ActionBundle 风险与参数审查、精确确认、Push/Draft PR 状态和 UNKNOWN/Reconcile 结果。
