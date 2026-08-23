# M5-I12 UNKNOWN 对账与运行诊断

## 目标

M5-I12 为已经越过外部写边界、但没有取得可信终结响应的 Action 提供可恢复的查询对账路径。系统在冷启动和周期调度中接管 `UNKNOWN`、过期 `RUNNING` 与过期 `RECONCILING`，通过 GitHub 只查询接口确认外部事实，并将 Webhook 与主动查询收敛到同一个 ExternalResult 单调合并函数。

本任务不重放 Push 或 Draft PR 创建，不改变 M5-I11 的 READY 执行路径。

## 已实现能力

### 有界接管

- `ActionReconciliationWorker` 按 Organization 分批发现待对账 Dispatch。
- `FOR UPDATE SKIP LOCKED` 保证同一 Dispatch 同时只被一个 Worker 接管。
- 新 Claim 使用 `RECONCILE` Mode、递增 Fencing Token 和有界 Lease。
- `UNKNOWN`、过期 `RUNNING`、过期 `RECONCILING` 共享同一接管协议。
- 无结论时线性有界退避；达到最大次数或最大 UNKNOWN 时长后进入 `MANUAL_REVIEW`。
- 旧 Claim、过期 Lease 和旧 Fencing Token 均不能提交 Receipt。

### GitHub 只查询恢复

- Push 通过 `GitHubPushPort.queryBranch` 查询远端 Branch Head；只有 Head 精确等于确认的 Delivery Commit 才形成成功 Receipt。
- Draft PR 通过 `GitHubDraftPullRequestPort.queryDraft` 执行精确 Head、Base、Commit 与内容查询。
- 查询路径使用只读授权目的，不调用 `pushBranch`、`ensureDraft`、Git Push 或 HTTP POST。
- 限流、Provider 不可用、授权事实暂时无法解析和查询未命中均保留为有界无结论状态。
- 数据库、Receipt、DomainEvent、TaskEvent、Outbox 等内部提交异常不会被误分类为 Provider 无结论；异常直接离开事务并触发整体回滚。

### Webhook 与主动查询收敛

- Webhook Adapter 和主动查询都追加不可变 `ExternalObservation`。
- `ExternalResultMerger` 使用同一个版本优先、状态单调的合并函数更新当前投影。
- Observation Key 提供持久去重，ExternalResult 使用乐观版本防止并发覆盖。
- 已提交的 PR Webhook 可在任何主动查询之前直接完成 UNKNOWN Action。
- 人工 Receipt 建立后，迟到 Webhook 或查询只能保留 Observation，不能覆盖人工终态。

### 人工终结

- 人工终结只接受 `MANUALLY_SUCCEEDED` 或 `MANUALLY_FAILED`。
- 调用者必须是当前有效 WorkItem OWNER，且必须是组织与团队范围内的 USER Principal。
- 命令必须携带强 `expectedVersion`、稳定 `ManualResolutionReason` 和非空说明。
- 成功结论必须成对提供 ExternalResultIdentity 与 Target Version。
- Action 只允许一个逻辑 Receipt；Receipt、Dispatch、事件与 Outbox 在同一事务内提交。

### 运行诊断

- 周期 Scheduler 使用进程内防重入，并依赖数据库 Claim 支持多实例运行。
- Startup Runner 在应用启动时执行一次有界恢复。
- Timer 指标仅使用 Action Kind、Claim Mode、Outcome 三类低基数标签。
- Queue Gauge 仅使用状态标签，聚合 `RUNNING`、`UNKNOWN`、`RECONCILING`、`MANUAL_REVIEW`。
- TaskExecutionId、ReviewDecisionId、ActionId 进入 Trace 与结构化日志，不进入指标标签。
- Actuator Health 仅输出聚合数量、最老未终结年龄和过期判断，不输出 Organization、Team 或 Action 标识。

## 配置

```yaml
crewscope:
  action:
    reconciliation:
      enabled: true
      startup-enabled: true
      worker-id: action-reconciler-local
      poll-interval: 5s
      lease-duration: 2m
      retry-delay: 30s
      maximum-unknown-age: 1h
      maximum-attempts: 5
      batch-size: 10
```

`enabled` 控制周期调度，`startup-enabled` 控制冷启动恢复。两个开关都不会移除 Worker 和人工终结服务，便于运维调用与测试。

## 验证证据

- `ActionReconciliationWorkerM5I12Test`：Branch Head 恢复、Webhook 优先收敛、限流退避、次数升级、Fencing 拒绝、内部提交异常外抛以及零写路径。
- `ActionManualResolutionServiceM5I12Test`：当前可行动 USER OWNER 成功，旧版本、非 OWNER、Agent Principal 和 BundleDigest 漂移在 Receipt 前失败关闭。
- `GitHubQueryOnlyProtocolM5I12Test`：Branch Query 只调用远端 Head 查询且从不调用 Git Push。
- `GitHubDraftPullRequestProtocolM5I10IntegrationTest`：PR Query 对存在和缺失结果均只发送 GET，POST 数量不增长。
- `JdbcActionWorkerPersistenceM5I11IntegrationTest`：过期 Lease 接管、Fencing、人工队列、健康摘要、Observation 去重、ExternalResult 恢复与单调更新、Receipt/Dispatch 原子回滚。
- `ActionDeliveryTest`：人工终态阻止迟到 Observation 覆盖。
- `ActionReconciliationApplicationConfigurationM5I12Test`：完整条件装配、Scheduler/Startup 独立开关和配置上下限。
- `ActionReconciliationObservabilityM5I12Test`：指标标签低基数和 Health 摘要脱敏。

## 结论

M5-I12 完成了外部 Action 从 UNKNOWN 到自动恢复、持续无结论、人工队列和人工终结的闭环。M5-I08 至 M5-I12 现在共同覆盖 GitHub 身份与授权、Repository Preflight、受管 Mirror、幂等 Push、Draft PR、Webhook、READY Worker、UNKNOWN 对账和运行诊断。
