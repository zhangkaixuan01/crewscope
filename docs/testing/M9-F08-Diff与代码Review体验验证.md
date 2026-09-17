# M9-F08 Diff 与代码 Review 体验验证

## 范围

本任务重建 Coding Diff Explorer 的阅读与 Review 交互：统一/分栏视图、旧新行号、上下文折叠、变更行键盘导航、文件已查看状态、全屏阅读、生成/二进制/大文件降级及行级评论。

## 实现要点

- Patch 由结构化行模型解析，行号、hunk header 与内容摘要随行保留；未识别语言降级为纯文本。
- Unified/Split 视图与已查看文件使用版本化偏好存储，刷新后恢复。
- 连续上下文超过 8 行时默认折叠，展开不会改变服务端 Diff 坐标；渲染预算最多 2,000 行。
- `n/p` 在变更行之间循环跳转；Diff 支持浏览器全屏。
- 生成目录、二进制内容和超预算文件展示明确降级说明，不把不可读内容伪装成源码。
- 行级评论通过 `ReviewGateway` 调用 A04 评论 API，锚点提交 file、side、line、hunk、hash 与 generation；`OUTDATED` 或已删除评论只读。
- 评论正文复用 `SafeMarkdown`，由 markdown-it 与 DOMPurify 处理不可信内容。

## 自动化验证

```bash
pnpm --dir crewscope-web exec vitest run src/components/domain/CodingDiffExplorer.spec.ts
pnpm --dir crewscope-web test -- --run
pnpm --dir crewscope-web build
node scripts/check-openapi-drift.mjs
node scripts/check-doc-links.mjs
node scripts/check-web-sensitive-fields.mjs
git diff --check
```

当前结果（计数已于 2026-09-17 收口时重新实测）：前端全量 **160 个测试文件、924 个测试**通过；生产构建与 OpenAPI、文档链接、敏感字段、空白检查、M9-Q01 质量门禁通过。Diff Explorer 专项覆盖视图切换、语言识别、行级评论提交与 Markdown 安全渲染、二进制提示、实时序列缺口及大文件树预算。3000 行 Diff 的 Chromium 渲染预算由 `e2e/m9-performance.spec.ts` 验证并纳入 M9-Q01。

## 语法高亮：换成 S01 冻结的 Prism（2026-09-17）

上一版的高亮是自研正则（`syntaxSegments`），只认少量关键字，而 `M9-S01` 的选型 Spike 冻结的是 Prism。本轮换成 Prism，实现在 `src/domains/coding/syntax.ts`（纯函数，渲染仍由 Diff 阅读器承担）：

- **Token 流，不是 HTML 字符串**。`Prism.highlight()` 返回 HTML，注入它就得信任这条字符串、并重新审计 DOMPurify 的清洗面；`Prism.tokenize()` 返回 Token 树，由调用方渲染成元素，**patch 里的任何字符串都不会变成标记**。因此公开入口只返回 Token 数组，HTML 路径从不被调用（这正是 S01 §3.3 的选型理由）。
- **语言包按文件类型动态 import**。首屏只加载当前文件的语言；每个语言列出它需要的完整语法依赖链（顺序有意义：Prism 的语法文件在模块作用域执行 `extend('<parent>')`，父语法缺失会**抛错**而不是降级，`tsx` 必须排在 `jsx` 与 `typescript` 之后；每个条目仍显式列出它需要的一切，避免"能跑是因为本次核心包恰好带了什么"）。
- **加载失败回退纯文本**，不阻塞 Diff：`loadSyntax` 返回 false，阅读器显示无高亮的文本——与这个文件存在之前的表现一致。因为一个语法分片慢而打不开 Diff，是比没有颜色更差的交易。
- 主题不绑第三方配色：Token 类型（`plain`/`keyword`/`string`/`comment`/`function`/`number`/`type`）直映射既有 CSS Token，明暗双主题共用一份映射。
- 自研 `syntaxSegments` 已删除，避免两套高亮并存。

证据：`syntax.spec.ts` 对**每一个支持的语言**做真实 tokenize（依赖链写错会在这里失败，而不是表现为某个文件类型悄悄变纯文本）；`syntax-fallback.spec.ts` 覆盖加载失败回退；浏览器档 `Diff Explorer highlights a whole Patch inside the reading budget` 与 `Diff Explorer highlights the same Patch under the dark theme` 断言真实渲染与暗色下的对照。

包体积实测（2026-09-17 收口，`pnpm build` + `scripts/check-web-bundle-budget.mjs`）：入口分片 `index-*.js` 405,794 bytes（gzip 126.40 kB）——**`grep -c Prism dist/assets/index-*.js` = 0，Prism 的任何一个字节都不在入口分片里**；15 个 `prism-*` 语言分片合计 33,511 bytes（gzip 10,466 bytes），按文件类型动态 import、按需加载；Prism 核心（`prismjs` 的静态 import）落在 `WorkPage-*.js`（277,246 bytes，与 Diff 阅读器同片）。门禁结论：最大分片 405,794 bytes ≤ 500 KiB、总 JS 1,371,802 bytes ≤ 2 MiB，PASS。

## 手工验收

1. 打开 Coding attempt，切换 Unified/Split，刷新页面确认视图偏好保留。
2. 选择文本文件，确认旧/新行号、变更行颜色与 `n/p` 导航；点击上下文折叠条确认可展开。
3. 点击变更行的 `＋`，提交 Markdown 评论，确认评论出现在文件下方且脚本标签不执行。
4. 让同一评论锚点失效后重新读取 Review，确认显示 `OUTDATED` 且没有编辑入口。
5. 打开二进制、生成目录和超预算文件，确认各自降级提示；使用全屏按钮确认浏览器全屏可用。
