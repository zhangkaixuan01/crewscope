# M5-F03 Agent 创建与详情设置

## 交付结果

M5-F03 在 `/settings/agents` 交付 Agent 创建向导和详情配置面板，覆盖 USER-owned Specialist 与 TEAM-owned Agent。普通成员可以管理自己拥有的 USER Agent；TEAM Agent 的创建、配置和生命周期操作要求 `agent:manage`。服务端继续执行 Organization、Team Membership、Ownership、Role、Template 和模型治理校验。

创建向导只接受服务端批准的 AgentTemplate、Ownership 和显示名称。创建命令回执不包含 AgentProfile ID，页面会记录创建前的可见 ID 集并刷新目录；只有刷新结果恰好出现一个新 ID 时才自动打开详情。并发创建产生多个新 ID 时页面保留权威列表，不猜测创建结果。

## 配置契约

详情面板展示 Profile 状态、Template 坐标、Profile Version、当前 Configuration 和不可变历史。配置编辑遵循以下规则：

- 首次配置使用 `If-Match: "0"`，后续追加使用当前 Configuration Revision 的强 ETag；
- PERSONAL Binding 使用 `DIRECT`，TEAM Binding 支持 `DIRECT` 或 `INHERIT_TEAM_DEFAULT`；
- 默认 Personal Assistant 的 TEAM Binding 由服务端固定为 `ORCHESTRATION_ONLY`，浏览器提交 `null`；
- 主模型和 Fallback 来自服务端按 Ownership、连接健康、区域、Template 能力和治理策略计算的可选交集，Fallback 不能等于主模型；
- “保存并预检”由服务端在追加事务内先验证候选 Binding，失败不产生 Revision；成功后页面读取已提交 Revision 的公开 Preflight 证据；
- 历史 Revision 只读。新 Task 和新 Conversation 使用新 Revision；已有 Conversation 保持 Pin，运行中 Task 和默认 Retry 继续使用固定 PolicySnapshot。

## Template 槽位与目录缺口

页面只呈现 Template 声明的可配置槽位。`supplementalInstructions`、GenerateOptions 和批准 Skill 都受前后端约束。批准 Skill 的候选 Key 来自 Template 公共白名单 `approvedSkillKeys`，浏览器不生成或接受任意 Skill Key。

当前没有公开 MemoryPolicy 和 BudgetPolicy 候选目录。页面只展示并保留当前精确引用，不提供 UUID 输入框，也不伪造候选。候选目录进入公开 API 后再补充选择交互。

System Prompt、Allowed Tool、Structured Output Schema、Endpoint、Credential Reference 和 API Key 不进入该页面的表单、Store、DOM、URL 或命令体。API Key 的单向录入属于 M5-F04“模型与凭证”页面。

## 生命周期与恢复

非默认 Agent 支持启用、禁用和归档。命令采用强 ETag 和 Idempotency-Key；高风险操作要求二次点击确认。配置或生命周期成功时，Store 先失效详情、Configuration、模型候选和 Preflight 派生事实，保留目录组件直至成功续程触发权威刷新，避免组件提前卸载丢失刷新事件。版本冲突保留表单并提供事实刷新入口；可重试失败复用同一 Idempotency-Key，输入变化后生成新 Key。

## 响应式与可访问性

桌面端使用目录内嵌详情面板，移动端将创建向导呈现为 Bottom Sheet，并在页面摘要区提供可见的“创建 Agent”入口。创建向导支持初始焦点、Escape、Focus Trap 和关闭后焦点恢复；详情关闭后焦点返回原 Agent 卡片。390×844 视口没有水平溢出。

## 验证

- `AgentCreateDialog.spec.ts` 覆盖公共创建字段白名单、TEAM 权限失败关闭、Escape 和可重试输入保留；
- `AgentConfigurationPanel.spec.ts` 覆盖首个 Revision 的 `If-Match: "0"`、PERSONAL Direct、TEAM 默认继承、Template Skill 白名单、敏感字段隔离和历史 Revision 只读；
- Playwright 在 desktop Chromium 与 390×844 narrow Chromium 完成创建、唯一新 ID 跳转、主/Fallback、首次保存、Preflight、URL Revision、无水平溢出和敏感内容扫描；
- Axe 覆盖 Agent 详情和创建对话框；视觉基线覆盖 Agent 列表、创建向导和详情配置双视口；
- 前端全量 61 个测试文件、273 项 Vitest、132 项 Playwright、生产构建通过；服务端公开 Template DTO 专项测试通过。

## 下一任务

M5-F04 交付“模型与凭证”管理页，提供 Provider/Model 目录、Connection 创建与验证、Credential 单向录入和轮换、Team 默认、治理允许列表、价格与健康事实。
