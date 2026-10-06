# M10-F03 成本与质量观测 API 契约

> 状态：终稿（F03a+F03b 全部交付并冻结；§5 冻结示例已回填）<br>
> 版本：V1<br>
> 日期：2026-10-05（初稿）→2026-10-06（终稿）<br>
> 适用范围：模型用量月度聚合的可重建投影、用量成本查询 API、软预算提醒链、chat 链用量事实化、成本质量前端页面

基路径：`/api/v1/organizations/{organizationId}/teams/{teamId}/observability`（查询面）与 `.../operations`（重建面）

## 0. 本轮边界

本契约覆盖 **F03 用量与质量观测包**：`MODEL_USAGE_FACT_RECORDED` 事实（S01 §3.9 契约，嵌入/提炼先行、chat 随 F03b 补齐）的月度聚合投影、按 Team 的成本查询 API、软预算提醒（复用 ADR-022 Inbox/飞书链）、以及 `/observability` 前端页面。以下不在本契约内：

- **硬配额/阻断**：预算是提醒不是配额——超限不阻断任何模型调用；
- **币种换算**：分币种列示，金额按价格表币种原样呈现（`toPlainString()`），无汇率表；
- **org 级跨团队账目**：查询面按 Team 授权，无组织级汇总端点；
- **从 Prometheus 反推账目**：指标面与本投影互不复用（S01 冻结红线）；
- **第六 Inbox 视图**：预算提醒复用 EXCEPTION 通道（既有收件箱视图）；
- **检索/注入/反馈/Skill 加载的观测增量**：查询面只呈现既有事实表（用量与质量），不新建这些维度的投影。

## 1. 数据模型（V64，纯 PostgreSQL）

**两表** `crewscope.model_usage_monthly_rollup` 与 `crewscope.team_budget_alert`：

### 1.1 月度聚合表 `model_usage_monthly_rollup`

- **粒度**：`(organization, team?, usage_month, role, provider_key, model_id, currency_code, catalog_entry_id?, catalog_revision?, price_revision?, attempt)`——team 可空（探针 ORG 连接先例：组织级事实无 Team 维度）；attempt 进粒度（真实重试按 attempt 分列）；
- **数据列**：input/output/cached_input tokens、三 cost `NUMERIC(24,12)`（仅定价行非 NULL）、`fact_count`、`unreported_fact_count`、`first_fact_at`、`last_fact_at`；
- **'XXX' 哨兵**：无可解析价格的事实落 `currency_code='XXX'` 行——token 照常累计、三 cost 与价格三元组全 NULL；**缺失用量/价格标 UNKNOWN 不按零**（S01 冻结语义，DB CHECK `ck_rollup_unpriced_shape`/`ck_rollup_priced_shape` 双向钉死）；
- **unreported**：`ModelTokenUsage` 全零（Provider 未回显）的事实只进 `fact_count`+`unreported_fact_count`，token 列零、不解析价格；
- **函数唯一索引** `ux_model_usage_rollup_grain`：可空列 COALESCE 哨兵（team→nil UUID、价格三元组→-1）；upsert 的 `ON CONFLICT` 子句**逐字重复索引表达式**（常量、无绑定参数）保证 PG 推断可靠，`DO UPDATE` 臂原子累加；
- **无 FK**：聚合是可重建投影——`DELETE + 重放` 重建既不挡目录/价格治理，也不依赖 team 行存在。

### 1.2 预算提醒台账 `team_budget_alert`（F03b 激活）

- `uk(org, team, usage_month, kind, level)` 去重：`kind ∈ {TOKEN, AMOUNT}`、`level ∈ {WARNING, EXCEEDED}`；仅 AMOUNT 行携带 `currency_code`（CHECK 形状闸）；
- **FK team RESTRICT**：与聚合表相反，这是权威提醒证据（非投影），保留 team 外键；
- 插入 `ON CONFLICT DO NOTHING`，**仅插入成功才发 `TEAM_BUDGET_ALERT_RECORDED`**——扫描两轮收敛一条 alert + 一次事件。

## 2. 投影语义（价格与可重建性）

- **价格解析在投影时**：按事实 `occurredAt` 对 provider/model 的最新目录修订（不筛状态——RETIRED 条目保留 append-only 价格，历史事实仍可计价）取 `findEffectivePrice`；**不做事发时价格快照**；
- **价格修订经 rebuild 反映**：发布新价格点后运行重建端点，历史事实按新解析结果重算；价格三元组进粒度，修订自然拆行；
- **无目录条目或无生效价格**：token 可见未计价（'XXX' 行），绝不按零计费；
- **cached 价缺失**：cached token 作为 input 一部分计费（价格行 cached NULL 时 cached_cost=0，非 UNKNOWN）；
- **月份归组**：`occurredAt` 按部署配置时区（`crewscope.observability.reporting-zone`，代码默认 UTC、部署配置 Asia/Shanghai）归组 `yyyy-MM`；
- **重建幂等**：`rebuildAll` = 全删 + 按 `domain_event` 规范日志（event_id keyset 扫描、批 500）重放——跑两次收敛同表；既有消费者回执继续挡增量路径（重建不经 dispatcher）；
- **重建并发语义**：同一进程内投影锁串行化重建之间、重建与增量投递之间（含按事件累加的 `ON CONFLICT` 合并臂）；语句逐条 auto-commit、无外裹事务，崩溃的重建会留下部分重算的投影——重跑即完成收敛。多实例部署下另一进程的增量投递仍可能与本进程的重建交错（该窗口内投递的增量可能在全删后、重放前落地而丢失计账），运维约定为**在安静期执行重建**，重跑收敛。

## 3. 消费链

- **白名单消费者** `model-usage-rollup-v1`（范本 KnowledgeIndexInvalidationConsumer）：只认 `MODEL_USAGE_FACT_RECORDED`，其余事件忽略；经 `IdempotentEventDispatcher` 投递——**回执与聚合增量同事务**（消费者失败回滚回执，重投再试）；
- **重放幂等**：同事件两投被 `event_consumer_receipt` 挡住，行不变。

## 4. 重建端点（F03a 已交付）

```
POST /api/v1/organizations/{organizationId}/operations/model-usage-rollup/rebuilds
```

- **平台管理员 only**（`TeamAccessContext.platformAdministrator`；非管理员 403，不触达服务）；
- 同步执行，`200` + `Cache-Control: no-store`：

```json
{ "status": "COMPLETED", "projectedFacts": 42 }
```

- `projectedFacts` = 重放的规范事件数；幂等（重复调用收敛）。重放扫过规范日志里的**每一条**用量事实（扫描量随事件日志增长）；
- **并发与崩溃**：进程内投影锁使并发重建互相排队、与增量投递互斥；语句逐条 auto-commit，中途崩溃留下部分重算投影，重跑收敛；多实例部署下跨进程交错窗口与「安静期重跑」运维约定见 §2「重建并发语义」。

## 5. 用量与成本查询 API（F03b，三端点）

> 冻结示例（与 `TeamObservabilityController` DTO 及 mock e2e `m10-agent-cost.spec.ts` 同源）：

```json
// GET .../observability/cost/months（keyset 页）
{
  "months": [
    {
      "month": "2026-09",
      "roles": {
        "EXECUTION": { "inputTokens": 1200000, "outputTokens": 60000, "cachedTokens": 40000, "factCount": 40 },
        "EMBEDDING": { "inputTokens": 100000, "outputTokens": 0, "cachedTokens": 0, "factCount": 2 }
      },
      "currencies": [
        { "currency": "CNY", "inputCost": "0.050000000000", "outputCost": "0", "cachedInputCost": "0" },
        { "currency": "USD", "inputCost": "0.528000000000", "outputCost": "0.105600000000", "cachedInputCost": "0" }
      ],
      "unpricedTokens": 300000,
      "totalFactCount": 42
    }
  ],
  "nextAfter": "2026-09"
}
```

```json
// GET .../observability/cost/months/2026-09（rows 为 PRICED 与 UNPRICED 各一）
{
  "month": "2026-09",
  "rows": [
    {
      "role": "CHAT_PRIMARY", "providerKey": "dashscope", "modelId": "deepseek-v3", "currencyCode": "USD",
      "catalogRevision": 2, "priceRevision": 3, "attempt": 1,
      "inputTokens": 1000000, "outputTokens": 50000, "cachedTokens": 10000,
      "inputCost": "0.528000000000", "outputCost": "0.066000000000", "cachedInputCost": "0",
      "factCount": 30, "unreportedFactCount": 2, "costStatus": "PRICED"
    },
    {
      "role": "EMBEDDING", "providerKey": "dashscope", "modelId": "text-embedding-v4", "currencyCode": "XXX",
      "catalogRevision": null, "priceRevision": null, "attempt": 2,
      "inputTokens": 300000, "outputTokens": 0, "cachedTokens": 0,
      "inputCost": null, "outputCost": null, "cachedInputCost": null,
      "factCount": 4, "unreportedFactCount": 0, "costStatus": "UNPRICED"
    }
  ]
}
```

```json
// GET .../observability/quality/months/2026-08（分母为零时 rate=null，非 0）
{
  "month": "2026-08",
  "executionAttempts": { "total": 20, "completed": 15, "failed": 4, "cancelled": 1, "successRate": 0.75 },
  "reviewFirstPass": { "enteredReview": 0, "firstPassApproved": 0, "firstPassRate": null }
}
```

- `GET .../teams/{teamId}/observability/cost/months`——月份降序 keyset（`after`/`limit` 默认 12）；每月 `roles{EMBEDDING|DISTILLATION|EXECUTION 三来源小计}`、`currencies[]` 分币种金额、`unpricedTokens`、`totalFactCount`、`nextAfter`；
- `GET .../teams/{teamId}/observability/cost/months/{month}`——单月明细行（role×model×currency×priceRevision×attempt），`costStatus: PRICED|UNPRICED`（'XXX' 行 cost=null）；
- `GET .../teams/{teamId}/observability/quality/months/{month}`——执行成功率（分母=执行尝试）与 Review 一次通过率（分母=进入 Review 的请求），直查 `task_execution`/`review_request`/`review_decision`/`review_modification_round`，不建投影；
- **守卫**：Team 成员读 + 平台管理员 bypass + 跨 Team 404 同形；`month` 非法或未来 → 400 `invalid_request`（`details.field=month`）；无数据月 → 200 空结构。

## 6. chat 链用量事实化（F03b）

- 工厂构建 Model 时登记实例→归因会话级映射（primary/fallback 按 `AgentModelRole` 标 CHAT_PRIMARY/CHAT_FALLBACK；compaction 独立 model-id 时标 COMPACTION，同 model-id 并入 CHAT_PRIMARY）；middleware 以 `input.model()` 查映射记入 telemetry accumulator；
- **发射点**=`recordTelemetry` 同事务（DomainEventStore + Outbox）；callId 确定性命名空间 `io.crewscope/model-usage/execution/{executionId}/{attempt}/{eventSequence}`——重放同序列同 callId；
- **attempt=1 局限**：框架内重试不可见（契约明示）；真实重试分列由 EMBEDDING/DISTILLATION 链承载；
- **实时路径增量发射**：`mapEvent` 的 `ModelCallEndEvent` 分支按**单次调用增量**发射（AgentScope 的 `ModelCallEndEvent.usage` 本身即本次调用增量，无需差分）；callId 确定性命名空间 `io.crewscope/model-usage/realtime/{executionId}/{attempt}/{segmentSequence}/{sequence}`；event-id 存在性检查（`domain_event` 探针）使同坐标重放跳过不重计——**实现裁定**：以「增量+确定性 callId+存在性幂等」等效替代原「累计快照差分」方案（差分的前提——`usage` 为累计值——不成立）。发射失败仅告警不中断执行流（durable `UsageReported` 流不受影响）；归因由 observable 模型在流开始时通知 Task 状态（缓存实例→归因稳定），env-slot 解析无归因只报 `UsageReported` 不发 fact。局限：极端故障路径（commit 失败后同 segment 重跑且事件槽对齐）会少计，可接受。

## 7. 软预算提醒链（F03b）

- 配置 `crewscope.observability.budget.*`：`enabled`（默认 false）、`monthly-token-budget`（0=不启用）、`warning-threshold`（0.8，(0,1]）、`monthly-amount-budget.<CCC>`（可选，逐币种不换算）；`reporting-zone` 已随 F03a 交付（默认 UTC/部署 Asia/Shanghai）；扫描周期 `scan-fixed-delay` 固定 300s；**EXCEEDED 档恒等于预算本身（ratio=1.0），不可配置**——提醒只有「接近」与「越过」两档；
- `TeamBudgetAlertScheduler`：fixedDelay + 防重入；单事务 = 当月窗口聚合 → 比较 → uk 插入成功才发 `TEAM_BUDGET_ALERT_RECORDED`；
- Inbox：`InboxSourceType.BUDGET` + `InboxProjectionOperation.BUDGET_ALERT_OPENED`（V65 重建 CHECK 组合；事件注册表 requiredFields=`alertId/teamId/usageMonth/kind/level` 五字段）、收件人=Team OWNER+ADMIN、HIGH、EXCEPTION 通道；通知模板 `team-budget-alert` **V65 DB seed**（模板 id=确定性 UUID `88460269-…01c6f`、版本 1、PUBLISHED；变量仅 5 个 TEXT：`itemType/sourceType/sourceId/sourceRevision/priority`——**TRUSTED_LINK 变量不可 seed**，因 `inboxUrl` 的 HTTPS origin 是部署期事实（`crewscope.notification.public-base-uri`），迁移无法预知；需要链接的部署下线 v1 并以自身 origin 发布 v2）；**通知失败不影响执行**；
- **不回填历史月**、提醒不自动关闭（下月新坐标自然重开）。

## 8. 前端 `/observability`（F03b）

路由 `observability`（中文 title 成本质量、`requiredPermission: scopeRead`、`queryWhitelist: ['team','month','role']`）；页面=月份导航 + 成本概览（三来源小计 dl 指标格 + 分币种行 + unpriced 汇总）+ role×model 表（价格修订徽标、UNPRICED 徽标）+ 质量卡两张（显式标注分母）+ 空状态。e2e：`m10-agent-cost.spec.ts`（mock）+ `e2e/m10-real/agent-cost-real-api.spec.ts`（真实栈）。

## 9. 审计与迁移口径

- **F03a**：零新增审计事件（`MODEL_USAGE_FACT_RECORDED` 已在册）；V64（tip 63→64，纯 PG，无存量回填）；
- **F03b**：+`TEAM_BUDGET_ALERT_RECORDED`（注册表 126→127，两处注册表大小断言同步）；+V65（重建 `ck_inbox_item_source_type_v27`：EXCEPTION 通道新增 BUDGET 组合）。

## 10. 验收对照（主计划 §10.4/§10.5）

| 要求 | 落点 |
|---|---|
| 事件重放幂等 | §3 回执同事务 + §2 重建幂等（集成测试双投/重建收敛断言） |
| 真实重试计量按 attempt 分列 | §1.1 attempt 进粒度 |
| 缺失/币种/价格版本/月份边界 | 'XXX' 哨兵 / 分币种列示 / 价格三元组拆行 + rebuild 反映 / reporting-zone 归组 |
| 跨 Team 授权 | §5 守卫（404 同形） |
| 提醒 team×月去重、通知失败不影响执行 | §1.2 uk + §7 单事务语义 |
| 不从 Prometheus 反推、缺失不按零 | §0 边界 + §1.1 CHECK |
