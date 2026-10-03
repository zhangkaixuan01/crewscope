# M10：Agent 智能跃迁与知识闭环执行清单

> 里程碑状态：进行中——`M10-S01` 已完成（2026-10-01，合同冻结+真实 embedding 实测+隔离原型，见 [S01 冻结记录](../spikes/M10-S01-知识与检索合同冻结.md) §6 台账）；`M10-D01` 已完成（2026-10-02，知识与检索公共地基，证据见 S01 冻结记录 §6 D01 列）；`M10-A02` 已完成（2026-10-02/03，A02a 人工录入管理命令面+A02b 来源提炼草稿/披露检查/DISTILLATION 用量事实，**A02 父包关闭**，证据见 S01 冻结记录 §6 A02a/A02b 列，契约见 [M10-知识库API契约](../api/M10-知识库API契约.md)）；`M10-I01a` 已完成（2026-10-03，embedding 适配+可选向量存储，证据见 S01 冻结记录 §6 I01a 列）；`M10-I01b` 可开工<br>
> 前置里程碑：`M9b-Q02`；M9 未完成的技术路径在 M9b 新版本补证，不能以历史本地 PASS 替代；不继承已取消的真人测评门槛<br>
> 评审来源：[M8 后全面架构与产品体验 Review](../reviews/M8后-全面架构与产品体验Review.md) `BE-03`<br>
> 运行时基线：`agentscope-java 2.0.0`（已有 Session/State/Checkpoint；新增扩展的可用性与兼容性由 S01 复核）<br>
> 计划粒度：保留 14 个父工作包 ID（12 主线 + Q00 工程债收口 + E01 可选）；大包按 §6.1 切片领取，父包依赖表示完整验收前置<br>
> 移交来源（2026-09-30）：[M9b-Q02 Release Gate](../testing/M9b-Q02-Release-Gate.md) 移交 5 项（§3 缺陷 8/23/24、缺陷 27 系统性遗留、§3.1 陈旧 dispatch 注记）并入 `M10-Q00`，明细见 §10.12<br>
> 计划更新：2026-10-01；仅调整计划，不表示能力已实现。共用 [M10–M12 计划规则](README.md#9-m10m12-计划规则)<br>
> 阅读顺序：§4 核心合同 → §5–7 依赖与分步交付 → §8 验收 → [§10 实施细则](#10-实施细则防偏差基线)。2026-09-19 二轮 Review 修正了数据供给、记忆循环依赖、动态 Skill 兼容及前后端责任遗漏。

## 1. 目标

M9 提供体验基础，[M9b](M9b-核心流程与使用体验收口.md) 收口真实使用路径与必要后端逻辑，M10 解决"越用越值钱"。M10 调研可提前，正式验收依赖 M9b；复用其模型目录、构建配置与统一工作区，不再引入并行配置入口。

当前已有 Conversation Session、AgentState、Checkpoint 和恢复机制，并非“执行完全无状态”。M10 补的是受治理的跨任务知识、仓库索引、有界辅助记忆与 Skill 复用；不替换既有恢复状态，也不另建执行内核。团队显式知识属于 Team，个人偏好记忆仍须定义成员归属与访问边界。

默认继续使用四服务 Compose（PostgreSQL、Redis、API 内含 Worker、Web）和源码构建，`quickstart.sh up` 不变；不增加强制 TLS、Digest、外部 Secret、监控栈或独立向量数据库。操作以 [单机运维手册](../runbooks/Team-Beta单机运维手册.md) 为准。

M10 完成后：

- 启用检索后，Agent 执行 Coding 任务前获取授权范围内、匹配目标提交的仓库片段与团队规则，并留下注入证据；
- 团队的显式约定（代码规范、架构决策、领域术语）成为可维护、可审计、可继承的 Team 知识资产；
- 反复出现的执行套路可以沉淀为 Skill，被后续任务复用而不是每次重新写 Prompt；
- 可选 E01 启用后，复杂任务可由多个专职 Agent 分段协作；这不是主线完成条件；
- 团队能看清每个 Agent、每个模型、每个任务的成本与质量，并据此调整配置。

## 2. 出口结果

1. **Team Knowledge Base** 落地：Team 范围内的知识条目具备来源、版本、生效范围、责任人与审计；
2. **代码库 RAG** 落地：仓库索引随受管 Mirror 增量更新，检索结果可溯源到具体文件与提交；
3. **有界辅助记忆** 落地：复用既有 Conversation/AgentState/Checkpoint，仅补有来源、可查看/清除的成员偏好；Team 长期约定属于知识库，不再复制一套“Team Memory”；
4. **Skill 沉淀闭环**：从成功执行中提炼 Skill，经人工确认后进入 Team Skill 目录并可被后续任务引用；
5. **多 Agent 协同（可选 E01）**：独立立项、验证和启用，不阻塞单 Agent 知识闭环；
6. **成本与质量可观测**：Token、时长、重试、成功率、Review 通过率按 Agent/模型/任务类型成表；
7. 上述全部能力继承既有 Ownership × ExecutionScope 交集模型，新增固定攻击集验证知识与记忆不跨越 Team 边界。

## 3. 范围与非目标

### 3.1 本期范围

- 在 `crewscope-agentscope` 中按 S01 证据选用 `memory`、`extensions-rag`、`extensions-skills` 等能力；不把引入全部扩展库当成完成标准；
- 知识与记忆的权威事实落在 CrewScope 自己的 PostgreSQL，AgentScope 扩展作为检索与组装能力使用，不成为第二套权威状态；
- 向量存储优先使用 PostgreSQL `pgvector`，不引入独立向量数据库；
- 索引与嵌入在 Worker 侧执行，复用既有 Lease/Fencing/Outbox/Checkpoint 耐久语义；
- 可选 E01 多 Agent 协同复用既有 TaskExecution 状态机与 Human Gate，不新建并行的执行内核。

### 3.2 非目标

- 跨 Team、跨 Organization 的知识共享（另立需求，不预设由 M12 承接）；
- MCP 接入由 M11 可选范围承接；A2A 与其他外部 Agent 互联另立需求，不默认计入 M11；
- 自动模型路由与模型切换（观测先行，另立需求；不等于检索失败时的无知识降级）；
- 模型微调、私有模型训练、全语言 AST/调用图、全历史提交索引、任意文档爬取与自动通用长时记忆；
- 自动合并、自动发布与 Autopilot。

## 4. 核心决策

### 4.1 三类数据不是三套记忆系统

| 数据 | 权威与来源 | 查询、更新责任 |
| --- | --- | --- |
| Team Knowledge | 人工维护/人工确认发布的团队约定；版本化权威事实 | A02 管理，I01 派生索引，A01 检索 |
| Repository Index | 受管 Mirror 指定提交的派生视图；可重建 | I01 建索引，A01 按目标提交检索 |
| 辅助记忆 | 当前 Team 内、归属成员/Agent 的有界偏好；不替代会话恢复状态 | I02 管理、按 Scope/所有者/TTL 读取并组装，不要求先进入向量库 |

既有 Conversation、AgentState、Checkpoint 继续承载短期上下文与恢复。新增记忆不叫第二个 ConversationMemory；删除辅助记忆只移除可选上下文，不能破坏执行状态，但不保证模型答案不变。A01 只负责前两类知识检索，I02 再组合辅助记忆，避免“A01 先需要 I02 的记忆、I02 又依赖 A01”的循环。

三类数据不自动互相升级。执行结果、个人记忆或仓库内容提炼为 Team 知识/Skill 时，只能成为草稿，经目标范围披露检查和人工确认后发布；不能借草稿把其他 Team、成员私有内容或凭证传播给更大的受众。

### 4.2 检索、注入、模型声明引用是三个事实

A01 返回候选片段；I02 记录预算裁剪后实际送入模型的片段。两者均携带来源 ID、Scope、不可变版本/提交、内容哈希、Generation 与排序信息；“已注入”不等于模型实际采用。模型若返回引用，只能关联本次注入集合；自由生成的引用不能冒充系统证据。

I02 交付引用查询和“不适用”反馈的后端，F01 只负责呈现与提交。反馈按执行/片段/成员幂等记录，不能改写已经发送的 Prompt 或既有结果；用于质量统计，后续重试/新执行可显式排除对应来源版本。不隐式全局废弃知识，也不自动训练或改写排序。

展示历史证据时重新授权；未授权内容、历史私有片段及已删除来源不通过 Prompt 日志泄漏。不能只展示条目 ID 却丢失真正注入的版本。

### 4.3 记忆不越过既有权限边界

知识检索发生在服务端，检索范围由 `ExecutionScope ∩ Ownership` 决定，与代码执行边界完全一致。Agent 能读到的知识，必须是发起该任务的成员本人有权读到的知识。撤权后正在执行的任务按既有 Fenced 语义处理。

向量检索不得成为权限绕过通道：SQL 查询同时约束授权 Scope、来源有效版本和索引状态，不把全局 Top-K 返回应用后才过滤。ANN 的内部扫描顺序不是授权证明；以真实 SQL、多 Scope 数据、查询预算和对外响应测试验证边界，不承诺所有物理执行计划完全无计时差异。缓存键包含 Scope/授权版本/来源版本，权限变化不能继续命中旧授权结果。

### 4.4 Skill 发布授权与执行授权分开

已有 `CodingSpecialistSkillBundle` 固定只读内置 Skill，`CodingSpecialistFactory` 固定过滤该 Skill 并禁用工作区 Skill。A03 须显式扩展这一接线，不能只做目录 CRUD，也不能打开任意工作区 Skill 扫描。

主线先支持“成员选择已完成执行 → 显式提炼草稿 → 审核发布 → 后续执行按已授权配置使用”，不做所有成功任务自动后台提炼。发布校验目标 Team 的发布权限、内容披露范围、工具声明与版本；此时通常不存在未来任务，不能用“当前 Task Tool Policy”替代发布授权。

每次执行再求交当前 Task Tool Policy/Scope，版本和内容哈希写入快照；命中 Skill 不增加工具权限，调用时继续鉴权和 Human Gate。关闭团队 Skill 时保留 M9 内置只读 Skill；禁止同名覆盖内置包。禁用/撤权在安全点阻止后续使用，普通更新不改写已开始的版本；回滚是激活历史内容的新修订，不修改历史事实。注入检测不是安全边界，拒绝越权最终由工具授权保证。

### 4.5 多 Agent 协同是受控拓扑，不是自由涌现

选做 E01 时，只支持声明式的固定拓扑（Planner → Coder → Reviewer，可配置启用项），不支持 Agent 自由创建子 Agent。每个环节的产出是结构化的、可检查的、可中断的，且整体仍是一个 TaskExecution，保持现有的恢复与对账语义。

### 4.6 观测先行于优化

F03 交付度量，不交付自动模型路由、自动调优或 M12 的硬配额系统。预算提醒是软提醒；索引/检索的单次 Token、时间、并发和存储上界由 I01/A01 实际执行。

S01 盘点既有 TaskExecution Token 事件、模型价格、Review 结果能否支撑聚合；A02/I01/I02/A03 在产生事实的环节补缺失证据，F03 做可重建投影，不从 Prometheus 反推精确账目。嵌入、草稿提炼、执行调用分列，按稳定调用/尝试 ID 去重；真实重试发生的用量仍计入。价格版本与币种明确，缺价格/缺用量标未知，不按零费用处理。

检索命中、实际注入、成员反馈、Skill 加载和模型声明引用分别计数，不混称“有效使用率”。成功率按执行尝试/任务口径明确分母；Review 一次通过率只统计进入 Review 的样本，不能作为知识带来因果提升的证明。

### 4.7 双来源索引与版本一致性

I01 同时承接仓库内容和 A02 发布的 Team 知识，而非只建仓库索引。知识创建/更新产生索引请求；废弃、删除、Scope 收窄先在权威查询中失效，再异步清理。旧事件重放不得复活旧版本，源内容保存成功与“已可检索”是两种状态。

仓库索引键至少包含 Organization/Team/RepositoryBinding、commit、分片策略和嵌入模型修订。物理 Mirror 可以复用，索引授权不能仅以全局 RepositoryKey 划分。新 Generation 构建完成后原子激活，失败保留旧代；只查询匹配任务目标提交的可用代，无匹配时显式缺失/降级，不能静默拿默认分支最新内容充当当前代码。重命名、删除、force-push、重复事件、解绑与重建须验证。

主线做有界文本分片与路径/语言/行号；符号、Doc 注释和提交摘要仅在 S01 确认支持范围后加入，不承诺全语言解析或全 Git 历史。嵌入输入遵守已有 ModelConnection 凭证、出站和数据策略；实现独立 embedding 能力校验/适配，不能假设现有 chat-completions 客户端可直接生成向量。

### 4.8 记忆和外部发送须可管理

I02 在现有 Agent 设置中提供辅助记忆的查看、清除与启停后端，F01 接入 UI，不新建独立菜单。键至少含组织、Team、成员、Agent 和策略版本；冻结 TTL、容量、允许内容与来源。成员离开/撤权后立即不可读取，物理清理可异步；清除使用代际/版本保护，防止在途写入把已清空内容恢复。自动写入仅限已冻结的偏好结构，不保留任意原始会话副本。

索引和草稿提炼会向模型 Provider 发送内容；必须在调用前验证来源访问权及允许的 Provider/数据保留策略。排除密钥文件和受限路径只是辅助手段，不是“零秘密”的保证；禁止把凭证或未经授权的源码发送给嵌入服务。

## 5. 依赖顺序

以下为父工作包完整关闭的依赖，多前置为 AND；切片可按 §6.1 提前推进，不用 Stub 代替父包最终验收。

```text
M9b-Q02 -> M10-S01 -> M10-D01
M9b-Q02 -> M10-Q00
M10-D01 -> M10-A02
M10-D01,M10-A02 -> M10-I01
M10-I01,M10-A02 -> M10-A01 -> M10-I02
M10-I02 -> M10-A03
M10-I01,M10-A02,M10-I02 -> M10-F01
M10-A03 -> M10-F02
M10-I01,M10-I02,M10-A03 -> M10-F03
M10-Q00,M10-F01,M10-F02,M10-F03 -> M10-Q01 -> M10-Q02

可选：M10-S01,M10-I02 -> M10-E01
选做 E01 时：E01 + F02 拓扑增量 + F03 分段观测增量 -> Q01/Q02 扩展验收
```

`M10-Q00` 只依赖 `M9b-Q02`，与主线全部包并行，不互相阻塞；它唯一的主线约束是在 `M10-Q01` 汇总验收前关闭。缺陷 24 若晚于 F03a 交付，F03 的成功率口径须先按「命令超时被错误升级的执行失败」显式分列，不得直接并入模型质量分母。

## 6. 执行任务

| ID | 类型 | 依赖 | 涉及模块 | 完整交付结果 | 主要验收 |
| --- | --- | --- | --- | --- | --- |
| `M10-S01` | SPIKE | M9b-Q02 | all/product/docs | 冻结 §4 的来源、授权、Provider、版本、记忆、Skill、证据和预算合同；复核 M9b 模型/构建/工作区合同；最小真实嵌入/恢复原型；接受 ADR-030 及 ADR-031 Skill 部分，拓扑独立选入 | §10.11 主线问题有决定与证据；按实测样本估算目标规模，10 万文件不当成所有机器硬规格；不把“库可加载”当成接入成功 |
| `M10-D01` | FEATURE | S01 | domain/application/infrastructure/docs | 公共地基：Scope/来源版本/Generation、知识条目与事件、端口及证据 Schema 合同；基础普通 PostgreSQL 迁移。冻结后续包数据归属，不一次性包办所有实现 | 权限、版本并发与来源归属合同测试；纯 PostgreSQL 安装升级；领域零框架。索引、记忆、Skill、统计增量迁移随责任包交付 |
| `M10-A02` | FEATURE | D01 | application/agentscope/server/docs | 知识创建、版本、分类、生效范围、废弃/删除及来源提炼草稿；强 ETag、幂等、Audit、索引变更事件与失效协议，提炼调用生成用量证据。UI 归 F01 | Scope/披露检查、并发冲突、重复命令、超长输入明确拒绝；不用静默截断改写权威正文；无向量服务仍可管理，索引状态与保存状态区分 |
| `M10-I01` | FEATURE | D01、A02 | application/infrastructure/agentscope/server/docs | 双来源索引、embedding 适配、独立向量迁移与启用路径、索引状态/重建/取消 API；持久作业的 Claim/续租/Fencing/进度/对账，运行于现有 API 内 Worker | 知识更新/废弃、提交/Generation 切换、解绑与并发旧 Worker 不能污染结果；限额、Provider 数据策略、向量维度/非有限值校验、重启恢复及两种安装路径通过 |
| `M10-A01` | FEATURE | I01、A02 | application/server/docs | 统一检索 Team 知识与仓库片段；授权 SQL、有效版本、候选去重/排序/预算；内部端口必交付，预览 HTTP 由 S01 决定且只留一个入口 | 固定数据/模型/配置下排序规则可解释，ANN 波动按冻结容差评估；Recall@K、无答案、版本匹配、P95/资源上界、权限与公开字段测试。辅助记忆不作为此包前置 |
| `M10-I02` | FEATURE | A01 | application/infrastructure/agentscope/server/docs | 有界辅助记忆管理；检索与记忆的 Prompt 组装/预算；调用前持久化注入清单、恢复关联；引用查询/幂等反馈 API、记忆查看/清除 API；开关与降级 | 无循环数据依赖；注入证据与实际片段一致；模型声明引用不越出清单；恢复/重试不丢证据或重计事件；清除不复活、撤权生效、反馈不改写历史；可选增强不可用不自动终止业务 |
| `M10-A03` | FEATURE | I02 | domain/application/infrastructure/agentscope/server/docs | 显式选择已完成执行提炼 Skill；草稿/审核/发布/禁用/版本回滚；扩展固定 Skill Factory 接线；版本/内容哈希和加载证据、运行时工具复验 | 发布与执行授权分开；未批准不加载、撤权不使用、同名不覆盖内置包；内置 Skill 回归；恶意内容不能突破 Tool Policy/Human Gate；真实后续任务加载指定发布版本 |
| `M10-E01` | FEATURE，可选 | S01、I02 | agentscope/application/domain/docs | 固定 Planner/Coder/Reviewer 拓扑；单一 TaskExecution、段间契约、版本化 Checkpoint、总预算及分段证据；ADR-031 拓扑部分另接受 | 段内/段间恢复、中断、撤权与副作用对账的包内测试通过；提供 F02/F03 增量契约后关闭代码包，扩展发布仍须后续 Q01/Q02 验收；未选入不阻塞主线 |
| `M10-F01` | FEATURE | I01、A02、I02 | web/docs | 知识管理、索引状态、执行注入证据与反馈；在现有 Agent 设置接入记忆查看/清除；按 §6.1 分批交付 | 真实 API 对接、草稿保留/ETag 冲突、历史授权/删除墓碑、反馈/清除闭环；六态、390px、键盘、Axe；无授权时不暴露隐私或责任人信息 |
| `M10-F02` | FEATURE | A03；拓扑增量另依赖 E01 | web/docs | Skill 目录、草稿 Diff、审核、发布/禁用/回滚、历史证据；可选拓扑配置和分段 Timeline | 内置/团队 Skill 来源明确；权限、版本、禁用原因及配置快照可见；未选拓扑无虚假入口，Skill 主线可独立使用 |
| `M10-F03` | FEATURE | I01、I02、A03；分段维度另依赖 E01 | application/infrastructure/server/web/docs | §4.6 用量证据的可重建聚合与查询、成本质量 UI、软预算提醒；按来源分列嵌入/提炼/执行成本 | 事件重放幂等、真实重试计量、缺失/币种/价格版本/月份边界、跨 Team 授权；提醒复用 Inbox/飞书并去重，通知失败不影响执行；不依赖监控栈 |
| `M10-Q00` | HARDENING | M9b-Q02 | all/docs | M9b-Q02 移交 5 项工程债的根修与回归（明细见 §10.12）：重启窗口执行竞态、约束冲突错误语义、命令超时证据与升级、跨类条件装配审计、静默分支可观测；不新增数据库迁移 | api 重启窗口恢复不绕轮不冲突；重复默认绑定得 409 可操作信息而非 500；取消/超时命令证据必落库且命令超时收敛为命令结果不升级执行；`@ConditionalOnBean` 同族缺席有全库审计结论并修复；静默 continue 分支留 secret-free WARN 或计数 |
| `M10-Q01` | HARDENING | F01、F02、F03；另含 Q00 | all/ci | 汇总各包攻击/故障测试，补跨包组合、质量评测与安装/升级/恢复矩阵；不在此包才实现遗漏的 API 或作业 | 固定攻击中越权/泄漏/副作用被阻断；故障按类别失败关闭、重试或显式降级；质量阈值按 S01 冻结，既有全量门禁（含 M9b-Q01 回归矩阵与 M9b-Q02 真实栈 gate）零回归 |
| `M10-Q02` | HARDENING | Q01 | all/release | 真实 PostgreSQL/Redis、嵌入与生成模型完成“知识/仓库入库→检索→注入→Review→发布 Skill→第二次执行加载 Skill”，另验普通 PostgreSQL 关闭态；产出发布证据 | 注入来源/版本和已发布 Skill 的复用都须验证，不能用“知识或 Skill 任一命中”代替闭环；真实环境、质量对照、成本/延迟和恢复有记录；可选拓扑单独结论 |

### 6.1 大包切片与关闭口径

保留父 ID 便于追踪；下列 `a/b/c` 是父包内 Checklist 标识，不新增平行里程碑。每片有负责人、合同测试和可演示结果；拆分后原则上控制在 3–10 个工作日，超过则在 S01 后继续按独立风险拆，不按文件拆。父包只有全部主线切片通过才关闭。

| 切片 | 可开始的前置 | 独立出口 |
| --- | --- | --- |
| S01a / S01b | M9b-Q02；S01a 后做 S01b | a 盘点/合同与评测样本；b 真实 embedding、授权查询和恢复最小原型；两片完成才关闭 S01 |
| D01a / D01b | S01；D01a 后做 D01b | a 公共模型/端口合同；b 知识基础迁移与并发/权限测试；不等待全部功能表 |
| A02a / A02b | D01；A02a 后做 A02b | a 人工录入管理/变更事件；b 来源提炼草稿与披露检查。AI 提炼需预算及 Provider 策略，不依赖未来 Skill 管理 |
| I01a / I01b / I01c | a：D01；b：I01a、A02；c：I01b | a embedding/可选存储；b 双来源分片和持久作业；c 版本激活、控制 API、故障恢复及启用 Runbook |
| A01 | I01、A02 | 两类知识统一检索与离线质量门禁 |
| I02a / I02b / I02c | a：D01；b：I02a、A01；c：I02b | a 辅助记忆生命周期/API；b Prompt 注入、预算与耐久证据；c 引用反馈/API、恢复及开关组合；A01 不依赖 I02a |
| A03a / A03b | a：D01；b：A03a、I02 | a Skill 目录/审批版本合同；b 真实提炼、Factory 接线与运行证据；提前做目录不代表闭环已交付 |
| F01a / F01b / F01c | a：A02；b：I01；c：I02 | a 知识管理；b 索引状态/控制；c 引用反馈和记忆管理，不把所有 UI 拖到最后 |
| F02 主线 | A03 | Skill 管理和后续执行配置闭环 |
| F03a / F03b | a：D01；b：F03a、I01、I02、A03 | a 既有用量盘点/聚合合同与样本；b 接齐新增证据、查询/UI 和提醒，全指标完成后关闭 |
| E01a / E01b，可选 | a：S01、I02 及拓扑合同；b：E01a | a 单执行固定拓扑；b 恢复、预算、撤权与副作用包内测试；随后交付 F02/F03 增量及 Q01/Q02 扩展验收，不把发布结果反设为 E01 前置 |

模块内安全/恢复测试随切片交付，Q01 做集成收口而非最后一次“大补测试”。表外小包维持完整交付，不为每个接口追加 ID。

## 7. 建议执行波次

| 波次 | 任务/切片 | 可演示结果 |
| --- | --- | --- |
| W1 合同与地基 | S01、D01 | 可执行合同与普通 PostgreSQL 基础可用 |
| W2 知识与供给 | A02、F01a；I01a 可并行 | 无向量也能管理知识；嵌入/可选存储原型可用 |
| W3 索引与检索 | I01b/c、F01b、A01；I02a/A03a/F03a 可并行 | 两种来源按版本检索，索引状态可见 |
| W4 注入与复用 | I02b/c、F01c、A03b、F02 | 注入可追溯、记忆可清除、Skill 可发布复用 |
| W5 观测与发布 | F03b、Q01、Q02 | 真实闭环、质量对照和升级恢复齐备 |

可选 E01 独立推进；其 F02/F03 增量不反向拖住主线。`M10-Q00` 不占波次关键路径，可与 W1 起任一波次并行清偿（建议 W1–W2 窗口内完成缺陷 24，避免其假执行失败混入 F03 的观测口径），只须在 Q01 前关闭。波次是交付顺序，不是固定周数。F01a 可先于索引能力演示，但必须依赖 A02 真实 API，不用 Mock 宣称已完成。M9-Q02 未完成的技术验证由 M9b-Q02 在新版本补证，提前研究不等于获准跨过 M9b 前置门禁。

## 8. M10 Release Gate

1. 普通 PostgreSQL、增强关闭：四服务源码构建/HTTP 新装、存量升级、恢复及 M0–M9（含 M9b）回归通过；知识管理与内置 Skill 保持可用。
2. pgvector、增强开启：真实嵌入和生成模型完成双来源索引、授权检索、注入证据、Review、Skill 发布和第二次任务加载；索引源版本与实际 Prompt 片段一致。
3. 数据失效：知识废弃/删改、仓库提交变化/解绑、成员撤权、辅助记忆清除及 Skill 禁用不被旧事件/缓存/Worker 恢复。
4. 恢复一致：索引半成品不激活；过期 Claim 不能提交；调用/证据关联可恢复，重放不重复统计，真实重试有独立记录。
5. 所有新增查询/命令均覆盖权限、预算、幂等/版本冲突、公开字段和必要 Audit；UI 六态、390px、键盘与 Axe 通过。
6. 安全固定集验证 Prompt/Skill 恶意内容不能提升权限、读取未授权来源或越过 Human Gate；不能宣称文字过滤能识别所有注入。
7. 检索质量独立验收：S01 冻结标注集、K、Recall@K/无答案误召回、来源版本准确性与阈值；包含相似但无权限、过期、无答案、中英文及支持的代码语言。不得在 Q02 临时降低阈值。
8. 真实任务对照：同一任务集/模型/预算下记录关闭检索与开启检索的正确性、Review、Token、延迟；重复次数及可接受回归范围预先冻结，不以“有引用”证明模型更准确，不要求每次生成完全相同。
9. 记忆、Skill、索引、检索的独立关闭及有效组合均测试；不可用增强显式降级，权限/业务准入失败不可伪装成降级继续。
10. F03 覆盖嵌入/草稿提炼/执行成本与质量证据，缺失标未知；月度提醒不是硬配额，不依赖 M12 或外部监控服务。
11. 主线与 E01 单独记录；选做时追加段间恢复、权限、预算、F02/F03 增量和真实拓扑验收。
12. `docs/testing/M10-Q02-Release-Gate.md` 记录 Revision、配置、模型版本、硬件、文件/片段规模、样本、命令、结果与脱敏证据；未执行标待执行，Mock/静态检查不能替代真实模型。

pgvector 是用户显式安装的运行增强，但支持它是 M10 主线的交付与测试责任；不能用“可选安装”跳过索引/检索闭环验收。普通用户仍无需安装它才能启动或使用基础功能。

## 9. 任务规模约定

14 个父包适合作为路线图，不都适合整包领取：D01 跨领域/应用/持久化，改为 FEATURE；I01、I02、A03、F01、F03 按 §6.1 分步验收，避免把记忆、反馈 API、索引控制、用量事实等藏进“前端”或“质量”包；Q00 是五项相互独立的移交缺陷，不切片、不新建里程碑，逐项领取即可。

运行增强默认关闭，不代表功能不交付。S01 冻结主线合同后按需评审增量，不把全部未来表提前塞进 D01，也不因某可选拓扑未定而挡住主线。

## 10. 实施细则（防偏差基线）

本节是实施约束，不是完成记录。规划类名/文件名在领取任务时复核，允许在合同不变的前提下调整落点；禁止用过时目录、固定菜单数或预分配迁移号绑死实现。

### 10.1 已有代码与需要补的能力

| 已有基线 | 对 M10 的约束 |
| --- | --- |
| [CodingSpecialistSkillBundle](../../crewscope-agentscope/src/main/java/io/crewscope/agentscope/coding/CodingSpecialistSkillBundle.java) 与 [CodingSpecialistFactory](../../crewscope-agentscope/src/main/java/io/crewscope/agentscope/coding/CodingSpecialistFactory.java) | 已有固定只读 Skill、哈希校验和固定过滤；A03 必须实现受控动态接线并保留内置路径，不能只增加目录表 |
| [AgentMemoryPolicyReference](../../crewscope-domain/src/main/java/io/crewscope/domain/agent/AgentMemoryPolicyReference.java) | 已有配置引用，先核对其消费者；I02 补策略解析与有界存储，不复制既有会话/恢复体系 |
| [GitHubRepositoryImportWorker](../../crewscope-application/src/main/java/io/crewscope/application/github/GitHubRepositoryImportWorker.java) 与 V34–V36 | 是导入专用持久作业，不是现成的通用索引 Checkpoint 引擎；借用 Claim/续租/授权模式，索引分批进度与 Fencing 提交由 I01 明确实现 |
| [ResolvedAgentScopeModelFactory](../../crewscope-agentscope/src/main/java/io/crewscope/agentscope/model/ResolvedAgentScopeModelFactory.java) | 当前解析路径使用 chat-completions；I01 要验证 embedding 能力与适配，复用连接/凭证治理不等于复用聊天协议 |
| [TaskExecutionEventPayload](../../crewscope-application/src/main/java/io/crewscope/application/execution/TaskExecutionEventPayload.java) 与 [OperationalTelemetry](../../crewscope-application/src/main/java/io/crewscope/application/observability/OperationalTelemetry.java) | 已有 Token 事件与低基数遥测；S01 核对保留/查询口径，F03 不假定已有完整可重建成本账本 |
| [M9-Q02 发布记录](../testing/M9-Q02-Release-Gate.md) | 本机检查与真实环境验收分开；前置门禁未完成部分不能写成通过 |

红线：领域零框架；既有公开 API/事件版本和耐久语义保持兼容；新增上下文不能提升权限或绕过 Human Gate；旧迁移不修改；关闭增强不破坏已有产品。

### 10.2 pgvector 的安装、迁移与退路

当前根目录与 Team Beta Compose 都使用不含 pgvector 的 `postgres:17-alpine`。I01 负责可选启用路径，不增加第五个默认服务：

1. 默认 Flyway 扫描不得包含 `CREATE EXTENSION vector` 或 `vector(n)` 列；纯 PostgreSQL 独立安装升级。
2. vector 扩展、向量表/索引使用显式安装的独立位置与 Schema history；I01 验证默认与扩展迁移的先后顺序及外键兼容。安装状态与检索开关分离，已安装后即使关闭检索也继续校验/升级其历史。
3. 为同一个 DB 服务提供已验证的可选镜像/覆盖配置，根 Compose 与 Team Beta 均覆盖；不强制 Digest，不引入独立向量数据库。
4. 切换镜像前备份并验证 PostgreSQL、扩展、排序规则与数据兼容，不假设 Alpine/Debian 可原地复用卷；必要时逻辑备份/恢复。索引可重建，权威知识、配置密钥与迁移历史不可丢失。
5. 未安装时普通启动成功，启用检索则给出可操作原因；已安装历史损坏/不兼容不能被“降级”吞掉。临时 Provider/检索超时按运行协议处理，不卸载扩展或忽略 Flyway 校验。
6. 同维度不同 embedding 模型也不能混用向量；模型/维度/分片策略变更通过新 Generation 重建、验证、切换，回退保留兼容旧代，不修改旧迁移。
7. 在现有 [单机运维手册](../runbooks/Team-Beta单机运维手册.md) 给出最短启用、关闭、升级和恢复步骤；普通用户仍只需原有启动入口。全文检索回退不列为额外交付要求，若另选须独立评测。

### 10.3 AgentScope 扩展接入

S01 复核当前 BOM 下 `memory`、`extensions-rag`、`extensions-skills`、`extensions-mem` 的真实 API、序列化与中断行为。优先用已存在能力，扩展模块按缺口选择，不引入第二套 Memory/Vector 权威存储。

依赖统一走 BOM，新增第三方依赖只进入适配层；domain 零框架、application 只依赖自有端口。不 fork 或复制源码；知识/记忆的适配取舍记 ADR-030，Skill/拓扑记 ADR-031。新包按职责使用 `agentscope.knowledge`、可选 `agentscope.topology`，不新造与现有 ActionDelivery 混淆的执行编排内核。

### 10.4 持久化责任与迁移

当前主迁移到 V51（M9b-Q02 期间合并 V49–V51：沙箱网络三档、policy snapshot 唯一约束、REVIEW session）；未来按合并时最大值 +1 分配。D01 冻结跨包合同和知识基础结构，以下责任包交付自身持久化及测试，避免 D01 成为“先完成所有功能数据库”的大包：

| 主题 | 交付责任 | 路径与验收 |
| --- | --- | --- |
| 公共 Scope/来源版本、知识条目/版本/事件 | D01，A02 完成命令与失效协议 | 普通 PostgreSQL；ETag、幂等、目标受众与授权 |
| 索引作业、片段、进度/租约、Generation | I01 | 普通类型元数据与可选向量结构分离；重复事件/旧 Claim/激活原子性 |
| 辅助记忆、清除代际、注入证据/反馈 | I02 | 普通 PostgreSQL；TTL、授权、并发清除、调用/恢复关联 |
| Skill 草稿、审批、不可变发布版本和加载证据 | A03 | 普通 PostgreSQL；目录幂等、披露范围、运行快照 |
| 用量事实缺口与聚合投影 | A02/I01/I02/A03 产生，F03 聚合 | 稳定调用 ID、重放去重、历史保留/重建；避免重复保存执行权威状态 |

一个工作包可有多个迁移；发现模型缺口回到合同评审并追加迁移，不禁止后续包补必要表。回退区分兼容旧代码、数据修复和完整备份恢复，不承诺任意 down SQL 可逆。

### 10.5 后端责任与接口落点

| 工作包 | 主要落点与边界 |
| --- | --- |
| D01/A02 | `domain/knowledge`、`application/knowledge`、`persistence/knowledge`；唯一知识管理 Controller，前端归 F01 |
| I01 | application 定义索引用例/作业端口；infrastructure 实现受管 Mirror、作业仓储和向量持久化；agentscope 实现 embedding 适配；server 提供状态、重建、取消 API |
| A01 | `KnowledgeRetrievalQuery` / `KnowledgeAccessPolicy` / `KnowledgeIndexPort`；服务端派生 Scope，不接受前端任意扩大检索范围 |
| I02 | application 定义辅助记忆、注入证据、反馈端口；agentscope 执行 Prompt 组装；server 提供引用/反馈及记忆查看/清除 API。不能只交付运行时注入而缺管理后端 |
| A03 | `domain/skill`、`application/skill`、`persistence/skill`；目录/审批 Controller 与既有 Coding Factory 动态接线 |
| F03 | 扩展既有 `application/observability`，新增必要投影仓储和成本查询 Controller；不平行建 `application/cost` 与 `application/metrics` |
| E01，可选 | `agentscope.topology` 与既有 `application/execution`；不另建 Task 生命周期 |

DTO、错误码、分页、幂等回执、强 ETag、权限/动作可用性沿用既有约定；有公开 HTTP 变更则同步 OpenAPI/前端生成类型，不能在 Q01 才补契约。代码路径是建议落点，不是必须照抄的一组空类。

### 10.6 前端按垂直切片接入

预计四个页面：知识库、索引状态、Skill 目录、成本质量；引用面板嵌入执行详情，辅助记忆嵌入既有 Agent 设置，拓扑仅选做时加入。对应数据层复用 `src/domains/*/{gateway,store,types,labels}.ts` 约定，不手抄后端 DTO。

F01a、F01b、F01c 分别跟随 A02、I01、I02 真接口；A02 不重复实现另一套知识前端。知识内容支持草稿保留与版本冲突回读；清除记忆/发布 Skill/废弃知识等动作说明影响并确认。检索“已注入”和模型声明引用分开标记；点击历史来源仍重新授权。

新入口同步实际 router、共享导航、标题/权限、390px 可达性与 StatePanel 六态，不锁定菜单数。内置 Skill 与 Team Skill 明确来源；没有能力/权限时给可操作提示，不暴露无权查看的责任人或资源名称。

### 10.7 开关与有效组合

以下为计划开关；有效配置由部署上限与授权 Team/Agent 配置共同决定，UI 不能修改其他 Team 或全局环境配置。启用条件和原因统一由后端 Readiness 提供，不要求用户手动猜多组开关。

| 开关 | 关闭语义 |
| --- | --- |
| `crewscope.knowledge.retrieval.enabled` | 不注入知识检索片段；知识管理仍可用。记忆/Skill 若单独启用仍按自身规则工作，不承诺所有 Prompt 与 M9 相同 |
| `crewscope.knowledge.index.enabled` | 停止接收新建/刷新作业，在途按安全点协议处理；已发布且版本匹配的索引可继续检索，来源失效和清理不停止 |
| `crewscope.memory.enabled` | 不向模型读写新增辅助记忆；授权查看/清除和 TTL 清理仍可用，既有 Session/Checkpoint 不变 |
| `crewscope.skill.enabled` | 不提炼或执行新增 Team Skill；历史目录/证据可在授权下查看，既有内置只读 Skill 保持工作 |
| `crewscope.topology.multi-agent.enabled` | 使用单 Agent；知识、记忆与 Skill 主线不受影响 |

必测组合：全部关闭；仅管理；索引开/检索关；索引关但有有效索引/检索开；memory 关而知识开；检索关而 Team Skill 开；已安装 vector 后关闭增强再升级；可选拓扑开/关。无有效索引或 Provider 时明确降级原因，不偷偷开启其他服务。

配置版本固定于执行/阶段边界；普通更新不改写在途快照。权限撤销独立生效，下一次外部发送/工具调用前重新校验；无法安全移除已撤权上下文时中止或重建执行上下文，不能继续向 Provider 发送已缓存的私有片段。S01 冻结开关关闭的排空/暂停语义及时间上限；全部增强关闭才要求 M9 行为兼容。

### 10.8 测试责任与评测证据

| 风险 | 随功能包交付 | Q01/Q02 汇总验收 |
| --- | --- | --- |
| 来源越权与外部泄漏 | A02/I01/A01：跨 Team、同仓库不同 Binding、草稿扩大受众、受限文件、Provider 数据策略 | 缓存/异步作业/历史引用多路径不可绕过 |
| 索引一致性 | I01：重复/乱序事件、知识废弃、提交变化、删改/force-push、维度异常、续租失败、双 Worker | 中断/重启/升级后无混代、旧 Claim 不可发布 |
| 检索质量与预算 | A01：标注集、Recall@K、无答案、版本、P95、超时/Top-K 上限 | 真实 embedding 与固定样本；权限过滤后无结果不扩 Scope |
| 记忆与引用 | I02：TTL、清除并发、防复活、引用子集、幂等反馈、调用前证据/恢复关联 | 开关组合、撤权后历史展示及实际 Prompt 一致 |
| Skill | A03：内置兼容、发布/运行二次授权、版本/hash、注入/越权工具、提炼预算 | 真实第二次任务加载已发布版本，不仅目录可见 |
| 成本与质量 | F03：来源事件缺失、重放、重试、价格修订、币种/时区、提醒去重 | 聚合重建和真实调用样本对账；未知值不变零 |
| 部署与体验 | I01/F01/F02/F03：双安装路径、迁移、真实 API、生成类型、移动/键盘/Axe | 四服务新装/升级/恢复及既有全量回归 |

Q01 验证固定集内的安全边界，不承诺拦截所有自然语言注入或所有计时侧信道；模型评测失败和系统授权失败分别记录。模型调用失败也须有调用尝试与证据状态，不能把“准备注入”当成“Provider 已接收”，更不能把记录存在当成业务成功。

计划 E2E：`m10-knowledge-base.spec.ts`、`m10-knowledge-index.spec.ts`、`m10-retrieval-citation.spec.ts`、`m10-memory-controls.spec.ts`、`m10-skill-review.spec.ts`、`m10-agent-cost.spec.ts`、`m10-feature-flags-off.spec.ts`。移动覆盖扩写共享清单，不再复制一套导航事实。计划发布脚本为 `scripts/m10-release-gate.sh`；这些文件目前不是已完成证据。

### 10.9 待交付文档与契约

ADR 编号沿用 [唯一映射](README.md#adr-规划编号的唯一映射)：

- `docs/adr/ADR-030-知识与记忆三层模型.md`：双来源/辅助记忆边界、授权、Generation、Provider、向量迁移、预算、质量与恢复。
- `docs/adr/ADR-031-Skill沉淀与多Agent协同拓扑.md`：内置/动态 Skill 共存、审批/披露、运行授权与版本；可选拓扑另接受。

API/端口契约：

| 计划路径 | 负责人 | 额外明确 |
| --- | --- | --- |
| `docs/api/M10-知识库API契约.md` | A02 | 来源披露、版本、生效/废弃与异步索引状态 |
| `docs/api/M10-仓库索引API契约.md` | I01 | 同时写明 Team 知识索引状态与统一作业模型，不因标题遗漏第二类来源 |
| `docs/api/M10-知识检索API契约.md` | A01 | 若仅内部调用，文件头标内部端口；范围、版本、预算和降级 |
| `docs/api/M10-执行引用与反馈API契约.md` | I02 | 候选/注入/声明引用区分、历史授权、幂等反馈及后续排除语义 |
| `docs/api/M10-辅助记忆API契约.md` | I02 | 所有者/Scope、查看/清除、TTL、代际及关闭行为 |
| `docs/api/M10-Skill目录与审核API契约.md` | A03 | 发布与执行授权、内置兼容、版本禁用/回滚 |
| `docs/api/M10-成本与质量观测API契约.md` | F03 | 指标分母、来源/去重、未知值、价格修订、提醒而非硬配额 |

每份公开契约沿用 M9 的权限、错误、并发、幂等、字段白名单等约定。I01 更新现有 Runbook，其他包把自己的开关/恢复要求交给同一入口，不新建平行运维目录。未来文档名不代表已创建或接受。

### 10.10 本轮 Review 的修正与防回退

| 原问题 | 本计划的修正 |
| --- | --- |
| D01 标 TASK 却承包多领域全部持久化 | 改 FEATURE，仅公共地基；迁移随责任功能包交付 |
| I01 只索引仓库，Team 知识无入库责任 | 明确双来源；I01 完整验收依赖 A02，存储适配切片可先行 |
| A01 检索尚未交付的记忆，形成隐性循环 | A01 检索显式知识/仓库；I02 独立读取辅助记忆再组装 |
| 引用反馈/记忆清除只有 UI 描述 | I02 负责后端/API/持久化，F01c 接真实接口 |
| 目录发布后就默认 Skill 可用 | A03 扩展固定 Factory 接线，发布授权与执行授权分开 |
| F03 无 Skill 前置却验收全套效果 | 增加 I01/I02/A03 证据依赖；明确事实产生与聚合职责 |
| F01 全部等待运行链路，知识管理不能先交付 | 分 F01a/b/c；父包关闭仍覆盖完整交付 |
| 出现引用就算“知识有效” | 区分候选/注入/声明引用，增加离线检索质量与真实任务对照 |
| 复用导入 Worker 被误写成已有通用 Checkpoint | I01 明确实现索引进度、续租、Fencing 与恢复 |
| 关闭 Team Skill 会误伤既有固定 Skill | 关闭仅影响新增能力，内置 Skill 与恢复状态保持兼容 |

### 10.11 S01 的必答项

主线问题须在 S01 出口形成明确合同与验收阈值；拓扑只在选做 E01 时回答，不能拖住主线。

1. 两类来源的 Scope/披露规则、知识生效与失效时机、目标 commit/Generation 的选择与保留。
2. embedding Provider 能力/数据策略、模型版本/维度、批量/限流、向量索引及可选安装迁移方案。选型范围已冻结（2026-09-30）：智谱 `embedding-3` 或阿里 `text-embedding-v4`（均 OpenAI 兼容端点、国内直连），S01 在此范围内实测选定并冻结维度（影响 pgvector 列定义与 Generation 重建策略）；凭据沿用既有 ModelConnection 治理，不得假设现有 chat-completions 连接可直接复用。
3. 文本/语言支持范围、分片/排除规则、索引持久作业 Claim/续租/Fencing/清理及资源上限。
4. 检索 Top-K/排序/去重、Prompt Token 总预算和知识/记忆/Skill 优先级；可选上下文超预算如何裁剪，硬授权/业务预算何时失败关闭。
5. 标注集、Recall@K/无答案/版本准确性阈值、真实任务对照、重复次数，以及硬件/模型/文件数/片段数/并发的测量口径。
6. 辅助记忆所有者、允许内容、TTL/容量、清除防复活与成员撤权；哪些已有 MemoryPolicy/Session 能复用。
7. 注入清单的持久化/调用关联、恢复/重试协议、内容保留/删除和反馈语义；是否提供检索预览 HTTP。
8. Team Skill 提炼、受众审批、固定 Factory 扩展、版本/哈希、每次执行授权及内置兼容。
9. 现有用量事实的缺口与保留/重建策略、价格/币种/分母/重试、软预算提醒渠道和去重。
10. 扩展库兼容性、权限枚举/动作可用性、开关有效组合与 Readiness、各切片负责人/规模/证据入口。扩展库复核的已知输入（M9b-Q02 实测，见其 Release Gate §3 缺陷 10/18）：agentscope 2.0.0 OpenAI 兼容适配器存在孤儿 tool 消息两条产生路径（CrewScope 侧已以发送面 middleware 修复，库内根因未修）、thinking 模式拒绝任何强制 tool_choice、`GenerateOptions.additionalBodyParams` 不透传使 `Builder.httpTransport` 成为唯一请求体定制点——I01 的 embedding 适配与 I02/A03 的 Prompt 组装若需非标准请求体参数都会撞同一堵墙；且内置模型目录唯一 Provider DeepSeek 无 embedding 端点，embedding Provider 的目录条目、凭据与数据策略是 S01 必须回答的现实约束，不得假设现有 chat-completions 连接可直接复用。

### 10.12 M10-Q00：M9b-Q02 移交明细

来源与完整取证见 [M9b-Q02 Release Gate](../testing/M9b-Q02-Release-Gate.md)（§3 缺陷 8/23/24、缺陷 27 系统性遗留段、§3.1 陈旧 dispatch 注记）。五项相互独立、逐项领取；除下述内容外不扩大范围，也不得反向阻塞知识闭环主线。

| # | 缺陷 | 根修方向 | 关闭条件 |
| --- | --- | --- | --- |
| 1 | api 重启窗口内 startup reconciler 与 claim loop 乐观锁竞态（缺陷 8；恢复多绕一轮，无数据损坏） | reconciler 与 claim 对同一执行版本的写路径收敛为单一写者或版本重读重试；语义对齐 I01 的 Claim/Fencing 合同，不重复造第二套机制 | 重启窗口内恢复执行不再触发 `OptimisticLockConflictException` 拒绝或多绕轮；竞态注入测试复现原故障后转绿 |
| 2 | 重复默认 ProviderBinding 撞 `ux_provider_binding_active_default` 唯一约束，以 500 `retryable: true` 泄漏（缺陷 23；客户端按提示重试永不会成功） | API 层把 23505 映射为 409 冲突码，错误体携带可操作信息（先改旧绑定为非默认，或直接换绑）；约束本身不改 | 同请求幂等重放 409；错误体可定位到「默认绑定已存在」；不再返回 retryable=true |
| 3 | 命令超时经线程中断投递：取消/超时证据结构性无法落库（`FilesystemArtifactStore.withFileLock` 的阻塞 `FileChannel.lock()` 遇中断位即抛），且命令级超时升级为执行级死亡（缺陷 24；t4 的实际失败根因，`termination` 全表零 `TIMED_OUT`/`CANCELLED`） | 证据写入走不可中断的锁获取（`lock()` 前保存并暂清中断位，或 `tryLock()` + 有界重试），使「执行正在被取消」与「证据无法记录」解耦；命令级超时收敛为命令结果 `TIMED_OUT` 供模型改窄重试，不向执行层升级 | 取消/超时路径的证据必落库（`TIMED_OUT`/`CANCELLED` 可观测）；命令超时后执行存活并可继续；沙箱控制中断位不再连锁击穿执行 |
| 4 | server 配置引用 infra 定义类型的跨类 `@ConditionalOnBean` 在真实注册序下恒假——action/GitHub 写链已修，同族潜在静默缺席未全库审计（缺陷 27 系统性遗留） | 全库清点跨类 `@ConditionalOnBean`，按前件注册方式判定：构造型直注（`@Component`/`@Repository` 经 `@ComponentScan` 一次注册）评估时可见，配置类 `@Bean`（实测 infra 晚于 server 处理）与 auto-configuration bean（`MeterRegistry`/`Tracer` 在全部用户配置后处理）恒假；恒假者逐个改为属性门/构造注入 fail-fast（对齐已验证的 `WorkerCapableProfileCondition` 范式） | 审计清单留档；恒假前件要么修复要么按属性门显式缺席；无「启动零错误、fleet 静默缺失」的残留 |
| 5 | 陈旧 dispatch 每秒静默重评估零可观测痕迹（§3.1 注记；`ActionWorker.claimOne` 对 `resolveCurrent` 的 `DomainException` 静默 `continue`） | 该分支补 secret-free WARN（含 dispatch id 与拒绝原因码）或计数指标；不改变 READY 等待语义本身 | 长期陈旧行可在日志/指标中定位（含原因码），运维不再需要语句普查才能发现 |

不修第 3 项的连带代价已在 §5 注明：F03 的执行成功率与 Review 通过率分母会持续吸入「命令超时被错误升级」的假执行失败。第 2 项与 A03 的 Skill 审批冲突语义共用「版本冲突 → 409 + 可操作信息」的既有错误约定，实现时对齐 `docs/api/` 既有契约风格，不新造错误码族。

第 4 项全库审计结论（2026-09-30，跨类 9 处高危逐个判定）：

- **恒假并已修复**：`TaskWorkerConfiguration` 的 `codingWorkspaceRuntimeOperationsAdapter`（前件 `CodingWorkspaceRuntimeRegistry`/`CodingWorkspaceStartupReconciler` 为 infra 配置类 `@Bean`）——去前件改构造注入，连带救活 `RuntimeMaintenanceService` 与 `CodingWorkspaceStartupHealthIndicator`；`ActionReconciliationApplicationConfiguration` 的 metrics observer（前件 `MeterRegistry`/`Tracer` 为 auto-config bean，恒 noOp）——改 `ObjectProvider` 注入期解析，双在场才 metrics、否则显式 noOp；`ProjectionSupervisorHealthIndicator`（`@ConditionalOnBean(ProjectionSupervisor)` 指向 infra 配置类 `@Bean`）——改与 supervisor 同一属性门 `crewscope.projection.supervisor.enabled`，启用而无 supervisor 时 fail-fast。
- **更正 Q02 推断**：notification 三 worker 的前件（`NotificationDispatchRepository` 等五项 infra 构件 + `LarkConnectorApplicationConfiguration`（L<N）的 `@Bean`）全部在条件评估时可见——`@ComponentScan` 对直注构件的注册先于任何配置类条件评估，与 GitHub 连接服务（13 个同类前件、Q02 实测可用）同一机制。Q02 gate 文档「零查询普查结果与此一致」是把直注与配置类 `@Bean` 混同的推断，worker 实际装配；notification 派发若仍有断点，根因在别处（上游投影/凭据路径），不在存在性条件。
- **留档不改**：`ActionReconciliationApplicationConfiguration` 的 health indicator（前件全直注，可见在环）；`GitHubProviderApplicationConfiguration` 的连接服务（前件全直注，实测可用）；其 `gitHubPullRequestWebhookPort`（前件之一 `GitHubWebhookSecretResolver` 全库无实现 bean，恒假）——但该端口无任何消费者，属未交付的入站 webhook 预留面，不在本包造无验收面的改动。
- 回归：`TaskWorkerConfigurationM3I09Test`（+2：晚注册配置类供给 workspace runtime 时 maintenance 链仍装配、worker-capable 缺协作者 fail-fast）、`ActionReconciliationApplicationConfigurationM5I12Test`（+1：metrics/noOp 分支按注入期解析）、`ProjectionSupervisorHealthIndicatorM6I02Test`（+1：属性门启用而无 supervisor fail-fast）。

第 4 项连带修复（2026-09-30，全量复验 fail-fast 暴露的第 10 处恒假——infra 同模块配置类间的注册序）：

- **取证**：Q00 全量复验中 `WorkQueryScaleHttpIntegrationTest`（team-beta 完整应用上下文）因 `codingWorkspaceRuntimeOperationsAdapter` 构造注入失败（`No qualifying bean of type CodingWorkspaceStartupReconciler`）拒绝启动。根因不在第 4 项修复本身，而在其暴露的更深层同族缺陷：`CodingWorkspaceRecoveryConfiguration.codingWorkspaceStartupReconciler` 自带跨类 `@ConditionalOnBean({DurableTaskWorkerStartupReconciler, WorkspacePolicyRepository, WorktreeProvisioner, WorkspaceDiffMonitorFactory, DockerSandboxControl, CodingArtifactLifecycle})` 前件，其中三项（`WorktreeProvisioner`/`WorkspaceDiffMonitorFactory`/`DockerSandboxControl`）由**同级 infra 配置类**（`ManagedWorktreeConfiguration`/`WorkspaceDiffConfiguration`/`TaskExecutionSandboxConfiguration`，按扫描序均晚于 `CodingWorkspaceRecoveryConfiguration` 处理）的 `@Bean` 提供——前件恒假，reconciler 在任何真实部署中从未注册。修复前两侧前件都恒假「相安无事」（server 侧静默缺席掩盖 infra 侧静默缺席）；第 4 项把 server 侧改为 fail-fast 后第一次有装配面能看见这个洞。
- **修复**：reconciler bean 去跨类前件改纯构造注入（保留 `@ConditionalOnMissingBean` 测试替身门）。前件六项与类级 `WorkerManagedRepositoryCondition` 谓词完全同源（`crewscope.runtime.execution-profile ∈ {all, worker}`，`ArtifactStore` 更是无条件注册）——去前件不改变任何合法组合的存在语义，只消除注册序脆弱；worker-managed-repository 生效而协作者真缺席时按既有纪律 fail-fast。
- 回归：`CodingWorkspaceRecoveryConfigurationTest` 4/4（+1：`LateCollaborators` 配置类按后注册顺序供给三个恒不可见协作者时 reconciler 仍装配——旧代码下确证红的注册序复现）；`WorkQueryScaleHttpIntegrationTest` 3 过 1 既有 skip 转绿（context 成功拉起）；`TaskWorkerConfigurationM3I09Test` 6/6 不受影响。

第 1 项修复留档（2026-09-30，竞态路径取证 + 恢复写路径补重读重试）：

- **取证结论**：`TaskWorkerExecutionLoop.start()` 内 reconcile 同步先于 claim poller 启动，单进程内严格串行；claim 事务（`lockReadyBatch` FOR UPDATE）只写 READY 行、startup reconciler（`findRecoveringForUpdate` FOR UPDATE）只写 RECOVERING 行——两者行集不相交且版本在行锁内读取，自身均无乐观锁面。唯一的竞态窗口在 `DurableExecutionLeaseSweeper.sweep`：批事务内 `findExpired` 以 FOR UPDATE SKIP LOCKED 锁 lease 行，但 `executionRepository.findById` 无锁读 TaskExecution 后 `beginRecovery(version)` 乐观提交——api 重启窗口内垂死进程的迟到写（release/heartbeat 链）可在读与写之间落地，输掉的 sweep 把 `OptimisticLockConflictException` 上抛：steady state 被 lifecycle 线程吞为 `lastFailure`（下轮再来，恢复多绕一轮），startup 路径直接炸 `TaskWorkerExecutionLoop.start()`（SmartLifecycle 启动失败，需再次重启）。执行侧对向已有兜底：正常 release 对 TaskExecution 聚合冲突重读重试 ≤3（`DurableTaskWorkerExecutionHandler.release`），失败路径 `releaseForRecovery` catch-all + Sweeper 兜底，heartbeat 失败即中止执行是 fencing 语义本体。
- **修复**：`DurableExecutionLeaseSweeper.sweep` 的批事务外包 `withConflictRetry`（`OptimisticLockConflictException | DataIntegrityViolationException`，4 次尝试，每次开新事务并以 SKIP LOCKED 重选过期批）——逐字对齐 `RuntimeRegistryCoordinator.withConflictRetry` 的「版本竞态输家重读重试、绝不盲写」形状，不引入第二套机制；恢复写路径与执行写路径自此对等收敛于 I01 Claim/Fencing 合同。
- **边界留档**：catch 集与既有范式一致、不含死锁异常族——sweep 锁序（lease→execution）与执行侧（execution→lease）理论上可死锁，输家回滚后 steady state 下轮 sweep 自愈，startup 侧属既有 fail-fast 纪律（同 `register()` 失败即启动失败）。同族的缺陷 F「RECOVERING 无进程内重臂」（steady state 无周期 requeue，仅进程重启或 API 维护触发 `reconcileWorkspaceResources`）为独立缺陷，不在本包范围。
- 回归：新增 `DurableExecutionLeaseSweeperM10Q00Test` 4/4（迟到写赢版本竞态时重读重试收敛、重试预算耗尽 fail-fast、非冲突异常不重试、并发 Lease 插入的完整性违例同族重试）；`DurableTaskWorkerStartupReconciler` 三测试 + `ExecutionLeaseSweeperLifecycleTest` 共 7 用例、真实 DB 集成 `DurableExecutionLeaseM3I03IntegrationTest` 8/8（含既有 completion-vs-sweep 并发竞态用例）全绿。

第 2 项修复留档（2026-09-30，层级预检 + 约束翻译双层）：

- **预检层**（常规路径，带可操作定位）：`ProviderBindingRepository` 新增 `findActiveWorkspaceDefaults`（WORKSPACE 层 ACTIVE+default，键镜像 `ux_provider_binding_active_default` 的 COALESCE 层级键）；`GitHubConnectionApplicationService.bind` 在 `defaultUsage=true` 时预检，层级已被占用（任意 Connection 的默认绑定）即抛新增的 `ProviderBindingDefaultConflictException`（`DomainErrorCode.PROVIDER_BINDING_DEFAULT_CONFLICT`，CONFLICT 类），错误体 `existingBindingId` 指向现行默认绑定——客户端可据此「先改旧绑定为非默认，或直接换绑」。`defaultUsage=false` 的共存路径（Q02 当时的绕行面）不受预检影响。
- **翻译层**（并发兜底）：`JpaProviderRepositoryAdapter.create` 捕获 flush 异常，沿 cause 链找 SQLState 23505 且约束名为 `ux_provider_binding_active_default` 的 `SQLException`（原生 Hibernate 形态在 Spring 翻译之前抛出，故在 adapter 内走 cause 链而非按类型 catch）→ 翻译为同一冲突异常；其它完整性违例原样上抛。
- **HTTP 语义**：异常经既有 `ApplicationErrorMapper` → `domainResponse` 通道（category CONFLICT → 409、`retryable` 恒 false）映射，未改 `ApiExceptionHandler`——与 REGISTRATION_CONFLICT 等既有 409 端点同一通道。幂等重放：reserve 与预检同事务，冲突回滚后收据不落库，同 Idempotency-Key 重放重新评估并再次得到同一 409（无副作用）。
- 回归：`GitHubConnectionApplicationServiceM5A06Test` 9/9（+2：第二默认被拒且错误体含现行绑定 id 并不触达 create、非默认与现行默认共存成功）；`ProviderBindingResolverTest` 8/8、`BuiltInProviderInitializationServiceTest` 5/5（两处测试 Fixture 补新端口方法的层级过滤实现）；真实 DB 集成 `M1JpaPersistenceIntegrationTest` 24/24（+1：绕过预检直插第二默认，PG 23505 翻译为冲突异常且层级默认计数保持 1、层级查询命中 seed 绑定 id）；server 层 `GitHubConnectionControllerM5A06Test` 5/5（+1：WebTestClient 绑定真实 `ApiExceptionHandler` 断言 409 + `provider_binding_default_conflict` + `retryable=false` + `details.existingBindingId`——直接锁定关闭条件的 HTTP 面）。

### 10.13 计划修订记录

- 2026-09-19：二轮 Review 修正（见 §10.10）。
- 2026-09-30：并入 M9b-Q02 移交 5 项为 `M10-Q00`（§10.12）；迁移基线事实更新至 V51；S01 必答第 10 条补入 Q02 实测的 agentscope 2.0.0 已知限制与 DeepSeek 无 embedding 端点约束；Q01 验收点名 M9b 两个既有 gate。
- 2026-09-30：embedding Provider 选型范围冻结为智谱 `embedding-3` / 阿里 `text-embedding-v4`（OpenAI 兼容、国内直连，§10.11 第 2 条），S01 在此范围内实测冻结维度与数据策略。
- 2026-10-01：S01a 完成——§10.11 十项合同与评测样本设计冻结于 [S01 冻结记录](../spikes/M10-S01-知识与检索合同冻结.md)，ADR-030 与 ADR-031（Skill 部分）创建并登记索引；embedding 维度/批量/价格、预算默认值、阈值校准值留 S01b 实测回填。
- 2026-10-01：S01b 完成，**S01 关闭**——embedding 实测选定阿里 `text-embedding-v4`（1024 维、批量上限 10、0.0005 元/千 Token 人民币、verify 不能证 embedding 能力改为首条真实嵌入探针），隔离原型全部达标（标注集首轮：知识 Recall@5/@10=1.0、代码文件级 Recall@10=1.0、版本准确性=1.0、无答案误召回 0@τ=0.55；作业恢复 claim/fencing/checkpoint 续传四场景全过）；全部数字回填 S01 冻结记录 §3.2/§3.4/§4/§6；D01/I01a 开放。
- 2026-10-02：D01 完成——知识与检索公共地基交付：`domain/knowledge`（Entry 头乐观锁+Version append-only 两对象聚合、头指针生效闸、内容寻址 hash 不含 revision、DELETED 墓碑不可复活、4 个 SchemaVersion.V1 事件）与 `domain/retrieval`（六分量 RepositoryIndexKey、Generation 词汇/保留策略、注入清单 Schema：三重归属 ref/预算分层/三降级码）两包；`application` 端口三件（KnowledgeRepository 含写与 findEffectiveVersion 权威闸、GenerationCatalog 只读、InjectionManifestRepository 占位）；V52 纯 PostgreSQL 两表迁移+team_role 存量幂等同步（KNOWLEDGE_MANAGE→OWNER+ADMIN、SKILL_MANAGE→OWNER）；TeamPermission +2 枚举。验收：权限/版本并发/来源归属合同测试（InMemory fake+反射租户守卫）、空库 52 版迁移+validate+pg_extension=0、领域零框架（domain pom 零改动）。JDBC 适配器/命令面/事件接线/Generation 执行随 A02/I01 交付；证据回填 S01 冻结记录 §6 D01 列。
- 2026-10-02：A02a 完成——知识人工录入管理命令面交付：`KnowledgeCommandService`（create/updateDraft/publish/retire/delete 五命令走 202 回执协议+reserve-first 幂等——同 key 异语义重试报 idempotency_conflict 而非 key 冲突；updateDraft 不发事件不写 outbox，草稿不可检索故不改变索引消费面，仅命令回执表记账）、`JdbcKnowledgeRepositoryAdapter`+`KnowledgePersistenceConflictMapper`（头乐观锁 UPDATE WHERE version、SQLSTATE 23505 约束名翻译矩阵、entry_key/revision 双 keyset 分页、生效指针 JOIN 只随 PUBLISHED 头）、`KnowledgeEntryController`（10 操作：命令面+管理读取，头 version 与版本 contentHash 双强 ETag，indexStatus 恒 PENDING 待 I01 投影接管；OpenAPI 基线 240→250）、V53 category 列（CONVENTION/RUNBOOK/DECISION/GUIDE/OTHER 五值 CHECK，存量回填 OTHER；category 为头字段经 PATCH 可改、不进 contentHash）、审计 4 事件注册（基线 121→125）、状态机生成物含 KnowledgeEntry 聚合（基线 17，ALLOWED_TRANSITIONS 静态 Map）。A02b（AI 提炼草稿/披露检查/DISTILLATION 用量事实）不在本轮；契约冻结于 [M10-知识库API契约](../api/M10-知识库API契约.md)，证据回填 S01 冻结记录 §6 A02a 列。
- 2026-10-03：A02b 完成，**A02 父包关闭**——知识来源提炼交付：`KnowledgeDistillationService` 四阶段序列（校验事务零副作用→无事务 LLM 调用→用量事实小事务→条目提交事务，reserve 在阶段 4——PENDING 预约不能跨 LLM、失败前 complete 会重放进虚无；LLM 失败无回执=同 IK 完整重试计入新用量事实，条目提交失败用量事实已留存）、来源收紧为 Task 当前执行尝试且 COMPLETED（净化事件流白名单渲染，131072 字符上限明确拒绝不截断）、origin 不可变归因（V54 两列+同空同非空 CHECK+attempt≥1+执行查找部分索引，头 DTO 暴露，draft→publish→retire→delete 全程保留）、披露检查为 publish 命令级前置校验（`KnowledgeDisclosurePolicy` 七个高置信凭据模式族，命中 403 `knowledge_disclosure_denied` 只回显族名，刻意排除低置信形态防误报）、DISTILLATION 用量事实事件首块落地（`ModelUsageFactRecorded` 统一形状：callId/eventId 确定性派生支持 outbox 至少一次投递幂等、role 五值枚举一次定义本轮只发射 DISTILLATION、Provider 计数不一致降级 unreported 零值=未回显）、内置稳定 knowledge-distiller profile（Team 惰性 provision+治理链全走，`KnowledgeDistillerRuntime` 单轮结构化输出+超时 sanitize+真 harness 用量捕获 middleware）、`KnowledgeDistillationController`（POST distillations 同步 202 回执含 entryId/entryKey/origin/indexStatus；OpenAPI 基线 250→251）、审计 125→126（MODEL_USAGE_FACT_RECORDED）。测试边界=fake model+真实 JDBC/事件管线（真 harness 调用链 202 用例全绿）；真实 Provider 实测留 F01a。契约 [M10-知识库API契约](../api/M10-知识库API契约.md) §11-§12，证据回填 S01 冻结记录 §6 A02b 列。
- 2026-10-03：I01a 完成——embedding 适配与可选向量存储交付：`EmbeddingClient` Port（S01 §3.2 冻结数字：批量 10/单条 33000/超时≥1s 为接口常量与预拒；每次真实 HTTP 尝试一条 Attempt 含失败尝试 usage）+`OpenAiCompatibleEmbeddingClient`（独立最小传输不复用 chat 适配器；短生命周期 client+Redirect.NEVER、429/504/≥500 有界重试读 Retry-After、逐向量维度/有限值校验、密钥与响应体不外泄）；目录 seed 追加 DashScope `text-embedding-v4` 行（价格 0.5 CNY/1M 生效 2026-10-01、数据策略 PROVIDER_MANAGED/PROHIBITED、能力 embedding，Java initializer 无 V55，默认迁移 tip 保持 V54）；`TeamEmbeddingService` 治理解析链（TEAM→ORG、ACTIVE 目录+生效价格硬门 PRICE_UNAVAILABLE、训练策略门=解析过滤非合规 provider 永不接收内容、逐 attempt `MODEL_USAGE_FACT_RECORDED(EMBEDDING)` 确定性 callId、探针 deterministic commandId 复用同一 callId 域）；verify 第二腿 `EmbeddingCapabilityProbe`（`ModelConnectionCredentialService` 可选协作者，null/empty=行为与 I01a 前完全一致，传输失败不花 embedding token）；可选向量存储：独立迁移链 `db/migration-vector`（V1 扩装入 public+V2 向量表+HNSW cosine，history=`flyway_vector_history`）、`KnowledgeVectorMigrationRunner`（SmartInitializingSingleton 单规则门控「enabled 或历史已存在」、worker 跳过、失败 fail-fast 不吞）、`PgVectorKnowledgeEmbeddingStore`（文本字面向量绑定无 PGvector 依赖、租户谓词+生效闸 join 先于向量 Top-K、闭合查询对象调用方无法放大范围）、compose 覆盖×2+quickstart env-key 追加+运维手册「可选向量存储」小节（snapshot 备份→改 env→up；Alpine↔Debian 卷不兼容警告）。无 HTTP 新面（OpenAPI/状态机/默认迁移基线不变）；证据回填 S01 冻结记录 §6 I01a 列与 ADR-030 §4/§5 已实现标注。I01b（分片/持久作业/激活）不在本轮。

尚未实测的数值不在计划里编造为保证；S01 未闭合对应主线合同，不进入该范围实现或以 Mock 标为验收通过。
