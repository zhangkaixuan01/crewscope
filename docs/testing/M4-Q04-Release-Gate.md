# M4-Q04 Release Gate

> 状态：已通过<br>
> 日期：2026-08-22<br>
> 范围：M4 全量代码、数据库迁移、AgentScope Coding Runtime、安全、恢复、评测、前端、依赖与文档

## 1. 发布决定

M4 Release Gate 已通过。M4 的 15 项出口条件均有自动化或已归档证据，未发现阻断发布的问题。M4 可以关闭，后续开发进入 M5 Agent 模型设置、Review、PlannedAction 与 GitHub Draft PR。

本地门禁在 macOS arm64 上执行，使用 JDK 21 运行并按项目 Java 17 Release 编译，Maven 3.9.11、Node.js 24.13.1、pnpm 10.28.2、Docker 29.6.2 和 Playwright 1.62.1。Sandbox 使用固定 Digest：

```text
maven@sha256:29a1658b1f3078e07c2b17f7b519b45eb47f65d9628e887eac45a8c5c8f939d4
```

## 2. 统一入口

M4 提供一个从干净后端构建到浏览器验收的统一入口：

```bash
nvm use 24
./scripts/m4-release-gate.sh
```

脚本依次执行依赖锁定安装、Markdown 链接、Whitespace、Maven 全量验证、Q01 安全门禁、Q02 故障恢复门禁、Q03 协议门禁、冻结 Judge Pack 独立编译、前端覆盖率、生产构建、Histoire、Playwright 和生产依赖审计。外部真实模型矩阵使用 Q03 已归档的不可变权威报告，不在普通 Release Gate 中重复消费模型额度。

## 3. 出口条件矩阵

| # | 出口条件 | 验收事实 | 结果 |
|---:|---|---|---|
| 1 | TaskExecution 独占 Workspace、Worktree 与分支 | 领域、持久化、Provision、并发和恢复测试进入 Maven 全量门禁 | 通过 |
| 2 | Ref 固化为不可变 Baseline Commit | Repository Preflight、CodingTargetSnapshot 和真实 Git 集成测试通过 | 通过 |
| 3 | 并发与部分创建故障唯一收敛 | Q02 固定故障集验证单活动 Workspace 和普通失败完整回滚 | 通过 |
| 4 | Worktree、Worker、Sandbox、Watcher 可恢复或失败关闭 | 55 / 55 个固定故障与重放样本恢复，恢复率 100% | 通过 |
| 5 | 固定镜像、非 Root、资源限制、默认无网络 | Q01 真实 Docker Sandbox 10 / 10，无跳过 | 通过 |
| 6 | 路径、命令、网络与挂载越权副作用为零 | Q01 固定攻击阻断率 100%，对应副作用计数均为 0 | 通过 |
| 7 | 旧 Lease、Fencing、责任和 Task Token 失败关闭 | Q01/Q02 的旧所有权、撤权、重放和跨 Scope 样本通过 | 通过 |
| 8 | Diff Stream 与 Git 权威事实一致 | RESET/DELTA、Retention Gap、最终 Hash 与恢复测试通过 | 通过 |
| 9 | 成功 Coding Task 证据链闭合 | Q03 成功运行均具备 Baseline、Diff、Command、Test、Acceptance 与 Delivery 事实 | 通过 |
| 10 | 固定任务集成功率至少 70% | DeepSeek 最终矩阵 29 / 36，端到端成功率 80.56% | 通过 |
| 11 | 固定故障恢复率至少 95%，无孤立或重复事实 | Q02 恢复率 100%，孤立资源与重复 Commit/Artifact/TestEvidence 均为 0 | 通过 |
| 12 | Pause/Resume/Cancel/Retry 一致且可审计 | 服务端命令、幂等重放、前端强版本控制与恢复回归通过 | 通过 |
| 13 | Conversation 与 Control/Execution Studio 使用同一事实 | API、Store、深链接、attempt 切换和双入口 E2E 通过 | 通过 |
| 14 | Web 无任意 Shell 与敏感内部状态披露 | Q01 DTO/Artifact 防线、F08 全状态、Axe 与浏览器回归通过 | 通过 |
| 15 | M0–M3、迁移、后端、前端、Docker、评测、依赖和文档全量门禁 | Maven、Judge Pack、Vitest、Build、Histoire、Playwright、Audit、链接和格式全部通过 | 通过 |

## 4. 后端、迁移与装配

`./mvnw --batch-mode --no-transfer-progress clean verify` 的 7 个 Reactor 模块全部成功：

| 模块 | 测试 |
|---|---:|
| `crewscope-domain` | 411 / 411 |
| `crewscope-application` | 319 / 319 |
| `crewscope-agentscope` | 113 / 113 |
| `crewscope-integration` | 1 / 1 |
| `crewscope-infrastructure` | 459 / 459 |
| `crewscope-server` | 214 / 214 |
| 合计 | 1517 / 1517 |

失败、错误和跳过均为 `0`。测试覆盖 M0–M3 回归及 M4 的 Domain、Application、AgentScope、Infrastructure、Spring 装配和 API。Flyway 验证包含空库、V1 升级、非默认 `search_path`、V14 专项升级，以及当前 V14–V19 追加迁移链；V14 专项 `5 / 5`、通用 Flyway 场景 `3 / 3` 通过。

M4-Q03 Spring 模型装配 `7 / 7`、协议单测 `6 / 6`、36 次矩阵聚合集成 `1 / 1` 通过。冻结的 12 个 Judge Pack Java 测试源已在临时物化工程中独立编译成功。

## 5. 安全与故障恢复

- Q01：Java `157 / 157`、Web `37 / 37`，总计 `194 / 194`；
- Q01：真实 Docker Sandbox `10 / 10`，攻击阻断率 `100%`，越界修改、禁止命令、未授权网络、敏感挂载、未授权 Artifact 读取和敏感信息公开泄漏均为 `0`；
- Q02：固定故障与重放样本 `55 / 55`，恢复率 `100%`；
- Q02：Java `97 / 97`、Web `40 / 40`，专项自动化总计 `137 / 137`；
- Q02：Worktree 普通失败回滚率 `100%`，孤立容器、进程、锁及重复 Delivery Commit、Artifact、TestEvidence 均为 `0`。

## 6. Coding Agent 质量基线

权威报告为本地运行证据 `var/evaluation/m4-q03/results/m4-q03-deepseek-v4-flash-20260822-06/aggregate-v2.json`。`var/` 是不进入 Git 的本地运行证据目录，版本库保存评测协议、固定任务集、Judge 和结果说明。

```text
Total Runs              = 36
Successful Runs         = 29
End-to-End Success      = 80.56%
Pass@1                  = 75.00%
Task Success Rate       = 100.00%
Security Compliance     = 100.00%
CrewScope Closure       = PASSED
Quality Gate            = PASSED
```

7 次未成功运行均由路径事实失败关闭。成功运行的编译、测试、验收、路径、安全、结构化输出和人工辅助复核全部通过。CrewScope 自修改样本具备 Workspace、AgentRun、CommandEvidence、TestEvidence、DiffArtifact、CodingResult Hash 和 Delivery Commit 的完整追溯链。

## 7. 前端与依赖

- Vitest：`237 / 237`；
- 覆盖率：Statements `85.12%`、Branches `74.98%`、Functions `85.98%`、Lines `87.87%`；
- Vite/TypeScript 生产构建：通过；
- Histoire：7 个 Story、32 个 Variant，静态构建通过；
- Playwright：双视口、视觉回归和 Axe 共 `126 / 126`；
- pnpm Production High/Critical Audit：未发现已知漏洞；
- CI 继续使用 OSV Scanner 审计 Maven 与 Web 依赖清单，并由聚合 `release-gate` Job 强制 Backend、Frontend、Quality 和两类依赖任务全部成功。

## 8. 文档与仓库卫生

- Markdown：190 个文件进入文档链接检查并通过；
- `git diff --check`：通过；
- 根级 `var/` 已加入忽略规则，真实模型运行、覆盖率和浏览器证据保留在本机且不污染版本库；
- `.env`、Provider Key、宿主路径和内部 Agent 状态不进入发布内容；
- `docs/Dockerfile` 仍是未纳入本阶段的本地文件，不属于 M4 Release Gate 提交范围。

## 9. 非阻断提示

- Mockito 动态加载 Agent 提示未来 JDK 版本将收紧该行为；
- Histoire 报告部分模块同时静态与动态导入，影响 Chunk 拆分提示，不影响功能和构建；
- 固定故障测试覆盖 AgentScope Compaction 降级并输出预期提示；
- 所有提示均未造成测试失败、错误或跳过，后续依赖升级时继续跟踪。

## 10. 结论

统一门禁最终输出 `M4 release gate passed.`。M4-Q04 完成，M4 正式关闭。代码提交与推送在独立 Review 后执行，GitHub Actions 的聚合 Release Gate 作为远端最终确认。
