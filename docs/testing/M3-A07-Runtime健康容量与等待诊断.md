# M3-A07：Runtime 健康、容量与等待诊断

> 日期：2026-08-15<br>
> 状态：已完成<br>
> 依赖：M3-I01、M3-I09

## 1. 交付结论

CrewScope 已提供 Organization Runtime Fleet 与 Team `WAITING_RUNTIME` 执行的统一观测入口。应用层在同一个 PostgreSQL 只读事务和同一个权威时刻内，组合 Runtime、Worker、能力、容量、Heartbeat 与当前 PolicySnapshot，派生 Fleet 健康和等待原因。

观测分为两层：ACTIVE TeamMember 可以读取不含基础设施身份的成员摘要；平台管理员或持有 Team 级 `TEAM_OBSERVE` 的 ACTIVE TeamMember 可以读取运维明细。Project 级授权不能提升为 Team 运维授权。

本任务复用 V10 Runtime/Worker/TaskExecution/PolicySnapshot 表和既有索引，没有新增数据库迁移。

## 2. HTTP 与配置契约

```text
GET /api/v1/organizations/{organizationId}/teams/{teamId}/runtime-health
GET /api/v1/organizations/{organizationId}/teams/{teamId}/runtime-health/operations
```

两个入口都支持可选 `environment` 查询参数，未提供时使用 `crewscope.runtime.observation.environment`；Heartbeat 新鲜度使用 `crewscope.runtime.observation.heartbeat-timeout`。环境值非法时返回 `400 invalid_request`，所有成功响应使用 `Cache-Control: no-store`。

成员摘要只返回环境、观测时间、Fleet 健康、Runtime/Worker 数量、可服务容量、失联/Drain 数量和聚合等待原因，不返回 Runtime/Worker ID、稳定 Key、实现版本、具体能力或心跳时间。

运维响应使用显式 DTO 白名单，返回 Runtime 与 Worker 注册身份、状态、版本、能力、容量、Heartbeat 和审计元数据，以及等待执行的 Task/attempt、等待时间、所需能力和诊断。Worker 健康显式区分 `RUNTIME_UNAVAILABLE`、`STALE`、`CAPACITY_EXHAUSTED` 和可领取状态，避免把禁用 Runtime 下的 Worker 误报为容量耗尽。Token、Claim/Fencing、凭证、内部配置、异常正文、AgentState 和 Reasoning 不进入响应。

## 3. 健康、容量与等待语义

Fleet 健康状态为：

- `HEALTHY`：存在 ACTIVE Runtime 和新鲜 ACTIVE Worker，且仍有容量、无失联/Drain Worker、无 `WAITING_RUNTIME`；
- `DEGRADED`：仍可服务，但容量耗尽、存在失联/Drain Worker或存在 `WAITING_RUNTIME`；
- `UNAVAILABLE`：没有 ACTIVE Runtime 或没有隶属于 ACTIVE Runtime 的新鲜 ACTIVE Worker。

容量只累计隶属于 ACTIVE Runtime 的新鲜 ACTIVE Worker。禁用或归档 Runtime 下的 Worker 即使 Heartbeat 新鲜，也不能增加可服务容量。

每个 `WAITING_RUNTIME` 执行按确定性优先级归入一个当前原因：

1. `CAPABILITY_UNAVAILABLE`：没有 ACTIVE Runtime，或没有 Worker 完整覆盖所需能力；
2. `NO_ACTIVE_WORKER`：有能力载体，但没有 ACTIVE Worker；
3. `DRAINING`：唯一可用能力载体正在 Drain；
4. `HEARTBEAT_STALE`：ACTIVE Worker 的 Heartbeat 已过期；
5. `CAPACITY_EXHAUSTED`：Runtime/Worker 可用且新鲜，但容量已满；
6. `REQUEUE_PENDING`：当前已有可领取 Worker，等待 Scheduler 下一轮重新入队处理。

## 4. 查询、Actuator 与可观测性

持久化端固定执行两条查询：第一条联接当前 Organization/environment 下的 Runtime 与 Worker；第二条联接当前 Organization/Team 的 `WAITING + RUNTIME` TaskExecution 与当前 PolicySnapshot。应用层再次闭合 Organization、Team、environment、Runtime 谱系、PolicySnapshot ID/Hash、Task、Execution 和完整 Scope。查询数量不随 Worker 或等待执行数量增长。

本地 Worker Actuator Health 同时读取执行循环和耐久 Registry：未启动或 `DRAINING` 为 `OUT_OF_SERVICE`；Heartbeat 失联、`DISABLED` 或仅 `REGISTERED` 为 `DOWN`；ACTIVE 且新鲜为 `UP`，容量已满仍保持 `UP`。Details 只包含 Claim 状态、活动/对账数量、安全失败类型、Worker 状态、Heartbeat 新鲜度、可领取状态和容量，不披露 Worker ID 或 stable key。

每次授权成功的观测读取记录 `runtime_observation_read` 结构化日志，关联 Organization、Team、调用 Principal、Correlation ID、视图和健康状态；请求 Trace 由统一 API 观测过滤器关联。`crewscope.runtime.observation.requests` 指标只使用 `view` 和 `health` 两个低基数 Tag，不使用租户、成员、Runtime 或 Worker ID。

## 5. 自动化证据

| 测试 | 覆盖 |
|---|---|
| `RuntimeObservationServiceM3A07Test` | Fleet 健康、新鲜容量、非 ACTIVE Runtime 容量排除与 Worker 标记、六类等待原因、跨 Team 持久化结果失败关闭 |
| `WorkItemAccessPolicyM3A07Test` | 平台管理员、Team 级 `TEAM_OBSERVE`、Project 级授权不能读取运维明细 |
| `RuntimeObservationControllerM3A07Test` | 两组 API、`no-store`、环境校验、成员/运维 DTO 白名单和统一 Forbidden 错误 |
| `M3TaskRuntimePersistenceIntegrationTest` | 真实 PostgreSQL 两条固定查询、PolicySnapshot 能力解析、Team 隔离和 Hibernate SQL 数量统计 |
| `TaskWorkerHealthIndicatorM3A07Test` | UP、DOWN、OUT_OF_SERVICE 与安全 Details |
| `RuntimeObservationRecorderM3A07Test` | 低基数指标与关联审计日志 |
| `TaskWorkerConfigurationM3I09Test` | Registry Coordinator 与 Worker Health 装配回归 |

验证命令：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
node scripts/check-doc-links.mjs
git diff --check
```

验证结果：Maven Reactor 7 个模块全部通过，共执行 `1043` 项测试，`0` 失败、`0` 错误、`0` 跳过；文档链接检查覆盖 `131` 个 Markdown 文件，差异格式检查通过。

## 6. 下一项

`M3-F01`：建立 Task Gateway、前端类型、Store、路由和 Scope 隔离，并接入列表、详情、attempt、事件 Cursor 与关联对象缓存。
