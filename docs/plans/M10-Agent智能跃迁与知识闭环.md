# M10：Agent 智能跃迁与知识闭环执行清单

> 里程碑状态：未开始<br>
> 前置里程碑：`M9-Q02`<br>
> 评审来源：[M8 后全面架构与产品体验 Review](../reviews/M8后-全面架构与产品体验Review.md) `BE-03`<br>
> 运行时基线：`agentscope-java 2.0.0`（当前仅使用 model / event / message / agent / harness / tool / middleware / state / agui / skill / permission，约 40% 能力面）<br>
> 计划粒度：13 个完整工作包<br>
> **实现前必读：[§10 实施细则（防偏差基线）](#10-实施细则防偏差基线)** ——pgvector 的三处镜像替换与降级路径、AgentScope 扩展模块的引入方式、迁移清单、包与类落点、特性开关的关闭语义、固定攻击集与 E2E 清单、十四条「不要这样做」、以及必须由 `M10-S01` 闭合的七个开放项

## 1. 目标

M9 解决"好不好用"，M10 解决"越用越值钱"。

当前 CrewScope 的每一次 Agent 执行都是**无状态的**：Agent 不记得上次为什么这样改、不知道这个仓库的分层约定、不知道团队的 Review 口径、不知道哪个模块最近刚被别人改过。每个任务都在从零理解同一个代码库。这是与竞品拉开差距的最大机会，也是"团队协作优先"这一产品初衷在 Agent 侧的真正落点——**CrewScope 的记忆应该属于 Team，不属于某次会话**。

M10 完成后：

- Agent 执行 Coding 任务前会自动检索该仓库的结构约定、相关历史改动与团队 Review 规则；
- 团队的显式约定（代码规范、架构决策、领域术语）成为可维护、可审计、可继承的 Team 知识资产；
- 反复出现的执行套路可以沉淀为 Skill，被后续任务复用而不是每次重新写 Prompt；
- 复杂任务可以由多个专职 Agent 分工协作，而不是单 Agent 串行硬扛；
- 团队能看清每个 Agent、每个模型、每个任务的成本与质量，并据此调整配置。

## 2. 出口结果

1. **Team Knowledge Base** 落地：Team 范围内的知识条目具备来源、版本、生效范围、责任人与审计；
2. **代码库 RAG** 落地：仓库索引随受管 Mirror 增量更新，检索结果可溯源到具体文件与提交；
3. **Agent Memory** 落地：区分 Conversation 短期记忆、Team 长期记忆与 Agent 偏好记忆，三者边界与生命周期明确；
4. **Skill 沉淀闭环**：从成功执行中提炼 Skill，经人工确认后进入 Team Skill 目录并可被后续任务引用；
5. **多 Agent 协同**：支持 Planner / Coder / Reviewer 的受控协作拓扑，全程可观测、可中断、可回放；
6. **成本与质量可观测**：Token、时长、重试、成功率、Review 通过率按 Agent/模型/任务类型成表；
7. 上述全部能力继承既有 Ownership × ExecutionScope 交集模型，新增固定攻击集验证知识与记忆不跨越 Team 边界。

## 3. 范围与非目标

### 3.1 本期范围

- 在 `crewscope-agentscope` 中接入 AgentScope 的 `memory`、`extensions-rag`、`extensions-skills` 与必要的 `extensions-mem`；
- 知识与记忆的权威事实落在 CrewScope 自己的 PostgreSQL，AgentScope 扩展作为检索与组装能力使用，不成为第二套权威状态；
- 向量存储优先使用 PostgreSQL `pgvector`，不引入独立向量数据库；
- 索引与嵌入在 Worker 侧执行，复用既有 Lease/Fencing/Outbox/Checkpoint 耐久语义；
- 多 Agent 协同复用既有 TaskExecution 状态机与 Human Gate，不新建并行的执行内核。

### 3.2 非目标

- 跨 Team、跨 Organization 的知识共享（M12）；
- MCP / A2A 协议与外部 Agent 互联（M11）；
- 自动模型路由与自动降级（观测先行，M11 再决策）；
- 模型微调、私有模型训练；
- 自动合并、自动发布与 Autopilot。

## 4. 核心决策

### 4.1 知识有三层，边界必须显式

```text
Team Knowledge   团队显式约定：规范、架构决策、领域术语、Review 口径
                 来源=人工录入 / 从 ADR、Review 结论提炼
                 生命周期=长期，需要维护者，可废弃可版本化

Repository Index 代码库派生事实：文件结构、符号、注释、提交摘要
                 来源=从受管 Mirror 派生
                 生命周期=跟随仓库，可随时全量重建

Agent Memory     执行过程记忆：本次会话上下文、跨会话偏好
                 来源=执行过程自动产生
                 生命周期=有界，可过期，可清空
```

三层不得互相写入。Repository Index 是**可重建的派生视图**（沿用 ADR-026 的 Readiness 原则），丢失不影响正确性；Team Knowledge 是权威事实，有完整审计；Agent Memory 是有界缓存，任何时候清空都不应破坏任务正确性。

### 4.2 检索结果必须可溯源，且必须可拒绝

任何进入 Prompt 的检索片段都携带 `来源类型 + 稳定坐标 + 版本/提交 + 相关度`。执行详情页展示"本次执行引用了哪些知识"，成员可以标记某条知识为"本次不适用"，该反馈进入知识质量信号。

**不允许出现"Agent 引用了某条知识但界面上无法追溯是哪条"的情况。**

### 4.3 记忆不越过既有权限边界

知识检索发生在服务端，检索范围由 `ExecutionScope ∩ Ownership` 决定，与代码执行边界完全一致。Agent 能读到的知识，必须是发起该任务的成员本人有权读到的知识。撤权后正在执行的任务按既有 Fenced 语义处理。

向量检索**不得**成为权限绕过通道：过滤在 SQL 层完成，不在应用层对已出库结果做二次裁剪。

### 4.4 Skill 沉淀必须经过人

Agent 可以提议 Skill，但 Skill 进入 Team 目录必须由具备权限的成员确认。自动沉淀的 Skill 直接生效会让错误模式在团队内自我强化——这与 Human Gate 的既有立场一致。

### 4.5 多 Agent 协同是受控拓扑，不是自由涌现

只支持声明式的固定拓扑（Planner → Coder → Reviewer，可配置启用项），不支持 Agent 自由创建子 Agent。每个环节的产出是结构化的、可检查的、可中断的，且整体仍是一个 TaskExecution，保持现有的恢复与对账语义。

### 4.6 观测先行于优化

M10 只交付成本与质量的**度量**，不交付自动优化。任何自动模型切换、自动重试策略调整都必须先有至少一个里程碑的真实数据支撑。

## 5. 依赖顺序

```text
M9-Q02 -> M10-S01

M10-S01 -> M10-D01 -> M10-I01 -> M10-A01
M10-S01 -> M10-D01 -> M10-A02
M10-A01,M10-A02 -> M10-I02
M10-I02 -> M10-F01
M10-I02 -> M10-A03 -> M10-F02
M10-S01 -> M10-E01 -> M10-F03

M10-F01,M10-F02,M10-F03,M10-E01 -> M10-Q01 -> M10-Q02
```

## 6. 执行任务

| ID | 类型 | 依赖 | 涉及模块 | 完整交付结果 | 主要验收 |
|---|---|---|---|---|---|
| `M10-S01` | SPIKE | M9-Q02 | agentscope/application/product/docs | 验证 AgentScope `memory`/`extensions-rag`/`extensions-skills`/`extensions-mem` 与 CrewScope 既有 Harness、State、Permission、Interruption 的集成路径；冻结三层知识模型的边界、权威归属、生命周期与重建策略；冻结嵌入模型与向量维度选择、`pgvector` 索引策略与查询预算；冻结检索片段的公开字段白名单；冻结多 Agent 拓扑与 Skill 沉淀审批流。产出 ADR-030（知识与记忆分层）、ADR-031（多 Agent 协同拓扑） | 用"新仓库/大仓库/无权限成员/跨 Team/撤权中/嵌入服务不可用"六类 Fixture 证明边界闭合；给出 10 万文件仓库的索引耗时与存储估算；证明扩展接入不破坏既有 Checkpoint 与 Interruption 语义；两个 ADR 被接受 |
| `M10-D01` | TASK | S01 | domain/infrastructure/docs | 建立知识与记忆的领域模型与持久化：`KnowledgeEntry`（Team 权威知识，含来源、版本、生效范围、责任人、废弃状态）、`RepositoryIndexSnapshot`（可重建派生视图，含 Generation 与提交坐标）、`AgentMemoryRecord`（有界，含 TTL 与作用域）；新增 Flyway 迁移启用 `pgvector` 并建立向量与元数据复合索引；三类实体的 Scope 字段均为非空且进入外键约束 | 领域层零框架依赖；迁移在既有 `V26..V36` 之后连续且可回滚验证；越权查询在 SQL 层即返回空集（有集成测试证明）；Generation 机制支持整库重建而不阻塞在线检索；向量索引在 10 万片段规模下有明确 P95 |
| `M10-I01` | FEATURE | D01 | infrastructure/agentscope/integration/docs | 交付仓库索引管线：从受管 Mirror 增量抽取（文件结构、语言、符号、Doc 注释、提交摘要），分片、嵌入、入库；复用既有 Worker Lease/Fencing/Checkpoint 实现可中断可恢复的长任务；支持全量重建、增量更新（按提交 diff）、单仓库禁用与配额上限；嵌入 Provider 复用既有 ModelConnection 治理，不新增凭证通道 | 索引任务可中断、可恢复、可取消且无重复写入；仓库删除或解绑后索引级联清理；二进制、超大文件、生成物、`.gitignore` 命中项被正确排除；嵌入服务不可用时任务进入可重试终态而非污染索引；Token 与配额有上限且超限失败关闭 |
| `M10-A02` | FEATURE | D01 | application/server/web/docs | 交付 Team Knowledge Base 管理能力：知识条目的创建、编辑、版本、废弃、分类与生效范围（Team / WorkProject / Repository 三级）；支持从既有 ADR、Review 结论、Task 总结一键提炼为草稿；权限沿用既有角色模型，编辑与废弃需明确权限；全程 Audit | 契约见 `docs/api/M10-知识库API契约.md`；覆盖跨 Team 越权、并发编辑强 ETag 冲突、废弃后不再被检索、生效范围变更即时生效、超长内容截断与注入字符处理；响应不含 Credential 或宿主路径 |
| `M10-A01` | FEATURE | I01 | application/server/docs | 交付统一知识检索 Query：给定任务上下文（仓库、路径、WorkItem、意图）在 `ExecutionScope ∩ Ownership` 内混合检索三层知识；结果去重、重排、按预算截断，每条携带来源类型、稳定坐标、版本与相关度；提供独立的 HTTP 端点供前端"知识预览"使用 | 检索过滤在 SQL 层完成并有越权集成测试证明；同一查询在相同数据下结果稳定；预算上界在最坏 Scope 下可验证；检索延迟 P95 有基线；结果字段通过公开字段白名单扫描 |
| `M10-I02` | FEATURE | A01、A02 | agentscope/application/docs | 将检索接入执行链路：在 AgentScope Harness 的 Prompt 组装阶段注入检索片段，受 Token 预算与优先级策略约束；引入 Conversation 短期记忆与 Agent 偏好记忆并区分 TTL；记录本次执行实际引用的知识 ID 与片段，写入 TaskExecution 证据；检索失败时降级为无知识执行而非任务失败 | 引用记录与实际 Prompt 内容一致（有测试证明）；Token 预算超限时按优先级截断且不截断到半个片段；检索服务不可用时任务正常完成并在证据中标注降级；既有 Checkpoint 恢复后引用记录不重复不丢失；固定攻击集验证知识不跨 Team 泄漏 |
| `M10-A03` | FEATURE | I02 | domain/application/agentscope/server/docs | 交付 Skill 沉淀闭环：从成功执行中提炼 Skill 草稿（触发条件、步骤、工具序列、适用范围），进入待审队列；具备权限的成员审核、编辑、发布或驳回；已发布 Skill 通过 AgentScope `skill` 能力面参与后续执行并记录命中率；支持禁用与版本回滚 | Skill 草稿不自动生效；发布、驳回、禁用全程 Audit；Skill 引用的工具必须在当前 Task Tool Policy 内，越界 Skill 拒绝发布；命中率与成功率可统计；恶意 Skill（越权工具、注入指令）在发布校验中失败关闭 |
| `M10-E01` | FEATURE | S01 | agentscope/application/domain/docs | 交付多 Agent 协同拓扑：声明式的 Planner / Coder / Reviewer 三段协作，每段可独立启用与配置模型；段间产出为结构化契约；整体仍是一个 TaskExecution，复用既有状态机、Lease、Checkpoint、Interruption 与 Human Gate；每段产出进入既有三流事件协议并在 Timeline 可见 | 任意段失败可恢复到段边界而非从头重跑；中断在段内与段边界均可正确响应；段间产出被 Schema 校验，非法产出触发有界重试后失败关闭；单 Agent 模式与多 Agent 模式在同一 API 下兼容；成本与时长按段拆分可见 |
| `M10-F01` | FEATURE | I02 | web/docs | 交付知识库与检索的前端：知识库管理页（列表、编辑器、版本对比、生效范围、废弃）；仓库索引状态页（覆盖率、最后更新、重建入口、失败诊断）；执行详情页新增"引用知识"面板，展示本次引用的条目与片段并支持标记不适用 | 沿用 M9 设计系统与 StatePanel 六态；Desktop/390px、键盘、Axe、视觉门禁通过；无权限成员看到明确责任人提示而非空白；长内容编辑有草稿保留与强 ETag 冲突回读 |
| `M10-F02` | FEATURE | A03、E01 | web/docs | 交付 Skill 与多 Agent 协同的前端：Skill 目录与待审队列（草稿 diff、工具清单、适用范围、发布/驳回）；Agent 配置中的协同拓扑开关与分段模型选择；执行 Timeline 按 Planner/Coder/Reviewer 分段折叠展示，每段显示模型、耗时、Token 与产出摘要 | 分段 Timeline 在长执行下接入 M9 虚拟滚动；Skill 审核动作有二次确认与权限校验；拓扑配置变更不影响进行中的执行；全部新页面纳入 Histoire、视觉与 Axe 基线 |
| `M10-F03` | FEATURE | E01 | application/server/web/docs | 交付成本与质量可观测：按 Agent / 模型 / 任务类型 / 时间窗聚合 Token 用量、执行时长、重试次数、成功率、Review 一次通过率、知识命中率；Team 级用量视图与可配置的月度预算提醒；指标同时进入既有 Prometheus 出口 | 聚合为派生查询，不复制执行事实；跨 Team 越权、空数据、部分缺失、时区边界通过；用量视图不暴露其他 Team 数据；预算提醒复用既有 Inbox/飞书通知通道；Prometheus 指标保持低基数 |
| `M10-Q01` | HARDENING | F01,F02,F03,E01 | all/ci | 知识与记忆安全门禁：新增固定攻击集覆盖跨 Team 知识泄漏、撤权后检索、Prompt 注入经由知识条目、恶意 Skill 越权工具、索引路径穿越、嵌入服务返回污染、向量检索侧信道；新增固定故障集覆盖嵌入服务不可用、索引中断恢复、向量库膨胀、检索超时降级；Coverage 与性能预算按新增代码 ratchet | 全部攻击被阻断且有稳定原因码；全部故障收敛到可恢复终态；降级路径不产生错误结果只产生标注；M0–M9 全量门禁零回归 |
| `M10-Q02` | HARDENING | Q01 | all/release | M10 Release Gate：在真实 PostgreSQL(pgvector)/Redis/真实模型上完成"导入仓库→建立索引→录入团队规范→发起 Coding 任务→验证 Agent 引用了正确知识→Review→沉淀 Skill→复用 Skill 执行第二个任务"的完整知识闭环；产出 `docs/testing/M10-Q02-Release-Gate.md` | 第二个任务可验证地复用了第一个任务沉淀的知识或 Skill，并有引用证据；全部必需门禁通过；索引与检索的成本与延迟有生产基线；M0–M9 语义零回归 |

## 7. 建议执行波次

| 波次 | 任务 | 可演示结果 |
|---|---|---|
| W1 合同与地基 | S01、D01 | 三层知识边界冻结，`pgvector` 与领域模型就位 |
| W2 知识供给 | I01、A02 | 仓库可建立索引，团队可录入规范 |
| W3 检索与接入 | A01、I02 | Agent 执行时真实引用团队知识并留下证据 |
| W4 沉淀与协同 | A03、E01 | Skill 可沉淀复用，多 Agent 分段协作可见 |
| W5 前端与观测 | F01、F02、F03 | 知识、Skill、成本三块在界面上完整可管 |
| W6 安全与发布 | Q01、Q02 | 固定攻击集通过，知识闭环端到端验证 |

`A02`（知识库管理）与 `I01`（索引管线）互不依赖，建议并行——前者产品价值可以先于索引能力单独演示。
`E01`（多 Agent）是本里程碑最大的不确定性来源，若 `S01` 评估风险过高，允许整体后置到 M11 并把 M10 收敛为"知识闭环"单主题。

## 8. M10 Release Gate

1. Team Knowledge、Repository Index、Agent Memory 三层边界清晰，互不写入，派生层可整体重建；
2. 知识检索过滤在 SQL 层完成，跨 Team 与撤权后越权全部被阻断；
3. 每次执行引用的知识在界面上可完整追溯到条目与片段，并可被成员标记不适用；
4. 索引任务可中断、可恢复、可取消、可重建，配额有上限；
5. 检索或嵌入服务不可用时执行降级而非失败，并在证据中显式标注；
6. Skill 必须经人工确认才生效，越权工具的 Skill 发布失败关闭；
7. 多 Agent 协同保持单一 TaskExecution 语义，分段可恢复、可中断、可观测；
8. 成本与质量视图为派生查询，不复制执行事实，不跨 Team 泄漏；
9. 新增固定攻击集与固定故障集全部通过；
10. 端到端知识闭环（索引→规范→执行→引用→沉淀→复用）在真实模型下验证；
11. M0–M9 全量固定攻击、故障、恢复、迁移与浏览器门禁零回归。

## 9. 任务规模约定

沿用 M8/M9 口径。`I01`（索引管线）与 `E01`（多 Agent 协同）是本里程碑的两个长任务，均按"先合同测试、后实现、最后接入执行链路"三步推进，中间状态不得进入默认执行路径——新能力一律以显式开关引入，默认关闭，验证通过后再翻转默认值。
---

## 10. 实施细则（防偏差基线）

> 与 M9 §10 同口径：本节不新增工作包、不改动 §2 出口结果与 §8 Release Gate 的任何一条，只把它们落到具体坐标上。所有路径与模块坐标按 2026-09-12 的代码库实测写下。
>
> M10 的特殊性在于它是**第一个真正扩展领域模型与基础设施依赖**的里程碑（向量检索、AgentScope 扩展模块、多 Agent 拓扑）。因此本节的重点不是文件落点，而是**三条容易越界的边界**：数据库与镜像依赖、AgentScope 模块引入方式、知识注入对权限与预算的影响。

### 10.1 红线清单

| 红线 | 判定方式 |
|---|---|
| 权限边界不因"检索"而放宽 | 任何检索都在 `ExecutionScope ∩ Ownership` 内求交后再命中向量库；**不允许先向量召回再过滤**（召回集本身就是信息泄漏面，`M10-Q01` 的固定攻击集专门打这一点） |
| Human Gate 不被 Skill 绕过 | Skill 只能编排**已经存在且已被授权**的工具序列，不得引入新工具、不得提升工具权限（§4.4） |
| 多 Agent 协同仍是一个 Task | 段间产出为结构化契约，Task 的耐久语义（Outbox / Lease / Fencing / Checkpoint）不变；**不得为每段新建独立 Task 生命周期**（§4.5） |
| 既有 Conversation / Task / Coding 的事件类型与载荷版本 | 只允许新增事件类型，既有不得改名或改载荷形状 |
| 已发布迁移不可变 | 含 M9 落地的 `V37`（及可选 `V38`），一个字节都不许改 |
| M9 的体验基线不回退 | 新增的知识库、索引状态、Skill、成本四类页面必须**从第一版就满足** M9 §10 的全部 DoD（Token、`labels.ts`、`StatePanel` 六态、`meta.title`、390px 可达、禁用可解释）——不允许以"新页面先跑通再说"为理由退回旧范式 |
| AgentScope 不 fork | 只通过依赖坐标引入扩展模块，不复制其源码进 `crewscope-agentscope`（见 10.3） |

### 10.2 基础设施依赖：pgvector 不是"加个迁移"就完事

实测现状：`compose.yaml:3` 使用 `postgres:17-alpine`，`deploy/team-beta/compose.yaml:129` 与 `compose.demo.yaml:10` 使用同一镜像并**按 digest 锁定**。`postgres:17-alpine` **不含 pgvector**。

因此 `M10-D01` / `M10-I01` 必须同时完成以下四件事，缺一件会在 CI 或部署环境上以"扩展不存在"失败：

1. **三处镜像同步替换**——`compose.yaml`、`deploy/team-beta/compose.yaml`、`deploy/team-beta/compose.demo.yaml`。替换为 `pgvector/pgvector:pg17`（或基于 `postgres:17-alpine` 自建镜像），**并保持既有的 digest 锁定习惯**，不得改成浮动 tag；
2. **扩展启用迁移独立成文件**——`CREATE EXTENSION IF NOT EXISTS vector;` 单独一个迁移，不与建表混在同一个文件里。理由：启用扩展在多数托管 PostgreSQL 上需要额外权限，独立文件让"权限不足"失败点清晰、可被运维单独授权；
3. **部署前置条件写进文档**——在 `docs/ops/` 既有部署说明里补一条「PostgreSQL 必须提供 `vector` 扩展」，并给出托管数据库（未开放该扩展时）的降级路径；
4. **降级路径必须真的可运行**——`M10-S01` 需要冻结「无 pgvector 时的行为」：是关闭知识检索特性开关并保留知识库管理（推荐），还是退化为全文检索。**不允许出现"没有 pgvector 就起不来"**——那会让所有既有部署在升级时直接失败。

向量维度与索引类型（`ivfflat` / `hnsw`）、距离度量由 `M10-S01` 冻结并写入 ADR-030；**维度一旦写进迁移就不可变**，改维度等于重建全量索引，属数据迁移而非结构迁移。

### 10.3 AgentScope 扩展模块的引入方式

实测 `agentscope-java` 提供的相关模块：`agentscope-core`（含 `io.agentscope.core.memory`）、`agentscope-harness`、`agentscope-extensions-rag`、`agentscope-extensions-skills`、`agentscope-extensions-mem`、`agentscope-extensions-postgresql`、`agentscope-extensions-sandbox`、`agentscope-spring-boot-starters`。

规则：

1. 依赖版本统一走 `agentscope-dependencies-bom`，**不在子模块里写死版本号**；
2. 新依赖**只允许出现在 `crewscope-agentscope` 与 `crewscope-infrastructure` 两个模块的 POM 里**。`crewscope-domain` 保持零框架依赖，`crewscope-application` 只依赖端口接口——由 `scripts/check-module-boundaries.mjs` 的 Java 侧规则（或 ArchUnit 测试）强制；
3. **不 fork、不复制源码。** 若某扩展的能力不满足需要，正确做法是在 `crewscope-agentscope` 里实现适配器/装饰器，并在 ADR-031 里记录"为什么不能直接用"；
4. `crewscope-agentscope` 实测包结构为 `io.crewscope.agentscope.{task,coding,review,model,template,teamobserver,agui}`。M10 新增两个同级包：
   - `io.crewscope.agentscope.knowledge`——检索注入、Prompt 组装阶段的片段拼装与 Token 预算裁剪（`M10-I02`）；
   - `io.crewscope.agentscope.topology`——Planner / Coder / Reviewer 三段协作拓扑（`M10-E01`）。**不要叫 `orchestration`**，它与既有 ActionDelivery 的"编排"语义重叠，会在讨论中反复歧义。

### 10.4 数据库迁移清单与编号

M9 用到 `V37`（行级评论）、`V38`（搜索索引）与 `V39`（评论命令键槽位分离，见 M9 计划 §10.9 第 16 条）。**M10 编号从合并时的实际最大值 +1 顺延：按 M9 用到 `V39` 计，M10 从 `V40` 起。** 文件名的主题部分不随编号变化，评审时以主题为准：

| 文件 | 工作包 | 内容 |
|---|---|---|
| `V40__enable_vector_extension.sql` | D01 | 仅 `CREATE EXTENSION IF NOT EXISTS vector;`（见 10.2 第 2 条） |
| `V41__knowledge_entry.sql` | D01 | `KnowledgeEntry` 及其版本、生效范围（Team / WorkProject / Repository 三级）、责任人、废弃状态 |
| `V42__repository_index_snapshot.sql` | D01/I01 | `RepositoryIndexSnapshot`、分片表、嵌入向量列与索引 |
| `V43__agent_memory.sql` | D01/I02 | Conversation 短期记忆与 Agent 长期记忆的持久化 |
| `V44__skill_draft.sql` | D01/A03 | Skill 草稿、审核状态、版本、适用范围、工具序列 |

三条约束：

- **一个工作包一个迁移，不合并**。知识与记忆是四个独立的可回滚单元，合并成一个大迁移会让任何一处出错都必须整体回退；
- 向量列的维度、索引类型与 `lists`/`m`/`ef_construction` 参数必须写进迁移注释并与 ADR-030 一致；
- `M10-A01`（检索 Query）、`M10-I02`（注入）、`M10-E01`（拓扑）、`M10-F01/F02/F03`（前端）**零迁移**。若这些任务开始写迁移，说明领域模型在 `D01` 没定完，必须回 `D01` 而不是就地补表。

### 10.5 后端包与类落点

| 工作包 | 层与落点 |
|---|---|
| `M10-D01` | domain：`domain/knowledge/`（新建）`KnowledgeEntry`、`KnowledgeEntryId`、`KnowledgeScope`（TEAM/WORKPROJECT/REPOSITORY）、`KnowledgeSource`、`KnowledgeLifecycle`；`domain/memory/`（新建）`ConversationMemory`、`AgentMemory`、`MemoryRetention`；`domain/skill/`（新建）`SkillDraft`、`SkillDraftId`、`SkillApplicability`、`SkillToolSequence`、`SkillReviewDecision`。**三个包全部零框架依赖** |
| `M10-D01` | infrastructure：`persistence/knowledge/`、`persistence/memory/`、`persistence/skill/`，沿用 `Jpa{X}RepositoryAdapter` / `Jdbc{X}RepositoryAdapter` / `{X}Entity` / `{X}EntityMapper` / `{X}PersistenceConfiguration` 命名族 |
| `M10-I01` | infrastructure：`infrastructure/index/`（新建，仓库索引管线）。**复用既有 Worker Lease / Fencing / Checkpoint**（与 `M8` GitHub 导入 Job 同一套，见 `V34`–`V36`），不新造调度机制 |
| `M10-A01` | application：`application/knowledge/`（新建）`KnowledgeRetrievalQuery`、`KnowledgeRetrievalQueryService`、`RetrievedFragment`、`RetrievalBudget`、`KnowledgeAccessPolicy`、`KnowledgeIndexPort`。**求交在 Query 层完成，向量库只接受已经收窄的范围参数** |
| `M10-A02` | application：`application/knowledge/` 内 `CreateKnowledgeEntryCommand`、`UpdateKnowledgeEntryCommand`、`DeprecateKnowledgeEntryCommand`、`KnowledgeEntryApplicationService`、`KnowledgeEntryRepository`、`KnowledgeEntryQuery(Service)`、`KnowledgeEntryPage`、`KnowledgeEntryCursor` |
| `M10-A03` | application：`application/skill/`（新建）`ProposeSkillDraftCommand`、`ReviewSkillDraftCommand`、`PublishSkillCommand`、`SkillApplicationService`、`SkillDraftRepository`、`SkillQuery(Service)` |
| `M10-I02` | agentscope：`io.crewscope.agentscope.knowledge`（见 10.3 第 4 条） |
| `M10-E01` | agentscope：`io.crewscope.agentscope.topology`；application：`application/execution/` 内扩写段间契约，**不新建 `application/pipeline` 之类的平行概念** |
| `M10-F03` | application：`application/observability/`（**既有包，就地扩写**，不要新建 `application/cost` 或 `application/metrics`）；server：`AgentCostController`、`AgentCostSummaryResponse` |

server 侧新增 Controller（DTO 一律平铺在 `server/api/`，不建 `api/dto/`）：`KnowledgeEntryController`、`KnowledgeRetrievalController`（若检索仅供内部调用则不暴露 HTTP，由 S01 裁决且**只许一处**）、`RepositoryIndexController`、`SkillController`、`SkillReviewController`、`AgentCostController`，以及对应的 `{X}ApiSupport` / `{X}Response` / `{X}PageResponse` / `{X}CursorCodec`。

### 10.6 前端落点

沿用 M9 §10.4 的目录规则与全部 DoD。新增：

| 路径 | 工作包 | 说明 |
|---|---|---|
| `src/domains/knowledge/{gateway,store,types,labels}.ts` | F01 | 知识条目、生效范围、生命周期状态 |
| `src/domains/knowledgeindex/{gateway,store,types,labels}.ts` | F01 | 仓库索引状态与覆盖率 |
| `src/domains/skill/{gateway,store,types,labels}.ts` | F02 | Skill 草稿、审核状态 |
| `src/domains/cost/{gateway,store,types,labels}.ts` | F03 | 成本与质量聚合 |
| `src/pages/KnowledgeBasePage.vue` | F01 | 知识库管理（列表、编辑器、版本对比、生效范围、废弃） |
| `src/pages/KnowledgeIndexPage.vue` | F01 | 索引状态（覆盖率、最后更新、重建入口） |
| `src/pages/SkillCatalogPage.vue` | F02 | Skill 目录与待审队列 |
| `src/pages/AgentCostPage.vue` | F03 | 成本与质量看板 |
| `src/components/knowledge/RetrievalCitation.vue` | F01/F02 | **检索片段的溯源呈现**（§4.2 要求可溯源、可拒绝）——执行详情里每个被注入的片段都要能点回知识条目或代码位置 |
| `src/components/skill/SkillDraftDiff.vue` | F02 | 草稿 diff，复用 M9 的 `CodeViewer` |

四个新页面必须同步进入：`src/app/router.ts` 的 `meta.title` / `navGroup` / `requiredPermission`、M9 的一级导航分组、抽屉导航、`scripts/check-route-metadata.mjs` 的路由清单、以及 390px 可达性 E2E 的菜单列表（列表长度从 14 增至 18）。**这一条是 M10 最容易漏的收口**——新页面加进来但不进 M9 建立的那几份清单，等于把刚清偿完的欠账重新开一个口子。

### 10.7 特性开关：默认关闭，且关闭时行为必须完整

M10 的全部新能力**默认关闭**（§9 已定），本节固化开关命名与关闭语义：

| 开关 | 关闭时的行为 |
|---|---|
| `crewscope.knowledge.retrieval.enabled` | 不注入任何检索片段；Prompt 组装与 M9 完全一致；知识库管理页仍可用（录入不依赖检索） |
| `crewscope.knowledge.index.enabled` | 不启动索引管线；索引状态页显示"未启用"并说明启用条件与责任方，**不是空白页** |
| `crewscope.memory.enabled` | 不写入也不读取记忆；Conversation 行为与 M9 一致 |
| `crewscope.skill.enabled` | 不提炼草稿；Skill 目录页显示"未启用"；**已发布的 Skill 不参与执行** |
| `crewscope.topology.multiAgent.enabled` | 单 Agent 路径，与 M9 完全一致 |

三条硬规则：

1. **关闭态必须有测试覆盖**——`M10-Q02` 的零回归验收必须在"全部开关关闭"的配置下跑一遍 M0–M9 全量，证明 M10 可以安全地不启用；
2. **关闭态的页面不是空白页**，必须说明"未启用"、启用条件与责任方（沿用 `SetupPage` 的 `responsibleParty` 口径）；
3. 开关读取集中在配置层，**不允许在 Prompt 组装的循环里逐次读配置**——那会让开关切换出现半启用的中间态。

### 10.8 门禁、固定攻击集与 E2E

`M10-Q01` 的固定攻击集落在既有安全测试目录（与 M0–M8 的攻击集同处，不新建平行目录），至少覆盖六类：

1. 跨 Team 知识泄漏（A 团队的知识条目出现在 B 团队的检索结果或 Prompt 里）；
2. 撤权后检索（成员被移除或降权后，其正在执行的任务仍召回原范围内容）；
3. 经由知识条目的 Prompt 注入（知识正文里写"忽略以上指令"）；
4. 恶意 Skill 越权工具（Skill 试图调用未授权工具或提升权限）；
5. 索引路径穿越（索引管线读取受管 Mirror 之外的路径）；
6. 向量召回集泄漏（先召回后过滤导致的计数、排序或耗时侧信道）。

E2E（`crewscope-web/e2e/`，沿用 `m{N}-{topic}.spec.ts`）：

- `m10-knowledge-base.spec.ts`（录入、版本对比、生效范围、废弃）
- `m10-knowledge-index.spec.ts`（索引状态、重建、未启用态）
- `m10-retrieval-citation.spec.ts`（执行详情里每个注入片段可溯源、可人工拒绝）
- `m10-skill-review.spec.ts`（草稿 diff、发布、驳回、未启用态）
- `m10-agent-cost.spec.ts`（成本聚合与下钻）
- `m10-feature-flags-off.spec.ts`（**全部开关关闭时五个新页面均给出"未启用"说明而非空白**）
- `m10-mobile-reach.spec.ts` 或直接扩写 `m9-mobile-reach.spec.ts` 的菜单列表至 18 项（二选一，**不要两处都维护一份菜单清单**）

发布门禁脚本：`scripts/m10-release-gate.sh`。

### 10.9 文档与契约文件名

ADR（沿用 `ADR-0NN-中文标题.md`）：

- `docs/adr/ADR-030-知识与记忆三层模型.md`（S01/D01/A01/I02：三层边界、向量维度与索引参数、检索预算与优先级、求交先于召回的裁决、无 pgvector 的降级路径）
- `docs/adr/ADR-031-Skill沉淀与多Agent协同拓扑.md`（S01/A03/E01：Skill 审核闭环与工具授权边界、三段协作的段间契约、为什么不 fork AgentScope）

契约（沿用 `M{N}-{Topic}API契约.md`）：

- `docs/api/M10-知识库API契约.md`（A02）
- `docs/api/M10-知识检索API契约.md`（A01，若不暴露 HTTP 则改为内部端口契约并在文件头声明）
- `docs/api/M10-仓库索引API契约.md`（I01）
- `docs/api/M10-Skill目录与审核API契约.md`（A03）
- `docs/api/M10-成本与质量观测API契约.md`（F03）

其他：`docs/ops/M10-pgvector部署前置说明.md`（10.2 第 3 条）。每份契约的七项必备内容与 M9 §10.8 相同。

### 10.10 「不要这样做」清单

| # | 偏差写法 | 正确做法 |
|---|---|---|
| 1 | 先向量召回 Top-K 再按权限过滤 | 权限求交必须在查询进入向量库**之前**完成；召回集本身是泄漏面（`Q01` 攻击集第 6 项专打此处） |
| 2 | 把 pgvector 迁移与建表写在同一个文件 | 扩展启用独立成文件，便于运维单独授权与定位失败 |
| 3 | 让 `postgres:17-alpine` 保持不变，指望环境自带 vector | 三处 compose 同步替换并保持 digest 锁定 |
| 4 | 复制 AgentScope 扩展源码进 `crewscope-agentscope` | 用适配器/装饰器，并在 ADR-031 记录原因 |
| 5 | 新建 `io.crewscope.agentscope.orchestration` | 与 ActionDelivery 的"编排"语义重叠；用 `topology` |
| 6 | 新建 `application/cost` 或 `application/metrics` | `application/observability/` 是既有包，就地扩写 |
| 7 | 四个新页面先跑通、体验后补 | 必须从第一版满足 M9 §10 的全部 DoD；否则刚清偿的欠账重新开口 |
| 8 | 新页面不进 `mobile-reach` 与 `route-meta` 清单 | 菜单数 14 → 18，两份清单必须同步（且只维护一处） |
| 9 | 开关关闭时页面留空白 | 显示"未启用" + 启用条件 + 责任方 |
| 10 | Skill 可以声明新工具 | Skill 只编排已授权工具，Human Gate 不可绕过 |
| 11 | 多 Agent 每段各自建 Task | 段间是结构化契约，整体仍是一个 Task 的耐久单元 |
| 12 | 改向量维度当作普通迁移 | 等于全量重建索引，属数据迁移，须单独计划与回滚方案 |
| 13 | 在 Prompt 组装循环里读特性开关 | 集中在配置层读取一次，避免半启用中间态 |
| 14 | 在 `A01`/`I02`/`E01` 里补表 | 说明 `D01` 的领域模型没定完，回 `D01` |

### 10.11 由 `M10-S01` 必须给出答案的开放项

本节刻意留空的部分，全部在 `M10-S01` 的 Spike 出口里闭合，**未闭合不得进入 `D01`**：

1. 向量维度、索引类型与参数、距离度量；
2. 无 pgvector 的降级路径（推荐：关闭检索、保留知识库管理）；
3. 检索 Token 预算的具体数值与三层知识的优先级/去重/重排策略；
4. `agentscope-extensions-rag` / `-skills` / `-mem` 与 CrewScope 既有 Harness、State、Permission、Interruption 的集成点清单（哪些用扩展、哪些自实现、为什么）；
5. 知识检索是否暴露 HTTP 端点（若暴露，只许一处）；
6. 记忆的保留期与清理策略，以及成员离开团队后其记忆的归属；
7. 新页面的四个 `requiredPermission` 取值与既有权限枚举的映射。
