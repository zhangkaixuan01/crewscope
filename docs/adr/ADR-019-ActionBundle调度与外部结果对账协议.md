# ADR-019：ActionBundle 调度与外部结果对账协议

> 状态：ACCEPTED<br>
> 日期：2026-08-22<br>
> 关联决策：[ADR-005](ADR-005-事件与投影协议.md)、[ADR-007](ADR-007-API命令与并发协议.md)、[ADR-018](ADR-018-GitHub连接与Draft-PR交付边界.md)<br>
> 影响里程碑：M5

## 背景

CrewScope 在成员批准 Review 后生成包含 Push Branch 和 Create Draft PR 的 ActionBundle。两个动作具有顺序依赖，且外部系统不能与 CrewScope 数据库参加同一事务。Worker 可能在外部写入完成、ActionReceipt 提交前退出，Provider 可能保存写入后丢失响应，Webhook 也可能重复、乱序或晚于主动查询到达。平台需要在至少一次调度下收敛为唯一业务结果，并保证旧 Worker、重复事件和人工处理后的迟到事实不能逆转终态。

## 决策

### ActionBundle 摘要与确认

ActionBundle 保存有序 PlannedAction。每个 ActionDigest 使用带版本的规范编码和 SHA-256，至少覆盖：

```text
Action ID 与 ActionKind
类型化规范参数
动作顺序与依赖
ReviewDecision ID/Version 与 ReviewSubject Hash
责任事实版本
ProviderBinding ID/Version
ConnectionGrant ID/Version
PolicySnapshot/Safety Overlay Version
目标资源身份与前置版本
风险和有效期
```

BundleDigest 按动作顺序覆盖每个 Action ID、ActionDigest 和依赖关系。Confirmation 绑定精确 BundleDigest 和全部子 ActionDigest。参数、顺序、依赖、Review、责任、Binding、Grant、策略、目标前置版本或风险任一变化都会生成新摘要，旧 Confirmation 失效。浏览器、Agent 和 Provider 不能提交或覆盖服务端计算的摘要事实。

源码交付 Bundle 在计划时就要求 EffectiveAccess 完整包含目标 Repository 上的 `source.write` 和 `pull-request.create`。权限和资源的部分交集不是充分授权，不能用于生成 Push + Draft PR 动作图。

### 事务提交与 Dispatch

ActionBundle、PlannedAction、Confirmation、DomainEvent、Outbox 和初始 ActionDispatch 在同一个 REQUIRED 数据库事务中提交。Worker 只 Claim 已提交的 Dispatch 行；事务提交前和回滚后外部调用数量必须为零。生产实现使用事务性 Outbox 唤醒 Worker，进程内 `afterCommit` 回调不能作为唯一耐久调度事实。

Confirmation 恢复和每次授权复验同时闭合 Organization/Team/Workspace/WorkProject Scope、当前 Bundle ID/Digest、全部有序 Action Digest、确认人和 Audit 创建人。确认人必须仍等于 Bundle 当前责任快照中的人类 Owner；仅篡改 Confirmation 行的 Scope、确认人、Bundle Digest 或子动作 Digest 不能获得外部写权限。

Dispatch 使用至少一次领取和有限 Lease：

- 每次 Claim 递增 Fencing Token，并保存 Worker、Lease 到期时间和尝试事实；
- Worker 续租、状态更新和 Receipt 提交必须比较当前 Fencing Token；
- Lease 到期后新 Worker 可以领取同一逻辑 Action，旧 Worker 的迟到提交被拒绝；
- 接管 Worker 在可能发生外部写入时先对账，不能直接重放写请求；
- 依赖动作拥有唯一成功 Receipt 后，后继动作才可 Claim。

### Action 状态与唯一结果

动作状态主路径为：

```text
READY -> RUNNING -> SUCCEEDED | FAILED
                 -> UNKNOWN -> RECONCILING -> SUCCEEDED | FAILED
                                          -> MANUAL_REVIEW
MANUAL_REVIEW -> MANUALLY_SUCCEEDED | MANUALLY_FAILED
```

`UNKNOWN` 表示写请求可能已经生效，但平台尚无充分证据提交成功或失败。网络断开、响应丢失、Worker 退出和不确定的 Provider 错误均先进入该状态。它不是普通可重试失败。

每个 PlannedAction 只有一个逻辑 ActionReceipt，数据库使用 Organization、PlannedAction 的唯一约束，并对稳定外部业务键建立相应唯一约束。Receipt 保存结果分类、外部身份、目标版本、证据摘要、接收时间和对账来源，不保存 Secret、原始响应或无限正文。查询、Webhook、重试和人工调查可以追加多个 Observation、Attempt 与 Audit，但不能创建第二个逻辑 Receipt。终态 Receipt 不可改写。

ActionReceipt 与 ADR-007 的 Command Receipt 具有不同职责：Command Receipt 收敛用户/API 命令提交；ActionReceipt 收敛数据库事务之外的外部副作用。两者使用独立唯一键和生命周期，不能互相替代。

### 外部写入与对账

Worker 对每种 ActionKind 实现稳定业务坐标、执行前查询、类型化写入和执行后查询：

- Push 使用 Repository、完整 Branch Ref、Delivery Head 和 Expected Remote Head；接管或不确定结果先查询远端 Head，等于 Delivery Head 时补写唯一成功 Receipt；
- Draft PR 使用 Repository、Head、Base、Head SHA、Draft、标题和正文摘要；响应不确定时先查询候选并精确复验，匹配时补写唯一成功 Receipt；
- 无证据证明请求未发生时，禁止直接重放外部写操作；
- Push 成功而 Draft PR 失败时只调度或对账 Draft PR，不重复 Push；
- 对账读取可以执行有界退避和限流等待，写入重试必须重新经过当前授权、Confirmation、依赖和目标前置校验。

### Webhook、主动查询与 ExternalResult

Webhook 与主动查询合并到同一稳定 ExternalResult。Webhook 先完成签名、Connection、Repository 和事件类型验证，再以 Provider Connection 与 Delivery/Event ID 去重。ExternalResult 保存稳定外部 ID、Provider 状态、Provider Version/更新时间、最后可信来源和观察时间。

合并顺序优先使用 Provider 单调版本或状态序列；只有 Provider 没有版本时才使用 Provider 更新时间和受控状态迁移表。接收时间不能覆盖 Provider 时间。同一 Provider Version 只能重放相同状态，冲突状态记录异常 Observation 并保持原结果。重复事件只记录去重指标，旧事件可以保留 Observation，但不能覆盖更新状态。主动查询与 Webhook 使用同一合并函数和数据库锁/乐观版本，不维护两套结果。

Action 成功只表达已完成确认过的写操作；PR 后续关闭、重开或合并更新 ExternalResult，不改写原 ActionReceipt。迟到事件同样不能把失败或人工终结的 Action 恢复为可执行状态。

### 人工处理

只有在有界自动对账后仍无法证明外部结果时，Action 才进入人工队列。人工终结要求当前平台操作权限、强版本前置、稳定原因码、用户可见说明和可引用证据。人工成功或失败使用唯一约束写入尚不存在的 Receipt、追加 Audit，并进入不可逆终态，禁止通过覆盖写绕过 Receipt 唯一性。后续 Webhook、查询、重试或旧 Worker 只能追加 Observation，不能覆盖人工裁决；发现矛盾事实时创建独立安全/运维告警。

## 实现约束

1. Action Worker 只能通过已提交 Dispatch Claim 执行，Controller、Agent Tool、浏览器和 Outbox Publisher 不能直接调用 Provider 写方法。
2. Claim、Lease、Fencing、状态迁移、Receipt 和依赖释放由数据库事务裁决；系统时间使用数据库权威时间。
3. ActionKind 提供独立的执行前查询、写入、对账和证据规范，不能用通用 HTTP 重试器重放写请求。
4. Receipt 成功提交与后继动作 READY 转换在同一数据库事务中完成。
5. Observation 与原始 Provider Payload 分离；持久化内容经过大小限制、字段白名单、哈希和 Secret 脱敏。
6. Webhook Delivery 去重键包含 Connection，防止不同 Provider 安装使用相同 Event ID 冲突。
7. Reconcile 批次、退避、RateLimit、最大 UNKNOWN 时间和人工队列阈值可配置并具有低基数指标。
8. M5-D08 已交付正式 ActionBundle、PlannedAction、类型化参数、动作图、Digest 与当前事实失效规则；M5-D09 已交付 Confirmation、Dispatch/Lease/Fencing、唯一 Receipt、UNKNOWN/Reconcile、ExternalResult 单调合并与人工终态；M5-D11 已用 V21 落地精确权威外键、Digest/外部业务键唯一约束、只追加 Receipt/Observation、受控 Dispatch 状态与 Fencing、单调 ExternalResult；Worker 和 Provider Adapter 由 M5-I08 至 M5-I12 实现。

## 结果

- 精确 Confirmation 只授权一个不可漂移的 ActionBundle；
- 数据库回滚、重复调度、Worker 退出和旧 Lease 不会产生未授权外部写入；
- Push 与 Draft PR 在响应丢失时通过外部权威查询补写唯一 Receipt；
- Webhook、主动查询和人工证据形成一个可解释的结果链；
- 部分成功只继续执行未完成的依赖后继动作。

## 验证

1. 动作参数、顺序、依赖、Review、责任、Binding、Grant、Policy 或目标前置变化使 Confirmation 失效。
2. 事务提交前和回滚后 Worker 不可见 Dispatch，Provider 写入为零。
3. Push 已生效、Receipt 保存前退出后，新 Worker 查询远端 Head 并补写唯一 Receipt，Push 计数保持 1。
4. Draft PR 创建响应丢失后进入 UNKNOWN，查询 Head/Base/Commit 后补写唯一 Receipt，PR 创建计数保持 1。
5. Lease 到期后新 Fencing Token 接管，旧 Worker 不能提交 Receipt。
6. Webhook 重复、乱序和旧事件与主动查询合并为一个 ExternalResult，不覆盖更新状态。
7. 人工终结必须有证据和 Audit，终态不被迟到 Webhook、查询或 Worker 逆转。
8. Push 成功、PR 不确定或失败时只继续 PR，Push 不重复执行。

协议验证见 [M5-S05 ActionBundle 与外部结果对账验证记录](../spikes/M5-S05-ActionBundle与外部结果对账验证记录.md)，正式领域契约见 [M5-D09 Confirmation 与 Action 结果状态机](../testing/M5-D09-Confirmation与Action结果状态机领域契约.md)。M5-Q02 已验证 48 / 48 固定故障收敛，重复逻辑 Receipt、旧 Fencing 终态写入和未进入确定状态或人工队列的 UNKNOWN 均为 0，见 [M5-Q02 模型、Review 与 GitHub 交付故障恢复](../testing/M5-Q02-Fault-Recovery.md)。

## 重新评估条件

- 外部 Provider 普遍提供可查询、强一致且长期稳定的原生幂等键；
- Action 跨越多个事实数据库或需要跨区域主动写入；
- 支持自动 Merge、部署、支付等不可逆高风险动作；
- Provider 没有可查询的稳定业务键或任何可验证外部证据；
- 人工终结需要四眼审批、电子签名或合规归档。
