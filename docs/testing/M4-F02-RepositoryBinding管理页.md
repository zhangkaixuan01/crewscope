# M4-F02：RepositoryBinding 管理页

## 1. 交付范围

M4-F02 在 WorkProject Settings 交付 RepositoryBinding 管理闭环：

- 路由 `/settings/repositories?team=<teamId>&project=<projectId>` 与 Repository 管理权限守卫；
- 管理员专用、路径无关的服务端 Repository Catalog；
- Catalog 下拉选择、默认分支、Draft Preflight 与创建；
- Existing Preflight、启用、停用、强版本和审计摘要；
- 可重试失败沿用原 Idempotency Key；
- `409/412` 丢弃陈旧命令并回读列表与详情；
- Loading、Empty、Error、Forbidden、Catalog 不可用和仓库失效状态；
- 桌面事实行与 `390×844` 窄屏顺序卡片。

## 2. Repository Catalog

公开端点：

```text
GET /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/repository-catalog
```

响应白名单：

```text
repositoryKey
availability: AVAILABLE | UNAVAILABLE
suggestedDefaultBranch: string | null
```

Worker/All Profile 枚举 Managed Root 的直接 `.git` 子目录，按 RepositoryKey 语法过滤，并复用 `ManagedRepositoryResolver` 校验目录、符号链接、Owner 和 bare repository。文件系统读取拒绝和 Pure Server 都使用稳定 `503 repository_catalog_unavailable`。Catalog 读取复用 RepositoryBinding 管理员策略，响应使用 `Cache-Control: no-store` 和稳定 Key 排序。

Canonical Path、Managed Root、文件系统用户、原始 Git 输出和基础设施错误细节不进入应用 DTO、HTTP 响应、前端 Gateway、Store 或页面。

## 3. 前端命令语义

`CodingStore` 为 Catalog、Binding 列表、Binding 详情和 Preflight 建立独立请求资源。完整 Organization + Team + WorkProject Scope 切换会取消旧请求并清空命令状态。

创建和启停命令在首次提交时生成 Idempotency Key。网络或可重试服务错误保留完整命令，用户重试沿用原 Key。`409/412` 表示 Expected Version 已过期，Store 清除原命令、失效列表与详情缓存，并从服务端加载权威版本。界面不对 RepositoryBinding 做乐观修改。

## 4. 页面与响应式

页面保持浅色、低饱和团队工作台风格。概览展示 Binding 数量和当前可绑定数量；创建候选是 AVAILABLE Catalog 与已有 Binding Key 的差集，创建成功或列表刷新后立即重新计算；Catalog 非 Ready 或刷新失败时，新建入口、Draft Preflight 和提交全部关闭。Binding 卡展示稳定 Key、默认 Branch、状态、版本、最近更新时间与安全操作人摘要。Preflight 成功只展示截断 Commit。

桌面使用三段事实行。窄屏沿 Repository Identity、操作、状态/版本/审计和 Preflight 结果顺序排列。创建入口同时位于桌面 ContextHeader 和页面目录标题区，移动端 ContextHeader 隐藏动作后仍可完成绑定。

## 5. 验证结果

- `pnpm test`：47 个测试文件、203 个测试通过；
- `pnpm build`：Vue TypeScript 检查与 Vite 生产构建通过；
- Playwright 定向场景：desktop-chromium 与 `390×844` narrow-chromium 共 2 项通过；
- `RepositoryCatalogApplicationServiceM4F02Test`：2 项通过；
- `ManagedRepositoryCatalogAdapterM4F02Test`：1 项通过，覆盖文件系统异常到安全 503 的映射与 cause 保留；
- `ManagedRepositoryConfigurationTest`：4 项通过，覆盖 Worker/All 装配和 Server Profile 关闭边界；
- Maven `verify`：7 个 Reactor 模块全部通过，`crewscope-server` 198 项测试零失败；
- DTO 白名单测试验证 Catalog、Binding、Workspace、Diff、Command 与 Test 浏览器状态不持有宿主路径及内部运行事实。

## 6. 下一项

M4-F03 在 WorkItem 委托表单和 Conversation TaskIntent 确认中接入 Repository、Ref、AllowedPaths、BuildProfile 与验收条件选择。
