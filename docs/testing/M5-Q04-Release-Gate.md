# M5-Q04 Release Gate

> 状态：已通过<br>
> 日期：2026-08-25<br>
> 范围：M5 全量代码、V20–V26、AgentScope 动态模型与执行 Agent、Review、GitHub Draft PR、Action、前端、安全、恢复、评测、依赖、CI 与文档

## 1. 发布决定

M5 Release Gate 已通过。M5 的 17 项出口条件均有自动化或已归档证据，未发现阻断发布的问题。M5 可以关闭，CrewScope 已形成“成员配置个人/团队执行 Agent -> Coding 交付 -> Reviewer Advisory -> 成员 Gate -> 精确确认 -> GitHub Draft PR”的完整闭环。

本地门禁在 macOS arm64 上执行，使用 JDK 21.0.12 运行并按 Java 17 Release 编译，Maven 3.9.11、Node.js 24.13.1、项目固定 pnpm 11.9.0、Docker 29.6.2 和 Playwright 1.62.1。Sandbox 使用固定 Digest：

```text
maven@sha256:29a1658b1f3078e07c2b17f7b519b45eb47f65d9628e887eac45a8c5c8f939d4
```

## 2. 统一入口

M5 提供一个从仓库卫生检查、干净后端构建到浏览器验收的统一入口：

```bash
nvm use 24
./scripts/m5-release-gate.sh
```

脚本依次执行固定依赖安装、Markdown 链接、Web 敏感字段、tracked/untracked Whitespace、Maven 全量验证、Q01 安全门禁、Q02 故障恢复门禁、Q03 Reviewer 协议门禁、M4 冻结 Judge Pack 独立编译、Vitest Coverage、生产构建、Histoire、Playwright Chromium、双视口 E2E/视觉/Axe 和生产依赖审计。真实模型质量使用 Q03 已归档的不可变权威报告，普通 Release Gate 不重复消费模型额度。

## 3. 出口条件矩阵

| # | 出口条件 | 验收事实 | 结果 |
|---:|---|---|---|
| 1 | 默认 Personal Agent 唯一，成员可创建多个隔离的个人执行 Agent | V20 约束、Agent 管理服务/API、创建向导和生命周期回归通过 | 通过 |
| 2 | Ownership、RuntimeRole、Template、ExecutionScope 分别建模 | 领域不变量、持久化恢复和公开 DTO 均使用独立字段 | 通过 |
| 3 | PERSONAL 可用 Owner USER Connection，TEAM 不隐式使用 USER Connection | Q01 注入攻击全部阻断；Q02 TEAM 回退 USER Connection 次数为 0 | 通过 |
| 4 | 模型调用前完成目录、连接、能力、区域、预算与配额交集校验 | Preflight、动态 Model Factory、停用/限流/撤销和 Fallback 回归通过 | 通过 |
| 5 | Template、Configuration、模型目录和价格追加版本 | V20–V23、Revision/ETag、历史读取和旧 Session 恢复测试通过 | 通过 |
| 6 | TaskExecution 固定完整 Agent、模型、价格与策略事实 | PERSONAL/TEAM 解析、PolicySnapshot v2、Retry 沿用/显式切换回归通过 | 通过 |
| 7 | 成员输入不能扩大 Tool、Sandbox、网络或凭证权限 | Q01 Prompt 编码分区、Tool/Skill 白名单和公开投影攻击集通过 | 通过 |
| 8 | Review 绑定精确 Baseline、Diff、Test、Acceptance 与 Context Hash | Review 持久化、证据校验、Diff 变化失效和重新 Review 回归通过 | 通过 |
| 9 | Reviewer 只生成 Advisory，SELF_REVIEW 不能形成 Gate | Schema、AgentScope Runtime、服务端命令和前端职责分区通过 | 通过 |
| 10 | Gate Decision 只由合格 TeamMember 提交 | ReviewerEligibility、责任撤销、职责分离、强 ETag 和重放测试通过 | 通过 |
| 11 | Gate 或精确确认缺失时 GitHub 写操作为 0 | ActionBundle 规划/确认、旧页/旧 Digest 和授权撤销测试通过 | 通过 |
| 12 | Push/PR 使用动作级凭证、精确 Digest、顺序和唯一 Receipt | AskPass、Lease Push、Draft PR、Confirmation 与 Receipt 回归通过 | 通过 |
| 13 | 重复调度、响应丢失和 Webhook 重放不重复写 | Q02 重复 Push、PR Create、逻辑 Receipt 均为 0 | 通过 |
| 14 | UNKNOWN 有界收敛或进入人工队列 | Query-only Reconcile、递增 Fencing、过期 Claim 和人工终结测试通过 | 通过 |
| 15 | Conversation 与 Control Mode 展示同一交付事实 | Task Delivery Summary、Conversation 卡片、Timeline 和双入口 E2E 通过 | 通过 |
| 16 | Reviewer 质量、安全攻击和故障集达到门槛 | Q01 84/84、Q02 48/48、Q03 质量门禁全部通过 | 通过 |
| 17 | M0–M4、迁移、后端、前端、Docker、GitHub Fixture、依赖和文档全量通过 | Maven、Judge Pack、Vitest、Build、Histoire、Playwright、Audit、链接与格式通过 | 通过 |

## 4. 后端、迁移与装配

`./mvnw --batch-mode --no-transfer-progress clean verify` 的 7 个 Reactor 模块全部成功：

| 模块 | 测试 |
|---|---:|
| `crewscope-domain` | 422 / 422 |
| `crewscope-application` | 422 / 422 |
| `crewscope-agentscope` | 149 / 149 |
| `crewscope-integration` | 1 / 1 |
| `crewscope-infrastructure` | 562 / 562 |
| `crewscope-server` | 306 / 306 |
| **合计** | **1862 / 1862** |

Failures、Errors 和 Skipped 均为 0。构建使用 7 个 Reactor 模块，主全量耗时 15 分钟。测试覆盖 M0–M4 回归与 M5 的 Domain、Application、AgentScope、Infrastructure、Spring 装配和 API。

Flyway 当前包含 V1–V26。M5 的 V20–V26 分别交付模型目录/AgentTemplate/配置、Review/Action/GitHub、模型 Revision 身份、凭证业务版本、Review 持久化投影、GitHub Connection Profile Revision 和 ActionReceipt Claim 坐标。空库、历史版本逐段升级、非默认 Schema、清理重放和 PostgreSQL 17 Testcontainers 矩阵均通过，跳过为 0。

AgentScope Java 2.0.0 的动态 OpenAI-compatible/DeepSeek Model、Formatter、Structured Output、Retry/Fallback、Personal/Team/Specialist Factory、Reviewer Runtime、受信 Template/Tool/Skill 注册和 Spring 条件装配均进入全量门禁。M4 的 12 个冻结 Judge Pack Java 源也已在临时物化工程中独立编译成功。

## 5. 安全、恢复与外部交付

- Q01：固定攻击样本 `84 / 84` 被阻断，阻断率 `100%`；Java 安全回归 `197 / 197`；Web 20 个生产文件和 8 个 Story 的敏感字段命中数为 `0`；
- Q02：固定故障样本 `48 / 48` 收敛，恢复率 `100%`；Java `149 / 149`、Web `39 / 39`，专项总计 `188 / 188`；
- Q02：TEAM 回退 USER Connection、重复 Git Push、重复 Draft PR Create、重复逻辑 Receipt、旧 Fencing 终态写入和未收敛 UNKNOWN 均为 `0`；
- GitHub：Loopback HTTP、真实本地 bare Git、AskPass 清理、Repository Catalog/Grant、受管分支、`force-with-lease`、Draft PR 查询幂等、Webhook HMAC/去重与 Query-only 对账均通过；
- 持久化：真实 PostgreSQL/Testcontainers 验证 Receipt、Dispatch、Event/Outbox 原子提交、唯一逻辑 Receipt、Lease 接管和 V26 Claim 恢复；
- Docker Sandbox：固定镜像、非 Root、资源/网络/挂载边界和 M4 全量安全回归继续通过。

## 6. Reviewer 质量基线

权威报告为本地不可变证据：

```text
var/evaluation/m5-q03/results/
  m5-q03-deepseek-deepseek-v4-flash-20260825T053719Z/aggregate.json
```

```text
Provider / Model         deepseek / deepseek-v4-flash
Model Revision           DeepSeek-V4-Flash-0731
Template                 reviewer@1
Cases                    12（8 缺陷 + 4 正确）
Structured Output        100.00%
Defect Recall            100.00%
Clean Specificity        100.00%
Evidence Validity        100.00%
Category Accuracy         75.00%
Severity Accuracy         87.50%
Gate Decision Violations      0
Calls                    12
Input / Output Tokens    26654 / 12706
Cached Input Tokens      15360
Conservative Cost        USD 0.02195632
Average / P95 Latency    8170.25 / 13105 ms
Quality Gate             PASSED
```

无凭证协议门禁为 Node `4 / 4`、Java `17 / 17`。真实报告固定 Provider、Model Revision、Template、Prompt/协议/集合 Hash、Token、成本、延迟和脱敏 Finding 证据；API Key、原始模型响应与内部推理不进入版本库。

## 7. 前端与依赖

- Vitest：70 个文件、`311 / 311`；
- Coverage：Statements `81.06%`、Branches `71.41%`、Functions `81.21%`、Lines `84.01%`；
- Vue TypeScript 与 Vite 生产构建：通过，1985 个模块完成转换；
- Histoire：8 个 Story、39 个 Variant，静态构建通过；
- Playwright：Desktop/Narrow 双视口、视觉回归、Reduced Motion、键盘焦点和 Axe WCAG 2.2 AA 共 `150 / 150`，耗时 6.2 分钟；
- Web 生产依赖 `pnpm audit --prod --audit-level=high`：未发现已知漏洞；
- CI 使用固定版本 OSV Scanner 审计 Maven 与 Web 依赖清单，聚合 `release-gate` 强制 Backend、Frontend、Quality 和两类依赖任务全部成功。

## 8. 文档、CI 与仓库卫生

- Markdown 链接检查覆盖 242 个文件并通过；
- tracked 与 untracked 文件均进入 Whitespace 检查，`git diff --check` 通过；
- CI Quality 增加 M5 Reviewer 协议资产校验，后端全量包含 Java Reviewer/安全/恢复回归；
- `.env`、Provider Key、GitHub Token、宿主路径、原始外部响应和内部 Agent 状态未进入发布内容；
- `var/` 继续作为本机真实模型、Coverage 和浏览器运行证据目录，不进入 Git。
- Release Gate 完成后按开发环境约定停止 CrewScope PostgreSQL、Redis 和 3 个 Sandbox 容器；容器与数据未删除，可按需重新启动。

## 9. 非阻断提示

- Mockito 当前使用动态加载 Agent，未来 JDK 将收紧该行为；
- AgentScope Fixture 在无 `AGENTS.md` 的临时 Workspace 输出预期提示；
- Histoire 的 RepositorySettings Story 同时静态与动态导入，影响 Chunk 拆分提示，不影响功能和构建；
- macOS Netty DNS Native Resolver 未加载时回退系统解析器，测试 HTTP 均使用明确 Loopback 地址；
- 故障注入会输出连接拒绝、Spring Bean 创建失败、Provider 500/503 和 Fallback 日志，最终 Surefire/Reactor 结果均成功且无跳过。

## 10. 结论

统一门禁最终输出 `M5 release gate passed.`。M5-Q04 完成，M5 的 48 个任务全部关闭。代码提交、推送与 GitHub Actions 远端确认在独立 Review 节点执行。
