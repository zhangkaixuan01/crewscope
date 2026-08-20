# M4-F06：Evidence 只读面板与有界 Artifact

> 状态：已完成<br>
> 日期：2026-08-20<br>
> 模块：`crewscope-web`

## 目标

在 Task Execution Studio 交付 CommandEvidence、TestEvidence、Acceptance、命令日志和测试报告观察面。页面提供团队成员可理解的执行证据、完整性校验和 Artifact 下载，同时保持严格只读的 Coding 命令边界。

## 页面能力

- CommandEvidence 按 Sequence 展示 CommandKind、ToolKey、Termination、ExitCode、执行时长、Timeout、摘要与失败分类；
- TestEvidence 按发布 Sequence 展示 Total、Passed、Failed、Errors、Skipped、摘要与失败分类；
- Acceptance 按 Criterion Index 稳定排序，展示验收标准、结论与证据摘要；
- Command 与 Test 集合沿用 Scope 化 Keyset Cursor，加载更多时去除重叠 Evidence ID；
- 日志和报告按需读取，首屏不自动传输 Artifact 内容；
- 完整内容提供下载，文件名来自服务端 `Content-Disposition`。

桌面使用 Command 列表与证据详情双栏。390×844 窄屏按 Command 列表、命令详情、日志、TestEvidence、Acceptance 和报告顺序阅读。列表按钮、日志与报告滚动区支持键盘访问。

## 有界传输与完整性

Gateway 只调用 A06 提供的固定关系入口：

```text
GET .../coding/commands/{commandEvidenceId}/log
GET .../coding/test-evidence/{testEvidenceId}/report
```

每次请求使用 `offset + limit` 读取 64 KiB。Store 以 `taskId + executionId + evidenceId` 分区内容，逐页验证 Offset、Body Length、Total Size、ETag、Content-Type 和文件名保持稳定。下载名从 `Content-Disposition` 提取，移除路径段并拒绝空值、`.`、`..`、控制字符和超过 255 字符的名称。完整内容继续复验 Artifact Descriptor 的 Size、SHA-256 与严格 UTF-8。浏览器内容预算为 8 MiB。

429、网络失败和其他分页错误保留已经验证的字节前缀、原 Total Size 与原 Offset。成员可以从失败页继续读取。attempt 或 Task 失效时同步终止请求并清除 Command Log 与 Test Report 缓存。

## 安全边界

- 页面没有命令输入、命令编辑、重跑命令、任意 Shell 或 ContentEditable；
- URL 只由 Task、attempt 和 Evidence 关系构造，不接受任意 Artifact ID、路径或 URL；
- Gateway 对日志限定 `text/plain`，对报告限定 `text/plain`、JSON 与 XML；
- ANSI、HTML、JSON 与 XML 全部作为文本节点展示，不使用 `v-html`；
- Token、Password、Secret、Bearer 与 API Key 常见形态在显示层再次遮蔽；
- 浏览器 DTO 白名单继续丢弃 TypedArgv、StorageUri、HostPath、Container 与 Token；
- 403 进入共享 Access Denied 边界；
- 下载 Blob 使用已完成完整性校验的字节与通过浏览器二次校验的服务端文件名。

## 验证

专项覆盖 Gateway 固定关系 URL、Range 坐标、Content-Type 与服务端文件名，Store 内容拼接、SHA-256、429 部分页保留，组件测试统计、验收排序、敏感内容遮蔽、按需读取和只读边界。Playwright 在桌面与 390×844 覆盖真实交互、日志与报告读取、下载入口、键盘焦点和窄屏顺序。

验证命令：

```bash
cd crewscope-web
pnpm test
pnpm build
pnpm exec playwright test
```

Markdown 链接与 `git diff --check` 同步进入发布前检查。

全量 Vitest 共 52 个测试文件、227 项测试通过。前端 TypeScript 检查与生产构建通过。Playwright 在 desktop Chromium 与 390×844 narrow Chromium 共 118 项通过，包含 WCAG 2.2 AA、现有视觉基线、M4-F06 双视口交互和 Artifact 403 权限边界。
