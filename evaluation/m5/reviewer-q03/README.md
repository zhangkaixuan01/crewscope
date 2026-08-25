# CrewScope M5-Q03 Reviewer 质量基线

本目录冻结 `reviewer@1` 的多模型协议与真实模型质量集。评测只向 Reviewer 提供生产 `ContextPackageV1`，不提供 Tool、Skill、Repository 或 Gate Decision 能力。

固定集合包含 8 个缺陷变更和 4 个正确变更。真实模型运行记录 Provider、Model、Revision、Template、Prompt Policy、Skill/Tool 空集合、资产哈希、Token、保守成本、延迟、Finding、证据有效性、类别、严重度和 Gate 越权计数；Prompt、模型原文、API Key 与内部推理不进入归档。

```bash
nvm use 24
./scripts/m5-q03-evaluation-gate.sh
./scripts/m5-q03-evaluation-gate.sh --real
```

默认命令执行无凭证协议门禁。`--real` 从当前环境读取模型变量；本地存在 `.env` 时仅导出其中的环境变量，不打印值。原始运行证据追加写入 `var/evaluation/m5-q03/results/<run-id>`，该目录不进入 Git。
