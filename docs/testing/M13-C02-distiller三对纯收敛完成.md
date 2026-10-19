# M13-C02：distiller agentscope 三对纯收敛 — 完成记录

> 来源：[M13-后端结构收敛执行计划](../plans/M13-后端结构收敛.md) C02 工作包（Review §3.1 复用与重复）。
> 性质：纯结构收敛（零行为变化），范围裁定与行数指标偏差见 §3/§4。

## 1. 交付：六文件 → 四共享文件

新增 `crewscope-agentscope/src/main/java/io/crewscope/agentscope/distiller/` 包，四文件替换 knowledge/skill 两侧六个镜像文件：

| 共享文件 | 行数 | 收敛对象（两侧原文件） | 参数化差异点 |
| --- | --- | --- | --- |
| `DistillerKind` | 40 | （新增枚举层） | label / sessionNamespace / stateTopicPrefix 三常量 |
| `DistillerPromptRenderer` | 48 | Knowledge/SkillDistillerPromptRenderer（30+32） | renderKnowledge/renderSkill 两方法，escape 共享 |
| `DistillerRuntimeSession` | 83 | Knowledge/SkillDistillerRuntimeSession（60+60） | kind 首字段；stable 身份校验与 helper 按 kind switch 分派两侧 domain Initialization |
| `DistillerTemplateRuntimeRegistry` | 140 | Knowledge/SkillDistillerTemplateRuntimeRegistry（101+100） | knowledge()/skill() 静态工厂收 label/outputSchema 供给/profile/definition requirement/VERSION 五参数 |

引用方改挂（构造路径变化，断言语义冻结）：两侧 Runtime（Renderer/Registry/Session 引用+`prompts.renderKnowledge/renderSkill`）、两侧 RuntimeRequest（session 字段类型）、两侧 RuntimeContextMiddleware（`get(DistillerRuntimeSession.class)`+stable 校验改共享 helper，错误码前缀 KNOWLEDGE_/SKILL_DISTILLER_ 保留为各自语义面）、`TemplateAgentSessionIdentity`（distiller/skillDistiller 两工厂合一为 `distiller(DistillerRuntimeSession)`，按 `session.kind()` 映射 Kind.DISTILLER/SKILL_DISTILLER——两个 security chain 值保留）、server 两 Adapter（构造调用加 kind 首参数）+`KnowledgeApplicationConfiguration`/`TeamSkillConfiguration`（Bean 工厂改 `knowledge()`/`skill()` 静态工厂，Bean 名不变）。

## 2. 行为等价性

- Registry 的 requireRuntime/requireCoordinates 布尔链（exactGraph 14 条+teamModel 16 条）逐字保留，仅 `OUTPUT_SCHEMA_HASH`/`Template.VERSION` 静态引用换实例字段；错误消息 label 拼接重现原文（"Knowledge Distiller Runtime requires the exact tool-less TEAM contract"）。
- Session 的 agentScopeKey 前缀（`crewscope:v1:session:` + kind namespace）、state topic（`io.crewscope/knowledge|skill-distiller-state/v1/`）、紧凑构造器校验与错误文案逐字保留。
- Renderer 模板文案逐字保留两侧原文；middleware 错误码三前缀不变。
- kind 交叉场景（如 Knowledge 中间件收到 SKILL session）由 stable 身份校验天然拦截（两侧 deterministic id 不同）——与原类型隔离同属 PlatformExecutionSecurityException 拒绝路径，且正常运行不可达（各链只 put 自己的 session）。
- Kind.DISTILLER 与 Kind.SKILL_DISTILLER 是两个 security chain 值，未合一——`TemplateAgentSessionIdentity.Kind` 枚举零变化。

## 3. 登记偏差（用户裁定 2026-10-19）：缩为三对纯收敛

原计划 C02 为六对收敛。勘察发现 Accumulator 对拴 `Port.AttemptUsage`、Runtime 对拴 `Port.DistilledDraft`（均为 application 层 C03 对象）——完整合并天然在 C03 类型合一之后（反向依赖）。上报后用户裁定：**本包缩为三对纯收敛（Renderer/Session/Registry），Request/Accumulator/Runtime 三对划入 C03 连带**。计划表 C02/C03 行已按此回填重排。

## 4. 行数指标（如实入档）

| 口径 | 数值 |
| --- | --- |
| 三对基线（六旧文件合计） | 383 行 |
| 新共享四文件合计 | 311 行（含新增 DistillerKind 40 行） |
| 三对净减 | **72 行（18.8%）——未达 ≥40% 指标** |
| 全 diff（含引用方+新文件） | +417 / −475，净 −58 行 |

未达标原因：①范围缩为三对后，`DistillerKind` 参数化层（40 行）成为纯结构性开销，六对合并时它可摊入更大收益；②Registry/Session 的真实差异点（label/schema/stable id/topic）必须显式参数化，工厂+字段+switch 分派约占合并收益的一半。结构目标（六文件归四、单一实现、差异点显式化）达成；行数目标在缩范围后不可达，如实说明。

## 5. 测试证据

| 面 | 结果 |
| --- | --- |
| 定向 18 用例（断言零修改，仅 import/构造路径） | KnowledgeDistillerRuntimeTest 6/6、SkillDistillerRuntimeTest 6/6、KnowledgeDistillerRuntimeContextMiddlewareTest 3/3、KnowledgeDistillerUsageMiddlewareTest 3/3 |
| server 定向 | TeamSkillConfigurationTest 4/4 |
| agentscope 模块 verify | BUILD SUCCESS——249 测试 0 失败 0 错误 2 skipped（既有基线维持） |
| 全 reactor | Q01 收口统一背书（本包收批跑模块级） |
| S01 分层门禁 | `node scripts/check-module-boundaries.mjs` 通过（distiller 新包在 agentscope 内，引用 domain/application 合规） |

## 6. 复跑入口

```sh
./mvnw -pl crewscope-agentscope -am test -Dtest='KnowledgeDistillerRuntimeTest,SkillDistillerRuntimeTest,KnowledgeDistillerRuntimeContextMiddlewareTest,KnowledgeDistillerUsageMiddlewareTest' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl crewscope-server -am test -Dtest='TeamSkillConfigurationTest' -Dsurefire.failIfNoSpecifiedTests=false
node scripts/check-module-boundaries.mjs
```
