# M6-I06 固定模板 Lark 投递与 Receipt 恢复

> 日期：2026-08-26<br>
> 范围：`crewscope-application`、`crewscope-integration`、`crewscope-infrastructure`、`crewscope-server`<br>
> 结论：通过

## 1. 交付内容

- `FixedNotificationTemplateRenderer` 只接受当前已发布的精确 Template ID/Version、M6 五个固定 Server Template Key 和封闭变量词汇。调用方不能传入正文、格式串、Provider Payload、任意 URL、Method 或 Recipient。
- 渲染前重新读取 `NotificationTemplateCatalog`，核对返回版本、变量 Schema 和变量 Hash；字段按产品固定顺序输出，文本与 Lark Operation 共同限制为 4,000 字符，并拒绝控制字符与 Unicode Format 字符。
- `NotificationProviderRequest` 精确携带 Action ID/Digest、Template/Version、Variables/Hash、Deduplication Key、Mapping、Connection 和完整 Authorization Snapshot。Worker Preflight 解析出的当前变量直接进入 Provider，不再从非权威来源重建正文。
- Provider UUID 由 Organization、Connection、Action ID、Action Digest 和 Notification Deduplication Key 确定性派生，并转换为 Lark 要求的 32 位小写十六进制值。同一逻辑投递的写入、超时、Lease 接管和查询恢复始终使用同一 UUID。
- `LarkNotificationCredentialIssuer` 只为已提交的精确 Claim 签发短期 Handle，TTL 不超过 Claim Lease。Handle 绑定 Organization/Team/Binding/Connection/Grant 和 Worker Principal；每次 Token 获取、消息写入及消息查询前都重新验证 Connection、Grant、Capability 与 Credential。
- `LarkNotificationProviderAdapter` 在 HTTP 写入前重新验证 ACTIVE TeamMember、ACTIVE Mapping 版本、VERIFIED ExternalTenant、Binding/Connection/Grant 版本、当前发布模板和变量 Hash。任一事实漂移都在外部写入前失败关闭。
- 发送成功后按返回的精确 Message ID 查询存在性。Lark 没有按 UUID 查询消息的接口；响应丢失恢复会使用完全相同的 Recipient、正文和 UUID 再次提交固定请求，由 Lark UUID 幂等语义返回原 Message ID。这是原操作的幂等恢复，不是新的业务投递。
- `LarkMessageReceiptProjection` 只合并 UUID 与 Message ID 同时一致的观察；外部观察时间单调取新，迟到观察不能覆盖新事实，身份冲突归一化为无敏感内容的失败。
- Provider 结果只返回 UUID、Message ID 和稳定 Evidence Code。M6-I03 在领域层将外部坐标 Hash 后写入每个 Delivery 唯一的确定性 Receipt。
- Spring 使用构造器注入并按依赖条件装配 Renderer、Credential Issuer 和 `NotificationProviderPort`。固定模板 Catalog、授权解析、Lark Client、Mapping、Tenant 或 Member Repository 缺失时，不装配不完整的通知 Provider。

M6-I06 不提供飞书入站消息、自由文本发送、成员已读状态或开放式 Provider HTTP 能力。

## 2. 投递与恢复边界

```text
committed Notification Claim
  -> resolve current active-generation Intent
  -> compare immutable Authorization Snapshot
  -> issue claim-bound Lark credential capability
  -> validate current Member / Mapping / Tenant / Template
  -> render product-owned fixed text
  -> POST exact recipient + nested JSON content + stable UUID
  -> query exact returned message_id
  -> ACCEPTED + one logical Receipt

possibly accepted response loss
  -> Delivery UNKNOWN
  -> query-only Worker claim
  -> repeat the exact fixed request with the same UUID
  -> Lark returns the original message_id
  -> query exact message_id
  -> FOUND + the same logical Receipt identity
```

查询恢复路径不会生成新的 UUID，也不会接受新的正文、变量、Recipient 或授权坐标。明确的管理员再次投递继续由 M6-I03 的新 Redelivery Plan 和新 Deduplication Key 表达。

## 3. 验证

专项命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-application,crewscope-integration,crewscope-infrastructure,crewscope-server -am \
  -Dtest=FixedNotificationTemplateRendererM6I06Test,\
LarkNotificationProviderM6I06IntegrationTest,\
NotificationIntentProjectorM6E04IntegrationTest,\
LarkConnectorApplicationConfigurationM6I04Test \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

I03 至 I06 联合回归命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-application,crewscope-integration,crewscope-infrastructure,crewscope-server -am \
  -Dtest=NotificationWorkerM6I03Test,\
JdbcNotificationPlanRepositoryM6I01IntegrationTest,\
NotificationWorkerApplicationConfigurationM6I03Test,\
LarkConnectorM6I04IntegrationTest,\
LarkCollaborationM6I05Test,\
JdbcLarkCollaborationRepositoryM6I05IntegrationTest,\
FixedNotificationTemplateRendererM6I06Test,\
LarkNotificationProviderM6I06IntegrationTest,\
NotificationIntentProjectorM6E04IntegrationTest,\
LarkConnectorApplicationConfigurationM6I04Test \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：M6-I06 专项链 20 个测试通过；I03 至 I06 联合回归 47 个测试通过，均为 0 Failure、0 Error、0 Skip。联合回归包含真实 PostgreSQL V1→V30 迁移、通知计划持久化、Lark Mapping 持久化和 Loopback HTTP Provider。

全量门禁命令：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

全量门禁通过，共执行 2,227 个测试：Domain 569、Application 509、AgentScope Adapter 149、Integration 18、Infrastructure 646、Server 336，均为 0 Failure、0 Error、0 Skip。

覆盖：

1. 精确 Template ID/Version、变量 Schema/Hash、固定字段顺序和 4,000 字符上限；
2. 未知模板、任意 `body` 变量、Catalog 版本替换和退休模板全部拒绝；
3. 引号、反斜线、换行和链接在 Lark 外层 Payload 与内层 `content` JSON 中双层编码后语义不变；
4. 重复请求使用同一 UUID 和 Message ID，Loopback Provider 只创建一条消息；
5. Provider 已接受但响应丢失后，同 UUID 恢复原 Message ID；
6. Mapping、Template、Connection 或 Grant 漂移时 HTTP 消息写入为零；
7. Receipt 外部观察时间乱序单调合并，UUID/Message ID 身份冲突失败关闭；
8. 当前 `member-inbox` Generation Intent、发布模板和授权事实由生产 Projector 精确解析；
9. Spring 完整依赖装配与依赖缺失时的失败关闭。
