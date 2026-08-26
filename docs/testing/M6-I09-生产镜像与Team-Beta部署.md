# M6-I09 生产镜像与 Team Beta 部署

> 日期：2026-08-26
> 对应任务：`M6-I09`
> 对应决策：[ADR-023 Team Beta 单机部署与发布验证协议](../adr/ADR-023-Team-Beta单机部署与发布验证协议.md)

## 1. 交付结果

M6-I09 交付可在一台专用主机运行的 Team Beta 部署形态：

```text
Web（唯一宿主端口）
  -> API（HTTP、AG-UI、SSE、Flyway）
       -> PostgreSQL / Redis / OTel Collector

Worker（Outbox、Action、Notification、Projection、Task）
  -> PostgreSQL / Redis / OTel Collector
  -> Artifact / Repository / Worktree / Runtime
  -> Docker Socket（受信任高权限边界）

Prometheus
  -> API / Worker 内部 Actuator
```

长期运行服务固定为 `postgres`、`redis`、`otel-collector`、`prometheus`、`api`、`worker` 和 `web` 七个。演示脚本使用一个结束后立即删除的固定 Alpine Helper 设置数据目录权限，它不是第八个运行服务。

## 2. 镜像与容器边界

- `backend.Dockerfile` 使用 Maven 3.9.11 / Temurin 17 多阶段构建，运行层只保留 Temurin 17 JRE、Tini、Git、Docker CLI 和应用 Jar；
- `web.Dockerfile` 使用 Node 24 / pnpm 11.9.0 构建，运行层使用 `nginx-unprivileged`；
- 生产 Compose 要求应用镜像和基础设施镜像均使用 `@sha256:<digest>`，未配置应用 Digest 时 `docker compose config` 直接失败；
- API/Worker 固定 `10001:10001`，Web 固定 `101:101`，三者均使用只读根文件系统、`cap_drop: ALL`、`no-new-privileges`与受控 `tmpfs`；
- Docker Socket 只挂载到 Worker。API 只挂载 Artifact，Web 不挂载业务数据；
- Web 只代理 `/api/`，`/actuator` 不进入公开反向代理路由。

## 3. 角色、状态与启动顺序

API 使用 `server` 执行 Profile，独占 Flyway、HTTP/Realtime 入口，关闭 Outbox、Action、Notification 和 Projection 后台调度。Worker 使用 `worker` Profile，关闭 Flyway 与 Team Activity Realtime，开启后台 Claim、对账和投影管理。

API 和 Worker 共享同一 CrewScope 环境/Schema Redis Keyspace，AgentState、Pending Tool 和 Session 检查点可共享恢复。执行所有权按 `server` 和 `worker` Scope 拆分独立租约；每个角色内仍实施单活动实例。`team-beta` 启动 Guard 强制 Ownership Scope 与执行 Profile 相同。

启动顺序使用 Compose 健康依赖：

```text
PostgreSQL + Redis
  -> API Flyway V1→V30 + 空库引导 + Readiness
       -> Worker Readiness
       -> Web /healthz

OTel Collector + Prometheus 独立启动，观测后端故障不改变业务结果。
```

API/Worker 容器健康检查使用 `/actuator/health/readiness`。聚合 `/actuator/health` 保留 Projection 和队列积压等业务运行信号，可进入 Prometheus 与运维判断，不用于容器存活判定。

## 4. 外部配置和空库引导

Secret 文件位于 Compose 外部目录，通过 Spring `configtree:/run/secrets/` 注入：

- 数据库密码和带 ACL 的 Redis URL；
- Bootstrap 密码；
- Credential Encryption Key；
- Activity Cursor、Task Token 和 Diff Cursor 密钥。

Compose 中不提供可用默认值。`TeamBetaDeploymentGuard` 在应用就绪前复验外部配置来源、Secret 强度、认证 Redis URL、角色开关和绝对数据路径。开发密钥、未认证 Redis、角色混合或缺失配置会失败关闭。

API 的 Flyway Strategy 在迁移完成后幂等创建一个 Team Beta Organization 和一个 Runtime Service Principal。既有坐标的不可变事实不一致时拒绝启动，不覆盖数据。

## 5. 静态与自动化契约

`scripts/check-team-beta-deployment.mjs` 在 CI Quality Job 中验证：

- 生产 Compose 精确包含七个服务，非 Web 服务不公开宿主端口；
- 镜像 Digest、非 Root、只读 RootFS、Capability 和 Privilege Escalation 约束；
- API/Worker 的 Flyway、Scheduler、Readiness 和 Redis Ownership Scope 精确分离；
- Docker Socket 只属于 Worker；
- Secret 只通过 Config Tree 注入；
- Demo Profile 仍保持相同七服务和真实多阶段构建；
- 丢失生产必需参数时 Compose 解析失败。

Spring 专项测试覆盖 API/Worker 角色、空库幂等引导、冲突事实失败关闭、多构造器生产选择与角色级 Redis Keyspace。

Trivy 0.74.0 首次扫描发现 Spring Boot 4.0.4 包含已有修复的 Critical `CVE-2026-40976`，M6-I09 因此将补丁基线升级到 Spring Boot 4.0.6，AgentScope Java 继续固定 2.0.0。重建后后端和 Web 镜像的已修复 Critical 均为 0；后端仍报告 39 个 High，Web 仍报告 12 个 High。CI 会构建两个生产 Dockerfile 并对已有修复的 Critical 漏洞失败关闭；High 漏洞保留真实报告并在 M6-Q04 发布候选基线中统一收口。

## 6. 真实 Compose 验证

开发机环境为 macOS/arm64 + Docker Desktop，Demo 使用 `linux/amd64` 后端容器。该结果用于确认可运行性，不替代 ADR-023 要求的 Linux amd64 Canonical Release Evidence。

真实启动达到：

```text
api              healthy
worker           healthy
web              healthy
postgres         healthy
redis            healthy
otel-collector   healthy
prometheus       healthy
```

入口和数据库验证：

```text
GET /healthz                         -> healthy
GET /api/platform                    -> CrewScope / AgentScope Java 2.0.0
GET /actuator/health through Web     -> Web SPA HTML，未代理 Actuator
Flyway Schema Version                -> 30
Bootstrap Organization count         -> 1
Runtime Service Principal count      -> 1
```

容器安全验证：

```text
api user=10001:10001       readonly=true  privileged=false  cap_drop=ALL
worker user=10001:10001    readonly=true  privileged=false  cap_drop=ALL
web user=101:101           readonly=true  privileged=false  cap_drop=ALL

api rootfs write           -> blocked
worker rootfs write        -> blocked
web rootfs write           -> blocked
api docker socket          -> absent
worker docker socket       -> present
```

API 和 Worker 分别重启后重新达到 Readiness `UP` 与容器 `healthy`，Schema 和幂等引导事实保持不变。

## 7. 执行方式

生产配置从 `deploy/team-beta/.env.example` 开始，将两个应用镜像替换为 Registry Digest，并在 Compose 外部创建 Secret 文件。演示环境执行：

```bash
./deploy/team-beta/demo.sh up
./deploy/team-beta/demo.sh status
./deploy/team-beta/demo.sh logs
./deploy/team-beta/demo.sh down
```

`up` 只输出 Bootstrap 用户名和 Owner-only Secret 文件路径，不在终端、日志或 CI 证据中回显密码。需要登录时由操作员在本机显式读取该 `0600` 文件。`down` 只停止容器，默认保留 PostgreSQL、Redis、Prometheus 数据卷和本地 Secret，避免把常规停机变成数据删除操作。

## 8. 任务边界

M6-I09 完成镜像、Compose、角色分离、外部 Secret、启动、安全和重启恢复契约。PostgreSQL/Artifact/Redis Snapshot 备份包、空目标恢复、升级/回滚边界、Environment Fingerprint 和完整运维 Runbook 归属 M6-I10。Canonical 负载、故障和 Release Candidate 证据归属 M6-Q03/Q04。
