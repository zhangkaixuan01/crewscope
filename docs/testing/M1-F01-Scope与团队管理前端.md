# M1-F01：Scope 与团队管理前端

> 日期：2026-08-08<br>
> 状态：已完成<br>
> 工程：`crewscope-web`

## 目标

把 M1-A01 Team API 与 M1-A03 WorkProject API 接入 Web 工作台，形成可恢复、可切换、具备权限反馈的 Team/WorkProject 范围，并交付 Today、Work 和团队成员三个管理入口。Conversation 与管理入口继续操作同一份范围事实。

## 交付范围

### Scope Gateway 与 Store

- `HttpScopeGateway` 映射 Team 列表、WorkProject 列表、Key Availability、WorkProject 创建、成员列表和成员添加 API；
- `ScopeStore` 按 Organization → Team → WorkProject 加载可访问范围；
- Store 区分 `idle/loading/ready/empty/error`，成员查询、成员命令与 WorkProject 命令使用独立状态；
- WorkProject 创建使用 Idempotency-Key，成功后回读 ACTIVE 项目、选中新项目并更新 URL；命令完成但投影延迟时保留原请求重试语义；
- 成员添加生成独立 `Idempotency-Key`，完成后回读成员事实；
- OIDC 写请求自动把 `XSRF-TOKEN` Cookie 作为 `X-XSRF-TOKEN` Header 发送，Bootstrap 模式没有 Cookie 时不增加 Header；
- Team 和 WorkProject 只保留 `ACTIVE` 对象，待初始化 Team 不读取 WorkProject；
- 原生浏览器 `fetch` 使用 `globalThis` Receiver 调用，避免存储函数引用导致 `Illegal invocation` 被错误归类为网络失败。

### URL 与路由

```text
/conversation   对话执行入口
/today          Team 当日工作入口
/work           WorkProject 管理入口
/team/members   Team Membership 管理入口
/access-denied  未授权反馈
/control        兼容跳转到 /today
```

- `team` 与 `project` Query 保存稳定 UUID；
- 页面刷新优先恢复 URL 指定范围；无效或缺失范围规范化为第一个可访问 Team/WorkProject；
- Team 切换清除不兼容的 Project 与 Focus，Project 切换保留当前入口；
- Conversation 与管理入口切换时保留 Team、WorkProject 和 Focus；
- 未授权路由跳转到 `/access-denied`，`from` Query 记录原目标。

### 页面与交互

- `ScopeSwitcher` 在桌面侧栏、折叠侧栏和窄屏顶栏提供 Team/WorkProject 切换、创建、Loading、Empty、Error 与 Escape 关闭；
- Today 展示真实 Team、WorkProject 和 Active Membership 摘要，并提供 WorkProject 创建以及 Work、成员与 Conversation 快捷入口；
- WorkProject 创建弹窗提供格式约束、服务端 Key 可用性检查、Focus Trap、Escape、窄屏底部 Modal 与安全错误反馈；
- Repository Settings 在缺少 WorkProject 时复用同一创建弹窗，成功后原地加载新项目的 RepositoryBinding 管理范围；
- Work 在缺少项目时提供首个 WorkProject 创建入口；创建成功后更新 URL 并直接进入当前项目 Scope 和 M1-F02 WorkItem 视图承载区；
- 团队成员页展示 Membership 状态、加入方式、时间、版本和 Owner 标识；
- 具备 `team:members:manage` 界面权限的成员可以按 USER Principal ID 添加成员；服务端要求 `MEMBER_MANAGE`；
- 旧 M0 Control 演示页和演示 WorkItem 列表已移除，避免真实范围与演示事实并存。

## 权限边界

前端权限控制导航、路由和命令按钮，只提供及时反馈。服务端继续负责：

- Organization 与认证 Principal 约束；
- ACTIVE Team Membership；
- Team Scope `MEMBER_MANAGE` 与 WorkProject 权限；
- Team Scope `WORK_PROJECT_MANAGE`、默认 Workspace 与项目 Key 唯一性；
- 目标 USER Principal 类型、状态和 Organization Scope；
- 幂等、并发、DomainEvent、Outbox 与 Command Receipt。

Bootstrap 身份通过 `VITE_CREWSCOPE_ORGANIZATION_ID` 与 `VITE_CREWSCOPE_PRINCIPAL_ID` 对齐开发环境。OIDC Session API 后续替换身份来源，不改变页面权限名、Scope Store 和 URL 契约。

## 自动化验证

```bash
cd crewscope-web
pnpm test:coverage
pnpm build
pnpm story:build
pnpm test:e2e:update
pnpm test:e2e
```

当前结果：

- Vitest：7 个测试文件、23 个测试通过；
- Coverage：Statements 85.78%、Branches 76.11%、Functions 79.10%、Lines 86.39%，全部高于 Release Gate；
- Store：覆盖 URL 指定范围恢复、无效范围规范化、空 Team、成员添加后回读与跨 Team 异步竞态隔离；
- Component：覆盖 ScopeSwitcher Team/WorkProject 选择与 URL 更新；
- Router：覆盖 Conversation/Today Query 保留、未授权跳转与 `/control` 兼容；
- API：覆盖 M1 Team/WorkProject 路径、成员命令 Body 与 Idempotency-Key；
- Playwright：桌面 1440×960 与窄屏 390×844 共 8 个场景通过，覆盖双入口、URL 恢复、Scope 切换、成员视图和视觉基线；
- TypeScript 与 Vite 生产构建通过；
- Histoire：3 个 Story、9 个 Variant 构建通过；
- 文档链接：58 份 Markdown 全部通过；
- `git diff --check`：通过。

## 阶段边界

M1-F01 不实现 WorkItem 创建、List/Board、筛选和详情。Work 页面保留稳定的 Team/WorkProject Scope 与 URL 入口，M1-F02 在该基础上接入 M1-A04/A05 WorkItem API。
