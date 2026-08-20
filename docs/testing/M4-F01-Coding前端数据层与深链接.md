# M4-F01 Coding 前端数据层与深链接

> 状态：已完成<br>
> 日期：2026-08-20<br>
> 范围：RepositoryBinding、CodingTarget、Coding attempt、Workspace、Diff、CommandEvidence、TestEvidence 前端数据契约

## 1. 交付内容

M4-F01 建立后续 Repository 管理页与 Execution Studio 共用的数据基础：

- `domains/coding/types.ts` 定义浏览器可持有的 RepositoryBinding、BuildProfile、CodingTarget、Workspace、Sandbox 预算、Diff、Coding Result、CommandEvidence 和 TestEvidence 类型；
- `HttpCodingGateway` 接入 M4-A01、M4-A02 与 M4-A04 的真实 API，并在浏览器入口再次执行显式响应字段白名单；
- `CodingStore` 以 Organization + Team + WorkProject 分区状态，隔离 Repository、CodingTarget、Task、attempt 与 Evidence 缓存；
- `coding/route.ts` 固化 Task、attempt 与 Workspace 深链接的父子坐标和失败关闭规则；
- `CreateTaskInput` 接受可选精确 CodingTarget 选择，供 M4-F03 的 WorkItem 与 Conversation 委托表单复用；
- 应用组合根安装独立 Coding Store，M4-F02 至 M4-F07 共享同一服务端事实源。

## 2. 数据披露边界

Gateway 只保留公开 DTO 字段。以下事实停留在服务端与 Worker：

- 受管仓库与 Worktree 宿主路径；
- 容器 ID、容器名、Sandbox 镜像与物理挂载；
- typed argv、工作目录、环境变量与任意终端输入；
- Artifact 存储 URI；
- Lease、Fencing、Task Token、Claim Token、Credential 和 Agent 内部状态。

Repository 与 BuildProfile 列表、Coding attempt 的每个嵌套对象、Command/TestEvidence 和 ArtifactSummary 均使用显式字段映射。未知字段不会进入 Store。

## 3. Scope、竞态与缓存

完整 Scope Key 为：

```text
organizationId:teamId:projectId
```

Scope 切换会取消活动请求、推进请求版本并清空全部 Coding 资源。即使底层请求忽略 AbortSignal，旧响应仍需同时通过请求版本、Scope Key 和当前 Controller 三项裁决才能写入状态。

缓存失效按所有权划分：

- Repository 变更失效 Binding 集合与详情；
- CodingTarget 选择变化失效对应 WorkItem 的 BuildProfile 与 Preflight；
- Task 事件或控制命令失效当前/历史 attempt 及其 Workspace、Diff、Command/TestEvidence；
- 单 attempt 刷新只失效该 executionId 的详情和 Evidence。

CommandEvidence 与 TestEvidence 分别保存服务端 Cursor。续页原样回传 Cursor，按 Evidence ID 去除页面交界重叠。

## 4. 深链接

```text
/work?team=<teamId>&project=<projectId>&workItem=<workItemId>&task=<taskId>&attempt=<executionId>&workspace=<workspaceId>
```

恢复顺序为 Team、WorkProject、Task、attempt、Workspace。Workspace 坐标通过所选 attempt 的公开 Workspace ID 复验。重复 Query、缺失父坐标和 Workspace 归属不一致进入失败关闭。关闭 Coding 焦点保留 Task 与 Work 上下文。

## 5. 自动验证

定向验证：

```bash
pnpm --dir crewscope-web exec vitest run \
  src/domains/coding/gateway.spec.ts \
  src/domains/coding/store.spec.ts \
  src/domains/coding/route.spec.ts
```

结果为 3 个测试文件、13 项测试全部通过，覆盖：

- Repository、BuildProfile、Workspace、Sandbox、Diff、Command 和 Test DTO 白名单；
- Cursor 原样转发、分页重叠去重与强版本命令头；
- 完整 WorkProject Scope 切换和晚到请求隔离；
- Task/attempt/Workspace 深链接恢复与归属拒绝；
- Repository、CodingTarget、Task 与 attempt 缓存失效；
- 403/404 稳定错误信封与页面安全状态。

前端全量验证：

```bash
pnpm --dir crewscope-web test
pnpm --dir crewscope-web build
```

46 个 Vitest 文件、197 项测试全部通过。`vue-tsc --noEmit` 与 Vite 生产构建通过。

## 6. 下一阶段

M4-F02 使用本阶段 Repository Gateway 与 Store 在 WorkProject Settings 交付 RepositoryBinding 管理页，支持受管 Repository Key 选择、Preflight、创建、启用、停用、强版本冲突刷新和权限状态。
