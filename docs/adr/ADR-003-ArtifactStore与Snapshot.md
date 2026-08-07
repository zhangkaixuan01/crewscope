# ADR-003：ArtifactStore 与 AgentStateSnapshot

> 状态：ACCEPTED<br>
> 日期：2026-08-05<br>
> 更新：2026-08-07（M0-I01/I02 固化流式契约与 Filesystem 原子存储协议）<br>
> 影响里程碑：M0、M3、M4、M6

## 背景

CrewScope 需要保存 Diff、Patch、测试日志、模型大结果、ContextPackage、AgentStateSnapshot 和 Sandbox Snapshot。PostgreSQL 适合保存元数据，Redis 适合保存短期运行态，大对象需要独立生命周期和完整性保证。

## 决策

应用层定义 `ArtifactStore` Port：

```text
put(writeRequest, contentStream) -> ArtifactDescriptor
get(artifactId, accessContext) -> ArtifactContent
head(artifactId, accessContext) -> ArtifactDescriptor
tombstone(artifactId, accessContext, reason, detail) -> ArtifactTombstone
purgeTombstoned(ArtifactPurgeRequest) -> ArtifactId[]
```

`put` 使用调用方生成的稳定 Artifact ID。`ArtifactWriteRequest` 包含 Scope、Content Type、声明大小、预期 SHA-256、数据分类、可见性、TTL 和 Producer。实现以流式方式读取内容，实际大小和哈希必须与声明一致。相同 Artifact ID、元数据和内容重复写入返回原 Descriptor；相同 ID 对应不同请求时返回确定冲突。

Artifact Scope 由 Organization、可选 Team 和可选 Workspace 构成。`ArtifactAccessContext` 保存当前 Principal 在一个 Organization 中已授权的 Team 与 Workspace 集合。读取范围按 Visibility 判定：

- `PRIVATE`：Producer Principal；
- `WORKSPACE`：已授权 Workspace；
- `TEAM`：已授权 Team；
- `ORGANIZATION`：同一 Organization。

ArtifactDescriptor 至少包含：

- artifact_id、organization_id、team_id、workspace_id；
- content_type、size、sha256；
- data_classification、visibility；
- storage_uri、encryption、created_at、retention_until；
- producer TaskExecution、StepExecution、AgentRun 和 Trace。

SHA-256 使用 64 位小写十六进制规范文本。DataClassification 使用 `PUBLIC`、`INTERNAL`、`CONFIDENTIAL` 和 `RESTRICTED`。Visibility 与数据分类分别表达访问范围和敏感程度。Storage URI 只保存稳定内部位置，不保存签名查询参数、用户信息或临时凭证。

### 实现

- 开发环境：FilesystemArtifactStore；
- Team Beta：S3/MinIOArtifactStore；
- AgentScope Sandbox Snapshot：通过 ArtifactStore Snapshot Adapter 保存；
- PostgreSQL：保存 RuntimeArtifact 和 AgentStateSnapshot 元数据；
- Redis：保存 AgentState 和小型短期数据。

FilesystemArtifactStore 使用同一根目录下的固定布局：

```text
objects/sha256/{hash-prefix}/{sha256}.blob
references/{artifact-id-prefix}/{artifactId}.json
temporary/{random}.part
locks/{lock-type}/{lock-key}.lock
```

Blob 按 SHA-256 内容寻址，Descriptor Sidecar 按 Artifact ID 保存。多个逻辑 Artifact 可以引用同一 Blob，Scope、Producer、Visibility、TTL 和 Tombstone 保持独立。Sidecar 使用带 `schemaVersion` 的规范 JSON，`storageUri` 保存稳定绝对 `file` URI，不序列化临时签名和凭证。

写入协议：

```text
获取 Artifact ID 文件锁
  -> 已存在时校验幂等请求和 Blob 完整性
  -> 流式写入同文件系统 temporary 文件
  -> 校验声明大小和 SHA-256
  -> 获取 SHA-256 文件锁
  -> fsync temporary 文件
  -> ATOMIC_MOVE 发布 Blob
  -> ATOMIC_MOVE 发布 Descriptor Sidecar
  -> 释放文件锁
```

JVM 条带锁避免同进程重叠文件锁，文件锁保护共享根目录中的跨进程写入。目标文件系统需要支持原子移动；不支持时写入失败并保留原有已提交引用。失败写入删除本次 temporary 文件。Blob 已发布而 Sidecar 尚未发布时形成可回收孤儿 Blob，不形成可读 Artifact。

读取先解析 Descriptor 和执行 Scope 授权，再检查 TTL/Tombstone，并以 Descriptor 的大小与 SHA-256 校验 Blob。实际路径只通过规范 ID 和哈希推导，Sidecar 中的 Storage URI 必须与推导结果一致。

物理清理在 Artifact ID 与 SHA-256 文件锁内重新读取 Descriptor。内容锁覆盖“扫描并校验其他引用、删除当前逻辑引用、删除无引用 Blob”的完整过程。只有最后一个引用移除后才删除共享 Blob。返回值只包含本次实际删除的逻辑 Artifact ID。

### 生命周期

- 上传采用临时对象、SHA-256 校验和原子提交；
- 内容寻址对象可以去重，授权和引用保持独立；
- 部署存储启用服务端加密或 KMS 信封加密；
- TTL 是从 Store 接收写入时刻开始计算的正 Duration；空 TTL 表示持续保留；到达 `retentionUntil` 时 Artifact 进入过期状态；
- 读取内容要求 Scope 授权、未过期且没有 Tombstone，Head 对已授权调用者继续返回生命周期元数据；
- 删除先写 Tombstone 和 AuditEvent；重复的相同 Tombstone 请求保持幂等，不同原因形成冲突；
- 物理清理只批量处理已有 Tombstone 且保留期限已经结束的对象；`ArtifactPurgeRequest` 使用 UTC 截止时间和 `1–1000` 批量上限，结果返回实际清理的 Artifact ID 供审计；
- 引用中的 Artifact 在 Task、Review、Action 和审计保留期内持续可读；
- 大 Workspace Snapshot 不进入 Redis。

Tombstone 记录稳定原因、可选安全说明、操作 Principal 和 UTC 时间。原因使用 `RETENTION_EXPIRED`、`USER_REQUESTED`、`SECURITY_POLICY`、`ORGANIZATION_REMOVED` 和 `SUPERSEDED`。Tombstone 说明不保存凭证、原始内容和敏感请求正文。

## 结果

- AgentScope Snapshot 和平台 Artifact 共享存储治理；
- PostgreSQL 不承载大二进制对象；
- Artifact 具备完整性、授权、保留和审计；
- 开发和部署通过同一 Port 切换实现。

## 验证

1. 重复上传、损坏内容和错误哈希得到确定结果；
2. 未授权 Principal 无法读取 Artifact；
3. AgentStateSnapshot 可以恢复 AgentRun；
4. Redis 数据丢失后可以从 Snapshot 与领域事实重建；
5. Tombstone、保留期和物理清理产生完整 AuditEvent。

## 重新评估条件

- Artifact 需要跨区域复制；
- 引入组织级数据驻留；
- Sandbox 使用原生 NAS/OSS 挂载；
- Artifact 总量需要独立索引或数据湖。
