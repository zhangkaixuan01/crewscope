# M6-I02 投影管理、Supervisor 与受审计恢复

## 1. 交付边界

M6-I02 把 M6-D07、M6-E01 和 M6-E07 已冻结的应用 Port 接到 PostgreSQL，并补齐投影重建的后台运行控制：

- `ProjectionAdministrationRepository` 按 ADR-020 固定锁序加载并保存 Definition、Pointer、Generation、RebuildJob、Validation 和 CommandReceipt；
- `OperationsRecoveryRepository` 对精确强版本目标执行一次性恢复调度；同 Organization、Command ID 和同指纹回放收敛，异指纹冲突；
- Projection Supervisor 使用数据库租约、单调 Fencing Token 和持久化 Keyset Cursor 分页重放影子代际；
- Startup Recovery 只接管已过期租约，正常关机把本实例仍持有的租约标记为 `INTERRUPTED`；
- 周期调度有界领取工作，不缓存投影代际路由；在线代际的 Outbox Worker 与影子代际的 Supervisor 使用不同 `worker_role` Claim；
- Retention/Cleanup 只处理 `RETIRED/FAILED/CANCELLED` 代际，并同时满足 Cursor 最长有效期、回滚观察期和 Worker Lease 上限；Audit、DomainEvent、Inbox Disposition 不进入删除集合。

通知恢复在本任务只提交不可变恢复请求和新的调度身份，不调用 Lark。通知 Claim、短期凭证、发送、查询和 Receipt 属于 M6-I03。

## 2. 原子性与历史语义

每个管理员投影命令在一个本地事务内完成状态 CAS、`projection_command_receipt`、安全 DomainEvent、Outbox 和 Audit 投影。事务同时写入 Audit Consumer Receipt，后续 Outbox 至少一次分发不会重复生成 Audit 行。

每个运行恢复命令在一个本地事务内完成目标行锁定、Expected Version 比较、`command_receipt`、`operations_recovery_schedule`、安全 DomainEvent、Outbox 和 Audit 投影。原 Outbox Dead Letter、Projection Dead Letter、Notification Delivery 与失败历史不删除；恢复调度拥有新的 ID 和状态机。

## 3. 数据库运行控制

`projection_worker_claim` 以 `organization + projection + generation + worker_role` 唯一。领取时仅允许空闲、已中断或租约过期记录变更 Owner，同时增加 Fencing Token；续租、Cursor 保存和完成必须同时匹配 Owner、Token、未过期租约及非终态 Generation。只有 `RUNNING` 状态可保留 Owner、Lease 和 Heartbeat；追平、中断与过期恢复会原子清空这些短期坐标，但保留 Cursor 和单调 Fencing Token。

`operations_recovery_schedule` 保存经过白名单化的目标坐标，不保存确认短语、异常文本、凭证、原始消息或 DomainEvent Payload。`projection_cleanup_receipt` 保存清理结果，物理删除顺序由外键决定，清理命令本身保留可审计事实。

## 4. 验证门禁

- PostgreSQL 多实例竞争只有一个 Claim Owner，租约过期后先清空 Owner/Lease/Heartbeat，新实例再获得更大的 Fencing Token；旧实例续租、保存 Cursor 和完成均失败；
- Supervisor 每页最多 1,000 条，重启从持久化 Cursor 继续；空页标记 `CAUGHT_UP`；关机不领取新页；
- Projection Administration 的四类保存路径验证固定锁序、乐观版本、状态守卫、Receipt 回放和 DomainEvent/Audit 同事务回滚；
- 三类 Operations Recovery 验证目标错配、过期版本、并发同指纹、异指纹冲突、历史不可变和零外部调用；
- Cleanup 验证 ACTIVE/BUILDING/VALIDATING、仍被 Pointer 引用、租约未过期和保留期不足的代际均拒绝；
- Spring 条件装配关闭时无 Scheduler、Startup Recovery 和 Health Bean，开启时 Actuator 只返回低基数摘要。

## 5. 实际验证结果

- M6-I02 基础设施专项测试 12 / 12：覆盖完整投影管理生命周期与 Receipt 回放、Outbox/Projection/Notification 三类 Operations Recovery、并发同命令收敛、原子提交与历史保护、Supervisor Claim/接管/Fencing/Cursor/Cleanup，以及 Startup Recovery 条件装配；
- Server 专项测试 2 / 2：覆盖 Health Bean 条件注册、过期租约降级和固定低基数字段集合；
- V29 已在 PostgreSQL 17 上从空 Schema 完成迁移；M6-I01 查询 Adapter、V27 迁移与既有投影运行时回归保持通过；
- 核心实现完成后的全仓 `./mvnw clean verify` 通过，新增补充门禁随后通过模块级编译与专项验证。
