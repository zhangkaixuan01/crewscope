# M9-F01：Design System v2 与反馈闭环

> 状态：已完成（基础组件层与反馈基础设施）

## 交付

- `tokens.css` 建立七档字号、十一档间距、四档断点、十档层级、双密度、动效曲线与明暗语义 Token。
- 基础组件补齐 Input、Select、Textarea、Checkbox、Radio、Switch、Badge、Tag、Tooltip、Popover、Dropdown、Dialog、Drawer、Tabs、Table、Card、EmptyState、Skeleton。
- `useFocusTrap`、`useDialog`、`useToast`、`useConfirm` 与全局 Toast/Confirm Host 建立统一交互基础设施。
- `BaseButton`、`StatusBadge`、`StatePanel` 迁移到语义 Token，Foundation Histoire 增加控件与骨架屏变体。
- `StatusBadge` 保持默认只读外观，同时提供可选 `interactive + availableActions` 动作入口，动作事件仍交由页面权限与服务端裁决。
- GitHub Connection 撤销已切换到 `useConfirm()`，生产代码不再调用原生 `window.confirm`。
- 全站 1215 处硬编码排版声明迁到七档阶梯，可输入控件字号抬到 `--cs-text-base` 下限，逐档对照见 [M9-F01 字号迁移对照表](M9-F01-字号迁移对照表.md)。
- 全站 2157 处硬编码间距取值迁到十一档按值命名的刻度（迁移前只有 36% 落在档位上），26 处裸 `z-index` 迁到十档语义档，逐值对照见 [M9-F01b 间距与层级迁移对照表](M9-F01b-间距与层级迁移对照表.md)。
- `scripts/check-design-tokens.mjs` 接入 CI：排版、层级与间距三条规则按**全站**口径（113 个文件）无基线执行；动效与断点规则仍限 24 个基础件——断点收敛是行为变更，不做机械改写。

## 验证

```bash
node scripts/check-design-tokens.mjs
cd crewscope-web && pnpm test -- --run src/components/base/components.spec.ts
cd crewscope-web && pnpm check:quality && pnpm build
```

页面级存量 Dialog 与裸 `title` 由 F02/A02/Q01 按门禁收口；断点收敛见 §10.3.2 的后续批次。
