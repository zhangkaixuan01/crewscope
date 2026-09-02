# M8-Q02 Release Gate

> 任务：`M8-Q02`
>
> 本机状态：`LOCAL_PRECHECK_PASS`
>
> Linux 运行时与恢复状态：`LINUX_RUNTIME_RECOVERY_PASS`
>
> 最终发行状态：`FINAL_RELEASE_PENDING_SIGNED_TAG`

M8-Q02 使用同一套聚合门禁验证 M8 产品化收口，并把开发机可证明的事实与正式 Linux
发行证据分开记录。本机通过只能形成 `LOCAL_PRECHECK_PASS`；它不能替代受保护 Tag、GHCR
Digest、OIDC 签名、真实告警接收端、公网 TLS、Linux systemd 定时器和生产备份恢复证据。

## 本机验证边界

本轮环境为 macOS arm64、Docker Desktop，使用 Node.js 24、仓库冻结的 pnpm 11.9.0、
Java 21 和 Docker 29。生产 Backend/Web 镜像按 `linux/amd64` 构建并通过 Docker Desktop
仿真运行；该结果证明镜像与 Compose 合同可在本机预检，不证明真实 Linux amd64 主机的
性能、内核、文件所有权、systemd 或公网网络边界。

工作区包含尚未提交的 M8 实现，因此本轮证据绑定当前 Git HEAD 和完整 Working Tree，
不把 HEAD 单独描述为最终候选 Revision。正式 Release Candidate 必须从干净受保护 Tag
重新生成全部证据。

## 一键门禁

```bash
./scripts/m8-q02-local-gate.sh contracts-only
./scripts/m8-q02-local-gate.sh local-precheck
```

`contracts-only` 提供低成本合同预检。`local-precheck` 顺序执行 Maven 全量、M7 固定安全与
事务收敛、前端 Coverage/Build/Histoire/Playwright/Audit、两个 linux/amd64 生产镜像和
隔离十服务运行时。性能敏感的冻结负载与浏览器门禁不得并行运行，避免开发机资源争用
污染 P95 和浏览器超时结果。

## 本机证据

| 验证面 | 命令或证据 | 结果 |
|---|---|---|
| 架构、依赖、配置、发行、部署、恢复、Web、敏感字段、文档 | `./scripts/m8-q02-local-gate.sh contracts-only` | 通过 |
| Java 全量 | `./mvnw --batch-mode --no-transfer-progress clean verify` | Domain 683、Application 642、AgentScope 167、Integration 18、Infrastructure 917、Server 672；合计 3099 / 3099，零失败、错误和跳过 |
| M7 固定安全与事务收敛 | `m7-q01-security-gate.sh`、`m7-q02-convergence-gate.sh` | 顺序复验通过 |
| GitHub 导入耐久执行 | M8-Q02 Application/Infrastructure/Server 合同 | API 持久化入队、Worker Lease/Fencing 执行、终态重放、取消/重试边界与 V36 升级通过 |
| 前端单元与 Coverage | `pnpm test:coverage` | 119 Files、688 Tests 通过；68.36/61.96/69.84/72.56 |
| 前端生产构建 | `pnpm build` | 通过 |
| 组件场景 | `pnpm story:build` | 21 Stories、154 Variants 通过 |
| 浏览器、视觉与 Axe | `pnpm test:e2e` | 248 / 248 通过 |
| 生产依赖 | `pnpm audit --prod --audit-level=high` | 0 个已知漏洞 |
| Backend/Web 生产镜像 | `docker build --platform linux/amd64 ...` | amd64/linux 通过；Backend `sha256:4ae6f33c...f54dd`、Web `sha256:6bb94118...89f2e` |
| 十服务与 Docker API 隔离 | `m8-q02-local-runtime-gate.sh` | 十服务全部 Healthy，Flyway V1–V36；API 仅挂 Personal/Template Runtime，Worker 仅挂 Task/Coding Runtime；Backup Metrics 只读采集 textfile；Worker 可使用 Container API、Volume API 失败关闭，无宿主 Socket Mount |
| 备份年龄指标 | Deployment Contract 临时 Daily Bundle Fixture | 脚本运行、Age 与 Last-success Prometheus 指标通过 |
| Trace 边界 | 生产 Compose 与配置合同 | 无可查询 Backend 时允许显式关闭，不伪装为已采集 |

首次将 Maven 与 Playwright 并行运行时，冻结的两个 M6 负载 P95 和两个浏览器用例受到
本机 CPU/容器争用影响。该次运行保留为无效并行样本，不调整性能或测试门槛；上表使用后续
独占资源的顺序复验结果。`ApiObservabilityWebFilterTest` 也在撤销临时
`LOGGING_LEVEL_ROOT=ERROR` 环境覆盖后 7 / 7 通过，证明先前 3 个错误是测试日志捕获环境造成，不是代码回归。

当前恢复边界为 `V26..V36 -> V36`，新 Manifest 写入 V36 上限；旧 Manifest 仍可恢复，但它不得包含
超过声明上限的源 Schema。恢复、M7 Release Contract、Operator 环境模板和 Runbook 已统一到 V36。

## 十服务验收

运行时门禁使用独立 Compose Project、独立临时 Data/Secret Root 和独立 Web 端口，不接触
开发环境 PostgreSQL/Redis。门禁必须同时证明：

1. Docker Socket Proxy、PostgreSQL、Redis、OTel Collector、Prometheus、Alertmanager、
   API、Worker 和 Web 等十个服务均为 `running/healthy`；
2. Worker 没有宿主 Docker Socket Mount，Proxy 是唯一 Socket Owner；
3. Worker 通过 `DOCKER_HOST` 可以使用 Container API，但 Docker Volume API 被拒绝；
4. Prometheus Rule 和 Alertmanager 配置可由正式镜像内工具解析；
5. Web `/healthz` 与 Setup Center 可访问；
6. 验证完成后只清理本次明确命名的 Compose Project 和临时目录。

## Linux amd64 生产验证

2026-09-02 在全新 Ubuntu 24.04、Linux amd64、4C8G 主机完成 Working Tree Release
Candidate 验证。证据绑定 Git HEAD `3559b58` 与本地/远端内容 Hash 一致的 126 项 M8
未提交改动，不把该 HEAD 冒充受保护 Tag。

| 验证面 | 结果 |
|---|---|
| 生产镜像 | 主机原生构建 Backend `sha256:f81c12c7...592128`、Web `sha256:c8920a76...121beb`；Socket Proxy 固定源码构建为 `sha256:68b9ebbe...f0a5d1` |
| 十服务 | PostgreSQL、Redis、OTel、Prometheus、Alertmanager、Backup Metrics、Socket Proxy、API、Worker、Web 全部 Healthy；历史验证 Flyway V35；最新版部署要求前滚至 V36；API/Worker 无 ERROR |
| 公网入口 | `https://47.99.220.125` 的 `/healthz`、`/login`、`/register` 均为 200；Let's Encrypt IP SAN、CA 链、HSTS、CSP 通过 |
| 浏览器安全 | 注册 Session Cookie 同时具备 `Secure`、`HTTPOnly`、`SameSite=Lax` |
| 产品基础闭环 | OPEN 注册、首 Team、邀请注册、两个独立 Account/Member、两个 Personal Agent 与 Setup Readiness 通过；恢复库保留 5 Account、1 Team、2 Member、3 Agent Profile |
| 自动备份 | Daily/Weekly/Health systemd Timer 安装并保持 Active；Daily 加密 Bundle/Envelope 成对生成，健康指标 2 项 |
| 空目标恢复 | 历史证据：独立 Compose Project、Volume、Data Root 和 `172.31.0.0/24` 网络恢复成功；V35→V35、Readiness UP、RPO 609 秒、RTO 32 秒；Evidence SHA-256 `16aaf2e1...51e44`。V36 前滚随最新版部署复验 |
| 告警 | 私有 Webhook Receiver 直接 firing/resolved 各 1 次；正式 `CrewScopeWorkerUnavailable` 规则按 5 分钟阈值进入 firing，Worker 恢复后收到 resolved，Prometheus 活跃告警归零 |
| 证书续期 | TLS-ALPN-01 强制续期成功；Daily systemd Timer 安装，非到期运行返回 `certificateRenewal=not-due`；当前证书有效至 2026-09-09 |
| 主机重启 | Boot ID 变化后十服务重新 Healthy；21 个 Session Key 保持；Nginx、Receiver、Registry、Backup/Health/Certificate Timer 全部恢复 |
| 执行隔离 | Worker 无宿主 Docker Socket Mount；Container API 可用，Volume API 失败关闭；Registry 仅 Loopback |

本轮生产演练发现并修复三项此前合同未捕获的问题：systemd 备份安装脚本把仓库根解析成
`deploy/` 导致 `deploy/deploy`；固定 `172.30.0.0/24` 阻止同机独立恢复；Environment
Fingerprint 首次运行会通过 Maven Wrapper 临时下载构建工具。当前分别以正确的三级父目录、
可配置且默认兼容的 Backend 子网/Web IP、可选宿主构建工具坐标收口，并加入自动合同。

生产服务器没有录入真实模型 API Key 或 GitHub OAuth/Token。因此 Coding、Review 和 Draft
PR 的外部 Provider 闭环被明确记录为 `EXTERNAL_CREDENTIALS_REQUIRED`；本轮没有伪造模型
响应、GitHub 仓库或 PR。GitHub Import 的持久化入队、Worker Lease/Fencing、终态重放和
受管仓库安全边界由本机全量合同继续提供证据。

## 最终 Release 待验项

以下正式发行与外部集成证据仍需补齐：

- 从干净受保护 Tag 重建镜像，完成 GHCR、SBOM、Provenance、Cosign OIDC 和 GitHub Release；
- 在用户提供授权的测试模型与 GitHub 凭证后完成真实 Coding、Review 和 Draft PR；
- 将短期 IP 入口迁移到正式域名证书，或继续保留已验证的短期 IP 证书自动续期；
- 使用正式发行 Digest 重跑安装与升级恢复，替代本次 Working Tree 候选证据。

在这些证据完成前，M8 的状态保持：

```text
LOCAL_PRECHECK_PASS
LINUX_RUNTIME_RECOVERY_PASS
FINAL_RELEASE_PENDING_SIGNED_TAG
```
