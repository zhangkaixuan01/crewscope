# 债务周-B：三 runtime 结构化输出有界恢复 — 完成记录

> 来源：M10-Q02 台账缺陷 7 行尾同构登记（「KnowledgeDistiller/TeamObserver/SkillDistiller 三个 runtime 的 decode 同构无兜底，真实栈从未触发，不在本批」）。
> 母本：`ReviewerSpecialistRuntime.call`（2026-10-10 清账包修复，M10-Q02 缺陷 7）。
> 性质：纯 Java，三个 runtime 同构改造；零 API 契约变更、零迁移。

## 1. 问题

三个 runtime 的 decode 链与修复前的 Reviewer 同构：模型偶发返回纯文本（HTTP 200 无 structuredData）或偏离冻结 schema 的结构时，`decode` 裸抛 `IllegalArgumentException`，`sanitizeModelFailure` 对 IAE 原样透传——上游拿到裸 IAE 而非有界模型失败码，且没有任何恢复尝试。DeepSeek 结构化输出偶发不合规是常态概率事件（Reviewer 实测 4/70 ≈ 6%）。

## 2. 改造形状（逐要素对齐母本）

每个 runtime 五要素：

| 要素 | 落点（Knowledge / Skill / TeamObserver 同构） |
| --- | --- |
| 恢复指令常量 | `STRUCTURED_OUTPUT_RECOVERY_INSTRUCTION`：批评前次纯文本 + `Call generate_response exactly once; do not answer with text.` + 嵌入原 prompt 渲染产物（`%s`）+ 逐 schema 字段格式行（Knowledge=title/content/suggestedCategory；Skill=description/body；TeamObserver=五 section 名） |
| call 链 | `agent.call(...).flatMap(valid ? just : recover).map(decode).onErrorMap(sanitize)`——decode 移入消毒覆盖范围 |
| `recoverStructuredDelivery` | 同 agent 同 schema/context 追问一次；仍无效 `Mono.error(new StructuredDeliveryException())` |
| `validStructuredDelivery` | `hasStructuredData` + try decode+requireValue catch RuntimeException return false（两类不合规同判：无结构化数据 / schema 漂移） |
| `sanitizeModelFailure` 分支 | `StructuredDeliveryException` → 专用 warn 日志 + `SafeModelFailures.sanitize`（bounded code=MODEL_EXECUTION_FAILED）；既有豁免集（DomainValidation/IAE/PlatformExecutionSecurity）不变 |

### 2.1 各 runtime 差异点

- **Knowledge/Skill**：`UsageAccumulator` 挂在 `RuntimeContext`——恢复轮同 context，两次模型调用自然累积两条 attempt usage 事实（正确语义，M10-Q02 缺陷 3 的 `#usage-N` 幂等键派生已支撑多 attempt）。call 抽为独立私有方法（原为 `Mono.using` 内联）。
- **TeamObserver**：已有独立 `call` 方法直接改造；`sanitizeModelFailure` 原为简版（无 warn 日志），本批对齐母本补齐 StructuredDeliveryException 分支的专用 warn。telemetry 分类：投递不合规两轮后的失败从裸 IAE（`OUTPUT_INVALID`）变为 `SafeModelExecutionException(MODEL_EXECUTION_FAILED)`→`telemetryError` default→`UNAVAILABLE`——与母本语义一致（模型无法产出合规结构=模型执行失败而非输出可修）。
- **decode 清理**：三处 decode 的 `hasStructuredData` 前置 IAE 检查删除（valid 先行后成为死分支）；`Objects.requireNonNull` 防御保留。

### 2.2 语义变化（登记）

| 场景 | 旧行为 | 新行为 |
| --- | --- | --- |
| 首次投递无结构化数据 | 裸 IAE 立即失败 | 恢复一次；二次仍无效 → `SafeModelExecutionException(MODEL_EXECUTION_FAILED)` |
| 首次投递 schema 漂移 | 裸 IAE 立即失败 | 同上（两类不合规同判） |
| 恢复轮成功 | —（无恢复轮） | 正常解码返回，多一次 attempt usage |
| TeamObserver telemetry | IAE→OUTPUT_INVALID | MODEL_EXECUTION_FAILED→UNAVAILABLE |

duplicate-selection 的 `DomainValidationException`（evidence 层拒绝，发生在 decode 之后的 `createResult` 阶段）不受影响，仍原样透传。

## 3. 测试证据

| 面 | 结果 |
| --- | --- |
| 定向单测 | KnowledgeDistillerRuntimeTest 6/6、SkillDistillerRuntimeTest 6/6、TeamObserverRuntimeM6I07Test 13/13——各含新增 recoversOnce（断言 2 次 call + 第二次 prompt 含 `Call generate_response exactly once`）与 sanitizes（断言 MODEL_EXECUTION_FAILED + 2 次 call）；既有用例随语义改写（no-structured-data / schema-drift / unknown-fields 三处断言从裸 IAE 改为 sanitize 终端失败） |
| agentscope 模块 verify | BUILD SUCCESS（`-pl crewscope-agentscope -am`：domain/application/agentscope 三下游全过；agentscope 249 测试 0 失败 2 skipped 为既有） |
| 全 reactor verify | BUILD SUCCESS（七模块 50:24；server 1096 测试 0 失败 2 skipped）——server 侧对语义变化（adapter 无 IAE/SafeModel 特判）零回归 |

## 4. 复跑入口

```sh
./mvnw --batch-mode --no-transfer-progress -pl crewscope-agentscope -am test \
  -Dtest='KnowledgeDistillerRuntimeTest,SkillDistillerRuntimeTest,TeamObserverRuntimeM6I07Test' \
  -Dsurefire.failIfNoSpecifiedTests=false
./mvnw --batch-mode --no-transfer-progress -pl crewscope-agentscope -am verify
```

## 5. 台账回填

M10-Q02 台账缺陷 7 行尾「同构弱点登记」段追加清偿记录（指向本文档）。
