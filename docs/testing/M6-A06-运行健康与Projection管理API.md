# M6-A06 运行健康与 Projection 管理 API

## 1. 交付结果

M6-A06 在 M6-E07、M6-I01、M6-I02 和 M6-I08 已完成的健康查询、原子恢复、Projection 代际管理和低基数观测能力上增加安全 HTTP 边界。

成员入口：

- `GET /api/v1/organizations/{organizationId}/teams/{teamId}/operations/health`。

管理员入口：

- `GET /api/v1/organizations/{organizationId}/operations/diagnostics`；
- `POST /api/v1/organizations/{organizationId}/operations/recoveries`；
- `POST /api/v1/organizations/{organizationId}/operations/projections/{projectionName}/rebuilds`；
- `POST /api/v1/organizations/{organizationId}/operations/projections/{projectionName}/rebuilds/{rebuildJobId}/retry`；
- `POST /api/v1/organizations/{organizationId}/operations/projections/{projectionName}/generations/{generation}/validate`；
- `POST /api/v1/organizations/{organizationId}/operations/projections/{projectionName}/generations/{generation}/switch`；
- `POST /api/v1/organizations/{organizationId}/operations/projections/{projectionName}/generations/{generation}/rebuilds/{rebuildJobId}/cancel`；
- `POST /api/v1/organizations/{organizationId}/operations/projections/{projectionName}/generations/{generation}/rebuilds/{rebuildJobId}/fail`。

## 2. 成员与管理员视图

成员健康摘要先通过服务端身份解析和当前 Team 可见性复验，再返回 `observedAt`、总体 Health 以及 Projection、Outbox、DeadLetter、Cursor、Notification 五个固定组件。每个组件只包含枚举状态、Backlog、InFlight、Failure、Affected、最老积压秒数和 Stale。Organization、Team、Projection、Generation、Job、Event、Delivery、Payload、错误文本和身份信息不进入成员 DTO。

管理员诊断重新验证当前 Organization Administrator，返回安全 Projection 坐标、强版本、Lag/Gap/DeadLetter 计数、有界 FailureCode 和最多固定数量的恢复候选。响应直接给出与当前 Action、目标和版本绑定的确认短语，前端不拼接或猜测危险命令确认值。诊断不返回 DomainEvent Payload、通知正文、Provider Body、Credential、Lease、Worker 或内部异常。

## 3. 恢复与 Projection 命令

恢复请求使用闭合 `target.type` 联合，只接受：

- `OUTBOX_DEAD_LETTER`：Outbox Event、DomainEvent 和 Expected Version；
- `PROJECTION_DEAD_LETTER`：Projection、Generation、DeadLetter、DomainEvent 和 Expected Generation Version；
- `NOTIFICATION_DELIVERY`：Delivery 和 Expected Version。

类型不匹配的额外字段、任意 SQL、URL、Method、Body、表名和未知属性全部在传输边界拒绝。所有恢复命令要求 `Idempotency-Key` 和精确确认短语；服务端按命名空间、Organization 和 Idempotency-Key 派生稳定 Command UUID。应用与 PostgreSQL 层继续负责当前管理员复验、Command Fingerprint 回放、目标锁定、版本比较、恢复调度、DomainEvent、Outbox 与 Audit 原子提交。

Projection 管理不提供通用 Action 执行入口。Start、Retry、Validate、Switch、Cancel 和 Fail 各自映射为一个强类型 Command。命令体携带本次事务涉及的完整 Definition、Pointer、Generation 和 RebuildJob Expected Version；Generation 来自路径且必须与确认短语一致。Switch 继续使用固定锁顺序并仅能激活已经通过规范快照校验的影子代际。命令响应返回新的安全状态以及后续命令需要的 Generation、RebuildJob 和 Pointer Version。

所有 Definition、Pointer、Generation 和 RebuildJob 版本漂移统一映射为 `409 optimistic_lock_conflict`，并返回 `expectedVersion`、`actualVersion` 与 `currentVersion`。冲突在任何写入前失败关闭，不生成 Lifecycle Event、Outbox、Audit 或 Command Receipt。

## 4. 失败关闭与装配

所有阻塞应用与 JDBC 调用切换到 WebFlux `boundedElastic` Scheduler。成功响应使用 `Cache-Control: no-store`。请求对象通过 `JsonAnySetter` 拒绝未知控制字段，公开响应使用显式 DTO 白名单。

`OperationsController` 强依赖 `OperationsHealthService`、`OperationsRecoveryService`、`ProjectionAdministrationService` 和 `TeamRequestIdentityResolver`。任一能力缺失时 Spring 上下文启动失败，不暴露半装配的运维 API。Actuator 继续使用 M6-I08 的独立低基数健康、授权和指标端点；业务恢复命令不会经由 Actuator 暴露。

## 5. 验证

新增专项测试：

- `OperationsControllerM6A06Test`：成员低基数摘要、管理员诊断、权限拒绝、安全 DTO、三类恢复 Target、精确确认、稳定 Command ID、闭合集合、Start/Retry/Validate/Switch/Cancel/Fail 强类型映射、稳定 409 版本冲突和未知字段拒绝；
- `OperationsControllerAssemblyM6A06Test`：完整装配和缺少服务时启动失败关闭；
- 联合回归复用 `OperationsApplicationConfigurationM6E07Test` 和 `TeamBetaOperationalTelemetryM6I08Test` 验证条件 Bean、阈值配置、Operations Gauge 与 Actuator 观测基线；
- M6-D07、M6-E07、M6-I01 和 M6-I02 既有专项继续覆盖旧版本冲突、并发重建、验证失败禁止切换、Command Receipt 回放、精确 Generation、事务原子性和不可变失败历史。

执行命令：

```bash
./mvnw -pl crewscope-server -am \
  -Dtest='OperationsControllerM6A06Test,OperationsControllerAssemblyM6A06Test,OperationsApplicationConfigurationM6E07Test,TeamBetaOperationalTelemetryM6I08Test,ProjectionAdministrationServiceM6D07Test' \
  -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw clean verify
node scripts/check-doc-links.mjs
git diff --check
```

结果：27 / 27 专项测试通过，包含 7 项 Projection 应用层版本冲突与不可变写入边界。
