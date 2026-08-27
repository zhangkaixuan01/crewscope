# M6-Q03 固定负载、恢复与完整 MVP E2E

> 任务：`M6-Q03`<br>
> 状态：Fixture 轨道已完成，Canonical Nightly 与 Release Candidate 待执行<br>
> 日期：2026-08-27<br>
> 范围：固定负载、重启恢复、Worktree 回滚、备份/空目标恢复、完整 MVP E2E、真实 Lark 固定模板烟测

## 1. 交付结果

M6-Q03 把发布验收实现为三个显式轨道：

| 轨道 | 凭证 | 内容 |
|---|---|---|
| `fixture` | 不读取真实 Provider 凭证 | PostgreSQL 协议基线与生产 Queue/Activity/Inbox 固定样本负载、Redis/进程替换恢复、真实 Git Worktree 回滚、部署/加密恢复合同、前端生产构建、完整 MVP Playwright Fixture |
| `nightly` | 不读取真实 Provider 凭证 | Fixture 全部内容，加独立源环境生成新备份并在独立空目标恢复、开放流量和校验 RPO/RTO Evidence |
| `release-candidate` | 受保护环境短期凭证 | Nightly 全部内容，加显式确认的专用接收者 `release-candidate-smoke@1` 固定 Lark 模板与脱敏 Receipt Evidence |

统一入口：

```bash
./scripts/m6-q03-gate.sh fixture
./scripts/m6-q03-gate.sh nightly
./scripts/m6-q03-gate.sh release-candidate
```

## 2. 固定负载

Canonical 坐标保持为 Dataset `m6-team-beta-v1`、Seed `20260825`、Web 并发 10、Task 并发 2、Warmup 120 秒、Measurement 600 秒、三次独立重复和每项每轮至少 500 个样本。nearest-rank P95 必须严格小于 2 秒，错误率不超过 `0.1%`。

Fixture 保存两份职责不同的负载 Evidence：

- `POSTGRESQL_PROTOCOL_LOAD` 使用独立 `m6_q03.work_request` 表验证 `FOR UPDATE SKIP LOCKED`、并发坐标、nearest-rank P95、直方图和 Evidence Schema；
- `PRODUCTION_QUEUE_ACTIVITY_INBOX` 使用真实 V1–V30 Schema、生产 `JdbcTaskExecutionQueueRepositoryAdapter`、`GenerationAwareProjectionRunner`、Activity Projector、Inbox Projector、Generation Receipt 与 Checkpoint，验证生产持久化路径。

两条路径都先执行 120 个丢弃 Warmup 样本，再执行三轮各 500 个测量样本。生产路径 Fixture 与 Canonical 均使用 10 个生产者每秒各一次的固定节奏；开发机只按样本数收口，不等待 Canonical 的 120/600 秒窗口。Evidence 明确记录实际耗时、`loadLane=FIXTURE` 和 `canonicalLinuxAmd64=false`。Canonical 轨道只重复生产路径的完整时间窗口，协议基线保持快速且不冒充发布性能结论。

本次 macOS/aarch64 PostgreSQL 协议基线：

| 轮次 | READY Claim P95 | Team Projection P95 | Claim/Projection 样本 | 错误率 |
|---:|---:|---:|---:|---:|
| 1 | `400ms` | `397ms` | `500 / 500` | `0` |
| 2 | `355ms` | `354ms` | `500 / 500` | `0` |
| 3 | `594ms` | `593ms` | `500 / 500` | `0` |

本轮 Warmup 实际耗时 `345ms`，三轮 Measurement 实际耗时分别为 `728 / 702 / 880ms`。执行环境为 macOS/aarch64、8 个可用处理器、16 GiB 物理内存、约 228 GiB 磁盘、Microsoft JDK 21.0.12。三轮均满足协议 Fixture 门槛；该结果不替代 Linux amd64、8 vCPU、16 GB 云主机规格（Linux OS 报告值至少 14 GiB）、Temurin 17、120 秒 Warmup 和三轮各 600 秒 Measurement 的 Canonical 正式结果。

本次同机生产链路 Fixture：

| 轮次 | READY Repository Claim P95 | Activity Active Generation P95 | Inbox Active Generation P95 | 三项样本 | 错误率 |
|---:|---:|---:|---:|---:|---:|
| 1 | `59ms` | `113ms` | `90ms` | `500 / 500 / 500` | `0` |
| 2 | `47ms` | `95ms` | `66ms` | `500 / 500 / 500` | `0` |
| 3 | `57ms` | `115ms` | `84ms` | `500 / 500 / 500` | `0` |

生产链路 Warmup 实际耗时 `12,022ms`，三轮 Measurement 实际耗时分别为 `50,610 / 50,380 / 50,559ms`。三项指标均直接来自生产表的数据库权威时间，三轮均严格低于 2 秒；Canonical Nightly 继续在冻结的完整时间窗口重新生成同一 Schema 的 Evidence。

## 3. 重启、Worktree 与 Provider 恢复

Java Fixture 使用真实 PostgreSQL/Redis Testcontainers、真实本地 Git 和 Loopback Provider，覆盖：

- Worker 退出后的耐久启动对账；
- Redis Agent State 与 PostgreSQL/Artifact Snapshot 的进程替换恢复；
- Projection Generation/Receipt/Checkpoint 重启收敛；
- Worktree Provision/Finalize/Archive/Cleanup 普通失败回滚与冷恢复；
- GitHub Delivery 与固定模板 Lark Notification Fixture；
- Team Beta V30 Bootstrap 与部署失败关闭。

Java 专项为 `83 / 83`，零失败、零跳过。Worktree Provisioner 与 Startup Reconciler 共 `23 / 23`，所有普通失败清理或恢复到唯一受管终态，回滚/恢复成功率 `100%`。

## 4. 备份与空目标恢复

Fixture 再次通过七服务 Compose 合同和三组件加密恢复合同，覆盖 PostgreSQL Custom Dump、Artifact Hash/引用/路径安全、Redis RDB、Manifest/Envelope、V26–V30 边界、Retention 与 Runbook。M6-I10 已完成两次真实开发机空目标恢复；Q03 `nightly` 轨道不复用旧报告，必须提供两个绝对路径 Operator Environment：

```text
CREWSCOPE_M6_Q03_SOURCE_OPERATOR_ENV
CREWSCOPE_M6_Q03_TARGET_OPERATOR_ENV
```

门禁调用生产 `backup.sh` 生成新的 Release Bundle，再调用生产 `restore.sh --enable-traffic` 写入独立空目标，并校验 Schema V30、API Readiness、AgentScope Java System Info、零活动执行、Artifact 引用、RPO `<=24h` 和 RTO `<=4h`。本机本轮没有满足 Canonical 资源和双环境坐标，因此没有生成新的 Nightly Restore Evidence。

## 5. 完整 MVP E2E

Playwright 使用干净 Vite Server 与每用例独立 Route Fixture，覆盖 Conversation、TaskIntent、Native WorkItem、Responsibility、Task 委托、Coding Workspace、Diff/Evidence、Reviewer、Human Gate、GitHub Delivery，以及 Activity、Inbox、Audit、Lark/Notification、Team Observer 和 Operations。Desktop/Narrow、视觉、Axe、离线、Cursor、冲突和恢复路径共 `180 / 180` 通过，TypeScript 与 Vite 生产构建通过。

## 6. 真实 Lark 安全入口

普通 Fixture/Nightly 不读取真实 Lark 凭证。Release Candidate 只有同时满足以下条件才发送：

- `CREWSCOPE_M6_Q03_REAL_LARK_CONFIRM=send-fixed-template-to-dedicated-recipient`；
- `CREWSCOPE_M6_Q03_LARK_RECIPIENT_LABEL=dedicated-lark-test-recipient`；
- 官方 Feishu/Lark OpenAPI Origin；
- 显式短期 App 凭证、接收者身份类型/值和绝对 Evidence 路径。

脚本只发送冻结的 `release-candidate-smoke@1` 文本，不接受任意消息正文。Evidence 只保存 App、接收者、Provider Message 和 Idempotency Key 的 SHA-256，不保存原始身份、凭证、正文或 Provider Body。本轮未获得专用接收者与显式发送确认，因此没有调用真实 Lark。

## 7. Fixture 验收结果

| 门禁 | 结果 |
|---|---|
| PostgreSQL 协议负载 | 三轮各 `500 / 500`，两项 P95 均 `<2s`，错误率 `0` |
| 生产 Queue/Activity/Inbox 负载 | 三轮各 `500 / 500 / 500`，三项 P95 均 `<2s`，错误率 `0` |
| Java 生产负载/重启/恢复/Worktree/Provider | `84 / 84`，零失败、零跳过 |
| Worktree 回滚与恢复 | `23 / 23`，`100%` |
| Team Beta 部署合同 | 7 服务、不可变镜像、角色/Secret/网络边界通过 |
| 加密备份恢复合同 | 三组件、V26–V30、Hash、路径、Retention、Runbook 通过 |
| 完整 MVP Playwright | `180 / 180` |
| TypeScript / Vite | 生产构建通过 |
| Fixture Aggregate Evidence Hash | `1df1c35f978611917c9d59515b0a6f8853d452b61132c302f68ce8aef71cfff3` |

本机可完成的 Fixture 轨道已完成。M6-Q03 的关闭条件只剩 Linux amd64 Canonical Nightly 的生产 Queue/Activity/Inbox 120 秒/600 秒三轮负载、新备份空目标恢复 Evidence，以及受保护 Release Candidate 的真实 Lark 固定模板安全 Receipt。
