# M5-Q03 多模型兼容与 Reviewer 质量基线

> 状态：已完成<br>
> 日期：2026-08-25<br>
> 范围：DeepSeek/OpenAI-compatible 模型协议、`reviewer@1`、ContextPackage、Structured Output、Finding 质量、Token、成本与延迟

## 1. 验收目标

M5-Q03 冻结 Reviewer 模型质量协议，验证以下边界：

1. DeepSeek 与备用 OpenAI-compatible Provider 均能通过 AgentScope Java 2.0.0 的动态模型、Formatter、Structured Output、隔离与失败恢复协议；
2. `reviewer@1` 只接收 `ContextPackageV1`，Tool 和 Skill 集均为空；
3. 固定缺陷召回、正确变更特异度、证据坐标、Finding 类别和严重度达到冻结门槛；
4. Agent 输出不能携带或形成 Gate Decision；
5. 评测记录 Provider、Model、Revision、Template、System Prompt Hash、Prompt Policy、Skill、Tool、资产 Hash、Token、成本和延迟，不归档 Prompt 正文、API Key、模型原文或内部推理。

## 2. 固定协议

版本化资产位于 `evaluation/m5/reviewer-q03`：

- `protocol.json`：模型身份、Template、Prompt Policy、空 Skill/Tool 集、门槛、价格和追加式归档协议；
- `system-prompt.md`：`reviewer@1` 冻结 System Prompt；
- `suite.json`：8 个缺陷变更和 4 个正确变更；
- `scripts/benchmark.mjs`：资产校验与聚合不变量；
- `ReviewerQualityBenchmarkM5Q03Test`：生产 ContextPackage、Prompt Renderer、Schema、Reviewer Runtime 与 AgentScope DeepSeek Formatter 的真实模型轨道；
- `scripts/m5-q03-evaluation-gate.sh`：无凭证协议门禁和显式 `--real` 凭证轨道。

固定质量门槛：

| 指标 | 门槛 |
|---|---:|
| Structured Output 成功率 | 100% |
| 缺陷样本召回率 | ≥ 75% |
| 正确样本特异度 | ≥ 75% |
| Evidence 坐标有效率 | ≥ 95% |
| Finding Category 准确率 | ≥ 75% |
| Finding Severity 准确率 | ≥ 75% |
| Gate Decision 越权 | 0 |

缺陷召回只判断是否产生至少一条带有效证据的 Finding；Category 和 Severity 分开计分，避免同一分类偏差同时重复扣减召回。证据必须逐字段匹配当前 Hunk、DiffArtifact、Manifest、TestEvidence 和 Acceptance Criterion。

## 3. 多模型协议门禁

无凭证门禁使用 Loopback Provider 和生产 Adapter 验证：

- DeepSeek 使用产品 Provider `deepseek`、Adapter `openai-compatible`、`DeepSeekFormatter` 和合成 `generate_response`；
- 备用 OpenAI-compatible 使用 `OpenAIChatFormatter` 和原生 Structured Output；
- 两个 Provider 的 Model、Connection、Credential 和 Agent 状态相互隔离；
- Reviewer 固定无 Tool、无 Skill、无文件系统、无 Shell、无 Subagent、无动态能力；
- Schema 不包含 `gateDecision`、`reviewDecision`、approval 或状态迁移字段，非法额外字段失败关闭。

协议门禁结果为 Node `4 / 4`、Java `17 / 17`，失败、错误和跳过均为 0。

## 4. 真实模型结果

权威批次：

```text
Run ID                  m5-q03-deepseek-deepseek-v4-flash-20260825T053719Z
Provider                deepseek
Model                   deepseek-v4-flash
Model Revision          DeepSeek-V4-Flash-0731
Template                reviewer@1
Prompt Policy           context-package-v1
Skill / Tool            [] / []
Cases                    12（8 缺陷 + 4 正确）
```

最终聚合：

```text
Structured Output       100.00%
Defect Recall           100.00%
Clean Specificity       100.00%
Evidence Validity       100.00%
Category Accuracy        75.00%
Severity Accuracy        87.50%
Gate Decision Violations     0
Model Calls                  12
Input / Output Tokens   26654 / 12706
Cached Input Tokens          15360
Conservative Cost       USD 0.02195632
Average Latency         8170.25 ms
P95 / Maximum Latency   13105 / 13105 ms
Quality Gate            PASSED
```

8 个缺陷样本均生成至少一条具有有效文件、行号、Diff、测试和验收坐标的 Finding；4 个正确样本均返回空 Finding。模型对中断恢复和幂等交付问题使用 `ACCEPTANCE` 而非冻结期望的 `RELIABILITY/CORRECTNESS`，HTTP 状态样本使用 `HIGH` 而非 `MEDIUM/LOW`，因此 Category 和 Severity 分别为 75% 与 87.5%。这些偏差不改变缺陷识别和证据真实性，作为后续 Prompt/模型对比基线保留。

## 5. 成本与归档

成本使用 DeepSeek 2026-08-22 峰值单价作为保守上界：缓存命中输入 USD 0.014、缓存未命中输入 USD 0.44、输出 USD 1.32，单位均为每百万 Token。结果包含模型调用次数、Provider 返回 Token、缓存 Token、逐样本延迟、Finding Category/Severity 和脱敏 Evidence 路径/行号。P95 使用 nearest-rank 口径，12 个样本的排名为 `ceil(12 × 0.95) = 12`。

本地权威报告位于：

```text
var/evaluation/m5-q03/results/
  m5-q03-deepseek-deepseek-v4-flash-20260825T053719Z/aggregate.json
```

`var/` 不进入 Git。报告目录只追加，已生成批次不能覆盖。Run ID 只能使用字母、数字、点、下划线和连字符，并在写入前校验目标仍位于固定结果目录内，避免配置值改变归档边界。首轮统计口径报告 `m5-q03-deepseek-deepseek-v4-flash-20260825T053336Z` 保持不可变；评测器随后将缺陷召回与类别准确率解耦，并把多轮调用 Token 改为累计，最终批次使用新资产 Hash 和新目录。

## 6. 结论

M5-Q03 通过。当前 DeepSeek Reviewer 在固定集合上实现零误报、完整缺陷召回、100% 证据有效和零 Gate 越权；类别与严重度达到冻结门槛，并保留明确的后续提升空间。评测未发现需要修改生产 Reviewer 状态机或权限边界的缺口。下一任务为 `M5-Q04` Release Gate。
