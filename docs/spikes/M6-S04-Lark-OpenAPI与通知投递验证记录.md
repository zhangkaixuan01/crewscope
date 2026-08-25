# M6-S04 Lark OpenAPI 与通知投递验证记录

> 任务：`M6-S04`<br>
> 日期：2026-08-25<br>
> 结论：通过<br>
> 长期决策：[ADR-022](../adr/ADR-022-Inbox与固定模板通知授权协议.md)（ACCEPTED）

## 1. 验证目标

M6-S04 在 M6-S03 通知领域协议、ADR-004 CredentialStore 和 ADR-006 ProviderBinding 当前授权之上验证：

1. Lark Tenant Access Token 的短期凭证使用、缓存隔离、到期余量和 401 刷新；
2. 外部 Tenant 与 Member 使用 `tenant_key + open_id` 精确验证；
3. 显示名、姓名、昵称和模糊邮箱不能形成成员 Mapping；
4. 固定模板服务端渲染、变量白名单、可信链接和 JSON 转义；
5. Provider UUID 去重、Timeout 响应丢失重试、Message ID 查询和安全 Receipt；
6. 401/403/404/429/5xx、撤权、超时、限流与重试上限；
7. Key、Token、Endpoint、Authorization、原始 Body 和成员 PII 零泄漏。

本 Spike 使用 JDK Loopback HTTP Server 和 `HttpClient`，不读取真实飞书凭证、不访问公网。所有 Connector、Cache、Mapping、Template 和 Stub 类型都位于测试文件内，不提前实现 M6-D03/D04 生产对象或 M6-I04/I06 Adapter。

## 2. 现有实现与差距

现有 `LarkCollaborationProvider` 只提供 `COLLABORATION / lark-collaboration / 1.0.0` Provider 描述符。CrewScope 已具备：

- CredentialStore 的加密保存、短生命周期明文 Handle 和撤权；
- Connection、ConnectionGrant、ProviderBinding 与当前版本复验；
- PlannedAction、Dispatch、Lease/Fencing、UNKNOWN/Reconcile 和唯一 Receipt；
- GitHub Connector 的固定 Endpoint、Loopback、安全错误和响应丢失对账经验。

M6 需要补齐 Lark 的 Tenant 身份、Token Cache、成员 Mapping、固定模板消息、Provider UUID 和具体恢复协议。本 Spike 验证这些边界，正式代码由 M6-D03/D04 和 M6-I03–I06 实现。

## 3. 固定 OpenAPI 面

MVP 只调用：

```text
POST /open-apis/auth/v3/tenant_access_token/internal
GET  /open-apis/tenant/v2/tenant/query
GET  /open-apis/contact/v3/users/{open_id}
     ?user_id_type=open_id&department_id_type=open_department_id
POST /open-apis/im/v1/messages?receive_id_type=open_id
GET  /open-apis/im/v1/messages/{message_id}
```

生产 Base URI 精确固定为 `https://open.feishu.cn`。Loopback HTTP 只在测试构造器显式开启，且只接受字面量 `127.0.0.1`/`::1`。UserInfo、非根路径、Query、Fragment、任意 Host、云元数据地址和未启用的 Loopback 全部在创建 HTTP Client 前拒绝。

Connector 不提供通用 Method/URL/Body 透传能力，Connection 和浏览器不能改变 Endpoint。

## 4. Tenant Token 与凭证

测试 Credential Resolver 模拟 ADR-004 动作级 Handle。每次 Token Exchange：

```text
resolve exact credential handle
  -> POST app_id/app_secret
  -> validate code/token/expire
  -> clear copied secret buffer
  -> close and clear handle
```

Token Cache Key 为：

```text
organization
connection id/version
connection grant id/version
credential id/version
expected tenant_key
```

Token 使用 Provider `expire=7200`，到期前 60 秒停止复用。Tenant A 连续查询两次只换取一次 Token，Tenant B 使用独立 Token。Tenant A 收到 401 后只删除 Tenant A 的精确 Cache Entry，再解析一次凭证并刷新；Tenant B 保持一次 Token Exchange。最终 3 个凭证 Handle 全部关闭。

每次业务请求先检查 Connection/Grant 当前状态。撤销后的请求在读取旧 Token Cache 和发起 HTTP 前返回 `CONNECTION_UNAVAILABLE`。

## 5. Tenant 与成员精确映射

Connection 先调用 Tenant Query，要求返回 `tenant_key` 与服务端配置完全相等。成员验证只接受 `ExternalLookupType.OPEN_ID` 和符合固定形状的 Open ID，再调用精确 User Endpoint。验证 Proof 保存 Organization、Team、Connection/Grant ID 与 Version、Tenant Key、Open ID、Union ID 和 Provider Version。管理员确认必须提交生成 Proof 的同一条当前 Connection/Grant；同 Tenant 的另一条 Connection、跨 Team 和撤权后的旧 Proof 都失败关闭。

Mapping Registry 只接受 Team Admin 确认。测试验证：

- 一个 Organization/Team Member 对应一个当前外部身份；
- 一个 Organization 内的 `tenant_key + open_id` 只对应一个内部 Member；
- 不同 Organization 的 Mapping 注册表相互隔离；
- Proof 不能跨 Connection/Grant 或 Team 复用；
- 非管理员不能确认；
- Display Name 和 Email 在 HTTP 请求前拒绝；
- Tenant 错配返回 `IDENTITY_MISMATCH`；
- 身份冲突不尝试选择相近用户。

显示名可以在管理员确认页面作为辅助展示字段，不能作为绑定键或查询回退条件。

## 6. 固定模板消息

测试 Registry 只注册 `review-required@3`，变量固定为：

```text
workItemTitle <= 200
reviewUrl <= 500 and origin=https://crewscope.invalid:443
```

服务端把变量渲染为固定文本，再通过 JSON Codec 生成 Lark `content` 字符串。标题包含引号时，Loopback Server 解码后得到完整文本，证明两层 JSON 转义保持结构有效。

Template Version 漂移、额外 `arbitraryBody`、非可信 Host 和非默认端口 URL 在发送 HTTP 前拒绝。Agent 输出、DomainEvent 原始正文和 Provider Body 不进入模板输入。

## 7. UUID、响应丢失与 Receipt

Lark `uuid` 由 Organization、Connection 和 PlannedAction Digest 规范派生为固定 32 位小写 SHA-256 前缀，不包含 Member、标题或 URL。一个 PlannedAction 在同一 Connection 的所有 Dispatch、Lease 接管和网络重试使用同一 UUID；不同 Organization、Tenant 或 Connection 形成不同 UUID。

Loopback 第一次发送先保存消息，再延迟响应超过 150ms Client Timeout。第二次请求使用相同 UUID，Stub 返回同一 Message ID。随后 Client 使用该 Message ID 查询精确消息存在性。再次执行相同命令仍返回同一 Message ID。

结果为：

```text
Provider message writes = 1
Observed UUID distinct count = 1
Receipt status = ACCEPTED
Receipt evidence = LARK_MESSAGE_EXISTS
```

`ACCEPTED` 表达 Provider 已接受且查询到消息，不表达成员已读。Lark Message Query 不承担 CrewScope UUID 回传；Receipt 从本地 PlannedAction/Dispatch 保存 UUID，并用 Provider Message ID 查询外部证据。

响应丢失后的相同 UUID 自动重试必须位于 Provider 去重保留期内。全部尝试没有取得 Message ID 时进入 `UNKNOWN/RECONCILING`。超过去重窗口后禁止生成新 UUID 自动重发，后续由失败 Inbox 和人工再次投递命令处理。

## 8. 限流、故障与安全错误

样本按顺序注入 `429 -> 503 -> 200`：

- 429 使用 `Retry-After: 7`；
- 503 使用 2 秒有界指数退避；
- 三次请求使用同一个 UUID；
- 最终只产生一个 Provider 消息。

连续三个 429 在第三次结束为 `RATE_LIMITED`，Provider 写入为 0。HTTP 分类固定为：

```text
401 -> AUTHENTICATION_REQUIRED
403 -> PERMISSION_DENIED
404 -> RESOURCE_UNAVAILABLE
429 -> RATE_LIMITED
5xx -> PROVIDER_UNAVAILABLE
Timeout/connection loss -> UNKNOWN_DELIVERY
Interrupted -> CANCELLED
invalid JSON/shape -> INVALID_RESPONSE
```

错误归一化函数接收包含测试 Secret、Token、内部 Host 和原始 JSON 的 Body，只返回稳定分类与安全消息。公开结果、异常和 Client Summary 不包含 Secret、Token、Endpoint 或原始 Body。生产日志只记录受控 Provider Key、Operation、Outcome、Attempt 和 Correlation；Tenant、Member、Message ID 不进入 Prometheus 标签。

## 9. 自动化验证

测试文件：

```text
crewscope-integration/src/test/java/io/crewscope/integration/provider/collaboration/
  LarkOpenApiM6S04IntegrationTest.java
```

专项命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-integration -am \
  -Dtest=LarkOpenApiM6S04IntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：

```text
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

M6-S03/S04 与 M5 Action 授权联合回归命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-integration -am \
  -Dtest=InboxNotificationM6S03Test,LarkOpenApiM6S04IntegrationTest,ActionBundleTest,ActionDeliveryTest,ActionWorkerM5I11Test,ActionReconciliationWorkerM5I12Test \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

```text
Tests run: 49, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

根 README 与 `docs` 共 250 份 Markdown 文档链接通过。

## 10. 后续实现边界

- M6-D03 实现 Notification Template/Authorization/Delivery/Receipt 与授权联合类型；
- M6-D04 实现 LarkExternalTenant、LarkMemberMapping、Collaboration Recipient 和 Port；
- M6-D08/D09 落地通知、Tenant 和 Mapping 约束；
- M6-I03 实现 Notification Worker、Lease/Fencing 和再次投递；
- M6-I04 实现固定 Endpoint HTTP Client、Token Cache、错误归一化和脱敏；
- M6-I05 实现精确成员查询、管理员 Mapping、Preflight 和健康检查；
- M6-I06 实现固定模板、UUID 投递、响应丢失恢复和 Receipt 查询；
- M6-A04/F05 交付 Connection、Mapping、Preference、投递历史与失败重投入口。

## 11. 结论

M6-S04 验证通过。Lark Tenant Token 可以按完整授权坐标安全缓存和刷新，成员身份可以通过 Tenant/Open ID 精确确认，固定模板消息可以在 Timeout、重复请求、429 和 5xx 下使用相同 UUID 收敛为一个 Provider 消息。Message ID 查询形成安全 `ACCEPTED` Receipt，撤权、身份错配、模板漂移和敏感错误全部失败关闭。ADR-022 已满足接受条件并转为 `ACCEPTED`。
