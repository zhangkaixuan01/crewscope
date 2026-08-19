# M4-I04 TaskExecution 级 Docker Sandbox

> 完成日期：2026-08-18<br>
> 范围：`crewscope-infrastructure`、`crewscope-server`、AgentScope Java 2.0.0

## 1. 交付结果

M4-I04 交付一个由 CrewScope 持有生命周期、由 AgentScope 提供原生 Docker 文件系统实现的 TaskExecution Sandbox：

- `TaskExecutionSandboxFactory`：Provision、Recover、Pause 与 Destroy；
- `ManagedTaskExecutionSandbox`、`TaskExecutionSandboxCall`：TaskExecution 级容器句柄与独占 AgentScope 调用窗口；
- `TaskExecutionSandboxDescriptor/Fingerprint`：完整物理期望状态与路径无关的公开 SHA-256 证明；
- `DockerSandboxControl`、`DockerCliSandboxControl`：有界 Inspect、Stop 与精确 Remove；
- `TaskExecutionSandboxConfiguration/Properties`：Worker-only Spring Boot 装配、容器路径和生命周期参数；
- `TaskExecutionSandboxError/Exception`：稳定、安全且不暴露宿主信息的失败分类。

## 2. AgentScope 复用边界

Factory 使用 AgentScope 2.0.0 的 `DockerFilesystemSpec`、`WorkspaceSpec`、`BindMountEntry` 与 `DockerSandboxClient` 创建原生 `DockerSandbox`。当前 `ManagedWorktree` 挂载为 `/workspace/repository`，并关闭 Workspace Projection，Worktree 保持唯一代码文件事实源。

每次 Agent 执行通过 `openCall()` 获得包含 external Sandbox 的 `SandboxContext`。AgentScope 可以复用其 Sandbox 文件系统能力；external Sandbox 的 `close/shutdown` 不删除容器，TaskExecution 生命周期仍由 CrewScope 统一管理。

## 3. 身份、恢复与 Fencing

容器名由 Workspace Key 确定性派生。Docker Label 与 Sandbox Fingerprint 闭合：

- Workspace Key、TaskExecution 与 attempt；
- 领域 Workspace Fingerprint 与 Worktree 物理 Fingerprint；
- WorkspacePolicy、BuildProfile 与摘要固定镜像；
- RuntimeEnvironment、Runtime、Worker、Lease 与 Fencing Token；
- canonical Worktree、容器 Workspace Root、Repository Mount、UID/GID 与资源预算。

Provision 与 Recover 只复用完整物理契约一致的容器。同一 Lease 的 PREPARE 到 RUN 不进入 Sandbox Fingerprint，因此不会重建容器。新 Fencing 代次恢复先删除旧代次容器，再创建并验证当前代次；旧 Lease 不能恢复容器，旧 Sandbox 句柄也不能销毁当前代次。

`openCall()` 在每次 AgentScope 调用前复验 Workspace 身份、当前活动 Lease 与 Fencing Token，并使用原子 Guard 拒绝重叠调用。调用窗口结束后 external Sandbox 立即失效。

## 4. 固定安全契约

容器配置固定为：

```text
BuildProfile digest image
Worktree UID:GID ordinary user
read-only root filesystem
network=none
CPU / memory / PID limits
cap-drop=ALL
no-new-privileges
bounded /tmp tmpfs
init process
```

环境只注入 `HOME=/tmp/crewscope-home`、`MAVEN_CONFIG`、`TMPDIR`、`CI=true` 与 `LANG=C.UTF-8`，不复制宿主环境或凭证。Sandbox 命令超时不能超过 `SandboxResourceBudget.maxCommandDurationSeconds`；stdout/stderr 按 `maxCommandOutputBytes` 截断，并保持 UTF-8 字符边界。

公开 State、异常和字符串输出不包含 canonical Worktree、容器名、Container ID 或 Docker 原始输出。Docker Daemon 失败与容器不存在分开处理，所有无法证明安全的容器状态失败关闭。

Docker CLI 的 stdout/stderr 合并流由独立 Collector 完整排空，保留上限为 1 MiB。主线程在进程退出后有界等待 Collector 关闭；输出超限、读取失败或管道未关闭均进入稳定 `COMMAND_FAILED`，Inspect 不解析半截 JSON，容器清单不接受静默截断结果。

## 5. 生命周期

- Provision：创建新容器，或幂等连接当前代次的完整匹配容器；
- Pause：默认 `STOP`，停止容器但保留容器与 Worktree；也支持部署级 `KEEP_RUNNING`；
- Recover/Resume：复验完整契约并启动已停止容器；
- Destroy：只删除完整匹配当前句柄的容器，重复清理幂等；
- 创建失败：只清理身份闭合的本次受管残留；名称冲突或不闭合资源保留现场并失败关闭。

## 6. Spring 配置

```yaml
crewscope:
  coding:
    sandbox:
      workspace-root: /workspace
      repository-mount: repository
      docker-command-timeout: 30s
      pause-stop-timeout: 1s
      pause-mode: STOP
```

配置只在 `all/worker` Execution Profile 装配。纯 `server` Profile 不创建 `DockerSandboxControl` 或 `TaskExecutionSandboxFactory`，避免 API 节点访问宿主 Docker。

## 7. 自动化证据

专项测试：

```text
TaskExecutionSandboxFactoryM4I04DockerIntegrationTest  7
TaskExecutionSandboxConfigurationTest                  3
合计                                                   10
```

覆盖：同机 Worktree 写入、普通用户、只读根层、默认无网络、固定环境、CPU/内存/PID、安全参数、幂等 Provision、PREPARE→RUN、Pause/Resume、新 Fencing 清理旧容器、旧句柄销毁隔离、过期 Lease、并发 Guard、命令超时、ASCII/UTF-8 输出上限、无残留容器、Worker-only 装配、Server 退让与非法配置失败。

执行命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  -Dtest=TaskExecutionSandboxFactoryM4I04DockerIntegrationTest,TaskExecutionSandboxConfigurationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：`10 / 10` 通过，Docker 测试未跳过，测试结束后 `io.crewscope.sandbox.managed=true` 容器残留为 `0`。

M4-I01 至 M4-I04 聚焦回归 `53 / 53` 通过。全仓 Maven 回归 `1262 / 1262` 通过：Domain `409`、Application `265`、AgentScope Adapter `95`、Integration `1`、Infrastructure `328`、Server `164`。159 份 Markdown 文档链接校验通过。

## 8. 后续边界

M4-I05 已在此 external Sandbox 调用窗口之上实现只读 `RepositoryInspectionTool`，复用 AgentScope `AbstractFilesystem` 的 tree/list/read/grep/glob 能力，并在每次调用继续复验 Context、WorkspacePolicy、Lease/Fencing、AllowedPaths 和结果预算。实现与证据见 [M4-I05 受控 RepositoryInspectionTool](M4-I05-受控RepositoryInspectionTool.md)。
