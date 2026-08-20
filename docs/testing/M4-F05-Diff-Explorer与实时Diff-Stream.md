# M4-F05：Diff Explorer 与实时 Diff Stream

## 1. 交付范围

M4-F05 在 Task Execution Studio 交付 Diff Explorer：

- 按仓库相对路径组织的层级文件树；
- 新增、修改、删除、重命名、复制和二进制变更状态；
- 文件数、增删行与 Diff Generation 累计统计；
- 当前或历史 attempt 的选中文件 Patch；
- Task Cursor 续传、RESET、DELTA、断序关闭与权威快照对账；
- 桌面双栏和 `390×844` 窄屏顺序阅读。

文件树最多同时渲染 400 个匹配文件。大型 Diff 保留完整统计，通过路径筛选缩小可见集合，避免一次创建上万个 DOM 节点。Patch 最多渲染 2,000 行，服务端 `patchTruncated` 与浏览器渲染截断都提供明确提示。

## 2. 实时投影协议

Diff Explorer 复用 M3 Task Store 的 JSON 历史与 SSE 连接，不建立第二条实时通道。Task Cursor 按 Organization、Team 和 Task 隔离，浏览器 SessionStorage 只保存不透明 Cursor。

投影规则：

1. `WORKSPACE_DIFF_RESET` 完整替换当前 Epoch、Sequence、Generation、Manifest Hash 和文件集合；
2. `WORKSPACE_DIFF_DELTA` 只接受相同 Epoch 且 `sequence = current + 1` 的事件；
3. 重复或迟到 Sequence 不改变当前投影；
4. 新 Epoch RESET 替换旧 Epoch；
5. 乱序、缺失字段、超大嵌套列表、Cursor 过期和 `projectionGap=true` 停止增量合并；
6. attempt 权威 DiffManifest 可用时完成 Reset Reconcile，权威快照不可用时显示 Gap 并提供重新读取入口。

Task Store 继续负责 Event ID 与 Domain Event ID 去重、`Last-Event-ID` 续传、指数退避和 `410 cursor_expired` 清理。SSE 事件同时触发 Task Runtime 与 Coding attempt 的合并刷新。

## 3. Patch 内容边界

实时 Timeline 只接受 canonical 仓库相对路径、固定变更枚举、非负安全整数、Boolean 和 64 位十六进制 Patch SHA-256。任一嵌套文件事实不满足形状时，事件投影进入 Gap 并等待权威快照。Patch 文本通过 M4-A06 的固定关系入口读取：

```text
GET /api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}
    /attempts/{executionId}/coding/artifacts/patch?offset=<offset>&limit=262144
```

前端按 256 KiB 顺序读取，验证每页起点、长度、总大小与 ETag 稳定，并复验 attempt Artifact Descriptor 的 `sizeBytes` 和 SHA-256。所有字节完成后使用严格 UTF-8 解码，再按服务端 Manifest 路径与解码后的精确 `diff --git` Header 提取单文件 Git Patch。正文中的 `+++`、Rename 文本和相似路径不参与定位。二进制文件不读取或展示内容。HTML、脚本和 ANSI 字符作为纯文本呈现。

浏览器状态不保存宿主路径、Worktree、容器标识、镜像、命令 argv、环境变量、Token、Credential、Artifact Storage URI、AgentState 或 reasoning。

## 4. 状态与可访问性

- Loading：读取 Task Diff 历史；
- Empty：尚未产生代码变更；
- Connected：SSE 已连接并同步文件投影；
- Reconnecting：保留当前耐久投影并从不透明 Cursor 续传；
- Reconciled：序列缺口已由权威 DiffManifest 替换；
- Gap：停止不安全增量合并并提供重新读取；
- Patch Loading/Error：独立于文件树保留已加载事实；
- Binary：展示类型化空态；
- Large Diff：限制可见文件和 Patch 行数并保留完整统计。

文件按钮使用 `aria-pressed` 表达选择，实时状态使用 `aria-live`，Patch 代码区可通过键盘聚焦和滚动。窄屏 DOM 顺序固定为统计、文件树、选中文件与 Patch。

## 5. 自动验证

- `pnpm test`：51 个测试文件、222 项测试通过，覆盖公开事件二次白名单、RESET、DELTA、重复、乱序、权威对账、分页拼接、Size/ETag/SHA-256、文件树、Patch 提取、Git 引号路径、重命名、删除、Binary 和 405 文件筛选；
- `pnpm test:e2e`：desktop-chromium 与 `390×844` narrow-chromium 共 114 项通过，覆盖文件树、累计统计、单文件 Patch、Binary、403、安全字段排除和响应式阅读顺序；
- Axe WCAG 2.2 AA 与 Task 详情视觉基线通过；
- `pnpm build`：Vue TypeScript 检查与 Vite 生产构建通过；
- `node scripts/check-doc-links.mjs`：180 份 Markdown 文档链接通过；
- `git diff --check`：通过。

## 6. 下一项

M4-F06 在 Execution Studio 交付 Command/TestEvidence、退出码、时长、测试统计、有界日志和 Artifact 下载。
