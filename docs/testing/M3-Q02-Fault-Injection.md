# M3-Q02 耐久 Task Runtime 故障注入

> 状态：已完成<br>
> 日期：2026-08-16<br>
> 范围：TaskExecution、Lease、Worker 启动对账、AgentRun/Step、AgentStateSnapshot、Task Event 与成员控制命令

## 1. 验收目标

M3-Q02 使用确定性时钟、受控并发栅栏、真实 PostgreSQL/Redis Testcontainers、文件 ArtifactStore 和可控 SSE 服务验证 Task Runtime 的故障收敛能力。测试不依赖外部模型、GitHub、飞书或其他 Provider 网络状态。

本阶段固定以下不变量：

1. 每个 TaskExecution 最终只保留一个 Lease 释放原因和一个一致的执行状态；
2. Lease 过期或被新 Worker 接管后，旧 Owner 的 Heartbeat、状态提交和完成操作全部失败；
3. 进程退出后，RUNNING AgentRun 与 StepExecution 先失败关闭，TaskExecution 再回到 READY，重复对账不重复写入；
4. Redis 热状态丢失或损坏时，从最新完整的二级 Snapshot 恢复；最新 Snapshot 损坏时回退并显式标记 continuity gap；
5. Task Event 连接轮换或断线后从最后确认的 Cursor 续传，事件不遗漏、不重复且聚合版本不回退；
6. Pause、Resume、Cancel 的同键重放只返回原 CommandReceipt，不重复状态更新、Agent Resume、审计、Task Event 或 Outbox；
7. M3 不执行真实 Provider Action，外部 Action Dispatch 总数和重复数均为 `0`。

## 2. 固定故障矩阵

| ID | 注入点 | 固定样本 | 单样本超时 | 预期结果 | 自动化证据 |
|---|---|---:|---:|---|---|
| FI-01 | Worker 在 CLAIMED 退出 | 1 | 10 s | 注入 Lease Sweep 后的 RECOVERING 持久化状态，启动对账回到 READY，无 Run/Step 孤儿，重复对账为 0 | `DurableTaskWorkerStartupReconcilerM3Q02Test` |
| FI-02 | Worker 在 PREPARING 退出 | 1 | 10 s | 与 FI-01 相同 | `DurableTaskWorkerStartupReconcilerM3Q02Test` |
| FI-03 | Worker 在 RUNNING 退出 | 1 | 10 s | 注入 RECOVERING 及孤立 RUNNING Run/Step，各关闭一次后回到 READY，重复对账不再写入 | `DurableTaskWorkerStartupReconcilerM3Q02Test` |
| FI-04 | Complete 与 Sweeper 同时提交 | 10 | 10 s | 每轮只有 COMPLETED 或 EXPIRED 一个释放事实，执行状态与释放原因一致 | `DurableExecutionLeaseM3I03IntegrationTest` |
| FI-05 | Heartbeat 停止至 Lease 到期 | 10 | 10 s | 每轮恰好恢复一次；旧 Owner 后续 Heartbeat、Prepare/Run/Complete 成功数为 0 | `DurableExecutionLeaseM3I03IntegrationTest` |
| FI-06 | Redis 热状态全部丢失 | 5 | 10 s | 新客户端从最新 Snapshot 恢复，恢复率 100%，无 continuity gap | `AgentStateSnapshotM3S03IntegrationTest` |
| FI-07 | 最新 Snapshot Artifact 损坏 | 5 | 10 s | 全部回退到前一个完整版本，恢复率 100%，每轮均报告 continuity gap | `AgentStateSnapshotM3S03IntegrationTest` |
| FI-08 | Task Event 连接轮换/断线 | 5 次连接、10 个事件 | 10 s | Cursor 连续，10 个 Event ID 恰好交付一次，聚合版本单调 | `TaskEventControllerM3A05Test`、`crewscope-web/src/domains/task/store.spec.ts` |
| FI-09 | Pause 同键重放 | 1 次提交 + 5 次重放 | 10 s | Execution/Audit/TaskEvent/Outbox 各写一次，重放副作用为 0 | `MemberTaskCommandServiceM3A04Test` |
| FI-10 | Resume 同键重放 | 1 次提交 + 5 次重放 | 10 s | AgentRun Resume 与持久化事实各发生一次，重放副作用为 0 | `MemberTaskCommandServiceM3A04Test` |
| FI-11 | Cancel 同键重放 | 1 次提交 + 5 次重放 | 10 s | Execution、Task 与事件事实各写一次，重放副作用为 0 | `MemberTaskCommandServiceM3A04Test` |

固定矩阵共 `56` 个故障/重放样本：3 个进程退出、10 个终态竞争、10 个 Heartbeat 丢失、5 个 Redis 丢失、5 个 Snapshot 损坏、5 次事件断线，以及 Pause/Resume/Cancel 各 1 次首次提交和 5 次重放。并发结果与 SSE 批次使用 10 秒显式超时；同步样本进入 Maven Surefire 报告并记录实际耗时，任何超时或未收敛都视为失败。

## 3. 收敛判定

### 3.1 Lease 与执行终态

Complete/Sweeper 竞争允许两种合法结果：

- Complete 先提交：Lease 为 `RELEASED/COMPLETED`，TaskExecution 为 `COMPLETED`；
- Sweeper 先提交：Lease 为 `RELEASED/EXPIRED`，TaskExecution 为 `RECOVERING`。

同一轮中只允许存在一个已释放 Lease 行。Recovery DomainEvent 与 Outbox 各最多一条。数据库行锁、乐观版本和 Fencing 共同阻止失败方覆盖已提交结果。

### 3.2 旧 Owner 隔离

Heartbeat 丢失样本在 Sweeper 提交后继续使用原 Claim Token、Lease ID、Worker ID 和 Fencing Token 发起操作。所有操作必须失败，数据库中的 TaskExecution、Lease、Run 和 Step 保持 Sweeper 提交后的版本。

### 3.3 孤立执行事实

FI-01 至 FI-03 注入进程退出后已经持久化的 `RECOVERING` 状态，验证 Worker 重启时的启动对账。真实 PostgreSQL Lease 到期、`CLAIMED/PREPARING/RUNNING -> RECOVERING` 转换和旧 Owner 隔离由 FI-05 的 `DurableExecutionLeaseM3I03IntegrationTest` 证明。

启动对账只处理 PostgreSQL 中已进入 `RECOVERING` 且无 ACTIVE Lease 的 attempt。对账顺序固定为：

```text
Lease Sweep -> 锁定 RECOVERING attempt -> 关闭 RUNNING AgentRun
            -> 关闭 RUNNING StepExecution -> TaskExecution requeue
```

CLAIMED 和 PREPARING 样本没有 Run/Step；RUNNING 样本各有一个 RUNNING Run 和 Step。第二次启动对账必须返回 `0`，不得重复关闭或重复 requeue。

### 3.4 State 与事件恢复

Snapshot 恢复同时校验 Identity、Scope、Checkpoint Sequence、Artifact Hash、声明大小和信封。最新候选损坏时跳过该候选并回退，continuity gap 作为公开恢复事实保留。

Task Event 以持久化 Position 和不透明 Cursor 排序。客户端只在成功接收事件后推进 Cursor；断线后使用 `Last-Event-ID` 续传。Event ID 和 Domain Event ID 双重去重，公开投影不根据客户端时间戳重排。

## 4. 外部写操作口径

M3 的 AgentScope Task Agent 只注册 `fixture.*` 与受控计划工具，没有 ExecutionWorkspace，也没有 GitHub、飞书或其他 Provider Action Executor。因此本阶段固定记录：

```text
External Action Dispatch          0
Duplicate External Action Dispatch 0
```

M4 Coding Agent/ExecutionWorkspace 只修改本地受管 Worktree。Provider 真实写入、业务幂等键和外部回执对账从 M5 Provider Action 开始验证。M3-Q02 不使用内部数据库写入次数冒充外部 Provider 写操作。

## 5. Artifact 与证据

本地与 CI 保留以下证据：

- Maven Surefire XML：每个故障样本的通过、失败与耗时；
- Testcontainers 日志：PostgreSQL/Redis 容器启动及真实集成测试执行；
- RuntimeArtifact Fixture：Snapshot 内容只通过 ArtifactStore 读取，报告只记录 Artifact ID、Hash、大小和恢复序号；
- Playwright Report 与失败 Trace：浏览器断线、Cursor 恢复和公开 Timeline；
- 本文验收记录：固定样本量、超时、恢复率、唯一终态率与重复副作用计数。

证据中不记录 Claim Token、Task Token、原始 AgentState、内部 Reasoning 或 Provider Credential。

## 6. 验证命令

```bash
cd /Users/zhangkaixuan/codes/crewscope-java
./mvnw -pl crewscope-application,crewscope-infrastructure,crewscope-server -am test \
  -Dtest='*M3Q02*,DurableExecutionLeaseM3I03IntegrationTest,AgentStateSnapshotM3S03IntegrationTest,TaskEventControllerM3A05Test,MemberTaskCommandServiceM3A04Test' \
  -Dsurefire.failIfNoSpecifiedTests=false
./mvnw clean verify
cd crewscope-web
pnpm test:coverage
pnpm build
pnpm exec playwright test
cd ..
node scripts/check-doc-links.mjs
git diff --check
```

## 7. 验收记录

专项命令执行结果：

```text
JUnit test cases                 39 / 39 passed
Fixed fault/replay samples       56 / 56 passed
Complete/Sweeper consistency     10 / 10 (100%)
Lost Heartbeat recovery          10 / 10 (100%)
Old Owner successful mutations    0 / 40
Redis secondary recovery          5 / 5  (100%)
Corrupt Snapshot fallback         5 / 5  (100%)
Task Event exact delivery        10 / 10 event IDs
Repeated control side effects     0 / 15 replays
External Action Dispatch          0
Duplicate External Dispatch       0
```

Surefire 记录的专项测试类耗时为：成员控制 `0.967 s`、启动对账矩阵 `0.912 s`、
Lease/PostgreSQL `8.062 s`、Snapshot/Redis `1.160 s`、Task Event/SSE `2.004 s`。
所有并发 Future 与 SSE 批次均在 10 秒显式上界内完成。

首次执行多轮 Lease 样本时，测试夹具复用了 Claim Token，数据库全局唯一
`claim_token_hash` 约束正确拒绝第二个 Lease。夹具随后改为每个样本使用独立的确定性
Token；生产协议未修改，重新执行后全部通过。

M3-Q02 达到唯一终态率 `100%`、固定故障恢复率 `100%`、旧 Owner 回写成功数 `0`、
孤立 RUNNING AgentRun/Step 数 `0`、重复控制副作用数 `0`。全量后端、前端、迁移、
浏览器与文档门禁归入 M3-Q03 Release Gate。
