# M1-F02：WorkItem 集合前端

> 日期：2026-08-08<br>
> 状态：已完成<br>
> 工程：`crewscope-web`

## 目标

把 M1-A04 WorkItem 创建命令和 M1-A05 WorkItem 列表查询接入 Web 工作台，在当前 Team/WorkProject 范围内交付创建、筛选、Cursor 续传、List/Board 和可分享 URL 状态。M1-F02 处理集合层，详情、状态迁移和协作子资源由 M1-F03 交付。

## 交付范围

### Gateway 与 Store

- `HttpWorkItemGateway` 映射 WorkItem 列表和 Native WorkItem 创建 API；
- 列表请求支持服务端 `status`、不透明 `after` Cursor 和 `limit`；
- 创建命令生成独立 `Idempotency-Key`，请求体不携带 Actor、Owner 或客户端权限事实；
- `WorkItemStore` 区分 `idle/loading/ready/empty/error`，并独立保存续页和创建命令状态；
- 创建收到 Command Receipt 后重新读取当前集合，使用服务端事实替代本地推断；
- Cursor 续页按 WorkItem ID 去重，加载失败保留已展示集合并允许重试；
- Project、状态或路由快速变化使用请求版本隔离，旧响应不会覆盖新范围；慢创建回执不会把集合切回旧 WorkProject；
- 新查询会清理旧续页 Loading，避免跨范围异步请求留下错误状态。

### Collection 页面

- `/work` 从项目目录页升级为 WorkItem Collection；
- List 是默认扫描视图，Board 按 WorkItem Status 分列；
- 共享 `WorkItemCard` 展示 Key、标题、状态、类型、优先级、标签、描述和 Due Date；
- 状态筛选由服务端执行，类型与优先级在当前已加载集合中执行；继续加载后沿用本地筛选；
- Board 固定展示主流程状态，有取消或归档事实时增加相应列；指定状态筛选时只展示目标列；
- 工作项点击把 Key 写入 `focus`，可随当前完整 Query 带回 Conversation；
- Empty、Filtered Empty、Initial Error、Load-more Error 和 Command Error 使用独立反馈；
- 桌面与窄屏均可创建、筛选、横向浏览 Board 和继续加载。

### 创建工作项

- 表单覆盖 Key、标题、类型、优先级、Due Date、标签和描述；
- Key 根据当前 WorkProject Key 和已加载最大序号提供可编辑建议；最终唯一性由服务端项目锁和数据库约束裁决；
- Key 和标题提供即时基础校验；服务端继续校验字段长度、标签数量、Scope、权限和项目 Key；
- 创建者的初始 Owner Assignment、DomainEvent、Outbox 与 Command Receipt 由服务端原子提交；
- 创建成功关闭表单并刷新当前状态筛选，失败保留草稿和安全错误文案。

## URL 契约

```text
/work?team={teamId}&project={projectId}
      &view={list|board}
      &status={all|WorkItemStatus}
      &type={all|WorkItemType}
      &priority={all|WorkItemPriority}
      &focus={WorkItemKey?}
```

- 缺失或非法的视图与筛选值规范化为 `list/all/all/all`；
- 页面刷新、导航和 Conversation/Control 切换保留完整 Query；
- Work 参数只在 Team/WorkProject Scope 恢复完成后规范化，避免与深链接恢复竞争；
- AppShell 使用同步版本守卫，较早的 Scope 请求不能删除或覆盖较新的 URL 范围；
- Team 切换继续清除 Project 与 Focus，Work 视图和筛选偏好保留。

## 权限与阶段边界

前端 `work:read` 控制路由可见性，`work:create` 控制创建入口。服务端仍负责 ACTIVE Membership、Role Scope、完整 Organization/Team/Workspace/WorkProject Scope、Native Source、幂等和并发约束。

M1-F02 不实现：

- WorkItem 详情抽屉和详情深链接；
- 状态迁移与 ETag 冲突恢复；
- 评论和 ResourceLink；
- Owner、Executor、Gate Reviewer 展示与分配；
- 时间线和 Agent 操作入口。

这些能力分别进入 M1-F03 和 M1-F04。

## 自动化验证

```bash
cd crewscope-web
pnpm test:coverage
pnpm build
pnpm story:build
pnpm test:e2e
```

当前结果：

- Vitest：10 个测试文件、30 个测试通过；M1-F02 新增 7 个测试，覆盖 Gateway、Store、Cursor 去重、创建刷新、慢回执隔离、跨 Scope 竞态和共享卡片；
- Coverage：Statements 86.66%、Branches 74.11%、Functions 81.08%、Lines 87.63%，全部高于 Release Gate；
- Playwright：7 个场景在桌面 1440×960 与窄屏 390×844 共执行 14 次，覆盖深链接范围恢复、创建、Cursor、List/Board 切换、筛选刷新恢复和看板分组；
- TypeScript 与 Vite 生产构建通过；
- Histoire：4 个 Story、11 个 Variant 构建通过，新增 WorkItemCard List/Board 目录；
- AppShell 视觉基线继续通过；Work Collection 专用截图进入 M1-Q01 统一竞品差异检查；
- 文档链接：59 份 Markdown 全部通过；`git diff --check` 通过。

## 下一阶段

M1-F03 基于当前 Collection 和 `focus` 状态实现 WorkItem 详情模板、详情抽屉、状态迁移、评论、ResourceLink、深链接和 Conversation 占位跳转。
