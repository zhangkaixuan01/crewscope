# M5-I11：Action Worker 与两步交付事务

> 状态：已完成
> 日期：2026-08-23
> 关联：[ADR-019](../adr/ADR-019-ActionBundle调度与外部结果对账协议.md)、[M5-I09](M5-I09-GitHub-Mirror-AskPass与幂等Push.md)、[M5-I10](M5-I10-Draft-PR幂等与Webhook对账.md)

## 目标

把已确认的 `Push Branch -> Create Draft PR` ActionBundle 接入耐久 Action Worker。Worker 只执行已经提交、当前仍获授权且依赖就绪的 `READY` Dispatch；数据库 Claim 提交后才进入 GitHub 写边界，外部结果通过唯一 Receipt 与终态 Dispatch 原子收敛。

## 执行协议

一次执行遵循以下边界：

1. 按 Organization 有界发现 `READY` Dispatch；
2. 在短事务中使用 `FOR UPDATE SKIP LOCKED` 锁定候选；
3. 读取原 ActionBundle、Confirmation 和当前 Review、OWNER、Provider、Connection/Grant、Policy、Safety Overlay、CodingTarget、RepositoryBinding 事实；
4. 领域层重新验证精确 Digest、有效期、当前授权和成功依赖；
5. 递增 Fencing Token，提交 `RUNNING` Claim、DomainEvent、TaskEvent 与 Outbox；
6. Claim 事务提交后执行 GitHub Repository Preflight 和 Provider 写操作；
7. 在新的短事务中复验 Worker、Fencing Token 和 Lease；
8. 原子写入唯一 ActionReceipt、终态 Dispatch、DomainEvent、TaskEvent 与 Outbox；
9. Push 成功 Receipt 提交后，依赖 SQL 才允许 Draft PR Dispatch 被领取。

GitHub Token、Credential Handle、AskPass、Provider 原始响应、内部 Endpoint、PR 正文和规范 Provider URL 不进入 Action 事件。事件只保存动作 Digest、低基数状态、外部身份 Hash 和证据 Hash。

## 失败分类

- 明确成功：写入 `SUCCEEDED` Receipt；
- 明确失败：写入 `FAILED` Receipt，后续依赖保持不可领取；
- 已证明无副作用的限流、Provider 不可用或 Mirror 不可用：在 Confirmation 有效窗口内延迟回到 `READY`；
- 策略解析、请求装配等 Provider 调用前的平台内部异常：直接外抛并保持可观测，不伪造外部写入不确定性；
- 已经进入 Provider 调用窗口，可能越过外部写边界的超时、响应损坏或未分类运行时错误：进入 `UNKNOWN`，不普通重试；
- Receipt 写入、Dispatch 终结或事件/Outbox 任一步失败：整个结果事务回滚；
- Claim 事件写入失败：整个 Claim 事务回滚，不留下不可观测的 `RUNNING`。

M5-I11 只领取 `READY`。`UNKNOWN`、过期 `RUNNING`、主动查询、Webhook 合并、启动接管和人工队列由 M5-I12 处理。

## PostgreSQL 与迁移

`JdbcActionDefinitionRepositoryAdapter` 持久化并复验 ActionBundle、PlannedAction 图和 Confirmation。`JdbcActionExecutionRepositoryAdapter` 提供：

- Organization Scope 查询；
- `FOR UPDATE SKIP LOCKED` READY Claim；
- 成功 Receipt 依赖过滤；
- 乐观版本与单调 Fencing 更新；
- `ON CONFLICT DO NOTHING` Receipt 幂等插入及冲突事实复验；
- 完整 Dispatch、Claim、Receipt 和外部身份恢复。

`V26__action_receipt_claim_coordinates.sql` 为自动 Receipt 增加 `claim_mode`、`claim_acquired_at`、`claim_last_heartbeat_at` 和 `claim_lease_until`。新 Receipt 可无损恢复完整 Claim；历史 Receipt 使用受约束的兼容坐标回填。只追加 Trigger 在迁移期间受控移除并在约束建立后恢复。

## Spring 装配

配置前缀为 `crewscope.action.worker`：

```yaml
enabled: true
worker-id: action-worker-local
poll-interval: 1s
lease-duration: 2m
retry-delay: 15s
batch-size: 10
```

Action Worker 只在 Dispatch、Receipt、Bundle、Confirmation、当前事实解析器、Repository Policy、GitHub Push、Draft PR、事件、事务和时间 Port 全部存在时装配。`enabled=false` 只关闭 Scheduler，保留可手动调用的 Worker。Worker ID、轮询、Lease、重试和批量参数均执行上限与下限校验。

M5-I11 使用全局配置的 GitHub Repository Allowlist 作为失败关闭的 Bootstrap Policy；空 Allowlist 不允许任何 Repository。M5-A06 将其替换为每 Connection 的持久化治理策略。

## 验证

专项测试覆盖：

- Push 后 Draft PR 严格顺序，Provider 调用全部位于事务外；
- Push `UNKNOWN` 不创建 Receipt、不执行 Draft PR；
- Provider 调用前的平台异常直接外抛，Provider 调用窗口内的未分类异常才进入 `UNKNOWN`；
- Push 成功而 Draft PR 明确失败时不重复 Push；
- 两 Worker 对同一 READY Dispatch 的 `SKIP LOCKED` 互斥；
- 旧 Fencing Token 无法插入 Receipt；
- Receipt 插入后终态 Dispatch 更新冲突时整体回滚；
- 重复逻辑 Receipt 只保留第一条；
- V25 到 V26 历史回填与不完整 Claim 坐标拒绝；
- Spring 条件装配、Scheduler 开关和全部配置边界。

```bash
./mvnw -q -pl crewscope-application -am \
  -Dtest=ActionWorkerM5I11Test \
  -Dsurefire.failIfNoSpecifiedTests=false test

./mvnw -q -pl crewscope-infrastructure -am \
  -Dtest=JdbcActionWorkerPersistenceM5I11IntegrationTest,V26ActionReceiptClaimCoordinatesMigrationIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

./mvnw -q -pl crewscope-server -am \
  -Dtest=ActionWorkerApplicationConfigurationM5I11Test \
  -Dsurefire.failIfNoSpecifiedTests=false test
```
