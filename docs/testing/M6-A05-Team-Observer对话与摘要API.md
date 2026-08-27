# M6-A05 Team Observer 对话与摘要 API

## 1. 交付结果

M6-A05 在 M6-I07 的 `team-observer@1` AgentScope 只读运行时上增加成员授权的会话式 HTTP 边界：

- `POST /api/v1/organizations/{organizationId}/teams/{teamId}/team-observer/sessions` 创建服务端 Session；
- `POST /sessions/{sessionId}/invocations` 启动一次团队摘要并返回 SSE；
- `POST /sessions/{sessionId}/invocations/{invocationId}/resume` 重放并继续订阅同一次 Invocation；
- `POST /sessions/{sessionId}/invocations/{invocationId}/cancel` 显式取消业务执行；
- `GET /sessions/{sessionId}/invocations/{invocationId}/summary` 读取完成的五段结构化摘要；
- `GET /sessions/{sessionId}/invocations/{invocationId}/evidence/{evidenceIndex}` 在重新授权后解析本次摘要选择的内部证据路径。

该入口复用 Conversation Mode 的 Session、Invocation、SSE、Resume 和显式取消交互语义。Team Observer 使用专用 Session 聚合，不写入只允许 Personal Agent 的 `Conversation` 聚合，也不伪造 `personalAgentPrincipalId`。

## 2. 授权与模型边界

Session 绑定 Organization、Team、当前 ACTIVE TeamMember、USER Principal、确定性 Observer Profile 和服务端 UUID。创建、运行、Resume、取消、摘要读取和证据解析都重新查询当前成员；离队、停用、跨 Team、跨成员和跨 Session 请求失败关闭。

生产执行适配器只枚举 TEAM 与 ORGANIZATION `ModelConnectionOwner`，USER Connection 不进入候选集合、Model Preflight 或 Credential 打开。每次运行重新加载当前 ACTIVE Profile、精确 `team-observer@1`、当前 Configuration 和 TEAM `ResolvedAgentExecutionConfiguration`。客户端请求只接受有界 `instruction` 与每段条数，Model、Provider、Connection、Tool、Skill、Identity、Runtime State 和写命令字段全部拒绝。

## 3. 流式、恢复与取消

每次 Invocation 保留有界安全事件：

- `STARTED`；
- `SUMMARY_COMPLETED`；
- `CANCELLED`；
- `FAILED`。

SSE 断开只移除 Transport Subscriber，AgentScope 执行继续到终态。Resume 连接同一 Invocation，先重放已有事件，再等待终态，不启动第二次模型调用。每个重放或新产生的业务帧在写入下游前重新验证 ACTIVE TeamMember 及 Session/Invocation 归属；连接期间撤权会关闭披露，但不把 Transport 断开当作业务取消。取消必须调用显式 API；重复取消返回 `cancelled=false`。运行时和模型异常只投影为稳定的 `team_observer_failed`，不返回 Provider、Credential、模型响应、Tool 参数、Tool Result 或授权事实。

Team Beta 为单实例 API 部署，HTTP Session 与 Invocation 重放缓存有界保存在进程内；AgentScope State 使用 Organization、Team、Member、Observer 和 Session UUID 闭合的状态键。进程重启后的跨进程 HTTP Invocation 恢复留给后续耐久会话任务，本任务不把瞬时流缓存声明为业务历史。

## 4. 安全投影与证据

`JdbcTeamSummaryProjectionAdapter` 提供五类有界投影：

- Team Activity 只读当前活动代际的 `TEAM_MEMBERS` 事件，不读取原始 Payload、`TEAM_ADMINS` 或 `WORK_ITEM_PARTICIPANTS` 事件；
- Inbox 固定当前 Member、当前代际、OPEN 且未归档；
- WorkItem、Task 与 Artifact 固定 Organization/Team 并只输出白名单状态与摘要元数据；
- 数据库文本移除控制字符和 Unicode Format 字符、折叠空白并按领域上限截断；
- Evidence Path 只使用服务端固定内部路由模板。

摘要 DTO 不返回 Organization、TeamMember、Actor、Model Connection、Tool Payload 或原始 Evidence Path。条目只返回 `evidenceIndex`；证据 API 在当前成员复验且索引精确存在于已完成 Structured Output 后返回内部 Path。目标 API 仍执行自身授权，证据解析不替代目标资源授权。

## 5. 验证

专项测试覆盖：

- `TeamObserverInvocationServiceM6A05Test`：当前成员、Session/Principal 隔离、SSE Resume、Transport 断开、显式取消、完成摘要、离队后 Resume/证据拒绝和证据白名单；
- `JdbcTeamSummaryProjectionAdapterM6A05Test`：当前代际、`TEAM_MEMBERS` Audience、原始 Payload 缺失、批准 Section 映射和控制字符清理；
- `AgentScopeTeamObserverExecutionAdapterM6A05Test`：模型候选只查询 TEAM/ORGANIZATION Owner；
- `TeamObserverControllerM6A05Test`：公开 Session/摘要 DTO、原始 Evidence 隐藏、客户端模型/Tool/写命令字段拒绝，以及 SSE 每帧持续授权；
- M6-I07 回归：固定五只读 Tool、Structured Output、Prompt 注入、虚构 Evidence、成员撤权竞态和 AgentScope Session 隔离。

执行命令：

```bash
./mvnw -pl crewscope-server -am \
  -Dtest='*M6A05Test,*M6I07Test' \
  -Dsurefire.failIfNoSpecifiedTests=false test
node scripts/check-doc-links.mjs
git diff --check
```

结果：26 / 26 通过。M6-A05 专项 11 项，M6-I07 持续授权、只读 Tool 与 AgentScope 运行时回归 15 项。
