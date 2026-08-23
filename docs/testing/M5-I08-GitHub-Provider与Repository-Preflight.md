# M5-I08 GitHub Provider 与 Repository Preflight

> 实现模块：`crewscope-application`、`crewscope-infrastructure`、`crewscope-integration`、`crewscope-server`
> 完成日期：2026-08-23

## 1. 交付范围

M5-I08 已交付 GitHub 读取侧 Provider 边界：

- 固定 `GitHubSourceCodeProvider` 的 Repository Catalog、Read、Push 和 Draft PR 能力，Connection Requirement 为 `REQUIRED`；
- 支持 TEAM-owned GitHub App Installation 与 USER-owned OAuth 两种连接身份；
- 每次远端调用重新校验 Connection、ConnectionGrant、Credential Subject、Secret Version、Capability 和 Repository Resource 交集；
- 验证 `/installation` 或 `/user` 的当前远端身份，保存无 Secret 的 Connection Profile；
- 分页同步 Installation/User Repository Catalog，保存稳定 Repository ID、可变 Owner/Name、默认分支、Visibility、权限、ETag Hash、缓存和 RateLimit；
- 保存 `BLOCKED`、`STALE` 等完整目录事实，Repository 选择只暴露 `DELIVERABLE`；
- Repository Preflight 重新读取当前远端事实，校验稳定 ID、权限、组织策略、默认分支和 Grant Resource；
- 将 401、403、404、409、422、429、5xx 与传输失败归一化为稳定安全错误；
- Spring 默认连接 `https://api.github.com`，HTTP Redirect 为 `NEVER`，Loopback HTTP 只作为显式测试配置；
- HTTP Body 以 4 MiB 流式上限读取，Catalog 限制 100 页，跨 Origin Pagination 直接失败关闭。

Push、AskPass、受管 Mirror 和远端 Head 交付由 M5-I09 实现；Draft PR 与 Webhook 由 M5-I10 实现。

## 2. 身份和最小权限

| Authentication | Connection Owner | Credential Subject | Execution Identity |
|---|---|---|---|
| App Installation | TEAM | TEAM 或当前 ORGANIZATION | `TEAM_SERVICE_ACCOUNT` |
| User OAuth | USER | 对应 PRINCIPAL | `DELEGATED_USER` |

App Installation 接受的交付权限集合为 Metadata Read、Contents Read/Write 和 Pull Requests Write。Administration、Actions、Secrets、Members 与 Webhooks 权限会使连接验证失败。传统 OAuth `repo` Scope 只有在 `allowBroadUserOauth` 组织策略显式启用时可用；该策略在每次 Catalog 和 Preflight 调用时继续复验。

## 3. V25 版本权威

`V25__github_connection_profile_revision.sql` 将 GitHub Profile 调整为每个 Connection Version 一份验证快照：

- 凭证轮换或 Connection 生命周期推进后，旧 Profile 保留为历史权威；
- 新 Connection Version 必须重新完成远端验证；
- Repository Catalog 和 RateLimit 精确引用 Profile Version；
- ExternalObservation 新增 `connection_version`，继续引用产生外部事实的精确连接版本；
- Catalog 同步先 Upsert 当前事实，再只把本次缺失的 Repository 标记为 `STALE`，每次同步只推进一次行版本。

## 4. 安全边界

Credential Handle 最长 5 分钟，每次使用 Secret 前重新解析并核对 Secret Version。明文 Buffer 在回调结束后清零，Handle 在关闭或过期后拒绝使用。Provider 原始 Body、Authorization、Token、Endpoint 与内部异常不会进入公开异常、Profile、Catalog 或 RateLimit 表。

Repository Catalog 是选择和缓存事实。Preflight 使用当前 Connection、Grant、Credential 和 GitHub API 结果形成写动作前置权威。

## 5. 自动化验证

专项命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  -Dtest=GitHubProviderAdapterM5I08Test,JdbcGitHubProviderRepositoryAdapterM5I08IntegrationTest,V25GitHubConnectionProfileRevisionMigrationIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-server -am \
  -Dtest=GitHubProviderApplicationConfigurationM5I08Test \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：11 / 11 通过。

覆盖场景包括：

- App/OAuth Owner、Credential Subject 和外部身份不可互换；
- Connection/Grant Version 与 Secret Version 漂移失败关闭；
- App 最小权限、高权限拒绝、OAuth 宽 Scope 组织开关；
- 两页 Catalog、Allowlist、Owner Policy、Fork、Archived、Read-only 与资源交集；
- 默认分支漂移、稳定 Repository ID、RateLimit 和 ETag Hash；
- 跨 Origin Link、HTTP 安全配置和敏感 Provider 错误脱敏；
- Profile/Catalog/RateLimit PostgreSQL 持久化、Organization 隔离、缺失事实 STALE 和单次 Version 推进；
- V24 到 V25 升级、Connection Version 推进、历史 Profile 保留和精确 RateLimit 外键。

## 6. 结论

M5-I08 已形成 GitHub Connection 验证、Repository 发现和写前 Preflight 的生产边界。M5-I09 可以基于同一 Connection/Grant/Credential 权威实现受管 Mirror、AskPass、Fetch 和 Push。
