# M6-Q04 MVP Release Gate

> 任务：`M6-Q04`<br>
> 状态：本机与 Linux amd64 Release Candidate 已完成，发布决策待权威 CI 扫描<br>
> 日期：2026-08-28<br>
> 范围：M0–M6 回归、V1–V30、AgentScope、前端、Docker、Provider、安全、故障、负载、恢复、依赖、文档与 MVP 发布决策

## 1. 决策边界

M6-Q04 使用两条明确轨道：

```bash
./scripts/m6-release-gate.sh local-preflight
./scripts/m6-release-gate.sh release-candidate
```

`local-preflight` 完成开发机能权威判定的确定性回归，不把 macOS/aarch64 Fixture 当作 Linux amd64 Canonical 性能结论。`release-candidate` 强制调用 Q03 受保护轨道，需要新备份空目标恢复、Canonical 完整时间窗口与真实 Lark 固定模板 Receipt。

Q04 只在以下三类证据同时通过后关闭：

1. 本机预检零失败、零跳过；
2. Q03 Canonical Nightly 与受保护 Release Candidate 已正式关闭；
3. GitHub Actions `release-gate` 的 Backend、Frontend、Quality、OSV、Web Audit 与镜像 Trivy 全部成功。

## 2. 本机预检范围

| 领域 | 门禁 |
|---|---|
| 仓库与文档 | tracked/staged/untracked Whitespace、Markdown 链接、Web 敏感字段、M4/M5 评测协议 |
| 后端与迁移 | Maven `clean verify`，覆盖 7 个 Reactor 模块、V1–V30、AgentScope、PostgreSQL/Redis/Git/Docker/Provider |
| M6 强化 | Q01 110 个固定攻击、Q02 121 个固定故障、Q03 Fixture 负载/恢复/完整 MVP E2E |
| 前端 | Vitest Coverage、TypeScript/Vite 生产构建、Histoire、Q03 Playwright Desktop/Narrow/视觉/Axe |
| 部署 | 七服务 Compose 与三组件加密恢复合同、Backend/Web 生产镜像构建 |
| 依赖 | Web 生产依赖 Audit；OSV 与镜像 Trivy 保留为权威 CI 必选任务 |

负载 Fixture 在长时间故障/迁移回归前使用独立 JVM 执行，Evidence 写入 `var/release/m6-q04/`。后续 Maven `clean verify` 不删除该 Evidence，Testcontainers 资源回收抖动也不进入负载窗口。这一顺序不改变 Canonical 门槛，Linux amd64 轨道仍使用冻结的 120 秒 Warmup 与三轮各 600 秒 Measurement。

## 3. 本机验收结果

本轮在 macOS/aarch64、Docker Desktop 8 GiB、Microsoft JDK 21.0.12、Node.js 24.13.1 与 pnpm 11.9.0 上完成以下验证：

| 门禁 | 本机结果 |
|---|---|
| 文档与静态合同 | `299` 个 Markdown 链接通过；`40` 个 Web 生产文件与 `14` 个 Story 敏感字段扫描通过；Team Beta 部署/恢复合同通过 |
| M4/M5 评测协议 | M4 Coding V1、M4 Coding Q03、M5 Reviewer Q03 的协议校验与脚本测试通过 |
| Maven 全量回归 | `2554 / 2554`，零失败、零错误、零跳过，7 个 Reactor 模块全部成功，`clean verify` 耗时约 `14m30s` |
| M6-Q01 | `110 / 110` 固定攻击阻断；Java `173 / 173`、Web `83 / 83` |
| M6-Q02 | `121 / 121` 固定故障收敛；Java `304 / 304`、Web `67 / 67` |
| M6-Q03 Fixture | Java `84 / 84`、Playwright `180 / 180`；Aggregate Evidence Hash `1df1c35f978611917c9d59515b0a6f8853d452b61132c302f68ce8aef71cfff3` |
| M4 Frozen Judge Pack | 全新物化目录中的 `12` 个生产源文件与 `12` 个 Judge 源文件按 Java 17 独立编译通过 |
| Web 全量质量 | Vitest `450 / 450`；Statements `80.78%`、Branches `74.05%`、Functions `81.16%`、Lines `83.18%`；TypeScript/Vite 生产构建通过 |
| Web Story | `14` 个 Story、`104` 个 Variant 构建通过 |
| Web 生产依赖 | `pnpm audit --prod --audit-level=high`：零已知漏洞 |
| 生产镜像 | Backend Linux/amd64 与 Web Linux/arm64 本机缓存构建通过；Backend JVM 启动检查与 Web 非 root 容器 HTTP 烟测通过 |

Fixture Evidence 保存在 `var/release/m6-q04/`。生产镜像使用 Dockerfile 的显式 Base Image 参数和本机离线缓存验证项目编译、分层、非 root 用户、Entrypoint 与静态站点启动。Docker Desktop 的 Registry Resolver 访问 Docker Hub 鉴权端点超时，因此本机没有把远端固定 Digest 解析冒充为成功；默认 Dockerfile 中的 Digest 未修改，Linux amd64 CI 必须使用这些冻结坐标重新构建并执行 Trivy。

## 4. 当前待完成证据

| 证据 | 执行环境 | 当前状态 |
|---|---|---|
| M6-Q04 本机确定性预检 | macOS/aarch64 开发机 | 已完成 |
| Q03 生产负载完整时间窗口 | Linux amd64 Canonical | 已完成，三轮错误率 `0` |
| Q03 新备份独立空目标恢复 | Linux amd64 Canonical 双 Operator Environment | 已完成，RPO `26s`、RTO `71s` |
| Q03 真实 Lark 固定模板 Receipt | 受保护 Release Candidate | 已完成，`SUCCEEDED` |
| Linux amd64 Release Candidate 回归 | 8 vCPU / 16 GB 主机 | 已完成 |
| 固定 Digest 生产镜像、OSV 清单与 Trivy 扫描 | GitHub Actions Linux amd64 | 待推送后执行 |

Release Candidate 绑定 Git Revision `a5020c9eafc21ac09d2d0ad8ced17049c026e4b4`。Q03、Playwright `180 / 180`、非 root Maven `clean verify` 7 个 Reactor 模块、Q01 `110 / 110`、Q02 `121 / 121`、冻结 Judge Pack、Backend/Web 镜像、Vitest `450 / 450`、Coverage、生产构建、14 个 Story/104 个 Variant 与生产依赖 Audit 全部通过。

Canonical 主机最初以 root 执行 Maven 时，真实 Sandbox 测试按设计拒绝 root-owned Worktree。正式续跑使用专用 UID/GID `1001:1001` 的非 root 发布用户并加入 Docker 组；`TaskExecutionSandboxFactoryM4I04DockerIntegrationTest` 随后 `12 / 12`、零跳过通过。发布环境不得通过削弱 Worktree 所有权校验兼容 root Runner。

## 5. 关闭规则

Q03 已正式关闭，开发机与 Canonical Release Candidate 的确定性门禁均已完成。GitHub Actions 的固定 Digest、OSV 与 Trivy 任一未通过前，M6-Q04 保持进行中，不作出最终 MVP Release 决定。
