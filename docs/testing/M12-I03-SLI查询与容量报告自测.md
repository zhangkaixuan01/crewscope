# M12-I03：SLI 查询与容量报告自测

> 日期：2026-10-15<br>
> 状态：已通过（Team-Beta 默认四服务栈实测）<br>
> 覆盖：[SLI 与容量报告 API 契约](../api/M12-SLI与容量报告API契约.md) §6 验收落点的 HTTP 面与 Prometheus 面；数值基线见[容量报告](M12-I03-容量报告.md)

## 0. 环境

```bash
./deploy/team-beta/quickstart.sh init   # 首次
./deploy/team-beta/quickstart.sh build  # 镜像须覆盖最新后端提交
./deploy/team-beta/quickstart.sh up
```

操作员 `crewscope-monitor`，密码 `deploy/team-beta/.runtime/bootstrap_password`；Prometheus 端点用户 `crewscope-prometheus`，密码取容器环境 `CREWSCOPE_MONITORING_PASSWORD`。组织 id 为 Team-Beta 固定 `0198a475-0831-7000-8000-000000000001`。

## 1. 登录与六指标结构

```bash
CK=$(mktemp) && ORG=0198a475-0831-7000-8000-000000000001
BP=$(cat deploy/team-beta/.runtime/bootstrap_password)
curl -s -c $CK http://127.0.0.1:8080/api/v1/auth/session -o /dev/null
XSRF=$(awk '$6=="XSRF-TOKEN"{print $7}' $CK)
curl -s -b $CK -c $CK -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' \
  -d "{\"identifier\":\"crewscope-monitor\",\"password\":\"$BP\"}" \
  http://127.0.0.1:8080/api/v1/auth/login
curl -s -b $CK http://127.0.0.1:8080/api/v1/organizations/$ORG/sli | jq '[.metrics[].id]'
```

**实测（2026-10-15）**：六条 id 恰按契约顺序（`api.latency.p95`、`agent.first.token`、`queueing.latency`、`execution.success.rate`、`notification.delivery.rate`、`knowledge.retrieval.latency.p95`）；`reportingZone=Asia/Shanghai`；响应头 `Cache-Control: no-store`；重复调用 5×200（端到端 69–258 ms）。

窗口断言：`api.latency.p95` 含 `process_lifetime`(ok, 1216.9 ms/12 样本) + `rolling_28d`(degraded, value=null)；`execution.success.rate` 恰 `rolling_28d`+`current_day` 双窗（空库均 no_data）；`queueing.latency` 的 snapshot 窗 scope=organization、unit=seconds、availability 恒 ok。

## 2. 错误预算与 no_data

- api process_lifetime 窗 `errorBudget.consumedRatio=0.0, status=ok`（无 5xx）。
- 表类窗口 `sampleCount=null`、`value=null`、`availability=no_data`（分母 0 是事实不是 0%）。
- `errorBudget=null` 出现在 degraded/no_data 窗（无预算概念）。

## 3. 负例

| 请求 | 预期 | 实测 |
| --- | --- | --- |
| 未登录 GET /sli | 401 | 401 ✓ |
| 团队角色（无组织角色）GET /sli | 403 POLICY_DENIED | HTTP IT 钉死（ORG_AUDITOR 200+quota 403 同测） |
| `GET /api/v1/organizations/not-a-uuid/sli` | 400 invalid_request（field=organizationId） | 400 ✓ |

## 4. Prometheus 直方图

```bash
docker exec crewscope-team-beta-api-1 sh -c \
  'wget -qO- --header="Authorization: Basic $(printf crewscope-prometheus:$CREWSCOPE_MONITORING_PASSWORD | base64)" \
   http://localhost:8080/actuator/prometheus' | grep -c '_bucket'
```

**实测**：414 个 `_bucket` 系列；`crewscope_api_requests_seconds_bucket`、`crewscope_m6_outbox_duration_seconds_bucket` 两族确认（其余族随首个样本出现）。经 nginx 的 `/actuator/*` 会回退 SPA——采集必须直连 api 容器或为其加路由（外部 Prometheus 按 `deploy/team-beta/prometheus.yaml` 的 `api:8080` job 走 compose 内网，不受影响）。

## 5. 自动化证据对应

| 本文步骤 | 自动化钉死 |
| --- | --- |
| 六指标结构/窗口/no_data/预算 | `OrganizationSliHttpIT`（真实 HTTP 四测全绿） |
| 403/400/ORG_AUDITOR 能力正交 | 同上 |
| 窗口算术/排除分母/预算烧尽 | `OrganizationSliQueryServiceTest`、`JdbcSliStatisticsAdapterIT`（真实 PG 5/5） |
| 桶合并/路由排除 | `HistogramPercentileMergeTest`、`MeterRegistrySliTimerStatisticsTest` |

全 reactor `./mvnw --batch-mode --no-transfer-progress clean verify`：1093 tests, 0 failures（2026-10-15）。
