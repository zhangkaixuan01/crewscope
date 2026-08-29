# M7-A07 开放用户 API 与装配合同

> 日期：2026-08-29<br>
> 结论：通过<br>
> 下一任务：`M7-F01`

## 1. 交付范围

M7-A07 冻结 Auth、Account、Onboarding 与 Team Invitation 的 V1 公开 API：

- 8 个写请求 DTO 使用字段闭集，未知字段与服务端坐标失败关闭；
- 注册、Onboarding 与邀请命令要求单值 `Idempotency-Key`；
- 当前账号修改要求单值强 `If-Match`；
- 首次执行与重放响应、错误码、强 ETag 和 `currentVersion` 语义固定；
- 10 个 M7 领域事件精确映射 V1 Reviewed Audit Definition；
- Controller、Application Service、Security Chain 与 Jackson Mapper Bean 保持唯一；
- 有界身份持久化执行器容量耗尽按路由折叠为稳定、可重试的 503；
- 增加可供前端直接实现的 [M7 开放用户 API 契约](../api/M7-开放用户API契约.md)。

## 2. DTO 与 Header 边界

`LoginRequest`、`RegistrationRequest`、3 个 Current Account Request、`CreateFirstTeamRequest`、`CreateInvitationRequest` 和 `InvitationTokenRequest` 均使用类型内 `JsonAnySetter` 拒绝未知字段。M7 没有启用全局 `FAIL_ON_UNKNOWN_PROPERTIES`，避免改变 M0–M6 已发布 DTO；失败关闭只作用于本次冻结的用户体系入口。

`ApiHeaders` 新增 M7 单值解析边界。缺失、重复 Header 行和逗号多值不能被 Spring 合并后静默接受。强 ETag 继续只允许一个无前导零的非负十进制版本。

`localRegistrationPersistenceExecutor` 使用有界 Worker 与等待队列，拒绝新阻塞任务时产生 `IdentityPersistenceCapacityException`。API 边界不公开内部执行器信息：注册路由返回 `503 registration_unavailable`，当前账号路由返回 `503 account_service_unavailable`，两者均标记 `retryable=true`。

注册首次响应不再发送 `Idempotency-Replayed: false`；该 Header 只在完成重放时出现并固定为 `true`，与统一命令协议一致。

## 3. 安全投影

公开响应字段扫描覆盖 Session、Account、Registration、Onboarding、Invitation 与 Command Receipt。Credential、Credential Version、Password/Hash、Session ID、Cookie、Token Digest 不进入响应。两项受控例外保持显式：

- CSRF Token 只出现在 `GET /api/v1/auth/session` 的 CSRF 坐标；
- Invitation 明文 Token 只出现在首次邀请创建响应，重放不再返回。

错误矩阵覆盖全部 Registration、Current Account 与 Invitation Application Failure 枚举，响应不包含输入值或异常原因。既有 M7-I08 认证指标闭集、API Observability 路由模板、稳定 Error Code 和结构化日志 Sanitizer 继续作为日志、Trace 与指标的低基数门禁。

## 4. Audit 与装配

专项合同从 10 个 M7 Domain Event 类型解析 `CrewScopeAuditEventTypes.reviewedRegistry()`，逐一验证：

- `SchemaVersion.V1` 定义存在；
- `SchemaVersion.V2` 未被意外发布；
- Allowed Source Fields 与领域事件 Record 字段完全一致。

Spring Context 验证五个 M7 Controller 和五个 Application Service 各只有一个 Bean；Invitation Service 缺失时 Controller 不创建。Jackson AutoConfiguration 与 AgentScope Legacy Configuration 同时加载时，Jackson 3 Web Mapper 和 Jackson 2 AgentScope Mapper 各一份且类型隔离。`SecurityConfigurationTest` 继续冻结业务与 Prometheus 两条 `SecurityWebFilterChain`。

## 5. 自动化验证

专项命令：

```bash
./mvnw --batch-mode --no-transfer-progress -pl crewscope-server -am -Dtest=M7ApiContractM7A07Test,M7ApiCompositionM7A07Test -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：

```text
M7ApiContractM7A07Test: 9/9
M7ApiCompositionM7A07Test: 3/3
BUILD SUCCESS
```

M7-A01 至 A06 的 Controller、安全路由、真实 Redis Session、PostgreSQL 事务与并发测试继续作为回归门禁。

完整收敛记录见 [M7-A01 至 M7-A07 提交前审查](../reviews/M7-A01-A07提交前审查.md)。
