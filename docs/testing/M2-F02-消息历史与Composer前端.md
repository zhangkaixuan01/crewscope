# M2-F02：消息历史与 Composer 前端

> 日期：2026-08-11<br>
> 状态：已完成<br>
> 工程：`crewscope-web`

## 目标

把 M2-A01/A02 的 Message 历史与用户消息追加 API 接入 Conversation 工作区，建立可续页、可恢复、可幂等重试的真实消息交互。

## 交付范围

### Gateway 与历史 Store

- 接入 Team/Conversation Scope 下的 Message 历史与追加 API；
- 将服务端按 Sequence 倒序返回的页转为正序展示；
- 不解析、不拼接不透明 Cursor，续页合并按 Message ID 去重；
- 历史请求使用 AbortController、Scope Key 和版本裁决隔离 Conversation 切换竞态；
- 页面仅展示服务端 Message 事实和明确的本地 Pending，不构造 Agent 回复或执行结果。

### 发送、收口与失败恢复

- 每次新发送创建客户端 Pending 和独立 `Idempotency-Key`；
- 发送 API 只返回 CommandReceipt，客户端在响应后刷新最新历史；
- 使用“新增 Sequence + 当前作者 + 相同内容”将 Pending 收口到服务端 Message 事实；
- 网络或回读失败时保留 Pending 和错误，重试复用原 `Idempotency-Key`；
- 发送失败恢复原输入；用户后续输入的草稿不被重试覆盖；
- 同一 Conversation 在一次发送收口前禁止重复发送，避免客户端命令竞态。

### Composer 与安全 Markdown

- USER、AGENT 和 SYSTEM 消息使用可辨别的语义和浅色视觉样式；
- Composer 支持 Enter 发送、Shift+Enter 换行、输入法组合态保护和 50,000 字符上限；
- Markdown 解析禁用原始 HTML，经 DOMPurify 执行标签和属性白名单清理；
- 链接仅允许 `http`、`https`、`mailto` 和页内锚点，外部链接增加 `target="_blank"` 与 `rel="noopener noreferrer"`；
- 桌面端保留会话列表、消息工作区和 Participant 观察面；窄屏 Composer 固定在底部导航上方的安全工作区内。

## 自动化验证

```bash
cd crewscope-web
pnpm test:coverage
pnpm build
pnpm story:build
pnpm test:e2e
```

当前结果：

- Vitest：19 个测试文件、78 个测试通过；覆盖 Gateway URL/Cursor/Idempotency-Key、Store 排序与去重、Scope 取消、Pending 收口、失败保留与同键重试、Composer 键盘交互和 Markdown 清理；
- Coverage：Statements 86.14%、Branches 80.46%、Functions 85.87%、Lines 87.36%，全部高于前端 Release Gate；
- TypeScript 检查与 Vite 生产构建通过；
- Histoire：4 个 Story、12 个 Variant 构建通过；
- Playwright：25 个场景在桌面 1440×960 与窄屏 390×844 共执行 50 次；Conversation 场景覆盖历史恢复、Markdown 发送、刷新恢复、Pending、CommandReceipt 收口、失败恢复、同键重试、新草稿保留、Cursor 续页与去重；
- Conversation 桌面与窄屏视觉基线已更新，Axe WCAG 2.2 AA 自动检查通过；
- 文档链接和 `git diff --check` 纳入阶段最终检查。

## 阶段边界

M2-F02 完成真实 Message 历史、用户消息追加、幂等恢复和安全 Markdown。下一项 M2-F03 接入 M2-A03/A04 的 Personal Agent Invocation、AG-UI 流式回复、Conversation Event 合并、断线恢复与取消，不改变本阶段固定的消息历史、幂等和安全渲染边界。
