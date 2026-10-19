# M13-C01：StructuredDeliveryCall 组件 — 完成记录

> 来源：[M13-后端结构收敛执行计划](../plans/M13-后端结构收敛.md) C01 工作包（Review §3.1/§4.1/§8 P1a）。
> 性质：纯结构收敛（恢复/消毒三件套归一），唯一登记偏差见 §3。

## 1. 交付：组件与四 runtime 改挂

新增 `crewscope-agentscope/src/main/java/io/crewscope/agentscope/StructuredDeliveryCall.java`（约 140 行，泛型组件，与 SafeModelFailures/StrictStructuredOutputDecoder 同层）。组件拥有 call→recover→decode→sanitize 全链：

| 组件职责 | 形态 |
| --- | --- |
| 单次模型调用 | `agent.call(List.of(new UserMessage(prompt)), schema, context)` |
| 单次有界恢复 | 首投递非冻结 schema 形状→同 agent 同 schema/context 以 `recoveryInstruction.formatted(prompt)` 追问一次；仍无效→私有 marker 异常 |
| 校验即解码 | `decoder: Function<Msg, T>` 同时承担 validate（抛 RuntimeException 即无效）与转换，链上应用两次（validation 一次+取值一次），与四份原实现的等价性逐一核对 |
| 消毒边界 | marker→专属 warn+sanitize（MODEL_EXECUTION_FAILED）；DomainValidation/IllegalArgument/PlatformExecutionSecurity 透传；其余→code/cause/root warn+sanitize |

每 runtime 保留三参数（modelLabel / recoveryInstruction / decoder）+ 各自的 `Mono.using` 会话外壳、timeout、usage/telemetry 累积（RuntimeContext 携带，组件不触碰）。四 runtime 各删约 70-90 行手写链。

## 2. 行为等价性

- 恢复指令常量、marker 消息、两条 warn 文案逐字保留（`<modelLabel> model never returned structured output…` 等四份原文以参数拼接重现）。
- logger 名从各 Runtime 类变为组件类——日志框架内部命名变化，warn 文案不变（注记，非契约面）。
- Knowledge/Skill/TeamObserver 三链语义零变化（债务周 B 后的最新形状为蓝本）。

## 3. 登记偏差（用户裁定 2026-10-19）：统一豁免集

**发现**：Reviewer 的 sanitize 豁免集缺 `PlatformExecutionSecurityException`（M5 母本旧形状；债务周 B 复制到三个新 runtime 时带了该豁免，母本自身未补）——四份手写漂移的直接后果：Reviewer 链上的平台安全拒绝（`ReviewerRuntimeContextMiddleware:57` 真实可达）被消毒成 MODEL_FAILURE，安全信号被吞；三个新 runtime 均透传。Review §4.1 矩阵「4/4 一致」系评审数据错误，已由此修正。

**裁定**：统一豁免集（Reviewer 补 Platform 透传）。影响面核查：Reviewer runtime 层测试对该面零覆盖（无断言冲突）；HTTP 面两类异常同走通用 500（外部零可观察差异）；方向为对齐三个新 runtime 的既定语义。

**连带微变**：TeamObserver 的 else 分支从静默 sanitize 获得统一的 code/cause/root warn（M9b-Q02 defect 20 形态）——可观测性增强，非契约面。

## 4. 测试证据

| 面 | 结果 |
| --- | --- |
| 定向 29 用例（断言零修改） | KnowledgeDistillerRuntimeTest 6/6、SkillDistillerRuntimeTest 6/6、TeamObserverRuntimeM6I07Test 13/13、ReviewerSpecialistRuntimeM5I06Test 4/4 |
| agentscope 模块 verify | BUILD SUCCESS（32s）——249 测试 0 失败 0 错误 2 skipped（既有基线） |
| 全 reactor | Q01 收口统一背书（本包收批跑模块级） |

## 5. 复跑入口

```sh
./mvnw -pl crewscope-agentscope -am test -Dtest='KnowledgeDistillerRuntimeTest,SkillDistillerRuntimeTest,TeamObserverRuntimeM6I07Test,ReviewerSpecialistRuntimeM5I06Test' -Dsurefire.failIfNoSpecifiedTests=false
```
