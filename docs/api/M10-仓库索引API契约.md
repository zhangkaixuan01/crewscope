# M10-I01 仓库索引与知识索引控制面 API 契约

> 状态：冻结（I01c 控制面 + 读面）<br>
> 版本：V1<br>
> 日期：2026-10-04<br>
> 适用范围：Team 知识索引统一作业模型的控制面（重建/仓库构建/取消）与读面（作业列表/详情）

基路径：`/api/v1/organizations/{organizationId}/teams/{teamId}/knowledge/index`

## 0. 本轮边界

本契约覆盖 **I01c 统一作业控制面**：知识条目与仓库双来源共用一张持久作业表（I01b 交付），本契约冻结其上的五个 HTTP 操作、权限模型、错误码、幂等语义与开关矩阵行为。以下不在本契约内：

- **A02 条目管理面**：`indexStatus` 三值投影在《M10-知识库API契约》§5 冻结，本文 §4 只补作业终态如何投影；
- **A01 检索消费**：向量近邻检索的读 API 不在本轮；
- **I02 Prompt 组装**：检索结果的注入清单不在本轮；
- **F01b 前端**：仅消费本文冻结的形状（`openapi.ts` 生成物已含全部五操作）。

## 1. 统一作业模型（双来源）

一张 `knowledge_index_job` 表承载两类来源，target 形状互斥（DB CHECK 闭环）：

| 来源 | target | 关键坐标 | 生效内容何时定格 |
|---|---|---|---|
| `KNOWLEDGE_ENTRY` | `(org, team, entryId)` | 条目 id | **claim 时**经权威闸重读生效修订——入队不捕获修订，过期事件不能复活已废弃内容 |
| `REPOSITORY` | 六元组索引键 | `(org, team, bindingId, commit, chunkPolicyHash, embeddingModelRevision)` | **入队时冻结**——模型解析与分片策略哈希随键落库，此后换模型/调策略=新键=新 Generation |

状态机（文字图）：

```
QUEUED ──claim──▶ CHUNKING ──▶ EMBEDDING ──▶ ACTIVATING ──▶ READY（终态）
   │                 │            │             │
   │  （租约过期由 claim CTE 重领：attempt+1、claimToken+1） │
   │                 └────────────┴─────────────┴──▶ FAILED（终态，必带 failureCode）
   └──cancel──▶ CANCELLED（终态，必带 failureCode=CANCELLED）
```

- **claim** = `attempt+1`、`claimToken+1`（单调围栏计数，V30 先例）、安装租约（默认 30 分钟，`CREWSCOPE_KNOWLEDGE_INDEX_WORKER_LEASE`，5s–1h）；每个作业有自己的 token 空间。
- **终态无租约**；QUEUED 之外的活态必带租约（DB CHECK 闭环）。
- **checkpoint 续传**：每批嵌入提交后记 `(chunkSeq, chunkCount)` 批尾位置；租约过期重领后从 `max+1` 续传，不重复嵌入；旧 token 的 checkpoint 写入被 EXISTS 门拒绝。
- **不建状态机聚合**：作业是持久事实而非领域聚合——迁移/告警/恢复全部面向表与失败码，没有 TRANSITIONS map（主计划 §10.9 裁定）。

## 2. 命令面（三个触发端点）

| 方法+路径 | 权限 | 成功 | 体 |
|---|---|---|---|
| `POST /rebuilds` | KNOWLEDGE_MANAGE | **202** `{"enqueued": n}` | — |
| `POST /repository-builds` | KNOWLEDGE_MANAGE | **202** `{"enqueued": 0\|1, "job": …\|null}` | `{projectId, bindingId, commit}` |
| `POST /jobs/{jobId}/cancel` | KNOWLEDGE_MANAGE | **200** 作业快照 | — |

- `rebuilds` 为每个**无活作业的生效版本**种子 refresh 作业并返回新建数；异步执行故 202。
- `repository-builds` 的 `enqueued:1` 附作业快照（新建或坍缩到同键活作业）；`enqueued:0`+`job:null` 表示 refresh 闸关闭——**跳过不是错误**（§7）。
- `cancel` 是同步状态变更（对齐 M8 `github-imports/{jobId}/cancel` 200 先例），三分语义：
  - 作业不存在/跨租户 → **404**（同形，不泄露存在性）；
  - 已 CANCELLED → **200** 原样重放（幂等）；
  - QUEUED → 条件更新；若 claim 赢了竞争则重读一次仍非 CANCELLED → **409**；
  - 其余状态（已领取/READY/FAILED）→ **409**。

**幂等是结构性的，不需要 `Idempotency-Key` 或 `If-Match`**：同 target 的活作业受部分唯一索引坍缩（`ux_knowledge_index_job_entry_live` / `ux_knowledge_index_job_index_key_live`），重复触发返回同一活作业；取消对终态幂等。并发 create 竞争在服务层收敛到赢家（23505 翻译为活作业查询重读），**竞争永不以错误形态暴露**。本 API 无命令回执存储，IK 是假协议，故不要求。

**入参校验链**（防跨组索引污染的三重防线）：
1. `bindingId` 经四坐标 `(org, team, projectId, bindingId)` 归属查找——不存在/跨租户同形 **404** `aggregate_not_found`；
2. binding 非 ACTIVE（DISABLED）→ **422** `invalid_value`（field=`repositoryIndex.bindingId`）；
3. `commit` 必须 40/64 位十六进制 → 否则 **400**（field=`commit`）。

**commit 可读性不预验**：控制面不触碰 git；mirror 缺失/分支不可读由 Worker 侧终态失败码兜底（`REPOSITORY_UNAVAILABLE` / `REPOSITORY_READ_FAILED`），运维处置见手册「作业故障恢复」。

## 3. 读取面（Team 有效成员即可）

| 方法+路径 | 权限 | 成功 | 缓存 |
|---|---|---|---|
| `GET /jobs?source=&status=&after=&limit=` | 有效成员 | **200** `{"items": […], "nextAfter": "…"\|null}` | `no-store` |
| `GET /jobs/{jobId}` | 有效成员 | **200** 作业快照 | `no-store` |

- `source`：`KNOWLEDGE_ENTRY` / `REPOSITORY`；`status`：七值之一；未知值 **400**。
- 分页是 **keyset**：按 `(createdAt, id)` 升序，`after` = 上一页 `nextAfter`（末条作业 id）；`limit` 1–100 默认 50；末页 `nextAfter: null`。`after` 必须解析为本 Team 的作业，否则 **400**（field=`after`）。
- 跨租户 id 与不存在 id 共享同一 404 形状（`knowledge_index_job_not_found`）。

**作业快照 DTO 封闭字段**：`id, source, status, entryId?, projectId?, indexKey?{bindingId, commit, chunkPolicyHash, modelKey, modelRevision}, attempt, chunksDone, chunksTotal, failureCode?, generationBuildSequence, claimedBy?, leaseExpiresAt?, createdBy, createdAt, updatedAt`。

- **不含 `claimToken`**：它是 Worker 写门的内部围栏凭据，暴露它只会诱导客户端做 worker 的事；
- **含 `claimedBy` / `leaseExpiresAt`**：恢复排障的第一问题就是「谁持有租约、何时过期」；
- `indexKey` 以分解字段呈现，不暴露 canonical 串；
- `failureCode` 是开放词表：九个封闭常量（含 `CANCELLED`）加上 sanitize 后的模型连接健康码，≤80 字符。

## 4. Team 知识索引状态

条目读面上的 `indexStatus`（`PENDING`/`INDEXED`/`FAILED`）在《M10-知识库API契约》§5 冻结。本文补**作业终态如何投影**：

- `READY` + 向量行存在 → `INDEXED`；
- 最近作业终态 `FAILED` → `FAILED`；
- 其余一切（未入队、进行中、作业已排队）→ `PENDING`；
- 未部署向量存储的部署恒 `PENDING`（投影目录本身不装配）。

仓库来源没有条目读面；其健康经本文作业列表观察（`source=REPOSITORY`），Generation 激活状态由激活序列（`generationBuildSequence`）与保留策略（成功后保留 2 代）管理。

## 5. 权限

- **读**（列表/详情）：组织 USER Principal + 该 Team 有效成员；
- **命令**（rebuild/repository-build/cancel）：另需 `KNOWLEDGE_MANAGE`（TEAM_OWNER/TEAM_ADMIN 内置角色携带）；平台管理员 bypass；
- 403 响应不泄露策略事实（统一 `policy_denied`）；
- 守卫在 application 服务层（与知识命令面/蒸馏面同形），控制器不含权限逻辑。

## 6. 错误码表

| HTTP | code | 触发 |
|---|---|---|
| 400 | `invalid_request` | 路径/枚举/`after`/`limit`/`commit` 解析失败（details.field 指名） |
| 403 | `policy_denied` | 非组织用户/非有效成员/无 KNOWLEDGE_MANAGE |
| 404 | `aggregate_not_found` | bindingId 四坐标未命中（含跨租户同形） |
| 404 | `knowledge_index_job_not_found` | jobId 不存在或跨租户（details.jobId） |
| 409 | `knowledge_index_job_not_cancellable` | 已领取或非 CANCELLED 终态（details.jobId+status） |
| 422 | `invalid_value` | binding 非 ACTIVE（field=`repositoryIndex.bindingId`） |

`failureCode`（作业字段，非 HTTP 错误码）：`REPOSITORY_UNAVAILABLE`、`REPOSITORY_READ_FAILED`、`MODEL_DRIFT`、`CHUNK_TOO_LARGE`、`CHUNK_LIMIT_EXCEEDED`、`CANCELLED` 等九封闭常量 + 模型连接健康码。

## 7. 开关矩阵行为

| `index` / `vector` | 列表/详情 | rebuild / repository-builds | cancel | 健康指示器 |
|---|---|---|---|---|
| 双 true | 200 | 202，正常入队 | 200 | UP |
| index=false（vector 任意） | **200**（历史数据恒可读，V55 恒在默认链） | **202 `enqueued:0`**（跳过不是错误，不 503） | 200（管理操作不门控） | UP/按组合 |
| index=true + vector=false | 200 | 202 `enqueued:0`（非法组合不入队） | 200 | **DOWN**（带 reason） |

控制面服务与控制器**恒装配**：闸的行为全部由入队服务布尔决定，部署形态不影响命令面存在性。

## 8. 恢复与运维

失败重试（幂等坍缩）、卡死租约（自动重领+围栏，勿手改作业行）、排队取消（三分语义）、开关排障（`enqueued:0` 不是故障）与模型/策略变更（新 Generation 原子激活）的处置步骤见《Team-Beta单机运维手册》「作业故障恢复」小节；健康首站 `/actuator/health` 的 `knowledgeIndex`/`knowledgeVector` 组件。

## 9. 审计关联

控制面命令**不发领域事件、不注册审计事件**（主计划 §10.13 口径）：作业本身即持久事实。可追溯性 = 作业快照的 `createdBy` + EMBEDDING 用量事实事件（ADR-030 §7）；取消与触发不产生额外计费面。
