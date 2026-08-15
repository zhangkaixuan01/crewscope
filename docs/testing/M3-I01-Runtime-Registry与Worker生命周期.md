# M3-I01 Runtime Registry 与 Worker 生命周期

## 1. 目标与边界

M3-I01 将 M3-D04 的 Runtime/Worker 领域契约连接到 M3-D09 的 PostgreSQL 持久化，交付可供后续 Claim Scheduler 使用的 JVM Worker 身份与健康事实。

本任务负责：

- 按 `Organization + RuntimeEnvironment + runtimeKey` 注册或沿用 ExecutionRuntime；
- 发布 Runtime 实现版本、能力、语言与构建系统快照；
- 按 `Runtime + stableKey` 注册或沿用 RuntimeWorker；
- 发布 Worker Profile、能力、最大并发数、当前活跃执行数和 Heartbeat；
- 提供显式 Drain 与只读心跳新鲜度判定；
- 为 `all` 与 `worker` 部署提供稳定 Worker Identity；
- 在启动时校验部署 Profile、租户、服务 Principal、能力、容量和心跳配置。

本任务不领取 TaskExecution，不创建 ExecutionLease，不运行 AgentScope Task Orchestrator，也不公开 Runtime 运维 HTTP API。上述能力分别由 M3-I02、M3-I09 和 M3-A07 交付。

## 2. 部署 Profile

| Profile | HTTP/API | JVM Worker | Registry 行为 |
|---|---:|---:|---|
| `server` | 是 | 否 | 不创建 Worker 生命周期 Bean，不注册 Runtime/Worker |
| `all` | 是 | 是 | 注册 `RuntimeProfile.ALL` Worker |
| `worker` | 无面向成员 API 职责 | 是 | 注册 `RuntimeProfile.WORKER` Worker |

Profile 只表达进程职责。`all` 与 `worker` 使用同一 Registry 协议和同一种 Worker 稳定身份，不使用进程启动时生成的随机 UUID。

## 3. 稳定身份与注册协议

Runtime Identity 由 `Organization + RuntimeEnvironment + runtimeKey` 决定。Worker Identity 由 `Runtime + stableKey` 决定。数据库行 ID 在首次注册后保持不变；相同配置重启时读取并沿用原 ID。

初始化在短事务内执行：

1. 读取 Runtime stable key；不存在时注册，存在时沿用；
2. Runtime 版本或能力快照变化时发布新快照；
3. 读取 Worker stable key；不存在时注册并激活，存在时沿用；
4. `REGISTERED` Worker 在成功 Heartbeat 时激活；
5. `ACTIVE` 与 `DRAINING` Worker发布最新能力、容量和 Heartbeat；
6. `DISABLED` Worker 和非 ACTIVE Runtime 由运维事实控制，进程启动失败，不自动覆盖；
7. 相同 stable key 的并发首次注册由数据库唯一约束裁决，失败方在新事务中重读稳定身份。

同一 Worker stable key 的 Profile 不可漂移。需要从 `all` 切换为 `worker` 时应配置新的 stable key，保留旧 Worker 的审计事实。

## 4. 能力与容量发布

Runtime 发布部署能够提供的完整能力集合，Worker 发布本实例实际提供的子集。Worker 能力、语言和构建系统必须都是 Runtime 快照的子集。

每次 Heartbeat 都发布当前容量快照：

```text
maxConcurrentExecutions  配置的并发上限
activeExecutions         进程内负载提供器报告的当前活跃执行数
```

M3-I01 的默认负载提供器返回零。M3-I09 将其替换为 JVM Worker 执行循环的权威活跃计数。非法负载不被修正或截断，注册和 Heartbeat 失败关闭。

## 5. Heartbeat、失联与 Drain

Worker 启动成功后按固定延迟周期发送 Heartbeat。每次成功 Heartbeat：

- 使用数据库中的最新 Runtime 与 Worker Version；
- 更新能力和容量快照；
- 递增 `heartbeatSequence`；
- 保留 Worker 的显式状态。

单次 Heartbeat 失败会被记录，后续周期继续重试。它不会自动创建第二个 Worker Identity。

失联状态使用下式派生：

```text
fresh = authoritativeNow - lastHeartbeatAt <= heartbeatTimeout
```

失联不会把 `ACTIVE` 改写成 `DISABLED` 或其他显式状态。M3-I02 路由同时检查 Runtime ACTIVE、Worker ACTIVE、Heartbeat fresh、能力匹配与容量可用。

Drain 是显式的 `ACTIVE -> DRAINING` 变更。DRAINING Worker 继续 Heartbeat 并允许在途负载收敛，但 `canClaim` 始终为 false。进程关闭只停止本地 Heartbeat 调度，不伪造 Drain；运维或优雅停机编排需要先显式调用 Drain。

## 6. 配置与失败关闭

`all` 与 `worker` 启动至少需要：

- 合法的 Organization UUID；
- 该 Organization 内已存在且 ACTIVE 的 Principal UUID，生产部署推荐使用 SERVICE Principal；
- 合法 Runtime environment、stable runtime key、语义化实现版本；
- 非空 Runtime/Worker 能力快照，且 Worker 能力为 Runtime 子集；
- 显式稳定 Worker key；
- `1..10000` 的最大并发数；
- `5s..10m` 的 Heartbeat timeout；
- 正数且小于 timeout 的 Heartbeat interval。

未知 Profile、缺少稳定身份、Principal 不存在或不可行动、能力越界、容量越界和心跳配置非法都在 Spring 启动阶段失败。`server` Profile 不要求 Worker 专属配置。

## 7. 验证矩阵

| 场景 | 预期 |
|---|---|
| 单实例首次启动 | 创建一个 ACTIVE Runtime 与一个 ACTIVE Worker |
| 相同 stable key 重启 | 沿用相同 Runtime ID 与 Worker ID |
| 两个不同 stable key | 同一 Runtime 下创建两个不同 Worker |
| 同 stable key 并发首次启动 | 唯一约束裁决后只保留一个 Runtime 和一个 Worker |
| 版本/能力变更 | Runtime 发布新快照，Worker Heartbeat 对账为有效子集 |
| Heartbeat | 时间、序号和容量单调更新 |
| Heartbeat 超时 | 派生为 stale，显式状态保持不变 |
| Drain | 状态为 DRAINING、继续 Heartbeat、不可 Claim |
| `server` Profile | Spring Context 中没有 RuntimeWorkerLifecycle |
| 非法 Profile/身份/能力/容量/时间 | Spring Context 启动失败 |

## 8. 自动化证据

专项测试：

```bash
./mvnw -pl crewscope-infrastructure,crewscope-server -am \
  -Dtest=RuntimeWorkerLifecycleTest,RuntimeRegistryM3I01IntegrationTest,RuntimeRegistryConfigurationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：9 个专项场景通过。真实 PostgreSQL 测试观察到并发首次注册的唯一约束竞争，冲突事务回滚后在新事务中重读并收敛为同一 Runtime ID 与 Worker ID。

全仓回归：

```bash
./mvnw clean verify
node scripts/check-doc-links.mjs
```

结果：7 个 Maven 模块、860 个测试、0 失败；116 个 Markdown 文件链接检查通过。
