# M12 配额与用量 API 契约

> 模块：M12-A02（配额配置、原子预占、用量查询）　状态：后端已交付（2026-10-13）<br>
> 依据：[ADR-037](../adr/ADR-037-配额计量与SLO治理.md)、[S01 冻结记录](../spikes/M12-S01-现状盘点与合同冻结.md) §3.3、[主计划](../plans/M12-企业化与多租户治理.md) §6 A02<br>
> 权限词汇：复用 A01 `SETTINGS_READ`/`SETTINGS_WRITE`（零新增 ORG 词汇）

## 0. 本轮边界与偏差声明（先读）

v1 交付两个配额维度与一个用量视图；**异步计量、SLI 与前端归 I03/F02**，不在本契约。

### 偏差声明（相对 ADR-037 与主计划）

1. **「失败/取消释放预占」收窄为承诺语义（commitment semantics）**。ADR-037 §3 设想按实际用量结算、失败/取消释放。勘察事实：`ModelUsageFactRecorded` 无 task 维度、rollup 是 org+team+month 粒度——**per-task 实际用量无事实源**，「按实际结算」不可实现。v1 冻结：预占按受理时 `creationPolicy.budget().maxTokens()`（真实上界）全额记账，终态（成功/失败/取消）不退款不释放；月份切换新行零起步。因预占只产生于受理事务内、事务回滚（含崩溃）即消失，**无孤儿预占面，无对账 sweeper**——ADR-037 §3 的对账作业以「不可达状态」为由不做。ADR-037 状态行已加交付注记。
2. **「释放/结算/对账」验收的替代覆盖**。主计划 A02 验收行（并发、重复请求、崩溃、取消、月份切换、下调限额）按承诺语义改写覆盖：并发不超占（advisory 锁+FOR UPDATE+CAS，C 层并发测试）、幂等重试不双占（CommandReceipt 先于事务 replay）、崩溃回滚=预占随事务消失（C 层 MANDATORY 事务语义）、取消不退款（契约语义本身）、月切新行零起步、下调限额不追讨在途。**任务创建端点的 HTTP 层 403 形态未做全栈 e2e**（创建链路前置过重，无既有真实 HTTP 先例）：仲裁事实由 application 决策矩阵+infrastructure 适配器集成测试钉死，`PolicyDeniedException`→403 `details={action}` 映射走既有全局通道（A01 已验证）。
3. **实际用量口径 = org+month rollup 聚合，无 task/预占明细**。`actualTokens` 是 `model_usage_monthly_rollup` 的组织级 SUM（跨 team、含无 team 行），与 `reservedTokens` 并列展示但不做归因对账。`findMonthAggregates`（team 必填）不可复用，A02 新写 org 粒度 SUM。
4. **`maxTokens==0` 防御分支不可达**。`PolicyBudget` 域校验强制 `maxTokens >= 1`，admission 层的「预算 0 跳过台账」防御路径保留但当前无真实 policy 可触发。
5. **limit null = 未配置，不是 0**。`>=1` 是下界（0/负数是配置错误 → 400）；「停新任务」用显式低限额表达，不用 0。

## 1. 端点总览（3 操作，OpenAPI 302→303）

| # | 方法/路径 | 权限 | 头/参数 | 成功 | 主要错误 |
| --- | --- | --- | --- | --- | --- |
| 1 | GET `/api/v1/organizations/{orgId}/quota` | SETTINGS_READ | — | 200 `{monthlyTaskIntakeLimit, monthlyTokenBudget, version}` +ETag=version | 400/401/403/404 |
| 2 | PUT `/api/v1/organizations/{orgId}/quota` | SETTINGS_WRITE | Idempotency-Key+If-Match 必填；body 两值（null/缺省=清除该维度） | 202 CommandReceipt（幂等重放同 receipt） | 422 invalid_value（limit<1）/409（If-Match）/401/403/404 |
| 3 | GET `/api/v1/organizations/{orgId}/quota/usage?month=YYYY-MM` | SETTINGS_READ | month 可选（缺省=报表月） | 200 QuotaUsageView | 400 `field=month`（非法/未来月）/401/403/404 |

无行默认态：GET 返回 `{null, null, version:0}`；usage `materialized:false`、limit null、计数为 0。

## 2. 配置命令（PUT /quota）

- 首写（`If-Match: "0"` 且无行）insert 建行（version 停 0）；此后 changeLimits 逐版递增，行永不清删——「清除」= 两维度写 null（行保留、version 递增）。
- 幂等：`organization.change-quota`，hash 五元（COMMAND_TYPE, orgId, expectedVersion, intake, budget；null 编码空串）。replay 先于事务，返回同 receipt（`Idempotency-Replayed: true`）。
- 审计：`ORGANIZATION_QUOTA_CONFIGURATION_CHANGED`（SECURITY/EXTENDED，required `monthlyTaskIntakeLimitAfter`/`monthlyTokenBudgetAfter`），经事件投影落 `audit_event`。
- 事件 payload：`{organizationId, monthlyTaskIntakeLimitAfter, monthlyTokenBudgetAfter, versionAfter}`。

## 3. 受理准入（挂点契约，非独立端点）

挂 `AgentTaskCreationService.createInTransaction` required 事务内：全部输入校验（400/422 优先）之后、Task 行创建（首个写副作用）之前。顺序：

1. 无配置行或两维度皆 null → **直接放行**（不取锁、不碰台账——未配置组织零开销，S01 §3.3）。
2. `MONTHLY_TASK_INTAKE` 已配置 → org 级 advisory 锁（namespaced key `orgId||':monthly-task-intake'`，与 ClaimQuota 裸 orgId key 隔离）→ 当月窗权威 COUNT（`crewscope.task` 按 `created_at`，`ix_task_organization_created`）→ `count >= limit` 拒绝。
3. `MONTHLY_TOKEN_BUDGET` 已配置且 `maxTokens > 0` → 台账行月首写（`ON CONFLICT DO NOTHING` 零起步）→ `FOR UPDATE` → `Math.addExact` 累加（溢出=超限）→ `new > limit` 拒绝不写 / 否则 CAS `reservation_sequence` 推进。
4. 拒绝形态：`403 POLICY_DENIED`，`details.action` ∈
   - `create more agent tasks this month (organization monthly task intake limit reached)`
   - `create an agent task reserving additional model tokens this month (organization monthly token budget reached)`
5. 存储故障（锁/CAS/连接）**异常穿透 → 5xx 回滚**（fail-closed），绝不映射为 403——403 是策略语义，故障不是。

月份口径单源：`crewscope.observability.reporting-zone`（代码默认 UTC，部署配 Asia/Shanghai）；intake COUNT 窗、ledger 月份键、usage 缺省月同用 `QuotaMonthCalendar`。

## 4. 用量视图（GET /quota/usage）

```json
{
  "month": "2026-10",
  "monthlyTaskIntake": {"limit": 5, "count": 3},
  "monthlyTokenBudget": {
    "limit": 1000000,
    "reservedTokens": 600000,
    "actualTokens": {"input": 100000, "output": 50000, "cachedInput": 25000, "total": 175000}
  },
  "materialized": true
}
```

- `limit` null=该维度未配置；`count`/`reservedTokens`/`actualTokens` 恒有值（零是事实）。
- `reservedTokens`：当月全部受理的 `maxTokens` 上界之和（承诺口径，≥ 实际）。
- `actualTokens`：org+month rollup 聚合（跨 team），统计延迟=rollup 投影节奏；与 reserved 不做归因对账（偏差声明 3）。
- 不暴露台账行、reservation_sequence、预占明细。
- month 校验：`YYYY-MM` 格式+不晚于当前报表月（未来月 400 `field=month`）；当前月在途可查。

## 5. 错误码

| HTTP | code | 场景 |
| --- | --- | --- |
| 400 | `invalid_request` | 路径 orgId 非法（field=organizationId）/month 非法或未来（field=month） |
| 422 | `INVALID_VALUE`（域映射，线缆 snake_case `invalid_value`） | limit 0/负数（域 `requireLimit`；集成测试实证 422，本表原写 400 系陈旧表述） |
| 403 | `POLICY_DENIED` | 无 SETTINGS_READ/WRITE；受理准入拒绝（`details.action` 见 §3.4） |
| 409 | `OPTIMISTIC_LOCK_CONFLICT` | If-Match 版本错配 |
| 5xx | — | 准入存储故障（fail-closed，受理回滚） |

## 6. 存储与迁移（V70）

- `organization_quota_configuration`：PK organization_id、typed 列（INT/BIGINT，NULL=未配置，`IS NULL OR >=1`）、version、审计四列（principal 复合 FK RESTRICT）。无种子行。
- `organization_quota_token_ledger`：PK (organization_id, usage_month CHAR(7) 正则同 V64)、`reserved_tokens`/`reservation_sequence` 非负、审计四列。
- `ix_task_organization_created` ON `crewscope.task (organization_id, created_at)`。
- 迁移断言：BootstrapOperatorProvisioningM7I07 69→70。

## 7. 审计与验收落点

| 验收项 | 落点 |
| --- | --- |
| 并发不超占 | `JdbcOrganizationQuotaPersistenceM12A02IntegrationTest`（advisory/FOR UPDATE/并发 reserve 串行累加）+ B 决策矩阵 |
| 幂等不双占 | CommandReceipt reserve/complete 同事务（A01 先例）+ D replay 断言 |
| 崩溃回滚=无孤儿 | MANDATORY 事务语义 + 预占随事务消失（无 sweeper 的理由，偏差声明 1） |
| 取消不退款/月切零起步/下调不追讨 | 承诺语义（C 层 month 独立行、超限不写测试） |
| 未配置零开销 | B `verifyNoInteractions` 双 Port + 旧构造器 null 兼容（全 reactor 既有创建测试零改动） |
| 配置面/权限/400/409 | D `OrganizationQuotaHttpM12A02IntegrationTest` 六场景 |

## 8. 明确不做（本切片）

per-task 实际结算、对账 sweeper、新 ORG 词汇、预占明细暴露、SLI 指标（I03）、配额页前端（F02）、`TeamBudgetAlertService` 软预算链（分层不动）。
