# M8 Setup Readiness API 契约

> 状态：冻结<br>
> 版本：V1<br>
> 日期：2026-09-01<br>
> 适用范围：当前 Organization 下已加入 Team 的成员

## 1. 查询

```http
GET /api/v1/organizations/{organizationId}/teams/{teamId}/setup-readiness
```

可选查询参数 `environment` 选择 Runtime 环境；缺省使用服务端 `crewscope.runtime.observation.environment`。请求沿用 Session、CSRF、`X-Correlation-Id` 和错误信封合同，响应使用 `Cache-Control: no-store`。

服务端先复验 Organization、Team 和当前账号的 ACTIVE Membership，再从现有模型、Agent、WorkProject、Repository、Provider Connection 和 Runtime 事实生成快照。该查询不创建或更新 Readiness 表。

## 2. 响应

```json
{
  "organizationId": "00000000-0000-4000-8000-000000000001",
  "teamId": "00000000-0000-4000-8000-000000000002",
  "snapshotVersion": "2819407314",
  "observedAt": "2026-09-01T15:00:00Z",
  "requiredReady": false,
  "capabilities": [
    {
      "capability": "PERSONAL_CONVERSATION",
      "required": true,
      "status": "ACTION_REQUIRED",
      "reasonCode": "PERSONAL_AGENT_CONFIGURATION_REQUIRED",
      "canConfigure": true,
      "responsibleParty": "当前成员",
      "actionKey": "OPEN_AGENT_SETTINGS"
    }
  ]
}
```

`capabilities` 固定包含以下六项，服务端按该顺序返回：

```text
PERSONAL_CONVERSATION
TEAM_TASK
CODING_REVIEW
GITHUB_DRAFT_PR
LARK_NOTIFICATIONS
TEAM_OBSERVER
```

### 2.1 状态

`status` 是闭合集合：

| 状态 | 含义 |
|---|---|
| `READY` | 当前能力的必要事实齐全且 Runtime 可用 |
| `ACTION_REQUIRED` | 需要当前成员或责任人完成配置 |
| `BLOCKED` | 存在权限或策略边界，当前成员不能继续配置 |
| `UNAVAILABLE` | 依赖服务或运行时暂时不可用 |

`reasonCode` 使用稳定大写枚举；Provider 原始错误、Secret、Endpoint、Remote URL、宿主路径、内部数据库坐标和异常原文不出现在响应中。`actionKey` 只有在 `ACTION_REQUIRED` 且当前成员拥有对应配置权限时出现，值只能是服务端预注册的站内动作。

## 3. 能力前置事实

| 能力 | 必需事实 | 可选集成 |
|---|---|---|
| Personal Conversation | 当前账号、ACTIVE Membership、默认 Personal Agent、可用模型配置 | GitHub、飞书 |
| Team Task | Team Coordinator Agent、可用 Team 模型、目标 Runtime | 外部 Provider |
| Coding/Review | ACTIVE WorkProject、ACTIVE 受管 RepositoryBinding、Coding/Reviewer Agent、Coding Runtime | GitHub Draft PR |
| GitHub Draft PR | ACTIVE TEAM GitHub Connection 和后续导入的 Managed Repository | 飞书通知 |
| Lark Notifications | ACTIVE TEAM Lark Connection | GitHub |
| Team Observer | Team Observer Agent 配置和 Runtime | Lark/GitHub |

GitHub 与飞书属于可选集成，缺失时不影响 Personal Conversation 和站内 WorkItem。Setup Center 应根据 `actionKey` 进入现有配置页；服务端仍对后续写命令执行独立授权和幂等校验。

## 4. 缓存、版本与错误

响应不允许共享缓存。`snapshotVersion` 由 Team、Agent、WorkProject、Runtime 和能力状态的当前版本事实派生；相同事实集合得到相同版本，不使用请求时间制造伪变化。事实并发变化时，客户端丢弃旧快照并重新查询。

跨 Organization、跨 Team、无 Membership 或格式非法的范围沿用现有稳定错误码。Runtime、Provider 或 Repository 查询失败时返回可重试的 `UNAVAILABLE` 能力项，不把底层异常传播到浏览器。

## 5. 安全与测试要求

契约测试必须覆盖新 Team、已配置 Team、无权限成员、Provider 故障和部分配置五类 Fixture，并验证：

- 六项能力始终存在且状态属于闭合集合；
- `requiredReady` 等于所有必需能力是否为 `READY`；
- 无权限成员没有写动作 `actionKey`；
- 公开字段白名单不包含 Secret、Endpoint、原始错误和任意 URL；
- 跨 Team/Organization 查询失败关闭；
- Runtime 暂时不可用只影响相关能力，不阻断基础对话状态计算。
