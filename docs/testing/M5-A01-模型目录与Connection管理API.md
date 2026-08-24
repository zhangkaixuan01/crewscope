# M5-A01 模型目录与 Connection 管理 API

> 状态：已完成
> 日期：2026-08-24

## 1. 交付范围

M5-A01 在 M5-I01 至 M5-I04 的持久化、CredentialStore 和 Preflight 基线上交付第一组模型管理公开 API：

- Organization-scoped Model Provider 列表与精确 Provider Catalog Revision、当前有效价格查询；
- USER、TEAM、ORGANIZATION ModelConnection 创建、列表和详情；
- Connection 验证、Credential 轮换、停用和不可逆撤销；
- Spring Boot 显式组合根、统一错误信封、强 ETag、Idempotency-Key 和 Command Receipt。

Provider DTO 只返回产品 Provider Key、显示名、Region、数据政策和生命周期，不返回默认 Endpoint 或 AgentScope Adapter Key。Catalog DTO 返回精确 Model/Catalog Revision、能力、Token 上限、Region、生命周期和当前有效价格。

Connection DTO 只返回稳定 Connection ID、Provider、Owner、Region、账单主体、Credential Version、健康安全摘要、生命周期、审计时间和乐观版本。Endpoint、Credential ID、Credential Key、Credential Metadata、Provider 原始错误和凭证明文不进入公开响应。

## 2. Owner 与授权

公开创建请求只接受 `providerKey`、`ownerType`、可选 `teamId`、`region`、API Key 和可选凭证到期时间。以下事实由服务端固化：

| Owner | 创建与变更权限 | Credential Subject | Billing Subject |
|---|---|---|---|
| `USER` | 当前活动 USER Principal，只能管理自己的 Connection | 当前 Principal | 当前 Principal |
| `TEAM` | 当前活动 TeamMember 且具有有效 Team-wide `PROVIDER_MANAGE`；平台管理员可代管 | 目标 Team | 目标 Team |
| `ORGANIZATION` | 平台管理员 | 当前 Organization | 当前 Organization |

TEAM Connection 的安全列表可由当前活动 TeamMember 查询；USER Connection 只对 Owner 可见；ORGANIZATION Connection 管理保持平台管理员边界。每次首次执行和 Receipt 回放都重新校验当前 Owner 权限，旧 Idempotency Key 不能绕过角色撤销。

## 3. API

```text
GET  /api/v1/organizations/{organizationId}/model-providers
GET  /api/v1/organizations/{organizationId}/model-providers/{providerKey}/catalog

POST /api/v1/organizations/{organizationId}/model-connections
GET  /api/v1/organizations/{organizationId}/model-connections?ownerType={type}&teamId={teamId}
GET  /api/v1/organizations/{organizationId}/model-connections/{connectionId}
POST /api/v1/organizations/{organizationId}/model-connections/{connectionId}/verify
POST /api/v1/organizations/{organizationId}/model-connections/{connectionId}/rotate
POST /api/v1/organizations/{organizationId}/model-connections/{connectionId}/suspend
POST /api/v1/organizations/{organizationId}/model-connections/{connectionId}/revoke
```

全部读取响应使用 `Cache-Control: no-store`，详情返回强 ETag。全部写命令要求 `Idempotency-Key`；既有 Connection 命令同时要求强 `If-Match` 和当前 `credentialVersion`。创建和轮换只把 API Key 转换为可清零 `CredentialSecret`，响应、Receipt、DomainEvent、Outbox 和错误信封均不返回 Key。

## 4. 幂等与事务边界

`ModelConnectionLifecycleCommandGate` 将 Receipt 预留、Connection/CredentialStore 变更、DomainEvent、Outbox 和 Receipt 完成放在同一本地提交事务中。Receipt 精确引用本次 Connection DomainEvent ID 和提交版本。

验证命令保持 M5-I02 边界：Credential Handle 准备与 Provider Probe 不占用数据库事务，只有脱敏健康结果进入最终事务。同键回放先执行只读 Completed Receipt 查询，因此使用原旧 ETag 的成功命令可以直接回放，不执行 Provider 调用，也不被当前新版本提前拒绝。首次并发请求仍在最终事务通过唯一 Receipt 预留串行化，最多重复只读 Probe，不重复提交健康事实。

Idempotency Request Hash 不保存 API Key；创建和轮换只写入 Key 的 SHA-256 指纹，使同键不同 Secret 按冲突处理。短期明文字节副本在摘要后立即清零。

## 5. 验证

专项覆盖：

- USER Owner 的 Endpoint、Credential Subject 和 Billing Subject 服务端固化；
- TEAM 缺少 `PROVIDER_MANAGE` 拒绝，ORGANIZATION 非平台管理员拒绝；
- USER Connection 跨 Owner 读取拒绝和精确 Owner 列表；
- Provider/Connection DTO 不披露 Endpoint、Adapter、Credential ID、Metadata 或 API Key；
- 创建、轮换 Receipt，详情 ETag，缺失并发头和安全错误信封；
- Verify Completed Receipt 在旧版本校验和 Provider Probe 前回放；
- Spring 组合根、V1 至 V26 迁移和 DomainEvent 事务关联回归。

验证命令：

```bash
./mvnw -q -pl crewscope-server -am \
  -Dtest=ModelConnectionApplicationServiceM5A01Test,ModelConnectionCredentialServiceTest,ModelManagementControllerM5A01Test \
  -Dsurefire.failIfNoSpecifiedTests=false test

./mvnw -q -pl crewscope-infrastructure,crewscope-server -am \
  -Dtest=DomainEventTransactionIntegrationTest,ApplicationCompositionConfigurationTest,ModelCredentialApplicationConfigurationM5I02Test,ModelConnectionApplicationServiceM5A01Test,ModelConnectionCredentialServiceTest,ModelManagementControllerM5A01Test \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

两组专项与关联回归均通过；PostgreSQL Testcontainers 从空库成功执行 V1 至 V26。
