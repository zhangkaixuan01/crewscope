# M6-I05 Lark Collaboration Provider 与映射 Preflight

> 日期：2026-08-26<br>
> 范围：`crewscope-application`、`crewscope-integration`、`crewscope-infrastructure`、`crewscope-server`<br>
> 结论：通过

## 1. 交付内容

- `LarkCollaborationProvider` 同时实现 Capability Provider、精确身份验证 Port 和健康检查 Port，固定声明 `COLLABORATION / lark-collaboration / REQUIRED` 与完整 Lark 能力集合。
- Provider 只执行当前 Tenant 查询和精确 `open_id` 成员查询。成员响应必须返回同一 `open_id`，同时保存 `union_id` 和 Connector 声明的 `contact-user-open-api-v1` 契约版本；姓名、昵称、手机号、邮箱和模糊搜索不进入验证流程。
- `DefaultLarkConnectionAuthorizationResolver` 复用 ADR-006 `ProviderBindingResolver.resolveCurrent`，要求 Organization、Team、Binding、TEAM Owner、Lark Implementation、Connection、Grant 和 Capability 全部精确且当前有效。
- `LarkCollaborationApplicationService` 在 Preflight 和健康检查前要求当前 ACTIVE Team Member 拥有 TEAM Scope 的 `PROVIDER_MANAGE`；Preflight 必须完成一次实时 Tenant 查询。
- Preflight 结果只返回 Binding、Connection、Grant 的 ID/Version 与检查时间。健康结果只返回封闭状态、Retryable、受限 Retry-After、Evidence Code 与检查时间，不返回 Tenant Key、Open ID、Union ID、Endpoint、Token、Secret 或原始 Response Body。
- 当前 Binding、Connection 或 Grant 无法解析时，健康检查返回 `AUTHORIZATION_UNAVAILABLE`，不会访问 Provider。即使 Tenant Token 已缓存，每次远端请求前仍由 I04 重新验证 Connection、Grant、Credential 和动作能力。
- `LarkMemberMappingApplicationService` 提供管理员验证、确认、撤销和列表管理；验证 Proof 有效窗口通过配置限制在 1 秒至 15 分钟。
- Mapping 列表固定 Organization/Team，可选精确 Status，按 `updated_at DESC, id DESC` 使用稳定 Keyset 分页，页大小为 1 至 100，不提供外部身份模糊筛选。
- `JdbcLarkCollaborationRepositoryAdapter` 落地 ExternalTenant、追加式 Proof 和双唯一 Mapping Repository。Mapping 替换在同一事务内终结旧记录并插入 Replacement，强版本冲突和内部/外部身份唯一冲突均失败关闭。
- Spring 使用构造器注入和条件装配。网络 Client、授权 Resolver、管理员策略、Preflight Service 或持久化 Port 缺失时，不会暴露不完整的映射服务。

M6-I05 不包含固定模板渲染、`NotificationProviderPort` Lark Adapter、Receipt 映射、任意文本发送或飞书入站消息；这些边界留给 M6-I06。

## 2. 授权与身份边界

```text
ACTIVE Team administrator + PROVIDER_MANAGE
  -> exact Organization / Team / Binding
  -> ADR-006 current Binding resolution
       TEAM Owner + lark-collaboration
       current Connection + Grant + Capability
  -> live Tenant query
       configured tenant_key == authenticated tenant_key
  -> exact open_id query
       requested open_id == returned open_id
  -> short-lived Proof
  -> administrator confirmation
  -> double-unique active Mapping
```

健康检查和成员验证都沿用同一授权图。撤销 Connection、Grant、Credential、Binding 或 Capability 后，旧 Proof、旧 Mapping 和已缓存 Tenant Token 都不能越过当前授权检查。

## 3. 验证

应用层与领域联合命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-application -am \
  -Dtest=LarkCollaborationM6I05Test,LarkMemberMappingM6D04Test \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Connector 联合命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-integration -am \
  -Dtest=LarkConnectorM6I04IntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

PostgreSQL Adapter 命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  -Dtest=JdbcLarkCollaborationRepositoryM6I05IntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Spring 条件装配命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-server -am \
  -Dtest=LarkConnectorApplicationConfigurationM6I04Test \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：26 个测试通过，0 Failure，0 Error，0 Skip。其中应用层与 D04 联合回归 12 个、Connector 7 个、真实 PostgreSQL 1 个、Spring 装配 6 个。

覆盖：

1. 固定 Tenant 查询、精确 `open_id`、Tenant/User 身份不匹配与 Provider 契约；
2. ADR-006 当前 Binding、TEAM Owner、Organization/Team Scope、Implementation 与 Capability；
3. ACTIVE Team Member、TEAM Role Scope 和 `PROVIDER_MANAGE`；
4. Preflight、健康状态、403/404/429/5xx、合法 Retry-After 和安全 Evidence；
5. Token Cache 命中后 Connection 撤权、撤权后的外部零请求；
6. Proof 时效、Mapping 双唯一、冲突收敛、强版本与原子 Replacement；
7. Organization/Team/Status 过滤、稳定 Keyset 和页大小边界；
8. ExternalTenant、Proof、Mapping 的真实 PostgreSQL 重建与 Spring 失败关闭。
