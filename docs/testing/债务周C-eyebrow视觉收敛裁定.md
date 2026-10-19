# 债务周-C：eyebrow 视觉收敛裁定 — 完成记录

> 来源：M12b-Q01 完成记录 §1 第 9 行（「eyebrow 不收敛（裁定）」——4 处本地定义与全局版分叉，超出 Q01 零视觉变更预算，登记待视觉专项裁定）。
> 裁定：**最小纠偏**（用户选定，2026-10-19）。性质：纯前端 1 行 CSS 改动。

## 1. 勘察与裁定

**现状矩阵**（本地定义 vs 全局 `src/design/layout.css` `.eyebrow`）：

| 位置 | 颜色 | 字号 | 字距 | 布局 |
| --- | --- | --- | --- | --- |
| 全局 layout.css（28 文件消费） | brand | sm | .08em | block，margin-bottom space-8 |
| AgentMemorySection（scoped `.memory-section .eyebrow`） | muted | xs | .08em | block，margin-bottom space-4 |
| OperationsWorkspace | brand | xs | **.1em** | block，margin-bottom space-4 |
| SearchPage（首处带图标） | muted | xs | .08em | **flex+gap+items-center**，margin 0 |
| GitHubSettingsPage | brand | xs | .08em | block，margin 0（父级 panel-heading 控距） |

**裁定**：
- 字距 `.1em` 是唯一无理由分叉（全站其余 eyebrow 一律 .08em，OperationsWorkspace 无任何记录解释差异）——**修正**。
- xs/sm（4 处本地一致选 xs vs 全局 sm）、brand/muted（2:2 语义分布：面板 kicker 用 brand、辅助信息 kicker 用 muted）、block/flex（SearchPage 图标行需要行内布局）登记为**有意变体**，不收敛——统一字号或颜色需动全局版（28 文件快照全漂移）或抹平语义区分，均超出债务周轻量预算；出现统一诉求时再立项。

## 2. 改动

- `crewscope-web/src/components/domain/OperationsWorkspace.vue`：本地 `.eyebrow` 字距 `.1em`→`.08em`（1 行）。

## 3. 测试证据

| 面 | 结果 |
| --- | --- |
| OperationsWorkspace.spec.ts | 9/9 |
| CI 四独有门禁 | design-tokens（181 文件）/ enum-labels（180 模板）/ sensitive（93 生产文件）/ disabled-reasons（0 unexplained）全 PASS |
| vitest 全量 | 1903/1903 |
| lint / check:q01 / build | 全过 |
| e2e 全量 | 681 passed + 7 skipped（11.2m，exit 0）——快照零漂移（字距值不入 DOM 快照，符合预期） |

## 4. 复跑入口

```sh
cd crewscope-web
pnpm exec vitest run src/components/domain/OperationsWorkspace.spec.ts
node ../scripts/check-design-tokens.mjs && node ../scripts/check-web-enum-labels.mjs \
  && node ../scripts/check-web-sensitive-fields.mjs && node ../scripts/check-disabled-reasons.mjs
```
