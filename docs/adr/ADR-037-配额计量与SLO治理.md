# ADR-037：配额计量与 SLO 治理

> 状态：ACCEPTED（M12-A02 已交付 2026-10-13，§3 按承诺语义收窄，注记见该节；M12-I03 已交付 2026-10-15，§5 口径落定并经首个容量报告取数，注记见该节；F02 待实现）<br>
> 日期：2026-10-12<br>
> 归属：M12-S01 合同冻结；挂点与先例勘察 2026-10-12（§1/§2 为源码事实）<br>
> 关联决策：[ADR-035](ADR-035-多租户隔离方案与迁移路径.md)（组织归属基线）、[ADR-025](ADR-025-本地密码与登录防护参数.md)（登录限流，非配额面）；[ADR-021](ADR-021-三流恢复与前端合并协议.md)（统计事件走既有事件管线）<br>
> 影响里程碑：M12（A02/I03/F02 主线）

## 背景

M12 主线交付原子配额与基础 SLI。现状：无硬配额——M10 `TeamBudgetAlertService` 是软预算提醒（「a reminder, never a quota」），硬限制仅有两个局部先例（`ClaimQuotaRepository` lease 仲裁、`workspace_write_budget_usage` 写预算表）；观测事实源已在（`server/observability` 31 类、`crewscope.api.requests`/`m6.*`/AgentCall 指标族、actuator/prometheus/otel 依赖与 deploy 配置物）。本 ADR 冻结：预占挂点与事务、仲裁形态选型、结算对账语义、资源上界诚实性、SLI 六指标口径。

## 决策

### 1. 原子预占挂点：AgentTaskCreationService 受理事务（源码事实承底）

勘察确认（`crewscope-application/.../task/AgentTaskCreationService.java`）：

- `create()` 经注入的 `TransactionExecutor.required(...)` 包裹 `createInTransaction`——**幂等 receipt 预占（reserve）→ 可见性检查 → 责任链行锁 → 乐观版本校验 → 指派/委派校验 → task/execution/policySnapshot 创建 → 事件 append → outbox enqueue → receipt complete 全部同一 required 事务**，任何步骤抛 `PolicyDeniedException` 即整体回滚。
- 幂等重试先经 receipt 预检：未获取 receipt 直接 replay 返回——**幂等重试不重复占用预占额度**由该序天然承底（预占行与 receipt 同事务，重试看不到已回滚的预占）。

**决策**：配额预占/拒绝 INSERT 落点 = 该事务内（步骤序列中、task 创建前）；拒绝复用 `PolicyDeniedException` 统一通道（既有抛点形态一致）。与任务创建同一事务即主计划 §4.4 第 1 条要求的「原子检查并预占」，无需另证等价协议。配额用例与预占仓储落 `application/quota`（新建包），不把配额逻辑写进 AgentTaskCreationService 本体（委托调用）。

### 2. 仲裁形态：两个既有先例按资源维度取用

| 先例 | 机制（源码事实） | 适用资源维度 |
| --- | --- | --- |
| `ClaimQuotaRepository` / `JdbcClaimQuotaRepositoryAdapter` | `@Transactional(MANDATORY)` 强制加入调用方事务；PostgreSQL 事务级 advisory 锁（`pg_advisory_xact_lock`，org 级单锁防死锁）+ 对权威表 `execution_lease` 的 ACTIVE 行 COUNT 对比三级限额；超限**不抛异常**，返回 Decision 枚举由调用方跳过 | **可数并发资源**（同时在途任务数/受理速率类）：权威行就是事实，计数不可漂移 |
| `WorkspaceWriteBudgetStore` / `JdbcWorkspaceWriteBudgetStore` | 预占台账表（`workspace_write_budget_usage`，`ON CONFLICT DO NOTHING` 幂等建行）+ `SELECT FOR UPDATE` 行锁 + 上下文重验（所有权/fencing/fingerprint）+ 条件 `UPDATE WHERE reservation_sequence=…`（更新数≠1 抛上下文异常）+ 超限抛专项异常 | **有界预算资源**（token/存储/写量）：预占-结算两阶段，行级串行 |

**决策**：A02 按配额维度选形态——并发受理类配额用 ClaimQuota 形态（advisory 锁+权威计数+Decision），预算消耗类配额用 WriteBudget 形态（台账+行锁+条件更新+专项异常）；两形态都是认可先例，混合使用不视为口径分裂。**禁止**第三种「应用层内存计数」形态（无权威事实源，崩溃即漂移）。

### 3. 结算、释放与对账

> **M12-A02 交付注记（2026-10-13）**：勘察确认 `ModelUsageFactRecorded` 无 task 维度、`model_usage_monthly_rollup` 为 org+team+month 粒度——per-task 实际用量无事实源，本节「按实际结算/失败取消释放」在 v1 收窄为**承诺语义**：预占按受理时 policy `maxTokens`（真实上界）全额记账、终态不退款；预占只在受理事务内产生、事务回滚（含崩溃）即消失，无孤儿预占面，对账作业不做。月份切换新行零起步、下调限额不追讨在途（本节第 3 条）已照原合同交付。详见[配额与用量 API 契约](../api/M12-配额与用量API契约.md) §0 偏差声明 1。

- 成功 → 按实际用量结算（预占转实占，差额释放）；失败/取消 → 释放预占；结算/释放写预占事实行（状态+用量+时间），与业务同一事务或在可证明等价的后续事务（对账幂等键承底）。
- 崩溃对账：预占行携带状态与到期语义；对账作业幂等扫描（孤儿预占=受理事务已回滚但 receipt 未 complete 的组合，按 receipt 事实裁定释放或补结算）；对账动作入 Audit。
- Lease 过期/消息重投/月份切换/下调限额遵循 §4.4 第 4 条：下调不强杀在途（在途遵守已接受预算），新请求按新限额拒绝；月份边界按组织时区配置显式声明，预占不跨月结转（当月剩余额度不滚存），缓存 token 计量口径 = 预占时点估算+结算修正。
- 用量统计是派生：事实源 `model_usage_monthly_rollup`（V64）+ 预占事实表；统计与告警走既有 Outbox/Inbox/飞书管线，不暗含新邮件 Provider。

### 4. 资源上界的诚实性

- Token/存储等最终用量未知时：预占有界预算（按配置的上限或估算系数）+ 明确超额政策（拒绝或标记超支继续），**无上界配置只能称软预算**，UI 文案不得写「绝对上限」；缺价格记录标「未知」（估算不是账单）。
- 准入故障（配额存储不可达）fail-closed：新受限请求拒绝；统计/报表故障不误伤已受理执行（§4.4 第 5 条）。
- 未配置配额 = 受理路径零变化（默认不配置=无配额），不因配额代码存在而增加强制配置。

### 5. SLI 六指标口径（初值目标由 I03 首个容量报告校准）

> **M12-I03 交付注记（2026-10-15）**：查询面 `GET /api/v1/organizations/{org}/sli`（AUDIT_READ_ORG）已交付，六指标口径落定并经[首个容量报告](../testing/M12-I03-容量报告.md)实测取数。a. **首 Token 口径（B 方案）**：模型侧=模型调用发起→首个响应事件（FIRST_CHUNK，CAS 恰好一次、重试安全）；应用开销=invocation 受理→首次模型调用发起（Reactor Context 传递、每 invocation 一次）；非流式模型退化为首响应完成。b. **Timer 类 28 天窗**：进程内 registry 只能回答 processLifetime 窗口，28 天滚动条目恒 `availability=degraded`+`value=null`（响应 note 内置各指标 Prometheus `histogram_quantile` 折叠系列名）；默认四服务部署不含 Prometheus 属诚实降级，非缺陷。c. **排除集明确化**：执行=用户取消+`terminal_failure_class IN (POLICY_VIOLATION, AUTHORIZATION)`；通知=INVALIDATED/CANCELLED+`failure_code IN (RECIPIENT_UNAVAILABLE, AUTHORIZATION_REVOKED)`；agent_run 无策略拒绝枚举不排除，五状态计数在响应 note 并列呈现（视角差注记）。实测注记：空组织投影 checkpoint lag 随墙钟增长（无事件不推进），降级评审以 backlog 与 outbox 计时为准——见容量报告 §2。

公共口径：时间窗滚动 28 天 + 当日快照双口径；排除项=维护窗口与已知故障注入演练期；错误预算=窗口内目标违反的允许占比，烧完触发降级评审（不自动限流，评审动作入 Runbook）。组织下钻一律走授权查询（低基数公共指标，organization_id 不进指标标签，§4.6 红线）。

| 指标 | 定义与事实源 | 排除项 | 初值目标（2026-10-15 首个容量报告实测取数，未触发改值） |
| --- | --- | --- | --- |
| API P95 | `crewscope.api.requests` Timer（ApiObservabilityWebFilter 全路由） | 监控链 `/actuator/*`、文件下载流 | P95 < 800ms；错误预算 99.9%（5xx 占比） |
| 首 Token 延迟 | AgentCallObservabilityMetrics 模型调用指标；口径=受理完成→首个模型响应字节，**应用开销与模型侧延迟拆分报告** | 客户端网络、模型供应商侧故障注入 | 应用开销 P95 < 1.5s；模型侧仅报告分布不设目标（供应商主导） |
| 排队时长 | outbox/projection 投影延迟（`crewscope.m6.*` 计时 + outbox 表事件时间差） | 计划内重建任务 | 入队→可见 P95 < 30s |
| 执行成功率 | task_execution/agent_run 终态分布（quality 查询先例同源） | 用户主动取消、策略拒绝（非执行失败） | 终态成功率 ≥ 95%（滚动 28 天） |
| 通知送达率 | notification_delivery/notification_receipt 终态 | 收件人侧退订/失效地址 | 送达率 ≥ 99%（重投后） |
| 检索延迟 | knowledge 指标族（索引查询计时） | 索引重建窗口 | P95 < 2s |

容量样本形态（I03 交付）：硬件/模型/数据量/并发/瓶颈/阈值六要素齐备的书面报告；50/200/500 并发为目标形态样本档位，不是普通单机强制规格。默认四服务直接可取数（Micrometer registry+表查询），不要求 Grafana；外部采集复用 deploy 既有 prometheus/alertmanager/otel-collector 配置物（不进默认服务列表）。

## 实施约束

- A02 不建独立 Controller 暴露内部预占细节；配置/用量查询走授权 API（A02 行合同）。
- I03 只读观测：扩展 `server/observability`+`application/observability`，强制准入（A02）与只读观测不混用同一服务类。
- 预占事实表编号从合并时实际最大值 +1 顺延（ADR-035 §4 迁移原则）。
