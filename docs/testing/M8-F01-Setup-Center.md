# M8-F01 Setup Center 验证记录

## 范围

- 新增 `/setup` Team-scoped Setup Center 页面，并从 Today 和侧边栏提供入口。
- 使用 `GET /api/v1/organizations/{organizationId}/teams/{teamId}/setup-readiness` 展示六项能力、必需能力进度、责任方和下一步动作。
- 只在服务端返回 `ACTION_REQUIRED + canConfigure + actionKey` 时展示站内配置入口；`BLOCKED` 展示责任方，`UNAVAILABLE` 展示可重试状态，离线时保留已加载事实。
- actionKey 跳转至 Agent、Today/WorkProject、GitHub/Repository、飞书配置页面；GitHub 仓库导入沿用 M8-A02 的 RepositorySettings 入口。

## 测试矩阵

| 场景 | 预期 |
|---|---|
| 桌面宽屏 | Hero、进度摘要、下一步和六项能力卡片完整展示 |
| 390px 移动端 | 卡片单列排列，操作按钮位于内容下方，底部导航不遮挡正文 |
| 键盘访问 | 入口、刷新和动作按钮可聚焦，状态使用 `aria-live` |
| 离线 | 展示离线状态，不允许写配置动作 |
| 部分能力失败 | 其他能力仍可展示；失败项显示稳定文案和刷新入口 |
| 权限不足 | 不展示 actionKey 写入口，仅提示责任方 |
| Team 切换 | 清除旧快照，加载新 Team，防止跨 Team 污染 |
| 不完整/越界响应 | gateway 拒绝快照，不渲染未验证字段 |

## 本地验证

```bash
pnpm --dir crewscope-web run build
pnpm --dir crewscope-web run test -- --run src/domains/setup/gateway.spec.ts src/domains/setup/store.spec.ts
pnpm --dir crewscope-web run check:sensitive
git diff --check
```
