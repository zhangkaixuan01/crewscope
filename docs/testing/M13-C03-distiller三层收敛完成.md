# M13-C03：distiller domain+application+server 三层收敛 — 完成记录

> 来源：[M13-后端结构收敛执行计划](../plans/M13-后端结构收敛.md) C03 工作包（Review §3.1 复用与重复）。
> 性质：纯结构收敛（零行为变化、零 API 契约/迁移/事件形状变更）；按六切片交付，断言语义冻结。
> 范围说明：server 两 DistillationAdapter 按计划表归 C04，本文不含；Repository 对与五个小 record 对登记不收敛（§3）。

## 1. 交付：六切片

| 切片 | 提交 | 共享实现（新增） | 薄壳化对象 |
| --- | --- | --- | --- |
| 1 | `4fca75e` | `application/distiller/DistillationCallPort`、`DistillationCallAttribution`、`DistillationAttemptUsage`、`DistillerReadiness` | Knowledge/Skill `DistillationPort` 与 `DistillerReadiness`；两侧 agentscope UsageAccumulator/Runtime/两 Adapter 的类型引用改挂 |
| 2 | `1ee11a4` | `agentscope/distiller/DistillerCallPipeline`（通用 call 管线）、`DistillerUsageAccumulator` | Knowledge/Skill `DistillerRuntime`；两侧 usage middleware 指向共享累加器；两旧 Accumulator 删除 |
| 3 | `8204de2` | `domain/distiller/DistillerTemplateContract`、`DistillerInitializationContract`、`DistillerInitializationPair` | 两侧 `*DistillerTemplate` 与 `*DistillerInitialization`（保留公开常量/静态方法与 record 组件名） |
| 4 | `f955791` | `application/distiller/DistillationCommandSupport` | Knowledge/Skill `DistillationService`（守卫、completed-execution 校验、requestHash、usage facts、created 事件+receipt 信封） |
| 5 | `1d68e66` | `application/distiller/DistillerProvisioningTemplate<I>`（`knowledge()`/`skill()` 工厂） | 两侧 `*DistillerProvisioningService`（构造器 11 参与 NPE 消息不变，`ensureReady` 委托） |
| 6 | `59da382` | `application/command/TeamCommandSupport`（守卫、权限门、requestHash、版本检查、事件/零事件 receipt、变更命令事务骨架） | Knowledge/TeamSkill `CommandService`；`DistillationCommandSupport` 的守卫/哈希/信封同源委托 |

## 2. 行为等价性（断言语义冻结）

- **稳定标识与文案逐字保留**：`io.crewscope/knowledge-distiller`、`io.crewscope/skill-distiller` 的 principal/profile UUID 命名空间；`system/knowledge-distiller/`、`system/skill-distiller/` 命令坐标；`knowledgeDistiller.*`/`skillDistiller.*` 错误键；`knowledge-distiller@1`/`skill-distiller@1` 模板标签；恢复指令与输出 schema 生成逻辑。
- **检查顺序逐字保留**：Knowledge `create`（nonNegative→entryId null→hash）与 Skill `create`/`updateDraft`/`mutatingCommand`（skillEnabled→id null→nonNegative→hash）保持各自原序；两侧 Provisioning 的 11 个构造参数 NPE 顺序与消息不变；Distillation 四阶段（校验→模型调用→usage facts→提交）边界不变。
- **requestHash 语义不变**：字段序、usage fact 的 `#usage-N` 后缀、零事件写 receipt 以 commandId 为 anchor、rollback/disable 的 body 语义字段全部原样。
- **类型隔离的拒绝路径不变**：两侧 deterministic id 不同，跨 kind session 仍由 stable 身份校验拦截；`Kind.DISTILLER`/`Kind.SKILL_DISTILLER` 两个 security chain 值未合一。
- **泛型模板不引入新契约**：`DistillerProvisioningTemplate` 的 SPI 全部为包内私有函数接口；`TeamCommandSupport` 对外仅暴露既有命令语义的等价方法。

## 3. 登记不收敛（勘察结论）

| 对象 | 理由 |
| --- | --- |
| `KnowledgeDistillerRepository` ↔ `SkillDistillerRepository` | 单 JPA 适配器双实现；`findDistillerForTeam`/`findSkillDistillerForTeam` 刻意区分以规避泛型擦除冲突，接口合一会改调用名与适配器结构 |
| Filter/Page/PageRequest/VersionPage/VersionPageRequest 五对 | 各约 20 行冻结 API 的 record；类型别名/继承不可行，泛型化会改访问器名与错误文案，无净收益 |
| server 两 `DistillationAdapter` | 计划表 C04 行明确归属（209↔206，81%），本包不越界 |

## 4. 行数盘点（按各切片 diff 如实入档）

| 切片 | 插入 | 删除 |
| --- | --- | --- |
| 1 | 138 | 136 |
| 2 | 187 | 225 |
| 3 | 539 | 517 |
| 4 | 333 | 415 |
| 5 | 481 | 367 |
| 6 | 550 | 522 |
| **合计** | **2,228** | **2,182** |

**净增 46 行**。原因：本包的目标是单一逻辑源而非删行——共享契约/骨架必须显式承载两侧差异（模板契约 212 行、初始化契约 243 行、provisioning 模板 447 行、命令支持 343+225 行），两侧薄壳保留公开 API；切片 2 与切片 4 为真实净删（−38、−82），其余切片为结构收益。计划 §2 的 ≥2,000 行净删指标由 C02/C03/C04/C05 累计核算，Q01 终盘点如实交代。

## 5. 测试证据

| 面 | 结果 |
| --- | --- |
| application 模块 verify（`-pl crewscope-application -am`，含上游 domain） | BUILD SUCCESS；application **1184 测试 0 失败 0 错误**（既有基线维持） |
| 命令与蒸馏定向 | KnowledgeCommandServiceTest 18/18、TeamSkillCommandServiceTest 19/19、KnowledgeDistillationServiceTest 12/12、SkillDistillationServiceTest 14/14（合计 63） |
| Provisioning 定向 | KnowledgeDistillerProvisioningServiceTest 4/4；server TeamSkillConfigurationTest 4/4 |
| agentscope 模块 verify（切片 2 时点） | BUILD SUCCESS——249 测试 0 失败 0 错误 2 skipped |
| domain 模块 verify（切片 3 时点） | BUILD SUCCESS——892 测试 0 失败 |
| 断言修改 | 仅切片 1 的两个 DistillationServiceTest 改 import/构造路径；断言值与意图零修改。其余测试文件零改动 |
| S01 分层门禁 | `node scripts/check-module-boundaries.mjs` PASS（六个切片逐次复跑） |

## 6. 复跑入口

```sh
./mvnw -pl crewscope-application -am verify
./mvnw -pl crewscope-application -am test -Dtest='KnowledgeCommandServiceTest,TeamSkillCommandServiceTest,KnowledgeDistillationServiceTest,SkillDistillationServiceTest,KnowledgeDistillerProvisioningServiceTest' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl crewscope-server -am test -Dtest='TeamSkillConfigurationTest' -Dsurefire.failIfNoSpecifiedTests=false
node scripts/check-module-boundaries.mjs
```

全 reactor 与 e2e 由 Q01 收口统一背书（M13 计划 §6 收批纪律）。
