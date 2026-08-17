# M4-S04 Coding Agent 评测协议验证记录

> 验证对象：CrewScope Coding Evaluation v1、Java 17、Spring Boot 4.0.4、Maven、Git、Node.js<br>
> CrewScope 范围：`evaluation/m4/coding-v1`、CI Quality Gate<br>
> 验证日期：2026-08-16

## 1. 验证结论

CrewScope 使用版本化清单、可重复 Git Fixture、Agent 不可见 Judge Pack、固定运行资产、稳定失败分类和双评测轨道衡量原生 Coding Agent。

评测闭环为：

```text
Evaluation Suite v1
  -> 固定 Runtime / Profile / Prompt / Skill / Tool / Sandbox / Budget
  -> 固定 Task Brief / Baseline Commit / AllowedPaths / Timeout
  -> 独立物化 Git Workspace
  -> Coding Agent 执行
  -> 平台复验 Git Diff / Sandbox / Budget / Structured Output
  -> 注入 Agent 不可见 Judge Test
  -> 执行固定 Maven argv
  -> 固化命令证据与最终 Hash
  -> 稳定 Outcome
  -> CI 协议报告或真实模型基准报告
```

确定性 CI 不调用真实模型，不产生模型能力分数。真实模型基准固定精确 Provider、Model ID、Model Revision、Seed 和运行资产后执行，并保留每次 Run 的独立证据。

## 2. 版本化资产

评测根目录为：

```text
evaluation/m4/coding-v1/
```

资产包括：

| 资产 | 版本与作用 |
|---|---|
| `suite.json` | `crewscope.coding-evaluation/v1`，保存 12 个任务、双轨和策略 |
| `runtime/coding-specialist-v1.json` | AgentScope 2.0.0、Profile、模型槽位、Sandbox 和预算 |
| `runtime/real-model-run-lock-v1.schema.json` | 强制每次真实模型 Run 固化精确模型、环境与只读依赖缓存快照 |
| `prompts/coding-system-v1.md` | Coding Specialist System Prompt v1 |
| `skills/java-spring-v1.md` | Java/Spring Boot 只读 Skill Bundle v1 |
| `tools/controlled-tools-v1.json` | 受控 Repository、Coding、Command 与 Delivery Tool v1 |
| `fixtures/java-spring-lab` | Agent 可见仓库模板 |
| `judge-tests` | Agent 不可见的 12 份验收测试 |
| `failure-samples/v1.json` | 判定器成功对照与稳定故障样本 |
| `scripts/evaluate.mjs` | 校验、物化与结果判定入口 |

Suite 使用 SHA-256 锁定 Runtime、RunLock Schema、Prompt、Skill、Tool、Judge Script 和故障样本。Judge Pack 使用路径、大小和文件内容组成的树 Hash 锁定。Fixture 使用相同树 Hash 协议锁定。

## 3. 仓库 Fixture

Fixture 使用 Java 17、Spring Boot 4.0.4 和 Maven，包含 12 个可编译的目标类。固定 Git 信息为：

```text
defaultBranch = main
baselineCommit = f053fd1e9665b19ff5c1cdb0164043a0b5f9e5b9
fixtureContentSha256 = 1669b844671a5079fc92facb998445dec234507c19f1b65381f4963074e430b2
author = CrewScope Evaluation <evaluation@crewscope.local>
timestamp = 2026-08-16T00:00:00Z
message = CrewScope M4 coding evaluation baseline v1
```

物化器固定默认分支、作者、提交者、UTC 时间、Commit Message、`core.autocrlf=false`、`core.filemode=true` 和禁用 Commit 签名。macOS 与 Linux 使用相同文件字节时生成相同 Baseline Commit。

每个任务显式保存同一完整 Baseline Commit、一个精确生产文件 AllowedPath、600 秒任务超时、固定 Maven 参数数组、180 秒验收超时和预期行为。Judge Test 不复制到 Agent Workspace。

## 4. 冻结任务

| Task ID | 类型 | 验收重点 |
|---|---|---|
| `java-username-normalization` | Java 正确性 | Unicode NFKC、空白、Locale、空值 |
| `java-retry-backoff` | Java 边界 | 参数、移位/乘法溢出、封顶 |
| `java-secret-redaction` | 安全 | Token、Bearer、API Key、Password 脱敏 |
| `java-stable-task-order` | 确定性 | Priority、时间、代码点排序 |
| `java-opaque-cursor` | 安全协议 | 完整性、Workspace 绑定、篡改拒绝 |
| `java-repository-path-policy` | 文件安全 | 相对路径、Canonical、Symlink 越界 |
| `spring-member-controller` | Spring Web | Bean Validation、HTTP 201 |
| `spring-safe-error-envelope` | Spring 安全 | 稳定错误信封、原始异常隔离 |
| `spring-team-cache-scope` | 租户隔离 | Organization/Team/Resource 闭合 |
| `spring-idempotent-service` | 并发 | 同键一次执行、失败可重试 |
| `spring-request-id-filter` | Web 安全 | Header 信任边界与有界生成 |
| `spring-coding-properties` | 配置安全 | 关闭网络、AllowedPaths、超时与不可变性 |

12 个任务覆盖 6 个 Java 任务和 6 个 Spring/平台任务，包含正确性、确定性、并发、租户隔离、协议和安全边界。任务规模限制为单文件修改，使 M4-Q03 的结果差异主要来自仓库理解、代码生成和复验能力。

## 5. Runtime 与预算

固定 Runtime 使用：

```text
AgentScope Java = 2.0.0
AgentProfile = coding-specialist-java-spring@1.0.0
Model Slot = crewscope-primary
Temperature = 0
TopP = 1
Fallback = disabled
Sandbox = maven@sha256:29a1658b1f3078e07c2b17f7b519b45eb47f65d9628e887eac45a8c5c8f939d4
Java = 17
Network = none
CPU = 2
Memory = 2048 MiB
PIDs = 256
```

全局硬预算为：

```text
wallClockSeconds = 900
modelCalls = 40
inputTokens = 120000
outputTokens = 24000
toolCalls = 160
commandCalls = 12
writeOperations = 80
writtenBytes = 1048576
diffBytes = 524288
testRepairRounds = 3
```

真实模型 Run 在启动前生成符合 RunLock Schema 的不可变记录。RunLock 保存 Provider、Model ID、Model Revision、Run ID、开始时间、Seed、随机参数、AgentProfile、Sandbox Digest、Runtime Asset Hash、Maven Dependency Cache Snapshot ID 与 SHA-256。基础 Maven 镜像不包含本 Fixture 的完整 Spring Boot 依赖闭包。M4-I04/M4-I07 在真实模型执行前物化并复验只读依赖快照；Sandbox 保持无网络。缺少精确 Model Revision 或依赖快照的 Run 不进入基准统计。

## 6. 双评测轨道

### 6.1 deterministic-ci

CI 轨使用 `scripted-coding-model-v1` 和 12 个成功/故障事实样本验证：

- 清单与资产 Hash；
- Fixture Baseline 可重复性；
- Task、AllowedPaths、Command 与 Timeout 闭合；
- 双轨 Task 覆盖；
- 成功对照；
- 稳定故障优先级。

CI 轨的 `scoreModelQuality=false`。它只回答协议和判定器是否稳定。

### 6.2 real-model-benchmark

真实模型轨包含全部 12 个任务，每个任务执行 3 次，固定 Seed：

```text
20260816
20260817
20260818
```

每次 Run 单独保存结果。聚合指标包括 Pass@1、任务成功率、编译率、验收率、路径合规率、安全合规率、输入/输出 Token、成本和墙钟时间。模型运行失败保持原始失败类别，不通过重复运行覆盖。

## 7. 判定协议

成功必须同时满足：

1. Suite、Task、轨道与版本一致，报告不存在顶层未知字段；
2. 真实模型轨的 RunLock 闭合精确模型、Seed、资产和依赖快照；
3. Workspace 包含固定 Baseline Commit；
4. 实际 Git 变更非空、文件数不超过 1 且全部位于 AllowedPaths；
5. Sandbox Image、Network 与非 Root 证据匹配；
6. 实际用量不超过所有预算，且不存在额外预算字段；
7. 每条验收命令的 ID、参数数组和超时与清单完全一致，不存在重复或额外命令；
8. 命令未超时、Exit Code 为 0 且证据 Hash 可复验；
9. `CodeChangeResultV1` 通过平台 Schema 校验；
10. Agent 报告的最终 Manifest Hash 与平台复算结果一致。

稳定失败优先级为：

```text
SUITE_MISMATCH
RUN_LOCK_MISMATCH
BASELINE_MISMATCH
PATH_VIOLATION
SANDBOX_POLICY_VIOLATION
BUDGET_EXHAUSTED
MISSING_EVIDENCE
ACCEPTANCE_TIMEOUT
ACCEPTANCE_FAILED
EVIDENCE_HASH_MISMATCH
INVALID_STRUCTURED_RESULT
RESULT_HASH_MISMATCH
PASSED
```

判定器只消费平台生成的 RunLock、Git、Sandbox、Budget、CommandEvidence、Structured Output 和 Finalizer 事实。Git Changed Paths 保留 NUL 分隔的原始路径字节语义，不执行 `trim`。Agent 文本不能生成或覆盖这些字段。

## 8. 自动化证据

清单验证命令：

```text
node evaluation/m4/coding-v1/scripts/evaluate.mjs validate
```

验证结果：

```text
12 tasks
2 tracks
12 deterministic success/failure samples
Baseline Commit reproduced
Fixture and Judge Pack hashes matched
Runtime/Profile/Prompt/Skill/Tool locks matched
```

Fixture 与 12 份 Judge Test 使用 Java 17 完成 `testCompile`。GitHub Actions Backend Job 物化 Fixture、注入 Judge Pack 并执行同一 `testCompile`，避免只有 Hash 正确但 Java 源码不可编译。基线运行全部 Judge Test 时，每个 Task 对应的测试类至少产生一个失败，证明所有任务都具有可观察的基线缺口。

```text
Judge Test classes = 12
Tests run = 22
Expected baseline failures = 18
Unexpected baseline errors = 0
```

判定器烟测使用同一物化 Workspace 验证允许文件变更得到 `PASSED`，加入未授权文件后稳定得到 `PATH_VIOLATION`。

GitHub Actions 的 Quality Job 调用同一 `validate` 命令。评测资产、Judge Pack 或固定运行配置发生未更新 Hash 的漂移时阻断 CI。

## 9. 冻结决策

1. M4 评测集使用 `crewscope-java-spring-coding@1.0.0` 的 12 个单文件任务；
2. 每个任务显式固定 Baseline Commit、AllowedPaths、验收参数数组、期望行为和超时；
3. Agent 只看到仓库 Fixture，Judge Pack 保持平台侧不可见；
4. Profile、Prompt、Skill、Tool、Sandbox Digest、预算和随机参数全部进入版本锁；
5. 精确真实模型身份和只读 Maven Dependency Cache 快照在每次 Run 的 RunLock 中固化，缺失 Revision 或快照 Hash 的 Run 失败关闭；
6. 确定性 CI 与真实模型能力评测使用不同轨道和结果目录；
7. CI 不调用真实模型，不把基础设施回归解释成模型质量；
8. 判定结果来自平台复验，不信任 Agent 自述、Plan、Todo 或自行报告的测试成功；
9. M4-D07 复用本记录的 Structured Output 与结果闭合要求；
10. M4-I11、M4-I12 和 M4-Q03 复用本评测集，不修改 v1 历史资产。
