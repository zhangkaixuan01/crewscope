# M3 Release Gate

> 日期：2026-08-16<br>
> 状态：已完成<br>
> 适用范围：M3 耐久 Task Runtime、Conversation/Control 双入口、Task Web、M2 回归、安全与故障恢复

## 1. 发布结论

M3 Release Gate 已通过。耐久 Task Runtime、Conversation/Control 双入口、Task Web、M2 回归、安全、故障恢复、迁移、依赖与文档门禁均满足发布条件，十四项出口条件全部关闭。

验证使用可控 AgentScope Model/Tool、真实 PostgreSQL/Redis Testcontainers、固定 Docker Sandbox、文件 ArtifactStore 与浏览器 API Contract Fixture，不依赖外部模型、GitHub、飞书或其他 Provider 网络状态。

## 2. 统一入口

```bash
./scripts/m3-release-gate.sh
```

执行顺序：

```text
Docker、Node 24 与固定 AgentScope Sandbox 镜像
  -> Markdown 链接与工作区 Whitespace
  -> Maven clean verify
  -> pnpm frozen install
  -> Vitest coverage、TypeScript 与 Vite build
  -> Histoire build
  -> Playwright Chromium、交互、恢复、视觉与 Axe
  -> pnpm 生产依赖 High/Critical Audit
```

CI 额外使用固定提交的 OSV-Scanner `v2.5.0` 递归扫描 Maven 与 pnpm 清单。Backend、Frontend、Quality、OSV 和 Web Audit 五个 Job 全部成功后，`release-gate` 才允许通过。

## 3. 十四项出口矩阵

| # | 发布条件 | 自动化证据 |
|---:|---|---|
| 1 | 两个 Worker 并发 Claim 同一 attempt 时只有一个成功 | `DurableTaskClaimSchedulerM3I02IntegrationTest` |
| 2 | Heartbeat 停止后进入 RECOVERING，并可安全 requeue | `DurableExecutionLeaseM3I03IntegrationTest`、`DurableTaskWorkerStartupReconcilerM3Q02Test` |
| 3 | Complete/Sweeper 竞争只产生一个一致终态 | `DurableExecutionLeaseM3I03IntegrationTest.tenCompleteSweeperRacesAlwaysCommitOneConsistentTerminalFact` |
| 4 | 失效 Lease、旧 Claim/Fencing Owner 不能回写 | `DurableExecutionLeaseM3I03IntegrationTest`、M3-Q01 固定攻击集 |
| 5 | Retry 创建新 attempt，历史终态和证据保持不变 | `MemberTaskCommandServiceM3A04Test`、Task Runtime 持久化集成测试 |
| 6 | Task Token 跨 Scope 攻击全部阻断 | [M3-Q01 安全硬化与固定攻击集](M3-Q01-Security-Hardening.md) |
| 7 | 成员、责任、Binding 与 Grant 撤销后权限立即收紧 | M3-Q01 当前事实复验测试 |
| 8 | CLAIMED/PREPARING/RUNNING 退出无孤立 Run/Step | `DurableTaskWorkerStartupReconcilerM3Q02Test` |
| 9 | Redis 丢失与 Snapshot 损坏可恢复或明确 continuity gap | `AgentStateSnapshotM3S03IntegrationTest` |
| 10 | Conversation/Control Mode 查看并控制同一 Task | M3-A01 至 A06 测试与 Playwright Task 场景 |
| 11 | Task Event 断线后精确续传且状态不回退 | `TaskEventControllerM3A05Test`、Task Store Vitest 与 Playwright |
| 12 | M2 Conversation、Personal Agent、TaskIntent、WorkItem 与 Provider Binding 全量回归 | `./mvnw clean verify`、Vitest 与 Playwright 全量套件 |
| 13 | M3 不创建 ExecutionWorkspace、不执行真实 Provider 写入、不披露内部 Token/State | M3-Q01/Q02、公开 DTO 与日志脱敏测试 |
| 14 | 后端、迁移、前端、故障、安全、Axe、视觉、依赖和文档门禁全部通过 | `scripts/m3-release-gate.sh` 与 GitHub Actions `release-gate` |

## 4. 迁移矩阵

最新数据库版本为 V13。全量迁移测试覆盖：

- 空库直接升级到 V13；
- V1 已有数据升级到 V13；
- V9 已有 Conversation/Personal Session 数据升级到 V13；
- V10 已有耐久 Runtime 数据升级到 V13，并回填 V11 Task Brief；
- V11 升级到 V13，补齐 V12 有界查询索引与 V13 Task Event Stream；
- V12 升级到 V13，只回填可证明属于 Task 的事件事实；
- 非默认 `search_path` 下仍只迁移 `crewscope` Schema。

证据为 `FlywayMigrationIntegrationTest` 与 `V10DurableTaskRuntimeMigrationIntegrationTest`。

## 5. 可重复纵向链路

M3 通过以下确定性链路证明主流程：

```text
WorkItem/Conversation 委托 -> Task + READY attempt
  -> Runtime 路由与原子 Claim -> PREPARING -> RUNNING
  -> Plan/Todo/Step/AgentRun/Checkpoint/Event Receipt
  -> Pause -> 同 Run Resume -> Complete
  -> Failed/Cancelled attempt -> Retry 后继 attempt
  -> Lease 过期 -> RECOVERING -> 启动对账 -> READY
```

前端从 Conversation Mode 与 Control Mode 读取同一服务端事实，不乐观伪造 Runtime 状态。浏览器测试覆盖创建、详情、Timeline、暂停、恢复、取消、重试、冲突、离线、断线续传、终态收口、键盘、窄屏、视觉和 Axe WCAG 2.2 AA。

## 6. 验收结果

2026-08-16 在本地执行统一入口，进程退出码为 `0`。结果如下：

| 门禁 | 结果 |
|---|---|
| 后端 | Maven `clean verify` 共 `1082 / 1082` 项通过，失败、错误、跳过均为 `0` |
| 模块分布 | Domain `340`、Application `242`、AgentScope `90`、Integration `1`、Infrastructure `245`、Server `164` |
| 迁移 | 空库、V1、V9、V10、V11、V12 至 V13 及非默认 `search_path` 的真实 PostgreSQL 场景通过 |
| 前端单元测试 | Vitest `180 / 180` 通过 |
| 前端覆盖率 | Statements `85.55%`、Branches `78.25%`、Functions `87.03%`、Lines `88.19%` |
| 应用与组件构建 | Vite 构建通过；Histoire `5` 个 Story、`20` 个 Variant 构建通过 |
| 浏览器验收 | Playwright Chromium `102 / 102` 通过，覆盖桌面、窄屏、交互、断线恢复、视觉基线与 Axe WCAG 2.2 AA |
| 安全 | M3-Q01 专项 `50 / 50` 通过，固定攻击样本 `25 / 25` 被阻断，固定泄漏探针命中数为 `0` |
| 故障恢复 | M3-Q02 专项 JUnit `39 / 39`、固定故障与重放样本 `56 / 56` 通过，唯一终态率与恢复率均为 `100%` |
| 副作用边界 | 旧 Owner 成功回写 `0 / 40`，孤立 RUNNING Run/Step `0`，重复控制副作用 `0 / 15`，外部 Action Dispatch `0` |
| 生产依赖 | pnpm High/Critical Audit 返回 `No known vulnerabilities found`；CI 固定 OSV-Scanner `v2.5.0` 并纳入聚合门禁 |
| 文档与格式 | Markdown 链接 `142 / 142` 通过，`git diff --check` 通过 |

## 7. 发布决定

M3 达到发布标准并完成。Task 从 WorkItem 或 Conversation 创建后，可由耐久 Worker 安全领取，通过 AgentScope Task Orchestrator 生成计划并执行受控步骤；成员可从 Conversation Mode 和 Control Mode 观察、暂停、恢复、取消与重试同一服务端事实；进程、Lease、Redis、Snapshot 和事件流故障均能恢复或形成明确的连续性缺口。

下一阶段为 M4：AgentScope 原生 Coding Agent。M4 在 M3 耐久执行内核上增加 ExecutionWorkspace、Git Worktree、Docker Sandbox、受控文件与结构化命令工具、DiffArtifact 和 TestEvidence，使 Coding Specialist 可以修改并验证真实代码仓库。
