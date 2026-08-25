# M5-F05 Task 委托与模型预检

## 交付范围

M5-F05 将 M5-A04 的 Task Agent 选择与只读 Preflight 接入 Conversation/WorkItem 共用委托流程。成员从当前 WorkItem 责任链选择 Personal Agent 或 Team Agent，并选择当前或历史 AgentConfiguration Revision。服务端推导 PERSONAL/TEAM ExecutionScope，解析真实模型图并返回非敏感 PolicySnapshot 坐标；创建 Task 时固定 Preflight 返回的精确 Revision。

本任务同时扩展成员 Task Retry：留空继续复用父 attempt 已固定的配置，填写 Revision 时提交 `agentConfigurationRevision`，由服务端重新 Preflight 并为新 attempt 固定新配置。

## 产品与事实边界

- Agent 候选来自当前 `ResponsibilityAssignment` 中具有 `actorAgentProfileId` 的 `PERSONAL_AGENT/TEAM_AGENT` Executor。
- Agent Directory 只补充 Ownership、RuntimeRole、生命周期、Template 和当前 Revision，不授予责任或执行权限。
- 前端不提交 ExecutionScope。PERSONAL/TEAM 由服务端根据 Ownership、成员资格和 OWNER/EXECUTOR 责任链推导。
- “当前配置”在 Preflight 后转换为响应中的精确 Revision 再创建，避免配置在两次请求之间漂移。
- Preflight 展示 Configuration、Binding Source、Template、Primary/Fallback Provider/Model、Connection Owner Type、Catalog/Price Revision、PolicyPack 与 Resolution Hash。
- A04 DTO 不公开 Billing Subject。页面明确显示“服务端已固定；当前 Preflight API 不披露”，不把 Connection Owner 或 Agent Ownership 解释为成本主体。
- TEAM Scope 明确提示只允许 TEAM/ORGANIZATION Connection，USER Key 在服务端失败关闭。

## 浏览器数据层

`TaskGateway.preflightDelegation` 调用：

```text
POST /organizations/{organizationId}/teams/{teamId}
     /work-projects/{projectId}/work-items/{workItemId}/tasks/preflight
```

请求只包含：

```json
{
  "executorAgentProfileId": "agent-profile-id",
  "agentConfigurationRevision": 4
}
```

Gateway 使用显式字段白名单，排除 Endpoint、Credential、Prompt、Tool Payload、Billing Subject 和服务端内部策略载荷。Task Store 按 `WorkProject + WorkItem + AgentProfile + Revision` 隔离结果，并使用固定 WorkItem 请求槽取消旧输入请求；Team Scope 变化清空全部结果。

委托草稿按 `Organization + Team + WorkProject + WorkItem` 分区存入 SessionStorage，只保存目标、验收标准、AgentProfile ID 与公开 Revision。CodingTarget 使用原有独立分区草稿。创建成功清除两份草稿；可重试失败冻结表单并使用 Task Store 保存的完整原命令与原 Idempotency-Key。

## 交互状态

- Loading：显示正在解析 ExecutionScope、Binding、模型与 PolicySnapshot。
- Ready：显示 PERSONAL/TEAM、模型来源、Fallback、价格 Revision 与 PolicySnapshot 摘要。
- Error：按服务端稳定 `reason` 映射安全修复提示；无 Binding、默认缺失/歧义、Owner 离队、责任变化、Agent/Principal 不可用失败关闭。
- Retryable Create：Agent、Revision、CodingTarget、目标和验收标准全部冻结，只提供“使用原请求重试”。
- Retry Attempt：留空沿用父配置；填写大于等于 1 的整数 Revision 显式切换。
- Conversation 与 WorkItem：共用 `DelegateToAgentDialog`、Task Store、Coding Store 和创建命令，只通过 `conversationSource` 保留来源。

Modal 使用允许 ARIA dialog 的容器语义，初始焦点位于 Dialog 容器，避免窄屏自动滚离 Agent 与 Preflight 上下文；Tab 保持在顶层 Modal 中，Escape 只关闭当前层。CodingTarget 标题不创建重复 Banner Landmark。

## 验证

- `HttpTaskGateway` 测试覆盖 Preflight 路由、精确 Revision 请求、响应白名单和显式 Retry Revision Body。
- `TaskStore` 测试覆盖 WorkItem/Agent/Revision 缓存分区与 Team Scope 清理。
- `DelegateToAgentDialog` 测试覆盖责任链 Agent、PERSONAL/TEAM、精确 Revision 固定、Conversation 来源、无 Agent 失败关闭、原请求重试和 Focus Trap。
- `TaskControlPanel` 测试覆盖 Retry 沿用说明、Revision 整数校验和显式切换命令。
- Playwright 覆盖 WorkItem 与 Conversation 双入口、创建同键重试、Retry 换 Revision、双视口、Axe WCAG 2.2 AA 和视觉回归。
- 前端全量：64 个测试文件、286 项 Vitest；138 项桌面/窄屏 Playwright；生产构建通过。
- 视觉基线：`task-delegation-preflight-desktop-chromium.png`、`task-delegation-preflight-narrow-chromium.png`。
