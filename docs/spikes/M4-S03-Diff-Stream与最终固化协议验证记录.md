# M4-S03 Diff Stream 与最终固化协议验证记录

> 验证对象：Java NIO WatchService、Git `2.50.1`（Apple Git-155）、Vue 3、Vitest、Playwright Chromium<br>
> CrewScope 模块：`crewscope-infrastructure`、`crewscope-web`<br>
> 验证日期：2026-08-16

## 1. 验证结论

CrewScope 使用 WatchService 触发提示、Git 权威 Reconcile、耐久 Diff Event、HMAC 不透明 Cursor 和前端顺序投影，可以在文件事件丢失、重复和乱序后收敛到 Git 权威结果。

验证闭环为：

```text
Worktree 文件变化
  -> WatchService Hint
  -> Debounce / Coalesce
  -> Git Reconcile
  -> DiffManifest + Content Hash
  -> RESET / DELTA Event
  -> Opaque Resume Cursor
  -> Browser DiffProjection
  -> Replay 或 RESET 修复缺口
  -> Delivery Commit
  -> Final DiffManifest + Full Patch Artifact
```

WatchService Event 只负责缩短刷新延迟。Git Reconcile 负责生成服务端事实。实时 Patch Preview 可以截断，文件统计、完整 Patch Hash 和 Artifact 保持完整。TaskExecution 终结时从精确 Baseline Commit 与 Delivery Commit 重新生成最终 Diff，最终制品不再受后续 Worktree 变化影响。

## 2. Git Fixture

临时仓库基线包含：

```text
README.md
obsolete.txt
src/Greeting.java
```

最终变更包含：

```text
README.md -> docs/README.md       RENAMED
docs/large.txt                    ADDED，40 行，Preview 截断
obsolete.txt                      DELETED，1 行
src/Feature.java                  ADDED，7 行
src/Greeting.java                 MODIFIED，+2/-2
```

最终权威统计为：

```text
files = 5
additions = 49
deletions = 3
```

## 3. WatchService 边界

WatchService 提供以下触发提示：

- `ENTRY_CREATE`；
- `ENTRY_MODIFY`；
- `ENTRY_DELETE`；
- `OVERFLOW`；
- WatchKey 失效；
- Watcher 重启。

生产 Workspace Diff Watcher 递归注册 AllowedPaths 内的目录。新目录创建后立即注册该目录及已有子目录。事件路径先执行 lexical containment，再在 Reconcile 中执行 canonical/symlink 与 AllowedPaths 校验。

事件处理规则：

1. 同一 Workspace 的高频提示进入短 Debounce 窗口；
2. 提示按 Workspace 合并，不按单文件形成领域事实；
3. `OVERFLOW`、WatchKey 失效和 Watcher 重启直接请求完整 Reconcile；
4. 周期 Reconcile 独立于 WatchService 持续运行；
5. Agent Tool 完成、Checkpoint、Pause、Resume、Finalizing 前执行额外 Reconcile；
6. WatchService 原始事件不进入客户端协议和最终 Audit。

Java Fixture 使用真实 WatchService 观察 `src/Greeting.java` 修改，并通过 Git Reconcile 得到唯一 `MODIFIED` 文件事实。

## 4. Git Reconcile 协议

Git Reconciler 固定使用平台生成的参数数组：

```text
git diff --name-status -z --find-renames <baseline> [<delivery>] --
git diff --binary --no-ext-diff --find-renames --unified=3 <baseline> [<delivery>] -- <paths>
```

实时模式比较 `baseline -> current index/worktree`。Finalizer 比较 `baseline -> delivery commit`。

每个 `DiffFileEntry` 包含：

| 字段 | 语义 |
|---|---|
| `path` | 当前规范相对路径 |
| `oldPath` | Rename/Copy 的原路径 |
| `kind` | ADDED/MODIFIED/DELETED/RENAMED/COPIED/TYPE_CHANGED |
| `additions/deletions` | Git Patch 权威行数统计 |
| `binary` | 二进制标记 |
| `patchTruncated` | 实时预览截断标记 |
| `patchSha256` | 完整单文件 Patch SHA-256 |
| `patchPreview` | 有界实时预览 |

Manifest 文件按 Unicode 代码点逐个比较排序。Java 使用 `codePointAt` 与 `Character.charCount`，浏览器使用 `codePointAt` 迭代完整字符串；两端不使用 Java/JavaScript 的 UTF-16 默认字符串顺序，也不使用 `localeCompare`。补充字符与 BMP 字符的顺序探针固定验证 `U+E000 < U+10000`。

事件路径拒绝绝对路径、反斜杠、空段、`.`、`..`、NUL 和控制字符。单个事件中的 Upsert Path、Removal Path 分别唯一，且二者集合不能相交。Change Kind、统计、布尔字段、Patch Hash 与 Preview 类型在进入投影前全部校验。

Manifest Content Hash 只覆盖排序后的文件事实，不覆盖 Generation。周期 Reconcile 得到相同 Content Hash 时不创建新 Generation 和 Event。

## 5. Diff Generation 与 Event

每次权威内容变化创建单调递增 `DiffGeneration`。实时事件分为：

```text
RESET
  -> 当前完整 DiffManifest

DELTA
  -> upsert DiffFileEntry[]
  -> removedPath[]
```

事件信封固定包含：

- Organization/Team/ExecutionWorkspace Scope；
- `streamEpoch`；
- `sequence`；
- `generation`；
- `eventId`；
- `kind`；
- `cursor`；
- `manifestHash`；
- `files/removals`。

同一 Stream Epoch 的 Sequence 严格递增。Generation 只在权威 Diff 内容变化时递增。Event Store 提供按 Cursor 的有界 Replay。

## 6. Opaque Cursor

Resume Cursor 编码以下服务端事实：

```text
schemaVersion
executionWorkspaceId
streamEpoch
sequence
generation
HMAC-SHA256
```

浏览器只保存并回传 Cursor，不解析字段。服务端解码后验证 HMAC、Workspace、Stream Epoch、Sequence 和 Generation。篡改、跨 Workspace、跨 Epoch 和非法 Base64URL 返回稳定 `INVALID_DIFF_CURSOR`。

Stream Epoch 在 Event Store 重建、保留窗口切换或 Workspace 恢复代次需要断开旧序列时更新。旧 Epoch Cursor 触发 RESET，不跨 Epoch 拼接 Delta。

## 7. 前端投影算法

前端 `DiffProjection` 按以下规则处理事件：

```text
event.sequence <= current.sequence
  -> DUPLICATE，忽略

DELTA.sequence == current.sequence + 1
  -> 应用 Upsert/Remove

DELTA.sequence > current.sequence + 1
  -> GAP，保留最后完整投影
  -> 使用 current.cursor 请求 Replay

缺失 Delta Replay 到达
  -> 顺序应用并继续实时流

Replay 不可用或服务端要求 Reset
  -> 应用 RESET 完整替换投影
```

未来 Delta 不会提前修改文件列表、Generation、Manifest Hash 或 Cursor。Reset 可以跨 Sequence 缺口，应用后清除 Gap 状态。

共享 Fixture 的交付顺序为：

```text
RESET(1)
  -> DELTA(3)       GAP
  -> DELTA(2)       APPLIED
  -> DELTA(2)       DUPLICATE
  -> DELTA(3)       APPLIED
  -> RESET(4)       RESET
```

Java 与 TypeScript 使用同一 JSON Fixture，最终投影均为 5 个文件、Generation 3 和相同 Manifest Hash。

## 8. Reset 触发条件

以下状态发布或要求 RESET：

- 首次订阅；
- Cursor 早于 Event Store 保留窗口；
- Stream Epoch 变化；
- WatchService `OVERFLOW` 后完整 Reconcile；
- Watcher/Worker 冷启动；
- 服务端投影 Checkpoint 与 Git Content Hash 不一致；
- 客户端报告 Sequence Gap 且缺失 Event 无法 Replay；
- Baseline、Delivery 或 Workspace Recovery Generation 变化；
- 运维执行显式 Reconcile/Reset。

Reset 携带完整有界 Manifest。大 Patch 继续使用 Artifact 引用，不把完整 Patch 放入事件。

## 9. Patch 截断协议

Fixture 的实时限制为：

```text
patchPreviewBytes = 2048
patchPreviewLines = 20
```

生产限制来自 WorkspacePolicy，并同时约束：

- 单文件 Preview 字节数和行数；
- 单 Event 文件数和总字节数；
- 单 Generation 文件数和累计 Patch 大小；
- 二进制、超大文件和敏感路径摘要。

截断只作用于 `patchPreview`。以下事实保持完整：

- Change Kind；
- additions/deletions；
- binary；
- 完整 Patch SHA-256；
- Full Patch Artifact 引用；
- Manifest Content Hash。

Fixture 验证 `docs/large.txt` 的预览不超过 2048 字节和 20 行，统计保持 `+40/-0`，完整 Artifact 包含 `line-40`，单文件与完整 Artifact Hash 均可复算。

## 10. 最终 Diff 固化

实时 Diff 是可恢复观察投影。最终交付事实从精确 Commit 对重新计算：

```text
获取 Workspace/Lease/Fencing 锁
  -> 固定 baselineCommit
  -> 创建并固定 deliveryCommit
  -> git diff baselineCommit deliveryCommit
  -> 生成完整排序 DiffManifest
  -> 写入 Full Patch Artifact
  -> 复算文件 Hash、Manifest Hash、Artifact Hash
  -> 再次复验 Workspace/Lease/Fencing/Commit
  -> 原子发布 FINAL DiffArtifact
```

最终 Hash 闭合：

```text
executionWorkspaceId
baselineCommit
deliveryCommit
diffGeneration
manifestHash
patchArtifactHash
```

Final DiffArtifact 发布后不可修改。后续 Worktree 变化进入新的 Attempt/Generation，不改变既有最终制品。Fixture 在 Finalize 后覆盖 `Greeting.java`，当前 Git Diff 随之变化，已固化的 5 文件 Manifest、Patch Artifact 和 Final Hash 保持不变。

## 11. 桌面与窄屏 Fixture

M4-S03 提供独立开发 Fixture 页面：

```text
crewscope-web/m4-s03-fixture.html
```

页面展示：

- Generation 与同步状态；
- 文件、增加行、删除行和流恢复统计；
- Rename、Add、Delete、Modify 状态；
- Patch Preview 与截断提示；
- Manifest/Patch Hash 安全摘要。

Playwright 在 1440×960 桌面和 390×844 窄屏执行相同 Replay，验证无水平溢出、Axe 无违规和截图稳定。Fixture 沿用 CrewScope 浅绿色 Token，仅作为 M4-F05 Execution Studio 的协议与布局输入。

## 12. 自动化证据

后端测试：

```text
crewscope-infrastructure/src/test/java/io/crewscope/infrastructure/workspace/
  WorkspaceDiffM4S03IntegrationTest.java
```

共享事件 Fixture 与前端投影：

```text
crewscope-web/src/spikes/m4/fixtures/diff-stream-v1.json
crewscope-web/src/spikes/m4/diffProjection.ts
crewscope-web/src/spikes/m4/diffProjection.spec.ts
crewscope-web/src/spikes/m4/DiffStreamFixture.vue
```

验证结果：

```text
Java M4-S03: 7 passed
Vitest: 184 passed（M4-S03 新增 4）
Playwright M4-S03: 2 passed
Vue Type Check: passed
Vite Build: passed
Axe: 0 violations
```

## 13. 冻结决策

M4-S03 冻结以下决策：

1. WatchService Event 是 Reconcile 触发提示，Git Diff 是权威事实；
2. 周期、关键安全点和最终化 Reconcile 独立于 WatchService 运行；
3. Diff Generation 只在 Manifest Content Hash 变化时递增；
4. Event Store 使用 RESET/DELTA、Stream Epoch、Sequence 和不透明 Cursor；
5. 客户端遇到 Gap 时保留最后完整投影，通过 Replay 或 Reset 收敛；
6. Java 与 TypeScript 显式迭代 Unicode 代码点排序并使用同一版本化 Fixture；
7. Patch Preview 不进入 Manifest Content Hash；截断不影响统计、完整 Patch Hash 和 Artifact；
8. 最终 Diff 从 Baseline Commit 与 Delivery Commit 重新生成并保持不可变；
9. M4-D05 已实现 DiffArtifact/Manifest/Generation 和最终 Hash，验证见 [M4-D05 DiffArtifact 领域模型](../testing/M4-D05-DiffArtifact领域模型.md)；M4-I08 已实现 Watcher/Reconciler/Event Store/Finalizer，验证见 [M4-I08 Workspace Diff 与最终 DiffArtifact](../testing/M4-I08-Workspace-Diff与最终DiffArtifact.md)；
10. M4-A05 与 M4-F05 复用本记录的 Cursor、Reset、投影和响应式 Fixture 契约。
