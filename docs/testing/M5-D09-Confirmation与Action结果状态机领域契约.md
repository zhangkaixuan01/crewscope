# M5-D09 Confirmation 与 Action 结果状态机领域契约

> 任务：`M5-D09`<br>
> 日期：2026-08-23<br>
> 状态：通过<br>
> 关联决策：[ADR-019](../adr/ADR-019-ActionBundle调度与外部结果对账协议.md)

## 1. 交付范围

M5-D09 在 `crewscope-domain` 与 `crewscope-application` 交付：

- `Confirmation`：只允许当前活跃的人类 Owner 确认当前 Bundle，固定 BundleDigest、全部有序 ActionDigest、确认人、时间窗口和取消事实；
- `ActionDispatch`：为每个 PlannedAction 保存 READY/RUNNING/UNKNOWN/RECONCILING/MANUAL_REVIEW/终态、依赖、Lease、单调 Fencing Token、尝试次数、对账次数、唯一 Receipt 引用和补偿标记；
- `ActionReceipt`：保存单一逻辑终结结果，精确固定 Organization/Bundle/ActionDigest、服务端幂等键、Claim、外部身份、目标版本、证据、来源和人工原因；
- `ExternalResult`：合并 Webhook、主动查询和写响应事实，使用 Connection-scoped Observation Key 去重，优先按 Provider Version，无版本时按 Provider UpdatedAt 和受控状态迁移合并；
- `ConfirmationRepository`、`ActionDispatchRepository`、`ActionReceiptRepository`、`ExternalObservationRepository` 与 `ExternalResultRepository` Application Port；
- `ActionBundleConfirmed`、`ActionDispatchTransitioned`、`ActionReceiptRecorded` 与 `ExternalResultMerged` 安全事件。

V21 表结构、数据库唯一约束与 PostgreSQL Adapter 属于 M5-D11；Outbox 后 Worker、Provider 写入和冷启动对账属于 M5-I08 至 M5-I12。

## 2. 确认与调度契约

Confirmation 精确固定整个动作图。确认前重新复验 Review、责任、Provider、Policy、Safety Overlay 和目标前置事实；非 Owner、Agent、过期 Bundle 或已漂移事实都不能获得确认。取消后不能恢复授权。

Dispatch 调度与领取遵守：

```text
READY --EXECUTE Claim--> RUNNING --certain result--> SUCCEEDED | FAILED
                            |
                            +--uncertain write--> UNKNOWN --RECONCILE Claim--> RECONCILING
                                                                      |       |
                                                                      |       +--> SUCCEEDED | FAILED
                                                                      +--bounded exhaustion--> MANUAL_REVIEW
MANUAL_REVIEW --human Receipt--> MANUALLY_SUCCEEDED | MANUALLY_FAILED
READY --cancel Receipt--> CANCELLED
```

- READY 第一次领取只生成 `EXECUTE` Claim，并在外部写入前复验当前授权、Confirmation 有效期和依赖 Receipt；
- UNKNOWN 或 Lease 过期的 RUNNING/RECONCILING 只能生成 `RECONCILE` Claim，不允许直接重放写请求；
- 每次 Claim 使用严格递增 Fencing Token，心跳、状态提交和 Receipt 必须持有当前有效 Claim；
- Confirmation 过期禁止新外部写入；对已经可能发生写入的动作保留只读 Reconcile，防止 UNKNOWN 永久卡死；
- 普通 Retry 必须附带 `NO_SIDE_EFFECT_*` 证据，不确定写入必须进入 UNKNOWN；
- 有界对账耗尽进入 MANUAL_REVIEW，不自动猜测结果。

## 3. 唯一结果与对账契约

`ActionIdempotencyKey` 由 Organization ID、Bundle ID、Action ID 和 ActionDigest 服务端派生。Dispatch 和 Receipt 在创建及持久化重构时都重算复验，客户端或损坏数据无法替换。每个动作只保留一个终态 Receipt，Dispatch 终态必须与 Receipt Result 一致。

自动 Receipt 必须绑定当前 Claim 或可信 Webhook/主动查询。人工终结只允许活跃 USER Principal，证据 Code 必须精确等于 `ManualResolutionReason`；人工终态不可替换，迟到 Provider 事实只形成冲突记录。Cancellation 也写入唯一 Receipt；若取消依赖动作时前置动作已成功，标记 `MANUAL_REVIEW_REQUIRED`，MVP 不自动创建逆向写操作。

ExternalResult 合并遵守：

1. Provider Version 较大时经受控状态迁移后应用，较小时保留为 STALE；
2. 同一 Provider Version 与同一状态视为 DUPLICATE，Provider UpdatedAt 差异不制造虚假冲突；
3. 同一 Provider Version 的不同状态为 CONFLICT；
4. Provider 无版本时使用 Provider UpdatedAt：较新事实可应用，较旧事实为 STALE，相同时间不同状态为 CONFLICT；
5. PR `MERGED` 不允许回退，人工终态不被任何后续观察改写。

## 4. 安全与持久化边界

- Receipt 用 `Organization + PlannedAction` 唯一约束，稳定外部业务键使用独立唯一约束，Application Port 以 `insertIfAbsent` 表达原子插入语义；
- Observation 只追加，Connection-scoped Hash Key 防止不同安装的相同 Event ID 冲突；
- Evidence 只保存稳定 Code、Hash 和可选 Artifact ID，原始 Provider Payload 不进入领域事件；
- 事件不包含凭证、PR 正文、内部路径或原始外部 ID，外部身份只暴露安全 Hash；
- Dispatch 重构复验 Claim 模式、Fencing/counter、Receipt 终态、有效期、延迟时间和补偿标记，损坏行失败关闭。

## 5. 自动化验证

`ActionDeliveryTest` 新增 8 个场景：

- 仅当前人类 Owner 可精确确认 Bundle，取消不可逆；
- 只有同 Bundle 的成功前置 Receipt 可释放后继动作；
- Lease 过期只能 Reconcile 接管，旧 Fencing Token 无法提交；
- 无副作用证据 Retry 与 UNKNOWN 分离，有界对账耗尽进入人工队列；
- 人工 Receipt 不可逆，Agent 不能人工终结，原因与证据必须同码；
- 取消写入唯一 Receipt，部分成功时标记人工补偿复核；
- Provider Version/UpdatedAt 的重复、较旧、冲突、较新、MERGED 回退与人工终态合并规则；
- Receipt 外部身份、Dispatch/Receipt 幂等键、终态映射、补偿标记和安全事件的篡改防护。

| 验证 | 结果 |
|---|---|
| M5-D08 + M5-D09 专项测试 | `14 / 14` |
| Domain 模块回归 | `498 / 498` |
| Application 及依赖回归 | `828 / 828` |
| 全仓 Maven 回归 | `1634 / 1634` |
| 文档链接与差异格式门禁 | `通过` |

## 6. 结论

M5-D09 已把精确 ActionBundle 授权转换为可持久、可接管、可对账且终态不可逆的领域协议。未确认或过期授权不能触发新写入，不确定写入不会被普通重试放大，旧 Worker、重复 Provider 事实和迟到事实不能逆转已确定结果。下一任务 M5-D10 落地模型目录、连接、Agent Template 与执行配置 V20 迁移。
