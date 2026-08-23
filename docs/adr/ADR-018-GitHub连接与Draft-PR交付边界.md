# ADR-018：GitHub 连接与 Draft PR 交付边界

> 状态：ACCEPTED<br>
> 日期：2026-08-22<br>
> 关联决策：[ADR-004](ADR-004-CredentialStore与动作凭证.md)、[ADR-006](ADR-006-ProviderBinding解析与授权.md)、[ADR-007](ADR-007-API命令与并发协议.md)<br>
> 影响里程碑：M5

## 背景

CrewScope 需要把 M4 受管本地 Git 交付推送到 GitHub，并在成员完成 Gate Review 与 ActionBundle 确认后创建唯一 Draft PR。团队共享执行身份与成员委托身份具有不同所有权、外部授权和资源范围；Git Push 没有 CrewScope 幂等键，GitHub Create Pull Request API 也不能作为通用幂等存储。凭证、远端漂移、响应丢失、限流和重复调度必须在 Connector Worker 边界收敛。

## 决策

### 连接与外部身份

M5 支持两类 GitHub Connection：

| Connection | Binding Owner | Credential Subject | 外部身份 | 外部授权事实 |
|---|---|---|---|---|
| TEAM-owned GitHub App | TEAM | TEAM 或 ORGANIZATION | `TEAM_SERVICE_ACCOUNT` | App Installation ID、Repository Allowlist、Installation Permissions |
| USER-owned OAuth | USER | PRINCIPAL | `DELEGATED_USER` | GitHub User、OAuth Grant、Repository Allowlist、有效 Scope |

Binding Owner、Credential Subject 和外部身份分别保存并精确匹配。USER-owned OAuth 不能用于 TEAM Execution；TEAM-owned App 不能转换为成员委托身份。组织级 GitHub App 可以用 ORGANIZATION Credential Subject，但具体团队仍通过 ConnectionGrant、ProviderBinding 和 Repository Allowlist 收窄。

TEAM-owned GitHub App 是团队交付默认方式。Connector Worker 在动作窗口使用 App 私钥签发的 JWT 调用 Installation Access Token API，按目标 Repository 与权限继续收窄 Token；Installation Token、App 私钥和 JWT 都不进入 Agent、Sandbox、浏览器、日志、Memory 或 Artifact。USER-owned OAuth 用于个人执行与成员授权资源，生产实现必须校验当前授权用户、Token 状态、实际仓库权限和平台策略，不接受客户端声明的 GitHub User 或 Scope。

### Repository Catalog 与 Preflight

Repository Catalog 使用 Provider API 分页发现资源：

- GitHub App 使用 Installation Repository Catalog；
- OAuth 使用当前授权用户可访问的 Repository Catalog；
- 结果与 ConnectionGrant、ProviderBinding、Repository Allowlist 和 PolicySnapshot 求交集；
- 保存 GitHub Repository ID 作为稳定外部身份，Owner/Name 作为可变展示和定位事实；
- Catalog 返回 Default Branch、Archived、Fork、Visibility、Pull/Push 权限和权限摘要；
- Draft PR 交付默认排除 Archived、Fork、无 Pull 或无 Push 权限的 Repository；Fork 交付需要独立策略显式允许；
- 每次写操作重新读取 Repository、Default Branch、权限、Branch Head 与保护策略，不把 Catalog 缓存当作授权事实。

Catalog 支持 GitHub `Link` 分页，并保存 `X-RateLimit-Limit`、`Remaining`、`Used`、`Reset` 和 `Resource`。缓存键包含 Connection 与授权版本，ETag 或短 TTL 只减少读取；Connection 撤销、Grant 变化、Webhook 或权限错误立即使缓存失效。公开 API 返回稳定 RateLimit 摘要和可重试时间，不返回 Provider 原始 Body、内部 Endpoint 或授权 Header。

### 最小权限

Draft PR 交付的最小 Repository 权限为：

```text
Repository Metadata: Read
Contents: Read and Write
Pull Requests: Write
```

GitHub App Installation Token 与细粒度用户授权都按目标 Repository 收窄。MVP 不要求 Administration、Actions、Secrets、Members 或 Webhooks Write。Webhook 由独立 Connection 能力和密钥管理；需要管理 Webhook 时采用独立管理员动作，不附加到日常 Push/PR Token。传统宽范围 OAuth `repo` Scope 不能作为组织默认，启用时必须由组织策略显式允许并在 UI 展示授权宽度。

### 受管 Mirror 与远端

GitHub Repository 映射到平台配置根目录下的受管 bare Mirror。Mirror 键包含 Organization、Provider 和 GitHub Repository ID；宿主路径不由浏览器、Agent 或模型提交。Remote URL 使用规范 HTTPS 地址且不包含用户名、Token 或查询凭证，Repository Config、Worktree Config 和 Archive Artifact 均不保存凭证明文。

Fetch/Push 由 Connector Worker 使用 M4 类型化 Git Process 边界执行。每次动作重新验证 Mirror Owner、bare 形状、Repository ID、Connection、Grant、Binding、目标 Branch、Baseline、Delivery Head、Confirmation 与当前策略。Mirror 只保存 Git Object 和受管 Ref，不承担授权判断。

### AskPass 与动作凭证

Git HTTP 凭证只在 Connector Worker 的精确动作窗口解析：

1. Credential Service 依据 PlannedAction、Task Token、ProviderBinding 与当前授权签发短生命周期 Credential Handle；
2. Worker 创建仅当前系统用户可读的临时 Secret 文件和不含 Secret 的 `GIT_ASKPASS` 脚本；
3. Git argv 只包含类型化 Git 参数与无凭证 HTTPS Remote，环境只包含 AskPass 路径和 Secret 文件路径，不包含 Token 值；
4. Process 环境从最小 Allowlist 重建，关闭交互式终端、系统/全局 Git Config、Pager 和 Hook；
5. 输出、异常和 Audit 只保存安全分类，Git Credential 协议输出不进入日志；
6. 动作结束、超时、取消和异常路径都清零内存并删除 AskPass、Secret 文件和动作环境。

临时 Secret 文件是动作窗口内的受控明文载体，必须使用 Owner-only 权限、受管临时根目录和 `finally/close` 清理。Agent 与 Sandbox 只能看到不透明 Credential Handle 和动作结果。

### Push 幂等与竞态控制

Push Operation 使用以下稳定坐标：

```text
repository_id
branch_full_ref
delivery_head_sha
expected_remote_head_sha | ABSENT
provider_binding_version
connection_grant_version
```

执行顺序：

1. 查询远端 Branch Head；
2. Head 已等于 Delivery Head 时返回既有成功，不执行 Push；
3. 当前 Head 与 `expected_remote_head_sha` 不同则返回 `REMOTE_HEAD_CONFLICT`；
4. Delivery Head 不是当前 Head 的 Fast-forward 时返回 `NON_FAST_FORWARD`；
5. 使用精确 `--force-with-lease=<full-ref>:<expected-sha-or-absent>` 和完整 SHA RefSpec Push；
6. Push 超时或响应丢失时进入 `UNKNOWN`，重新查询远端 Head；等于 Delivery Head 时补写唯一成功 Receipt，否则继续对账或进入人工处理。

`--force-with-lease` 用于原子比较远端前置状态，不授权 Non-fast-forward。平台在调用前仍执行 Fast-forward 校验。Branch 名、Head SHA、预期 Head 与 Remote 都来自已确认 PlannedAction 和可信 Repository 事实，不接受 Agent 自由命令。

### Draft PR 幂等

Create Draft PR 绑定：

```text
repository_id
head_owner + head_branch
base_branch
head_sha
title_hash
body_hash
draft=true
```

创建前按 Repository、Open State、Head 和 Base 查询现有 PR。候选的 Draft、Head SHA、Base、标题和正文与 PlannedAction 全部一致时返回既有成功；存在同 Head/Base 但内容或 Commit 不一致时返回 `PULL_REQUEST_CONFLICT`。没有候选时调用 Create Pull Request API 并固定 `draft=true`。响应丢失、超时或 `422` 疑似重复时重新查询并执行相同校验；禁止通过修改 Branch、标题或正文规避冲突创建第二个 PR。

Push Receipt 是 Draft PR 的执行前置。Push 已成功而 PR 失败时只重试 PR 动作。PR 结果保存 GitHub PR ID/Number、规范 URL、Head/Base/Head SHA 和 Provider 更新时间，Webhook 与主动查询只能推进同一 External Result 的对账状态。

### 错误与限流

Provider 错误在基础设施边界归一化：

| GitHub 事实 | CrewScope 分类 |
|---|---|
| `401` | `AUTHENTICATION_REQUIRED` |
| `403` 且无 RateLimit 耗尽事实 | `PERMISSION_DENIED` |
| `403` 且 Remaining 为 0、`429` | `RATE_LIMITED` |
| `404` | `RESOURCE_UNAVAILABLE` |
| `409` | `CONFLICT` |
| `422` | `VALIDATION_FAILED`，随后按动作协议判断是否需要对账 |
| `5xx`、连接失败 | `PROVIDER_UNAVAILABLE` 或 `UNKNOWN`，取决于是否可能已发生写入 |

读取请求可以在 `Retry-After`、RateLimit Reset 和有界退避约束下重试。写入请求不能因网络错误直接重放，必须先进入查询对账。Provider 原始 Body、Authorization、Token、内部 URL、Git stderr 和堆栈不进入公开错误；内部安全日志同样执行 Secret 与 URL 脱敏。

## 实现约束

1. GitHub Provider Adapter 使用可注入 HTTP Client、Clock 和 Credential Handle，不在领域层依赖 GitHub SDK 类型。
2. Repository ID、Connection ID/Version、Grant ID/Version、Binding ID/Version 和外部身份进入 PolicySnapshot 与 ActionDigest。
3. Catalog、Preflight、Push 和 PR 每次调用都带 GitHub API 版本与受控 Accept Header。
4. Redirect 默认拒绝；如 GitHub 官方下载端点需要 Redirect，必须使用独立只读 Client 和域名 Allowlist。
5. Push/PR 写操作只由事务提交后的 Action Worker 执行，Controller、Agent Tool 和前端不能直接调用 Adapter 写方法。
6. HTTP 与 Git 的请求计数、耗时、错误分类和 RateLimit 使用低基数指标；Repository、Branch、Token、URL 和用户标识不作为指标 Label。
7. M5-S04 的测试内 Probe 冻结契约；M5-D11 已用 V21 落地 GitHub Connection Profile、Repository Catalog、RateLimit Snapshot、Connection-scoped 外部唯一键与 TEAM App/USER OAuth 身份矩阵；Adapter 由 M5-I08、M5-I09 和 M5-I10 实现。

## 结果

- 团队共享身份与成员委托身份保持可解释、可授权和可审计；
- GitHub 凭证明文被限制在 Connector Worker 的动作窗口；
- 远端漂移、响应丢失和重复调度不会覆盖代码或创建重复 Draft PR；
- Repository Catalog、权限和 RateLimit 成为显式 Provider 事实；
- M4 受管 Git 与 M5 外部交付之间形成稳定实现边界。

## 验证

1. TEAM App 与 USER OAuth 的 Owner、Credential Subject、外部身份和 Repository 范围不能互换。
2. Loopback GitHub API 的分页、Allowlist、Archived/Fork/权限过滤和 RateLimit Header 可重复解析。
3. Token 不进入 Git argv、环境值、Git Config、日志、异常或 Agent 输入；Session 关闭后临时文件删除且内存缓冲清零。
4. 相同 Branch/Head 重试不执行第二次 Push；响应丢失后通过远端 Head 恢复成功。
5. 远端 Head 与确认前置不一致及 Non-fast-forward 使用不同稳定错误失败。
6. Draft PR 响应丢失后按 Head/Base 查询恢复，重复调用不创建第二个 PR，参数漂移明确冲突。
7. 401、403、404、409、422、429 与 5xx 转为安全错误，原始 Body、Token 与内部 Endpoint 不泄漏。
8. 最小权限集合不包含 Administration、Actions、Secrets、Members 或 Webhooks Write。

验证证据见 [M5-S04 GitHub 连接与 Draft PR 验证记录](../spikes/M5-S04-GitHub连接与Draft-PR验证记录.md)。

## 重新评估条件

- GitHub 提供对 Push 或 Create Pull Request 的原生幂等键；
- GitHub App Installation Token、OAuth 或 API RateLimit 协议发生不兼容变化；
- 平台支持 Fork PR、跨 Organization Repository、GitHub Enterprise Server 或 SSH Push；
- 产品允许自动 Merge、Branch Protection 管理、Release 或部署；
- Credential Worker 迁移到独立安全进程、Vault Agent 或硬件身份代理。
