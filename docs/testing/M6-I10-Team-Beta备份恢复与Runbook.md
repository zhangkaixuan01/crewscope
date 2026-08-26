# M6-I10 Team Beta 备份恢复与 Runbook

> 日期：2026-08-26
> 对应任务：`M6-I10`
> 对应决策：[ADR-023 Team Beta 单机部署与发布验证协议](../adr/ADR-023-Team-Beta单机部署与发布验证协议.md)

## 1. 交付结果

M6-I10 把 Team Beta 三组件备份、完整性、整体加密、空目标恢复、版本边界、保留策略和运维步骤实现为可执行合同：

- `backup.sh`：Maintenance/Quiescence、PostgreSQL Custom Dump、完整 Artifact、Redis RDB、Manifest、Environment Fingerprint、整体加密和 Bundle/Envelope 成对发布；
- `restore.sh`：坏包拒绝、Credential Key ID 校验、三类空目标门禁、V26–V30 到 V30 迁移、Smoke 和 RPO/RTO Evidence；
- `retain-backups.sh`：默认 Dry-run，显式保留 Daily 7 / Weekly 4；
- `team-beta-recovery.mjs`：流式 SHA-256、Manifest/Envelope、Artifact 与环境指纹校验；
- `check-team-beta-recovery.mjs`：恢复合同的无凭证 CI 门禁；
- [Team Beta 单机运维手册](../runbooks/Team-Beta单机运维手册.md)：部署、备份、恢复、失败处置与演练 Runbook。

## 2. 备份格式

每个 Backup ID 生成两个文件：

```text
<backupId>.bundle.enc
<backupId>.envelope.json
```

加密前 Payload 包含：

```text
manifest.json
postgres.dump
artifacts.tar.gz
redis.rdb
artifact-verification.json
environment-fingerprint.json
```

Manifest 固定声明 PostgreSQL、Artifact 和 Redis 三个组件的格式、字节长度和 SHA-256，并记录应用/Git/Schema、创建时间、Credential Key ID、Maintenance 零活动证明与 Environment Fingerprint。AES-256-CBC 使用 PBKDF2-SHA256 和 200000 次迭代进行整体静态加密。Envelope 记录密文文件名、长度、SHA-256 与加密前 Manifest SHA-256。两个文件先在私有 Staging 目录完整生成，再先发布 Envelope、最后发布作为可发现提交标记的 Bundle。普通失败与可捕获信号会清理本次已移入的文件；强制中断最多留下不可发现的孤立 Envelope，不会伪装成可恢复备份。

## 3. 失败关闭矩阵

| 场景 | 结果 |
|---|---|
| 密文长度或 SHA-256 漂移 | 解密前拒绝 |
| Manifest 或组件长度/Hash 漂移 | 写入目标前拒绝 |
| Archive 绝对路径、`..`、链接或特殊文件 | 解压前拒绝 |
| 创建时间超过 24 小时或位于未来 | 拒绝 |
| Schema V25 或 V31 | 拒绝 |
| Credential Key ID 缺失 | 写入目标前拒绝 |
| PostgreSQL、Artifact 或 Redis 任一目标非空 | 拒绝覆盖 |
| Artifact 根/目录符号链接、Reference 缺失、Blob 长度/Hash/路径漂移 | 拒绝 |
| Flyway 未到目标 V30、API 不 Ready 或存在活动执行 | 不开放流量 |

恢复失败不修改备份包，不自动删除部分目标。操作员使用全新的空目标修复后重试。

## 4. 自动化合同

```bash
node scripts/check-team-beta-recovery.mjs
```

自动化覆盖 Shell/Node 语法、Manifest 创建与验证、密文/组件篡改、路径穿越、过期/未来时间、V25/V31、Artifact 缺失/损坏/符号链接、Retention Dry-run/Apply、Runtime Git Ignore、CI 与文档合同。

## 5. 真实开发机演练

演练环境为 macOS/arm64 + Docker Desktop，应用镜像为 `crewscope-backend:demo`。源与目标使用不同 Compose Project、Data Root、Secret Root、PostgreSQL Volume 和 Redis Volume。

| 演练 | Backup ID | 源 Schema | 目标 Schema | RPO | RTO | 结果 |
|---|---|---:|---:|---:|---:|---|
| V30 完整数据恢复 | `20260826T150424Z-6de3d8befa73fabd` | 30 | 30 | 77s | 63s | 通过 |
| V26 升级恢复 | `20260826T151011Z-192d252fcbe132bb` | 26 | 30 | 38s | 64s | 通过 |

V30 包的环境指纹为 `20086bf62b4549b5f1ef7485fd7dd4a24704ee632604afe09058dc5d05e2ba26`，密文 SHA-256 为 `8c834586f0ff75ac5577ffcb24e5ff56b7138cbdd7c2b51bb930fe374db798a9`。组件验证结果：

```text
PostgreSQL   1,030,586 bytes  SHA-256 438f0fc00f233462e4e9df85b7a231448d4bbf1e069f0efeb68d8355429036c1
Artifact         1,671 bytes  SHA-256 8bdbc5c3faaee135d61dc7917dcfac1b7c8604a2b5e8f28498ce6bb240d6a056
Redis               89 bytes  SHA-256 16cf5843a8bd466974c11975189fa829d57c8ad78be0b8326f3734ad1da219dc
```

恢复后确认 Flyway V30、Organization 1、Runtime Principal 1、Artifact Reference/Object 各 1。受控演练 Artifact 的 Blob 长度和 SHA-256 不变，Reference `storageUri` 从源 Data Root 原子重定位到目标 Data Root 后再次通过完整校验。Redis RDB 被目标实例加载；API 启动后的瞬时 DB Size 为 1，租约类 Key 允许按 TTL 到期。

V26 源通过当前镜像加 `SPRING_FLYWAY_TARGET=26` 真实创建，确认 `notification_delivery` 尚不存在；备份逻辑把该版本 Notification 活动计数视为 0。恢复使用无 Flyway Target 限制的当前镜像，实际执行 V27–V30 并以 V30 Ready，证明边界不是修改 Flyway History 的模拟结果。

失败关闭实测：

```text
Bundle/Envelope 身份不一致 -> Ciphertext filename does not match the envelope，exit 2
已恢复 Artifact 目标再次执行 -> Artifact restore target is not empty，exit 2
```

自动化合同另行覆盖密文与组件字节篡改、V25/V31、过期/未来时间、缺失 Credential Key ID、Artifact 缺失/损坏和路径穿越。两次成功恢复均在 Worker/Web 关闭状态生成 Evidence，API Readiness 为 `UP`，活动执行为 0，实际 RPO/RTO 均满足 24 小时/4 小时门槛。

该结果是开发证据，不替代 Linux amd64 Canonical Release Evidence。M6-Q03 继续在发布环境完成完整故障、负载和恢复验收。
