# M7-Q04 Release Gate

> 任务：`M7-Q04`<br>
> 状态：已完成<br>
> 日期：2026-08-30<br>
> 范围：M0–M7 全量回归、注册 Profile、生产升级恢复、Docker 镜像、安全与依赖审计

## 1. 发布门禁

M7-Q04 使用统一入口聚合既有 M6 产品基线和 M7 开放用户体系：

```bash
./scripts/m7-release-gate.sh local-preflight
./scripts/m7-release-gate.sh release-candidate
```

`local-preflight` 验证本机可重复执行的真实 PostgreSQL、Redis、Spring Boot、生产 Web、Docker 镜像和完整测试。`release-candidate` 继续要求 M6 Canonical Operator 环境、真实备份恢复与飞书证据；GitHub Actions 负责 OSV、生产依赖和两个镜像的权威 Trivy 扫描。

Q04 Profile 子门禁默认复用外层 Release Gate 已构建的 `crewscope-backend:demo` 与 `crewscope-web:demo` 镜像；需要单独执行时显式开启镜像构建：

```bash
CREWSCOPE_Q04_BUILD_IMAGES=true ./scripts/m7-q04-registration-profile-gate.sh
```

禁用构建时若任一 Demo 镜像不存在，子门禁在创建运行栈前失败关闭。

## 2. M7 专项覆盖

- Q01 固定认证攻击集 128 项全部阻断；
- Q02 固定并发、故障和事务收敛样本 72 项全部收敛；
- Q03 在两个独立 BrowserContext 中完成双用户注册、邀请、Personal Agent、TEAM Conversation、Audit、进程重启、Session 过期和恢复；
- Q04 Profile 门禁在同一个真实数据库和 Redis 上依次切换 `OPEN`、`INVITE_ONLY`、`DISABLED`，验证普通注册、邀请注册、既有账号登录和身份连续性；
- 正式 `/login` 与匿名 Session 均不返回 `WWW-Authenticate`，浏览器不再进入 Bootstrap Basic Challenge；
- Team Beta 备份清单和恢复边界统一冻结为 `V26..V32 -> V32`。

## 3. 零跳过与发布依赖

Release Gate 拒绝 JUnit `@Disabled`、Vitest/Playwright `skip`、`todo` 和 `only`；全量 Maven 完成后解析 Surefire/Failsafe XML，要求失败、错误、跳过均为零。CI 最终门禁必须同时取得 Backend、Frontend、Quality、Maven/Web 依赖审计和 Backend/Web 镜像安全扫描成功状态。

## 4. 最终证据

| 门禁 | 结果 |
|---|---|
| Maven Reactor | 6 个模块全部成功；3056 项测试、547 个 Suite，零失败、零错误、零跳过；总耗时 14 分 30 秒 |
| Web Vitest / Coverage | 112 个文件、652 项测试；Statements 80.18%、Branches 73.91%、Functions 82.72%、Lines 83.90% |
| Web Build / Histoire | 生产构建通过；21 个 Story、153 个 Variant |
| Q04 Profile E2E | 真实 PostgreSQL、Redis、Spring Boot 与 Nginx；`OPEN -> INVITE_ONLY -> DISABLED`，`1 / 1 passed`，耗时约 1.9 分钟 |
| Linux amd64 Server RC | 8 核 Linux 主机原生构建 Backend/Web；三 Profile E2E `1 / 1 passed`；V30 备份隔离恢复并迁移至 V32；Operator 登录、API 重启与 HTTPS 转发 Cookie 合同通过 |
| Q03 双用户 E2E | Desktop/Narrow 使用独立 BrowserContext，`2 / 2 passed` |
| 部署与恢复合同 | Team Beta 7 服务合同通过；加密备份、安全归档与 `V26..V32 -> V32` 恢复边界通过 |
| Web 敏感字段 | 78 个生产文件、21 个 Story 通过 |
| 文档 | 343 个 Markdown 文件链接检查通过 |
| 生产依赖 | `pnpm audit --prod --audit-level=high` 未发现已知漏洞 |
| 代码质量 | M7 Release Contract、部署合同、恢复合同与 `git diff HEAD --check` 全部通过 |

Profile E2E 复用了 M7-Q03 已从当前 Dockerfile 和源码构建的本机 Demo 镜像，并记录固定镜像 ID：

```text
crewscope-backend:demo sha256:33faf8ce5017736edbb5281403684a06f26f959414619270b958b0f63b370cd8
crewscope-web:demo     sha256:098238dbaa80a28faef1169cabd73384b5e8d3514031fd8dc9764f66e3523f78
```

本轮尝试重新构建镜像时，Docker BuildKit 对 Docker Hub 固定 Digest 的请求超时，因此不把本轮记录为“重新构建成功”。三 Profile 已在上述真实镜像与正式 Compose 拓扑上完成验证；推送后仍由 GitHub Actions 执行权威 OSV、生产依赖和 Backend/Web Trivy 扫描。

## 5. Profile 连续性结论

1. `OPEN`：Owner 从正式注册页完成注册、Onboarding、Team 创建与邀请创建；
2. `INVITE_ONLY`：普通注册稳定返回 `422 registration_unavailable`，邀请注册成功并加入同一 Team；
3. `DISABLED`：带有效邀请仍返回 `403 registration_unavailable`，既有 Owner 可从正式 `/login` 登录；
4. 三次切换复用同一 PostgreSQL/Redis 数据集，Owner 的 Account、Principal、Team 与成员事实保持连续；
5. 正式登录页无 `WWW-Authenticate`，未触发 Bootstrap Basic Challenge，Axe 零违规；
6. Profile Gate 退出后已清理 `crewscope-m7-q04` Compose Project。

## 6. Linux amd64 Server Release Candidate

服务器 Release Candidate 使用 8 核 Linux x86_64、约 16GB 内存、Docker 29.7.2 与 Docker Compose 5.5.0。当前未提交候选工作树同步到独立目录，排除了 `.env`、Secret、Git 元数据、构建产物和运行数据；构建得到：

```text
crewscope-backend:m7-server-rc sha256:7e8fe90f1927e1eb198e6f1bf4107f1fcd1e1ad95e7f7f371a5c59cf2b1d8299 linux/amd64
crewscope-web:m7-server-rc     sha256:3e7f4f738139ccf79882df8f63e47d45a00b7fae5abc8f3613786e0b7ba915dd linux/amd64
```

服务器专项验证结果：

1. 独立 7 服务栈完成 `OPEN -> INVITE_ONLY -> DISABLED`，Playwright `1 / 1 passed`，耗时约 1.2 分钟；
2. 现有 M6 `release-target` 在只读盘点后生成 1,035,196 字节的 V30 Custom Dump，SHA-256 为 `772814c7a0e556ea7309fc68b4beeeec58f2cc73466f47e934d788426325d56b`；
3. V30 备份恢复到新的临时 PostgreSQL，M7 API 成功执行 V31、V32 两条迁移；原 Organization 及 M0–M6 核心业务计数保持一致，新增 1 个 Operator Account 和 1 个 AccountOrganizationBinding；
4. 使用错误 Organization 名称启动时 Bootstrap Seeder 按设计失败关闭；恢复备份中的不可变 Organization 名称后 API、Worker、Web 全部健康；
5. Operator 从正式 JSON 登录入口返回 200，PlatformRole 为 `OPERATOR`，响应不包含 `WWW-Authenticate`；API 重启后 Redis Session 仍关联同一 Account；
6. 生产配置经回环 TLS 终结器合同传入 `X-Forwarded-Proto: https` 时，Session Cookie 包含 `Secure; HttpOnly; SameSite=Lax`；
7. 两套临时 Compose Project、容器和卷已全部清理，既有 M6 source/target 13 个容器继续健康；保留 M7 RC 镜像、候选源码和权限隔离的升级备份证据。

服务器尚未配置项目域名和公网 CA TLS 终结器，因此本轮验证应用侧 HTTPS 转发与 Secure Cookie 合同，不把公网证书握手列为已完成。推送后仍需 GitHub Actions 完成权威 OSV 与 Backend/Web Trivy 扫描。

M7-Q01 至 M7-Q04 全部完成，M7 Release Gate 本机与 Linux amd64 Server RC 结论均为 `PASS`。
