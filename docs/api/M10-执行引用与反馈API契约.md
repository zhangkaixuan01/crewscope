# M10-I02c 执行引用与反馈 API 契约

> 状态：冻结（I02c 引用查询 + 「不适用」反馈 + 声明引用回执）<br>
> 版本：V1<br>
> 日期：2026-10-04<br>
> 适用范围：Coding 执行链注入证据的查询、「不适用」反馈与声明引用回执（OpenAPI 面 259 → 262）

## 0. 本轮边界

本契约覆盖 **I02c 执行引用查询与反馈**：一次执行的全部已封注入清单读回（CANDIDATE / INJECTED / 声明引用三区呈现）、成员对单个注入来源的「不适用」幂等反馈、模型声明引用的回执端点（⊆ 清单校验 + 持久化）。以下不在本契约内：

- **模型侧生产接线**：`POST /claimed` 是回执端点——接收 API 声明、校验、落库；不改 system prompt、不加输出 schema、不自动上报（生产接线属后续阶段）；
- **反馈的后续作用**：反馈不自动影响后续检索/注入/排序，不隐式全局废弃；团队聚合统计归 F03；
- **排除机制**：「后续可显式排除对应来源版本」的排除动作本身不在 I02c（仅保证反馈记录可供其消费）；
- **反馈撤销 / 他人反馈可见**：不可变行、无 DELETE；GET 只返回请求者本人反馈；
- **F01c 前端**：无 UI 消费面（本契约即其后端合同）；
- **清理/TTL**：见 §6；
- **非执行级引用面**：会话/编排链不适用（幂等键只锚 task 执行链）。

## 1. 数据模型（V60 两表，纯 PostgreSQL）

**`injection_reference_feedback`**（不可变行，无 updated_at）：

| 列 | 形状 |
|---|---|
| `id` | UUID PK |
| `organization_id`…`execution_id` | 六坐标列，由 `task_execution` 行派生（复合 FK → `uk_task_execution_scope_id`，`ON DELETE RESTRICT`，同 V59） |
| `source_type` / `source_id` / `source_version` / `source_content_hash` | 四元组列化（version BIGINT CHECK ≥1；hash CHAR(64) CHECK 64hex 小写） |
| `member_principal_id` | UUID，**无 FK**（反馈是历史质量意见，不阻 ADR-038 principal 生命周期；合法性由写入时 `requireVisibleTeam` 守卫承担） |
| `feedback_kind` | TEXT CHECK = `'NOT_APPLICABLE'`（S01 冻结单语义，列预留枚举位） |
| `created_at` | TIMESTAMPTZ |

幂等键 `uk_injection_reference_feedback_key UNIQUE (execution_id, 四元组, member_principal_id)`：同成员对同证据二次反馈在库层收敛（INSERT … ON CONFLICT DO NOTHING，0 行回读重放已存行——返回行 createdAt 为首次时间）。

**`injection_claimed_reference`**（不可变行）：

| 列 | 形状 |
|---|---|
| `id` | UUID PK |
| 六坐标 | 同上派生 + RESTRICT FK |
| `attempt` | INTEGER CHECK ≥1 |
| `claimed` | JSONB 数组（CHECK `jsonb_typeof='array'`；元素 `{type,sourceId,version,contentHash}`） |
| `created_at` | TIMESTAMPTZ |

幂等键 `uk_injection_claimed_reference_attempt UNIQUE (execution_id, attempt)`：同一 attempt 只有一张回执。**集合语义幂等**——存储前按 (type, sourceId, version, contentHash) 规范排序去重，相等性按集合比较（模型输出顺序不是可冲突的事实）：同集重放 200（返回已存回执），异集 409（details 携带已存集合）。

domain 值对象 `ManifestSourceKey(type, sourceId, version, contentHash)`（无 stage）：反馈与声明共用的四元组；紧凑构造校验同 `ManifestSourceRef`（sourceId 非空、version≥1、hash 64hex 归一小写）。

## 2. 端点（基路径 `/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/injection-references`）

### GET —— 引用查询（三区呈现）

200 no-store。无已封清单 → `attempts: []` 恒 200（恢复场景不重计，清单是历史事实）。

```json
{
  "executionId": "…",
  "taskId": "…",
  "attempts": [
    {
      "manifestId": "…",
      "attempt": 1,
      "createdAt": "…",
      "budget": {"totalTokens": 8192, "knowledgeTokens": 6, "chunkTokens": 0, "memoryTokens": 0},
      "degradations": ["RETRIEVAL_DISABLED"],
      "trims": [{"layer": "REPOSITORY_CHUNK", "trimmedCount": 2, "reason": "layer budget exceeded"}],
      "references": [
        {"type": "KNOWLEDGE_ENTRY", "sourceId": "…", "version": 3,
         "contentHash": "…", "stage": "INJECTED", "notApplicable": true},
        {"type": "MEMORY_PREFERENCE", "sourceId": "reply-language", "version": 4,
         "contentHash": "…", "stage": "CANDIDATE", "notApplicable": false}
      ],
      "claimed": [{"type": "KNOWLEDGE_ENTRY", "sourceId": "…", "version": 3, "contentHash": "…"}]
    }
  ]
}
```

- `attempts` 按 attempt 升序；`claimed` 键 `(executionId, attempt)` 天然并入 attempt 对象：**`claimed: null`（未提交回执）与 `claimed: []`（零声明）语义区分**；
- `notApplicable` 布尔 = **当前请求成员本人**是否已标记该四元组（反馈是个人质量意见，他人反馈不泄漏；团队聚合归 F03）。

### POST `/feedback` —— 「不适用」反馈（同步 200 no-store）

请求体 = 单条四元组 `{"type", "sourceId", "version", "contentHash"}`（version 为字符串）。锚定规则：四元组必须在该执行**全部 attempt 清单 INJECTED 集的并集**内——幂等键（执行/片段/成员）无 attempt 维度，只有并集与键形状自洽；被预算裁掉的 CANDIDATE 从未进入 Prompt，不是评判对象（422）。返回存储行（重放返回首次行）。SKILL_INSTRUCTION 在 INJECTED 集内不特判。

### POST `/claimed` —— 声明引用回执（同步 200 no-store）

请求体 `{"attempt": "1", "references": [四元组…]}`（attempt 为字符串，≥1）。每个声明四元组必须 ⊆ **该 attempt 清单**的 INJECTED 集（声明不可超出清单）；该 (execution, attempt) 无已封清单 → 422。空 `[]` 合法（零声明）。返回规范序回执；同集重放 200、异集 409。

## 3. 权限（执行可见者）

`WorkItemAccessPolicy.requireVisibleTeam` + task↔execution 归属校验（execution.taskId = 路径 task、scope 全等，形状同执行详情读面）。**归属校验先于清单锚定评估**——跨租户/跨任务探测与未命中同形 404，不泄漏执行存在性；载荷格式违约（解析失败 400 / 域校验 422）在控制器先于守卫评估，但对存在、缺失与跨租户目标同形，同样不构成存在性 oracle。三端点同一守卫。结构性幂等，无 Idempotency-Key（键 = 纯坐标）。

## 4. 错误码

| HTTP | code | 触发 |
|---|---|---|
| 400 | `invalid_request` | 路径 UUID / type 枚举 / version·attempt 数字解析失败（details.field） |
| 403 | `policy_denied` | 可见性守卫拒绝 |
| 404 | `aggregate_not_found` | Task/TaskExecution 未命中、归属不符、跨租户同形 |
| 409 | `injection_claimed_reference_conflict` | 同 (execution, attempt) 异集声明（details.storedClaimed = 已存集合紧凑串 `TYPE:sourceId:version:hash` 逗号表） |
| 422 | `invalid_value` | 四元组域校验违约（DomainValidationException 既有映射） |
| 422 | `feedback_reference_outside_manifest` | 反馈四元组不在任何 attempt INJECTED 并集（details.source） |
| 422 | `claimed_reference_outside_manifest` | 声明越出该 attempt 清单 INJECTED 集（details.outside） |
| 422 | `injection_manifest_not_sealed` | 该 (execution, attempt) 无已封清单可锚定 |

## 5. 开关矩阵（端点不门控）

三端点**零开关门控**：已封存清单是历史事实，读面/反馈/回执不依赖 `knowledge.injection.enabled` / `knowledge.retrieval.enabled` / `memory.enabled`（I01c「闸关读面恒可用」先例；装配测试钉全关属性下 bean 恒在）。注入链自身的开关组合行为见 I02b 契约 §5。

## 6. 保留策略（plan §10.11 第 7 条冻结）

无 TTL、无主动清理；清单/反馈/回执随 `task_execution` 的 `ON DELETE RESTRICT` 生存期存续——证据在，执行不可删；清理策略（若引入）属 F03/Q01。零领域事件/零审计事件/不走 outbox。
