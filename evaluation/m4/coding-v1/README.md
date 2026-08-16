# CrewScope M4 Coding Evaluation v1

本目录保存 M4-S04 冻结的 Java/Spring Boot Coding Agent 评测协议。所有相对路径均以本目录为根，清单、运行配置、Task Brief、仓库 Fixture、Judge Pack 和故障样本共同形成版本化评测输入。

## 目录

```text
suite.json                         评测清单与 12 个任务
runtime/coding-specialist-v1.json AgentScope、模型、预算与 Sandbox 配置
prompts/coding-system-v1.md        固定 System Prompt
skills/java-spring-v1.md           固定只读 Skill Bundle
tools/controlled-tools-v1.json     固定受控 Tool 协议
fixtures/java-spring-lab/          Agent 可见的基线仓库模板
judge-tests/                       Agent 不可见的验收测试
failure-samples/v1.json            稳定失败分类样本
scripts/evaluate.mjs               校验、物化与结果判定入口
```

## 运行方式

```bash
node evaluation/m4/coding-v1/scripts/evaluate.mjs validate
node evaluation/m4/coding-v1/scripts/evaluate.mjs materialize --output /tmp/crewscope-m4-eval
node evaluation/m4/coding-v1/scripts/evaluate.mjs judge-report \
  --task java-username-normalization \
  --workspace /tmp/crewscope-m4-eval \
  --report /path/to/evaluation-report.json
```

`validate` 不调用模型，也不执行网络请求。它会重建临时 Git 仓库，验证固定 Baseline Commit、资产哈希、任务边界、双轨隔离和故障分类。

`materialize` 只复制 Agent 可见的 Fixture，并使用固定作者、时间、Commit Message 和分支创建可重复 Git 基线。`judge-tests` 不进入 Agent Workspace。

`judge-report` 先验证轨道与真实模型 RunLock、Git 基线和 AllowedPaths，再严格验证平台生成的命令证据、预算、结构化结果和最终 Hash。未知顶层字段、重复或额外命令、额外预算字段均失败关闭。Agent 自述、Plan、Todo 和自行报告的测试结果不参与成功判定。

## 轨道

- `deterministic-ci`：使用脚本化执行记录验证协议、恢复、策略和判定器，不产生模型能力分数。
- `real-model-benchmark`：对 12 个任务使用冻结配置执行 3 次，分别保存每次原始证据与聚合报告。

两个轨道使用不同结果目录。真实模型结果只能追加新 Run，不覆盖清单和历史证据。
