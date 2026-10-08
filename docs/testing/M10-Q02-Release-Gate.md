# M10-Q02 真实模型闭环 Release Gate

> 定位：主计划 `docs/plans/M10-Agent智能跃迁与知识闭环.md` :157 的收关门（Q02 = M10 最后一个包）。验收面 = 该行合同全文：「真实 PostgreSQL/Redis、嵌入与生成模型完成**知识/仓库入库→检索→注入→Review→发布 Skill→第二次执行加载 Skill**，另验普通 PostgreSQL 关闭态；产出发布证据」；红线：「注入来源/版本和已发布 Skill 的复用都须验证，不能用『知识或 Skill 任一命中』代替闭环；真实环境、质量对照、成本/延迟和恢复有记录；可选拓扑单独结论」。结果枚举只用：待执行 / 通过 / 失败 / 需授权或环境 / 不适用（说明理由）。
>
> 层级标注原则（与 [Q01 矩阵](M10-Q01-跨包硬化与收口矩阵.md) 一致）：**真实栈 e2e** = 双独立 compose 项目——`crewscope-m10-q02-vector`（端口 18090、RUNTIME_ROOT `var/release/m10-q02/vector`、六开关全开+MEMORY 关）与 `crewscope-m10-q02-plain`（端口 18091、RUNTIME_ROOT `var/release/m10-q02/plain`、全关普通 PostgreSQL），只经 `sh scripts/m10-q02-real-model-gate.sh` 运行（`CREWSCOPE_M10Q02_PHASES` 分段 s0–s8、断点续跑、s2–s7 绝不 reset）；spec 只跑 `--project 'M10 Real Desktop'`（Narrow 孪生会双倍真实模型成本，不入证据面）。**驱动器/judge** = `node scripts/m10-q02/*.mjs`（公共 API 驱动 + gate 侧冻结镜像 maven judge）；**Java** = 仓库根 `./mvnw` reactor（单会话纪律）。
>
> 真实凭据边界（m9b-q02 逐字先例）：DeepSeek key 与 DashScope key 只经 `CREWSCOPE_Q02_DEEPSEEK_API_KEY` / `CREWSCOPE_Q02_DASHSCOPE_API_KEY` 环境变量进入 gate 进程，spec 内只通过 Node 侧 fetch（带浏览器会话 Cookie）提交——不进页面、URL、命令行、trace、截图与任何文件；`S01B_DASHSCOPE_KEY_FILE` 只携带 key 文件**路径**给 s6 的 JUnit 层（测试自行读文件、用毕置零）。gate 永不读取/打印/落盘任何 key 值。缺凭据时对应行保持「需授权或环境」，不降格为 mock 后关闭；Mock/静态检查不能替代真实模型（合同 item 12）。

## 1. 合同场景逐行证据

| 场景（:157 合同拆解） | 验证层级与证据 | 结果 |
| --- | --- | --- |
| 知识入库→检索→注入（含来源与版本逐字段） | 真实栈：`e2e/m10-real/q02-knowledge-loop-real-api.spec.ts` t2（实验室语料 14 条+S01 dataset 已发布条目→publish→rebuilds→job READY→全部 INDEXED，真实 DashScope embedding）；t4（单源/联合 preview 三查全非降级：条目投影 revision==发布版本、chunk fragments 钉死冻结 commit 与索引 generation）；t5（执行注入逐字段断言：INJECTED KNOWLEDGE_ENTRY ref 的 version==发布版本且 sourceId 落在已发布实验室条目集、REPOSITORY_CHUNK ref 的 version==IndexKey.modelRevision——不以「有引用」代替版本对账；另交一条 NOT_APPLICABLE 反馈闭环 I02c） | 通过（终跑 2026-10-08/09：闭环 spec 9/9 绿，26.5m） |
| 仓库入库 | 真实栈：同 spec t3（绑定 preflight 断言 baselineCommit==f053fd1e 冻结基线→repository-builds→job READY→IndexKey{modelKey,modelRevision,chunkPolicyHash,generation} 入坐标；尾部 fire-and-forget enqueue crewscope-java 整仓索引——s5 等待与降级见 §2） | 通过（IndexKey=modelKey text-embedding-v4@revision 1、chunkPolicyHash e6c06cbf…、generation 1）；整仓索引降级见 §2.1 注 |
| 真实执行（第一次）→ Review | 真实栈：同 spec t5（java-username-normalization，m9b-q02 t4 已实证链路：coding 专家 profile+DIRECT flash+codingTarget+CONFIRMATION resume；断言 COMPLETED+diffManifest）；t6（reviewer 模板 v1+TEAM 绑定+advisory 座位先行就绪→create→execute→COMPLETED→findings 入档→成员 B 经邀请流真实加入并记 gate 决策 APPROVED） | 通过（t5 COMPLETED+diff 非空、注入清单逐字段命中；t6 review COMPLETED、决策 APPROVED） |
| 发布 Skill→第二次执行加载已发布 Skill | 真实栈：同 spec t7（创建者本人蒸馏 COMPLETED attempt→DRAFT→publish→断言 effectiveRevision R 与 64-hex contentHash）；t8（配置 append approvedSkillKeys→java-retry-backoff 第二次执行→断言 INJECTED SKILL_INSTRUCTION refs 中存在发布 skill 本身且 sourceId==发布 skillKey、version==R、contentHash 逐字一致——内置 Coding Skill 由 PromptInjectionService 每次组装恒封存（TeamSkillExecutionSource 的内置排除仅作用于 pinned 恢复侧），故内置 ref 合法共存；effective-version 复核 R 不漂移） | 通过（R=1、contentHash d51d3ee2…；第二次执行 COMPLETED 且 SKILL_INSTRUCTION ref@R 命中） |
| 成本/延迟/恢复记录 | 真实栈：同 spec t9（cost/months 三来源 EMBEDDING/EXECUTION/DISTILLATION 非零+quality 结构）；对照实验逐 run token 差分与墙钟延迟见 §2；恢复面=JSONL 断点续跑+单次 infra 重建注记+judge 预热卷两臂共享 | 通过（三来源全非零；恢复面实证：终跑 s3/s4 七次 infra 重试全走单次重建、JSONL 断点续跑贯穿冒烟→终跑） |
| 普通 PostgreSQL 关闭态（另验） | 真实栈：gate s8——plain 栈 18091 重跑既有 `feature-flags-off-real-api.spec.ts` + `cross-team-isolation-real-api.spec.ts`（合同 item 1 既有覆盖即「另验」，不写新 spec） | 通过（11 passed+1 skipped，16.2s；skip=记忆开关用例在该栈形态下的既有条件跳过） |
| 升级象限（Q01 移交④） | 真实栈：gate s7——`flyway_vector_history` 行数 before→`vector build`→带数据六开 `vector up`→行数 ≥before→`q02-upgrade-recheck-real-api.spec.ts`（u1 升级后双源 preview 非降级+fragments 仍钉冻结 commit；u2 已发布 Skill 以坐标文件 pin 的 R+contentHash 重新加载进一次全新真实执行）→A01 mjs 复跑 | 通过（行数不回退；u1 6.8s+u2 3.8m 全绿，u2 SKILL_INSTRUCTION@R 命中；A01 复跑 p50 423.1/p95 1006.7 ms） |
| 质量对照（Q01 移交③） | 真实栈：gate s5——session-bootstrap（cookie 经 `set -a` 透传不回显）→A01 mjs `EXPECT_READY=1`；crewscope-java 整仓索引 READY 时三坐标指向整仓绑定，否则按用户授权降级（2026-10-07）：lab 语料跑 mjs+failureCode 入档+整仓结论记「待执行」；gate s6——`KnowledgeRetrievalQualityGateTest` S01 冻结阈值不降 | 通过（s5 A01 mjs 15 问全 200 非降级，p50 413.6/p95 928.2 ms——lab 语料降级档，整仓结论「待执行」见 §2.1；s6 冻结阈值全达成：knowledge recall@5/@10=1.0、version accuracy=1.0、code file recall@10=1.0、τ=0.55 FP=0.0、p50/p95/p99=17.6/20.9/24.8 ms） |
| 可选拓扑单独结论 | 不适用（B6，沿用 Q01 §3 第 5 条）：拓扑开关属 E01 可选范围未选入主线，M10 主线无拓扑执行面；开关降级语义已在运维手册登记 | 不适用 |

## 2. 对照实验（S01 §4 冻结协议）

### 2.1 环境与配置记录

| 项 | 值 |
| --- | --- |
| Revision（git） | `0806cef` + 工作区（终跑执行时点；其后缺陷修复与工具加固提交为 `fa65365`、`426f07d`——终跑镜像内含这些改动） |
| 栈配置（vector） | 六开关全开（PGVECTOR+KNOWLEDGE_INDEX+INDEX_WORKER+RETRIEVAL+INJECTION+SKILL），MEMORY 关（B1）；两臂=同栈两次 re-up 只切 RETRIEVAL+INJECTION，s2–s7 绝不 reset（升级象限 s7 后对照数据仍在：task 表 80 行、`flyway_vector_history` 4 行） |
| 生成模型 | DeepSeek DIRECT（catalog entry `0198a475-0831-7000-8000-000000000101`@revision 1；坐标文件入档） |
| 嵌入模型 | DashScope text-embedding-v4（IndexKey.modelRevision=1、chunkPolicyHash `e6c06cbf…`、dimension 1024；坐标文件入档） |
| 硬件 | MacBookAir10,1 / Apple M1 / 16 GB RAM（Docker Desktop；双 compose 栈 + judge 冻结镜像同宿主） |
| 语料规模 | 知识：实验室语料 14 条 + S01 dataset 已发布 35 条（publish→INDEXED 全量）；仓库：java-spring-lab @f053fd1e（12 主类+48 行 pom，索引 generation 1）；crewscope-java 整仓：**索引 FAILED（CHUNK_TOO_LARGE）→ 按用户授权降级（见下行注）** |
| 样本矩阵 | 计划 12 任务 × 2 臂 × 3 重复 = 72 次；实际终跑 70 次（每臂 35）——冒烟单任务对照与终跑同用 JSONL 键 `arm:task:rep`，冒烟两条（12:0x 跑在 reset 前的栈上）终跑被 doneKeys 跳过、已移出 sidecar `runs-{arm}-stale.jsonl`，判定口径=70 次纯净集（偏差如实入档，见 §2.4 注）；臂序 off→on 冻结（B4）；Review 全覆盖（每次 COMPLETED 都 create+execute+机械决策，用户裁定 2026-10-07②；实际 70 create/65 走完，5 条 reviewer 输出无 structuredData 失败——产品缺陷 7/8 见 §3，按 S01 冻结口径扣除分母） |
| 对照纯度 | 驱动器每臂起点把 coding profile 的 approvedSkillKeys 重置为 []（闭环 spec t8 的 append 不污染对照）；配置命令七字段白名单+If-Match=当前 revision |

> **整仓索引降级注（用户授权 2026-10-07④）**：s5 轮询 crewscope-java 整仓索引 job 终态为 `FAILED (CHUNK_TOO_LARGE)`——根因在 `KnowledgeIndexWorker`（:329）：任一分片超过 `EmbeddingClient.MAX_INPUT_CHARS`（33 000 字符）即**作业级**硬失败（80/70 行窗口下超长单行或超长 markdown 节必然触发），而非文件级跳过；lab 夹具全小文件从未暴露。处置=授权降级：A01 mjs 三坐标指向 lab 语料（15 问全 200 非降级，p50 413.6/p95 928.2 ms），failureCode 入档，**整仓检索结论记「待执行」**；文件级跳过策略留待后续包（不触 S01 冻结阈值）。

### 2.2 预冻结验收界限（B2，终跑前不可调）

| 界限 | 值 | 依据 |
| --- | --- | --- |
| 延迟增幅上限 | ≤ 1000 ms/任务 | 检索栈开销包络：SQL P99 69ms + embedding 查询延迟，任务级实操上界 |
| Token 增幅上限 | ≤ 7168 tokens/attempt | 注入预算帽：knowledge 3072 + chunk 4096 |
| 正确率 | on 臂 judge 通过率不低于 off 臂 | 不以「有引用」证明更准确 |
| Review 一次通过率 | on 臂不低于 off 臂（分母=实际进入 review 的运行） | 同上 |

正确性双口径（B3）：judge 为主口径（冻结镜像 maven `-Dtest=<X>JudgeTest`，PATH_VIOLATION 语义 m4 冻结），产品口径（COMPLETED+TestEvidence+diffManifest）并列报告。

### 2.3 命令与脱敏证据

```sh
# 终跑入口（key 只在 shell 环境，不落盘；phases 可分段）
CREWSCOPE_Q02_DEEPSEEK_API_KEY=… CREWSCOPE_Q02_DASHSCOPE_API_KEY=… \
S01B_DASHSCOPE_KEY_FILE=/path/to/key-file \
sh scripts/m10-q02-real-model-gate.sh

# 冒烟（单任务对照）
CREWSCOPE_M10Q02_PHASES=s0,s1,s2,s3,s4 \
CREWSCOPE_M10Q02_TASKS=java-username-normalization CREWSCOPE_M10Q02_REPS=1 \
  sh scripts/m10-q02-real-model-gate.sh
```

| 证据件 | 位置 | 结果 |
| --- | --- | --- |
| 逐 run JSONL（断点续跑键 arm:task:rep） | `var/release/m10-q02/reports/runs-{off,on}.jsonl` | 70 条终跑记录（另 2 条冒烟残留移 `runs-{arm}-stale.jsonl`）；status/Review 经 `--backfill-reviews` 回填 |
| 对照汇总与冻结判定行（fatal 前置+B2 四界限） | `var/release/m10-q02/reports/comparison-summary.{md,json}` | 判定通过（五行全绿，见 §2.4） |
| 逐 run patch+judge 工作区与日志 | `var/release/m10-q02/reports/{patches,judge}/` | 70 patch+judge 工作区留档 |
| 闭环坐标文件（账号为一次性栈账号；不含任何 key） | `var/release/m10-q02/loop-coordinates.json` | 产出（generatedAt 2026-10-08T13:44:35Z） |
| A01 mjs 输出（含 latency p50/p95/p99） | gate 终跑日志（摘录入档） | s5（lab 降级档）p50 413.6/p95 928.2/p99 928.2 ms；s7 复跑 p50 423.1/p95 1006.7/p99 1006.7 ms，各 15 问全 200 非降级 |
| 观测脱敏证据（api 日志/DB 行计数，无 key 无凭据） | 摘录入档 | task 表 80 行（闭环 2+终跑对照 70+infra 重试 7+升级复验 1）、`flyway_vector_history` 4 行（升级前后不减）、cost 三来源非零（t9 断言） |

### 2.4 结果

| 指标 | 臂-off | 臂-on | 判定 |
| --- | --- | --- | --- |
| 正确率（judge 主口径） | 80.0%（28/35；分布 PASSED 28/NO_PATCH 1/JUDGE_FAILED 6） | 94.3%（33/35；PASSED 33/JUDGE_FAILED 2） | 通过（on≥off，+14.3pp） |
| 正确率（产品口径并列） | 94.3%（33/35） | 100.0%（35/35） | — |
| Review 一次通过率（进入数） | 30 次进入 / 100.0% | 34 次进入 / 100.0% | 通过（on≥off；分母=实际进入 review 的运行，S01 冻结口径） |
| Token in+out 均值/运行 | 232 865 | 196 876 | 通过（差 −35 989 ≤ 7168 帽；on 反而更省） |
| 延迟均值/P95 (ms) | 460 646 / 1 179 584 | 196 544 / 261 559 | 通过（均值差 −264 102 ≤ 1000 ms 帽；off 臂长尾见注②） |
| 注入引用均值/运行（off 臂应为 0） | 0.0 | 9.0 | —（on 臂 35/35 全部有注入，均值 9 条/运行） |

**冻结验收判定行（B2，五条全过，`report.mjs` 重算 2026-10-09）**：✅ 两臂零 fatal（无效样本）｜✅ judge 正确率不退化（on=0.943 ≥ off=0.800）｜✅ Review 一次通过率不退化（on=1.000 ≥ off=1.000）｜✅ 延迟增幅 ≤1000ms/任务（−264 102 ms）｜✅ Token 增幅 ≤7168/运行（−35 989）——**判定：通过**。

> 注①（Review 进入数 30/34 的由来）：70 条 COMPLETED 全部 create 了 review，65 条 execute+决策走完；5 条（off 4/on 1）reviewer 模型返回无 structuredData→500、且失败后重试确定性撞回执-事件锚定唯一索引（产品缺陷 7/8，见 §3）——按 S01 冻结口径「分母=实际进入 review 的运行」如实扣除，未重烧两臂。
> 注②（off 臂延迟长尾）：off 臂 P95≈19.7 分钟由少数超长 run 拉高（infra 重试 off 2/on 5 已单列），on 臂 P95≈4.4 分钟；均值差 −264 秒远优于 ≤1s/任务的上限，注入开销未体现为任务级延迟劣化。

## 3. 真实栈抓到的产品缺陷（Q02 存在的直接证据）

| # | 模块 | 根因 | 修复与回归 | 结果 |
| --- | --- | --- | --- | --- |
| 1 | crewscope-application（agent 配置预检） | 冒烟 t7：首次蒸馏恒 403 `policy_denied`/`AGENT_UNAVAILABLE`。链路=「SkillDistillerProvisioningService.ensureConfiguration → AgentConfigurationApplicationService.append → preflightCandidate」，而 preflightCandidate 对 DISABLED profile 的 pending-ACTIVE 虚拟激活只豁免 TeamObserverTemplate；skill-distiller 按 createDefault 供给为 DISABLED（先配置后激活），append 预检必然撞 `ResolvedAgentExecutionConfiguration` 的 ACTIVE 硬门，整个 prepare 事务回滚（活栈实证：模板行已提交 ACTIVE、profile 行不存在）——任何新团队首次蒸馏不可能成功。KnowledgeDistiller 同根（逐字同构，从未在真实栈触发）。 | `preflightCandidate` 豁免抽为 `lazilyProvisionedBuiltin()`，覆盖 TeamObserver+KnowledgeDistiller+SkillDistiller 三内置；同补 `AgentManagementApplicationService` create/lifecycle 两路由的 skill-distiller 守卫（镜像 knowledge-distiller 先例：只许 readiness 供给，堵公共 API 旁路）。回归=`AgentConfigurationApplicationServiceDistillerTest`（两 distiller DISABLED profile 首次 append 预检见 pending-ACTIVE、durable 保持 DISABLED）。 | 通过（终跑闭环 spec 9/9：t7 首次蒸馏即成功→publish→t8 二次执行加载 Skill@R 全绿） |
| 2 | crewscope-application + crewscope-domain + crewscope-infrastructure（任务执行器准入 × A03b 技能面 × 运行时会话类型门 × 会话 shape CHECK） | 冒烟 t5 逐层暴露四处同根接缝（仅真实闭环可暴露，A03b 测试全程 mock facts）：技能加载/manifest 封存读**执行器** pinned 配置的 approvedSkillKeys（`DefaultTeamSkillExecutionSource.resolveForPinnedExecution`），而保存侧天花板扩展只对 `coding` 模板 profile 放行非空技能键（活栈模板行实证：personal-assistant/team-coordinator 天花板为空集）；但执行器准入 `isTaskOrchestrator`（M3 时代）只认 PERSONAL/TEAM——没有任何 profile 能同时满足三者，A03b「第二次执行加载已发布 Skill」经公共 API 不可达。准入放开后第三处接缝立即现形：任务创建过 422，但 worker `DurableTaskWorkerExecutionFactory.prepare` 恒以 TASK purpose 建 `TaskAgentRuntimeSession`，而 `profileAndPrincipalTypesMatch` 的 TASK case 同样只认 PERSONAL/TEAM——SPECIALIST 执行器在 prepare 一秒内抛 `DomainValidationException`，事务回滚（活栈实证：agent_runtime_session/agent_run 0 行），执行停滞 RECOVERING（claim SQL 只扫 READY，恢复须 worker 重启 reconcile——停滞本身为设计语义，非缺陷）。域层放行后第四处现形：`agent_runtime_session` 的 CHECK 约束 `ck_agent_runtime_session_shape`（V17 建、V51 重建）TASK 分支同样硬编码两对身份，插入报 `DataIntegrityViolationException`（Postgres 日志实证约束名）——域测试看不到 DB CHECK，同缝两次漏过。 | ① `isTaskOrchestrator` 放宽为按 profile 实判：PERSONAL/TEAM 原样，SPECIALIST 仅当模板为 `coding`（A03b 注释本意「the Coding template's execution chain」；legacy SPECIALIST 映射即 coding@1）；三处调用点+错误文案同步。② `profileAndPrincipalTypesMatch` TASK case 增加 SPECIALIST/SPECIALIST_AGENT 对（模板级准入留在应用层，域层保持类型对齐不变量）。③ migration `V66__coding_specialist_task_session.sql` DROP+ADD 重建 shape CHECK，TASK 分支加 SPECIALIST 身份对（V51 先例模式；升级象限 s7 的 vector_history 前向迁移同链验证）。回归=`AgentTaskCreationServiceM3A01Test`（coding 专家准入新用例+非 coding 专家仍拒）+`TaskAgentRuntimeSessionTest.allowsTheCodingSpecialistIdentityToOrchestrateTheWholeTask`+`V66CodingSpecialistTaskSessionMigrationIntegrationTest`（TASK+SPECIALIST 可插入、错配身份对仍 23514）。 | 通过（终跑 t5 COMPLETED+diff 非空、t8 SKILL_INSTRUCTION@R 命中；V66 随 s7 升级象限同链验证） |
| 3 | crewscope-application（蒸馏 usage 事实幂等键） | 冒烟 t7：蒸馏模型调用成功（DISTILLATION 1332/2279 tokens 已入账），但 HTTP 500 `DuplicateKeyException`。`SkillDistillationService.recordUsageFacts` 对 Provider 返回的**每个 attempt** 各 append 一条 `MODEL_USAGE_FACT_RECORDED`，而事件幂等键全用同一条蒸馏命令的 Idempotency-Key——事件存储的 `ux_domain_event_idempotency`（org, key）唯一约束在第二条 attempt 事件上必然拒绝（活栈实证：attempt=1 事件已提交，同键第二插 23505；键值=命令键非 callId）。Provider 重试（attempts≥2）是常态，且缺陷 1 让首次蒸馏从未到达记账段，单测的 fixedAttribution 恒单 attempt——从未暴露。KnowledgeDistillationService 逐字同构同 bug。 | 两服务 usage 事件幂等键派生为 `命令键 + "#usage-" + attempt`（`appendExecutorAssigned` 的 `#` 后缀先例：复合命令合法多事件，`#` 不能出现在客户端命令键）。回归=`SkillDistillationServiceTest.multiAttemptUsageFactsCarryDistinctEventIdempotencyKeys` + Knowledge 同名用例（双 attempt attribution→两条事件键各带 #usage-1/#usage-2）。 | 通过（终跑 t7 蒸馏多 attempt usage 事实全入账，零 500 复发） |
| 4 | crewscope-infrastructure（FINALIZING 恢复死锁） | 冒烟 t5：执行本体成功（TEST_EVIDENCE_PUBLISHED→workspace FINALIZING(SUCCEEDED)→archive ref 已建→worktree/branch 清理完成），但收尾 `WorkspaceDiffFinalizer` 入口的 managed-repository resolve 抛瞬时 `RepositoryPreflightException`（同线程毫秒前 archive 自身的同一 resolve 刚通过）→ RECOVERING。重启恢复轮暴露第二层确定性死锁：M4-I10 恢复链把 FINALIZING crash boundary 重建为可写执行面（recoverFinalizing 恢复 worktree→specialist 续跑），而 `JdbcWorkspaceWriteBudgetStore` 的 `requireCurrentActiveWorkspace` SQL 硬编码 `status='ACTIVE'`——恢复轮工具会话的 budget initialize 必然 `WorkspaceWriteBudgetContextException`（活栈实证：01:35:31 RepositoryPreflightException→重启→01:44:52 WorkspaceWriteBudgetContextException，同一执行两次 RECOVERING）。此后任何收尾瞬时故障都成永久 RECOVERING。 | budget 生命周期门放宽为 `status IN ('ACTIVE','FINALIZING')`（方法更名 requireCurrentExecutionSurface）：M4-I10 的 FINALIZING 恢复本就以实测下界重入记账（initialize 的 lower-bound 参数即为此设计），环境/runtime/worker/lease/fencing/fingerprint 六项 ownership 检查逐字保留——stale 面（RECOVERING）依旧拒绝。InMemory 桩无此校验（注释声明 Jdbc 独有）无需改。回归=`M4D09CodingPersistenceIntegrationTest.admitsWriteBudgetOnAFinalizingWorkspaceRecoveryAndStillRejectsStaleSurfaces`（FINALIZING 同 lease initialize+reserve 过、beginRecovery 后仍拒）。 | 通过（终跑 72 次对照执行+闭环 2 次+复验 1 次收尾零 FINALIZING 停滞；s7 升级重建后再证） |
| 7 | crewscope-agentscope（reviewer 结构化输出无兜底） | 终跑对照 Review 补跑 70 次中 4 次（off 3+on 1，≈6%）：reviewer 模型真跑 58–176 s 后返回**无 structuredData**，`ReviewerSpecialistRuntime.decode`（:102）裸抛 IllegalArgumentException→500；`sanitizeModelFailure` 对 IAE 原样透传（:118–120），无 bounded 重试/降级语义——DeepSeek 结构化输出偶发不合规是常态概率事件（冒烟与主线 t6 从未触发，样本量不够）。review 停在 IN_PROGRESS。 | 入档不修（本包处置裁定）：不阻断闭环主线（t6 review 全绿）与对照判定（65/70 有效，分母=「实际进入 review 的运行」S01 冻结原文）。修复方向（留后续包）：decode 对无 structuredData 做 bounded 重试（重渲染 schema 追问一次）或落显式 FAILED 终态带失败码。 | 通过（登记：4/70 触发，全部如实入档 review-unavailable，判定不受影响） |
| 8 | crewscope-application（review execute 失败后不可重试） | 缺陷 7 任一失败后重试 execute 必 500（确定性）：第一次 execute 的 accept 事务已把回执锚定确定性 started 事件（`DurableReviewEventPublisher.stableId`），状态 IN_PROGRESS 后重试走 `ReviewerExecutionApplicationService.stableStartedEventId`（:232）派生**同一**事件 id——新回执锚定同 id 撞 V63 的 `ux_command_receipt_domain_event`（`command_receipt (organization_id, domain_event_id)` 部分唯一索引=一事件一回执），`DuplicateKeyException` 21–98ms 即复现（DB 实证：5 条 review 全卡 IN_PROGRESS version=1、findings=0、decisions=0；Postgres 日志约束名逐条对上）。与缺陷 3 同族：确定性幂等键与事件-回执一对一锚定的设计张力，仅真实失败+真实重试可暴露。 | 同缺陷 7 入档不修。修复方向（留后续包）：重试回执按事件 id 重放首次锚定，或为 IN_PROGRESS 恢复引入显式恢复事件类型（不与 started 事件共锚）。 | 通过（登记：5/5 重试全部确定性复现，根因链事件派生源逐字对上） |

### 3.1 测试与 harness 缺陷（非产品代码，同为「只有真跑才能暴露」的直接证据）

> 编号跨 §3/§3.1 连续：5、6 为 harness 侧（下表）；7、8 为 Review 补跑阶段暴露的产品侧缺陷（§3 表尾）。

| # | 层 | 根因与修复 | 结果 |
| --- | --- | --- | --- |
| 5 | S01 质量门 JUnit（`KnowledgeRetrievalQualityGateTest`） | 该测试自 A01 交付起**从未真实执行**（无 key 时 `@EnabledIfEnvironmentVariable` 恒 skip，S01 冻结数字来自原型脚本），终跑 s6 首跑连破三层：①embed cache 写盘在批循环外——中途任一批交付失败，已付费批次全丢、重跑从头烧全量语料（终跑 3/4 的 TIMEOUT 即此形态；已改每批落盘，773 条重跑全缓存命中）；②两隔离 team 共享 org 的 `seedTenant` 无条件双插（先撞 org 主键、幂等化后再撞 `uk_team_organization_name`——ON CONFLICT (id) 救不了业务唯一键；已改共享行 ON CONFLICT+team 名带 id 后缀）；③knowledge 召回断言 `UUID.equals(KnowledgeEntryId)` 错型恒 false——期望条目排 top-1 也判 miss、recall 全 0（`equals(Object)` 静态类型不拦；已改比较 `.value()`，miss 输出补 top-3 诊断）。 | 通过（修复后冻结阈值全达成，见 §1 质量对照行） |
| 6 | 对照驱动器（`run-comparison.mjs` 及配套） | 终跑暴露四件：①`awaitTerminal` 返回 spread 后字段是 `status`，写成 `attempt.settled` → 全部 72 条 status=null、Review 全跳过（修复+`--backfill-reviews` 从活 API 回填 verdict 并补跑 Review，避免重烧整夜对照）；②对照 carrier 工作项只挂了 gate 座位没挂 advisory reviewer agent——review create 恒 422（回填时暴露；backfill 现按 carrier 幂等补挂）；③冒烟单任务对照与终跑同用 JSONL 键 `arm:task:rep`——冒烟两条跑在 reset 前的栈上，终跑被 doneKeys 跳过成「幽灵样本」且污染 report 分母（两条移 sidecar `runs-{arm}-stale.jsonl`，判定口径=70 次纯净集）；④report 的「不退化」判定缺 fatal 前置——全 fatal 两臂 0 vs 0 会假阳性通过（已加「no fatal (invalid) runs」前置判定行）。 | 通过（70 条全 COMPLETED 回填、Review 65/70 走完（5 条扣产品缺陷 7/8，见 §3），判定行全过） |

## 4. Q01 移交项关闭表

| Q01 移交 | 本包动作 | 结果 |
| --- | --- | --- |
| §3 第 3 条：A01 mjs 评测 gate 对运行栈真实执行（与 q01/S10 同批复跑） | gate s5：session-bootstrap→`EXPECT_READY=1 node scripts/m10-a01/retrieval-quality-gate.mjs`（三坐标优先整仓，授权降级时 lab 语料+整仓结论「待执行」） | 通过（15 问全 200 非降级，p50 413.6/p95 928.2 ms——lab 语料降级档；整仓检索结论「待执行」见 §2.1 注）；Q01 矩阵 §3 第 3 条已回填补证结论 |
| §3 第 4 条：完整双镜像原地升级 gate（q01/S18/S21 同批） | gate s7：rows before→build→带数据六开 up→rows≥before→upgrade-recheck spec→A01 mjs 复跑 | 通过（`flyway_vector_history` 行数不回退 4 行；u1 6.8s 双源非降级+u2 3.8m SKILL_INSTRUCTION@R 命中；A01 mjs 复跑 p50 423.1/p95 1006.7 ms）；Q01 矩阵 §3 第 4 条已回填完整档结论 |
| q01/S10 行（A01 mjs 真实执行） | 回填 Q01 矩阵该行复跑结果 | 通过（矩阵该行已改「通过」：s6 冻结阈值全达成+三层测试缺陷修复链——矩阵 §3 第 3 条互指） |
| q01/S18、q01/S21 行（升级轻量档→完整档） | 回填 Q01 矩阵两行「完整档随 Q02」栏位 | 通过（两行后续动作列已关闭：完整档随 gate s7 执行，行内注明结论与本档 §1 升级象限行互指） |

## 5. 复跑入口

```sh
# 全量（s0–s8，约 9–13h；对照 72 次执行占大头；无凭据时闭环 spec 按原因 skip）
CREWSCOPE_Q02_DEEPSEEK_API_KEY=… CREWSCOPE_Q02_DASHSCOPE_API_KEY=… \
S01B_DASHSCOPE_KEY_FILE=/path/to/key-file \
sh scripts/m10-q02-real-model-gate.sh

# 分段/断点续跑（驱动器 JSONL 与各相位幂等；KEEP_STACKS=1 保留栈供事后检查）
CREWSCOPE_M10Q02_PHASES=s3,s4 sh scripts/m10-q02-real-model-gate.sh
CREWSCOPE_M10Q02_KEEP_STACKS=1 CREWSCOPE_M10Q02_PHASES=s5 sh scripts/m10-q02-real-model-gate.sh

# 对照报告重算（不改栈）
node scripts/m10-q02/report.mjs
```

## 6. 书面裁定（随本包入档）

1. **B1 memory 注入排除**：Q02 合同字面不含 memory 注入；MEMORY_PREFERENCE 留待模板开放 KNOWLEDGE_SCOPE 槽（I02a/b Java 测试+F01c 真实栈三态/清除已覆盖）；全 gate MEMORY off。
2. **B2「S01b 基线记录值」预冻结解读**：延迟增幅上限=检索栈开销包络（SQL P99 69ms+embedding 查询延迟，任务级实操上界 ≤1s/任务）；Token 增幅上限=注入预算帽（knowledge 3072+chunk 4096/attempt，manifest BudgetResponse 实测即上界证据）；不以「有引用」证明更准确。
3. **B3 正确性双口径**：judge 主（断言结构性结果），产品口径（COMPLETED+TestEvidence+diffManifest）并列。
4. **B4 臂序 off→on 冻结**（同栈连续不交错）。
5. **B5 crewscope-java 作整仓索引语料不作对照任务集**：任务集=m4 冻结 12 项（用户裁定 2026-10-07④：整仓索引失败授权降级——lab 语料跑 mjs+failureCode 入档+整仓结论「待执行」，不触 JUnit 冻结阈值）。
6. **B6 可选拓扑不适用**：沿用 Q01 §3 第 5 条。
7. **B7 自建定向测试的协议处理**（冒烟实证 2026-10-07）：执行器完成合同硬性要求测试证据成功（`CodingSpecialistStepRuntime` 无成功测试证据即进入修复轮，耗尽即 `TEST_REPAIR_BUDGET_EXHAUSTED`），而 m4 冻结夹具**不含任何测试**且任务 allowedPaths 只含交付主类——冒烟中 agent 编译成功、三轮 `mvn test -Dtest=<不存在的测试>` 全部 `NO_TESTS_EXECUTED` 后预算耗尽判 FAILED。m4 原协议中 JudgeTest 由 harness 在验收前打入，agent 从不自跑测试。裁定：①任务 allowedPaths 追加 `src/test/java`（目录前缀）；②验收措辞明确要求自建针对性 JUnit 单元测试并只跑该测试；③judge 主口径在 apply 前剥离 patch 中 `src/test/java/**` 文件段（`strippedTestFiles` 计数入 run 记录），隐藏 JudgeTest 打入后才权威——判据与 m4 冻结语义对主代码路径保持逐字一致。
