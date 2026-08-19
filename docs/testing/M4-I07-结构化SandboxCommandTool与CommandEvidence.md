# M4-I07 结构化 SandboxCommandTool 与 CommandEvidence

## 1. 交付范围

M4-I07 在 TaskExecution 级 AgentScope Docker Sandbox 上交付一个命令入口：

- `coding_run_command` 只接收 `command_kind`、可选模块选择器、可选测试选择器和可选超时；
- `BuildProfileCommandRunner` 从当前 WorkspacePolicy 与精确 BuildProfile 解析最终 typed argv；
- `CommandLogArtifactWriter` 将完整有界输出写入 ArtifactStore；
- `CommandEvidenceWriter` 发布平台观察到的 CommandSpec、终态、Exit Code、日志引用和稳定摘要；
- `SandboxCommandUsageRegistry` 在同一 Worker 的重复 Session 间累计命令次数并延续 EvidenceSequence。

Tool 不接收命令字符串、argv、工作目录、环境变量、Sandbox 镜像、Docker 参数或宿主路径。AgentScope 原生 `ShellExecuteTool` 和 `execute` Tool 不注册。

## 2. BuildProfile 解析规则

命令入口和固定参数全部来自不可变 CommandCatalog：

| BuildTool | 固定入口 | 平台生成的选择器 |
|---|---|---|
| Maven | `mvn` | 模块生成 `-pl <comma-list>`；测试生成单个 `-Dtest=<comma-list>` |
| Maven Wrapper | `./mvnw` | 与 Maven 相同 |
| Gradle Wrapper | `./gradlew` | executable-only 槽位可将模块映射为 `:module:path:<task>`；测试逐项生成 `--tests <selector>` |
| Project Script | `./scripts/...` | 只执行 Profile 固化 argv，不定义动态选择器协议 |

模块必须同时位于 `allowedModules`、数量/长度预算和安全编码字符集内；测试必须满足 `TestClass` 或 `TestClass#method`。重复选择器、Maven 逗号扩展、Gradle 冒号任务注入、未知模块、越界数量和非法 timeout 在调用 Sandbox 前拒绝。Gradle 模块任务由 CommandKind 固定映射为 `classes/test/check`。

AgentScope 2.0.0 的 Docker Sandbox 使用 `sh -c`。Runner 不接受 Shell 源码，并对受信 typed argv 的每个参数执行 POSIX 单引号编码，使分号、空格和引号保持为参数数据。`CommandSpec.capture` 复验固定 argv 前缀、选择器参数上限、工作目录、Policy/Profile、镜像 Digest 与 timeout。

## 3. 终态与进程树

平台按实际执行结果形成终态：

- 正常退出保留真实 Exit Code，只有 `EXITED + 0` 成功；
- 非零退出从 AgentScope `ExecException` 提取有界 stdout、stderr 和 Exit Code；
- 超时形成 `TIMED_OUT`；
- 输出截断形成 `OUTPUT_LIMIT_EXCEEDED`，不伪造 Exit Code；
- 启动失败、取消和 Sandbox Policy 失败映射为对应 CommandTermination。

AgentScope 2.0.0 超时时只终止宿主侧 `docker exec`，无法证明容器内后代进程已退出。CrewScope 在独占调用窗口内停止并重新启动精确受管容器，使用容器生命周期作为进程树终止边界。真实 Docker 测试启动“父进程等待、子进程延迟写文件”的脚本；超时恢复后延迟文件不存在，并且同一 Sandbox 可继续执行下一条命令。

## 4. Artifact 与 Evidence

每次已执行命令先写 `text/plain;charset=utf-8` 的 `COMMAND_LOG`：

- Artifact Scope 为当前 Workspace；
- Visibility 为 `WORKSPACE`，DataClassification 为 `RESTRICTED`；
- 写入前声明大小与 SHA-256，写入后复验 Descriptor；
- 日志保存 stdout、stderr、终态、Exit Code 和截断标记；
- Agent 只收到部署上限内的 UTF-8 输出前缀和 Artifact ID，完整有界日志留在 ArtifactStore。

随后 `CommandEvidence.record` 闭合 Workspace、Policy、CodingTarget、attempt、CommandSpec、EvidenceSequence、时间、终态、Exit Code、日志 Hash、创建 Principal 与 Evidence Hash，再通过 `CommandEvidenceRepository.create` 原子发布。Artifact 或 Evidence 发布失败返回固定 `EVIDENCE_PUBLICATION_FAILED`，异常不保留宿主路径、容器标识或原始命令输出。M4-I09 已将本 Writer 接入稳定 Artifact ID、统一保留期、关系元数据闭合 Reader、Range、Tombstone 与测试报告发布能力。

## 5. 验证结果

专项测试覆盖：

- 只注册 `coding_run_command`，不存在原生 raw Shell Tool；
- Maven、Maven Wrapper、Gradle Wrapper 与项目脚本固定入口；
- 模块/测试选择器、重复项、编码攻击、参数引号和 timeout；
- 正常、非零退出、超时、输出超限和启动失败；
- Workspace 跨 Session 命令预算与 EvidenceSequence 恢复；
- Restricted Workspace Artifact、SHA-256、Exit Code 和 CommandEvidence 发布；
- Worker/all Spring 装配、纯 server 退让和配置上限；
- 真实 Docker 进程树终止、Sandbox 重启与零延迟副作用。

专项命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  -Dtest=BuildProfileTest,BuildProfileCommandRunnerM4I07Test,CommandEvidenceWriterM4I07Test,SandboxCommandUsageM4I07Test,SandboxCommandConfigurationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  '-Dtest=TaskExecutionSandboxFactoryM4I04DockerIntegrationTest#m4I07TimeoutTerminatesTheContainerProcessTreeAndRestartsTheSandbox' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

专项快速测试共 11 项通过，真实 Docker 进程树测试 1 项通过。全仓 `./mvnw --batch-mode --no-transfer-progress test` 门禁通过：7 个 Reactor 模块全部成功，共 1,298 项测试，0 失败、0 错误、0 跳过。

## 6. 后续边界

M4-I08 已实现 Workspace Diff Watcher、Git Reconciler、Diff Event Store 与最终 DiffArtifact。M4-I09 已补齐 Patch/构建日志/测试报告 Reader、Tombstone、保留期、Range 和公开摘要。M4-I10 完成跨 Worker 重启的用量恢复与在途命令对账。
