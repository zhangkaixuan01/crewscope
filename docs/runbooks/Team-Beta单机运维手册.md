# CrewScope Team Beta 单机运维手册

> 适用范围：CrewScope Team Beta 单机七服务部署
> 恢复边界：备份 Schema V26–V30，当前应用目标 Schema V30
> 恢复目标：RPO 24 小时，RTO 4 小时

## 1. 权威数据与职责

Team Beta 的权威恢复集合由三部分组成：

1. PostgreSQL Custom Format Dump：业务事实、任务、动作、通知、投影与审计；
2. Content-addressed Artifact：`references` 元数据和 `objects/sha256` Blob；
3. Redis RDB：Agent、Session、Pending Tool 与执行恢复状态。

Repository Mirror、Worktree、AskPass、临时 Sandbox 和 Prometheus 数据不进入业务恢复包。它们是可重建运行资源或观测数据。操作员负责 Compose 主机、镜像 Digest、外部 Secret、TLS 入口、备份介质和演练证据。

## 2. 首次配置

从 `deploy/team-beta/.env.example` 创建权限为 `0600` 的绝对路径 Operator 环境文件。生产文件至少配置：

- 后端和 Web 不可变镜像 Digest；
- 数据、Secret 与备份根目录；
- Compose Project；
- 应用版本、Git Revision、Dataset Version 与 Seed；
- 备份口令文件；
- 恢复 Schema 边界。

备份口令文件至少 32 字节，独立于备份介质保存。Credential Encryption、Activity Cursor 和 Task Token 的 Key Material 继续由外部 Secret 生命周期管理；备份 Manifest 只记录恢复必需 Key ID，不复制密钥。

Operator 环境文件可以包含受控坐标，不应包含数据库密码、Redis 密码、模型 Key、GitHub Token、飞书 Secret 或 Credential Key Material。

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

正常状态包含 `postgres`、`redis`、`otel-collector`、`prometheus`、`api`、`worker` 和 `web` 七个服务。Web 是唯一宿主入口。API/Worker Readiness、Projection、Outbox、Action、Notification 和 Provider 指标用于日常诊断。

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

备份普通失败或收到可捕获信号时，脚本会清理本次未完整发布的 Bundle/Envelope，并尝试恢复备份前运行的 API、Worker 和 Web。强制中断可能留下孤立 Envelope，但 Bundle 作为最后的提交标记，Retention 不会将其识别为备份。操作员必须检查七服务状态和告警，不能把失败产生的临时文件认定为备份。

## 5. 保留策略

保留策略默认只预览，Daily 保留 7 份，Weekly 保留 4 份；Release 与 On-demand 不自动删除：

```bash
./deploy/team-beta/operations/retain-backups.sh /absolute/path/team-beta.env
./deploy/team-beta/operations/retain-backups.sh /absolute/path/team-beta.env --apply
```

先审阅 `would-delete` 列表，再使用 `--apply`。脚本只删除超出数量的成对 Bundle/Envelope；任一 Envelope 缺失时失败关闭。删除属于不可恢复操作，执行前应确认异机副本和 Release 保留要求。

## 6. 空目标恢复

### 6.1 恢复前检查

恢复使用新的 Compose Project、新的 PostgreSQL/Redis Volume 和空 Artifact 根。API、Worker 和 Web 必须停止。目标 Secret Root 必须具备 Manifest 声明的 Credential、Activity Cursor 与 Task Token Key ID，并保存与源环境一致的有效 Key Material。

恢复应用镜像必须声明：

```text
CREWSCOPE_RESTORE_MIN_SCHEMA=26
CREWSCOPE_RESTORE_MAX_SCHEMA=30
CREWSCOPE_RESTORE_TARGET_SCHEMA=30
```

应用回退只允许使用能够读取已恢复 Schema 的不可变镜像。当前合同允许 V26–V30 备份由当前镜像迁移到 V30；它不允许把 V30 数据库交给只支持更低 Schema 的旧镜像，也不执行数据库降级迁移。

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
  -> 仅启动 API，将 V26–V30 迁移到 V30
  -> Readiness、System Info 与零活动 Smoke
  -> 生成实际 RPO/RTO Evidence
  -> 可选启动 Worker/Web
```

Evidence 位于 `$CREWSCOPE_BACKUP_ROOT/restore-evidence`，权限为 `0600`。它保存 Backup ID、时间、实际 RPO/RTO、源与目标 Schema、Environment Fingerprint、Artifact 校验和 Smoke 摘要，不保存 Secret、正文或 Key Material。

### 6.3 失败处理

密文损坏、Manifest 不一致、组件损坏、备份过期、未来时间、V25/V31、Key ID 缺失或非空目标均失败关闭。恢复开始写入后发生错误时，脚本保留已经写入的目标用于受控诊断，不尝试回滚或覆盖。

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
- V26–V30 迁移边界和不兼容 Schema 拒绝；
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
