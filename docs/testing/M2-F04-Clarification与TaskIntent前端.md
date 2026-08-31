# M2-F04 Clarification 与 TaskIntent 前端

## 交付范围

M2-F04 在 Conversation 工作区交付结构化澄清和 TaskIntent 人工决策闭环。

- AgentScope Adapter 从服务端 Pending `request_clarification` Tool 提取并约束公开 `ClarificationRequestV1`；
- `RUN_INTERRUPTED` 只公开 Summary、Question、Context、Required、Choices 和稳定 FieldKey；
- Clarification 卡使用原生单选与文本控件，只提交 `answers: fieldKey -> value`；
- Agent 运行状态、失败恢复状态与 Clarification 表单统一进入消息流末尾的操作区，位于最后一条消息之后、Composer 之前；
- `RUN_INTERRUPTED` 同时展示“Personal Agent 需要补充信息”状态和 HITL 表单，失败状态在同一区域提供安全重试；
- 失败或待补充信息状态出现时，只滚动消息历史到操作区，用户无需回到消息历史开头，也不会把窄屏 HITL 控件滚入固定模式导航下方；
- Clarification 原生单选控件定位在可见选项内部，整个选项均可点击，并保留键盘焦点样式；
- Resume 使用独立 `Idempotency-Key`，断线重放复用原键，刷新恢复 Pending Clarification；
- TaskIntent Gateway 保留并校验强 ETag；
- TaskIntent Store 在修订、拒绝和确认后重新读取服务端事实；
- 修订提交完整 `TaskIntentV1`，拒绝原因限制为 1–1000 字符；
- 确认先执行 Confirmation Preview，核对 Proposal、Revision、Version 和 ETag，再发送空 Body Confirmation；
- `409/412` 与预检事实不一致自动刷新，`403` 进入统一 Access Denied；
- Owner 可以执行决策，同级参与者可以观察提案和责任事实。

## 安全边界

客户端不能提交 InterruptToken、ToolCallId、ReplyId、ConfirmResult、PermissionRule、Session、Tool 名称或 Tool 参数。Reasoning、Tool Result、Provider 原始响应和未知 AG-UI 事件不进入组件状态。TaskIntent 状态和版本只来自 GET 事实，CommandReceipt 不用于本地推断。

## 自动化验证

- AgentScope Native Runtime 集成测试验证生产 Clarification Tool 的公开 FieldKey 与字段化回答绑定；
- Application 测试验证公开 Clarification Payload、终态映射和安全事件协议；
- Vitest 覆盖 Gateway、Store、底部 Agent 操作区、Clarification 卡、TaskIntent 卡、Resume 重放、强 ETag、空 Body 确认、Owner 提示、重复操作和冲突刷新；
- Playwright 在桌面与窄屏验证结构化回答、固定底栏下的选项可操作性、无运行时字段泄漏、TaskIntent 当前事实、确认预检和空 Body Confirmation；
- Histoire 提供底部 Clarification、Agent 失败恢复、Owner Review 和 Participant View 等组件变体。

验证结果：

- Vitest：26 个测试文件、101 个测试全部通过；
- 前端覆盖率：Statements 86.97%、Branches 82.69%、Functions 85.15%、Lines 87.90%；
- Playwright：桌面与窄屏共 64 个场景全部通过；
- WCAG 2.2 AA 自动检查通过；
- Histoire：5 个 Stories、15 个 Variants 构建通过；
- 前端 TypeScript 与生产构建通过；
- Java 分模块回归：Domain 199 个、Application 175 个、AgentScope 68 个，共 442 个测试通过；
- AgentScope Native Runtime 17 个专项测试通过；
- 文档链接检查：98 个 Markdown 文件通过；
- `git diff --check` 通过。

## 验证命令

```text
mvn -pl crewscope-application,crewscope-agentscope -am clean test
pnpm test:coverage
pnpm build
pnpm story:build
pnpm test:e2e
node scripts/check-doc-links.mjs
git diff --check
```
