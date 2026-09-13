# M9-F01：Design System v2 与反馈闭环

> 状态：已完成（基础组件层与反馈基础设施）

## 交付

- `tokens.css` 建立七档字号、十档间距、四档断点、层级、双密度、动效曲线与明暗语义 Token。
- 基础组件补齐 Input、Select、Textarea、Checkbox、Radio、Switch、Badge、Tag、Tooltip、Popover、Dropdown、Dialog、Drawer、Tabs、Table、Card、EmptyState、Skeleton。
- `useFocusTrap`、`useDialog`、`useToast`、`useConfirm` 与全局 Toast/Confirm Host 建立统一交互基础设施。
- `BaseButton`、`StatusBadge`、`StatePanel` 迁移到语义 Token，Foundation Histoire 增加控件与骨架屏变体。
- `StatusBadge` 保持默认只读外观，同时提供可选 `interactive + availableActions` 动作入口，动作事件仍交由页面权限与服务端裁决。
- GitHub Connection 撤销已切换到 `useConfirm()`，生产代码不再调用原生 `window.confirm`。
- `scripts/check-design-tokens.mjs` 接入 CI，覆盖已迁移的 foundation/feedback 层，后续页面迁移时扩大扫描范围。

## 验证

```bash
node scripts/check-design-tokens.mjs
cd crewscope-web && pnpm test -- --run src/components/base/components.spec.ts
cd crewscope-web && pnpm check:quality && pnpm build
```

页面级存量 Dialog、裸 `title` 与全站字号迁移由后续 F02/F10/Q01 按门禁收口。
