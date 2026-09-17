# M9-Q02 Release Gate

M9-Q02 是 M9 的功能发布验收。门禁把可在开发机重复执行的合同检查与需要真实环境、真实凭证和真实用户参与的证据分开，避免把模拟结果误称为生产验证。

本门禁**不要求版本 Tag、不要求正式供应链发行**：GHCR、Cosign、SBOM、Provenance 与受保护 Tag 统一标记为 `SUPPLY_CHAIN_RELEASE_DEFERRED`，产品开发尚未结束，本轮不打 Tag。计划中「发行镜像可验证、可部署、可观测」以当前代码在目标环境构建出的镜像为证据，不等待正式发行 Digest。

## 本机门禁

```bash
./scripts/m9-q02-local-gate.sh contracts-only
./scripts/m9-q02-local-gate.sh local-precheck
```

`contracts-only` 执行文档链接、M9-Q01 体验回归、OpenAPI、环境配置合同；`local-precheck` 在此基础上顺序执行 Maven、前端单元覆盖率、生产构建、质量门禁和浏览器 E2E。浏览器测试必须使用独占资源，避免并行容器争用污染结果。

## 本机门禁执行记录（2026-09-17）

`local-precheck` 的每一档都已在本机执行并通过：

| 阶段 | 结果 |
|---|---|
| `contracts-only` 五项 | PASS（Markdown 397、M9-Q01 门禁 `bareTitleAttributes=0`、裸枚举 0 处新增、OpenAPI 217 操作与状态机 16 聚合产物最新、配置合同 16 变量） |
| `./mvnw clean verify` | `BUILD SUCCESS`（3146 用例、0 失败 0 错误 0 跳过，13:25 min） |
| `vitest run --coverage` | 151 文件 / 793 用例通过（Lines 72.53%、Statements 67.96%、Branches 61.36%、Functions 68.69%，地板 65/60/65/70） |
| `vue-tsc --noEmit && vite build` | 生产构建通过 |
| `check:quality` | eslint + stylelint + format:check + `check-web-quality.mjs`（含设计令牌、裸枚举、空状态、分页与禁用态解释门禁）全部通过 |
| `playwright test` | 281 passed / 5 skipped / 0 failed（4.9 分钟） |

本轮（F1/F3/F4/F6 修正与 F10 ⑤⑨ 接线）的复跑记录另记三点：

- OpenAPI 生成产物的 `sourceHash` 随 `TeamSetupReadinessController` 的边界校验一起重算，端点、参数与 schema 未变，操作数仍为 217；`contracts-only` 在重算后通过。
- 视觉基线只有 `setup-center-{desktop,narrow}-chromium-darwin.png` 两张变化（新增「配置健康 · 四项组件」一节：桌面端 4 行、390px 下端内纵向堆叠），差异图与 actual 全页截图逐张人工确认后才 `--update-snapshots`；其余快照在本轮全量运行中逐张比对通过、未被改写。同时给浏览器档的 mock 补上 `configuration-health` fixture，使这一节在 E2E 中以真实内容参与渲染、Axe 与快照，而不是停留在降级提示。
- 跨语言哈希：`sha256Hex`（纯 TS）与 Java `RuntimeContentHash.sha256` 对同一组输入（空串、`abc`、56 字节双块、中文 UTF-8、中英混排、64 字节填充边界、1 MB）逐条比对十六进制全等，向量同时与 `node:crypto` 核对一致。

浏览器一档的**首次合并执行**出现 12 条失败，逐条复核后判定为开发机资源饥饿而非产品回归：12 条全部是 wall-clock 超时（`.app-shell` 不可见、`waitForLoadState` 120 秒未达、点击超时），没有一条是断言内容不符；同一次运行全套耗时 1.4 小时，而此前与之后的单独执行都是 4.6–4.8 分钟。同一份未改动的代码在机器空闲时单独重跑为 `281 passed / 5 skipped / 0 failed`。

由此得到一条比「独占资源」更严的运行前提：**浏览器档必须单独执行，且不得与另一个 Playwright 进程并存**。`playwright.config.ts` 的 `reuseExistingServer: true` 会让先结束的一方拆掉双方共享的 dev server，后一方的用例随即成片失败，错误是 `net::ERR_CONNECTION_REFUSED` 与 `[vite] server connection lost`，表面上与「页面渲染慢」难以区分。

### 后续两轮的复跑记录（2026-09-17）

**F5 轮（行级评论命令键与两列方案）**：三条针对性集成测试在真实容器上通过——`JpaReviewLineCommentIdempotencyIntegrationTest` 3/3、`V39ReviewLineCommentCommandKeyMigrationIntegrationTest` 3/3、`BootstrapOperatorProvisioningM7I07IntegrationTest` 9/9，Flyway 迁移到 `V39`。

**A05 轮（工作台动作可用性接线与投影/命令对账）**的定向证据：

| 档位 | 结果 |
|---|---|
| 应用层定向测试（6 个类） | 通过：可用性投影、投影器、**对账矩阵**、`WorkItemAccessPolicy` 九格一致矩阵、`WorkDeskQueryService`（含每请求一次权限解析的计数门）、`WorkItemCommandService` |
| 工作台数据库级集成测试 | `JdbcWorkDeskRepositoryAdapterM9A05IntegrationTest` 1/1（真实 PostgreSQL + 全量 Flyway，本仓库第一个工作台 DB 级测试） |
| 前端单元 | `vitest run` 151 文件 / 793 用例通过；`vitest run --coverage` 退出 0，Lines 72.59% / Statements 68% / Branches 61.45% / Functions 68.71%（地板 65/60/65/70） |
| 前端质量门禁与构建 | `check:quality`、`vue-tsc --noEmit && vite build` 通过 |
| 浏览器 E2E | `playwright test e2e/m9-workdesk.spec.ts` 2 passed（**单独执行**，不与另一 Playwright 进程并存） |
| 契约生成 | 改过 `WorkDeskController` → 重跑 `generate-openapi-types.mjs`（217 操作，`sourceHash` 重算）后 `check-openapi-drift.mjs` 退出 0 |

两次**失败**的全量构建必须记下来，因为它们不是测试失败：F5 轮的全量 `clean verify` 两次都停在 `crewscope-server` 的编译期——`WorkDeskApplicationConfiguration` 与 `WorkItemApplicationConfiguration` 引用的 `WorkItemTransitionAvailabilityProjector` 当时尚未落地，`crewscope-application` 自身 `BUILD SUCCESS`，所以缺陷只在 reactor 末端暴露（这是仓库在半改状态下被构建的结果，不是产品回归）；其中一次另有 `Surefire is going to kill self fork JVM. The exit has elapsed 30 seconds after System.exit(0)`。**因此「全量 `clean verify` 通过」这条结论在本轮之前并未真正成立过**，它由 A05 轮的收尾运行给出，结果记于本节末。

**全量构建**：`./mvnw --batch-mode --no-transfer-progress clean verify` **`BUILD SUCCESS`，退出码 0**（13:34 min，六个模块全 `SUCCESS`：Domain 688、Application 673、AgentScope Adapter 167、Integration 18、Infrastructure 930、Server 687，合计 **3163 用例、0 失败 0 错误 0 跳过**）。这是本案中**第一次**全量 `clean verify` 通过——F5 轮的两次尝试都中止在 reactor 末端的编译缺陷上（见上），所以「全量构建通过」这条结论此前并未成立，本条是它唯一的证据来源。日志里唯一的 `[ERROR]` 是 `Surefire is going to kill self fork JVM. The exit has elapsed 30 seconds after System.exit(0).`，容器持有型 fork 的已知噪声，不影响结果，也不代表有用例被跳过（`Skipped: 0`）。

### 第二轮收口（W1–W7）的复跑记录（2026-09-17）

本轮补齐 `M9-F05`/`F07`/`F08`/`F09`/`F10`③/`F01` 与 `M9-A05` 的消费侧（动作四个呈现面、首页重建、列表排序与批量、Prism 高亮、配置参数边界、死代码清理），本机门禁逐档复跑如下：

| 档位 | 结果 |
|---|---|
| `./scripts/m9-q02-local-gate.sh contracts-only` | PASS（文档链接 398 篇、M9-Q01 门禁三项计数为 0、裸枚举 0 处新增、OpenAPI 217 操作与状态机 16 聚合产物最新、配置合同 16 变量 / 11 Compose Secrets） |
| `./mvnw --batch-mode --no-transfer-progress clean verify` | `BUILD SUCCESS`，退出码 0，14:44 min；六个模块全 `SUCCESS`，合计 **3178 用例、0 失败 0 错误 0 跳过**（Domain 694、Application 682、AgentScope 167、Integration 18、Infrastructure 930、Server 687）。日志里唯一的 `[ERROR]` 仍是容器持有型 fork 的已知噪声 `Surefire is going to kill self fork JVM…` |
| `vitest run --coverage` | 160 文件 / **924** 用例通过（比上一轮少 1 例：`AgentPresence` 的 spec 随组件一并删除）；Lines 73.15% / Statements 68.68% / Branches 61.98% / Functions 69.22%，地板 65/60/65/70 |
| `pnpm build` + `check-web-bundle-budget.mjs` | 生产构建通过；预算 PASS（最大分片 `index-*.js` 405,794 bytes ≤ 500 KiB、总 JS 1,371,802 bytes ≤ 2 MiB）。Prism 的字节不在入口分片里（`grep -c Prism dist/assets/index-*.js` = 0），15 个语言分片合计 33,511 bytes（gzip 10,466）按需加载 |
| `check:quality` | 通过（vue-tsc + eslint + stylelint + format:check + `check-web-quality.mjs`） |
| `playwright test` | **317 passed / 5 skipped / 0 failed**（5.5 min，**单独执行**，不与第二个 Playwright 进程并存） |

八项 Q01 静态门禁与契约漂移的逐个结果、以及本轮门禁本身的三处变化（`check-config-form-tokens.mjs` 扫描面 7 → 8、`check-openapi-drift.mjs` 守护的生成器由两个增至三个、裸枚举豁免清单 31 → 30 条），记在 [M9-Q01 验证文档](M9-Q01-质量门禁与基线.md) 第 8 节。

真实环境验收仍未开始：本节的每一档都能在单台开发机上重复执行，因此它们**不**构成下面六行的任何一条证据。

以上都是开发机结论，不替代下一节的真实环境验收。

## 真实环境验收记录

| 验收项 | 证据要求 | 状态 |
|---|---|---|
| 空环境 Setup | 全新 PostgreSQL/Redis/生产 Web，完成注册、首 Team、模型配置、仓库导入 | 待执行 |
| 双用户协作 | 两个独立账号完成责任分配、Coding、Review 和权限边界 | 待执行 |
| Draft PR 闭环 | 使用授权 GitHub App 完成受管仓库导入和 Draft PR | 待执行 |
| 首屏理解度 | 3 名新用户在 30 秒内说出当前待办 | 待执行 |
| Diff 评论可发现性 | 至少 2/3 新用户无需提示完成行级 Review 评论 | 待执行 |
| 配置路径 | 3 名用户完成模型/仓库配置，记录耗时、放弃次数和切 Team 草稿恢复 | 待执行 |

真实环境记录必须包含时间、部署版本、测试账号标识（不可写入密码或 Token）、卡点和截图/日志摘要。未执行的项目保持“待执行”，不得以本机 Mock 或快照替代。
