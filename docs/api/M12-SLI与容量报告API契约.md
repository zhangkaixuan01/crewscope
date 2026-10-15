# M12 SLI 与容量报告 API 契约

> 模块：M12-I03（基础 SLI/错误预算与容量报告）　状态：后端已交付（2026-10-15）<br>
> 依据：[ADR-037](../adr/ADR-037-配额计量与SLO治理.md) §5、[主计划](../plans/M12-企业化与多租户治理.md) §6 I03<br>
> 权限词汇：复用 A01 `AUDIT_READ_ORG`（零新增 ORG 词汇；ORG_OWNER/ORG_ADMIN/ORG_AUDITOR 可读，团队角色 403）

## 0. 本轮边界与诚实分层声明（先读）

v1 交付一个只读查询面（六指标+错误预算）与容量报告文档。**自动降级/限流、外部 Prometheus 默认部署、前端面板归后续**，不在本契约。

### 诚实分层（ADR-037 §5 的实现口径）

1. **表类指标走真实窗口**：执行成功率、通知送达率以 `created_at` 过滤的 SQL FILTER COUNT 计算 28 天滚动窗+当前报表日窗。
2. **Timer 类指标窗口 = processLifetime**：API P95、首 Token、排队时长、检索延迟来自进程内 micrometer 直方图（histogram 桶合并跨 series），窗口诚实标注 `process_lifetime`；其 28 天滚动条目恒 `availability=degraded`、`value=null`——**只有外部 Prometheus（`histogram_quantile` 折叠 `_bucket` 系列）才能回答跨重启的 28 天百分位**，响应 note 给出折叠公式。
3. **零迁移红线**：无新表/新索引。表类查询按 `organization_id + created_at` 过滤，`task_execution` 无专用索引接受扫描，代价写进容量报告。
4. **org 隔离红线**：org_id 不进公共指标标签；组织下钻只走本端点（授权后 SQL 过滤）；Prometheus 端点无组织明细。

## 1. 端点总览（1 操作，OpenAPI 305→306）

| # | 方法/路径 | 权限 | 头/参数 | 成功 | 主要错误 |
| --- | --- | --- | --- | --- | --- |
| 1 | GET `/api/v1/organizations/{orgId}/sli` | AUDIT_READ_ORG | — | 200 OrganizationSliView（no-store） | 400（orgId 非法）/401/403/404 |

只读、无版本乐观锁（每次查询现算）、无 ETag、`Cache-Control: no-store`。

## 2. 响应形态（OrganizationSliView）

```json
{
  "organizationId": "0198a475-0831-7000-8000-000000000001",
  "generatedAt": "2026-10-15T08:00:00Z",
  "reportingZone": "Asia/Shanghai",
  "metrics": [
    {
      "id": "api.latency.p95",
      "objective": "p95 < 800 ms with 99.9% success budget (ADR-037 §5)",
      "dataSource": "crewscope.api.requests Timer",
      "windows": [
        {
          "window": "process_lifetime",
          "scope": "deployment",
          "component": null,
          "unit": "milliseconds",
          "value": 650.0,
          "sampleCount": 42,
          "availability": "ok",
          "errorBudget": {
            "basis": "server_error_ratio",
            "allowedFailureRatio": 0.001,
            "observedFailureRatio": 0.0005,
            "consumedRatio": 0.5,
            "status": "ok"
          },
          "note": "Excludes /actuator/* and file-download route templates. ..."
        },
        { "window": "rolling_28d", "scope": "deployment", "component": null,
          "unit": "milliseconds", "value": null, "sampleCount": null,
          "availability": "degraded", "errorBudget": null,
          "note": "In-process registry cannot answer this; fold crewscope_api_requests_bucket via histogram_quantile over 28 days." }
      ]
    }
  ]
}
```

- `metrics[]` 恒六条，顺序固定：`api.latency.p95`、`agent.first.token`、`queueing.latency`、`execution.success.rate`、`notification.delivery.rate`、`knowledge.retrieval.latency.p95`。
- `windows[]`：`window` ∈ process_lifetime / rolling_28d / current_day / snapshot；`scope` ∈ deployment（进程级）/ organization（org 级 SQL 事实）。
- null 语义：`value=null`+`availability=no_data`=分母为空或无样本；`errorBudget=null`=该窗无预算概念。
- `errorBudget.status`：`consumedRatio = min(1, observed/allowed)`；`>=1` → `exhausted`。烧尽**不触发自动动作**，处置走 Runbook 降级评审。

## 3. 六指标口径（含排除集）

| 指标 | 目标（ADR-037 §5） | 窗口 | 排除口径 |
| --- | --- | --- | --- |
| `api.latency.p95` | p95<800ms；5xx 预算 99.9%（allowed=0.001） | process_lifetime + rolling_28d(degraded) | route 以 `/actuator` 开头与三个下载流路由模板（coding artifacts/patch、commands/{id}/log、test-evidence/{id}/report）；排除在查询侧（`MeterRegistrySliTimerStatistics`），发射面零改动 |
| `agent.first.token` | 应用开销 p95<1500ms；模型侧仅分布 | model_side + app_overhead 两 component 各两窗 | 模型侧=模型调用发起→首响应事件（FIRST_CHUNK）；应用开销=INVOCATION STARTED→首次 MODEL_CALL STARTED（每 invocation 一次）；非流式模型退化为首响应完成（ADR 注记） |
| `queueing.latency` | p95<30s | outbox_delivery/projection_replay（process_lifetime）+ snapshot（org 级、秒） | 计划内重建推高 projection lag **不过滤**（快照 note 说明）；snapshot 值=max(outbox 最老年龄, checkpoint 最大滞后) |
| `execution.success.rate` | ≥95% 28 天滚动 | rolling_28d + current_day（org 级） | 分母=terminal−CANCELLED−`terminal_failure_class IN (POLICY_VIOLATION, AUTHORIZATION)`；agent_run 视角无策略拒绝枚举不排除，note 并列五状态计数（视角差注记） |
| `notification.delivery.rate` | ≥99% | rolling_28d + current_day（org 级） | 分母=SUCCEEDED+FAILED_FINAL(非收件人侧)；排除 `receipt.failure_code IN (RECIPIENT_UNAVAILABLE, AUTHORIZATION_REVOKED)` 与状态 INVALIDATED/CANCELLED |
| `knowledge.retrieval.latency.p95` | p95<2s | process_lifetime + rolling_28d(degraded) | 索引重建窗口不单独排除（`KNOWLEDGE_INDEX` 与检索计时分离）；关态/供应商不可用计 DEGRADED outcome（「功能关闭」与「慢」可区分） |

窗口边界：rolling=`[now−28d, now)`；current_day=报表时区当日 00:00 起（`crewscope.observability.reporting-zone`，默认 UTC，部署配 Asia/Shanghai）。

## 4. 错误码

| HTTP | code | 场景 |
| --- | --- | --- |
| 400 | `invalid_request` | 路径 orgId 非法（field=organizationId） |
| 403 | `POLICY_DENIED` | 无 AUDIT_READ_ORG（`details.action="read organization audit"`）；ORG_AUDITOR 可读本面但 403 配额配置面（能力正交钉死） |
| 5xx | — | SQL/registry 故障异常穿透（只读面，无吞错） |

## 5. 发射面变更（C1a，非端点）

- 新增 Timer：`crewscope.agent.model.first.token`、`crewscope.agent.model.app.overhead`（代码内 `publishPercentileHistogram(true)`，零标签）。
- 既有 4 枚 Timer 开 per-meter 直方图（application.yml 方括号键，**值为纯 boolean 无 `.enabled` 子键**）：`crewscope.api.requests`、`crewscope.m6.outbox.duration`、`crewscope.m6.projection.duration`、`crewscope.m10.knowledge.retrieval.duration`。
- 新增 `OperationalTelemetry.Type.KNOWLEDGE_RETRIEVAL`（QUERY）；`KnowledgeRetrievalService` 全路径计时（关态也计时 outcome=degraded）；顺手修复 `recordMetric` 漏 `KNOWLEDGE_INDEX` case 的裸记录 bug。
- Prometheus 端点核对：`/actuator/prometheus` 出现各 `_bucket` 系列。

## 6. 验收落点

| 验收项 | 落点 |
| --- | --- |
| 排除口径（执行/通知）+窗口+org 隔离+快照 | `JdbcSliStatisticsAdapterIT`（真实 PostgreSQL） |
| 六指标组装/双窗口/预算烧尽/分母 0/no_data/403 | `OrganizationSliQueryServiceTest` |
| 桶合并数学（插值/+Inf 尾/边界并集/零样本） | `HistogramPercentileMergeTest` |
| 多 series 合并/排除路由/5xx 比率/无样本 empty | `MeterRegistrySliTimerStatisticsTest` |
| 首块/开销恰好一次+percentile 配置机器钉死 | `AgentCallObservabilityM2I07Test`、`SliPercentileHistogramConfigurationTest`、`AgentCallObservabilityMetricsFirstTokenTest` |
| HTTP 门禁（200 结构/403/400/ORG_AUDITOR 能力正交） | `OrganizationSliHttpIT` |
| 容量报告（六要素+档位+实测取数） | `docs/testing/M12-I03-容量报告.md` |

## 7. 明确不做（本切片）

- 错误预算烧尽的自动限流/降级（动作清单在 Runbook「SLI 与错误预算」章节，人工评审）。
- Prometheus 默认部署（可选 `deploy/team-beta/prometheus.yaml` 三 job 样例在 Runbook，不进默认 compose）。
- 前端运维面板（归 F 系切片）。
