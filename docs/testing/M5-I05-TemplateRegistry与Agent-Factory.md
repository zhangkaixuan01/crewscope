# M5-I05 TemplateRegistry 与 Agent Factory

## 1. 交付范围

M5-I05 已将 M5-I04 固定的执行配置装配为受控 AgentScope Java 2.0.0 实例：

- `ResolvedAgentScopeModelFactory` 按 `ResolvedAgentExecutionConfiguration` 的精确 Provider、Connection、Credential、Catalog 与模型 Revision 构建 Primary/Fallback Model；
- Model 创建前重新读取当前注册事实，并复验 Provider Hash、Adapter、DataPolicy、Connection Version、Owner、Region、Credential Version、Catalog Hash 与 Model Revision；
- Credential Handle 请求同时携带期望 Connection Version 与 Credential Version，关闭 Preflight 到 Model 创建之间的轮换、暂停和撤销竞态；
- `AgentTemplateRuntimeAssembler` 将 Active AgentProfile、TemplateVersion、ConfigurationRevision、Resolved Configuration 与动态 Model 组装为一个不可扩大的执行定义；
- `AgentTemplateRuntimeRegistry` 要求 Personal、Team、Specialist 三类 Factory 各且仅有一个，并按 `AgentRuntimeRole` 失败关闭分派；
- Spring 使用构造器装配完整 Registry；API-only 进程可以装配 Reviewer/Personal/Team 入口，Coding Template 只在 Worker 提供 M4 `CodingSpecialistFactory` 时执行。

## 2. Template 与身份边界

`AgentTemplateRuntimeDefinition` 在 Agent 创建前固定并复验：

- Agent Principal、AgentProfile ID/Version、Ownership 与 RuntimeRole；
- TemplateVersion、Template Content Hash、Configuration Revision/Hash 与 ExecutionScope；
- Enabled Tool 是 Template Allowlist 子集；Approved Skill 是 Template Skill Allowlist 子集；Structured Output Schema Hash 完全相同；
- Primary/Fallback Model 只来自完整 Preflight 结果，用户补充指令不参与 Provider、Connection、Catalog 或 GenerateOptions 解析；
- System Prompt 由 Template Baseline 与成员补充指令组成，补充指令只允许收窄任务，不能改变 Tool、Skill、Schema、数据、模型、审批或 Sandbox 策略。

`TemplateAgentSessionIdentity` 只接受可调用的耐久 Conversation/Task RuntimeSession，并固定 Agent Principal、AgentProfile、AgentScope `userId/sessionId` 与 StateReference。`TemplateAgentBuildRequest` 再次要求 Runtime Toolkit 名称与配置完全相同。两个 Agent 即使来自相同模板，也使用各自的 Principal、Profile、Session 与 State 坐标。

## 3. 三类 Factory

- Personal Factory 只接受 `PERSONAL_ASSISTANT + Conversation Session`；
- Team Factory 只接受 `TEAM_COORDINATOR + Task/Step Session`，拒绝 Specialist Session；
- Specialist Factory 只接受 `SPECIALIST + Specialist Session`；`coding` 委托 M4 Coding Runtime，其他模板使用受限通用 Builder；
- 受限通用 Builder 关闭 Filesystem、Shell、Subagent、Memory、Dynamic Skill、Workspace Context、Compaction、`@path` 展开和 Tools Config，并移除 AgentScope 自动加入的 `wait_async_results`；
- 非 Coding 模板若声明了尚未注册的固定 Skill Bundle 会失败关闭，避免配置声称启用 Skill 而运行时静默忽略；M5-I06 已使用零 Tool Reviewer、一次性有界 ContextPackage 和 Structured Finding 执行链路接入评审能力。

Coding Template 继续使用 M4 的冻结 Coding Toolkit、`java-spring-v1` 只读 Skill、Plan/Todo、Session 文件系统隔离、Compaction、Tool Result Eviction、Telemetry 与 AgentState 链路。M5 只替换经过 Preflight 的 Primary/Fallback Model、Template Prompt 和安全 GenerateOptions，不复制或削弱 M4 Runtime。

## 4. 自动化验证

- `ModelConnectionCredentialServiceTest`：5 个场景覆盖生命周期、Handle 关闭/过期/撤权与 Connection Version 漂移；
- `ResolvedAgentScopeModelFactoryM5I05Test`：4 个场景覆盖 DeepSeek/OpenAI 兼容策略、Primary/Fallback 独立 Handle、Connection/Credential Version 漂移；
- `AgentTemplateRuntimeRegistryM5I05Test`：7 个场景覆盖三角色唯一注册、Tool/Skill/Schema/Prompt 边界、晚期 Toolkit 扩大、Personal/Coding/Reviewer 身份隔离、受限 Reviewer 和外来 Profile/Principal；
- `AgentScopeCodingRuntimeM4I11IntegrationTest`：新增 M5 动态模型路径回归，并继续覆盖固定 Coding Tool/Skill、Plan/Todo、Compaction、Eviction 与 Session Workspace 隔离；
- `AgentTemplateRuntimeApplicationConfigurationM5I05Test`：2 个场景覆盖 API-only Spring 装配和最大迭代启动校验。

专项回归共 24 个测试，全部通过。

完整 Maven Reactor 回归共 1702 个测试，全部通过：

- Domain 500 个；
- Application 343 个；
- AgentScope Adapter 137 个；
- Integration 1 个；
- Infrastructure 495 个；
- Server 226 个。

回归实际运行 PostgreSQL、Redis、Flyway、Docker Sandbox、本地 Git Process 和 Loopback HTTP 集成测试。根 README 与 `docs` 共 214 份 Markdown 文档链接通过，`git diff --check` 同步通过。
