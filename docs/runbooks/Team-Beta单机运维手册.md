# CrewScope Team Beta 单机运维手册

> 适用范围：CrewScope Team Beta 单机十服务部署
> 恢复边界：备份 Schema V26–V36，当前应用目标 Schema V36
> 恢复目标：RPO 24 小时，RTO 4 小时

## 1. 权威数据与职责

Team Beta 的权威恢复集合由三部分组成：

1. PostgreSQL Custom Format Dump：业务事实、任务、动作、通知、投影与审计；
2. Content-addressed Artifact：`references` 元数据和 `objects/sha256` Blob；
3. Redis RDB：Agent、Session、Pending Tool 与执行恢复状态。

Repository Mirror、Worktree、AskPass、临时 Sandbox 和 Prometheus 数据不进入业务恢复包。它们是可重建运行资源或观测数据。操作员负责 Compose 主机、镜像 Digest、外部 Secret、TLS 入口、备份介质和演练证据。

## 2. 首次配置

宿主机使用 Linux amd64，并准备 Docker Engine、Docker Compose、OpenJDK 17、Node.js 24、
pnpm 11.9.0、jq、OpenSSL、tar 与 gzip。备份 Environment Fingerprint 会调用这些固定工具；
缺失工具或版本不兼容时备份失败关闭，并由清理钩子恢复进入维护模式前正在运行的服务。

从 `deploy/team-beta/.env.example` 创建权限为 `0600` 的绝对路径 Operator 环境文件。生产文件至少配置：

- 后端和 Web 不可变镜像 Digest；
- 数据、Secret 与备份根目录；
- Compose Project；
- 内部 Backend 子网与 Web 固定代理 IP；同机恢复 Project 必须使用不重叠的坐标；
- 8C16G 主机的密码 Hash Permit，当前冻结值为 4；
- 应用版本、Git Revision、Dataset Version 与 Seed；
- 备份口令文件；
- 恢复 Schema 边界。

备份口令文件至少 32 字节，独立于备份介质保存。Credential Encryption、Activity Cursor 和 Task Token 的 Key Material 继续由外部 Secret 生命周期管理；备份 Manifest 只记录恢复必需 Key ID，不复制密钥。

Operator 环境文件可以包含受控坐标，不应包含数据库密码、Redis 密码、模型 Key、GitHub Token、飞书 Secret 或 Credential Key Material。

正式 Compose 将 Web 仅绑定到宿主机环回地址。公网入口使用宿主机 TLS 终止器转发到该端口，
可从 `deploy/team-beta/nginx-host-tls.conf.example` 开始配置，并替换示例中的全部域名坐标与证书路径。生产环境使用自有域名和受信任证书，
并只对公网开放 80/443；API、Worker、PostgreSQL、Redis、Prometheus 和 OTel 端口保持不公开。

`backend` 与 `observability` 网络保持 `internal: true`。API 和 Worker 额外加入不发布宿主端口的 `provider-egress` 网络，用于模型 Provider、GitHub 和飞书的 DNS/HTTPS 出站。安全组与宿主机防火墙需要允许容器转发后的 DNS 和 443 出站；仅验证宿主机能访问 Provider 不足以证明应用容器可达。

## 3. 日常启动与检查

```bash
docker compose \
  --env-file /absolute/path/team-beta.env \
  -p crewscope-team-beta \
  -f deploy/team-beta/compose.yaml \
  up --detach --wait

docker compose \
  --env-file /absolute/path/team-beta.env \
  -p crewscope-team-beta \
  -f deploy/team-beta/compose.yaml \
  ps
```

正常状态包含 `postgres`、`redis`、`otel-collector`、`prometheus`、`alertmanager`、`backup-metrics`、`docker-socket-proxy`、`api`、`worker` 和 `web` 十个服务。Web 是唯一宿主入口。API/Worker Readiness、Projection、Outbox、Action、Notification、Provider 和备份新鲜度指标用于日常诊断。

API 和 Worker 都以只读根文件系统运行。API 只挂载可写的 `runtime/personal-agent` 与
`runtime/template-agent`，Worker 只挂载可写的 `runtime/task-agent` 与
`runtime/coding-agent`；四个目录在容器内保持与宿主相同的绝对路径。Personal Conversation
和 Team Observer 在 API 内同步创建 AgentScope Harness 工作区；对应挂载缺失时，业务配置
即使已就绪，运行时仍会在模型调用前失败。发布后应分别验证四个 Runtime Root 可创建子目录，
并保持宿主目录归属 `10001:10001`。API 与 Worker 不共享对方的 Runtime 工作区。

Worker 不再挂载宿主 Docker Socket，也不加入宿主 Socket 用户组。唯一接触宿主 Socket 的是
`docker-socket-proxy`，它只在 `backend` 内部网络监听，并通过 `CONTAINERS/IMAGES/POST/EXEC`
等白名单提供 Sandbox 所需的 Docker API；`BUILD`、`VOLUMES`、`SYSTEM`、`SWARM` 和 Secret
管理接口关闭。生产环境必须把 `CREWSCOPE_DOCKER_SOCKET_PROXY_IMAGE` 固定为已扫描的
Digest。该代理降低 Worker 直接获得宿主控制面的风险，但代理进程和 Docker Engine 仍属于
同一台执行主机的信任边界，需用专用主机和逃逸演练验证残余风险。

生产默认显式关闭 OTLP Trace（`CREWSCOPE_OTLP_TRACING_ENABLED=false`），因为公开模板不
捆绑可查询 Trace Backend；OTel Collector 不会把“已开启但由 nop 丢弃”的状态伪装成可查询
Trace。需要 Trace 时，应在受控 Compose Overlay 中接入 Tempo/Jaeger 等固定 Digest Backend，
并将 API/Worker 的开关和 Endpoint 一起变更、验证后再上线。

Prometheus 加载 `prometheus-alerts.yaml`，并把告警发送到内部 `alertmanager`。默认 receiver
是 no-op，部署者应在私有 Overlay 中替换 `alertmanager.yaml` 的 receiver（Webhook、邮件或
企业通知），并显式让 Alertmanager 加入具备通知目标路由的受控出站网络；默认 `internal`
观测网络不会隐式放通宿主机或公网。不得把 Webhook Secret 提交到仓库。告警规则只使用低基数标签，覆盖 API/Worker
不可用、Runtime 健康、Provider 错误、遥测丢弃和备份过期。

首次干净启动时，API 会在 Runtime Service Principal 建立后幂等初始化非秘密模型目录。进入“模型与凭证”页面应至少看到 `DeepSeek / deepseek-v4-flash`，随后由成员创建 USER、TEAM 或 ORGANIZATION ModelConnection 并单向录入 API Key。启动初始化不会生成测试 Key、共享 Key 或默认 Connection。

创建连接前后都应从 API、Worker 容器验证 Provider 域名能够解析，并可通过 HTTPS 建立连接。未携带 Key 请求 DeepSeek `/models` 返回 `401` 可以证明网络链路可达；`Network unreachable`、解析失败或连接超时表示 `provider-egress`、宿主机转发或出站规则仍未闭合。不要通过发布 API/Worker 端口解决出站问题。

若页面显示“没有可用 Provider”，先检查 API 当前启动周期日志，再只读核对 `model_provider_definition`、`model_catalog_entry` 和 `model_price_revision`。三者均为空表示部署镜像未包含平台目录初始化；不要手写 Content Hash 或直接插入临时价格，应升级到包含 `PlatformModelCatalogInitializer` 的不可变后端镜像并重启 API。Provider 已存在但按钮仍禁用时，继续检查当前 Team 上下文和 Provider 状态。

## 4. 创建备份

每天执行 Daily 备份，每周执行 Weekly 备份，在 Release Candidate 前执行 Release 备份：

```bash
./deploy/team-beta/operations/backup.sh /absolute/path/team-beta.env daily
./deploy/team-beta/operations/backup.sh /absolute/path/team-beta.env weekly
./deploy/team-beta/operations/backup.sh /absolute/path/team-beta.env release
```

脚本执行以下受控流程：

```text
停止 Web 入口
  -> 等待活动 TaskExecution、Action Dispatch、Notification Dispatch 归零
  -> 停止 Worker 和 API
  -> 再次确认零活动
  -> PostgreSQL Custom Dump
  -> Artifact 引用、长度与 SHA-256 校验并归档
  -> Redis SAVE 与 RDB Snapshot
  -> 生成 Environment Fingerprint 和 Manifest
  -> AES-256-CBC + PBKDF2-SHA256 200000 次整体加密
  -> 生成密文长度、密文 SHA-256 与 Manifest SHA-256 Envelope
  -> 从私有 Staging 目录先发布 Envelope，最后以 Bundle 作为提交标记
  -> 恢复备份前处于运行状态的服务
```

成功输出 `backupId`、Bundle、Envelope、Schema Version 和 Environment Fingerprint。Bundle 与 Envelope 必须成对复制到受控异机介质；只复制其中一个不构成可恢复备份。脚本使用互斥锁拒绝并发备份。

Environment Fingerprint 只把宿主 Java、Maven 和 pnpm 作为可选诊断坐标；缺失时记录
`unavailable`。备份不得调用 Maven Wrapper、下载构建工具或依赖 Maven Central 可用性。
Docker、Compose、Node、磁盘和发行坐标仍为必需事实，缺失时失败关闭。

备份普通失败或收到可捕获信号时，脚本会清理本次未完整发布的 Bundle/Envelope，并尝试恢复备份前运行的 API、Worker 和 Web。强制中断可能留下孤立 Envelope，但 Bundle 作为最后的提交标记，Retention 不会将其识别为备份。操作员必须检查十服务状态和告警，不能把失败产生的临时文件认定为备份。

## 5. 保留策略

保留策略默认只预览，Daily 保留 7 份，Weekly 保留 4 份；Release 与 On-demand 不自动删除：

```bash
./deploy/team-beta/operations/retain-backups.sh /absolute/path/team-beta.env
./deploy/team-beta/operations/retain-backups.sh /absolute/path/team-beta.env --apply
```

Linux 生产机可使用仓库提供的幂等 systemd 调度脚本，重复执行不会改变备份语义：

```bash
sudo deploy/team-beta/operations/manage-backup-schedule.sh install
systemctl list-timers 'crewscope-backup-*'
```

安装脚本会把当前仓库绝对路径写入 systemd Unit，不要求固定部署在 `/opt/crewscope`；Unit
通过 `/etc/crewscope/team-beta.env` 读取 Operator 坐标。

`crewscope-backup-health.sh` 每 15 分钟检查最新 Daily Bundle，并写入 `$CREWSCOPE_DATA_ROOT/metrics/crewscope_backup.prom`。`backup-metrics` 只启用 node_exporter textfile collector 并以只读方式采集该目录；Prometheus 规则在年龄超过 26 小时时触发
`CrewScopeBackupStale`，systemd 失败状态和脚本退出码仍可作为独立故障信号接入
主机监控。卸载时执行 `sudo deploy/team-beta/operations/manage-backup-schedule.sh uninstall`。

先审阅 `would-delete` 列表，再使用 `--apply`。脚本只删除超出数量的成对 Bundle/Envelope；任一 Envelope 缺失时失败关闭。删除属于不可恢复操作，执行前应确认异机副本和 Release 保留要求。

## 6. 空目标恢复

### 6.1 恢复前检查

恢复使用新的 Compose Project、新的 PostgreSQL/Redis Volume 和空 Artifact 根。API、Worker 和 Web 必须停止。目标 Secret Root 必须具备 Manifest 声明的 Credential、Activity Cursor 与 Task Token Key ID，并保存与源环境一致的有效 Key Material。

同一主机并行保留源环境进行空目标演练时，恢复环境文件必须同时设置不同的内部网络坐标，
且 Web IP 必须属于所选子网，例如：

```text
CREWSCOPE_COMPOSE_PROJECT=crewscope-team-beta-restore
CREWSCOPE_BACKEND_SUBNET=172.31.0.0/24
CREWSCOPE_WEB_INTERNAL_IP=172.31.0.10
```

默认生产坐标仍为 `172.30.0.0/24` 和 `172.30.0.10`。Docker 拒绝重叠网段时不得复用源
Project 网络或覆盖源 Volume。

恢复应用镜像必须声明：

```text
CREWSCOPE_RESTORE_MIN_SCHEMA=26
CREWSCOPE_RESTORE_MAX_SCHEMA=36
CREWSCOPE_RESTORE_TARGET_SCHEMA=36
```

应用回退只允许使用能够读取已恢复 Schema 的不可变镜像。当前合同允许 V26–V36 备份由当前镜像迁移到 V36；它不允许把 V36 数据库交给只支持更低 Schema 的旧镜像，也不执行数据库降级迁移。V34 时生成的旧格式备份仍可恢复，但其源 Schema 不得超过 Manifest 声明的 V34 上限。

### 6.2 执行恢复

```bash
./deploy/team-beta/operations/restore.sh \
  /absolute/path/team-beta-restore.env \
  /absolute/path/backups/daily/20260826T120000Z-id.bundle.enc
```

默认恢复 PostgreSQL、Artifact 和 Redis，只启动 API 完成 Flyway 与 Smoke，保持 Worker 和 Web 关闭。确认 Evidence 后显式开放流量：

```bash
./deploy/team-beta/operations/restore.sh \
  /absolute/path/team-beta-restore.env \
  /absolute/path/backups/daily/20260826T120000Z-id.bundle.enc \
  --enable-traffic
```

`--enable-traffic` 只适用于一次性恢复流程。若第一次已完成默认恢复，应人工审阅 Evidence 后使用 Compose 启动 Worker/Web，不得在同一非空目标再次运行恢复。

恢复严格按以下顺序执行：

```text
校验 Envelope、密文长度和 SHA-256
  -> 整包解密并拒绝路径穿越、链接和特殊文件
  -> 校验 Manifest、三组件长度/SHA-256、24 小时 RPO、Schema 和 Key ID
  -> 确认 Artifact、Redis、PostgreSQL 目标均为空
  -> 恢复 PostgreSQL
  -> 恢复 Artifact，将 Reference storageUri 重定位到目标 Data Root 并复验全部 Object
  -> 恢复 Redis RDB
  -> 仅启动 API，将 V26–V36 迁移到 V36
  -> Readiness、System Info 与零活动 Smoke
  -> 生成实际 RPO/RTO Evidence
  -> 可选启动 Worker/Web
```

Evidence 位于 `$CREWSCOPE_BACKUP_ROOT/restore-evidence`，权限为 `0600`。它保存 Backup ID、时间、实际 RPO/RTO、源与目标 Schema、Environment Fingerprint、Artifact 校验和 Smoke 摘要，不保存 Secret、正文或 Key Material。

### 6.3 失败处理

密文损坏、Manifest 不一致、组件损坏、备份过期、未来时间、V25/V37、Key ID 缺失或非空目标均失败关闭。恢复开始写入后发生错误时，脚本保留已经写入的目标用于受控诊断，不尝试回滚或覆盖。

重试步骤：

1. 保留失败日志和目标坐标，记录 Backup ID 与失败阶段；
2. 停止该目标全部服务；
3. 使用新的 Compose Project、空 Volume 和空 Artifact 根；
4. 修复镜像、Secret、容量或备份介质问题；
5. 从校验阶段重新执行恢复。

不得在部分恢复目标上再次运行脚本，不得删除源环境或最后一份可用备份。

## 7. 演练与发布证据

每个 Release Candidate 至少完成一次空目标恢复演练。演练检查：

- Manifest 与三组件 Hash；
- V26–V36 迁移边界和不兼容 Schema 拒绝；
- Organization、Runtime Principal、Artifact、Redis 与 API Readiness；
- 坏包、过期/未来包和非空目标失败关闭；
- 实际 RPO `<= 86400s`、RTO `<= 14400s`；
- 源与目标容器结束后的受控状态。

macOS/arm64 开发演练只作为诊断证据。发布结论使用 ADR-023 冻结的 Linux amd64 Canonical Release Environment，并把恢复 Evidence Hash 纳入 Release Manifest。

## 8. 常用诊断

```bash
node scripts/check-team-beta-recovery.mjs
node scripts/check-team-beta-deployment.mjs
docker compose ls
```

发生故障时优先检查容器状态、Readiness、PostgreSQL Flyway Version、磁盘容量、备份介质权限、外部 Secret Key ID 和 Compose Project。任何诊断输出进入工单或证据前都应移除密码、Token、Key Material、模型正文、命令输出与成员 PII。
