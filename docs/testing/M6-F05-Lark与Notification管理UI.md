# M6-F05 Lark 与 Notification 管理 UI 验证记录

## 1. 交付范围

M6-F05 交付 Control Mode 独立 `/settings/integrations/lark` 页面与 `provider:manage` 导航/路由守卫。页面包含 Connection 创建、轮换、撤销、Preflight、Health、精确成员验证与映射、固定模板偏好、DND、通知投递历史、安全详情和失败再次投递。

## 2. 安全与并发边界

- Tenant Key、App ID 与 App Secret 只存在于 Credential Dialog 局部状态，成功、关闭和 Scope 切换后清空，不进入 Store、URL、Receipt 或公开 DTO；
- 当前 Principal 通过 Team Member 目录精确解析内部 Member。`open_id` 只进入一次成员验证调用并立即清空；验证 Receipt 的 `domainEventId` 作为 Proof ID，映射确认不接触外部身份；
- Connection DTO 分别公开 Credential Version 与 ProviderBinding Version。轮换/撤销使用 Credential ETag，Preflight/成员验证使用 Binding Version；ProviderBinding ID 与 Version 必须同时存在或同时为空，不完整坐标在应用 DTO 与浏览器 Gateway 两侧失败关闭；
- Mapping、Template、Variable、Inbox Type、Delivery Status、Failure Code 和 Health Status 使用闭集 Mapper，未知值失败关闭；
- Mapping/Delivery Cursor 与筛选绑定。CursorExpired 和 Offline 保留已加载事实；Conflict 回读权威资源，不自动重放 Secret、Proof、撤销、偏好或重投命令；
- 只有 `FAILED_FINAL` Delivery 显示可执行的再次投递，命令绑定详情强 ETag 和新的 Idempotency-Key；
- Team 切换清除 Connection、Mapping、Delivery、筛选与 Tab 坐标；WorkProject URL 规范化保留 Team 级集成坐标。

## 3. 产品状态与可访问性

- Connection、Mapping 和 Delivery 覆盖 Loading、Empty、Error、Forbidden、Offline、CursorExpired 和 Conflict；命令展示持久化 Receipt；
- Desktop 使用 Connection 双列和 Notification 三列，Narrow 使用详情优先的单列；筛选与表格降级无横向溢出；
- Credential 字段使用密码输入和浏览器自动填充关闭策略；Dialog 打开后聚焦标题、约束 Tab 焦点、支持 Escape 关闭并恢复触发按钮焦点；筛选、Tab、DND、映射确认、关闭与重投均使用原生键盘控件；
- 双视口 Axe、键盘焦点、视觉基线和纵向 E2E 覆盖精确映射、Secret 清除、DND、失败重投与强版本 Header。

## 4. 自动验证

主要命令：

```bash
pnpm --dir crewscope-web test
pnpm --dir crewscope-web build
pnpm --dir crewscope-web story:build
pnpm --dir crewscope-web test:e2e
pnpm --dir crewscope-web check:sensitive
```

验证结果：

- Vitest `393 / 393`，其中 M6-F05 Component/Gateway 专项 `30 / 30`；
- Playwright、视觉与 Axe `170 / 170`，其中 M6-F05 Desktop/Narrow 纵向闭环与视觉/Axe `4 / 4`；
- Java 全模块编译/打包通过，Lark Administration Controller 契约专项 `4 / 4`；全量后端回归在 Infrastructure `656 / 657` 后因既有 `JdbcOperationsRecoveryRepositoryM6I02IntegrationTest` 审计摘要安全值错误停止，本次涉及的 Domain、Application、AgentScope Adapter 与 Integration 模块均通过；
- Production Build 通过；Histoire Build `12` 个 Story、`75` 个 Variant；
- Web 敏感字段门禁通过，覆盖 `20` 个生产文件与 `12` 个 Story；
- 文档链接检查通过，覆盖 `292` 份 Markdown。

视觉基线：

- `crewscope-web/e2e/m6-lark-notification.spec.ts-snapshots/m6-lark-notification-desktop-chromium.png`
- `crewscope-web/e2e/m6-lark-notification.spec.ts-snapshots/m6-lark-notification-narrow-chromium.png`

## 5. 结论

M6-F05 完成。Team 管理员可以在传统管理入口安全维护飞书连接、精确成员映射和固定模板通知闭环；浏览器不保留 Secret、外部身份或 Provider 原始数据。下一任务为 `M6-F06`。
