# M4-A06：Coding Artifact 内容 API

> 状态：已完成<br>
> 日期：2026-08-20<br>
> 模块：`crewscope-application`、`crewscope-infrastructure`、`crewscope-server`

## 目标

为 Task Execution Studio 提供 Patch、构建日志和测试报告的成员受权内容读取。API 使用 Task、TaskExecution 和对应领域证据闭合 Artifact 关系，执行完整性校验、响应预算、并发限制和安全审计。

## API

| 内容 | 入口 |
|---|---|
| 最终 Patch | `GET /api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/coding/artifacts/patch` |
| 构建日志 | `GET /api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/coding/commands/{commandEvidenceId}/log` |
| 测试报告 | `GET /api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/coding/test-evidence/{testEvidenceId}/report` |

每个入口接受一个标准 `Range: bytes=start-end`、`bytes=start-` 或 `bytes=-suffixLength`。前端日志分页也可使用 `offset` 与 `limit`，两者表示半开字节区间 `[offset, offset + limit)`。Range Header 与分页参数互斥，多段 Range 被拒绝。

完整响应使用 `200`，显式 Range 或分页使用 `206`。响应携带 `Accept-Ranges`、精确 `Content-Range`、`Content-Length`、真实 `Content-Type`、SHA-256 ETag 和只由服务端坐标生成的 `Content-Disposition` 下载名。所有响应使用 `Cache-Control: no-store`、`X-Content-Type-Options: nosniff` 与 `Content-Security-Policy: sandbox`。

## 授权与关系闭合

`CodingArtifactAccessService` 在一个应用事务中完成：

1. 复验 ACTIVE Team Membership；
2. 按 URL 中的 Organization、Team 和 Task 读取 Task；
3. 复验 TaskExecution 归属相同 Task 与完整 WorkItemScope；
4. 按用途读取最终 DiffArtifact、CommandEvidence 或 TestEvidence；
5. 复验证据的 Scope、Task 和 TaskExecution；
6. 从 Task Scope 构造 Workspace 级 ArtifactAccessContext；
7. 调用 M4-I09 Reader 闭合 Restricted、Workspace Visibility、Producer、Content Type、大小和 SHA-256。

公开 API 只提供三个用途固定的关系入口。Artifact ID、宿主路径、Storage URI、Tombstone Detail 和 Producer 不能作为下载坐标。未发布的最终 Patch、没有报告引用的 TestEvidence、跨 Task/attempt/Scope 证据统一返回不可用结果。

## 传输治理

- 单次完整或 Range 响应受 `maximum-range-bytes` 限制；大对象使用 Range 或分页读取；
- `maximum-concurrent-reads` 在共享 `CodingArtifactReader` 中限制同时存活的内容流，范围为 `1–256`；
- Permit 持有至流正常完成、失败或客户端取消并关闭，容量耗尽返回 `429` 与 `Retry-After: 1`；
- Range 起点越界或空对象 Range 返回 `416` 与 `Content-Range: bytes */{totalSize}`；起点有效且结束位置超过对象末尾时按标准 Range 语义截断到 `totalSize`，字节分页的最后一页使用同一规则；
- 整个 Blob 的大小和 SHA-256 校验先于有界流返回；
- WebFlux 在 bounded-elastic 调度器读取阻塞 Artifact 流，响应流终止时关闭底层流；
- 每次授权传输记录低基数 `kind/mode` 指标和包含 Scope、Actor、Correlation ID、Artifact ID、响应字节数的结构化审计事实。

配置：

```yaml
crewscope:
  coding:
    artifact:
      maximum-range-bytes: 1048576
      maximum-concurrent-reads: 16
```

## 错误协议

| 状态 | Code | 含义 |
|---|---|---|
| `400` | `invalid_range` | Range/分页语法、组合或坐标无效 |
| `404` | `coding_artifact_unavailable` | 内容缺失、过期、Tombstone 或 Store 授权失败 |
| `413` | `coding_artifact_response_too_large` | 单次响应超过部署预算 |
| `416` | `coding_artifact_range_not_satisfiable` | 有效 Range 超出当前对象 |
| `429` | `coding_artifact_download_busy` | 并发内容流达到部署上限 |
| `409` | `coding_artifact_integrity_mismatch` | 关系元数据与 Artifact Descriptor 不一致 |

## 验证

专项覆盖成员授权、跨 Task/attempt/Scope 关闭、未发布报告、开放与 Suffix Range、字节分页、下载名、Content Type、安全响应头、任意 Artifact ID 路由缺失、413/416/429、流关闭、并发 Permit 释放、配置边界和审计指标。

专项命令：

```bash
./mvnw -pl crewscope-application,crewscope-infrastructure,crewscope-server -am \
  -Dtest='*M4A06*,CodingArtifactServiceM4I09Test,CodingArtifactConfigurationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

专项验证共 22 项通过，0 失败、0 错误、0 跳过。

全仓验证命令：

```bash
./mvnw clean verify
```

提交前复审后的全仓验证共 1438 项通过，0 失败、0 错误、0 跳过。文档链接检查通过，共检查 175 个 Markdown 文件；`git diff --check` 通过。
