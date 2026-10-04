# M10-A03a Skill 目录与审核 API 契约

> 状态：冻结（A03a 目录/审批版本合同）<br>
> 版本：V1<br>
> 日期：2026-10-04<br>
> 适用范围：Team Skill 的目录管理命令面（手工草稿、审批发布、禁用、回滚）与管理面读取

## 0. 本轮边界

本契约覆盖**A03a：Skill 目录/审批版本合同**——创建草稿、改草稿、发布版本、禁用、回滚，以及头/版本的读取。以下能力不在本契约内：

- **A03b**：真实提炼端点（从 Task 执行蒸馏 SKILL.md、origin 填值、DISTILLATION 用量事件）、Factory 四点动态接线、注入清单动态 skillInstruction；
- **F02**：Skill 管理 UI（历史证据呈现、禁用原因展示）；**Q02**：攻击集；
- **I01**：Skill 不进知识索引（无 `indexStatus` 字段——Skill 是指令注入面而非检索面，与知识条目的关键差异）；
- **agent 模板层 `approved_skill_keys`**（V20 旧 JSONB 列）：与本目录无任何搭接，A03a 不读不写。

## 1. 资源模型与 ETag

Team Skill 是 ADR-031 定义的聚合：一个**乐观锁头**（`team_skill`，携带权威生效版本指针）加一串**不可变版本行**（`team_skill_version`，只增不改）。与知识条目同构，差异见 §6。

| 资源 | 强 ETag 来源 | 语义 |
|---|---|---|
| 头 `GET /skills/{skillId}` | 头 `version`（零基整数，`"3"`） | 每次改头（改草稿/发布/禁用/回滚）+1；写头命令的 `If-Match` 必须匹配它 |
| 不可变版本 `GET /skills/{skillId}/versions/{revision}` | `contentHash`（64 位 SHA-256，`"<hex>"`） | 版本行永不变化，ETag 永不变 |
| 生效版本 `GET /skills/{skillId}/effective-version` | `contentHash` | 指针移动即换内容；未生效（DRAFT / DISABLED）时 404 |

并发冲突统一返回 **409 + `currentVersion`**（本 API 无 412）。

`contentHash = SHA-256("team-skill-version\0" + content)`——revision 不参与（回滚产出同 hash 的新版本行是合法形态，见 §6）。

## 2. 命令面（全部 202 回执协议）

幂等与命令恢复协议遵循 ADR-007：每个命令带 `Idempotency-Key`；写头命令另带 `If-Match: "<version>"`。成功返回 202 与回执信封（`commandId`/`domainEventId`/`committedVersion`/`correlationId`）；同 key 重放返回原回执并加 `Idempotency-Replayed: true` 头。**例外**：`updateDraft` 不发领域事件，其回执 `domainEventId` 位是 Skill 的稳定 id——用于命令恢复定位，不代表事件（与知识条目 §4 同款例外，理由同源：草稿不是生效内容）。

基路由：`/api/v1/organizations/{organizationId}/teams/{teamId}/skills`。

| 方法+路径 | 头 | 体 | 领域事件 |
|---|---|---|---|
| `POST /skills` | IK | `{skillKey, content}` | `TEAM_SKILL_CREATED` |
| `PATCH /skills/{skillId}` | IK + If-Match | `{content}` | **无**（见上） |
| `POST /skills/{skillId}/publish` | IK + If-Match | — | `TEAM_SKILL_VERSION_PUBLISHED` |
| `POST /skills/{skillId}/disable` | IK + If-Match | `{reason?}`（体可省） | `TEAM_SKILL_DISABLED` |
| `POST /skills/{skillId}/rollback` | IK + If-Match | `{toRevision}` | `TEAM_SKILL_VERSION_PUBLISHED`（携带 `rolledBackFromRevision`） |

`commandId` 可通过 `GET /api/v1/command-results`（M9b 创建结果契约）恢复；资源类型 `TEAM_SKILL` 的可见性复验规则 = 请求者仍是该 Team 的有效成员。

**没有 DELETE 端点**：目录项只禁用不删除（§3），这是与知识条目的合同级差异。

## 3. 版本生命周期

```
DRAFT ──publish──▶ PUBLISHED ──publish──▶ PUBLISHED（新修订）…
  │                    │
  │ disable（作废）     │ disable（下线）
  ▼                    ▼
DISABLED ──updateDraft + publish──▶ PUBLISHED（复活=发布新修订）
```

- **发布**把当前草稿铸成下一修订（revision 从 1 递增）并移动生效指针；发布后头上的草稿清空。
- **无变化发布是 422**（`skill_version_unchanged`）：草稿 contentHash == 当前生效版本 contentHash，或回滚目标 == 当前生效 revision——命令是 no-op，不是冲突。这与知识条目的 409 `knowledge_version_content_conflict`（uk 约束）不同：Skill 版本表**没有 contentHash 唯一约束**（§6），防线在命令级显式比对。
- **禁用可从 DRAFT（作废草稿）或 PUBLISHED（下线）到达**，带可选 `reason`（≤200 字符，存头，F02 呈现）。DISABLED 头保留 `effectiveRevision`（最后生效版本证据）且**保留未消费的草稿**；`effectivelyPublished()` 仅在 status==PUBLISHED 时为真——DISABLED 的 effective-version 端点 404。
- **没有 DELETED 墓碑**：禁用即下线，历史版本行全保留（F02 呈现完整审批证据）；复活 = `updateDraft` + `publish` 产出新修订。

## 4. 草稿文档合同（正文即真相）

`content` 是单一 markdown 文本——真实 SKILL.md 形状，A03b 装载时直接喂 runtime repository：

```markdown
---
name: deploy-runbook-v2
description: Marker d1 of the drill.
---

Body of the document.
```

约束（422 `invalid_value`，域层校验）：

- 以 `---` 开头的平面 frontmatter，恰好含 `name` 与 `description` 两字段（`key: value` 行，无嵌套 YAML——复杂结构显式拒绝，domain 零依赖手写解析）；
- **`name` 必须等于 `skillKey`**（key 与文档自述一致，防漂移）；
- `description` ≤200 字符；`content` ≤65536 字符；尾部空白被 strip；
- `skillKey`：`[a-z0-9][a-z0-9-]{0,62}`，且保留名 `java-spring-v1`（内置 Coding Specialist bundle 同名，防遮蔽）被拒绝（422，非 409）。

头响应的 `draft.name` / `draft.description` 是即时重解析的派生值，不单独存储——快照只存全文。

## 5. 读取面与权限

读取（头/列表/版本）= Team 有效成员即可，无需 SKILL_MANAGE（与知识条目 §7 同构）。写命令（§2 全部 5 个）要求 **SKILL_MANAGE**：

| 权限 | 授予 |
|---|---|
| `SKILL_MANAGE` | TEAM_OWNER（V52）+ TEAM_ADMIN（**V61 新增**，幂等回填存量行） |

- 平台管理员直通；成员走角色 + 授权过滤（ACTIVE/effective/grantable）后 anyMatch。
- 「提炼发起 = 本人执行的选择权」留给 A03b 提炼入口，本轮全部写命令统一 SKILL_MANAGE 门。
- 跨租户访问同形 404（不泄露存在性）。

## 6. 回滚语义与知识条目的差异

| 维度 | 知识条目（A02） | Team Skill（A03a） |
|---|---|---|
| 回滚端点 | 无 | `POST /rollback`：激活历史内容为**新修订**（revision=latest+1，content=目标版本原文，contentHash 同值合法） |
| contentHash uk | 有（同内容重发布 409） | **无**——回滚合法产出同 hash 版本行；「无变化」由命令级比对挡（422） |
| 披露扫描 | 仅 origin（提炼）条目扫 | **无条件**：publish 对全部内容（frontmatter+正文）执行披露扫描——Skill 是指令注入面，受众是未来全 Team 任务的 prompt，比知识条目更严 |
| 披露错误 | 403 `knowledge_disclosure_denied` | 403 `skill_disclosure_denied`（同面 POLICY） |
| 删除 | 有（DELETED 终态墓碑） | **无**：禁用即下线，历史全保留 |

## 7. 错误码表

| HTTP | code | 场景 |
|---|---|---|
| 409 | `skill_key_conflict` | 同 Team 下 skillKey 已存在 |
| 409 | `optimistic_lock_conflict` | If-Match 落后，携带 `currentVersion` |
| 403 | `skill_disclosure_denied` | 发布内容命中高置信凭据模式族（无条件扫） |
| 403 | `policy_denied` | 无 SKILL_MANAGE / 非有效成员 |
| 422 | `skill_version_unchanged` | 无变化发布或回滚目标==当前生效 |
| 422 | `skill_disabled` | 写命令在开关关闭时（见 §8） |
| 422 | `invalid_value` | frontmatter/name≠key/超界/保留名等域校验 |
| 400 | `invalid_request` | 路径/参数/header 形态错误 |
| 428 | `precondition_required` | 写头命令缺 If-Match |
| 404 | `not_found` | 资源不存在或跨租户 |

## 8. 开关矩阵（crewscope.skill.enabled，默认 false）

| 面 | 开关 true | 开关 false |
|---|---|---|
| 5 个写命令 | 正常 | **422 `skill_disabled`**（守卫最先执行，先于权限与幂等储备） |
| 头/列表/版本读取 | 正常 | **正常**（历史目录/证据在授权下恒可查看） |
| 服务装配 | — | **恒装配**（开关只是构造 boolean，照 agent-memory 模式；A03b 接线前不翻默认） |

## 9. 事件（v1 冻结）

| 事件 | 载荷 | 消费者 |
|---|---|---|
| `TEAM_SKILL_CREATED` | skillId, skillKey, description | F02/F03 预留（本轮无异步消费） |
| `TEAM_SKILL_VERSION_PUBLISHED` | skillId, skillKey, revision, contentHash, `rolledBackFromRevision?`（回滚复用同一事件） | 同上 |
| `TEAM_SKILL_DISABLED` | skillId, skillKey, `reason?` | 同上 |

`updateDraft` 不发事件（§2）。A03a 零 LLM 调用、零 `MODEL_USAGE_FACT_RECORDED`；A03b 起提炼命令按 attempt 记录 `MODEL_USAGE_FACT_RECORDED`（role=DISTILLATION，§11）。

## 10. A03b 预留（已交付）

- **origin 列已建、恒空**：`team_skill.source_task_execution_id` / `source_execution_attempt`（全有全无 CHECK）——A03b 提炼入口填值无需迁移；头响应 `origin` 字段本轮恒 `null`。**A03b 交付**：提炼命令回填 origin，头响应 `origin` 对提炼产物非空。
- **注入引用三元组**：发布版本 `(skillKey, revision, contentHash)` 三列齐备，可直接构造 `ManifestSourceRef`——A03b 注入清单动态 skillInstruction 的数据合同已冻结。**A03b 交付**：执行时动态 skill 以 INJECTED `SKILL_INSTRUCTION` refs 密封进注入清单（load 证据），与加载仓库同源（D2）。

## 11. A03b 提炼端点

`POST /api/v1/organizations/{organizationId}/teams/{teamId}/skills/distillations`（IK 必填）

- **请求**：`{"taskExecutionId": UUID, "skillKey": string}`。skillKey 为命令参数（D4：调用方定键，模型只产 description≤200 与 body≤65536，服务端组装 frontmatter `name: {skillKey}`）。
- **回执**：同步 202（蒸馏 LLM 调用 + DRAFT 提交 + 用量事实全部落库后返回）；body 含 `commandId / domainEventId / committedVersion / correlationId / skillId / skillKey / status（恒 DRAFT）/ origin{taskExecutionId, attempt}`；幂等重放带 `Idempotency-Replayed: true`，重放不回 skillId 但 skillKey 恒回。
- **权限**：**本人选择权**——仅任务创建者（platformAdmin 旁路）可发起，成员的 SKILL_MANAGE 不授予提炼权；发布仍是 §2 的 SKILL_MANAGE 命令（D6：产物=DRAFT，不自动发布）。
- **前置**：执行 COMPLETED 且为当前 attempt、任务/执行归属同 scope、Team 就绪、内置 skill-distiller@1 模板与配置链可用（首用惰性 provisioning）。
- **错误**：403 `policy_denied`（非创建者/成员失效）；404 跨租户同形；409 `skill_key_conflict`（任何状态的同名键）；422 `invalid_value`（未完成执行/超界/开关关闭 `skill_disabled` 等）；400 `invalid_request`。
- **开关矩阵（A03b 补行）**：`crewscope.skill.enabled=false` → 提炼 422、动态加载空、读面通；`crewscope.injection.enabled=false` → 动态 skill 不加载不进清单（D3：加载证据载体是注入清单），**内置 Coding Skill 照常**（M9 行为）；检索开关与 Team Skill 独立互不影响。
- **执行接线（对 API 不可见但属交付面）**：Coding Factory 按 PolicySnapshot 钉住的 approvedSkillKeys 构建 allow-list（内置恰 1 + 动态 keys），动态内容经只读内存仓库并入 SkillLoadTool catalog；九项工具硬禁用与 bundle pin 不因动态 skill 移动。
