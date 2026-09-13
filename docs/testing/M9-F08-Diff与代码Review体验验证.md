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

当前结果：前端全量 128 个测试文件、708 个测试通过；生产构建与 OpenAPI、文档链接、敏感字段、空白检查通过。Diff Explorer 专项覆盖视图切换、语言识别、行级评论提交与 Markdown 安全渲染、二进制提示、实时序列缺口及大文件树预算。

## 手工验收

1. 打开 Coding attempt，切换 Unified/Split，刷新页面确认视图偏好保留。
2. 选择文本文件，确认旧/新行号、变更行颜色与 `n/p` 导航；点击上下文折叠条确认可展开。
3. 点击变更行的 `＋`，提交 Markdown 评论，确认评论出现在文件下方且脚本标签不执行。
4. 让同一评论锚点失效后重新读取 Review，确认显示 `OUTDATED` 且没有编辑入口。
5. 打开二进制、生成目录和超预算文件，确认各自降级提示；使用全屏按钮确认浏览器全屏可用。
