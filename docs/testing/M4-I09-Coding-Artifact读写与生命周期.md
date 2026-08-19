# M4-I09 Coding Artifact 读写与生命周期

## 1. 交付范围

M4-I09 在通用 ArtifactStore 和 M4 Diff/Command/TestEvidence 关系事实之上交付：

- Patch、构建日志和测试报告共享的 Restricted Workspace Artifact 发布策略；
- Workspace 与 EvidenceSequence 派生的稳定 Artifact ID；
- 统一可配置保留期、单 Artifact 大小和单次 Range 响应预算；
- 先验证整对象大小与 SHA-256、再返回精确半开字节区间的 Range 读取；
- PatchArtifactReference、CommandEvidence、TestEvidence 与 Artifact Descriptor 的完整元数据闭合；
- Tombstone、保留期结束后的有界物理清理和精确 Artifact ID 返回；
- 不包含正文、存储位置、Producer 和 Tombstone Detail 的公开摘要。

## 2. 发布协议

`CodingArtifactPublisher` 统一执行以下规则：

```text
验证 Principal 与 Workspace Organization/Team Scope
  -> 应用部署 Artifact 大小上限
  -> 计算完整内容大小与 SHA-256
  -> 写入 Restricted + Workspace Visibility + TaskExecution Producer
  -> 应用统一正保留期
  -> 复验 ArtifactStore Descriptor 与完整写请求
```

Artifact ID 使用带用途域隔离的 SHA-256 派生 UUIDv8：Patch 绑定 ExecutionWorkspace，构建日志和测试报告绑定 ExecutionWorkspace 与 EvidenceSequence。相同事实和内容重复发布返回同一个 Descriptor；相同事实对应不同内容形成确定冲突，不创建第二个逻辑引用。

`PatchArtifactWriter` 与 `CommandLogArtifactWriter` 已迁移到统一 Publisher。`TestReportArtifactWriter` 发布 canonical Content Type 的完整测试报告，并返回 `TEST_REPORT` EvidenceArtifactReference。

## 3. 读取与数据库闭合

`CodingArtifactReader` 先使用已授权 ArtifactAccessContext 获取 Descriptor，再与 PostgreSQL 已提交聚合携带的引用逐项比较：

- Artifact ID、Organization/Team/Workspace Scope；
- Artifact Kind 对应的 Content Type、大小和 SHA-256；
- `RESTRICTED` 数据分类与 `WORKSPACE` 可见性；
- Producer TaskExecution 与聚合 TaskExecution；
- Producer Principal 与聚合创建审计 Principal。

任一字段不一致返回稳定 `METADATA_MISMATCH`，不返回任何正文。未经授权、已过期、已 Tombstone 或缺失内容使用同一安全不可用边界。

Range 使用 `[startInclusive, endExclusive)` 精确半开区间。ArtifactStore 在完整 Blob 大小与 SHA-256 验证通过后切片，返回流不能继续读取区间外内容。超过部署单次响应预算的整对象必须使用 Range；空区间、越界区间和过大区间被拒绝。

## 4. 生命周期与公开摘要

所有 M4 Coding Artifact 使用同一可配置保留期，默认 30 天。到达保留期限后内容停止读取，Head 继续为已授权生命周期处理提供元数据。

`CodingArtifactLifecycle` 在重读并闭合关系元数据后写 Tombstone。相同原因与规范 Detail 重试幂等，不同 Tombstone 请求冲突。有界 Purge 只删除已有 Tombstone 且保留期结束的逻辑引用；共享 Blob 由 FilesystemArtifactStore 在最后一个引用删除后清理。

`CodingArtifactSummary` 只包含 Artifact ID、用途、Content Type、大小、Hash、`ACTIVE/EXPIRED/TOMBSTONED` 和可选保留期限。正文、宿主路径、Storage URI、Producer、Tombstone 原因与 Detail 不进入摘要或异常消息。

## 5. 配置

```yaml
crewscope:
  coding:
    artifact:
      retention: 30d
      maximum-artifact-bytes: 67108864
      maximum-range-bytes: 1048576
```

保留期范围为 1 小时至 3650 天，单 Artifact 上限范围为 1 KiB 至 1 GiB，Range 上限必须为正数且不能大于 Artifact 上限。

## 6. 验证结果

专项测试覆盖：

- 精确 Range、流关闭、区间外不可读与越界分类；
- 真实 FilesystemArtifactStore 在 Range 前验证完整 Blob Hash；
- 写入中断不发布 Sidecar 且不残留 temporary 文件；
- Patch/测试报告稳定 ID、重复发布、不同内容冲突和统一 TTL；
- 大对象强制 Range、响应字节预算和安全公开摘要；
- 敏感 Token、宿主路径、Producer 与 Tombstone Detail 不进入摘要；
- TestEvidence 引用的大小、Hash、Scope、Producer 与 Descriptor 不一致时失败关闭；
- Tombstone 幂等、立即停止读取、保留期前不清理和到期有界 Purge；
- server/worker 共用 Reader/Lifecycle 装配与配置上限；
- M4-I07 CommandEvidence 和 M4-I08 Diff Finalizer 回归。

执行命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  '-Dtest=*M4I09Test,CodingArtifactConfigurationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

M4-I09 专项测试共 8 项通过，0 失败、0 错误、0 跳过。全仓 Maven 门禁共 1321 项通过，0 失败、0 错误、0 跳过，Reactor 7 个模块全部成功，总耗时 7 分 10 秒。

## 7. 后续边界

M4-I10 已使用当前 Artifact Tombstone/Purge 能力完成 Worker 启动孤立 Artifact、Workspace、Sandbox 和 Watcher 对账，证据见 [M4-I10 Worker 启动资源对账](M4-I10-Worker启动资源对账.md)。M4-A06 在 CodingArtifactReader 上提供成员受权的 Patch、构建日志和测试报告 HTTP Range/分页接口与下载审计。
