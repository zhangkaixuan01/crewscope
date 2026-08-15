# M3-I08 AgentStateSnapshot 生产恢复

> 状态：已完成
> 日期：2026-08-15
> 范围：`crewscope-application`、`crewscope-agentscope`、`crewscope-infrastructure`

## 交付结果

M3 Task Runtime 已形成 AgentScope 热状态、ArtifactStore 和 PostgreSQL 检查点之间的生产级恢复闭环：

```text
已提交 AgentRun Event Receipt
  -> 从 AgentStateStore 读取完整 agent_state
  -> 校验 Task/Run/Profile/Agent/userId/sessionId
  -> ArtifactStore 原子发布不可变快照
  -> 复验发布窗口
  -> 事务内锁定当前 ExecutionLease 并复验 Owner/Fencing
  -> 同一 PostgreSQL 事务登记 RuntimeArtifact
  -> 旧 CURRENT 进入 SUPERSEDED
  -> 新 AgentStateSnapshot 进入 CURRENT
```

Snapshot Writer 只接受已经提交的精确 `AgentRun + Segment Sequence + Event Sequence` Receipt。事件事务回滚、缺失或尚未提交时不会发布 Artifact，也不会创建 RuntimeArtifact 或 AgentStateSnapshot。

## AgentScope 稳定身份

AgentScope Java 2.0.0 的 `HarnessAgent.Builder#agentId` 配置复合文件系统和状态命名空间，`HarnessAgent#getAgentId()` 返回底层 `AgentBase` 的进程随机 ID。进程随机 ID 不能用于跨 Worker 恢复。

CrewScope 使用以下稳定身份同时创建 Harness 命名空间和验证 Snapshot：

```text
crewscope-task-{agentProfileId}-v{agentProfileVersion}
```

Snapshot 身份同时闭合：

- TaskExecution 和 AgentRun；
- AgentProfile ID、版本和 Agent Principal；
- 稳定 Agent 名称与 ID；
- TaskAgentRuntimeSession；
- AgentScope `userId/sessionId`。

任一坐标不一致立即失败关闭，不把跨 Task、跨 Run 或跨 Session 内容当作普通损坏候选回退。

## Writer 与并发裁决

Writer 在发布 Artifact 前读取最新 Snapshot 标记并计算下一 Snapshot Sequence 和 Checkpoint Sequence。Artifact 发布后重新加载 Task、Run、Session、Principal、Receipt 和最新 Snapshot 标记。发布窗口已经前进时拒绝当前 Writer。

发布元数据前在同一事务内锁定 ExecutionLease，使用 PostgreSQL 权威时间复验完整 Owner/Fencing 坐标。Lease 释放、过期或换 Owner 后，旧 Worker 的快照元数据提交失败，已发布 Artifact 进入 `PUBLICATION_ABORTED` Tombstone。

数据库唯一约束继续裁决真正并发的提交。只有一个 Writer 可以创建当前 Snapshot；失败 Writer 的 PostgreSQL 事务回滚。Snapshot Sequence、Checkpoint Sequence 和单 Session `CURRENT` 不会重复。

## Reader 与 Redis 重建

Reader 从 PostgreSQL 按 Checkpoint Sequence 降序加载 `CURRENT/SUPERSEDED` 候选，再逐项验证：

- Snapshot 与 RuntimeArtifact 元数据一致；
- Artifact Scope、Producer 和身份信封一致；
- Descriptor、声明大小和 SHA-256 一致；
- AgentState JSON 可解析且 `userId/sessionId` 匹配。

最近候选缺失、损坏或信封非法时，Reader 跳过该候选并回退到最近完整版本。被跳过的 Snapshot 进入 `INVALID`；非缺失的异常 Artifact 写入 `SECURITY_POLICY` Tombstone。清理使用 best-effort，清理故障不覆盖已经验证成功的恢复结果，后续生命周期 Sweep 继续对账。

回退导致 Checkpoint 区间缺失时返回 `AgentRunContinuityGap`，包含最后有效 Snapshot、缺失起止 Checkpoint、稳定原因和检测时间。M3-I09 Worker 负责把恢复证据接入启动对账和后继运行决策。

`AgentScopeTaskRuntime.recoverState` 只把 Reader 返回并再次解析、复验身份的 AgentState 覆盖到 AgentStateStore。Redis 槽位为空、旧值过期或内容失真时都以耐久快照重建。活动 Segment 运行期间禁止覆盖热状态。

## 安全点

Runtime 暴露以下检查点语义：

- `PERIODIC`；
- `CALL_COMPLETED`；
- `INTERRUPTED`；
- `PAUSED`；
- `SHUTDOWN`。

活动 Segment 尚未到达有限边界时拒绝 Checkpoint；活动 Segment 存在时拒绝 Recovery。旧五参数 Runtime 构造器保留 M3-I06 Fixture 兼容性，调用 Snapshot 操作时失败关闭。生产装配必须显式注入 `TaskAgentStateSnapshotService`。

M3-I08 提供能力和安全边界，不在 AgentScope Runtime 内提前决定事件提交顺序。M3-I09 Worker 在 AgentRun Event Receipt 提交成功后调用 `checkpointState`，确保 Snapshot 不领先于耐久事件事实。

## 验证

专项测试覆盖：

- 五类安全点读取完整 AgentState 并传递稳定身份；
- 空热状态和失真热状态从耐久 Snapshot 覆盖重建；
- 跨 Session 恢复拒绝且不污染原热状态；
- 活动 Segment 拒绝 Checkpoint 和 Recovery；
- 未配置 Snapshot Service 失败关闭；
- 未提交 Receipt 不创建 Artifact 或数据库元数据；
- 快照发布与损坏候选作废前均在事务内复验当前 Lease Owner/Fencing；
- 连续发布的 Current/Superseded 与单调序号；
- 最新 Artifact 损坏后回退旧 Snapshot、标记 INVALID、写 Tombstone 并生成 continuity gap；
- 两个并发 Writer 只提交一个 Current，失败 Artifact 写入 `PUBLICATION_ABORTED` Tombstone；
- AgentState 信封、大小、Hash、缺失对象和跨身份注入故障。

验证命令：

```bash
/Users/zhangkaixuan/Downloads/apache-maven-3.9.6/bin/mvn clean verify
node scripts/check-doc-links.mjs
git diff --check
```
