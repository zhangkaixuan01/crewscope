# ADR-003：ArtifactStore 与 AgentStateSnapshot

> 状态：ACCEPTED<br>
> 日期：2026-08-05<br>
> 影响里程碑：M0、M3、M4、M6

## 背景

CrewScope 需要保存 Diff、Patch、测试日志、模型大结果、ContextPackage、AgentStateSnapshot 和 Sandbox Snapshot。PostgreSQL 适合保存元数据，Redis 适合保存短期运行态，大对象需要独立生命周期和完整性保证。

## 决策

应用层定义 `ArtifactStore` Port：

```text
put(content, metadata) -> ArtifactDescriptor
get(artifactId, accessContext) -> content stream
head(artifactId) -> ArtifactDescriptor
tombstone(artifactId, reason)
purge(expiredBefore)
```

ArtifactDescriptor 至少包含：

- artifact_id、organization_id、team_id、workspace_id；
- content_type、size、sha256；
- data_classification、visibility；
- storage_uri、encryption、created_at、retention_until；
- producer TaskExecution、StepExecution、AgentRun 和 Trace。

### 实现

- 开发环境：FilesystemArtifactStore；
- Team Beta：S3/MinIOArtifactStore；
- AgentScope Sandbox Snapshot：通过 ArtifactStore Snapshot Adapter 保存；
- PostgreSQL：保存 RuntimeArtifact 和 AgentStateSnapshot 元数据；
- Redis：保存 AgentState 和小型短期数据。

### 生命周期

- 上传采用临时对象、SHA-256 校验和原子提交；
- 内容寻址对象可以去重，授权和引用保持独立；
- 部署存储启用服务端加密或 KMS 信封加密；
- 删除先写 Tombstone 和 AuditEvent，再按保留策略物理清理；
- 引用中的 Artifact 在 Task、Review、Action 和审计保留期内持续可读；
- 大 Workspace Snapshot 不进入 Redis。

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
