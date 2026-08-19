# M4-I01 类型化 GitCommandExecutor

> 完成日期：2026-08-18
> 范围：`crewscope-infrastructure`

## 1. 交付结果

M4-I01 将宿主 Git 进程收敛为 `GitCommandExecutor`，为后续 Repository Preflight、Worktree Provision、Diff Finalize 和本地归档提供单一受控执行边界。

公开 API 不接受原始命令字符串或任意参数集合，只提供以下类型化操作：

- `resolveBranch`：将服务端 `RepositoryBranchName` 解析为完整 `RepositoryCommitId`；
- `isBareRepository`、`verifyCommit`：为 M4-I02 提供仓库类型与完整 Commit 存在性验证；
- `addWorktree`、`removeWorktree`：使用 `ManagedWorkspaceBranch`、完整基线 Commit 和绝对路径管理 Worktree；
- `status`、`diff`、`log`、`show`：执行固定只读模板；
- `stageAll`、`writeTree`、`commitTree`：使用完整对象 ID 和有界平台消息创建 Delivery Commit，不移动活动 Branch；
- `createArchiveReference`、`deleteManagedBranch`：只操作平台生成的 Archive Ref 和 Managed Branch。

`GitTreeId` 与 `GitCommitMessage` 补充了 Tree Hash 和提交消息边界。Repository、Worktree 的受管根目录解析、符号链接与所有权证明属于 M4-I02/I03，不在 Executor 内重复实现。

## 2. 进程安全边界

每次调用均由平台固定命令模板生成参数数组并直接交给 `ProcessBuilder`，不经过 Shell。执行环境执行白名单重建：

```text
HOME = Worker 专用 command-home
GIT_CONFIG_NOSYSTEM = 1
GIT_CONFIG_GLOBAL = /dev/null
GIT_TERMINAL_PROMPT = 0
GIT_PAGER / PAGER = cat
LC_ALL / LANG = C
author / committer = CrewScope Delivery 固定身份
core.hooksPath = /dev/null
```

只从 Worker 环境保留 `PATH` 以解析受控 Git 可执行文件，其他变量不继承。Repository Hook 被命令级配置关闭，Worktree 创建不会在宿主 Worker 执行仓库 Hook。Diff 固定关闭 external diff 与 textconv。

`GitCommandPolicy` 对每次进程施加 `0 < timeout <= 5m` 和 `1 KiB <= output <= 16 MiB` 的启动期约束。输出由独立有界读取器持续消费，达到上限立即终止完整进程树；超时、读取异常和线程中断同样执行进程树清理。失败异常不携带 Git 原始输出、Ref、仓库路径或 Worktree 路径。

## 3. 稳定错误分类

| 分类 | 含义 |
|---|---|
| `NOT_A_REPOSITORY` | 目标不是 Git Repository |
| `INVALID_REFERENCE` | Branch、Commit、Object 或路径对象不存在或无效 |
| `CONFLICT` | Branch、Worktree 或 Ref 与已有事实冲突 |
| `TIMEOUT` | 命令超过固定时限并已终止 |
| `OUTPUT_LIMIT` | 合并输出达到上限并已终止 |
| `COMMAND_FAILED` | 启动、输入、读取、中断或未分类 Git 失败 |

业务调用方只能读取稳定分类、安全摘要和可选 Exit Code。Git 原始错误正文只用于 Executor 内部分类，不向上层、DTO 或日志传播。

## 4. Spring 装配

`GitCommandConfiguration` 通过 `@EnableConfigurationProperties` 装配一个 `GitCommandExecutor`，并在部署显式提供替代 Bean 时退让。配置前缀为：

```yaml
crewscope:
  coding:
    git:
      command-home: ./var/crewscope/git-home
      timeout: 30s
      maximum-output-bytes: 1048576
```

`GitCommandProperties` 使用 Spring Boot Binder 解析 Duration 与数值；不安全配置在 Bean 创建阶段失败关闭。

## 5. 自动化证据

专项命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  -Dtest=GitCommandExecutorM4I01IntegrationTest,GitCommandConfigurationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

10 个场景全部通过：

1. 真实临时仓库覆盖 Branch Resolve、Worktree、Status、Diff、Log、Show、Write Tree、Commit Tree、Archive Ref 与清理；
2. 非仓库、无效 Ref 和 Worktree 冲突映射为稳定错误；
3. Ref、Commit、相对路径值对象拒绝 Option 与 Traversal 注入；
4. 路径中的 Shell 元字符保持普通字符，不创建额外文件；
5. Repository `post-checkout` Hook 不在宿主执行；
6. 固定环境不继承 `JAVA_HOME` 等无关变量；
7. 超时进程树在预算内终止；
8. 输出洪泛达到上限后终止且正文不进入异常；
9. Policy、Tree ID 和 Commit Message 非法输入失败关闭；
10. Spring 属性绑定、非法配置拒绝和自定义 Bean 退让通过。

模块回归命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am test
```

Domain `409/409`、Application `265/265`、Infrastructure `285/285`，合计 `959/959` 通过。类型化 API 与进程治理完成职责拆分后，M4-I01 专项再次以 `10/10` 通过。Server YAML 解析专项 `2/2`、M4 固定评测 `12 tasks / 2 tracks`、156 份 Markdown 文档链接与 `git diff --check` 同步通过。

## 6. 后续边界

M4-I02 已基于本 Executor 实现 `ManagedRepositoryResolver` 与 Baseline Preflight，负责 Managed Root containment、逐段符号链接检查、裸仓库/所有权校验、RepositoryBinding 状态、Branch 漂移和完整 Commit 闭合。M4-I03 已组合数据库 Workspace 事实、路径锁和本 Executor，实现可恢复 Worktree 生命周期、物理 Fingerprint 与本地交付归档。
