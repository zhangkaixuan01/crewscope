# M10-A02 知识库 API 契约

> 状态：冻结（A02a 管理面 + A02b 蒸馏面）<br>
> 版本：V2<br>
> 日期：2026-10-02<br>
> 适用范围：Team 知识条目的人工录入管理命令面，与从 Task 执行提炼草稿的蒸馏命令面

## 0. 本轮边界

本契约覆盖**人工录入管理**（A02a：创建草稿、改草稿、发布/废弃版本、删除条目、管理面读取）与**来源提炼**（A02b：从已完成 Task 执行提炼 DRAFT 草稿、发布前披露检查、DISTILLATION 用量事实事件）。以下能力不在本契约内：

- **I01/A01**：检索索引、embedding、向量库——`indexStatus` 投影已由 M10-I01b 接管（枚举 `PENDING`/`INDEXED`/`FAILED`，未部署向量存储时恒 `PENDING`，见 §5）；
- **I01/I02 来源扩展**：仓库内容、个人记忆提炼（本轮来源仅 TaskExecution）；
- **F01**：前端 UI；**F03**：用量聚合投影与价格核算（事件形状见 §12，消费归 F03b）；
- **A03**：Skill 提炼。

## 1. 资源模型与 ETag

知识条目是 ADR-030 定义的聚合：一个**乐观锁头**（`knowledge_entry`，携带权威生效版本指针）加一串**不可变版本行**（`knowledge_entry_version`，只增不改）。

| 资源 | 强 ETag 来源 | 语义 |
|---|---|---|
| 条目头 `GET /entries/{entryId}` | 头 `version`（零基整数，`"3"`） | 每次改头（改草稿/发布/废弃/删除/改分类）+1；写命令的 `If-Match` 必须匹配它 |
| 不可变版本 `GET /entries/{entryId}/versions/{revision}` | `contentHash`（64 位 SHA-256，`"<hex>"`） | 版本行永不变化，ETag 永不变 |
| 生效版本 `GET /entries/{entryId}/effective-version` | `contentHash` | 指针移动即换内容；未生效时 404 |

并发冲突统一返回 **409 + `currentVersion`**（本 API 无 412）。

## 2. 命令面（全部 202 回执协议）

幂等与命令恢复协议遵循 ADR-007：每个命令带 `Idempotency-Key`；写头命令另带 `If-Match: "<version>"`。成功返回 202 与回执信封（`commandId`/`domainEventId`/`committedVersion`/`correlationId`）；同 key 重放返回原回执并加 `Idempotency-Replayed: true` 头。**例外**：`updateDraft` 不发领域事件，其回执 `domainEventId` 位是条目的稳定 id——用于命令恢复定位，不代表事件。

| 方法+路径 | 头 | 体 | 领域事件 |
|---|---|---|---|
| `POST /…/knowledge/entries` | IK | `{entryKey, category, title, content}` | `KNOWLEDGE_ENTRY_CREATED` |
| `PATCH /…/knowledge/entries/{entryId}` | IK + If-Match | `{title, content, category?}` | **无**（见 §4） |
| `POST /…/knowledge/entries/{entryId}/publish` | IK + If-Match | — | `KNOWLEDGE_VERSION_PUBLISHED` |
| `POST /…/knowledge/entries/{entryId}/retire` | IK + If-Match | — | `KNOWLEDGE_VERSION_RETIRED` |
| `DELETE /…/knowledge/entries/{entryId}` | IK + If-Match | — | `KNOWLEDGE_ENTRY_DELETED` |
| `POST /…/knowledge/distillations` | IK | `{taskExecutionId, entryKey, category?}` | `KNOWLEDGE_ENTRY_CREATED` + N×`MODEL_USAGE_FACT_RECORDED`（见 §11-§12） |

`commandId` 可通过 `GET /api/v1/command-results`（M9b 创建结果契约）恢复；资源类型 `KNOWLEDGE_ENTRY` 的可见性复验规则 = 请求者仍是该 Team 的有效成员。

## 3. 版本生命周期

```
DRAFT ──publish──▶ PUBLISHED ──retire──▶ RETIRED ──publish──▶ PUBLISHED …
   │                   │                    │
   └──── delete ───────┴────────────────────┴───▶ DELETED（终态，永不可再发布）
```

- **发布**把当前草稿铸成下一修订（revision 从 1 递增）并移动生效指针；发布后头上的草稿清空。
- **同内容重发布是 409**（`knowledge_version_content_conflict`）：同一 entryKey 下相同 title+content 的 contentHash 已存在，这是域既定语义（uk 约束 + 域异常双保险），**不是缺陷**。改分类不需要新版本：`category` 走 `PATCH` 草稿命令，随头乐观锁提交，且不进 contentHash。
- **废弃**保留 `lastEffectiveRevision` 用于归因，条目退出可检索集合；RETIRED 可再发布新修订复活。
- **删除是不可逆墓碑**：头与版本行保留归因，但永远 404 于 effective-version、永不可再发布。
- **发布带 origin 的草稿多一道披露检查**（A02b，命令级前置校验）：title/content 命中高置信凭据模式族 → 403 `knowledge_disclosure_denied`，详见 §11.4。

## 4. updateDraft 为什么没有事件

草稿永不可检索（ADR-030 §2：只有 PUBLISHED 头 + 生效指针命中才可检索），因此改草稿不改变可检索集合，索引消费者（I01）不受影响——`PATCH` 不发领域事件、不写 outbox，只在命令回执表记账。发布/废弃/删除才发事件驱动索引失效与重建。

## 5. indexStatus 投影（M10-I01b 已接管，枚举冻结）

所有读响应（头详情、版本、列表）恒带 `"indexStatus"` 字段，值域已冻结为三值枚举 `PENDING` / `INDEXED` / `FAILED`。语义：

- `INDEXED`：该坐标已有向量行——条目读=生效修订（`effectiveRevision`）已嵌入；版本读=该修订已嵌入且条目头为 `PUBLISHED`、生效指针恰命中该修订；
- `FAILED`：该条目最近一次索引作业终态失败（原因码如 `CHUNK_TOO_LARGE`/`CHUNK_LIMIT_EXCEEDED`/`MODEL_DRIFT`，由索引侧状态目录透出）；
- `PENDING`：其余一切——尚未入队、作业进行中、或部署未启用向量存储（`crewscope.knowledge.vector.enabled=false` 时恒 `PENDING`，与 I01b 之前的响应字节级一致）。
- 投影由索引侧 `KnowledgeIndexStatusCatalog` 派生计算（向量行存在性+最近作业状态，不落冗余列），本 API 永不自行计算；客户端不得将 `PENDING` 解释为"内容未保存"。
- 作业级状态、控制与恢复面（重建触发、作业列表/详情、取消、仓库索引构建）见《M10-仓库索引API契约》（I01c 控制面，与本读面共用上述枚举语义）。

## 6. 分类（category）

固定五值枚举，DB 存 `VARCHAR(16)` 并有 CHECK 闭环（V53），未知值在 400（请求级）或 422（域级）被拒：

`CONVENTION`（团队约定）/ `RUNBOOK`（操作手册）/ `DECISION`（决策记录）/ `GUIDE`（指南）/ `OTHER`（默认，V53 迁移前存量回填值）。

category 是**头字段**：可经 `PATCH` 修改（体里 `category` 缺省 = 保持不变），受头乐观锁保护，不产生新版本行。

## 7. 读取面（Team 有效成员即可，无需 KNOWLEDGE_MANAGE）

| 方法+路径 | 说明 |
|---|---|
| `GET /…/knowledge/entries?status=&category=&after=&limit=` | entryKey 升序 keyset 分页；`after` 为上一页末尾 entryKey；`limit` 1-100 默认 50；响应 `{items, nextAfter}`（末页 `nextAfter: null`） |
| `GET /…/knowledge/entries/{entryId}` | 头详情，ETag=头 version |
| `GET /…/knowledge/entries/{entryId}/versions?after=&limit=` | revision 升序 keyset 分页；`after` 为 revision 数字 |
| `GET /…/knowledge/entries/{entryId}/versions/{revision}` | 不可变版本全文，ETag=contentHash |
| `GET /…/knowledge/entries/{entryId}/effective-version` | 头为 PUBLISHED 时返回生效版本；否则 404（DRAFT 未发布、RETIRED 已废弃、DELETED 已删除同形 404，不泄露状态差异） |

DTO 闭合字段（头）：`id, entryKey, category, status, indexStatus, effectiveRevision, latestRevision, draft{title, content}?, version, createdAt, updatedAt, createdBy, updatedBy, origin{taskExecutionId, attempt}?`（origin 为 A02b 蒸馏条目专有，人工录入为 null，见 §11.2）。
DTO 闭合字段（版本）：`entryId, revision, previousRevision, title, content, contentHash, indexStatus, createdAt, createdBy`。

## 8. 权限

| 操作 | 要求 |
|---|---|
| 全部读取（§7） | Organization USER + Team 有效成员（`canParticipate`） |
| 全部命令（§2） | 读取要求 + `TeamPermission.KNOWLEDGE_MANAGE`（TEAM_OWNER / TEAM_ADMIN 内建角色携带） |

越权与失去成员身份统一 403 `policy_denied`，不泄露策略事实。

## 9. 错误码表

| HTTP | code | 触发 |
|---|---|---|
| 400 | `invalid_request` | 缺/坏 `Idempotency-Key`、弱 ETag 或通配 `If-Match`、未知 category/status、非法 entryKey/after/limit、超长 title/content（请求级 @Size） |
| 403 | `policy_denied` | 非成员读、无 KNOWLEDGE_MANAGE 命令 |
| 403 | `knowledge_disclosure_denied` | 发布带 origin 的草稿命中凭据模式族（§11.4；details 只有模式族名，绝不回显命中文本） |
| 404 | `aggregate_not_found` | 条目/版本不存在、生效版本不可解析（含跨租户同形） |
| 409 | `optimistic_lock_conflict` | If-Match 版本落后，`currentVersion` 携带实际头版本 |
| 409 | `knowledge_entry_key_conflict` | 同 (organization, team, entryKey) 已存在 |
| 409 | `knowledge_version_content_conflict` | 同内容重发布（§3，预期语义） |
| 409 | `invalid_state_transition` | 如 DELETED 再 publish、DRAFT 直接 retire |
| 409 | `idempotency_conflict` | 同 key 不同语义重试 |
| 422 | `knowledge_entry_*` 等域校验 | 绕过请求级校验的域不变量（如哈希/形状校验） |
| 422 | `invalid_value`（`details.field`=`distillation.taskExecutionStatus`） | 提炼来源执行未 COMPLETED |
| 422 | `invalid_value`（`details.field`=`distillation.taskExecutionId`） | 提炼来源不是 Task 的当前执行尝试（D6） |
| 422 | `invalid_value`（`details.field`=`distillation.sourceText`） | 净化事件流超 128k 上限——明确拒绝，不静默截断 |
| 428 | `precondition_required` | 写命令缺 `If-Match` |

## 10. 审计

四条领域事件全部注册审计（TEAM 类目 / SUCCEEDED / STANDARD 保留）：`KNOWLEDGE_ENTRY_CREATED`（entryKey+category）、`KNOWLEDGE_VERSION_PUBLISHED`（entryKey+revision）、`KNOWLEDGE_VERSION_RETIRED`（entryKey+retiredRevision）、`KNOWLEDGE_ENTRY_DELETED`（entryKey+lastEffectiveRevision）。

A02b 追加一条：`MODEL_USAGE_FACT_RECORDED`（MODEL 类目 / SUCCEEDED / EXTENDED 保留；record 类投影按事件类型解码，历史事件 payload 未动）。蒸馏条目的 `KNOWLEDGE_ENTRY_CREATED` 复用 A02a 形状，不携带提炼专用字段——「发生过提炼」由 origin 头字段（§11.2）与用量事实（§12）组合表达。

## 11. 蒸馏命令（A02b）

### 11.1 端点与回执

`POST /api/v1/organizations/{organizationId}/teams/{teamId}/knowledge/distillations`，头 `Idempotency-Key` 必须，体 `{taskExecutionId: UUID, entryKey: string, category?: string}`（category 缺省 = 让模型建议，见 §11.3）。权限 = 有效成员 + `KNOWLEDGE_MANAGE`（同 §2 命令面）。

命令是**同步等 LLM** 的 202 回执：调用返回时 DRAFT 条目、origin 与全部用量事实已提交。回执在 §2 信封上扩展：

```json
{
  "commandId": "…", "domainEventId": "…", "committedVersion": 0, "correlationId": "…",
  "entryId": "…", "entryKey": "postmortem-cache",
  "origin": {"taskExecutionId": "…", "attempt": 2},
  "indexStatus": "PENDING"
}
```

`entryKey` 由调用方指定（条目身份，不可变，跨 Team 内唯一——同 key 已存在则 409 `knowledge_entry_key_conflict`）；`origin` 是不可变归因（§11.2）。同 IK 重放返回原回执 + `Idempotency-Replayed: true`；**重放响应不重暴露 entryId/origin**（回执不带结果体），但请求自带的 entryKey 恒可回显定位条目。

### 11.2 origin：不可变归因头字段

蒸馏条目携带 `origin {taskExecutionId, attempt}`，落 V54 两列（`source_task_execution_id` + `source_execution_attempt`，CHECK 同空同非空且 attempt ≥ 1，执行查找走部分索引）。origin **终身不可变**：updateDraft/publish/retire/delete 全程保留；`GET /entries/{id}` 头 DTO 相应带 origin（人工录入条目 origin 为 null）。

### 11.3 模型与产物

提炼走**内置稳定 profile**：每 Team 惰性自动 provision 系统 knowledge-distiller profile（首个蒸馏命令时确保就绪），绑定 Team 默认 chat 模型，走完整治理链（governance/数据策略/价格门槛/凭据治理）——与 TeamObserver 同模式，无会话无工具，单轮结构化输出。产物**只能是 DRAFT**（title/content 由模型产出，人工可经 `PATCH` 修改后发布）；category 优先级 = 请求显式指定 > 模型 suggestedCategory（合法枚举名）> `OTHER`。

### 11.4 来源边界与披露检查

- **来源收紧**：只接受 Task 的**当前执行尝试**（`task.currentExecutionId()` 命中）且 status=COMPLETED；跨 Team 或不存在 404；非当前尝试 422（防旧尝试过时内容）。
- **输入**：净化事件流（TaskEvent 公开投影白名单渲染），上限 **131072 字符**，超限 422 明确拒绝。
- **披露检查**：发布带 origin 的草稿时（§2 publish 命令内、域状态机校验前），title/content 匹配高置信凭据模式族（aws_access_key_id / openai_api_key / github_token / github_pat / slack_token / google_api_key / pem_private_key）→ 403 `knowledge_disclosure_denied`。这是**辅助防线非零秘密保证**：刻意排除泛 base64、`password=` 等低置信形态（误报会封锁讲凭据轮换的正常手册）；错误 details 只含模式族名，绝不回显命中文本。

### 11.5 失败语义（四阶段序列）

校验事务（零副作用）→ 无事务 LLM 调用 → 用量事实小事务 → 条目提交事务（IK reserve 在此）。推论：

- **LLM 失败 ⇒ 无回执**：同 IK 重试 = 完整重试，产生新真实调用、新用量事实（S01「真实重试计入」）；
- **条目提交失败 ⇒ 用量事实已留存**（token 已花，必须记账）；
- **同 IK 并发双执行**：提交阶段 reserve 输家收敛为 replay；
- 用量事实事件的确定性 id 见 §12。

## 12. 用量事实事件（MODEL_USAGE_FACT_RECORDED）

统一形状（S01 §3.9）的首块落地：**到达提交阶段的蒸馏命令，按其真实 Provider 调用尝试逐条**发射（命令内前序失败尝试随最终成功一起入账）；`role` 枚举五值一次定义（`CHAT_PRIMARY`/`CHAT_FALLBACK`/`COMPACTION`/`EMBEDDING`/`DISTILLATION`），本轮只发射 `DISTILLATION`；EMBEDDING 发射点归 I01a。命令整体失败（LLM 异常，§11.5）不留事实——重试是新命令新事实，与「真实重试计入」的 S01 口径一致。

| 字段 | 值 | 说明 |
|---|---|---|
| 聚合 | `MODEL_USAGE_FACT` / `callId` / version=attempt | 聚合投影与价格核算归 F03b，本轮只入事件流与 outbox |
| `callId` | `nameUUIDFromBytes("io.crewscope/model-usage/"+commandId+"/"+attempt)` | 稳定调用身份=去重键；commandId 每次实际执行随机 |
| `eventId` | `nameUUIDFromBytes("io.crewscope/model-usage/event/"+callId)` | 确定性——outbox 至少一次投递下消费者按 eventId 幂等 |
| `role` | `DISTILLATION` | 业务角色枚举 |
| `attempt` | ≥1 整数 | 真实重试按 attempt 分列 |
| `providerKey` / `modelId` / `connectionId` / `connectionVersion` | 解析链产物 | modelId 优先运行时观察到的模型名，回退目录坐标 |
| `usage` | `{inputTokens, outputTokens, cachedTokens, totalTokens}` | **零值 = Provider 未回显该计数**，不是「调用免费」；totalTokens 恒为 input+output 自算；Provider 计数不一致（负数/cached>input）降级为 unreported（四计数全零） |
| actor / 时间戳 | 发起成员（USER）/ 事实发生时刻 | — |

「缺失计数标 UNKNOWN」是 F03b 价格聚合侧合同（价格表缺该模型时），不在事件形状内。
