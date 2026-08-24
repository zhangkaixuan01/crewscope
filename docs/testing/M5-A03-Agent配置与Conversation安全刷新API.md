# M5-A03 Agent 配置与 Conversation 安全刷新 API

## 1. 交付范围

M5-A03 提供 Team-scoped Agent 配置与 Conversation 配置固定能力：

- 查询当前 `AgentConfigurationVersion`；
- 查询指定 `PERSONAL/TEAM` ExecutionScope 的可选模型交集；
- 追加主模型、Fallback、Team 默认继承和受控偏好配置；
- 对当前配置执行 Model Preflight；
- 查询 Conversation 固定 Revision 与当前 Revision；
- 在无活动调用和 Pending Interrupt 的安全点显式刷新 Conversation 配置。

配置请求只接受稳定 Connection ID、Catalog Entry ID/Revision、补充指令、批准 Skill、Memory/Budget Reference 和 `SafeModelGenerateOptions`。Provider/Adapter、Model 显示坐标、Endpoint、Credential、System Prompt 基线、Tool、Schema 和 PolicyPack 均由服务端补齐。

## 2. API

AgentProfile 路由前缀：

```text
/api/v1/organizations/{organizationId}/teams/{teamId}/agent-profiles/{profileId}
```

| 方法 | 路径 | 语义 |
|---|---|---|
| `GET` | `/configurations/current` | 当前不可变配置；ETag 为 Configuration Revision |
| `GET` | `/model-catalog?executionScope=PERSONAL|TEAM` | 当前 Principal、Agent、Connection、目录和策略交集 |
| `POST` | `/configurations` | 使用 `If-Match` 和 `Idempotency-Key` 追加下一 Revision |
| `POST` | `/model-preflight` | 解析当前直接或继承 Binding 的非秘密运行证据 |

Conversation 路由前缀：

```text
/api/v1/organizations/{organizationId}/teams/{teamId}/conversations
```

| 方法 | 路径 | 语义 |
|---|---|---|
| `GET` | `/{conversationId}/agent-configuration` | 返回 Session ETag、固定/当前 Revision 与是否需要刷新 |
| `POST` | `/{conversationId}/agent-configuration-refresh` | 使用 Session ETag 在安全点固定当前配置 |

无初始配置时，配置追加使用 `If-Match: "0"`。Conversation 刷新不接受目标 Revision，服务端始终固定当前已授权 Revision。

## 3. 权限与隔离

- USER-owned Agent 只允许所属成员配置；TEAM-owned Agent 需要当前 Team-wide `AGENT_MANAGE`；
- 默认 Personal Agent 和 USER-owned Specialist 的 PERSONAL Binding 可使用 Owner USER、TEAM 或 ORGANIZATION Connection；
- USER Connection 不进入 USER-owned Specialist 的 TEAM Binding，也不进入 Team Agent；
- Personal Assistant 的 TEAM Binding 固定为 `ORCHESTRATION_ONLY`，客户端不能提交该值；
- `INHERIT_TEAM_DEFAULT` 只用于 TEAM Binding，Preflight 返回实际 Default Source、Revision 与 Hash；
- 配置详情需要管理权限，响应不公开 Prompt 基线、Tool Payload、Endpoint、Credential、Adapter 或内部 Hash 坐标；
- Conversation 配置状态和刷新只对 Conversation Owner 开放，并复验 Conversation、Workspace、Member、Personal Agent、Session 和当前配置。

## 4. Revision、幂等与并发

`AgentConfigurationVersion` 从 Revision 1 连续追加并保存直接前一 Revision。配置命令在权限复验后查询 Completed Receipt；同请求、同幂等键可使用旧 ETag 回放，不同请求或并发旧 ETag 冲突。

`AgentRuntimeSession` 固定 Ownership、RuntimeRole、TemplateVersion 和可选 Configuration Revision/Hash。刷新只推进 Configuration Revision，保留 AgentScope Session Key 与 AgentState Reference。Session Repository 使用乐观版本防止两个刷新同时提交。

`PersonalAgentInvocationService` 为每个 Conversation 建立进程内配置边界：调用启动、Interrupt Resume 和配置刷新通过同一边界串行化。`INITIALIZING`、`ACTIVE` 和 `INTERRUPTED` 均拒绝刷新；终态允许刷新。刷新事务持有安全点边界，关闭“检查完成后新调用立即启动”的竞态。多节点部署继续依赖同一 Conversation 的运行路由亲和与 Session 乐观锁。

Personal Agent 运行缓存键包含 Profile Version 和 Configuration Pin。安全点刷新后不会复用旧 Configuration Revision 对应的 HarnessAgent 实例。

## 5. 原子证据链

配置追加和 Conversation 刷新均在同一事务写入：

```text
Aggregate/Session -> DomainEvent -> Outbox -> CommandReceipt
```

任一步失败时业务事实与 Receipt 一起回滚。Receipt 回放前仍执行当前 Owner、Membership、Agent 和 Team 授权复验。

## 6. 验证

专项覆盖：

- 连续 Configuration Revision 与前一 Revision；
- USER Owner 和跨 Owner 拒绝；
- TEAM 默认继承和完整 Preflight 调用；
- 强 Configuration/Session ETag 与命令头；
- DTO 不泄露 Endpoint、Credential、Adapter、Prompt 基线和 Tool Payload；
- 活动调用、初始化调用和 Pending Interrupt 拒绝刷新；
- 终态安全点刷新；
- Session Pin 前进且 AgentScope Key/State Reference 不变；
- Completed Receipt 回放、旧 Session ETag 冲突和原子证据链；
- Configuration Revision 刷新后 Agent 缓存隔离。

验证命令：

```bash
./mvnw -q -pl crewscope-server -am \
  -Dtest=AgentConfigurationApplicationServiceM5A03Test,ConversationConfigurationRefreshServiceM5A03Test,AgentConfigurationControllerM5A03Test,ConversationConfigurationControllerM5A03Test,PersonalAgentInvocationServiceTest,PersonalAgentFactoryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```
