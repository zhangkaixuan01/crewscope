# M3-S03：AgentState 二级恢复验证记录

> 状态：VERIFIED<br>
> 日期：2026-08-13<br>
> AgentScope 基线：`v2.0.0`（`44c304ec`）<br>
> 关联决策：[ADR-003](../adr/ADR-003-ArtifactStore与Snapshot.md)、[ADR-009](../adr/ADR-009-会话执行所有权与恢复协议.md)、[ADR-011](../adr/ADR-011-AgentScopeNativeRuntime实例与恢复协议.md)

## 1. 验证目标

M3-S03 验证 PostgreSQL 检查点、Redis AgentState 和 ArtifactStore AgentStateSnapshot 的二级恢复协议，固定快照一致性点、身份闭合、完整性校验、候选选择、Redis 重建与 continuity gap 语义。

验收场景包括：

- 在 AgentScope 完整调用、Permission ASK、受控暂停和关闭安全点之后创建快照；
- Redis 清空或状态损坏后从最近有效快照重建同一 `(userId, sessionId)`；
- PostgreSQL 只向恢复器提供已提交的快照元数据候选；
- ArtifactStore 原子发布快照内容并校验大小、SHA-256、Scope、授权、TTL 和 Tombstone；
- 快照信封校验 TaskExecution、AgentRun、Agent 身份、Agent 版本、Session 和检查点序号；
- 最近候选缺失或损坏时回退到更早的完整快照，并产生 continuity gap；
- 没有有效快照、跨 Task 注入或身份不一致时失败关闭；
- 进程退出后由新的恢复器和 Redis Client 完成重建。

## 2. 分层事实

| 存储 | 保存内容 | 一致性责任 |
|---|---|---|
| PostgreSQL | AgentStateSnapshot 元数据、Artifact ID、Hash、检查点序号、Run/Task 身份、提交状态 | 选择已提交候选，裁决当前 TaskExecution、Lease、Fencing 和 AgentRun |
| Redis | AgentScope `agent_state` 热状态 | 低延迟整状态读写；可清空、可重建，不承担业务事实和恢复裁决 |
| ArtifactStore | 不可变 AgentStateSnapshot 信封 | 原子发布、大小与 Hash 校验、授权、保留、Tombstone 和内容读取 |

恢复顺序固定为 PostgreSQL 候选元数据、ArtifactStore 内容验证、AgentState 身份验证、Redis 整状态覆盖。Redis 中已有内容不能绕过当前 Lease、Fencing、PolicySnapshot 和 PostgreSQL 检查点校验。

## 3. 快照信封

M3-S03 最小信封包含：

- `schemaVersion`；
- TaskExecution ID、AgentRun ID；
- Agent `name`、`agentId` 和版本；
- AgentScope `userId`、`sessionId`；
- 单调 `checkpointSequence` 和 UTC `capturedAt`；
- AgentScope 2.0.0 `AgentState` JSON。

快照使用 `application/vnd.crewscope.agent-state-snapshot+json`，数据分类为 `RESTRICTED`，可见性为 `PRIVATE`。快照大小上限固定为 8 MiB，每次写入必须声明 TTL，Team Beta 默认 TTL 固定为 30 天；Task、审计或合规保留策略可以在正式 Writer 中提供更长 TTL。

## 4. 写入一致性点

```text
AgentScope 到达安全点并完成 AgentStateStore 保存
  -> 读取同一 Session 的完整 AgentState
  -> 校验调用身份与 AgentState userId/sessionId
  -> 生成不可变快照信封
  -> ArtifactStore 原子 put 并返回 Descriptor
  -> PostgreSQL 提交 AgentStateSnapshot 元数据和检查点引用
```

Artifact 已发布而 PostgreSQL 元数据未提交时形成不可达孤儿 Artifact，由生命周期清理处理。PostgreSQL 元数据不得先于 Artifact 发布。快照不会捕获正在执行一半的 Tool；写入中断不会产生可恢复候选。

Adapter 在生成 PostgreSQL 候选前复验 ArtifactStore `put` 返回的 Descriptor，要求 Artifact ID、Scope、Producer、Content Type、大小、SHA-256、数据分类、可见性、TTL 和活动状态与写入请求一致。错误的 ArtifactStore 实现不能产生可提交候选。

## 5. 恢复协议

```text
校验当前 TaskExecution Lease / Fencing / AgentRun
  -> PostgreSQL 按 checkpointSequence DESC 返回 COMPLETE 候选
  -> 校验每个候选的 Task/Run/Agent/Session 身份
  -> ArtifactStore 授权读取并验证 Descriptor Hash/Size
  -> Adapter 复算实际字节数和 SHA-256
  -> 解码信封并再次校验身份、序号和 AgentState
  -> 将最近有效 AgentState 覆盖写入 Redis
  -> 返回精确恢复或 continuity gap
```

最近候选缺失、损坏或信封无效时可以回退到更早的有效候选。恢复结果记录跳过的检查点序号和稳定原因码。没有有效候选时抛出安全异常，Task Orchestrator 决定创建新 AgentRun 并持久化 continuity gap。

跨 Task、Run、Agent 版本或 Session 的候选属于身份注入，立即失败关闭，不参与回退。恢复器不会信任 Artifact 内容自行声明的身份。

## 6. 验证矩阵

| 场景 | 预期证据 | 状态 |
|---|---|---|
| 最近完整快照 | 多个候选时选择最大检查点序号 | 通过 |
| Redis 清空 | 新 Redis Client 从 Snapshot 重建完整 AgentState | 通过 |
| Redis 状态损坏 | 覆盖损坏热状态并恢复可信 Snapshot | 通过 |
| 写入中断 | 未提交 Artifact/元数据不成为候选 | 通过 |
| Artifact 缺失 | 回退较早完整快照并产生 continuity gap | 通过 |
| Artifact 损坏 | 拒绝加载损坏内容；存在旧快照时回退并记录 gap | 通过 |
| 无有效快照 | 失败关闭且不写 Redis | 通过 |
| 身份注入 | 跨 Task/Run/Agent/Session 候选立即拒绝 | 通过 |
| 大小限制 | 超过 8 MiB 的快照拒绝写入或读取 | 通过 |
| Store 契约偏差 | 写入 Descriptor 不一致时拒绝候选；读取字节与 Descriptor 不一致时拒绝内容 | 通过 |
| 进程退出 | 新 Adapter、ArtifactStore、Redis Client 可恢复 | 通过 |

## 7. 实现边界

M3-S03 交付最小 Snapshot Adapter、故障注入测试和恢复协议。M3-D07 定义 AgentStateSnapshot、AgentRun 和 continuity gap 领域事实；M3-D08/D09 实现 PostgreSQL 表与 Repository；M3-I08 实现生产 Writer/Reader、安全点协调、并发写裁决和清理 Tombstone。

## 8. 验证结果

实现产物：

- `AgentStateSnapshotAdapter`：生成版本化 Snapshot 信封，通过 ArtifactStore 原子发布，验证 PostgreSQL 投影候选，并将最近可信 AgentState 覆盖写入 Redis；
- `AgentStateSnapshotRecoveryException`：为身份注入、存储不可用、无有效候选和 Redis 重建失败提供安全恢复异常；
- `AgentStateSnapshotM3S03IntegrationTest`：使用真实 Redis、文件 ArtifactStore、新进程等价组件和故障注入验证恢复协议。

身份闭合覆盖 Snapshot Identity、Artifact Scope、Artifact Producer、TaskExecution、AgentRun、Agent 名称与版本、AgentScope user/session。写入返回的 Descriptor 在生成候选前完成闭合校验；读取时 Descriptor 的 Artifact ID、Content Type、数据分类、可见性、大小和 SHA-256 均在信封解码前验证，并由 Adapter 复算实际字节数和 SHA-256。实际内容偏离 Descriptor 时归类为完整性异常。

专项验证：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  -Dtest=AgentStateSnapshotM3S03IntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：10 个 M3-S03 场景全部通过。

邻近存储回归：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  -Dtest=AgentStateSnapshotM3S03IntegrationTest,RedisAgentStateM2I05IntegrationTest,FilesystemArtifactStoreIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：28 个 Snapshot、Redis AgentState 和 ArtifactStore 测试全部通过。

模块回归：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am test
```

结果：全量 `./mvnw test` 覆盖 Domain 199、Application 178、AgentScope Adapter 75、Integration 1、Infrastructure 167、Server 100，共 720 个测试全部通过。

前端回归：

```bash
cd crewscope-web
pnpm test
pnpm build
pnpm test:e2e
```

结果：119 个 Vitest 单元测试、生产构建和 72 个 Playwright Chromium E2E 全部通过。本机与 CI 均使用由 Playwright 管理的 Chromium，避免系统 Chrome 版本漂移。
