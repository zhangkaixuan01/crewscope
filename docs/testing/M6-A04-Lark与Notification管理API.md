# M6-A04 Lark 与 Notification 管理 API

## 目标

M6-A04 为 Team 管理员提供 Lark Connection、成员映射、固定模板偏好、通知投递历史和失败再次投递的完整管理入口。所有读取和命令都会重新验证当前 `PROVIDER_MANAGE` 权限；所有状态变更要求强 ETag、`Idempotency-Key` 并返回持久化 `CommandReceipt`。

## API

根路径为 `/api/v1/organizations/{organizationId}/teams/{teamId}/lark`：

- `POST/GET /connections`、`GET /connections/{connectionId}`；
- `POST /connections/{connectionId}/rotate|revoke`；
- `POST /bindings/{bindingId}/preflight`、`GET /bindings/{bindingId}/health`；
- `POST /member-verifications`、`GET/POST /member-mappings`、`POST /member-mappings/{mappingId}/revoke`；
- `GET /notification-templates`；
- `GET/PUT /notification-preferences/{memberId}`；
- `GET /notification-deliveries`、`GET /notification-deliveries/{deliveryId}`、`POST /notification-deliveries/{deliveryId}/redeliver`。

Connection 创建在一个事务内闭合 TEAM Credential、Connection、完整 Capability Grant 和默认 Team Workspace ProviderBinding。请求必须提供预期 `tenant_key`、`app_id` 和 `app_secret`。Credential Secret 由 `ObjectMapper` 编码为固定 JSON 后单向写入 CredentialStore；响应只包含脱敏 App ID，不返回 Secret、Credential ID、Grant ID、Tenant Key、Token 或 Endpoint。

成员验证只接受精确 `open_id`，公开 Proof 通过 Receipt 引用，映射 DTO 只返回内部 Member、Binding、状态和时间，不返回 Open ID、Union ID 或 Tenant Key。通知历史 DTO 不返回变量、授权快照、Digest、Provider Message ID、请求/响应 Body、Claim、Lease 或原始错误文本。

## 并发、回放与分页

- 新建 Connection 和确认 Mapping 使用 `If-Match: "0"`；
- 成员验证使用当前 ProviderBinding Version；
- Secret 轮换与 Connection 撤销使用公开 Connection Credential Version；
- Mapping 撤销、Preference 更新和失败再次投递使用对应资源 Version；
- 相同 `Idempotency-Key` 与相同语义返回原 Receipt，权限在回放读取之前重新验证；
- Mapping 和 Delivery 使用 `updated_at DESC, id DESC` Keyset；
- 两类 Cursor 使用独立 HMAC 签名域，绑定 Organization、Team 和规范化 Filter，并支持服务端 Key 轮换。
- 翻页先重新验证当前 `PROVIDER_MANAGE`，再解码 Scope/Filter-bound Cursor；
- Preflight、成员验证和通知再次投递的强 ETag 冲突统一映射为 `409 optimistic_lock_conflict`，并返回当前版本。

## 验证

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-server -am \
  -Dtest='*M6A04Test' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

专项验证覆盖两类 Cursor 的 Round Trip、篡改、跨 Team、筛选重放、保留旧 Key、删除旧 Key、未授权时不解码 Cursor、强 ETag 冲突和公开 DTO 禁止字段。`test-compile` 同时验证 Application、Infrastructure、Integration 与 Server 的完整装配。M6-I03 至 M6-I06 既有测试继续覆盖成员映射冲突、授权漂移、固定模板、DND、幂等投递、响应丢失恢复和失败再次投递内核。

结果：8 / 8 专项测试通过，其中 Application 2 项、Server 6 项。
