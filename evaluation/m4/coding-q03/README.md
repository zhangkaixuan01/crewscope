# CrewScope M4-Q03 Coding 质量基线

本目录在不修改 `coding-v1` 冻结资产的前提下，提供真实模型评测的运行锁、36 次任务矩阵、平台证据聚合、人工判定和质量门禁。

## 命令

```bash
node evaluation/m4/coding-q03/scripts/benchmark.mjs validate

./scripts/m4-q03-prepare-maven-cache.sh \
  var/evaluation/m4-q03/cache/<snapshot-id> \
  <snapshot-id>

node evaluation/m4/coding-q03/scripts/benchmark.mjs prepare \
  --output var/evaluation/m4-q03/results/<run-id> \
  --run-id <run-id> \
  --provider deepseek \
  --model-id deepseek-v4-flash \
  --model-revision DeepSeek-V4-Flash-0731 \
  --dependency-cache-id <snapshot-id> \
  --dependency-cache-sha256 <sha256> \
  --input-cache-hit-price <usd-per-million-tokens> \
  --input-cache-miss-price <usd-per-million-tokens> \
  --output-price <usd-per-million-tokens> \
  --pricing-window OFF_PEAK \
  --pricing-source https://api-docs.deepseek.com/quick_start/pricing \
  --pricing-effective-at <utc-iso-8601-timestamp>

node evaluation/m4/coding-q03/scripts/benchmark.mjs aggregate \
  --input var/evaluation/m4-q03/results/<run-id> \
  --output var/evaluation/m4-q03/results/<run-id>/aggregate.json
```

`prepare` 创建 12 个任务乘 3 个冻结 Seed 的 36 次不可变运行清单。每个运行目录接收 CrewScope 平台导出的 `workspace/`、`platform-report.json`、`telemetry.json` 和 `human-review.json`。聚合器逐项调用 S04 判定器，不信任 Agent 自述，并拒绝缺失、重复、额外、跨 Run 或被覆盖的证据。

缓存制备脚本先在隔离目录完成全部 Judge Pack 的 Maven `go-offline` 和 `test-compile`，随后移除写权限并生成路径、大小和文件字节组成的 SHA-256 树指纹。真实模型运行前使用 `verify-cache` 复验，Sandbox 将快照只读挂载到 `/maven-cache`。

真实模型轨道要求精确 Provider、Model ID、Model Revision、冻结 Sandbox Digest和只读 Maven Cache 快照。API Key 只通过进程环境进入 AgentScope Provider，不写入运行锁、遥测、报告或归档。

每个批次在 `benchmark-lock.json` 固定同一个价格窗口。逐 Run Lock 继续遵循 S04 冻结字段协议，并通过批次矩阵归属继承价格锁。成本使用缓存命中输入、缓存未命中输入和输出三类官方单价计算；`cachedTokens` 必须位于 `0..inputTokens`，未命中输入量等于 `inputTokens - cachedTokens`。DeepSeek 当前峰值窗口为 `01:00–04:00 UTC` 和 `06:00–10:00 UTC`，其他时间使用 `OFF_PEAK` 单价。跨窗口批次应停止并创建新批次。

长批次允许在 RunLock 中固定 `PEAK` 单价作为全程保守计价，即使运行跨入低谷窗口也不下调成本；`OFF_PEAK` 批次不得跨入峰值窗口。这样可避免低估费用，同时保持一个批次只有一套不可变价格事实。

`crewscope-closure.json` 保存至少一个 CrewScope 自身修改的完整闭环证据，包含 Workspace、AgentRun、CommandEvidence、TestEvidence、DiffArtifact、Delivery Commit、测试、安全和人工复核坐标。聚合门禁要求冻结任务成功率不低于 70%，且每个成功任务的编译、测试、验收、路径、安全与人工判定全部通过。
