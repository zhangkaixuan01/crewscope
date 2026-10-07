# M10-Q02 真实模型闭环 Release Gate

> 定位：主计划 `docs/plans/M10-Agent智能跃迁与知识闭环.md` :157 的收关门（Q02 = M10 最后一个包）。验收面 = 该行合同全文：「真实 PostgreSQL/Redis、嵌入与生成模型完成**知识/仓库入库→检索→注入→Review→发布 Skill→第二次执行加载 Skill**，另验普通 PostgreSQL 关闭态；产出发布证据」；红线：「注入来源/版本和已发布 Skill 的复用都须验证，不能用『知识或 Skill 任一命中』代替闭环；真实环境、质量对照、成本/延迟和恢复有记录；可选拓扑单独结论」。结果枚举只用：待执行 / 通过 / 失败 / 需授权或环境 / 不适用（说明理由）。
>
> 层级标注原则（与 [Q01 矩阵](M10-Q01-跨包硬化与收口矩阵.md) 一致）：**真实栈 e2e** = 双独立 compose 项目——`crewscope-m10-q02-vector`（端口 18090、RUNTIME_ROOT `var/release/m10-q02/vector`、六开关全开+MEMORY 关）与 `crewscope-m10-q02-plain`（端口 18091、RUNTIME_ROOT `var/release/m10-q02/plain`、全关普通 PostgreSQL），只经 `sh scripts/m10-q02-real-model-gate.sh` 运行（`CREWSCOPE_M10Q02_PHASES` 分段 s0–s8、断点续跑、s2–s7 绝不 reset）；spec 只跑 `--project 'M10 Real Desktop'`（Narrow 孪生会双倍真实模型成本，不入证据面）。**驱动器/judge** = `node scripts/m10-q02/*.mjs`（公共 API 驱动 + gate 侧冻结镜像 maven judge）；**Java** = 仓库根 `./mvnw` reactor（单会话纪律）。
>
> 真实凭据边界（m9b-q02 逐字先例）：DeepSeek key 与 DashScope key 只经 `CREWSCOPE_Q02_DEEPSEEK_API_KEY` / `CREWSCOPE_Q02_DASHSCOPE_API_KEY` 环境变量进入 gate 进程，spec 内只通过 Node 侧 fetch（带浏览器会话 Cookie）提交——不进页面、URL、命令行、trace、截图与任何文件；`S01B_DASHSCOPE_KEY_FILE` 只携带 key 文件**路径**给 s6 的 JUnit 层（测试自行读文件、用毕置零）。gate 永不读取/打印/落盘任何 key 值。缺凭据时对应行保持「需授权或环境」，不降格为 mock 后关闭；Mock/静态检查不能替代真实模型（合同 item 12）。

## 1. 合同场景逐行证据

| 场景（:157 合同拆解） | 验证层级与证据 | 结果 |
| --- | --- | --- |
| 知识入库→检索→注入（含来源与版本逐字段） | 真实栈：`e2e/m10-real/q02-knowledge-loop-real-api.spec.ts` t2（实验室语料 14 条+S01 dataset 已发布条目→publish→rebuilds→job SUCCEEDED→全部 INDEXED，真实 DashScope embedding）；t4（单源/联合 preview 三查全非降级：条目投影 revision==发布版本、chunk fragments 钉死冻结 commit 与索引 generation）；t5（执行注入逐字段断言：INJECTED KNOWLEDGE_ENTRY ref 的 version==发布版本且 sourceId 落在已发布实验室条目集、REPOSITORY_CHUNK ref 的 version==IndexKey.modelRevision——不以「有引用」代替版本对账；另交一条 NOT_APPLICABLE 反馈闭环 I02c） | 待执行 |
| 仓库入库 | 真实栈：同 spec t3（绑定 preflight 断言 baselineCommit==f053fd1e 冻结基线→repository-builds→job SUCCEEDED→IndexKey{modelKey,modelRevision,chunkPolicyHash,generation} 入坐标；尾部 fire-and-forget enqueue crewscope-java 整仓索引——s5 等待与降级见 §2） | 待执行 |
| 真实执行（第一次）→ Review | 真实栈：同 spec t5（java-username-normalization，m9b-q02 t4 已实证链路：PERSONAL profile+DIRECT flash+codingTarget+CONFIRMATION resume+settle 1500s；断言 COMPLETED+diffManifest）；t6（reviewer 模板 v1+TEAM 绑定+advisory 座位先行就绪→create→execute→COMPLETED→findings 入档→成员 B 经邀请流真实加入并记 gate 决策 APPROVED） | 待执行 |
| 发布 Skill→第二次执行加载已发布 Skill | 真实栈：同 spec t7（创建者本人蒸馏 COMPLETED attempt→DRAFT→publish→断言 effectiveRevision R 与 64-hex contentHash）；t8（配置 append approvedSkillKeys→java-retry-backoff 第二次执行→断言 SKILL_INSTRUCTION ref 的 sourceId==发布 skillKey、version==R、contentHash 逐字一致——内置 Coding Skill 的 sourceId 被 TeamSkillExecutionSource 排除在动态引用外，故 SKILL_INSTRUCTION 引用必须全部来自已发布团队 Skill；effective-version 复核 R 不漂移） | 待执行 |
| 成本/延迟/恢复记录 | 真实栈：同 spec t9（cost/months 三来源 EMBEDDING/EXECUTION/DISTILLATION 非零+quality 结构）；对照实验逐 run token 差分与墙钟延迟见 §2；恢复面=JSONL 断点续跑+单次 infra 重建注记+judge 预热卷两臂共享 | 待执行 |
| 普通 PostgreSQL 关闭态（另验） | 真实栈：gate s8——plain 栈 18091 重跑既有 `feature-flags-off-real-api.spec.ts` + `cross-team-isolation-real-api.spec.ts`（合同 item 1 既有覆盖即「另验」，不写新 spec） | 待执行 |
| 升级象限（Q01 移交④） | 真实栈：gate s7——`flyway_vector_history` 行数 before→`vector build`→带数据六开 `vector up`→行数 ≥before→`q02-upgrade-recheck-real-api.spec.ts`（u1 升级后双源 preview 非降级+fragments 仍钉冻结 commit；u2 已发布 Skill 以坐标文件 pin 的 R+contentHash 重新加载进一次全新真实执行）→A01 mjs 复跑 | 待执行 |
| 质量对照（Q01 移交③） | 真实栈：gate s5——session-bootstrap（cookie 经 `set -a` 透传不回显）→A01 mjs `EXPECT_READY=1`；crewscope-java 整仓索引 SUCCEEDED 时三坐标指向整仓绑定，否则按用户授权降级（2026-10-07）：lab 语料跑 mjs+failureCode 入档+整仓结论记「待执行」；gate s6——`KnowledgeRetrievalQualityGateTest` S01 冻结阈值不降 | 待执行 |
| 可选拓扑单独结论 | 不适用（B6，沿用 Q01 §3 第 5 条）：拓扑开关属 E01 可选范围未选入主线，M10 主线无拓扑执行面；开关降级语义已在运维手册登记 | 不适用 |

## 2. 对照实验（S01 §4 冻结协议）

### 2.1 环境与配置记录

| 项 | 值 |
| --- | --- |
| Revision（git） | 待执行（终跑 HEAD sha） |
| 栈配置（vector） | 六开关全开（PGVECTOR+KNOWLEDGE_INDEX+INDEX_WORKER+RETRIEVAL+INJECTION+SKILL），MEMORY 关（B1）；两臂=同栈两次 re-up 只切 RETRIEVAL+INJECTION，s2–s7 绝不 reset |
| 生成模型 | 待执行（DeepSeek DIRECT flash；catalog entry id 与 revision 随坐标文件入档） |
| 嵌入模型 | 待执行（DashScope text-embedding-v4；IndexKey.modelKey/modelRevision 随坐标文件入档） |
| 硬件 | 待执行（宿主机型/CPU/RAM/Docker 分配） |
| 语料规模 | 知识：实验室语料 14 条+S01 dataset 已发布条目（条数终跑记录）；仓库：java-spring-lab @f053fd1e（主类 12+48 行 pom，chunk 数终跑记录）；crewscope-java 整仓（~2902 main java 文件，chunk 数终跑记录） |
| 样本矩阵 | 12 任务（m4 冻结全集）× 2 臂 × 3 重复 = 72 次；臂序 off→on 冻结（B4）；Review 全覆盖（每次 COMPLETED 都 create+execute+机械决策，用户裁定 2026-10-07②） |
| 对照纯度 | 驱动器每臂起点把 coding profile 的 approvedSkillKeys 重置为 []（闭环 spec t8 的 append 不污染对照）；配置命令七字段白名单+If-Match=当前 revision |

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
| 逐 run JSONL（断点续跑键 arm:task:rep） | `var/release/m10-q02/reports/runs-{off,on}.jsonl` | 待执行 |
| 对照汇总与冻结判定行（四行） | `var/release/m10-q02/reports/comparison-summary.{md,json}` | 待执行 |
| 逐 run patch+judge 工作区与日志 | `var/release/m10-q02/reports/{patches,judge}/` | 待执行 |
| 闭环坐标文件（账号为一次性栈账号；不含任何 key） | `var/release/m10-q02/loop-coordinates.json` | 待执行 |
| A01 mjs 输出（含 latency p50/p95/p99） | gate 终跑日志（摘录入档） | 待执行 |
| 观测脱敏证据（api 日志/DB 行计数，无 key 无凭据） | 摘录入档 | 待执行 |

### 2.4 结果

| 指标 | 臂-off | 臂-on | 判定 |
| --- | --- | --- | --- |
| 正确率（judge 主口径） | 待执行 | 待执行 | 待执行 |
| 正确率（产品口径并列） | 待执行 | 待执行 | — |
| Review 一次通过率 | 待执行 | 待执行 | 待执行 |
| Token in+out 均值/运行 | 待执行 | 待执行 | 待执行 |
| 延迟均值/P95 | 待执行 | 待执行 | 待执行 |
| 注入引用均值/运行（off 臂应为 0） | 待执行 | 待执行 | — |

## 3. 真实栈抓到的产品缺陷（Q02 存在的直接证据）

（待终跑回填；每条含模块、根因、修复与回归。）

## 4. Q01 移交项关闭表

| Q01 移交 | 本包动作 | 结果 |
| --- | --- | --- |
| §3 第 3 条：A01 mjs 评测 gate 对运行栈真实执行（与 q01/S10 同批复跑） | gate s5：session-bootstrap→`EXPECT_READY=1 node scripts/m10-a01/retrieval-quality-gate.mjs`（三坐标优先整仓，授权降级时 lab 语料+整仓结论「待执行」） | 待执行 |
| §3 第 4 条：完整双镜像原地升级 gate（q01/S18/S21 同批） | gate s7：rows before→build→带数据六开 up→rows≥before→upgrade-recheck spec→A01 mjs 复跑 | 待执行 |
| q01/S10 行（A01 mjs 真实执行） | 回填 Q01 矩阵该行复跑结果 | 待执行 |
| q01/S18、q01/S21 行（升级轻量档→完整档） | 回填 Q01 矩阵两行「完整档随 Q02」栏位 | 待执行 |

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
