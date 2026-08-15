# M3-I09 JVM Worker 执行循环与启动对账

> 状态：已完成
> 日期：2026-08-15
> 范围：`crewscope-infrastructure`、`crewscope-server`

## 交付结果

M3 Task Runtime 已形成可启动、可停止、可恢复的 JVM Worker 主链：

```text
启动注册稳定 Runtime/Worker
  -> PostgreSQL 权威 Lease Sweep
  -> 锁定 RECOVERING TaskExecution
  -> 关闭孤立 AgentRun/StepExecution
  -> 重新发布 READY
  -> 有界 Claim
  -> PREPARE + Task Token + Task Session/AgentRun
  -> RUN + Lease Heartbeat
  -> AgentScope 有限事件流
  -> 原子 Event Receipt
  -> 安全点 AgentStateSnapshot
  -> Complete/Fail/Wait/Pause/Cancel + Lease Release
```

`all` 与独立 `worker` Profile 使用同一个 `TaskWorkerExecutionLoop` 和同一个耐久协议。`server` Profile 不创建 Worker、Task Agent、执行线程或 Health Indicator。

## 执行循环

`TaskWorkerExecutionLoop` 在首次 Claim 前同步运行启动对账。对账失败使 Spring 启动失败，进程不会一边领取新任务一边修复旧所有权。

Claim 批量同时受以下上限约束：

- Worker `maxConcurrentExecutions`；
- Scheduler `maximumBatchSize`；
- 当前 JVM 活动执行数。

活动数由共享 `TaskWorkerLoadTracker` 维护，并通过 Runtime Worker Heartbeat 发布。PostgreSQL 活动 Lease 继续是跨进程配额和所有权的唯一权威事实；本地计数只负责当前 JVM 容量和优雅停机。

## 单次执行协议

`DurableTaskWorkerExecutionFactory` 从 Claim Receipt 的一次性 Claim Token 构造完整 `LeaseCommandScope`，随后：

1. 提交 `CLAIMED -> PREPARING`；
2. 从当前 PolicySnapshot 签发仅含受控 Fixture Tool 的短期 Task Token；
3. 解析当前 Task、Policy、Safety、Plan、AgentProfile 和执行 Principal；
4. 幂等初始化 TaskAgentRuntimeSession；
5. 创建下一 AgentRun；
6. 提交 PREPARE-to-RUN Lease；
7. 构造闭合的 `TaskExecutionRuntimeFacts`。

`DurableTaskWorkerExecutionHandler` 订阅单一 AgentScope 有限流。新事件 Receipt 和 Snapshot 元数据都在各自事务内锁定 Lease，使用数据库权威时间验证完整 Owner/Fencing 坐标；事件 Receipt 提交成功后才允许后继事件。终态 Receipt 提交后执行 Snapshot Checkpoint，再原子提交 TaskExecution 结果和 Lease Release。Checkpoint 失败不会伪造业务完成，而是进入 `WORKER_SHUTDOWN -> RECOVERING` 回退路径。

Heartbeat 失败使当前订阅停止提交事件。旧 Worker 不能依靠内存状态继续写入；过期 Sweeper、Fencing Token 和下一次 Claim 共同完成所有权接管。

Java Flow 的 Subscription Cancel 不保证继续回调 `onComplete/onError`。Worker 在 Heartbeat 失败或停止请求时同时触发进程内终止信号，确保等待线程可以进入 `WORKER_SHUTDOWN` 恢复释放。

PREPARING 中途失败且 Factory 尚未返回完整 `TaskWorkerPreparedExecution` 时，Worker 不根据不完整内存事实伪造结果或释放。PREPARE Lease 由过期 Sweeper 转为 RECOVERING，已签发 Task Token 受 Lease 与自身 expiry 双重上界限制。

## 启动对账

启动对账复用稳态 `DurableExecutionLeaseSweeper`，因此 CLAIMED、PREPARING 和 RUNNING 进程退出都以 PostgreSQL `clock_timestamp()` 和 `expiresAt` 为准。Lease 进入 `RELEASED(EXPIRED)`、TaskExecution 进入 `RECOVERING` 后，对账器使用悲观行锁读取有界批次：

- RUNNING AgentRun 以 `WORKER_PROCESS_LOST` 安全失败关闭；
- RUNNING StepExecution 以可重试 `RECOVERY_INTERRUPTED` 关闭；
- 确认不存在活动 Lease；
- TaskExecution 使用原 attempt 和更大的后继 Fencing Token 重新进入 READY/Claim 链路。

多个进程启动时由数据库行锁、乐观锁、活动 Lease 唯一约束和 `SKIP LOCKED` Claim 共同裁决，不建立进程外协调锁。

## 优雅关闭与健康状态

关闭顺序固定为：

```text
停止 Claim -> Worker DRAINING -> 等待在途执行 -> 请求剩余执行停止 -> 关闭线程池
```

有限边界内完成的执行正常提交 Receipt、Checkpoint 和 Release。超时仍未结束的执行不伪造完成；进程退出后由 Lease 过期和下一次启动对账恢复。

Actuator `taskWorker` Health 只披露是否启动、是否接受 Claim、当前活动执行数、本次启动修复数和最近失败类型。Organization、Task、Execution、Lease、Token 和异常正文不进入 Health 明细。

## 验证

专项测试覆盖：

- 启动对账严格早于首次 Claim；
- 最大并发、批量上限、DRAINING 与优雅关闭；
- Receipt、Checkpoint、Release 和 Token 清理顺序；
- Receipt/Snapshot 提交与 Heartbeat、Release、Sweeper 通过 Lease 行锁串行化；
- Snapshot 失败不伪造完成；
- 取消后不回调的 Flow Publisher 仍可被停止请求唤醒并进入恢复释放；
- CLAIMED/PREPARING/RUNNING 退出后的孤立 Run/Step 清理；
- RECOVERING 保留活动 Lease 时失败关闭；
- `server/all/worker` Spring 装配与 Actuator Health；
- 真实 PostgreSQL 过期 Sweep、悲观启动扫描和 READY 重建。

验证命令：

```bash
/Users/zhangkaixuan/Downloads/apache-maven-3.9.6/bin/mvn clean verify
node scripts/check-doc-links.mjs
git diff --check
```
