# M6-I08 OTel、Prometheus 与日志安全

> 日期：2026-08-26<br>
> 范围：`crewscope-application`、`crewscope-agentscope`、`crewscope-integration`、`crewscope-infrastructure`、`crewscope-server`<br>
> 结论：通过

## 1. 交付内容

- 新增 `OperationalTelemetry` 应用层端口，使用闭集 `Type`、`Operation`、`WorkerRole`、`ProviderKey`、`ProjectionName`、`StreamType`、`Outcome` 和 `ErrorCode` 表达观测坐标。端口不接受租户、资源、URI、Payload、Credential、异常或任意标签值，并提供不影响业务的 No-op 实现。
- Outbox Publisher、Projection Supervisor、SSE、Inbox、Notification Dispatch/Reconcile/Redeliver Worker、Lark OpenAPI Connector 和 Team Observer Runtime 已接入同一观测边界。完成句柄只接受稳定 Outcome 与 ErrorCode，并保证重复完成不重复记账。
- `TeamBetaOperationalTelemetry` 为每类边界创建 OTel Span，并只写入经评审的属性。内部 Baggage 固定为 `crewscope.correlation_id`、`crewscope.operation` 和 `crewscope.worker_role`；Lark Connector 使用固定原生 `HttpClient`，不会把 CrewScope Baggage 传播到外部 Provider。
- Prometheus 自定义指标统一使用 `crewscope.m6.*`。`TeamBetaMetricPolicy` 冻结指标名、标签集合与枚举值，拒绝未声明指标、未知标签、动态身份标签和非法标签值；单指标理论 Series 上限为 256，M6 指标理论总上限为 688，低于 2,000 的 Team Beta 预算。
- Operations Health 使用预注册、无身份字段的低基数 Gauge。Organization、Team、Member、Task、Correlation、Trace、URI、异常消息、Secret 和其他动态值不能成为 M6 指标标签。
- Trace、Baggage、Metric 或结构化日志写入失败只累计 `crewscope.m6.telemetry.dropped` 安全计数，不改变事务、Worker 或 Provider 调用结果。`crewscopeTelemetry` Health Indicator 只返回按失败类型聚合的丢弃数，不公开原始异常、Payload 或 Credential。
- `SafeStructuredLoggingJsonCustomizer` 在 Spring Boot 日志系统初始化阶段处理所有结构化 String 成员。`StructuredLogSanitizer` 覆盖 Secret、Token、Credential、Prompt、Tool 输入输出、邮箱、电话、飞书身份、异常与堆栈，并按 Code Point 清理全部 ISO 控制字符与 Unicode 行/段分隔符、限制安全值为 256 字符；通用 Message 中的 Bearer Token、API Key、邮箱和手机号同样被拦截。
- Actuator 保持 `/actuator/health` 与 `/actuator/info` 可匿名访问、Health Detail 隐藏；`/actuator/prometheus` 必须通过平台认证。
- Actuator 授权切片测试关闭 Redis Health Indicator，使用例只验证 HTTP 暴露面与认证边界；生产配置仍保留 Redis 健康检查。

## 2. 安全与降级边界

```text
M6 business boundary
  -> start closed OperationalTelemetry.Request
  -> create bounded Span + approved internal Baggage
  -> execute business transaction / worker / provider call
  -> complete once with stable Outcome + ErrorCode
  -> record predeclared Prometheus series
  -> emit globally sanitized structured log

telemetry backend failure
  -> increment aggregate dropped counter when possible
  -> keep business result unchanged
```

关联 ID 只用于 Trace 与脱敏日志定位，不进入 Prometheus 标签。外部 Provider 不接收 CrewScope Baggage。观测实现不接收异常正文，因此 Collector、Registry 和日志后端故障中的敏感文本不会通过本边界再次披露。

## 3. 验证

专项与 I02/I03/I04/I06/I07 联合回归命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-server -am \
  -Dtest=OutboxConfigurationContractTest,ProjectionSupervisorConfigurationM6I02Test,\
LarkConnectorM6I04IntegrationTest,LarkNotificationProviderM6I06IntegrationTest,\
TeamObserverRuntimeM6I07Test,TeamBetaOperationalTelemetryM6I08Test,\
ActuatorAuthorizationM6I08Test,ApiObservabilityWebFilterTest,\
StructuredLogSanitizerTest,ApiObservabilityConfigurationTest,\
LarkConnectorApplicationConfigurationM6I04Test,\
NotificationWorkerApplicationConfigurationM6I03Test \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

联合回归共 55 个测试通过，0 Failure、0 Error、0 Skip：

| 模块 | 测试数 |
| --- | ---: |
| `crewscope-agentscope` | 10 |
| `crewscope-integration` | 11 |
| `crewscope-infrastructure` | 5 |
| `crewscope-server` | 29 |
| 合计 | 55 |

覆盖：

1. 七类 M6 后台与交互边界的 Span、Metric 和稳定 Outcome/ErrorCode；
2. 指标标签扫描、未声明指标拒绝、单指标 256 与总计 2,000 Series 预算；
3. 三项内部 Baggage 白名单与 Provider Payload/Authorization 排除；
4. Collector/Tracer、Prometheus/MeterRegistry、Baggage 与日志故障的业务无损降级；
5. Secret、PII、控制字符与超长值的结构化 JSON 日志快照；
6. SSE/Inbox 自动分类、Operations Health Gauge 和真实 Outbox/Team Observer 回调；
7. 匿名 Prometheus `401`、认证后 `200`，以及匿名 Health Detail 不披露；
8. Lark I04/I06 外部调用、固定模板投递与安全错误回归。

仓库全量门禁命令：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

全量门禁共 2,251 个测试通过，0 Failure、0 Error、0 Skip，Reactor 全部模块成功：

| 模块 | 测试数 |
| --- | ---: |
| `crewscope-domain` | 569 |
| `crewscope-application` | 514 |
| `crewscope-agentscope` | 159 |
| `crewscope-integration` | 18 |
| `crewscope-infrastructure` | 647 |
| `crewscope-server` | 344 |
| 合计 | 2,251 |
