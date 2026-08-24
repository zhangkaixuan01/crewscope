# M5-A04 Task 委托与 Retry 配置切换 API

## 交付范围

M5-A04 将 M3 Task 委托接入 M5 Agent 配置解析。成员创建 Task 时选择 AgentProfile，并可选择精确 AgentConfiguration Revision；客户端不提交 ExecutionScope。服务端根据 Agent Ownership、当前 Team Membership 与 WorkItem 的 OWNER/EXECUTOR 责任链推导 PERSONAL 或 TEAM，完成 Model Preflight 后创建 PolicySnapshot Schema v2。

Task 创建接口增加可选 `agentConfigurationRevision`。同一路由提供 `POST /preflight` 只读预检，返回 Agent/Profile、ExecutionScope、Configuration、Binding Source、Template、主/Fallback 模型、价格 Revision、PolicyPack 与 Resolution Hash 的非敏感坐标。响应不返回 Endpoint、Credential、Prompt、Tool 正文和 Billing Subject。

Retry 默认复用父 attempt 固定的 `ResolvedAgentExecutionConfiguration`，同时复验 Agent、责任、成员与 Connection 的当前可用性，不重新继承已变化的 Team 默认。请求体显式提供 `agentConfigurationRevision` 时重新执行 Preflight，并为后继 attempt 固定新的配置。Schema v1 历史 attempt 在未显式切换时保持兼容。

## 服务端规则

- TEAM/ORGANIZATION-owned Agent 固定使用 TEAM ExecutionScope。
- USER-owned Agent 仅在 Owner 是当前调用成员，且 OWNER/EXECUTOR 责任链只包含该成员及其 Agent 时使用 PERSONAL；其他授权协作场景使用 TEAM。
- USER-owned Agent 的 Owner 离队、Agent/Profile/Principal 停用、Executor 责任变化时拒绝新委托与 Retry。
- PERSONAL 可使用 Owner USER、TEAM、ORGANIZATION ModelConnection；TEAM 只使用 TEAM、ORGANIZATION ModelConnection。
- 默认配置缺失或歧义按 Model Preflight 失败关闭。
- Task 创建与 Retry 的命令哈希包含配置选择；相同幂等键不能切换 Revision。
- 委托与 Retry 事件记录 PolicySnapshot、ExecutionScope、Configuration Revision/Hash 和 Binding Source，不记录 Secret。

## API 示例

创建或预检选择：

```json
{
  "executorAgentProfileId": "11111111-1111-4111-8111-111111111111",
  "agentConfigurationRevision": 3
}
```

默认沿用固定配置的 Retry 不发送请求体。显式换配置：

```json
{
  "agentConfigurationRevision": 4
}
```

## 验证

- `TaskAgentSelectionServiceM5A04Test` 覆盖 PERSONAL 判定、TEAM-owned 强制 TEAM 和 Owner 离队拒绝。
- `TaskControllerTest` 覆盖 Task 创建精确 Configuration Revision 映射。
- `TaskCommandControllerM3A04Test` 覆盖 Retry 无请求体沿用与显式 Revision 切换，并保持 Resume 拒绝请求体。
- 既有 M3 Task 创建与控制测试继续验证 Schema v1 历史组合的兼容路径。
- `DomainEventEnvelopeJsonCodecTest` 使用真实 V1 Envelope 证明旧 `TASK_DELEGATED_TO_AGENT` 与 `MEMBER_TASK_RETRY_ACCEPTED` Payload 缺少 M5 新增 Optional 字段时仍解码为 `Optional.empty()`，保持历史事件回放可读。
