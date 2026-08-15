# M3-A03 受信 Worker Command Port

## 目标

建立一个可审计、可重放、受 Lease/Fencing 保护的 Worker 命令边界，复用 M3-I02 的 Claim Scheduler、M3-I03 的 Lease Coordinator 和 M3-I04 的 Task Token 当前事实验证。

## 命令协议

- `WorkerTaskCommandService.claim(limit)` 是进程内受信启动 Port，继续使用已配置的 Runtime/Worker 稳定 Identity、配额、一次性 Claim Token 和 PostgreSQL 公平队列；
- `POST /api/internal/v1/worker/executions/{executionId}/prepare`：`CLAIMED -> PREPARING`，要求 `If-Match`；
- `POST .../start`：`PREPARING -> RUNNING` 并切换 RUN Lease，要求 `If-Match` 和 `X-CrewScope-Lease-Version`；
- `POST .../heartbeat`：续租当前 Phase，要求 `X-CrewScope-Lease-Version`；
- `POST .../progress`：提交有界 `safeSummary` 与可选 `percent`，推进 TaskExecution Version，要求 `If-Match`；
- `POST .../complete`：原子提交 COMPLETED 与 Lease Release，要求两个版本；
- `POST .../fail`：只接受 `TaskExecutionFailureClass` 和大写稳定错误码，原子提交 FAILED 与 Lease Release。

所有 HTTP mutation 要求 `Idempotency-Key`。响应返回 Command/Event/Correlation ID、审计事件的 committedVersion、operation，以及下一次命令应使用的 TaskExecution/Lease Version。精确重放返回相同 Receipt 并增加 `Idempotency-Replayed: true`。

## 安全与审计

- Claim 发生时尚无 Task Token，因此只保留进程内 Port，不引入浏览器 Session、Basic Auth 或长期 Worker Secret；
- HTTP 命令只从 `TaskTokenWebFilter` 注入的 `TaskTokenExecutionContext` 解析 Organization、Execution、attempt、Lease、Runtime、Worker、Claim Token Hash、Fencing Token 和 Execution Principal；
- Route Execution 必须等于 Token Execution，Body 采用命令白名单，伪造身份字段或保留 Header 在进入应用层前拒绝；
- Coordinator 再次使用 PostgreSQL 权威时间复验 Lease 未过期、全坐标 Owner 匹配与 TaskExecution 当前 Fencing Epoch；
- 首次 mutation 同一事务写入脱敏 `WORKER_TASK_*_ACCEPTED` DomainEvent、Outbox 和 CommandReceipt，AuditEvent 投影可追踪 Runtime Worker Service Principal、TaskExecution、Correlation 与结果；
- 响应和事件不保存 Claim Token 明文/Hash、Fencing Token、Task Token、Provider 凭证或原始异常。

## 错误契约

- 无效、过期、撤销、旧 Fencing Epoch 或错误 Worker 的 Task Token：`401 task_token_invalid`；
- Route、Body/Header 身份伪造或应用层 Lease Ownership 失配：`409 worker_ownership_invalid`，不返回内部坐标；
- TaskExecution/Lease 版本冲突：`409 optimistic_lock_conflict`；
- 重复终态或非法状态迁移：`409 invalid_state_transition`；
- 缺少版本前置条件：`428 precondition_required`；
- 所有 Worker 响应使用 `Cache-Control: no-store`。

## 验证

- `TaskExecutionProgressTest`：Progress 只允许 RUNNING、推进版本/审计并拒绝旧 Version 和终态；
- `WorkerTaskCommandServiceM3A03Test`：Claim 委派、六类 mutation、Token 所有权坐标、幂等重放、审计事件、脱敏 Fail/Progress 与版本/重复终态冲突；
- `WorkerTaskCommandControllerM3A03Test`：强版本 Header、Task Token Context、Body/Header 伪造、错误 Execution 路由、安全错误遮罩和外部 `/api/v1` 无可调用路由；
- M3-I03/M3-I04 现有回归继续覆盖旧 Claim/Fencing Token、错误 Worker、Lease 过期、Task Token 撤销/轮换和 Basic/OIDC Session 无法调用 Worker 路由。

## 下一项

`M3-A04`：实现成员 Pause、Resume、Cancel 和 Retry 命令，将请求态传播到 Worker/AgentScope 安全点。
