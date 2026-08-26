# M6-I03 Notification Worker 与查询恢复

> 日期：2026-08-26<br>
> 范围：`crewscope-domain`、`crewscope-application`、`crewscope-infrastructure`、`crewscope-server`<br>
> 结论：通过

## 1. 交付内容

- 建立 Provider 无关的 `NotificationWorker` 与独立 `NotificationReconciliationWorker`；写 Worker 只执行 `READY/RETRY_WAIT`，查询 Worker 只处理 `UNKNOWN` 和过期 Claim。
- V30 为 Notification Delivery 增加 Worker ID、单调 Claim Token、Lease、Heartbeat 和 Reconciliation Count，并增加写入队列与查询恢复队列的部分索引。
- Claim 使用 `FOR UPDATE SKIP LOCKED`；外部调用发生在 Claim 事务返回后，结果事务同时匹配 Organization、Delivery Version、Worker ID、Claim Token 和未过期 Lease。
- 过期 `RUNNING` 先提交为 `UNKNOWN`，再签发查询 Claim；过期 `RECONCILING` 只进行技术重围栏，不进入写队列。
- Provider 写入与查询共享由 Organization、Connection、Action Digest 确定性派生的 UUID。响应丢失、超时和未分类写错误进入查询恢复，不执行盲重试。
- 写入失败使用有界指数退避；查询失败保持不确定状态并按固定间隔重查；超过上限后进入 `FAILED_FINAL`。
- 动作级 `NotificationCredentialHandle` 只在有界回调内暴露临时 Secret 副本，并提供 TTL 与显式关闭契约。数据库、命令、Receipt 和日志均不保存 Token。
- 成功与失败 Receipt 使用 Delivery 确定性 ID，数据库维持每个 Delivery 一个逻辑 Receipt。Provider Reference 和 Message ID 只持久化安全 Hash，结果对象的字符串表示固定脱敏。
- 受审计 `RETRY_NOTIFICATION_DELIVERY` Schedule 使用独立 Claim/Lease/Fencing 消费，以原 Operations Command ID 幂等创建新的 Redelivery Plan，并在完成时保存 Replacement Delivery；原失败 Delivery 与 Receipt 保持不可变。
- Spring 使用构造器注入和条件装配，分别提供写、查询恢复和人工再次投递的非重叠调度器。I04 至 I06 注入真实 Lark Credential Issuer、Connector 和 Provider；M6-I03 不包含飞书 HTTP、成员映射或模板渲染。

## 2. 状态与事务边界

```text
Claim transaction commit
  -> current authorization preflight
  -> issue action-scoped credential handle
  -> Provider send
  -> fenced outcome transaction

possibly accepted write
  -> UNKNOWN
  -> query-only claim
  -> Provider query using the same idempotency UUID
  -> FOUND: SUCCEEDED + Receipt
  -> NOT_FOUND: bounded RETRY_WAIT
  -> inconclusive: UNKNOWN
  -> exhausted: FAILED_FINAL + Receipt
```

Credential 签发失败发生在 Provider 写入前，因此可以安全进入写入退避。`send` 内部抛出的未分类异常可能已经越过 Provider 写边界，因此必须进入 `UNKNOWN`。Lease 到期后，即使旧调用随后返回，其 Version/Token/Lease 组合也无法通过回写条件。

## 3. 验证

专项与 PostgreSQL 联合命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-domain,crewscope-application,crewscope-infrastructure,crewscope-server -am \
  -Dtest=NotificationWorkerM6I03Test,\
JdbcNotificationPlanRepositoryM6I01IntegrationTest,\
NotificationWorkerApplicationConfigurationM6I03Test \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：15 个测试通过，0 Failure，0 Error，0 Skip；其中 10 个验证 M6-I03 新增风险，5 个保留 M6-I01 持久化回归。

覆盖：

1. Claim 事务提交前 Provider 零调用；
2. 同一 Delivery 并发 Claim 只有一个 Worker 成功；
3. 完成后重复轮询零重复消息；
4. 写响应丢失进入 `UNKNOWN`，查询使用相同 Provider UUID 恢复成功；
5. 过期写 Lease 被查询 Worker 接管，旧 Token 零回写；
6. 退避达到上限后只保存一个失败 Receipt；
7. V1 至 V30 在空 PostgreSQL 上完整迁移；
8. Credential/Provider Port 缺失时 Spring 失败关闭，禁用调度仍保留可手动调用的 Worker；
9. Worker ID、Lease、Credential TTL、退避、尝试次数和 Batch Size 的配置边界。
