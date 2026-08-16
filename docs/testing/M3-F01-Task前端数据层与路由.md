# M3-F01：Task 前端数据层与路由

## 目标

建立 M3 Task 前端的稳定事实边界，为后续 Control Mode 列表、详情、Conversation Task 卡片、控制命令和实时 Timeline 提供统一数据基础。

本阶段交付 Task 公开类型、HTTP Gateway、Store、深链接契约与 Scope 隔离，不提前实现 Task 列表和详情视觉组件。

## 公开类型与响应白名单

`crewscope-web/src/domains/task/types.ts` 只描述成员可读取的 Task 事实：

- Task 列表、详情、责任快照和 TaskExecution attempt；
- PlanVersion、StepExecution、Agent Session、AgentRun、Interrupt、Snapshot 摘要和 Lease 摘要；
- Task Event 公开信封和投影缺口；
- WorkItem、Conversation 与 Task 三向关联摘要。

Claim Token、Task Token、Token/JTI Hash、Credential、原始 AgentState、内部 Reasoning 和 Worker 长期凭证没有对应前端字段。

`HttpTaskGateway` 不直接把反序列化对象放入 Store。列表、详情、attempt、Runtime Facts、事件和关联对象均经过显式字段选择；即使服务端响应意外包含内部字段，也不会进入返回对象。

## Gateway 与 Cursor

Gateway 接入以下成员查询：

- Team 级 Task 列表与可选 WorkProject、TaskStatus 筛选；
- Task 详情与 attempt 历史；
- 单 attempt Runtime Facts；
- Task Event JSON 历史；
- WorkItem → Task、Conversation → Task、Task → WorkItem/Conversation 关联。

列表、事件和关联的 `after` 均视为不透明 Cursor，使用 `URLSearchParams` 原样编码。续页按 Task ID 或 Event ID 去重，不解析 Cursor，也不从 Cursor 推断业务身份。

## Store 与 Scope 隔离

`TaskStore` 使用两层范围键：

- Organization + Team 决定所有 Task 资源缓存的安全分区；
- WorkProject + TaskStatus + Owner Principal 决定列表查询和 Cursor 分区。

Team 切换会取消集合、详情和所有缓存请求，递增请求版本并清空 Task、Runtime、Event 与关联状态。相同 Team 内切换 WorkProject 或筛选时重新建立集合查询，旧响应不能覆盖当前集合。Task 深链接详情返回后还会比对当前 WorkProject，项目不匹配时失败关闭。

集合、详情和资源请求分别使用 AbortController 与版本裁决。浏览器 Fetch 能取消时立即停止请求；测试 Fixture 忽略取消时，版本与 Scope 比对仍会丢弃过期结果。

Task 创建和成员控制命令同样绑定发起时的 Organization + Team generation。Scope 切换会废弃未完成的命令交互状态；服务端回执到达后，每个关联、列表、详情和 Runtime 刷新阶段都重新校验 generation。旧 Scope 命令可以在服务端按幂等契约完成，不能向新 Scope 的 Store 写入状态、发起关联查询或将页面切回旧 Team。

## 路由与缓存

Task 使用服务端 A06 已固定的深链接：

```text
/work?team=<teamId>&project=<projectId>&workItem=<workItemId>&task=<taskId>
```

路由解析只接受单值 Query。Team、WorkProject 和 Task 均存在时才允许恢复 Task 选择；重复值和缺失 Scope 失败关闭。进入 Conversation/Control 双入口时继续保留共享 Query，关闭 Task 只移除 `task` 焦点。

Runtime Facts 按 `taskId + executionId` 缓存，事件按 Task 缓存，关联按 WorkItem、Conversation 或 Task 来源缓存。重复读取命中当前 Scope 缓存；续页合并保持稳定身份去重；命令、事件或页面刷新需要新事实时通过显式失效删除对应缓存。

## 验证

新增 13 个 Vitest，覆盖：

- Task DTO 白名单映射与内部字段丢弃；
- 列表、详情、attempt、Runtime Facts、事件和三向关联路由；
- 不透明 Cursor 编码与续页；
- CrewScope API 错误信封保留；
- 集合去重、缓存命中、续页合并和显式失效；
- Team/WorkProject Scope 切换；
- 忽略 AbortSignal 的慢响应版本隔离；
- 创建/控制命令等待回执和回读期间的 Scope 切换隔离；
- Task 深链接恢复、重复 Query 和 Scope 匹配。

验证结果：前端全量 `33` 个测试文件、`132` 项测试通过；`pnpm build` 的 TypeScript 检查与 Vite 生产构建通过；文档链接和差异格式检查通过。

## 后续

`M3-F02` 在当前 Gateway、Store 和 `/work?...&task=` 契约上交付 Control Mode Task 列表、状态与负责人筛选、当前 attempt、等待原因和 WorkItem“交给 Agent 处理”入口。
