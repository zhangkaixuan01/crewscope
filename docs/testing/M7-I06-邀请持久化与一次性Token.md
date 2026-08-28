# M7-I06 邀请持久化与一次性 Token

## 1. 完成范围

M7-I06 交付 Team 邀请的应用与基础设施边界：

- `SecureInvitationTokenGenerator` 使用 CSPRNG 生成 256-bit 随机值，并编码为 43 字符无填充 Base64URL Token；
- `HmacSha256InvitationTokenDigester` 使用版本化用途前缀和至少 32-byte 外部密钥执行 HMAC-SHA256；
- `TeamInvitationIssueService` 在事务内只持久化 Digest，明文 Token 仅通过首次创建结果返回；
- `JdbcTeamInvitationRepositoryAdapter` 实现创建、查询、Token Digest 行锁、乐观锁更新、Team 列表和过期批次领取；
- `TeamInvitationExpiryService` 以最大 500 条的有界批次关闭到期邀请，只执行 `PENDING → EXPIRED`，保留邀请与安全审计事实。

邀请 Token 有效期限制为 1 分钟至 30 天。生产安全装配默认关闭；显式启用后必须提供至少 32-byte 的 Base64 HMAC Key，否则应用启动失败关闭。

## 2. 安全与并发边界

数据库、Repository Port、日志字符串和列表结果均不保存或返回明文 Token。`InvitationToken` 与 `TeamInvitationIssueResult` 的字符串表示固定脱敏，Token Digest 使用 64 位小写十六进制持久化。

接受与撤销流程通过 `lockByTokenDigest` 获取 `FOR UPDATE` 行锁，并在调用方事务结束前保持锁定。Team 邀请列表按 `created_at DESC, id DESC` 使用 Keyset 分页，单页最多 200 条。过期 Worker 使用 `FOR UPDATE SKIP LOCKED` 领取批次，允许多个实例并行处理且不重复关闭同一邀请。

HMAC Key 在所有 PENDING 邀请的存续期间必须保持稳定。后续需要轮换时，应先引入可持久化的 Key Version 和多版本 Reader，再切换 Writer，不能直接替换现有 Key。

## 3. 验证结果

执行：

```bash
./mvnw -pl crewscope-infrastructure -am \
  -Dtest=TeamInvitationM7D05Test,TeamInvitationAcceptanceM7D05Test,V32TeamInvitationMigrationIntegrationTest,TeamInvitationInfrastructureContractM7I06Test,InvitationTokenSecurityM7I06Test,JdbcTeamInvitationRepositoryM7I06IntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：

- domain：15 / 15；
- application：12 / 12；
- infrastructure：16 / 16，使用真实 PostgreSQL 17 与 Redis 7.4 Testcontainers；
- 合计：43 / 43。

覆盖 256-bit Token 规范与独立性、HMAC 稳定性和密钥隔离、弱密钥与缺失配置失败关闭、首次明文返回、Digest-only 持久化、创建/查询/撤销/过期、乐观锁、Keyset 分页、8 路并发单终态领取、有界 `SKIP LOCKED` 清理，以及邀请和 DomainEvent 历史事实保留。

## 4. 后续

M7-I07 实现 Bootstrap Operator Account 启动引导。M7-A01 与 M7-A05 将复用本任务的 Token、Digest、Repository 和锁定边界完成带邀请注册及邀请管理 API。
