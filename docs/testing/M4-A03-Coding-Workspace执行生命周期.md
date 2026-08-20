# M4-A03 Coding Workspace 执行生命周期

## 1. 交付范围

M4-A03 将 Coding Workspace 接入 Durable Worker 的完整执行边界：

- `PREPARING` 创建或恢复 WorkspacePolicy、WorkspacePolicyOverlay、ExecutionWorkspace、Git Worktree 与 AgentScope Docker Sandbox；
- `RUNNING` 将 Workspace 切换为 `ACTIVE`，启动 Diff Monitor，并向 Coding Specialist 开放一个独占 Tool Session；
- Pause/Wait 停止 Sandbox 影响，将 Workspace 保留为 `READY`；
- Complete/Cancel 进入 `FINALIZING`，销毁 Sandbox、归档 Worktree、发布最终 DiffArtifact，再随 TaskExecution 终态进入 `COMPLETED`；
- 执行失败销毁 Sandbox，并将 Workspace 标记为 `FAILED`；
- Worker 故障关闭本地 Monitor，保留耐久资源，由启动对账继续恢复；
- PREPARING 失败撤销 Task Token、释放当前 Lease，并保留可证明的 Workspace 恢复事实。

## 2. 持久化写预算

V15 新增 `crewscope.workspace_write_budget_usage`。每次 create/edit/patch/move/delete 在文件系统效果发生前完成数据库预算预留，保存：

- Workspace 与 WorkspacePolicy；
- Policy Hash；
- 写操作次数；
- 累计写入字节；
- 已变更路径集合；
- 单调 Reservation Sequence 与乐观版本。

预算预留校验当前 `ACTIVE` Workspace、Runtime、Worker、Lease、Fencing Token、Workspace Fingerprint 与 Policy Hash。新 Worker 在开放写 Tool 前，将数据库累计值与 Git Status 下界合并。效果失败保留已提交预留，预算超限拒绝不改变累计值；事务回滚同时回滚预算预留，旧 Worker 和旧 Fencing Epoch 无法继续写入。

## 3. 测试证据与最终结果

`coding_run_command` 在 TEST、VERIFY、ACCEPTANCE 命令完成后执行以下步骤：

1. 发布 CommandEvidence 与完整命令日志；
2. 解析 Maven Surefire/Failsafe 的 `Results` 汇总，生成真实 TestStatistics；
3. 立即执行 Git Reconcile，将 TestEvidence 绑定到命令结束后的 DiffManifest；
4. 发布 Restricted TestReport Artifact 与 TestEvidence；
5. 成功证据触发最终 DiffArtifact 固化；
6. 平台保留模型生成的 changeSummary、limitations、risks，并使用 Workspace、CodingTarget、RepositoryAnalysis、DiffArtifact 与 TestEvidence 权威坐标生成最终 CodeChangeResultV1；
7. CodingOutputValidator 复验平台结果后提交成功事件。

最终 Diff 固化是 Pause 的最后提交点。在该点之后提交的 Pause 由 TaskExecution 收敛为 `COMPLETED`，不会将已归档 Workspace 退回 `READY`。Cancel 保持更高优先级；已固化 Diff 继续保留，TaskExecution 与 Workspace 的完成原因收敛为 `CANCELLED`。Worker 释放 Lease 时对并发控制请求造成的 TaskExecution 乐观锁冲突进行有界重读。

部署批准的验证命令是 M4 的验收执行边界。命令成功、解析到至少一个测试且统计无失败时，当前 CodingTarget 的验收项引用该 CommandEvidence 并标记通过。报告缺失、零测试、命令失败和测试失败均形成确定的失败分类并进入有界修复轮次。

## 4. Spring 装配

`CodingWorkspaceExecutionConfiguration` 在 `all/worker` Profile 装配：

- `DurableCodingWorkspaceExecutionLifecycle`；
- `CodingWorkspaceRuntimeRegistry`；
- `CodingSpecialistToolSessionFactory`；
- `TestEvidencePublisher`；
- `JdbcWorkspaceWriteBudgetStore`。

`TaskWorkerConfiguration` 通过构造器注入可选 Coding 生命周期。非 Coding Task 使用 NOOP 生命周期，沿用 M3 Durable Worker 行为。生产 Coding Specialist Authority Gateway 从 Worker 本地 Registry 获取当前 Workspace，每轮重新读取 Lease，并在最终固化前关闭独占 Tool Session。

## 5. 验证场景

专项验证覆盖：

- READY Workspace 在新 Lease/Fencing Epoch 下恢复并进入 ACTIVE；
- Workspace 激活后才进入 Worker 本地 Tool Registry；
- Pause 在 Lease Release 前停止影响，在 Release 后返回 READY；
- Complete 在 Lease Release 前固化最终 Diff，在 Release 后提交 Workspace 终态；
- 文件写预算跨 Worker Registry 恢复，效果失败保留已提交预留，预算超限拒绝不改变累计值；
- PostgreSQL 事务回滚、旧 Fencing、错误 Fingerprint 与预算越界失败关闭；
- Maven 单模块与多模块结果汇总解析；
- CommandEvidence、TestReport、TestEvidence 与 DiffManifest 完整绑定；
- 测试失败在同一 Run/Session 内修复，成功结果使用平台权威坐标；
- Durable Worker Pause、Cancel、Complete、Worker Shutdown 与 Token 撤销顺序。
- 最终固化与迟到 Pause/Cancel 的终态收敛，以及 Lease Release 乐观锁有界重读。

相关专项测试：

- `DurableCodingWorkspaceExecutionLifecycleM4A03Test`
- `WorkspaceWriteBudgetM4A03Test`
- `MavenTestSummaryParserM4A03Test`
- `TestEvidencePublisherM4A03Test`
- `M4D09CodingPersistenceIntegrationTest`
- `CodingSpecialistStepRuntimeM4I12Test`
- `DurableTaskWorkerExecutionHandlerM3I09Test`
- `TaskWorkerConfigurationM3I09Test`
- `ExecutionWorkspaceTest`
- `TaskExecutionTest`

阶段专项与关联定向回归共 87 个测试，结果为 0 failure、0 error、0 skipped。全仓 `clean verify` 共 1390 个测试，170 个 Markdown 文件链接校验通过；完整 Release Gate 继续由 M4-Q04 汇总。
