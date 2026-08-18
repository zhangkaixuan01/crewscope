# M4-D04 WorkspacePolicy 领域模型

> 完成日期：2026-08-17
> 范围：`crewscope-domain`、`crewscope-application`

## 1. 交付结果

M4-D04 为每个 Coding TaskExecution attempt 建立不可变、可复验的 WorkspacePolicy，并交付以下领域契约：

- `AllowedPathSet`：复用 CodingTargetSnapshot 的 canonical 仓库相对路径语义；
- `BuildProfile`：固化 Key、Version、BuildTool、Java Release、摘要固定 Sandbox 镜像、CommandCatalog 和 canonical SHA-256；
- `BuildCommand` 与 `CommandCatalog`：使用 typed argv、固定入口、工作目录、超时与有界模块/测试选择器，CommandKind 和 Tool Key 唯一；
- `SandboxResourceBudget`：固化网络、CPU、内存、PID、命令时长、输出字节与只读根文件系统；
- `WorkspaceOperationBudget`：固化命令、文件、写入、Diff 和测试修复轮次上限；
- `WorkspacePolicyOverlay`：形成只能缩小路径、删除命令和降低预算的单调版本流；
- `BuildProfileCatalog`、`WorkspacePolicyRepository`、`WorkspacePolicyOverlayRepository`：提供精确版本、完整 Scope 与 Overlay compare-and-set Port。

## 2. 闭合关系

WorkspacePolicy 创建时完成以下一致性校验：

1. CodingTargetSnapshot、TaskExecution 与 PolicySnapshot 共享 Organization、Team、Workspace、WorkProject、Task 和 TaskExecution 谱系；
2. TaskExecution PlanningContext 指向同一个 PolicySnapshot ID 与 Hash；
3. BuildProfile Reference 与 CodingTargetSnapshot 中固化的引用完全一致；
4. AllowedPathSet 是 CodingTargetSnapshot AllowedPaths 的子集；
5. PolicySnapshot 允许 `SANDBOX`、`WORKTREE` 及 CommandCatalog 的全部 Tool Key；
6. Command 超时受 SandboxResourceBudget 与 PolicyBudget 总时长约束；
7. 命令调用与写操作总数受 PolicyBudget Tool 调用次数约束；
8. M4 Sandbox 使用 `network=none` 和只读根文件系统。

WorkspacePolicy 保存独立 canonical SHA-256。持久化重建重新计算 Hash，并拒绝事实篡改。

## 3. 受控命令

BuildCommand 只接受参数数组。受控入口为：

- `mvn`；
- `./mvnw`；
- `./gradlew`；
- `./scripts/` 下的 canonical 项目脚本。

参数必须是有界、非空、单行值。工作目录必须是 canonical 仓库相对路径。CommandSelectorPolicy 固化模块白名单、模块选择器数量、精确测试类/方法选择器数量和单值长度。BuildProfile 至少包含一个命令；WorkspacePolicyOverlay 可以将目录收紧为空，从而停止后续命令执行。

Tool Key 支持 `command.mavenTest`、`command.mavenVerify` 这类 lowerCamel 分段，首字符保持小写。BuildProfile Key 保持全小写稳定格式。

## 4. 预算模型

固定 Coding V1 基准可以表达为：

| 类别 | 基准值 |
|---|---:|
| Network | `NONE` |
| CPU | 2 |
| Memory | 2048 MiB |
| PIDs | 256 |
| Command duration | 900 秒 |
| Command calls | 12 |
| Write operations | 80 |
| Written bytes | 1,048,576 |
| Diff bytes | 524,288 |
| Test repair rounds | 3 |

预算值对象提供逐字段 `isNoBroaderThan` 比较，Overlay 必须在每个维度保持或降低限制。

## 5. Overlay 单调性

WorkspacePolicyOverlay 首版本完整继承 WorkspacePolicy。后继版本满足：

- AllowedPathSet 仅缩小；
- CommandCatalog 仅删除原有且内容未变化的 CommandKind；
- Network 权限、CPU、内存、PID、超时与输出上限仅收紧；
- 命令、文件、写入、Diff 和测试修复预算仅收紧；
- 至少一个维度发生有效变化；
- Version 连续递增并保存直接父 Overlay Hash；
- Base WorkspacePolicy ID/Hash 保持一致；
- 重建时复验 Base Policy 和 Overlay Hash。

SafetyEnforcementOverlay 负责平台通用实时撤权，WorkspacePolicyOverlay 负责 Coding Workspace 专属限制。两者在执行前共同参与有效策略求交。

## 6. AgentScope 2.0 映射

AgentScope `DockerFilesystemSpec` 原生提供 Image、WorkspaceRoot、Environment、Memory、CPU、Network 和 AdditionalRunArgs。CrewScope 在 M4-I04 根据 WorkspacePolicy 生成固定 Docker 参数，将 PID 与只读根文件系统映射为平台控制的参数。模型、API 和 BuildProfile 均不接收 raw AdditionalRunArgs。

## 7. 测试证据

领域专项测试共 16 个：

- AllowedPathSet 规范化、包含关系和路径逃逸；
- typed argv、入口白名单、项目脚本、工作目录与有界模块/测试选择器；
- CommandKind/Tool Key 唯一、稳定 Hash 和命令删除；
- OCI 镜像 Digest 与 BuildProfile Hash 防篡改；
- 网络、CPU、内存、PID、超时、输出、文件、写入、Diff 和修复轮次；
- CodingTarget、TaskExecution、PolicySnapshot 与 BuildProfile 闭合；
- Overlay 路径、命令、预算收紧、空命令目录、No-op 与扩权拒绝；
- Overlay 父 Hash、Base Policy 和持久化篡改拒绝。

Application 专项测试共 2 个：

- BuildProfileCatalog 精确版本与 Hash 查询；
- Repository 完整 Scope、精确 Overlay 版本和 compare-and-set 方法契约。

验证命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-domain,crewscope-application -am test
```

## 8. 后续边界

M4-D05 交付 DiffArtifact 与 DiffManifest。M4-D08 和 M4-D09 交付 V14 表结构与 PostgreSQL Adapter。M4-I04 至 M4-I07 交付 Docker Factory、受控文件工具、Git 工具和 BuildProfile Runner。
