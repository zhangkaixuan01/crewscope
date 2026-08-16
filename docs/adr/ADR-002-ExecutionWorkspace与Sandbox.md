# ADR-002：ExecutionWorkspace 与 Sandbox 拓扑

> 状态：ACCEPTED<br>
> 日期：2026-08-05<br>
> 影响里程碑：M0 Spike、M4、M6

## 背景

CrewScope 需要同时管理 Git Worktree、Sandbox 文件访问、Maven 命令、Diff Stream、文件监听和冷恢复。AgentScope Kubernetes Sandbox 使用节点级 hostPath bind mount。Worker 本地 Worktree 与任意 Kubernetes 节点之间缺少天然共享路径。

## 决策

### MVP 拓扑

MVP 使用同机 Execution Worker：

```text
Execution Worker
  ├── Workspace Manager
  ├── Git Worktree Root
  ├── Docker Sandbox bind mount
  ├── Diff Watcher
  └── GitCommandExecutor
```

Worker、Worktree、Docker Daemon 和 Diff Watcher 位于同一受控执行节点。Worktree 是代码变更的文件事实源。Sandbox 只挂载当前 TaskExecution 的允许路径。

HarnessAgent 使用内置 `DockerFilesystemSpec`。开发、CI 和 MVP 验收统一运行该拓扑。本地进程只通过显式 `trusted-repository` Profile 启用。

### Kubernetes 拓扑

Kubernetes 进入 Team Beta 后续阶段，通过新实施任务完成：

- 专用 Execution Worker DaemonSet；
- 节点级 Worktree Root；
- Sandbox Pod 节点亲和性；
- SandboxExecutionGuard；
- Worker 与 Pod 共同使用的节点目录；
- 或者 RWX PVC 下重新设计锁、Watcher 和清理语义。

普通 API Pod 不创建交给任意节点挂载的本地 Worktree。

## 安全约束

- Sandbox 使用普通用户、只读镜像层、CPU/内存/PID/超时限制；
- 网络默认关闭，按任务域名、端口和协议开放；
- Agent 环境只获得 Task Token；
- Maven、测试和项目脚本在容器内运行；
- CI 与 MVP 验收禁用本地进程 Profile；
- Sandbox 退出后执行 Worktree、进程、挂载和网络对账。

## 结果

- Worktree 修改和 Diff Watcher 使用同一文件系统；
- MVP 避开跨节点 hostPath 调度风险；
- 本地开发、CI 和部署拥有一致隔离语义；
- Kubernetes 扩展拥有清晰前置条件。

## M0-S03 验证结果

2026-08-06 使用 AgentScope Java 2.0.0 完成 [M0-S03 验证](../spikes/M0-S03-DockerSandbox与Worktree验证记录.md)：

- `WorkspaceSpec + BindMountEntry` 将宿主临时 Git 仓库挂载到 `/workspace/repository:rw`；
- Harness `execute` Tool 在容器内修改 Java 文件并完成无网络 `mvn validate`；
- 宿主 Git Diff Watcher 并发观察到一致变更；
- Session 级 `PermissionMode.BYPASS` 只用于隔离 Fixture，生产 Shell 保持确认和审计；
- AgentScope 2.0.0 自管理容器固定等待 `docker stop --time=30`，单次测试约 33 秒。

M4 使用 TaskExecution 级 Sandbox 生命周期，避免为细粒度 Tool 频繁创建和停止容器。进入生产
Worker 前评估 AgentScope 升级、上游修复或可配置停止超时。

## M4-S01 验证结果

2026-08-16 完成 [M4-S01 AgentScope 原生 Coding 组合验证](../spikes/M4-S01-AgentScope原生Coding组合验证记录.md)：

- Plan Mode、Todo、Compaction、Interrupt/Resume 与受控 Tool 可在 Docker Sandbox 中组成完整读、改、测闭环；
- AgentScope 自管理 Docker Sandbox 在每次 Agent 调用结束后删除容器，恢复调用会重建容器；
- TaskExecution 级 Sandbox 由 CrewScope Worker 持有，Worktree 保存唯一代码事实；
- AgentScope external Sandbox 和 external SandboxState 路径绕过原生 `SandboxExecutionGuard`；
- CrewScope 在 external Sandbox 注入和每次受控 Tool 调用时复验 Workspace、Task Token、Lease 与 Fencing；
- 原生 Guard 只作为 AgentScope 自管理调用窗口能力，不能作为平台所有权证明。

## M4-S02 验证结果

2026-08-16 完成 [M4-S02 Git Worktree 与冷恢复协议验证](../spikes/M4-S02-Git-Worktree与冷恢复协议验证记录.md)：

- RepositoryBinding 通过稳定 Repository Key 解析受管裸仓库，宿主路径不进入浏览器、模型或 Tool 参数；
- Git 管理命令使用平台生成的类型化参数数组、固定环境、超时、输出上限和稳定错误分类；
- 一个 TaskExecution attempt 使用确定性 managed branch、Worktree 路径、Workspace 路径锁和 Fingerprint；
- 双 Worker 竞争同一 Workspace 时只有一个 Creator，另一个返回可退避的 Workspace Busy；
- 普通部分创建失败同步补偿回滚，进程骤停形成的可证明孤儿由启动对账清理；
- 未知目录、错误 HEAD/Branch、失效 `.git` 指针和符号链接越界失败关闭并保留现场；
- 归档通过 `commit-tree` 创建 Delivery Commit、固定 Archive Ref，并可在 `ARCHIVING` 中断后幂等完成清理。

## M4-S03 验证结果

2026-08-16 完成 [M4-S03 Diff Stream 与最终固化协议验证](../spikes/M4-S03-Diff-Stream与最终固化协议验证记录.md)：

- WatchService Event 作为低延迟触发提示，Git 周期与安全点 Reconcile 生成权威 DiffManifest；
- Diff Event 使用 RESET/DELTA、Stream Epoch、Sequence、Generation、Manifest Hash 和 HMAC Opaque Cursor；
- 丢失、重复和乱序 Delta 通过顺序 Replay 或 Reset 收敛，未来 Delta 不修改最后完整客户端投影；
- Java 与 TypeScript 使用同一版本化 JSON Fixture 和代码点路径排序；
- Patch Preview 截断后保留完整统计、Patch Hash 和 Artifact；
- Final DiffArtifact 从 Baseline/Delivery Commit 重新生成，Worktree 后续变化不修改已固化证据；
- 桌面与 390px 窄屏 Fixture 通过稳定截图、无横向溢出和 Axe 检查。

## 验证

1. Docker Sandbox 修改代码后宿主 Diff Watcher 能观察变更；
2. Worker 和 Sandbox 重启后 Worktree 可恢复；
3. 越界路径、禁止命令和网络访问被阻断；
4. Worktree 元数据损坏后可以检测、回滚或冷恢复；
5. CI 环境没有使用宿主 Shell 执行 Agent 命令。

## 重新评估条件

- MVP 需要多节点 Execution Worker；
- Worktree 容量超出单执行节点；
- 采用 AgentRun、E2B、Daytona 或其他远程 Sandbox；
- 团队决定在 MVP 内部署 Kubernetes Worker。
