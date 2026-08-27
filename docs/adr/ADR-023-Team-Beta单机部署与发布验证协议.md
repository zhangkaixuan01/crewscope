# ADR-023：Team Beta 单机部署与发布验证协议

> 状态：ACCEPTED<br>
> 日期：2026-08-25<br>
> 关联决策：[ADR-002](ADR-002-ExecutionWorkspace与Sandbox.md)、[ADR-003](ADR-003-ArtifactStore与Snapshot.md)、[ADR-004](ADR-004-CredentialStore与动作凭证.md)、[ADR-008](ADR-008-可观测性与日志安全协议.md)、[ADR-020](ADR-020-投影代际重建与游标协议.md)<br>
> 影响里程碑：M6

## 背景

CrewScope Team Beta 使用一套可重复部署、观测、压测、备份恢复和发布验收协议。M6-I08 已落地 OTel/Prometheus 与日志安全；M6-I09 已交付生产形态的后端/Web 镜像、七服务 Compose、外部 Secret、角色分离和一键演示 Profile，原 `docs/Dockerfile` 占位镜像已删除；M6-I10 已交付三组件加密备份、空目标恢复、版本边界与单机 Runbook。

Team Beta 面向单团队试用，采用一台专用 Linux 主机。该阶段验证完整产品闭环、数据可恢复性和运维证据，不承诺多机高可用、跨区域容灾或 Kubernetes 拓扑。

## 决策

### 单机部署拓扑

Team Beta 固定且只包含 `postgres`、`redis`、`otel-collector`、`prometheus`、`api`、`worker` 和 `web` 七个服务：

```text
Internet
  -> Web / TLS Reverse Proxy（唯一公开入口）
       -> API

API
  -> PostgreSQL
  -> Redis
  -> Artifact Volume
  -> OTel Collector

Worker
  -> PostgreSQL
  -> Redis
  -> Artifact Volume
  -> Repository / Worktree Volume
  -> Docker Socket（受信任执行边界）
  -> OTel Collector

Prometheus
  -> API / Worker 内部 Actuator
```

- Web、API 和 Worker 使用独立运行角色与独立进程；
- Web 是唯一公开端口，API、Worker、Actuator、PostgreSQL、Redis、OTel Collector 和 Prometheus 只在内部网络访问；
- API 负责 Flyway 迁移。Worker 关闭 Flyway，并在数据库迁移完成且 API Ready 后启动 Claim；
- API 与 Worker 共享同一 Redis AgentState Keyspace，执行所有权按 `server` 和 `worker` Scope 使用独立租约，每个角色仍只允许单个活动执行实例；
- Web、API 和 Worker 使用非 Root 用户、只读根文件系统、受控 `tmpfs` 和最小 Linux Capability；
- 所有应用、基础设施和 Sandbox 镜像使用不可变 SHA-256 Digest；
- Secret 通过受控 `secret-ref:` 外部文件或环境引用注入，Compose 文件不提供真实默认值、明文和可用测试凭证；
- 只有 Worker 可以访问 Docker Socket。该能力等同宿主机高权限，Team Beta 必须运行在专用主机，Worker 只接受平台校验过的 Sandbox 请求；
- PostgreSQL、Redis、Artifact、Repository、Worktree 和 Prometheus 使用分离的持久卷与最小读写权限；
- 每个服务提供健康检查，API/Worker 使用 Spring Boot Readiness Group，Prometheus 使用 `/-/ready`；启动依赖使用就绪状态，不使用聚合业务健康或固定等待时间推断进程可用性。

### Canonical Release Environment

性能与恢复发布证据固定在 Linux amd64 环境生成：

| 坐标 | 冻结值 |
|---|---|
| CPU | 8 vCPU |
| 内存 | 16 GB 云主机规格，Linux OS 报告值不得低于 14 GiB |
| 磁盘 | 至少 100 GiB，推荐 200 GiB |
| Java Runtime | Eclipse Temurin 17 |
| Maven | Wrapper 3.9.11 |
| Node.js | 24.x |
| pnpm | 11.9.0 |
| Schema | V30 |
| Dataset | `m6-team-beta-v1` |
| Seed | `20260825` |

每份证据保存 Environment Fingerprint、OS/Architecture、CPU、内存、磁盘、JDK、Maven、Node、pnpm、Docker Engine、Docker Compose、Git Revision、应用与 Sandbox Image Digest、Schema Version、Dataset Version、Seed 和测试配置。Fingerprint 使用规范字段编码后计算 SHA-256。开发机和 macOS/arm64 结果只作为诊断证据，不能替代 Canonical Release Environment 的发布证据。

### OTel、日志与 Prometheus

Trace 使用 W3C Trace Context。CrewScope 内部调用只传播以下受控 Baggage：

```text
crewscope.correlation_id
crewscope.operation
crewscope.worker_role
```

调用模型、GitHub、Lark 和其他外部 Provider 时不传播 Baggage。Span Attribute 与结构化日志允许保存经脱敏的 Correlation、业务引用和安全错误码，用于沿 Conversation、Task、AgentRun、Review、Action、Outbox、Projection、Notification 和 Provider 定位事实。

Prometheus 标签只允许以下受控枚举：

```text
outcome status type providerKey projectionName
workerRole operation errorCode streamType result
```

每项自定义指标的理论 Series 上限为 256，CrewScope 自定义指标理论总上限为 2,000。新增指标必须在注册表声明标签集合与每个枚举的最大基数，并通过预算校验。

以下字段禁止成为 Prometheus 标签：Organization、Team、Member、Conversation、WorkItem、Task、AgentRun、Action、Notification、Event、Correlation、Trace、Message 等 ID，URI、Repository、Branch、模型输入、命令、异常消息、Provider 原始错误、凭证和 Secret。受控关联 ID 可以进入 Trace 和脱敏结构化日志。

OTel Collector、Trace Backend 或 Prometheus 故障不能阻塞业务事务、Worker Claim 或外部动作完成。Exporter 使用有界队列、超时和丢弃计数；健康与告警公开聚合故障，不公开 Payload 或凭证。

### 固定负载与 P95

Team Beta 负载协议固定为：

| 参数 | 值 |
|---|---|
| Web 并发 | 10 |
| Task 并发 | 2 |
| Warmup | 120 秒 |
| Measurement | 600 秒 |
| 重复次数 | 3 |
| 每项指标每轮有效样本 | 至少 500 |
| 每轮错误率 | `<= 0.1%` |
| READY Claim P95 | `< 2 秒` |
| Team Projection P95 | `< 2 秒` |

Warmup 样本全部丢弃并单独计数。Measurement 中的失败请求保留在请求与错误率统计，不能静默剔除。每轮独立验收，三轮全部满足门槛后通过；不得用跨轮聚合掩盖单轮退化。

P95 使用 nearest-rank 算法：

```text
对 N 个 Measurement 样本升序排列
rank = ceil(0.95 * N)
P95 = sorted[rank - 1]
```

READY Claim 延迟使用数据库权威的 `READY committedAt -> successful Claim committedAt`。Team Projection 延迟使用 `DomainEvent committedAt -> 当前 Active Generation 的目标投影可见/Receipt committedAt`。采样使用同一时间源并保存原始直方图、样本数、错误数、每轮 P95 和最差 P95。

固定故障矩阵至少包含 100 个样本，自动恢复率达到 `>= 99%`，并同时满足：重复 Action Dispatch 为 0、重复 Notification Dispatch 为 0、丢失 Inbox Disposition 为 0、旧 Fencing Token 写入为 0。

### 备份与恢复

备份覆盖三个权威组件：

1. PostgreSQL 一致性 Custom Format Dump；
2. Content-addressed Artifact 存储及索引；
3. Redis Agent/Session Snapshot。

备份前进入 Maintenance Mode，停止新命令、Worker Claim 和通知调度，并等待活动 TaskExecution、Action Dispatch 与 Notification Dispatch 全部归零。Worktree、Git Mirror、AskPass 和临时 Sandbox 不进入备份；备份窗口内无活动执行保证这些可重建运行资源不承担唯一权威事实。

每个备份生成不可变 Manifest，保存 Backup ID、应用版本、Schema Version、创建时间、组件长度与 SHA-256、Manifest SHA-256、加密标记和恢复所需 Credential Key ID。Manifest 只保存 Key ID，Key Material 由独立 Secret/KMS 生命周期保管。备份包静态加密，传输使用 TLS。

Team Beta 目标为 RPO 24 小时、RTO 4 小时。恢复开始时间与 Manifest 创建时间之差必须在 0 至 24 小时内，负时间和超过 24 小时的备份均不满足发布恢复门槛。每天生成备份，保留 7 份 Daily 和 4 份 Weekly；每个 Release Candidate 前生成按需备份。M6-Q03 在空目标执行完整恢复演练：

```text
校验 Manifest 与所有组件 Hash
  -> 恢复 PostgreSQL
  -> 恢复 Artifact
  -> 恢复 Redis Snapshot；失败时从 PostgreSQL/Artifact 重建二级状态
  -> 校验数据库与 Artifact 引用
  -> 重建并校验投影
  -> Maintenance 模式启动 API/Worker/Web
  -> 执行 Smoke Test
  -> 开放流量
```

Manifest、组件 Hash、Schema 兼容性、Credential Key 可用性、目标为空或引用完整性任一失败时停止恢复。恢复过程不覆盖现有目标数据。演练保存开始时间、完成时间、实际 RPO/RTO、组件 Hash、Smoke 结果与操作者证据。

### Release Gate 分层

发布门禁分为三条 Lane：

| Lane | 触发与凭证 | 必需步骤 |
|---|---|---|
| Pull Request | 自动执行，无真实 Provider 凭证 | Backend、Frontend、质量/安全、依赖与镜像扫描 |
| Nightly | 定时或手动执行，无真实 Provider 凭证 | Compose Clean Start、故障矩阵、固定负载、备份恢复、Fixture MVP E2E |
| Release Candidate | 受保护环境人工触发，使用短期真实凭证 | 专用测试接收者 Lark Smoke、Release Manifest |

真实 Lark Smoke 只能向专用测试接收者发送固定模板。GitHub、模型和 Lark 的常规 CI 继续使用 Stub/Fixture。真实凭证只存在于受保护环境，不能进入 PR、Fork、Nightly、日志、Artifact 或 Cache。Gate 依赖只能指向当前 Lane 或更早 Lane，Pull Request、Nightly、Release Candidate 按此顺序单向推进。

每个步骤归档结构化证据。Release Manifest 聚合 Commit、Image Digest、Environment Fingerprint、Schema、Dataset、Seed、样本数、P95、错误率、故障恢复、备份 Hash、恢复 RTO、E2E、依赖扫描和真实 Provider Smoke 结果。任何必需步骤缺失、`SKIPPED`、失败或证据 Hash 不匹配时，M6-Q04 拒绝发布。

## 实现约束

1. M6-I08 实现 OTel Span、内部 Baggage 白名单、Prometheus 低基数预算、结构化日志脱敏与观测后端失效降级。
2. M6-I09 已构建 API/Worker 与 Web 多阶段镜像，实现 Role 分离、Compose 网络、持久卷、Readiness 健康检查、Digest、Config Tree Secret、角色级 Redis Ownership 和 Worker Docker Socket 高权限边界，镜像内使用固定 UID/GID、非 Root 和只读运行约束。
3. M6-I10 已实现备份、空目标恢复、Artifact URI 重定位、版本升级/回滚边界、数据校验清单和 Team Beta Runbook。
4. API 是 Flyway 单一迁移角色。Worker/Web 不竞争数据库迁移锁，也不在 API Ready 前处理任务。
5. 生产配置缺失、使用浮动镜像、公开内部端口、Root 用户、可写根文件系统或内联 Secret 时启动失败关闭。
6. 低基数预算、P95 算法、样本量、Seed、恢复顺序和 Required Gate Step 属于版本化协议；变更必须更新测试、ADR 和 Release Manifest Schema。
7. Release 证据保存受控摘要与 Hash，不保存原始凭证、模型完整响应、通知正文、命令输出或成员 PII。

## 结果

- Team Beta 获得可复现的单机部署目标和清晰的高权限边界；
- API、Worker 和 Web 可以独立扩缩、重启、诊断和恢复；
- Trace 提供跨执行链关联，Prometheus 保持可预算的低基数；
- 固定环境、数据、Seed、样本和算法使性能证据可以比较；
- PostgreSQL、Artifact 和 Redis Snapshot 形成可验证的整包备份与有序恢复；
- 自动 CI 与真实 Provider 验收按凭证风险分层；
- M6-Q04 使用完整、零跳过、可校验的证据做发布决策。

## 验证

M6-S05 test-only Harness 覆盖 6 个场景：

1. 七服务拓扑、唯一 Web 公网入口、三角色分离、非 Root、只读根文件系统、Digest 和 Docker Socket 边界；
2. Canonical Environment 的完整 Fingerprint、固定 Dataset/Seed/负载和敏感值拒绝；
3. Prometheus 标签白名单、单指标 256 Series 与总计 2,000 Series 预算；
4. nearest-rank P95、三轮独立门槛、错误率与 100 个故障样本；
5. Maintenance/Quiescence、三组件 Hash Manifest、空目标有序恢复和 RPO/RTO；
6. PR、Nightly、受保护 Release Candidate 分层，Required Step 缺失或跳过时拒绝发布。

验证证据见 [M6-S05 Team Beta 部署与发布验证记录](../spikes/M6-S05-Team-Beta部署与发布验证记录.md)、[M6-I09 生产镜像与 Team Beta 部署](../testing/M6-I09-生产镜像与Team-Beta部署.md)和 [M6-I10 Team Beta 备份恢复与 Runbook](../testing/M6-I10-Team-Beta备份恢复与Runbook.md)。M6-I09 在本机真实 Compose 中验证七服务同时 Healthy、V1→V30、空库幂等引导、API/Worker 重启恢复、只读 RootFS、UID/GID 与 Docker Socket 隔离；M6-I10 实际验证 V30→V30、V26→V30、Artifact 重定位、坏包和非空目标失败关闭，RTO 为 63/64 秒。该 macOS/arm64 记录是开发证据，不替代 Canonical Linux amd64 Release Evidence。

## 重新评估条件

- Team Beta 需要多机高可用或滚动升级；
- Worker Docker Socket 替换为远程隔离执行服务；
- ArtifactStore 从单机文件系统迁移到 S3/MinIO；
- 数据规模超过单机 PostgreSQL、Artifact Volume 或备份窗口；
- 发布目标进入 Kubernetes、跨区域或受监管生产环境；
- Prometheus 预算、负载模型或 SLO 因真实使用数据需要版本化调整。
