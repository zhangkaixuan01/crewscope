# CrewScope M12 后后端整体架构与实现 Review

> 评审对象：六 Maven 模块主代码 3,132 文件 / 285,523 行，测试 871 文件 / 246,087 行（2026-10-19 实测）。
> 评审范围：M10（Agent 智能跃迁与知识闭环）→ M11（协作规模化与开放生态）→ M12（企业化与多租户治理）三个里程碑堆叠后的后端全景。
> 母本方法：对齐《M12后-前端整体架构与实现Review》（量化盘点「复制代替抽象」+ 修复路线图分批 + 覆盖声明如实）。
> 状态：**评审完成**（2026-10-19，R1-R5 全节填充）。

## 1. 总体评价

### 1.1 记分卡

| 维度 | 评分 | 依据 |
| --- | ---: | --- |
| 模块分层与依赖纪律 | A | 六模块洋葱单向（pom 实测，§2.2）；domain 零框架依赖沿用；Micrometer 零越界（§7）；Port/Adapter 干净（26 Port/0 Adapter，infrastructure 109 Adapter） |
| 领域建模与聚合质量 | A- | 最大聚合根 1,124 行无怪物；乐观锁/幂等/事件信封全链一致；扣分：1,233 行投影器与 1,161 行 JDBC 适配的内聚性未逐行审（§9 盲区） |
| 可靠性与恢复语义 | A | Outbox/Lease/Fencing/Dead Letter 底座沿用并经 M11 实时/M12 多租户扩展；receipt 配对零缺陷（§5.1）；模型调用有界恢复语义统一（§4.1） |
| 安全与治理边界 | A | 强 ETag、Idempotency-Key、签名 Cursor、多租户 138 表绊线（M12-D01）、配额门禁、账号删除全链（M12-Q02 真实栈实证） |
| **复用与重复控制** | **C+** | distiller 垂直链 ~2,900 行冗余为全仓最大单笔（§3.1）；CursorCodec 四胞胎（§3.2）；command 样板跨文件零共享（§5.2）——三族合计约 4,000+ 行可收敛，且税已缴三次 |
| 工程与测试治理 | A- | 246,087 测试行、mock 双轨健康（§6.2）、domain 薄而无真空（§6.3）；扣分：IT 命名错位（§6.1）、后端契约断言与前端指纹门禁不对称（§6.4） |
| 可观测性与运维面 | A | 观测端口模式一致、SLI 六指标固定序、模型调用观测链完整（§7） |

### 1.2 一句话结论

后端底座（分层/事务/安全/观测）在 M10→M12 三个里程碑堆叠后**保持了 M8 后评定的 A 级纪律，无正确性缺陷**（R3 横切五面扫描零命中）；唯一显著退化是 M10 的 distiller 垂直链开了「复制代替抽象」的先例且未被后续里程碑纠正——同一形状的修复成本已缴三次（M10-Q02 缺陷 1/3 双份修复、债务周 B 第三次），**继续不收敛则每个 distiller 域内改动都按双倍计价**。修复面高度收敛：不需要推倒任何东西，是三族定点收敛（distiller 链/CursorCodec/command 样板），且内部正确答案全部已存在——`mutatingCommand`+`completed` 文件内 helper 证明了 command 骨架可抽，债务周 B 的母本形状证明了恢复三件套可组件化。

### 1.3 与 M8 后 Review 的对照（进步面）

| M8 后评定 | M12 后复评 | 变化 |
| --- | --- | --- |
| 领域建模与分层架构 A（24 域包/32 应用包） | A（31 domain 包、application 30+ 域包，六模块洋葱不变） | 规模翻倍，纪律未破 |
| 可靠性与恢复语义 A（V26-V36） | A（V1-V70，新增 M11 撤权链/M12 租户隔离/模型调用有界恢复） | 底座沿用，语义面扩展 |
| 安全与治理边界 A | A（新增配额/账号删除/SLI 授权下钻，M12-Q02 真实栈实证） | 持平且加深 |
| 工程与发布治理 A-（579 测试类） | A-（871 测试文件/24.6 万测试行，CI 门禁族持续加密） | 量级增长，扣分项同类 |
| Agent 能力纵深 C+（memory/rag/skills 全未接入） | （本次未单列）M10 已接入 memory/rag/蒸馏/skills 闭环 | 结构性进步 |
| （M8 后不存在此项） | **复用与重复控制 C+（新列）** | M10 引入 distiller 双链是唯一新增架构级债务 |

## 2. 架构与依赖

### 2.1 模块规模矩阵（2026-10-19 实测）

| 模块 | 主代码（文件/行） | 测试（文件/行） | 测试行/主代码行 |
| --- | --- | --- | --- |
| domain | 910 / 60,624 | 144 / 30,202 | 0.50 |
| application | 1,189 / 81,004 | 219 / 66,037 | 0.81 |
| agentscope | 148 / 13,970 | 55 / 16,362 | 1.17 |
| integration | 21 / 2,258 | 4 / 3,030 | 1.34 |
| infrastructure | 473 / 75,492 | 220 / 85,152 | 1.13 |
| server | 391 / 52,175 | 229 / 45,304 | 0.87 |
| **合计** | **3,132 / 285,523** | **871 / 246,087** | **0.86** |

观察（初判，R4 深化）：
- domain 测试密度全仓最低（0.50）——domain 是否被 application 层测试间接覆盖、还是存在真空，待 R4 判定。
- application 是最大模块（1,189 文件 / 30+ 域包），按领域垂直切分（action/activity/agent/audit/…）。

### 2.2 依赖方向：严格洋葱单向（pom 实测）

```
domain → （无内部依赖）
application → domain
agentscope → domain, application
integration → domain, application        （仅 provider 包，21 文件）
infrastructure → domain, application
server → domain, application, agentscope, integration, infrastructure
```

平行三模块（agentscope/integration/infrastructure）互不依赖，server 唯一聚合——分层纪律与前端 check-front-boundaries 门禁约束下的 web 侧对齐。（后端目前无等价自动门禁，见 §8 路线图。）

### 2.3 巨型类 Top10（行数实测）

| 行数 | 类 | 备注 |
| --- | --- | --- |
| 1,284 | application/audit/CrewScopeAuditEventTypes | 事件类型常量表（预期形态） |
| 1,233 | infrastructure/event/projection/NotificationIntentProjector | 投影器 |
| 1,161 | infrastructure/persistence/review/JdbcReviewRepositoryAdapter | JDBC 手写适配 |
| 1,139 | agentscope/AgentScopeNativeRuntime | Coding Agent 主运行时 |
| 1,124 | domain/task/TaskExecution | 聚合根 |
| 992 | application/conversation/ConversationApplicationService | |
| 937 | domain/coding/ExecutionWorkspace | |
| 935 | application/task/AgentTaskCreationService | |
| 933 | infrastructure/persistence/taskruntime/JpaTaskRuntimeRepositoryAdapter | |
| 893 | infrastructure/workspace/git/GitCommandExecutor | |

无 2,000+ 行怪物；巨型化集中在投影器/仓储适配/聚合根三类，可控但值得逐个看内聚性（R2/R3 深化）。

## 3. 复用与重复（最大发现节）

方法：①已知镜像包逐对 `diff` 量化相同行占比；②全仓 3,132 主代码文件做滑动窗口指纹（10 行窗口/步长 5，剔注释空行，44,965 窗口）聚合出共享窗口数 Top 文件对。两法互补：前者精确、后者发现未知族。

### 3.1 族一：distiller 垂直链全镜像【全仓最高优先级】

Knowledge/Skill 两条蒸馏链在**全部四层**成对镜像（逐对 diff 实测相同行占比）：

| 层 | 镜像对（Knowledge 侧 ↔ Skill 侧） | 相同行 |
| --- | --- | --- |
| domain | Initialization 215↔215、Template 147↔142 | ≈80% |
| application | 11 对：**DistillationService 503↔497（73%）**、**CommandService 609↔669（67%）**、ProvisioningService 224↔225（84%）、Port/Readiness/Repository/Filter/Page/PageRequest/VersionPage/VersionPageRequest（42–79%） | — |
| agentscope | Runtime 213↔211（76%）、TemplateRuntimeRegistry 101↔100（86%）、UsageAccumulator 63↔63（80%）、Session 60↔60（83%）、Request 59↔61（63%）、PromptRenderer 30↔32（58%） | — |
| server | KnowledgeDistillationAdapter 209↔SkillDistillationAdapter 206 | 81% |

- 合计约 **3,300 行中 ~70% 为双份复制**（约 2,900 行冗余）。
- **实证危害**（不是理论洁癖）：M10-Q02 台账缺陷 1（preflight 豁免漏两 distiller）与缺陷 3（usage 幂等键 `#usage-N`）都是「Knowledge 逐字同构同 bug」双份修复；债务周-B（2026-10-19）又以同一形状手工修了两个 runtime 的结构化恢复——同一形状改 N 遍是这族税的第三次缴纳。
- 建议：domain/application 两层的 distiller 内聚为「参数化单实现 + 两薄壳」（差异点：schema spec、category hint vs skill key、catalog ceiling）；agentscope 两个 Runtime 合并为泛型基类或组合恢复组件（恢复/消毒/usage 累积三件套已可抽 `StructuredDeliveryCall` 组件）；server 两 Adapter 同法。

### 3.2 族二：CursorCodec 四胞胎（server/api）

WorkDeskSectionCursorCodec(315) ↔ WorkItemCursorCodec(285) ↔ TeamActivityCursorCodec(272) + 各自配套——共享窗口数全仓第二（11 窗）。游标 encode/decode/base64/校验样板×4 份。建议抽 `CursorCodec<T>` 基类（每 Codec 只声明字段序与类型）。

### 3.3 族三：ModelBillingSubject ↔ ModelCredentialSubject（domain/model，96↔96）

6 窗小而完整的双胞胎，建议合一（差异仅为 subject 字段语义）。

### 3.4 族四：Team 生命周期二 Service（application/team，545↔781，5 窗）

TeamApplicationService ↔ TeamMemberLifecycleApplicationService——成员/团队生命周期样板复制，窗口数中等；结合 §3.1 的 CommandService 模式（KnowledgeCommandService ↔ WorkItemCommandService 3 窗）指向「draft/publish 生命周期 + 命令幂等样板」这一横切重复——**command 样板（reserve→receipts.complete→审计事件→投影）是全仓第三大重复源**，建议先量化再决定抽 CommandTemplate 还是留惯例（涉及面广，慎动）。

### 3.5 族五：JPA Entity 样板（infrastructure，3–6 窗×多对）

WorkspaceEntity↔WorkProjectEntity、WorkItemCommentEntity↔WorkItemResourceLinkEntity、ResponsibilityAssignmentEntity↔TeamEntity、AgentProfileEntity↔WorkItemCommentEntity——审计字段/构建器/equals 样板复制。JPA 实体抽象收益有限（@MappedSuperclass 可消审计字段但equals/构建器仍需手写），**登记不收敛**（对齐前端 review 对「模板样板」的豁免先例）。

### 3.6 意外发现：KnowledgeDistillationService ↔ KnowledgeIndexControlService（同模块，8 窗，503↔278）

职责不同的两个 Service 高同构——索引控制服务从蒸馏服务复制了事务/幂等/审计样板起家。并入 §3.4 的 command 样板议题一并量化。

### 3.7 Controller 层中低度同构（登记不收敛）

WorkItem↔WorkItemQuery（同域读写分离，预期形态）、RepositoryBinding↔RepositoryCatalog↔Team（4–5 窗）——框架样板（Idempotency-Key/CSRF/分页）占比高，抽象易伤可读性。**登记不收敛**，靠 §8 的门禁防新增劣化。

## 4. 错误处理与消毒边界

### 4.1 sanitizeModelFailure 豁免集矩阵（R3 实测）

| Runtime | StructuredDeliveryException | PlatformExecutionSecurityException | IllegalArgumentException | DomainValidationException | 专属分支 |
| --- | --- | --- | --- | --- | --- |
| ReviewerSpecialist（M5 母本） | ✓ | ✓ | ✓ | ✓ | — |
| KnowledgeDistiller（债务周 B） | ✓ | ✓ | ✓ | ✓ | — |
| SkillDistiller（债务周 B） | ✓ | ✓ | ✓ | ✓ | — |
| TeamObserver（债务周 B） | ✓ | ✓ | ✓ | ✓ | — |
| AgentScopeNativeRuntime（coding） | （第 5 消费者，形态略异） | ✓ | ✓ | ✓ | CodingSpecialistExecutionException ×2 |

- **语义面一致**：四个非 coding runtime 债务周 B 后已统一母本形状（无结构化投递→恢复一次→仍无效→`SafeModelExecutionException(MODEL_EXECUTION_FAILED)`）；TeamObserver telemetry 语义变化（OUTPUT_INVALID→UNAVAILABLE）已在债务周 B 文档入档。
- **实现面仍 5 份手写**：豁免集逐字相同却零共享——`sanitize`/恢复链/usage 累积三件套正是 §3.1 建议 `StructuredDeliveryCall` 组件的抽取面。这是「语义已收敛、代码未收敛」的典型态，抽组件不改变任何行为。
- 边界正确性抽检：`StrictStructuredOutputDecoder.decode` 只抛 IllegalArgumentException、`StructuredOutputSpec.requireValue` 只 cast——恢复路径 `validStructuredDelivery` 的 catch 范围与之一致，无过宽吞异常。

### 4.2 事件幂等键派生一致性

全仓后缀派生幂等键仅 3 处，同一 `<命令键>#<子域>` 模式无流派分裂：

| 位置 | 键形状 | 来源 |
| --- | --- | --- |
| KnowledgeDistillationService:291 | `idempotencyKey + "#usage-" + attempt` | M10-Q02 缺陷 3 修复后形状 |
| SkillDistillationService:295 | 同上 | 同 bug 双份修复（§3.1 实证） |
| AgentTaskCreationService:667 | `idempotencyKey + "#executor-assigned"` | 派生命令事件子键 |

（PromptInjectionService:258 的 `path#line-line` 是 fragment 标识非幂等键，不计入。）

### 4.3 Mono.using / timeout 分层规范

五个 runtime 全走 `Mono.using(session…)` 管 session 生命周期，无裸 session 泄漏形态：四个非 coding runtime 的 using+timeout 结构逐行对齐（108-140 行区间同构）；coding runtime 两处 using + `remainingDuration` 剩余预算制 timeout（多轮任务形态，合理更复杂）；`SafeAgentScopeGenerateOptionsMapper.requirePositive` 统一校验 timeout 正数。**规范一致性良好**，唯一重复源仍是 distiller 双份（§3.1）。

## 5. 事务与一致性

### 5.1 reserve→receipts.complete 配对（R3 判定：无缺陷）

9 个 `*CommandService` 扫描：7 个 reserve=complete 文本计数直接配对；**KnowledgeCommandService 与 TeamSkillCommandService 各报 reserve=3 complete=2 为 helper 折叠假象**——`completed(...)` 私有 helper 内的单处 `receipts.complete` 物理服务两条 reserve 路径（create 命令 + `mutatingCommand` 共享骨架），展开调用链后 3/3 配对完整。记忆坑「漏 complete 致 replay 500」**零命中**。

> 方法学注记：文本计数配对扫描会被 helper 折叠欺骗（同一 complete 文本行服务多条 reserve），涉嫌疑文件必须展开调用链复核——本节即一例。

### 5.2 command 样板的文件内收敛 vs 跨文件零共享

KnowledgeCommandService 与 TeamSkillCommandService 各自持有 `mutatingCommand`（findCompleted→权限→乐观锁→reserve→action）+ `completed`（事件信封→events.append→outbox.enqueue→receipts.complete，同事务）两个私有 helper，**两文件间逐行同构**（§3.1 族一 67% 相同行占比的组成）。即：每个 CommandService 文件内部已各自完成一轮收敛，但 9 个 CommandService 之间零共享——这是 §3.4 command 样板议题的直接证据，抽共享骨架（CommandTemplate）的可行性已被文件内 helper 的存在证明。

### 5.3 Outbox 消费面边界

`outbox.enqueue` 共 19 个消费文件；其中 9 个无 `receipts.complete`——逐类核对为**非命令面**：identity 注册/认证（自有幂等机制）、`Durable*EventPublisher`/观测 fact 服务（内部事件转发与投影，走 outbox 自身至少一次语义，无 Idempotency-Key 命令语义）。receipt 模式边界清晰：仅用户写命令（带 Idempotency-Key）走 reserve/complete，无越界误用。

## 6. 测试结构与盲区

### 6.1 分层统计与命名错位（R4 实测）

| 模块 | *Test | *IT | 备注 |
| --- | --- | --- | --- |
| domain | 131 | 0 | 纯手写，无框架 |
| application | 213 | 0 | **@SpringBootTest=0**（Spring 装配测试全部下沉 server，边界干净） |
| agentscope | 53 | 0 | mock HarnessAgent 为主 |
| integration | 4 | 0 | |
| infrastructure | 213 | 1 | |
| server | 224 | 1 | **101 文件用 @SpringBootTest/WebTestClient** |

发现：命名上 871 个测试文件仅 2 个 `*IT`，但 server 有 101 个 Spring 装配级集成测试以 `*Test` 命名——**surefire/failsafe 分相约定未使用**，全 reactor verify 单相位跑完（50 分钟量级的组成因素）。这不是缺陷，但若未来要拆 CI 快慢道，需要先做 IT 命名/注解的存量梳理。

### 6.2 mock 边界：手写 fake 与 Mockito 双轨

application 层 213 测试文件中 117（55%）用 Mockito（mock 外部端口/模型调用），其余为手写 in-memory fake——抽检 KnowledgeCommandServiceTest（78 行起）：零 Mockito，CommandReceiptStore/DomainEventStore/OutboxRepository/TeamRepository 全部测试内手写实现，javadoc 明示「Command-face contract for A02a」契约优先取向。**双轨形态健康**：契约面（command/receipt）用 fake 保真，外部依赖面用 mock。已知坑均有记忆登记且已清偿（mock 掩盖 receipts 漏 complete——R3 判定零命中；mock reactive 未 stub 返 null）。

### 6.3 domain 密度 0.50 的判定：薄而全，无真空

31 个 domain 主代码包中 29 个有专属测试包；缺失的 `policy`/`search` 仅 3 个微型值对象（PolicyPackId/PolicyPackReference/SearchableObjectType），且被 application/server 层 19 个测试文件间接引用。聚合根覆盖抽样：TaskExecution（1,124 行）有 ClaimToken/FencingToken/ExecutionLease/StepExecution/SafetyEnforcement 等 20+ 测试文件分面覆盖；ExecutionWorkspace（937 行）有专属 Test。**结论：密度低是「薄而全 + application 层厚间接覆盖」的形态，不是真空**——与前端 review 对 store 层的判定同构。

### 6.4 真实栈 gate 面（引用既有台账，未复跑）

e2e 全量 681+7 skipped（mock 栈，含 m12-f01/f02/f03 三企业域 spec）；m11-real gate（实时协作/WorkGraph/撤权链）与 m12-real gate（enterprise-mainline 7 用例）按 M12-Q02 报告入档。后端契约断言不从 OpenAPI 文件推导（server 测试零 openapi 引用）——契约由 controller 测试直接承担，与前端的 openapi.ts 指纹门禁是**不对称防护**（后端靠 OpenAPI drift 检查脚本补位，见 §8）。

## 7. 可观测性与运维面

- **观测端口模式一致**：application 层定义 `OperationalTelemetry` 端口，server 层 `TeamBetaOperationalTelemetry` 实现 + `TelemetryDegradationState` 降级态；SLI 面齐整（OrganizationSliController/SliConfiguration/MeterRegistrySliTimerStatistics，六指标固定序有 M12-Q02 真实栈实证）。
- **Micrometer 零越界**：domain/agentscope/application 三层主代码零 `io.micrometer` import——观测依赖严格圈在 infrastructure（19 文件）/server。各域专属 telemetry（LoginDefense/CodingSpecialistTelemetryAccumulator）走各自端口/累积器形态，未引入指标库耦合。
- 模型调用观测链完整：AgentCallObservationScope/Record/Sink + AgentCallFailureClassifier 构成 per-call 观测骨架，usage 幂等键派生一致（§4.2）。

## 8. 修复路线图（按性价比分批）

> 前提：R3 判定零正确性缺陷——本路线图全部是**结构性收敛**（降双倍维护成本），无紧急度。分批原则对齐前端 review：每批独立可交付、独立可验证、不动行为。

| 批 | 内容 | 规模 | 验证面 | 备注 |
| --- | --- | --- | --- | --- |
| **P0 分层门禁自动化** | 新增 `scripts/check-module-boundaries.mjs`（解析六个 pom 的内部依赖，断言 §2.2 洋葱矩阵），挂 CI quality job | ~1 天 | 门禁脚本自测+CI 绿 | 防新增劣化的最低成本闸门，前端 check-front-boundaries 先例；**先于一切收敛动作落地**，保证后续重构期间依赖纪律不破 |
| **P1a StructuredDeliveryCall 组件** | agentscope 层抽恢复/消毒/usage 三件套组件，四 runtime（Reviewer/Knowledge/Skill/TeamObserver）改挂组件 | ~150 新行，净删 ~200 | 债务周 B 的 25 个定向用例不改断言全绿 | 最小步：母本形状已被债务周 B 证明五要素稳定，抽组件是纯机械上提 |
| **P1b distiller 垂直链收敛** | domain/application 两层 distiller 参数化单实现+两薄壳（差异点：schema spec/category hint vs skill key/catalog ceiling）；server 两 Adapter 同法 | ~2,900 冗余行→预计净删 ~2,000 | 全 reactor+M10-Q02 复跑入口 | M13 候选主线；建议 P1a 先行（组件是 Runtime 收敛的前置件） |
| **P2 CursorCodec 基类** | `CursorCodec<T>` 基类，三个 Codec 只声明字段序与类型 | ~870 行→~500 | server 模块定向测试 | 独立小案，可穿插 |
| **P3 CommandTemplate 评估** | 先量化（滑动窗口指纹锁定跨文件重复全集）再裁定抽共享骨架 or 留惯例；若抽，以 `mutatingCommand`+`completed` 已有 helper 为蓝本 | 评估 ~2 天，实施 1-2 周 | 9 个 CommandService 全量定向+receipt replay 用例 | **慎动批**：涉及全部写命令面，须在 P1b 经验之后 |
| **P4 ModelSubject 合一** | ModelBillingSubject↔ModelCredentialSubject 合一（96↔96） | ~半天 | domain 定向 | 微型案，随手清 |
| **P5 IT 分相梳理** | 101 个 Spring 装配级 `*Test` 重命名/注解分相（surefire/failsafe），仅当 CI 需要拆快慢道时做 | ~2 天 | 全 reactor 两相位跑通 | **条件触发**，当前 50 分钟全 reactor 可接受 |

登记不收敛（对齐前端 review 豁免先例）：JPA Entity 审计字段/构建器样板（§3.5，@MappedSuperclass 收益有限）、Controller 框架样板（§3.7，抽象易伤可读性）——靠 P0 门禁防新增劣化即可。

若采纳 P1b+P3，规模（约 2-3 周后端工作量）足以立 **M13-后端结构收敛** 里程碑；若只做 P0-P2+P4（约 1 周），可作为轻量专项（债务周模式复用）不立里程碑。

## 9. 评审证据与覆盖声明

**方法**：全部结论来自本仓静态勘察（2026-10-19，工作树 HEAD=39c2aa5）——①模块矩阵与巨型类=wc/find 实测；②依赖洋葱=pom 依赖声明 grep；③同构族=镜像包逐对 diff+全仓 3,132 文件滑动窗口指纹（10 行窗/步长 5，44,965 窗口）；④横切五面=sanitize 豁免集/reserve-complete 配对/幂等键派生/Mono.using-timeout/outbox-Micrometer 分布的定向 grep+展开复核；⑤测试结构=分层统计+抽样精读。未运行任何测试（纯评审，证据链全部可由既有台账复跑入口背书）。

**精读过的文件**（非仅 grep）：KnowledgeCommandService（236-533 区段，reserve/complete/helper 判定）、TeamSkillCommandService（配对核对）、KnowledgeDistillerUsageAccumulator、KnowledgeCommandServiceTest（1-76，mock 边界形态）、M8 后 review 记分卡全文（对照基线）；其余为 R1-R2 已铺的镜像包 diff 面（§3 各对）。

**盲区如实声明**：
1. 三个巨型类（NotificationIntentProjector 1,233/JdbcReviewRepositoryAdapter 1,161/AgentScopeNativeRuntime 1,139）只做了行数盘点，未逐行审内聚性——「可控但值得逐个看」是判断不是结论。
2. integration 模块（21 文件）与 provider 面未逐个审。
3. 性能面（N+1/索引/查询计划）零覆盖——无慢查询证据，不构成发现也不构成豁免。
4. 并发正确性（Lease/Fencing/撤权竞态）沿用 M8 后 A 级评定与 M9b/M11 台账，本次未重验。
5. 测试断言质量只抽检（§6.2 一样本），未全量核对「断言从契约推导而非实现快照」（记忆中有 A01 先例坑）；契约面靠 M10-Q02/M11/M12 各 gate 台账背书。
6. e2e/真实栈 gate 数字全部引用既有台账（§6.4），本次未复跑。
