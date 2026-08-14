# M3-D07：Agent 运行与恢复领域模型

> 状态：COMPLETED<br>
> 日期：2026-08-14<br>
> 范围：`crewscope-domain`、`crewscope-application`、设计与 ADR

## 1. 交付结果

M3-D07 完成 Task 运行侧的 Agent 会话、运行、中断、恢复、Snapshot 和大结果引用领域契约：

- 保留 M2 Conversation `AgentRuntimeSession` 的 Personal Agent 不变量；
- 新增 `TaskAgentRuntimeSession`，支持 `TASK`、`STEP` 和 `SPECIALIST`；
- 新增 `AgentRun` 与连续编号的 `INVOKE/RESUME/RECOVERY` Segment；
- 新增一个 Run 一个 Pending `AgentInterrupt` 的 Repository 约束和幂等 Resume Receipt；
- 新增 `AgentRunContinuityGap`，保存前一 Run、最后有效 Snapshot、缺失检查点区间和原因；
- 新增 `RuntimeArtifact` 元数据，运行结果只保存 Artifact 引用；
- 新增 `AgentStateSnapshot` 元数据，闭合 Session、Run、Agent、AgentScope Key、序号、大小和 SHA-256；
- 新增五个 Application Repository Port，为 M3-D08/D09 数据库和 Adapter 实现提供稳定边界。

## 2. Session 绑定

| Purpose | 必需绑定 | Agent 类型 | AgentProfile 类型 |
|---|---|---|---|
| `TASK` | Task、TaskExecution | `TEAM_AGENT` | `TEAM` |
| `STEP` | Task、TaskExecution、StepExecution | `TEAM_AGENT` | `TEAM` |
| `SPECIALIST` | Task、TaskExecution、StepExecution | `SPECIALIST_AGENT` | `SPECIALIST` |

Session 初始化逐项校验 Organization、Team、Workspace、WorkProject、Task、TaskExecution、StepExecution、执行 Principal、Agent Principal、AgentProfile ID/Version/Status 和类型。Session ID、AgentScope Key 和 AgentState Reference 从可信事实确定性派生。相同事实重试返回相同坐标，跨执行、跨 Step、错 Agent 或错 Profile 失败关闭。

M2 Personal Session 与 M3 Task-side Session 使用不同领域形状。M3-D08 在扩展 `agent_runtime_session` 表时使用 Purpose 和可空绑定列持久化两种形状，数据库 Check Constraint 与复合外键保持形状互斥。

## 3. AgentRun 与 Segment

一个 AgentRun 表示一个逻辑运行。一个 Run 可以包含多个有限流 Segment：

```text
Run 1 / Segment 1 INVOKE
  -> Pending Interrupt
  -> Resolve
Run 1 / Segment 2 RESUME
  -> COMPLETED

无法精确续接：
Run 1 -> FAILED
Run 2 / Segment 1 RECOVERY + continuity gap
```

Run Sequence 由 Repository 在 TaskExecution 内串行分配。Segment Sequence 在 Run 内从 1 连续递增。前一个 Segment 必须终止后才能创建后一个 Segment。同一个 StepExecution 可以拥有多个 Run，历史 Run 不被覆盖。

Run 状态为 `RUNNING/INTERRUPTED/COMPLETED/FAILED/CANCELLED`。最后 Segment 状态与 Run 状态闭合。完成、失败和取消只允许从 Running 提交一次，终态不可变。终态只保存可选 `RuntimeArtifactId` 和稳定失败码，不保存结果正文。

## 4. Interrupt 与 Resume

`AgentInterrupt` 绑定 AgentRun ID 和当前 Segment Sequence。数据库对 Pending 状态建立每个 Run 的部分唯一约束。Interrupt Token 只保存 SHA-256，不保存明文。

Resume Resolution 保存：

- 非 Nil `resumeRequestId`；
- 规范回答 SHA-256；
- 处理 Principal；
- UTC 处理时间。

第一次合法 Resume 将 Pending 变为 Resolved。相同 Request ID 与相同回答 Hash 重放返回已提交对象，不受旧 Expected Version 影响。相同 Request ID 携带不同回答 Hash 失败关闭。AgentRun 对同一个已提交 Resolution 重放 Resume 时返回已提交 Run，不重复创建 Segment。

## 5. Snapshot 与 Artifact

`RuntimeArtifact` 只保存：

- ArtifactStore `ArtifactId`；
- Task、TaskExecution、可选 StepExecution、AgentRun；
- Artifact Kind、Content Type、大小和 SHA-256；
- Retention Until 和审计字段。

模型结果、Tool 结果、执行日志和 AgentState 正文不进入领域对象或 PostgreSQL 结果列。

`AgentStateSnapshot` 进一步保存 Task-side Session、AgentProfile ID/Version、Agent Principal、Agent Name、AgentScope Key、Snapshot Sequence 和 Checkpoint Sequence。Snapshot Artifact 类型固定为 `AGENT_STATE_SNAPSHOT`，Content Type 固定为 `application/vnd.crewscope.agent-state-snapshot+json`，大小范围为 1 byte 至 8 MiB。

新 Snapshot 为 `CURRENT`。发布下一 Snapshot 时，旧 Current 进入 `SUPERSEDED` 并继续作为回退候选。损坏或不可恢复的元数据进入 `INVALID`。同一 Session 的 Current 唯一性、Snapshot Sequence 和 Checkpoint Sequence 单调性由 D08 数据库约束与 D09 Repository 事务实现。

## 6. Application Port

- `TaskAgentRuntimeSessionRepository`：确定性初始化、乐观更新、Execution/Step 查询；
- `AgentRunRepository`：Run Sequence、单活动 Run、Segment 与终态更新；
- `AgentInterruptRepository`：Pending 唯一、Resume Request 唯一和终态更新；
- `RuntimeArtifactRepository`：Artifact ID 唯一与 Run 归属查询；
- `AgentStateSnapshotRepository`：Current 原子替换和按 Checkpoint 降序读取候选。

Repository 注释固定了 D08/D09 必须实现的并发与部分唯一语义。D07 不提供内存 Repository，避免用非事务实现伪造数据库并发保证。

## 7. 验证

专项命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-domain -am \
  -Dtest=TaskAgentRuntimeSessionTest,AgentRunAndInterruptTest,AgentStateSnapshotAndRuntimeArtifactTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：11 个专项测试全部通过。

覆盖场景：

1. TASK、STEP、SPECIALIST Session 与 Task/Execution/Step/AgentProfile 闭合；
2. 相同 Session 事实确定性重试；
3. 同一 Step 多个 AgentRun 和 Run Sequence；
4. Segment Sequence、Interrupt、Resolve 和 Resume；
5. Resume 精确重放与冲突重放；
6. AgentRun 唯一终态和终态后拒绝修改；
7. RuntimeArtifact 元数据引用大结果；
8. continuity gap 与直接后继恢复 Run；
9. Snapshot Session/Run/Agent/AgentScope/Artifact 身份闭合；
10. Snapshot Hash、Content Type 和 8 MiB 上限；
11. Current、Superseded 回退候选和 Invalid 状态。

Domain 回归：

```bash
./mvnw --batch-mode --no-transfer-progress -pl crewscope-domain test
```

结果：318 个 Domain 测试全部通过。

全仓回归：

```bash
./mvnw --batch-mode --no-transfer-progress test
```

结果：7 个 Maven 模块、839 个测试全部通过，0 Failure、0 Error、0 Skipped。

文档与差异检查：

```bash
git diff --check
/Users/zhangkaixuan/.nvm/versions/node/v24.13.1/bin/node scripts/check-doc-links.mjs
```

结果：差异检查通过，113 个 Markdown 文件链接检查通过。

## 8. 后续边界

- M3-D08 创建 V10 表、Session Shape Check Constraint、复合外键、序号唯一约束和 Pending/Current 部分唯一索引；
- M3-D09 实现 JPA/JDBC 映射、原子 Run Sequence、Current Snapshot 替换和恢复候选查询；
- M3-I05 扩展 Task Execution Runtime Port；
- M3-I07 将 AgentScope 事件映射为 AgentRun Segment、Interrupt、Artifact 和终态；
- M3-I08 接入 Snapshot Writer/Reader、Redis 重建和 continuity gap 编排。
