# M4-F03：CodingTarget 委托表单

## 1. 交付范围

M4-F03 在成员委托入口交付 Coding Task 创建闭环：

- WorkItem 详情中的统一 Agent 委托表单；
- TaskIntent 确认结果卡中的“配置 Coding Task”入口；
- ACTIVE RepositoryBinding、Baseline Ref、AllowedPaths 与精确 BuildProfile 选择；
- 显式 Ref Preflight 与完整 Commit 固化提示；
- Coding/通用 Agent Task 切换；
- Scope 化草稿恢复、可重试命令锁定与原 Idempotency Key 重试；
- Loading、Error、无仓库、无 BuildProfile、失效 Ref 与字段错误状态；
- 桌面与 `390×844` 窄屏顺序交互。

## 2. 双入口契约

TaskIntent 确认保持空请求体，继续原子创建 WorkItem、责任链和 ConversationWorkItemLink。确认结果卡只向具备当前交互资格的成员展示 Coding 委托入口。入口携带 Conversation、WorkItem 与最新持久 USER Message 坐标进入 WorkItem 委托表单。

Control Mode 与 Conversation Mode 最终调用同一个：

```text
POST /api/v1/organizations/{organizationId}/teams/{teamId}
     /work-projects/{projectId}/work-items/{workItemId}/tasks
```

`conversationSource` 只记录来源关系。两种入口提交相同 `codingTarget`，服务端执行相同责任、Scope、版本、Binding、Profile 与 Ref 校验。

## 3. 表单事实与恢复

表单默认使用第一个 ACTIVE RepositoryBinding、其默认分支、`.` AllowedPaths 与第一个服务端 BuildProfile。Repository、Ref、AllowedPaths 或 BuildProfile 变化立即使旧 Preflight 失效。创建 Coding Task 必须取得与当前 Binding、Repository Key 和 Ref 完全匹配的 Preflight 结果。

草稿键闭合：

```text
Organization + Team + WorkProject + WorkItem
```

草稿只保存 Coding 开关、RepositoryBinding ID、短 Ref、仓库相对 AllowedPaths 和公开 BuildProfile 坐标。宿主路径、Managed Root、Sandbox 镜像、命令参数、环境变量、Token 与内部运行状态不进入 SessionStorage。失效 Binding 或 Profile 按当前服务端选项恢复默认值；成功创建后删除草稿。

Task Store 在首次创建时把表单选择转换为纯 DTO 并形成可克隆的完整命令快照。网络或可重试服务错误锁定表单，重试沿用该命令与原 Idempotency Key。浏览器不根据 CommandReceipt 构造 Task ID，继续从受权关联查询恢复服务端 Task 身份。

## 4. 输入边界

- AllowedPaths 接受 1–200 个仓库相对 canonical 路径，`.` 表示整个仓库；
- 客户端拒绝绝对路径、Windows Drive、反斜杠、空路径、`.`/`..` 路径组件和控制字符；
- BuildProfile 的 Key、Version 与 Hash 全部来自服务端 Options；
- 客户端 Preflight 只提供交互反馈，服务端创建事务再次执行权威 Preflight；
- 关闭 Coding 开关提交 `codingTarget: null`，保留既有非 Coding Task 行为。

## 5. 验证结果

- `pnpm test`：48 个测试文件、207 项测试通过；
- `pnpm build`：Vue TypeScript 检查与 Vite 生产构建通过；
- Playwright 完整回归：desktop-chromium 与 `390×844` narrow-chromium 共 106 项通过；
- `mvn verify`：7 个 Reactor 模块全部通过，其中 Infrastructure 416 项、Server 198 项测试通过；
- 单元测试覆盖服务端默认值、精确 Profile DTO、Preflight、失效 Ref、无 ACTIVE Binding、Scope 化草稿恢复和纯 DTO 提交；
- E2E 验证 WorkItem 与 TaskIntent 确认结果两个入口提交相同 CodingTarget，临时失败重试沿用原 Idempotency Key。

## 6. 下一项

M4-F04 在 Task 详情交付 Execution Studio，展示基线、Workspace、Sandbox、Coding Agent、计划、当前命令、资源预算和恢复代次。
