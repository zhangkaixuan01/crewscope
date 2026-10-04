# M10-I02b Prompt 注入与注入清单契约

> 状态：冻结（I02b 装配 + 预算 + 耐久清单）<br>
> 版本：V1<br>
> 日期：2026-10-04<br>
> 适用范围：Coding 执行链的检索/记忆 Prompt 注入、四层预算裁剪、(execution, attempt) 幂等注入清单

**内部端口（无 HTTP 面）**：本契约的全部能力是进程内装配（`PromptInjectionService.assemble`）与一张持久表；不新增任何 HTTP 端点，OpenAPI 面保持 259 不变。

## 0. 本轮边界

本契约覆盖 **I02b Prompt 注入、预算与耐久证据**：A01 检索候选（知识条目+仓库片段）与 I02a 辅助记忆按四层优先级组成有界注入块，缀于 Coding Specialist 指令之后；每次装配在首次模型调用前落一条注入清单（幂等键 `(executionId, attempt)`），被裁候选以 CANDIDATE 阶段入清单、降级码透传。以下不在本契约内：

- **I02c 引用反馈**：已随 I02c 交付（引用查询三端点/「不适用」反馈/声明引用回执，契约见 [M10-执行引用与反馈API契约](M10-执行引用与反馈API契约.md)）；「已注入≠已采用」的 UI 呈现消费归 F01c；
- **F01c 前端**：无 UI 消费面，清单无查询端点；
- **非 Coding 链**：会话链/编排链/Reviewer/Distiller 不接线（清单幂等键只适配 task 执行链）；
- **注入缓存**：每 round 重组装一次检索（受 maxTestRepairRounds 界定），缓存属 F03 观测后再议；
- **第四个降级码**：注入 actor 不可用 = 失败关闭（§4），不是降级。

## 1. 数据模型（V59 单表）

**`injection_manifest`**（纯 PostgreSQL 主链，无向量扩展）：

| 列 | 形状 |
|---|---|
| `id` | UUID PK |
| `organization_id`…`execution_id` | 六坐标列，由 `task_execution` 行派生（复合 FK 七列 → `uk_task_execution_scope_id`，`ON DELETE RESTRICT`）——清单不可能指向别租户的执行 |
| `attempt` | INTEGER ≥1（CHECK） |
| `source_references` | JSONB 数组（CHECK `jsonb_typeof = 'array'`；列名避开保留字 REFERENCES） |
| `trims` | JSONB 数组 |
| `budget` | JSONB 对象 |
| `degradations` | JSONB 数组 |
| `created_at` | TIMESTAMPTZ |

幂等键 `uk_injection_manifest_attempt UNIQUE (execution_id, attempt)`：同一对二次 append 在库层拒绝（23505），适配器翻译为 `InjectionManifestConflictException`（沿 cause 链匹配约束名），装配方捕获后经 `findByAttempt` 收敛到已存清单。

## 2. 四层预算与裁剪算法

**层优先级**（预算快照四列只计三层；SKILL 恒硬保留不进快照）：

| 层 | 默认预算 | 内容 |
|---|---|---|
| SKILL_INSTRUCTION | —（硬保留） | 类路径技能包指令，恒 INJECTED |
| KNOWLEDGE_ENTRY | 3072 | 知识条目（title+content） |
| REPOSITORY_CHUNK | 4096 | 仓库片段（同候选各 fragment content 求和） |
| MEMORY_PREFERENCE | 1024 | 辅助记忆值 |

总预算默认 8192，**硬上限 32768**（超出拒绝启动）；层值 ≥0 且层和 ≤总额（`InjectionBudgetProperties.validatedLimits` 启动期 fail-fast；domain limits 本身只校验非负——两阶段裁剪的可达性由装配期构造性保证）。

**两阶段裁剪**（`InjectionBudgetPlanner` 纯函数，输入为每层 token 代价有序表）：

1. **层内尾裁**：每层按序累加，超出层预算的最长前缀之后全部裁掉，TrimRecord reason = `"layer budget exceeded"`；
2. **总额尾裁**：层内幸存者若层和超总额，按 **MEMORY_PREFERENCE → REPOSITORY_CHUNK → KNOWLEDGE_ENTRY** 自最低层尾裁，reason = `"total budget exceeded"`。

被裁候选以 **CANDIDATE** 阶段入清单（不丢弃证据）；`PromptBudgetSnapshot` = (配置总额, 三层 INJECTED 实际估算值)。

**Token 估算冻结公式**：`estimate(text) = max(ceil(utf8Bytes/4), ceil(codePoints/3))`，下限 1。ASCII 按 /3 宁高估、CJK 恰 1 token/字。**估算口径=渲染形**：候选按转义后文本（`& < >` 与渲染器同一张转义表，单源 `InjectionTextEscaper`）计价，每候选/每 fragment 计 64 token 坐标头开销（ASCII 头 ≤192 字符，/3 上界 64；真实头 ≤~165 字符），分区标签与 preamble 共计 100 token 从总额扣除。副产品：渲染字符 ≤ 3×总预算、与候选数无关（每候选头开销 192 字符定价对 165 字符真实头），8192 预算 ⇒ 注入块 ≤~24.6k 渲染字符，加基础指令仍在 `CodingSpecialistRound` 30,000 字符界内（越界构造器自抛 = 天然失败关闭）。

## 3. 清单语义

- **attempt 粒度、跨 round 无状态**：每 round 调一次 `assemble`；round 1 miss → append 封存；append 冲突 → `findByAttempt` 收敛到先封者；round ≥2 / 恢复 → 读回已封清单。
- **收敛过滤**：最终注入一律按**已封清单 INJECTED 四元组**（type+sourceId+version+contentHash）过滤新鲜候选——注入 ⊆ 已封证据，永不超封；知识中途变更导致的多余新鲜候选静默不注入（无降级码可用，本条即冻结约定；后续轮次 chunk 相邻合并边界变化产生新四元组属同族语义——按片段四元组比对，新四元组不在封存集即不注入，方向只缩不越）。
- **降级三码透传**：检索降级码（RETRIEVAL_DISABLED / NO_MATCHING_GENERATION / EMBEDDING_PROVIDER_UNAVAILABLE）原样进清单 `degradations`。
- **失败关闭分界**：检索授权/业务预算失败上抛、不清单、不注入；清单 append 的非冲突 DB 失败同样上抛 → openRound 失败 → `CODING_RUNTIME_FAILED`（可重试，新 attempt 新清单）。

**ManifestSourceRef 派生**：

| 层 | sourceId | version | contentHash |
|---|---|---|---|
| KNOWLEDGE_ENTRY | `entryId` | revision | 命中自带的哈希 |
| REPOSITORY_CHUNK | `path#startLine-endLine`（每 fragment 一条） | generationBuildSequence | fragment 哈希 |
| MEMORY_PREFERENCE | `memoryKey` | **policy 版本**（entry.version 从 0 起，违反 ref≥1） | sha256(value) 服务端现算 |
| SKILL_INSTRUCTION | `CodingSpecialistSkillBundle.SKILL_ID` | 1 | bundle SHA_256 常量（装配处与运行时校验同一常量，单源不漂移） |

引用顺序：skill 先，然后每层 kept(INJECTED) + trimmed(CANDIDATE)。

## 4. 接线（仅 Coding Worker 链）

- **一点接线**：`WorkerCodingSpecialistAuthorityGateway.openRound`——`requireWorkspace` 后、session open 前组装注入（失败不留半开 session），渲染块以 `\n\n` 缀于原指令之后（原权威句逐字保留；空块 = 开关关闭 = 指令不变）。
- **注入 actor = 任务创建者**：`facts.task().audit().createdBy()` 经 `PrincipalRepository` 解析，要求 USER 且 `canAct`；`TeamAccessContext(actor, false)`。**检索授权者 = 记忆所有者 = 任务发起成员**，一个身份贯穿三层。创建者撤权/失格中途 → 该执行失败关闭（ADR-038：不得继续发送已撤权上下文）。`injection.enabled=false` 是操作者总闸，**关闭时连创建者都不解析**（零行为变化：不可用的创建者不能拖垮一个本不含注入的轮次）；组装先于 lease 校验失败关闭，但封存与 touch 只发生在 lease 复查通过之后。
- **检索 query**：objective + acceptance criteria 复合（与 instruction 复合口径一致），超 33000 字符退化 objective-only；sources 恒 {KNOWLEDGE_ENTRY, REPOSITORY_CHUNK}（CodingTargetSnapshot 构造期强制活跃绑定）。

## 5. 开关矩阵（三键正交）

| `knowledge.injection.enabled` | `knowledge.retrieval.enabled` | `memory.enabled` | 行为 |
|---|---|---|---|
| false | * | * | 不组装、不解析注入 actor、不清单、无注入块（指令不变，零行为变化） |
| true | false | true | 清单照封 + `RETRIEVAL_DISABLED` 降级码；记忆层正常 |
| true | true | false | **不调 list**：记忆层零调用零引用零 token（I02a「不新增模型向读」语义），知识/片段层正常 |
| true | true | true | 全三层 |

服务恒装配（开关是服务内布尔，非 bean 条件）；`memory.enabled` 与 I02a 生命周期共用同一键——一键一义。

## 6. touch 语义（注入 = 使用才续期）

仅**实际注入**的记忆条目调用 `touch`（false 返回容忍、异常忽略——续期绝不拖垮模型调用）；CANDIDATE/未注入条目不续期。这与 I02a「注入=使用才续期」配对：被裁掉的记忆不因被考虑过而续命。

## 7. 渲染块

不可信声明句（两句）+ `Evidence manifest <id>.` + 三转义分区 `<knowledge-entries>` / `<repository-fragments>` / `<assistant-memory>`（空层整段省略；三列表全空 → 整块为空）。每条目坐标头一行（`[knowledge entry <id> revision <n> hash <h>]` / `[<path> lines a-b commit <c> hash <h>]` / `[preference <key> version <n> hash <h>]`），正文 HTML 式转义 `& < >`（与 TaskPromptBoundary 同式）——检索内容无法伪造分区标签。
