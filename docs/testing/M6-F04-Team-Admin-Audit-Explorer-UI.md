# M6-F04 Team Admin Audit Explorer UI 验证记录

## 1. 交付范围

M6-F04 交付 Control Mode 独立 `/audit` 页面、`audit:read` 路由与导航守卫、`governance:export` 命令守卫，以及 Audit/Correlation 的严格公开数据边界。

页面提供时间、Category、Outcome、Initiator、Actor、Agent Principal、Subject、ProviderBinding 和 Correlation 组合筛选，支持稳定 Cursor 分页、公开审计详情、Correlation 图分页、对象站内跳转和有界 JSON 导出。页面不提供原始 Payload 区域。

## 2. 安全与恢复边界

- Audit Category、Outcome、Retention 和 ActorType 使用闭集 Mapper；Schema Version 必须为正整数，External Operation Hash 必须为空或 64 位小写十六进制；
- Summary 最多 32 项，键和值执行长度与控制字符限制，并拒绝 Secret、Token、Credential、Authorization、Cookie、Payload、Prompt、Endpoint、Email 和 Phone 语义键；
- 导出响应复验 `rowCount === events.length`、`rowCount <= maximumRows <= 10,000`；页面要求在线、治理导出权限及不超过 31 天的显式时间范围；
- Correlation 只接受十类公开对象与两个公开来源；对象链接只允许无 Fragment 的 `/activity` 站内路径；
- Correlation 续页按 Event ID 和对象类型/ID 去重，合并 RelatedEventIds；Cursor 过期清除续页坐标并保留已加载图；
- Organization + Team Scope Generation 继续拒绝旧 Team 晚到响应。Team 切换清理审计身份、对象、Provider、事件和 Correlation 坐标，WorkProject 规范化保留 Team 级 Correlation；
- 查询权限与导出权限分层，浏览器守卫不替代服务端逐请求授权。

## 3. 产品状态与可访问性

- 列表覆盖 Loading、Empty、Error、Forbidden、Offline cached 和 CursorExpired cached/non-cached；
- 导出覆盖 Idle、Pending、Success、Error 和 Forbidden；Correlation 覆盖 Loading、Empty、Error、Forbidden、CursorExpired 和分页；
- Desktop 使用语义表格与粘性详情/Correlation 双列；Narrow 保持同一 table 语义并降级为详情优先的卡片阅读顺序；
- 详情打开后焦点进入标题，筛选、重置、分页、关闭、Correlation 与对象跳转均使用原生键盘控件；
- 双视口 Axe WCAG 2.2 AA、视觉基线和无横向溢出验证通过。

## 4. 自动验证结果

```text
Vitest                     78 files / 382 tests passed
Playwright                 166 tests passed（Desktop + Narrow）
M6-F04 Playwright           6 tests passed
Histoire                   11 stories / 65 variants built
Production build           passed
```

主要命令：

```bash
pnpm --dir crewscope-web test
pnpm --dir crewscope-web build
pnpm --dir crewscope-web story:build
pnpm --dir crewscope-web test:e2e
pnpm --dir crewscope-web check:sensitive
```

视觉基线：

- `crewscope-web/e2e/m6-audit.spec.ts-snapshots/m6-audit-desktop-chromium.png`
- `crewscope-web/e2e/m6-audit.spec.ts-snapshots/m6-audit-narrow-chromium.png`

## 5. 结论

M6-F04 完成。Team Admin 可以从传统管理入口查询和关联公开审计事实；治理导出保持有界、可见、受权限控制，浏览器不接触原始 Payload。下一任务为 `M6-F05`。
