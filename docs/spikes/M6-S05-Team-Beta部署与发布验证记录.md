# M6-S05 Team Beta 部署与发布验证记录

> 任务：`M6-S05`<br>
> 日期：2026-08-25<br>
> 结论：通过<br>
> 长期决策：[ADR-023](../adr/ADR-023-Team-Beta单机部署与发布验证协议.md)（ACCEPTED）

## 1. 验证目标

M6-S05 冻结以下协议：

1. 单机 Team Beta 的 Web、API、Worker、数据与可观测拓扑；
2. OTel 字段传播、Prometheus 低基数标签和 Series 预算；
3. 固定环境、Dataset、Seed、并发、样本与 nearest-rank P95；
4. PostgreSQL、Artifact、Redis Snapshot 的一致备份和空目标恢复；
5. Pull Request、Nightly 与 Release Candidate 分层门禁；
6. 发布证据的 Environment Fingerprint、Hash 和零跳过要求。

本 Spike 使用测试文件内的协议 Harness，不创建生产镜像、部署 Compose、Collector 配置、备份脚本或 M6 Release Gate。生产实现由 M6-I08 至 I10 完成，完整发布验收由 M6-Q03/Q04 完成。

## 2. 当前基线与缺口

审查时的仓库基线为：

- `compose.yaml` 提供 PostgreSQL 17 与 Redis 7.4 开发依赖；
- `docs/Dockerfile` 是 Ubuntu 与 `top` 的文档占位镜像；
- API、Worker 和 Web 尚未形成生产镜像与独立运行角色；
- Spring Boot 已包含 Actuator、Micrometer Tracing、OTel Bridge 与 Prometheus Registry；
- Worker 与 Scheduler 当前随 API 进程装配；
- ArtifactStore 当前使用本机 Filesystem Adapter；
- `.github/workflows/ci.yml` 已覆盖 Backend、Frontend、Quality、Dependency Scan 与既有 Release Gate；
- `scripts/m5-release-gate.sh` 覆盖 M5，全新的 Compose Clean Start、固定负载、备份恢复和受保护真实 Lark Gate 尚未实现。

这些缺口对应 M6-I08 至 I10。本 Spike 把目标结构和验收算法固化为可执行协议。

## 3. 作者环境与 Canonical 环境

本次作者环境：

```text
Host OS/Arch: macOS 26.3.1 arm64
CPU: 8
Memory: 16 GiB
Java: Microsoft OpenJDK 21.0.12（Maven release=17）
Maven Wrapper: 3.9.11
Node.js: v24.13.1
Local pnpm: 10.28.2
Project/CI pnpm: 11.9.0
Docker Engine: 29.6.2
Docker API: 1.55
Docker Compose: 5.3.1
Docker OS/Arch: linux/arm64
```

Canonical Release Environment 固定为 Linux amd64、8 vCPU、16 GiB、至少 100 GiB 磁盘，推荐 200 GiB；Java 使用 Temurin 17，Node 使用 24.x，pnpm 使用 11.9.0。作者环境只证明协议 Harness 可运行，性能与恢复 Release Evidence 必须在 Canonical 环境生成。

每次发布计算包含工具链、硬件、Git Revision、Image Digest、Schema、Dataset、Seed 和负载参数的 SHA-256 Environment Fingerprint。任何坐标变化都会形成不同 Fingerprint。

## 4. 单机 Team Beta 拓扑

Harness 固定且只接受七个服务：

```text
web -> api -> postgres / redis / artifact-data
              |
              +-> otel-collector

worker -> postgres / redis / artifact-data
       -> repository-data / worktree-data
       -> Docker Socket
       -> otel-collector

prometheus -> API / Worker internal actuator
```

验证结果：

- Web 是唯一公开服务；
- Web、API、Worker 分离为三个运行角色；
- 三个应用角色使用非 Root 用户与只读根文件系统；
- 所有镜像使用 `@sha256:<64 hex>`；
- 所有服务具有 Healthcheck；
- Secret 只接受受控 `secret-ref:` 外部引用；
- Docker Socket 只授予受信任 Worker；
- Root 用户、Worker 公开端口和浮动 `latest` 镜像均被协议拒绝。

Worker Docker Socket 具备宿主机高权限。Team Beta 使用专用主机承载该执行边界，生产加固阶段可以将 Sandbox 执行迁移到独立 Worker Host 或远程执行服务。

## 5. OTel 与 Prometheus 预算

Trace 采用 W3C Trace Context，并沿 Conversation、Task、AgentRun、Review、Action、Outbox、Projection、Notification 和 Provider 保存受控关联信息。内部 Baggage 白名单为 `crewscope.correlation_id`、`crewscope.operation` 和 `crewscope.worker_role`；外部 Provider 请求不传播 Baggage。

Prometheus 标签注册表与最大基数：

| 标签 | 最大基数 |
|---|---:|
| `outcome` | 6 |
| `status` | 8 |
| `type` | 12 |
| `providerKey` | 4 |
| `projectionName` | 12 |
| `workerRole` | 4 |
| `operation` | 8 |
| `errorCode` | 24 |
| `streamType` | 3 |
| `result` | 4 |

一项指标的标签基数乘积上限为 256，CrewScope 自定义指标理论总 Series 上限为 2,000。Organization ID、Exception Message 和未注册标签在 Harness 中失败关闭；`errorCode * operation * status = 1,536` 的单指标组合同样失败关闭。

OTel/Prometheus 故障使用有界缓冲与安全降级，不进入业务事务成功条件。Exporter 丢弃和失败通过聚合指标与健康状态暴露。

## 6. 固定负载与故障协议

负载坐标固定为：

```text
Dataset: m6-team-beta-v1
Seed: 20260825
Web concurrency: 10
Task concurrency: 2
Warmup: 120s
Measurement: 600s
Repetitions: 3
Minimum samples per metric per run: 500
```

P95 使用 nearest-rank：`ceil(0.95 * N)`。500 个样本的 P95 取排序后的第 475 个样本。Harness 验证 1,900 ms 通过，2,000 ms 由于门槛为严格 `< 2s` 而失败。

每轮独立满足 Claim P95 `< 2s`、Projection P95 `< 2s` 和错误率 `<= 0.1%`。Warmup 不进入 Measurement；Measurement 中失败请求保留在错误率分母和计数中。

固定故障证据至少 100 个样本，自动恢复率 `>= 99%`。重复 Action、重复 Notification、丢失 Inbox Disposition 和旧 Fencing Token 写入都必须为 0。Harness 验证 99/100 通过、98/100 失败，任一重复外部动作同样失败。

## 7. 备份与空目标恢复

备份开始前要求：

```text
Maintenance Mode = true
Active TaskExecution = 0
Active Action Dispatch = 0
Active Notification Dispatch = 0
```

备份包包含 PostgreSQL、Content-addressed Artifact 和 Redis Snapshot。不可变 Manifest 保存组件长度、SHA-256、Manifest Digest、应用版本、Schema V28、加密标记和 Credential Key ID。Manifest 与证据均不保存 Key Material。

目标固定为 RPO 24 小时、RTO 4 小时。恢复时刻必须位于 Manifest 创建后的 24 小时内，未来时间戳和过期备份失败关闭。恢复只能写入空目标，顺序为：

```text
VERIFY_MANIFEST
RESTORE_POSTGRES
RESTORE_ARTIFACTS
RESTORE_REDIS_OR_REBUILD
VERIFY_REFERENCES
REBUILD_PROJECTIONS
START_MAINTENANCE
SMOKE_TEST
ENABLE_TRAFFIC
```

Harness 验证 3 小时 RTO 通过，并验证 Artifact 篡改、非空目标与活动执行全部失败关闭。M6-I10 需要把 Manifest、组件 Hash、加密、Retention 和恢复顺序实现为脚本；M6-Q03 在干净环境执行真实恢复演练。

## 8. Release Gate 分层

协议图包含 11 个 Required Step：

```text
PULL_REQUEST
  backend
  frontend
  quality-security
  dependency-image-scan

NIGHTLY
  compose-clean-start
  fault-matrix
  load-profile
  backup-restore
  mvp-e2e-fixture

RELEASE_CANDIDATE
  real-lark-smoke
  release-manifest
```

Pull Request 与 Nightly 不使用真实凭证。Release Candidate 由受保护环境人工触发，真实 Lark 只发送至 `dedicated-lark-test-recipient`。Step 依赖只能从 Pull Request 单向流向 Nightly，再流向 Release Candidate；自动 Lane 不能声明凭证能力。每个 Step 都归档证据；任一 Required Step 缺失、跳过或失败时拒绝发布。

Release Manifest 汇总所有上游步骤，并保存 Commit、Image Digest、Environment Fingerprint、Seed、样本数、P95、故障恢复、备份 Hash、RTO 与 Evidence Hash。真实凭证、消息正文和 Provider 原始 Body 不进入 Artifact。

## 9. 自动化验证

测试文件：

```text
crewscope-infrastructure/src/test/java/io/crewscope/infrastructure/release/
  TeamBetaReleaseProtocolM6S05Test.java
```

专项命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  -Dtest=TeamBetaReleaseProtocolM6S05Test \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：

```text
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 10. 后续实现边界

- M6-I08：构建非 Root、只读运行、固定 Digest 的 API/Worker 与 Web 镜像；
- M6-I09：实现单机 Compose、角色分离、网络、卷、Healthcheck、Secret 和启动顺序；
- M6-I10：实现 OTel Collector、Prometheus、备份恢复、Environment Fingerprint、负载与 Release Gate 脚本；
- M6-A06/A07：交付运行健康、诊断、配置与运维管理 API；
- M6-F07：交付运维与部署管理界面；
- M6-Q03：执行完整 MVP、负载、故障和恢复验收；
- M6-Q04：聚合零跳过的 Release Evidence 并做 Team Beta 发布决策。

## 11. 结论

M6-S05 验证通过。单机 Team Beta 的安全拓扑、环境指纹、低基数指标、固定负载、nearest-rank P95、三组件备份恢复和三层 Release Gate 已形成可执行协议。ADR-023 已接受，M6-I08 至 I10 和 M6-Q03/Q04 可以依据同一套冻结参数实施与验收。
