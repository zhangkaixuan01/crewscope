# M9-F04 Conversation 体验验证

## 验收范围

- 三栏 Conversation 在桌面端可通过拖拽条调整左右栏宽度，比例写入 `localStorage`；分隔条支持左右方向键。
- 左侧对话列表与右侧参与者面板可独立折叠，390px 视口降级为列表/详情堆叠。
- 消息历史采用固定高度窗口化渲染，保留向上加载、未读分隔线和“跳到最新”；刷新或切换对话后恢复滚动位置。
- 消息时间统一显示相对时间，悬浮提示提供带时区的绝对时间；流式回复与 HITL 操作区位于会话尾部。
- TaskIntent、WorkItem、Task 关联以可折叠结构化区块展示；Composer 支持多行自适应、Slash 入口、附件占位、字数和模型预算提示。
- `prefers-reduced-motion: reduce` 时跳转和交互不使用平滑动画，键盘 Tab 顺序和 `aria-live` 播报保持可用。

## 本地验证

```bash
pnpm --dir crewscope-web exec vitest run src/components/domain/ConversationComposer.spec.ts src/composables/useVirtualList.spec.ts
pnpm --dir crewscope-web build
```

使用 Playwright 在 390px、1280px、1440px 视口检查折叠、堆叠、发送和长会话滚动；性能基线使用 5000 条消息 fixture，记录滚动帧率与堆内存，不以完整 DOM 节点数作为性能实现前提。
