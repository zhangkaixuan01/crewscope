# M10-A01 统一知识检索 API 契约

> 状态：冻结（A01 统一检索 + 预览面）<br>
> 版本：V1<br>
> 日期：2026-10-04<br>
> 适用范围：Team 知识条目与仓库分片的统一检索服务、唯一预览 HTTP 入口、离线质量门禁

基路径：`/api/v1/organizations/{organizationId}/teams/{teamId}/knowledge`

## 0. 本轮边界

本契约覆盖 **A01 统一检索**：两类知识来源（Team 知识条目 / 受管 Mirror 仓库分片）合流为一个候选序列的检索服务、S01 §3.7 冻结的唯一预览 HTTP 入口、以及三层离线质量门禁。以下不在本契约内：

- **I02 Prompt 组装**：候选如何进入注入清单、预算裁剪与四层优先级是 I02b 的职责；A01 只交付候选（S01 §4.2 三事实中的第一事实）；
- **F01b 前端**：无 UI 消费面；
- **Q01 真实环境验收**：延迟绝对阈值与 τ 复核在 Q01，本文 §7 只冻结测量口径与信息性输出；
- **内部端口**（`KnowledgeRetrievalService` 及其协作者）不是 HTTP 面，仅 §1 标注形状供代码导航。

## 1. 统一检索模型

**两类来源**（S01 §3.3）：

| 来源 | 检索坐标 | 可见性闸门 | 正文来源 |
|---|---|---|---|
| `KNOWLEDGE_ENTRY` | `(org, team)` | SQL 生效版本闸（`effective_revision`），多版本条目只有生效修订可命中；RETIRED 版本的向量行留在库中——闸门而非缺席负责隐藏 | 命中后按 `findVersion` 读回 title/content |
| `REPOSITORY_CHUNK` | 六元组索引键 + `ACTIVE` Generation | `GenerationCatalog.findActiveGeneration` 定位唯一生效代；BUILDING/RETIRED 代不可见 | 向量行冗余的 content 列（命中免回表） |

**候选形状**（`RetrievalCandidate`，封闭字段）：`source`、`rank`、`score`、知识命中投影（`entryId/revision/title/contentHash/content`）或仓库片段明细列表（每片段 `bindingId/commit/generationBuildSequence/chunkSeq/path/language/startLine/endLine/contentHash/content`）。无内部凭据类字段。

**检索流程**（服务内部七步）：守卫（成员级三查）→ 开关与协作者检查（§5）→ 查询文本 embed（治理链全走、EMBEDDING 用量事实照发）→ 知识路 nearest → 仓库路（仅当 repository 目标给定：binding 四坐标校验 → 六元组 Generation 定位 → chunk nearest）→ 合并排序（§2）→ 去重截断（§2）。

**内部端口**：`KnowledgeRetrievalService.retrieve(TeamAccessContext, org, team, KnowledgeRetrievalQuery)` 返回 `KnowledgeRetrievalResult`（`candidates + degradations`）。具体类非接口，恒装配（§6），对齐 `KnowledgeCommandService` 先例。

## 2. 排序、去重与预算

**三键排序**（冻结，可解释——每候选带 `rank/score/source` 供离线门禁复盘）：

1. `score` 降序；
2. 分差容差 `1e-9` 内 `KNOWLEDGE_ENTRY` 优先于 `REPOSITORY_CHUNK`；
3. 仍并列时版本新（知识修订号）/构建新（`generationBuildSequence`）优先。

**去重与合并**：

- 同知识条目多版本由 SQL 生效闸天然唯一；
- 同文件（同 `path`）的候选按 `startLine` 排序，区间相接或重叠（`next.startLine <= cur.endLine + 1`）合并为**一个候选**，片段明细保留在 `fragments` 列表内；合并只减不增候选数；
- **Top-K 截断在合并之后**（两路合流时全局排序后截断）。

**预算**：`topK` 默认 8，硬上限 20（`KnowledgeRetrievalQuery.MAX_TOP_K`）；查询文本长度上界 33000（复用 `EmbeddingClient.MAX_INPUT_CHARS` 口径）。

**τ 不截断**：检索服务不按分数截断候选——分数全量透出，由消费者决策；τ=0.55 是评测层判定值（S01 §4 冻结初值，Q01 复核），只在门禁测试/脚本内复算，**不是产品决策**。

## 3. 预览端点（唯一 HTTP 面）

```
POST /api/v1/organizations/{organizationId}/teams/{teamId}/knowledge/knowledge-retrieval:preview
```

S01 §3.7 冻结字面（冒号路由）；**仅此一个入口**，复用真实检索路径供离线评测与调试，**永不进入 Prompt 组装链路**。

**请求体**：

```json
{
  "query": "how do we deploy",          // 必填，1..33000 字符
  "sources": ["KNOWLEDGE_ENTRY", "REPOSITORY_CHUNK"],  // 可选，缺省见下
  "topK": 8,                            // 可选，1..20，默认 8
  "repository": {                       // 可选；sources 含 REPOSITORY_CHUNK 时必填
    "projectId": "<uuid>",
    "bindingId": "<uuid>",
    "commit": "<40/64 hex>"
  }
}
```

**sources 缺省语义**：未给 `repository` → `["KNOWLEDGE_ENTRY"]`（只搜当前可达的知识源）；给了 `repository` → 两源全开。`sources` 与 `repository` 必须 ⇔ 一致（显式声明 `REPOSITORY_CHUNK` 而无目标、或有目标而声明 `["KNOWLEDGE_ENTRY"]` 排除仓库 → 400）。

**响应**：恒 `200` + `Cache-Control: no-store`（含降级——降级不伪装成空结果）：

```json
{
  "candidates": [ { "source": "...", "rank": 1, "score": 0.87, "entry": {...}, "fragments": [...] } ],
  "degraded": [],                        // DegradationReasonCode 名单，见 §5
  "meta": { "topK": 8, "sources": ["KNOWLEDGE_ENTRY"] }   // 生效预算与来源，供评测重放
}
```

**错误码**：400 `invalid_request`（字段级 `field` 命名对齐 I01c：`query/sources/topK/repository/repository.commit`…）；403 `policy_denied`（守卫拒绝）；404 `aggregate_not_found`（binding 四坐标未命中或跨租户）；422 `invalid_value`（`repositoryIndex.bindingId` 非 ACTIVE）。

## 4. 授权

- **守卫**：与执行面同款三查（组织用户 → Team 存在且 ACTIVE → 发起者是 ACTIVE 成员），第三副本遵循蒸馏/控制面先例，抽共享留独立重构；
- **仓库目标**：binding 四坐标校验（org/team/project/bindingId 全匹配）——未命中或跨租户 404，非 ACTIVE 422，与 I01c 入队校验同形；
- **SQL 收敛先于 Top-K**：知识路=org+team 谓词+生效闸；仓库路=org+team+binding+六元组索引键+ACTIVE Generation 定位，全部内联进向量 nearest SQL——不做「全局 Top-K 返回后应用层过滤」；
- **成员级即预览级**：预览是「成员可在本 Team 发起执行的数据面」的调试复用，成员级授权即等价。**不做 WorkItem 责任交集**——那是 I02 注入 Scope 的职责（预览不进 Prompt 链路，无需执行 Scope）。

## 5. 降级矩阵

三码（`DegradationReasonCode`，I01 已冻结）× 两路独立降级语义：

| 触发条件 | degraded | 候选 | 语义 |
|---|---|---|---|
| `retrieval.enabled=false` | `["RETRIEVAL_DISABLED"]` | 空 | 跳过不是错误（对齐 I01c 闸关 202+0 先例） |
| 向量存储缺席（`vector.enabled=false` 等） | `["RETRIEVAL_DISABLED"]` | 空 | 同上——协作者可空是装配合同 |
| 查询 embed 失败（投递异常/域校验） | `["EMBEDDING_PROVIDER_UNAVAILABLE"]` | 空 | 两路都需要查询向量，整体降级 |
| 仓库路无 ACTIVE Generation（BUILDING 中/从未构建/已退役未接替） | `["NO_MATCHING_GENERATION"]` | 仅知识路候选 | **仅仓库路降级**，知识路照常返回 |
| 一路降级一路健康 | 该路码 | 健康路候选照常 | 恒 200，degraded 显式列出 |

## 6. 开关矩阵行为

| `retrieval.enabled` | `vector.enabled` | 行为 |
|---|---|---|
| false | 任意 | 恒空候选 + `RETRIEVAL_DISABLED`；健康指示器 UP（关闭是设计状态） |
| true | false | **非法组合**：空候选 + `RETRIEVAL_DISABLED`，且 `KnowledgeRetrievalHealthIndicator` **DOWN**（带 reason，对齐 KnowledgeIndexHealthIndicator 先例，不 fail-fast） |
| true | true | 正常检索；健康指示器 UP |

装配合同：`KnowledgeRetrievalService` 恒装配（内部降级，不因开关缺席而 NRE）；两向量存储经 `ObjectProvider` 解析可空注入；chunk 向量存储 bean 归属 `KnowledgeVectorConfiguration`（`vector.enabled` 单开关门，读服务与写 worker 共用），不在 worker 配置重复定义。

## 7. 离线质量门禁与阈值

**三层形态**（用户裁定）：

1. **CI 恒跑**——确定性假向量：application 服务测试（排序/去重/降级三码/守卫/binding/TopK/两路合流）+ infrastructure pgvector 集成（真 SQL：生效闸/租户隔离/binding 隔离/Generation 门/计划不落顺序扫描）；
2. **`S01B_DASHSCOPE_KEY_FILE` 门控 JUnit**（`KnowledgeRetrievalQualityGateTest`）——标注集 166 查询（`scripts/m10-s01/dataset/`）双 Team 灌注、真实 DashScope embedding（内容哈希缓存 `/tmp/a01-embed-cache.json`，key 经置零句柄消费）、走产品 `KnowledgeRetrievalService` 全链路；无 key 环境 skip 不计入失败；
3. **node 冒烟**（`scripts/m10-a01/retrieval-quality-gate.mjs`）——env 门控（`BASE_URL/ORG/TEAM/TOKEN`，缺→exit 2）打预览端点：断言恒 200+no-store+响应形状+400 错误合同，记录延迟分布；违例 exit 1。

**S01 §4 冻结阈值**（层二断言）：

| 指标 | 冻结值 |
|---|---|
| 知识 Recall@5 | ≥ 0.85 |
| 知识 Recall@10 | ≥ 0.90 |
| 知识版本准确性 | = 1.0（生效闸承重证明：RETIRED 向量行在库而不可见） |
| 代码文件级 Recall@10 | ≥ 0.75（片段级分列报告，无绝对阈值——A01 补数据基线） |
| 无答案误报率 @τ=0.55 | ≤ 0.10 |
| 检索延迟 P50/P95/P99 | 信息性输出（绝对阈值断言留 Q01 复核） |

τ sweep（0.30–0.05 步进到 0.70 的误报率×代码召回曲线）作为信息性输出，是 Q01 复核 τ 的直接输入。

## 8. 审计与用量

- 检索的查询文本 embedding 走 `KnowledgeEmbeddingExecutor` 治理链：EMBEDDING 用量事实照发（模型连接/目录坐标/Token 计量全坐标，对齐 §4.6 用量事实分列要求）；
- 预览端点**不发领域事件、不注册审计事件**（只读调试面，S01 §3.7 语义）；拒绝路径复用全局异常映射（403/404/422）随平台既有审计口径，不新增事件类型。
