# M0-I01：ArtifactStore 契约基线

> 日期：2026-08-07<br>
> 状态：已完成

## 目标

为 RuntimeArtifact、DiffArtifact、测试日志、AgentStateSnapshot 和 Sandbox Snapshot 建立统一的流式存储边界。M0-I01 定义 Port 和元数据规则，M0-I02 提供 Filesystem 实现。

## Port

```text
put(ArtifactWriteRequest, InputStream) -> ArtifactDescriptor
head(ArtifactId, ArtifactAccessContext) -> Optional<ArtifactDescriptor>
get(ArtifactId, ArtifactAccessContext) -> Optional<ArtifactContent>
tombstone(ArtifactId, ArtifactMutationContext, reason, detail) -> Optional<ArtifactTombstone>
purgeTombstoned(ArtifactPurgeRequest) -> ArtifactId[]
```

`put` 由调用方管理输入流。`get` 返回一次性 `ArtifactContent`，调用方通过 `close()` 释放读取流。

## 写入与完整性

`ArtifactWriteRequest` 包含：

- 稳定 `ArtifactId`；
- Organization、可选 Team、可选 Workspace；
- Content Type、声明大小、预期 SHA-256；
- DataClassification、Visibility；
- 可选正 TTL；
- Producer Principal、TaskExecution、StepExecution、AgentRun 和 W3C Trace ID。

SHA-256 统一为 64 位小写十六进制。Store 以流式方式计算实际大小和哈希，校验成功后原子发布。相同 Artifact ID 与相同请求保持幂等，不同内容或元数据返回 `CONFLICT`。

Storage URI 使用无用户信息、查询参数和 Fragment 的稳定绝对 URI，避免把临时签名和凭证写入 Descriptor。

## 分类与读取范围

DataClassification：

```text
PUBLIC | INTERNAL | CONFIDENTIAL | RESTRICTED
```

Visibility：

| Visibility | 读取范围 |
|---|---|
| `PRIVATE` | Producer Principal |
| `WORKSPACE` | 当前 Principal 已授权 Workspace |
| `TEAM` | 当前 Principal 已授权 Team |
| `ORGANIZATION` | 同一 Organization |

数据分类表达敏感程度，可见性表达访问范围。任何分类的 Artifact 都不保存凭证明文。

## TTL 与 Tombstone

- TTL 最小精度为一微秒，从 Store 持久化 `createdAt` 时开始计算；
- 空 TTL 表示持续保留；
- 到达 `retentionUntil` 的时刻开始过期；
- 过期或已有 Tombstone 的对象停止内容读取；
- Head 对已授权调用者保留生命周期元数据；
- Tombstone 保存稳定原因、规范化安全说明、操作 Principal 和 UTC 时间；
- 相同原因与说明的 Tombstone 重试保持幂等，不同请求返回 `CONFLICT`；
- 物理清理同时要求 Tombstone 已生效且保留期结束；
- `ArtifactPurgeRequest` 将批量大小限制为 `1–1000`，清理结果返回实际删除的 Artifact ID。

## 安全错误

`ArtifactStoreException` 只携带安全消息和稳定分类：

```text
INTEGRITY_VIOLATION
CONFLICT
ACCESS_DENIED
STORAGE_FAILURE
```

Artifact 缺失或调用者无读取范围时，`head/get` 使用空结果隐藏对象存在性。

## 验证

`ArtifactStoreContractTest` 的 12 个单元测试覆盖：

1. SHA-256 计算、大小写规范化和非法摘要；
2. Scope 与 Visibility 坐标约束；
3. Content Type、声明大小和 TTL 边界；
4. Producer 执行 ID 和 W3C Trace ID；
5. TTL 截止时间与幂等 Descriptor 比较；
6. Private、Workspace、Team、Organization 读取矩阵；
7. 授权集合不可变；
8. 过期边界和 Tombstone 内容阻断；
9. Tombstone 与保留期共同控制物理清理；
10. Tombstone 说明规范化和幂等比较；
11. Storage URI、生命周期顺序和安全错误；
12. 物理清理批量范围。

定向验证命令：

```bash
./mvnw --batch-mode --no-transfer-progress -pl crewscope-application -am test
```

结果：Domain 26 个测试、Application 24 个测试全部通过，其中 I01 新增 12 个契约测试。

全仓验证命令：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

结果：7 个 Maven 模块成功，100 个测试全部通过。

## 后续

M0-I02 实现 `FilesystemArtifactStore`，覆盖临时文件、流式大小与 SHA-256 校验、原子移动、同 ID 幂等、授权读取、TTL、Tombstone 和批量清理。
