# M0-I02：FilesystemArtifactStore 基线

> 日期：2026-08-07<br>
> 状态：已完成

## 目标

为 `ArtifactStore` Port 提供可重启、可校验、可并发使用的本地文件系统实现，用于开发环境与后续 AgentStateSnapshot、Sandbox Snapshot 和 RuntimeArtifact 接入。

## 存储布局

```text
objects/sha256/{hash-prefix}/{sha256}.blob
references/{artifact-id-prefix}/{artifactId}.json
temporary/{random}.part
locks/{lock-type}/{lock-key}.lock
```

- Blob 按 SHA-256 内容寻址，多个 Artifact 可共享同一 Blob；
- JSON Sidecar 保存独立的 Scope、Producer、Visibility、TTL 和 Tombstone；
- Sidecar 带 `schemaVersion`，路径中的 Artifact ID 必须与文档内 ID 一致；
- `storageUri` 必须等于根据内容哈希推导的绝对 `file` URI。

## 原子性与并发

写入按以下顺序执行：

```text
Artifact ID 条带锁 + 文件锁
  -> temporary 流式写入
  -> 声明大小与 SHA-256 校验
  -> Content Hash 条带锁 + 文件锁
  -> fsync
  -> ATOMIC_MOVE Blob
  -> ATOMIC_MOVE Sidecar
```

同 Artifact ID 的相同请求幂等返回原 Descriptor，不重复消费输入流。同 ID 不同元数据或内容返回 `CONFLICT`。失败写入清理临时文件，清理异常作为 suppressed exception 保留原始失败。

## 读取与生命周期

- `head/get` 先执行 Organization 隔离和 Visibility 授权；
- `get` 在 TTL 边界开始过期，Tombstone 生效后停止返回内容；
- Blob 使用同一 `FileChannel` 完成大小/SHA-256 校验、回卷和读取；
- `ArtifactContent.close()` 关闭底层文件句柄；
- Tombstone 保持幂等，跨 Organization 变更返回 `ACCESS_DENIED`；
- 物理清理同时满足 Tombstone 和保留截止时间，共享 Blob 在最后一个引用删除后才删除。

## 配置

```yaml
crewscope:
  artifact:
    filesystem:
      root: ${CREWSCOPE_ARTIFACT_ROOT:./var/crewscope/artifacts}
```

配置值在 Store 初始化时转换为规范绝对路径。生产对象存储将通过同一 Port 提供 S3/MinIO 实现。

## 验证

`FilesystemArtifactStoreIntegrationTest` 的 12 个真实文件系统集成测试覆盖：

1. 流式写入、调用方输入流所有权、Sidecar 和内容读取；
2. 大小/哈希不匹配与临时文件清理；
3. 同 ID 幂等和元数据冲突；
4. 同 ID 并发写入；
5. Store 重启后元数据与内容恢复；
6. Private、Workspace 和 Organization 授权与跨组织隐藏；
7. TTL 精确边界；
8. Tombstone 幂等、冲突和跨组织拒绝；
9. Blob 和 Sidecar 损坏检测；
10. Sidecar 路径与 Artifact ID 不一致检测；
11. 批量上限和共享 Blob 引用清理；
12. Tombstone 生效后等待 `retentionUntil` 才物理清理。

定向验证命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  -Dtest=FilesystemArtifactStoreIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：12 个集成测试全部通过。

全仓验证命令：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

结果：7 个 Maven 模块成功，112 个测试全部通过。

## 后续

M0-I03 定义 `CredentialStore` Port，实现 AES-256-GCM `DatabaseEnvelopeCredentialStore`。
