# M3-D04 Runtime 与 Worker 领域模型

## 目标

M3-D04 建立可持久的 Execution Runtime Registry 与 Runtime Worker 领域契约，为后续 Claim、Lease、Task Token 和 JVM Worker 注册提供稳定身份、路由能力、容量和存活事实。

## 实现结果

### Runtime Registry

- `ExecutionRuntimeId` 是 Runtime 注册记录的强类型身份。
- `ExecutionRuntime` 使用 `OrganizationId + RuntimeEnvironment + key` 形成隔离坐标。
- Runtime 保存显示名、实现版本、能力快照、`ACTIVE/DISABLED/ARCHIVED` 状态、乐观锁版本和审计 Principal。
- Runtime key 使用稳定小写 kebab-case，实现版本使用数字语义版本。
- `ARCHIVED` 是终态，不能恢复或继续发布能力。

### Runtime Worker

- `RuntimeWorkerId` 是数据库身份，`stableKey` 是同一 Runtime 内重启可复用的部署身份。
- `RuntimeProfile` 支持 `ALL` 和 `WORKER`；`server` 进程不注册 Worker。
- Worker 显式状态为 `REGISTERED/ACTIVE/DRAINING/DISABLED`。
- 注册后的 Worker 先处于 `REGISTERED`，成功激活后记录首次心跳并进入 `ACTIVE`。
- `DRAINING` 停止新 Claim 且保留当前负载，便于在途 TaskExecution 收敛。
- Drain 和 Disable 是控制事实，不伪造心跳时间或心跳序号。
- Heartbeat 上报 Worker 当前能力、最大并发数、活跃执行数和单调序号。
- 心跳失联是 `lastHeartbeatAt + timeout` 的派生事实，不改写 Worker 显式状态。

### 能力与路由

- `RuntimeCapabilities` 从 Application 下沉到 Domain，M2 调用型 `ExecutionRuntime` Port、M3 Registry/Worker、Scheduler 和 Policy 共用同一能力词汇。
- 能力快照包含平台特性、编程语言和构建系统，采用完整包含关系进行匹配。
- Worker 能力必须是所属 Runtime 能力的子集。
- Runtime 能力收缩后，旧 Worker 停止参与路由；Worker 可以通过下一次心跳上报收缩后的子集完成对账。
- Worker 只在 Runtime 和 Worker 都为 ACTIVE、心跳未过期、容量可用、能力匹配且 Organization/环境/Runtime 谱系闭合时可以 Claim。

### Persistence Port

- `ExecutionRuntimeRepository` 提供按 Organization、Environment、ID 和 runtime key 查询的契约。
- `RuntimeWorkerRepository` 提供按 Organization、Environment、Runtime 和 stable worker key 查询的契约。
- Domain 负责 stable key 格式与边界闭合，M3-D08 在 PostgreSQL 中落实 `Organization + Environment + runtimeKey` 和 `Organization + Environment + runtimeId + workerStableKey` 唯一约束。

## 验证

M3-D04 新增 17 个领域专项测试：

- `ExecutionRuntimeTest` 6 个；
- `RuntimeWorkerTest` 11 个。

专项 Reactor 验证：

```text
crewscope-domain       275 tests passed
crewscope-application  178 tests passed
crewscope-agentscope    75 tests passed
```

覆盖 Runtime/Worker 注册、稳定 Key 格式、能力/语言/构建系统匹配、能力收缩对账、容量边界、启停、Drain、心跳边界与过期、乐观锁、审计 Principal 和跨 Organization/环境/Runtime 隔离。

最终回归结果：

```text
7 Maven modules successful
796 tests passed, 0 failures, 0 errors, 0 skipped
110 Markdown files passed link validation
git diff --check passed
```
