# M5-S04 GitHub 连接与 Draft PR 验证记录

> 验证对象：GitHub Connection、Repository Catalog、AskPass、受管 Mirror、Push 与 Draft PR API<br>
> CrewScope 模块：`crewscope-infrastructure`<br>
> 验证日期：2026-08-22

## 1. 验证目标

1. 冻结 TEAM-owned GitHub App 与 USER-owned OAuth 的所有权、Credential Subject 和外部身份映射；
2. 验证 Repository Catalog 分页、资源范围、Archived/Fork/权限过滤和 API RateLimit 事实；
3. 证明 Git HTTP 凭证可以通过一次性 AskPass 注入，Token 不进入 argv、环境值、Git Config、日志或 Agent；
4. 冻结受管 Mirror、远端 Head 前置校验、原子 Lease、Push 幂等和响应丢失对账；
5. 冻结 Draft PR 创建前查询、响应丢失恢复、重复调用和参数冲突规则；
6. 明确最小权限、安全错误集与读写重试边界。

本 Spike 使用测试内 Connection、Catalog、Credential、Push 和 Draft PR Probe 验证协议，没有提前创建 M5 生产领域对象、数据库迁移或 GitHub Adapter。

## 2. 验证环境

```text
Loopback GitHub REST Stub
  -> Installation/User Repository Catalog
  -> Link Pagination + X-RateLimit Headers
  -> Create/List Draft Pull Request

本地 Git Fixture
  -> Source Repository
  -> Bare Remote
  -> Managed Bare Mirror
  -> 完整 SHA RefSpec + --force-with-lease

临时 AskPass Session
  -> Owner-only AskPass Script
  -> Owner-only Secret File
  -> 最小 Process Environment
  -> close() 清零与删除
```

没有访问真实 GitHub，没有读取开发机 `.env` 或登录态。固定 Stub 与本地 Git Remote 保证离线、可重复和无外部副作用。

## 3. 连接与资源目录结论

| 连接 | Owner | Credential Subject | 外部身份 | Catalog |
|---|---|---|---|---|
| TEAM-owned GitHub App | TEAM | TEAM/ORGANIZATION | `TEAM_SERVICE_ACCOUNT` | Installation Repositories |
| USER-owned OAuth | USER | PRINCIPAL | `DELEGATED_USER` | Current User Repositories |

两类连接构造时分别校验 Owner、Subject 和外部身份。OAuth 使用 TEAM Subject 的固定反例被拒绝。Catalog 结果继续与 Repository Allowlist 相交；固定数据证明：

- TEAM App 只返回 `crewscope/repository-a`；
- Archived、Fork、Read-only 与 Allowlist 外资源被过滤；
- USER OAuth 返回授权的团队仓库与个人仓库，不获得 App Allowlist 中的其他资源；
- 两个 Catalog 都执行两页请求并读取 GitHub `Link`；
- 每页解析 `Resource=core`、Limit、Remaining、Used 与 Reset。

Repository Catalog 是选择与 Preflight 输入，不是持续授权。生产写操作必须重新校验 Connection、Grant、Binding、Repository、权限、默认分支和保护策略。

## 4. 凭证注入结论

Spike 使用真实 `git credential fill` 触发临时 AskPass：

```text
Git argv
  git -c credential.helper= credential fill

Environment
  GIT_ASKPASS=<temporary-script-path>
  CREWSCOPE_GITHUB_TOKEN_FILE=<temporary-secret-file-path>
  GIT_TERMINAL_PROMPT=0
  GIT_CONFIG_NOSYSTEM=1
  GIT_CONFIG_GLOBAL=/dev/null
```

AskPass Script 只包含固定用户名和 Secret 文件读取逻辑，不包含 Token。环境保存路径而非 Token 值。测试证明：

- Token 不在 argv、环境值、AskPass 内容、本地 Git Config、安全 Audit 摘要和 Agent 可见输入；
- Git Credential 输出只在动作进程内验证，不写日志或异常；
- 安全证明对象的字符串只显示 `REDACTED`；
- Session 关闭后 AskPass 与 Secret 文件不存在，已解析内存缓冲全部清零。

生产实现需要在超时、取消、进程启动失败和 Git 失败路径执行相同清理。

## 5. Push 幂等结论

Push Probe 使用受管 bare Mirror 和本地 bare Remote，动作坐标为 Branch、Delivery Head 与 Expected Remote Head：

```text
query remote head
  -> equals delivery head: ALREADY_PRESENT
  -> differs from expected: REMOTE_HEAD_CONFLICT
  -> delivery is not fast-forward: NON_FAST_FORWARD
  -> push exact SHA with exact --force-with-lease
```

固定故障序列：

1. 第一次 Push 执行一次外部写入；
2. 相同 Branch/Head 重试直接返回既有成功，Push 计数不增加；
3. 第二个 Branch 完成 Push 后模拟响应丢失；
4. 重试查询远端 Head，匹配 Delivery Head 后恢复成功，Push 计数不增加；
5. 用陈旧 `ABSENT` 前置提交不同 Head，返回 `REMOTE_HEAD_CONFLICT`；
6. 用当前远端 Head 作为前置但提交祖先 Commit，返回 `NON_FAST_FORWARD`；
7. 两个失败均不改变远端 Head。

仅“先查再推”存在 TOCTOU 窗口。生产 M5-I09 必须同时使用完整 Ref、完整 SHA 和 `--force-with-lease=<ref>:<expected-or-absent>`，由 Git Remote 原子裁决前置状态。

## 6. Draft PR 幂等结论

Draft PR Probe 使用 GitHub REST 形状：

```text
GET /repos/{owner}/{repo}/pulls?state=open&head={owner}:{branch}&base={base}
POST /repos/{owner}/{repo}/pulls {draft:true, head, base, title, body}
```

Stub 在持久化第一个 PR 后主动断开响应。Client 捕获不确定结果后重新查询，按 Draft、Head Branch、Head SHA、Base、Title 和 Body 精确匹配，返回 `RECONCILED_AFTER_UNKNOWN`。再次执行返回 `ALREADY_PRESENT`，服务端创建计数始终为 1。相同 Head/Base 但标题变化返回 `PULL_REQUEST_CONFLICT`，不创建第二个 PR。

GitHub Create Pull Request 不作为 CrewScope 幂等存储。生产 M5-I10 使用执行前查询、稳定业务唯一坐标、响应丢失后查询和 ActionReceipt 唯一约束共同收敛。

## 7. 权限、错误与 RateLimit

最小权限：

```text
Repository Metadata Read
Contents Read
Contents Write
Pull Requests Write
```

固定断言证明不需要 Administration、Actions、Secrets、Members 或 Webhooks Write。

错误归一化覆盖：

| HTTP | 稳定分类 |
|---|---|
| 401 | `AUTHENTICATION_REQUIRED` |
| 403 | `PERMISSION_DENIED` |
| 403 + Remaining 0 | `RATE_LIMITED` |
| 404 | `RESOURCE_UNAVAILABLE` |
| 409 | `CONFLICT` |
| 422 | `VALIDATION_FAILED` |
| 429 | `RATE_LIMITED` |
| 500/503 | `PROVIDER_UNAVAILABLE` |

每个错误使用包含伪 Secret 与内部 Host 的原始 Body，公开异常中均不可见。写操作发生网络失败时不能按普通 5xx 直接重试，必须根据动作阶段进入 `UNKNOWN/RECONCILING`。

## 8. 自动化验证

测试类：

```text
crewscope-infrastructure/src/test/java/io/crewscope/infrastructure/github/
  GitHubDeliveryM5S04IntegrationTest.java
```

专项命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure \
  -Dtest=GitHubDeliveryM5S04IntegrationTest test
```

结果：

```text
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

五个场景分别覆盖连接/Catalog、AskPass、Push、Draft PR 和权限/错误/RateLimit。

M5-S01 至 M5-S04 联合专项：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-server -am \
  -Dtest=AgentScopeDynamicModelM5S01IntegrationTest,AgentOwnershipM5S02CompatibilityTest,ReviewerSpecialistM5S03IntegrationTest,GitHubDeliveryM5S04IntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：动态模型 `2 / 2`、Agent 所有权兼容 `4 / 4`、Reviewer `5 / 5`、GitHub 交付 `5 / 5`，合计 `16 / 16` 通过。

全仓回归：

```bash
./mvnw --batch-mode --no-transfer-progress test
```

```text
Tests run: 1534, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

回归实际运行 PostgreSQL、Redis、Flyway、Docker Sandbox、本地 Git Process 和 Loopback HTTP 集成测试。根 README 与 `docs` 共 196 份 Markdown 文档链接通过，`git diff --check` 同步通过。

## 9. 后续实现边界

- M5-D11 保存 GitHub Connection 扩展、Repository 绑定、Action 与外部结果坐标；
- M5-I08 实现 Provider Adapter、Connection Grant、Catalog 与 Binding Preflight；
- M5-I09 复用 M4 Git Process 边界，实现 Mirror、AskPass、Fetch/Push、Lease 与 Head 对账；
- M5-I10 实现 Draft PR、已有 PR 发现、Webhook 和主动查询；
- M5-S05 在本协议上冻结 ActionBundle、Dispatch、UNKNOWN 和唯一 Receipt 状态机。

完整长期决策见 [ADR-018](../adr/ADR-018-GitHub连接与Draft-PR交付边界.md)。

## 10. 结论

M5-S04 验证通过。TEAM GitHub App 与 USER OAuth 可以在同一个 GitHub Provider 下保持身份和资源隔离；动作级 AskPass 不需要把 Token 放入 argv、环境值或仓库配置；远端 Head、原子 Lease 与查询对账可以使 Push 收敛；Head/Base/Commit/内容精确匹配可以使 Draft PR 响应丢失与重复调度收敛为唯一结果。M5-S05 可以在这些外部动作事实之上继续冻结耐久 Action 状态机。
