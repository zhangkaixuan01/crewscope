# M0-S03 Docker Sandbox 与 Worktree 验证记录

> 验证对象：AgentScope Java `v2.0.0`  
> CrewScope 模块：`crewscope-agentscope`  
> 验证日期：2026-08-06

## 1. 验证目标

1. 创建临时 Git 仓库作为宿主 Worktree；
2. 使用 `DockerFilesystemSpec` 将 Worktree 读写挂载到 Sandbox；
3. 通过 Harness `execute` Tool 修改 Java 文件；
4. 在容器内执行 Maven 命令；
5. 由宿主 Git Diff Watcher 观察同一变更；
6. 验证容器生命周期结束后没有残留 Sandbox 容器。

## 2. 运行配置

```text
image = maven:3.9.6-eclipse-temurin-17
imageDigest = sha256:29a1658b1f3078e07c2b17f7b519b45eb47f65d9628e887eac45a8c5c8f939d4
containerWorkspace = /workspace
bindMount = <host-temp-repository>:/workspace/repository:rw
network = none
isolationScope = SESSION
stateStore = InMemoryAgentStateStore
permissionMode = BYPASS（仅限受控测试 Fixture）
```

宿主临时仓库包含：

```text
.git/
.gitignore
pom.xml
src/main/java/io/crewscope/probe/Greeting.java
```

仓库初始化后创建基线 Commit，`git status --porcelain` 为空。

## 3. 执行闭环

确定性 Model 驱动 HarnessAgent 执行三个 Reasoning Round：

```text
Round 1
  execute:
    sed -i 's/before-sandbox/after-sandbox/' \
      src/main/java/io/crewscope/probe/Greeting.java

Round 2
  execute:
    mvn --batch-mode --no-transfer-progress validate
    mkdir -p target
    写入 target/m0-s03-maven.txt

Round 3
  final response: sandbox-worktree-complete
```

两个 Tool Result 均包含：

```text
Exit code: 0
```

Maven 标记文件内容为：

```text
maven-validate-ok
```

Sandbox 网络保持关闭。`mvn validate` 只解析和验证 Fixture POM，不访问远程仓库。

## 4. 宿主 Diff Watcher

宿主 Watcher 与 Agent 调用并发轮询：

```bash
git diff --no-ext-diff --unified=0 -- \
  src/main/java/io/crewscope/probe/Greeting.java
```

观察结果：

```diff
-        return "before-sandbox";
+        return "after-sandbox";
```

该结果证明 Worker、Worktree、Docker Sandbox 和 Diff Watcher 可以位于同一执行节点，并以宿主
Worktree 作为代码变更的文件事实源。容器只获得显式挂载的当前仓库路径。

## 5. AgentScope 2.0.0 接口记录

### 5.1 BindMountEntry

`WorkspaceSpec.entries` 使用相对容器路径作为 Key：

```text
key = repository
hostPath = 临时 Git 仓库绝对路径
readOnly = false
```

`DockerSandbox` 将其转换为：

```text
-v <hostPath>:/workspace/repository:rw
```

不需要通过 `additionalRunArgs` 手工组装挂载参数。

### 5.2 ToolUseBlock 参数

AgentScope 2.0.0 的 `ToolExecutor`：

- 使用 `ToolUseBlock.content` 执行 JSON Schema 校验；
- 使用 `ToolUseBlock.input` 构造实际调用参数。

确定性 Model Fixture 必须同时写入 JSON `content` 和 Map `input`。正式 Model Adapter 也需要经过
契约测试，防止只有 `input` 时出现参数全部缺失的校验错误。

### 5.3 Shell 权限

`execute` 默认进入权限确认。M0-S03 对隔离的临时 Fixture 显式设置 Session 级
`PermissionMode.BYPASS`。生产任务不默认启用 BYPASS，Shell 和外部副作用继续使用 M0-S02 的
Confirmation、策略与审计边界。

## 6. 生命周期观察

AgentScope 2.0.0 自管理 Docker Sandbox 在释放时调用：

```text
docker stop --time=30 <containerId>
docker rm --force <containerId>
```

容器的空闲 Shell Loop 没有快速响应停止信号，本次测试功能执行时间很短，总耗时约 33 秒，主要
耗时来自停止等待。测试结束后没有残留 `agentscope-sandbox-*` 容器。

生产 Worker 在 M4 实现时采用 TaskExecution 级 Sandbox 生命周期，避免按细粒度 Tool 或短调用
频繁创建和停止容器；同时评估 AgentScope 升级、上游修复或可配置停止超时。

## 7. 自动化证据

测试类：

```text
crewscope-agentscope/src/test/java/io/crewscope/agentscope/
  HarnessAgentM0S03DockerIntegrationTest.java
  ScriptedModel.java
```

前置条件：

```bash
docker info
docker pull maven:3.9.6-eclipse-temurin-17
```

验证命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-agentscope -am \
  -Dtest=HarnessAgentM0S03DockerIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：

```text
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Docker 或指定镜像不可用时测试通过 JUnit Assumption 标记为 Skipped。M0-Q01 CI 必须预拉取固定
镜像并校验该测试没有 Skipped。

## 8. 结论

M0-S03 已验证通过：

- `DockerFilesystemSpec + WorkspaceSpec + BindMountEntry` 适合 MVP 同机拓扑；
- Worktree 修改在容器和宿主之间同步可见；
- Maven 命令在无网络 Sandbox 内成功执行；
- 宿主 Git Diff Watcher 能观察到一致变更；
- Worktree 是代码文件事实源，Sandbox 是受控执行环境；
- M4 需要实现 TaskExecution 级 Sandbox 生命周期和停止延迟治理。

AgentScope M0 Spike 串已完成。
