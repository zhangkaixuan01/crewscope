# M6-I04 Lark Connector 与 Tenant Token 安全缓存

> 日期：2026-08-26<br>
> 范围：`crewscope-integration`、`crewscope-server`<br>
> 结论：通过

## 1. 交付内容

- `LarkOpenApiClient` 只暴露 Tenant 查询、精确 `open_id` 成员查询、固定 Text 消息传输和精确 Message ID 查询；不存在任意 URL、Method 或 Body 接口。
- 生产 Origin 固定为 `https://open.feishu.cn`。测试只能显式开启带端口的 `127.0.0.1` 或 `::1` HTTP Origin；UserInfo、Path、Query、Fragment、Host 别名和元数据地址全部拒绝。
- 每次 HTTP 调用前重新验证 Organization、Connection/Grant ID 与 Version、当前有效期、能力交集、Credential 元数据、Secret Version、Connection Reference 和 Credential Subject。
- 成员操作要求 `collaboration.member.lookup-exact`；消息发送与查询要求 `collaboration.notification.send-fixed-template`。Tenant 查询只验证当前授权，用作 I05 Preflight 基础。
- `app_id/app_secret` 以加密 CredentialStore Secret 中的固定 JSON 保存，只在动作级短期 `LarkCredentialHandle` 回调内解析。Handle、ResolvedCredential、临时 Secret 副本、请求载荷和响应字节均有显式关闭或清理边界。
- Tenant Token Cache Key 包含 Organization、Connection/Grant ID 与 Version、Credential ID/Version、Secret Version 和 Tenant Key。缓存按 Key Single Flight、容量上限和最近访问淘汰，Token 至少保留 60 秒到期安全余量。
- 401 只失效精确 Cache Key 并最多刷新一次；第二次 401 同样清除坏 Token。429、5xx 和传输错误只归一化，不在 Connector 内重试，由 M6-I03 Worker 统一管理耐久重试与查询恢复。
- 读超时归一化为 `PROVIDER_UNAVAILABLE`，可能已经写入的消息超时归一化为 `UNKNOWN_DELIVERY`，线程中断恢复中断标记并返回 `CANCELLED`。`Retry-After` 只接受 1 至 300 秒。
- 错误只保留稳定 Code、Retryable、受限 Retry-After 和 Evidence Code；Endpoint、Authorization、Token、Secret、原始 Body 和外部身份不进入异常、结果或安全摘要。
- Spring 使用构造器注入和条件装配。缺少 ConnectionRepository、ConnectionGrantRepository 或 CredentialStore 时不创建网络 Client；配置边界在 Bean 创建时失败关闭。

M6-I04 提供 I05 成员映射与健康检查、I06 固定模板通知 Provider 的低层安全传输基础；本任务不实现 Mapping Adapter、模板 Registry、NotificationProviderPort 或 Receipt 映射。

## 2. 固定调用与恢复边界

```text
current authorization + actor
  -> verify operation capability
  -> re-read Connection + Grant + Credential metadata
  -> authorization-scoped Token Cache
       miss: short-lived Credential Handle -> tenant token exchange
  -> one fixed Lark endpoint
       401: invalidate exact key -> refresh once
       429/5xx/read failure: normalized result -> durable Worker
       possible write failure: UNKNOWN_DELIVERY -> query recovery
```

## 3. 验证

专项命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-integration,crewscope-server -am \
  -Dtest=LarkConnectorM6I04IntegrationTest,\
LarkConnectorApplicationConfigurationM6I04Test,\
LarkOpenApiM6S04IntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：16 个测试通过，0 Failure，0 Error，0 Skip。其中 6 个生产 Connector Loopback 场景、4 个 Spring 装配场景、6 个 S04 冻结协议回归。

覆盖：

1. Tenant A/B Token 隔离、同 Key 并发 Single Flight、Secret Version 轮换形成新 Key；
2. 首次 401 精确刷新、第二次 401 终结并清除坏 Token、其他 Tenant 不刷新；
3. Connection、Grant、Credential 撤销或不可用时在旧 Token 与 HTTP 前失败；
4. 固定成员/消息调用、动作能力拒绝、Tenant 与成员精确身份；
5. 403、404、429、500、503、合法 Retry-After、读写超时和取消；
6. 非法 JSON、缺失字段、超大响应、任意 Origin/SSRF/UserInfo/Path/Query/Fragment；
7. Credential Secret 全部关闭，Secret、Token、Endpoint、Body 和身份在公开 Evidence 中零泄漏；
8. 默认生产 Origin、显式 Loopback、缺失授权 Store 和所有配置上下界。
