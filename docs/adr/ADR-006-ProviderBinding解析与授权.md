# ADR-006：ProviderBinding 解析与授权固化

> 状态：ACCEPTED<br>
> 日期：2026-08-05<br>
> 更新：2026-08-09（M2-I01 固化只读解析、层级占位与失败关闭语义）<br>
> 影响里程碑：M2、M3、M5、M6

## 背景

CrewScope 支持用户级、团队级和组织级 Connection 与 ProviderBinding。同一种 Provider 能力可能存在多个实现、外部身份和资源范围。Agent 只能使用服务端解析出的精确 Binding，默认值选择不能扩大权限。

## 决策

### Binding Resolver

Resolver 根据动作所需执行身份和能力按顺序解析：

1. Action 显式 ProviderBinding；
2. Task 显式 ProviderBinding；
3. WorkProject ProviderBinding；
4. 当前执行身份对应的 Team 或 Personal Workspace ProviderBinding；
5. Organization 默认 ProviderBinding。

顺序只处理默认选择。用户级、团队级和组织级身份保持独立。相同优先级存在多个匹配项时返回歧义结果，由用户或上层策略选择。

Resolver 输入全部来自服务端可信事实：Organization、Team、Workspace、可选 WorkProject、Binding Owner、ProviderType、外部执行身份、期望能力/资源范围，以及可选 Action/Task 显式 Binding。`Binding Owner` 表示当前 Grant Grantee；外部执行身份独立表达 `DELEGATED_USER`、`TEAM_SERVICE_ACCOUNT`、`ORGANIZATION_SERVICE_ACCOUNT`，空值只匹配 connectionless Binding。两者必须同时精确匹配，不能从 Owner 类型推断并替换外部身份。

解析按以下规则执行：

- Action 显式 Binding 存在时只验证该 Binding，Task 和默认层级不参与；
- Task 显式 Binding 存在时只验证该 Binding，默认层级不参与；
- 自动解析先检查 WorkProject 层是否存在 ACTIVE 原始候选，再检查 Workspace 层；
- 最高存在层级形成占位。该层候选因版本、状态、Grant、Connection、能力或资源范围失效时返回不可用，不向较低层回退；
- 同一层存在一个 ACTIVE `defaultUsage=true` 时只验证该默认项；默认项失效时失败关闭；
- 同层没有默认项时，一个当前有效候选直接解析，多个当前有效候选返回 `AMBIGUOUS`；
- Organization 默认是 `Binding Owner=ORGANIZATION` 的 Workspace 层默认项，不允许 USER/TEAM 查询隐式切换 Owner；
- Resolver 返回 `RESOLVED`、`NOT_FOUND` 或 `AMBIGUOUS`，执行路径只接受 `RESOLVED`。

显式 Binding 必须继续匹配当前 Organization、Team、Workspace、可选 WorkProject、Owner、ProviderType 和外部执行身份。显式 ID 只收窄选择，任何错配都返回不可用，不回退也不泄露其他 Scope 的 Binding 事实。

### 有效权限

最终能力取以下范围交集：

```text
外部身份权限
∩ TeamMember / TeamRole
∩ SubjectAuthorization
∩ Object Visibility
∩ ProviderBinding
∩ ConnectionGrant
∩ Workspace
∩ PolicySnapshot
∩ SafetyEnforcementOverlay
∩ Task/Step 资源范围
∩ ExecutionLease / Task Token
```

显式 Binding 只能缩小选择范围。

M2 Resolver 在 `ProviderBinding.currentAccess` 重验固化依赖后，再把当前有效范围与本次期望能力和资源范围求交集。交集为空的候选不可用；解析结果只携带交集后的访问范围。具体 Action 执行必须以单个原子能力和目标资源请求解析，不能把部分交集解释为对未保留动作的授权。

### 授权固化

解析完成后固化：

- ProviderDefinition 与 ProviderImplementation 版本；
- ProviderBinding ID；
- Connection 与 ConnectionGrant ID；
- Credential Subject；
- Tool、动作类型和目标资源；
- Scope、资源范围和执行身份。

上述事实写入 PolicySnapshot。写操作同时写入 ActionDigest；任一事实变化后原 Confirmation 过期。

### MVP 范围

- NativeWorkItemProvider；
- GitHubSourceCodeProvider；
- LarkCollaborationProvider 出站成员查询和固定模板通知；
- Lark 入站 Channel 进入后续里程碑。

## 结果

- Agent 无法通过参数伪造 Principal、Role、Binding 和 Connection；
- Personal 与 Team 外部身份拥有清晰边界；
- Task 恢复使用固化快照并叠加实时撤权；
- Binding 歧义以可交互错误结束，执行路径采用失败关闭。

## 验证

1. Task 显式 Binding 不能访问超出 ConnectionGrant 的资源；
2. 同级多个 Binding 返回歧义，不自动选择；
3. Personal Agent 不能使用未委托的 Team Service Account；
4. Connection 撤销后 Task Token 换取和待执行 Action 同时失效；
5. Handoff 或责任版本变化后 ActionDigest 变化；
6. Agent 提交伪造 Binding ID 时服务端使用可信上下文拒绝请求。
7. 无效 WorkProject 或显式 Binding 不回退到 Workspace 默认项；
8. Binding Owner 与外部执行身份分别精确匹配，connectionless 查询不返回外部身份 Binding；
9. 唯一默认项优先于非默认项，默认项失效时失败关闭。

## 重新评估条件

- 引入跨 Team ProviderBinding；
- Provider 支持动态多身份委托；
- Plugin 市场允许第三方 Binding Resolver；
- 企业 IAM 提供统一外部身份代理。
