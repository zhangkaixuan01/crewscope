# M4-D05 DiffArtifact 领域模型

> 完成日期：2026-08-17
> 范围：`crewscope-domain`、`crewscope-application`

## 1. 交付结果

M4-D05 将 M4-S03 冻结的 Git Diff 权威事实实现为不可变领域模型，并交付以下契约：

- `DiffPath`：canonical 仓库相对文件路径和 Unicode 代码点顺序；
- `DiffFileEntry`：变更类型、原路径、增删行、二进制标记、截断状态、完整单文件 Patch Hash 和有界 Preview；
- `DiffManifest`：排序后的文件集合、聚合统计、Content Hash 和单调 Generation；
- `PatchArtifactReference`：完整 Patch 在 ArtifactStore 中的 ID、字节数、Content Type 和 SHA-256；
- `DiffArtifact`：从精确 Baseline Commit 与 Delivery Commit 发布的最终不可变交付制品；
- `DiffArtifactRepository`：每个 ExecutionWorkspace 唯一最终制品和完整 WorkProject Scope 查询 Port。

## 2. DiffManifest 权威边界

Git Reconciler 向领域层提交已经解析的 `DiffFileEntry`。领域模型执行以下闭合：

1. 路径必须是 canonical 仓库相对文件路径，拒绝绝对路径、反斜杠、空段、`.`、`..`、NUL 和控制字符；
2. `RENAMED/COPIED` 必须提供不同的 `oldPath`，其他变更类型不接受 `oldPath`；
3. 增删行必须非负，二进制变更固定使用零行统计且不提供文本 Preview；
4. 非截断且提供完整 Preview 时，重新计算并验证单文件 Patch SHA-256；
5. Preview 同时受 UTF-8 字节数和行数上限约束，截断不改变完整 Patch Hash 与行统计；
6. 当前路径在一个 Manifest 内唯一，文件按 Unicode 代码点逐个比较排序；
7. 文件数最多为 10,000，增删行聚合使用溢出检查；
8. `files()` 返回不可修改快照。

Manifest Content Hash 使用 `diff-manifest-v1` 版本前缀和长度前缀编码，覆盖排序后的以下权威事实：

```text
path
oldPath
kind
additions
deletions
binary
patchTruncated
patchSha256
```

Generation 和有界 `patchPreview` 不进入 Content Hash。Preview 字节变化不会制造新的 Git 权威代次，完整 Patch Hash 或其他权威事实变化会创建直接下一代。

## 3. Generation 规则

`DiffGeneration` 从 1 开始且严格为正数。`DiffManifest.reconcile` 按 Content Hash 裁决：

- Hash 相同：返回原 Manifest，不增加代次；
- Hash 变化：创建 `generation + 1` 的新 Manifest；
- `Long.MAX_VALUE`：失败关闭，不允许回绕；
- 持久化重建：重新计算文件数、增删行与 Content Hash，拒绝篡改。

## 4. 最终 DiffArtifact

`DiffArtifact.publishFinal` 只接受 `FINALIZING` 状态的 ExecutionWorkspace，并完成以下校验：

1. CodingTargetSnapshot 与 Workspace 的 Organization、Team、Workspace、WorkProject、Task、Snapshot Reference 和 Baseline Commit 完全一致；
2. Manifest 的当前路径和 Rename/Copy 原路径全部位于 CodingTargetSnapshot AllowedPaths；
3. 空 Manifest 必须引用空 Patch，非空 Manifest 必须引用非空 Patch；
4. 操作主体是完整 Scope 内的有效 Principal；
5. Scope、TaskExecution、attempt、ExecutionWorkspace、CodingTarget、Baseline Commit、Delivery Commit、Manifest、Patch Artifact 和审计创建事实一起固化。

最终 Hash 按 M4-S03 冻结顺序闭合：

```text
executionWorkspaceId
baselineCommit
deliveryCommit
diffGeneration
manifestHash
patchArtifactHash
```

`DiffArtifact.reconstitute` 重新计算最终 Hash。Baseline、Delivery、Generation、Manifest Hash、Patch Hash 或 Workspace ID 被修改时重建失败。聚合不提供状态修改方法，发布后的最终制品保持终态不可变。

## 5. Patch Artifact

完整 Patch 使用逻辑 ArtifactStore 引用：

```text
contentType = text/x-diff;charset=utf-8
artifactId
sizeBytes
patchSha256
```

零字节 Patch 必须使用空 UTF-8 内容的 SHA-256。领域模型不保存 Patch 大文本、宿主路径或 Worktree 文件系统信息。M4-I08 Finalizer 负责写入 ArtifactStore、复验实际字节数与 Hash，再原子创建 DiffArtifact。

## 6. Repository Port

`DiffArtifactRepository` 提供：

- 原子 `create`，数据库适配器必须以 ExecutionWorkspace 唯一约束裁决并发双发布；
- 按 DiffArtifact ID 查询；
- 按 ExecutionWorkspace 查询；
- 按 TaskExecution 查询；
- 所有查询显式携带 Organization、Team 和 WorkProject，结果按完整 Scope 隔离。

第二个最终制品使用稳定错误码 `diff_artifact_workspace_conflict`。M4-D08 在数据库层实现唯一约束，M4-D09 实现 PostgreSQL Adapter。

## 7. 测试证据

Domain 专项测试共 15 个，覆盖：

- Add/Modify/Delete/TypeChange/Rename/Copy 的原路径规则；
- canonical 路径、逃逸拒绝和 AllowedPaths 判断；
- Unicode 代码点排序与当前路径唯一性；
- 行统计、二进制、完整 Preview Hash、截断 Preview 和 Preview 上限；
- 固定 Fixture 的 5 文件、49 增加行和 3 删除行；
- 输入顺序无关 Hash、Preview 排除规则和不可修改文件集合；
- Generation 无变化复用、内容变化递增、上限失败关闭；
- Manifest 统计与 Hash 防篡改、空 Patch Hash；
- Workspace/Target/Baseline/Delivery/AllowedPaths/Patch 空性闭合；
- 最终 Hash 重建、防篡改与终态不可变。

Application 专项测试共 2 个，覆盖 Workspace 唯一发布冲突、稳定错误码、完整 Scope 隔离和 TaskExecution 查询。

验证命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-domain,crewscope-application -am test
```

## 8. 后续边界

M4-D06 交付 CommandEvidence、TestEvidence 和 AcceptanceResult。M4-D08/M4-D09 交付 V14 表结构与 PostgreSQL Adapter。M4-I08 交付 Watcher、Git Reconciler、Diff Event Store 和最终 DiffArtifact Finalizer。
