# ADR-031：Skill 沉淀与多 Agent 协同拓扑

> 状态：Skill 部分 ACCEPTED（M10-S01 设计冻结；A03a 目录/审批版本合同已交付 2026-10-04，见[Skill 目录与审核 API 契约](../api/M10-Skill目录与审核API契约.md)；**A03b 真实提炼端点与 Coding 执行接线已于 2026-10-04 交付，A03 父包关闭**——提炼门=本人选择权（任务创建者），skillKey 为命令参数模型只产 description/body，产物 DRAFT；保存侧上限=policy 上限 ∪ Team 目录 PUBLISHED keys（扩展域经 append-only 版本行哈希对账）；执行加载 allow-list 按快照钉住的 approvedSkillKeys 构建，注入清单 INJECTED `SKILL_INSTRUCTION` refs 即 (skillKey, revision, contentHash) 加载证据，与只读内存仓库同源、hash 不一致 fail-close；injection.enabled=false 时动态不加载、内置照常）；拓扑部分 **PROPOSED-待选入**（仅 E01 选做时激活，S01 未冻结，本文不构成拓扑承诺）<br>
> 日期：2026-10-01；源码基线：`6645c9a`<br>
> 归属：M10-S01/A03/F02；拓扑增量归属 E01/F02/F03<br>
> 关联：[S01 冻结记录](../spikes/M10-S01-知识与检索合同冻结.md)、[ADR-030 三层模型](ADR-030-知识与记忆三层模型.md)、[ADR-016 Agent 模板与执行配置](ADR-016-Agent所有权、模板与执行配置.md)、[ADR-017 Reviewer 与 Human Gate](ADR-017-Reviewer证据与人工Gate边界.md)。

## 背景与源码基线

现状（S01 取证）：`CodingSpecialistSkillBundle` 是唯一准入的只读 Skill Bundle——常量 `SKILL_NAME="java-spring-v1"`、classpath 资源 `coding-skills/java-spring-v1/SKILL.md`、硬编码 SHA-256 pin，`openRepository()` 每次整体哈希比对并校验「恰好 1 个 skill」；`CodingSpecialistFactory` 以 `SkillFilter.only(内置)` 固定过滤，叠加 `disableDefaultWorkspaceSkills()` 与九项禁用面（filesystem/shell/subagents/dynamic-subagents/memory 工具与钩子/workspace-context/at-path 扩展/tools-config），单一 repository 接线且每 HarnessAgent 独占实例；`SKILL_LOAD_TOOL` 在工具驱逐排除表；装配在 `TaskWorkerConfiguration`。当前没有任何动态/外部 Skill 通道，全部静态编译期钉死。agentscope 2.0.0 core 另提供 `FileSystemSkillRepository`/`MarkdownSkillParser` 与 harness `SkillCurator`/`SkillRuntime`（存在性事实，启用取舍由 A03 定）。

M10 主线要把「反复出现的执行套路」沉淀为 Team Skill 复用。本 ADR 冻结 Skill 部分合同；多 Agent 拓扑部分只在 E01 选入时回答。

## 一、Skill 沉淀（已冻结）

### 1. 发布授权与执行授权分开

- 发布授权：目标 Team 的 `SKILL_MANAGE` 发布权限 + 内容披露范围检查 + 工具声明审查 + 版本唯一性。发布时通常不存在未来任务，**不能用「当前 Task Tool Policy」替代发布授权**。
- 执行授权：每次执行求交当前 Task Tool Policy/Scope；版本与内容哈希写入执行快照。命中 Skill **不增加任何工具权限**；工具调用继续走既有鉴权与 Human Gate。注入检测不是安全边界，拒绝越权最终由工具授权保证。

### 2. 主线流（不做自动后台提炼）

成员显式选择已完成执行 → 提炼产生**草稿**（结构化 SKILL.md 草稿+来源披露；提炼调用产生独立用量事实，角色 `DISTILLATION`）→ 目标 Team 审批发布（不可变发布版本：版本号+内容哈希）→ 后续执行按已授权配置使用。不做所有成功任务自动提炼。

草稿不能把其他 Team、成员私有内容或凭证传播给更大受众——披露检查是 A03 的命令级校验。

### 3. Factory 接线：四处显式扩展（现状取证落点）

1. bundle 清单/pin 常量与「恰好 1 个」校验 → 放宽为「内置恰好 1 + 动态 N」，内置 pin 校验原样保留；
2. `SkillFilter.only(内置)` → allow-list（内置 + 本次执行已授权的 Team Skill 发布版本），按执行快照构建、不可运行时放宽；
3. 单一 repository 接线 → 内置 classpath repository + 受控动态 repository 组合（内容取自发布版本快照；每 HarnessAgent 独占实例约束保持；装载失败拒绝该 Skill 而非放行空内容）；
4. `TaskWorkerConfiguration` 装配点接动态供给（属性门 `crewscope.skill.enabled` 控制，同门 Readiness）。

不打开任意工作区 Skill 扫描；`disableDefaultWorkspaceSkills()` 等九项禁用面全部保持。

### 4. 版本、撤权与回滚

- `java-spring-v1` 为保留名，Team Skill 同名发布拒绝；关闭 `crewscope.skill.enabled` 只停 Team Skill 链路（提炼与执行），内置只读 Skill 照常工作，历史目录/证据可在授权下查看。
- 撤权/禁用在安全点阻止后续使用；普通更新不改写已开始的执行版本。回滚=激活历史内容的**新修订**，不修改历史事实。
- 恶意内容防线：Skill 正文不能突破 Tool Policy/Human Gate（固定攻击集验证）；不承诺文字过滤识别所有注入。

### 5. 验证

A03a：目录/审批/版本合同（幂等、披露、版本冲突 409 对齐既有约定）——**已交付（2026-10-04）**：domain/application/infrastructure/server 四层 + V61 迁移 + TEAM_ADMIN SKILL_MANAGE 回填 + `crewscope.skill.enabled` 写门（默认关），契约见[Skill 目录与审核 API 契约](../api/M10-Skill目录与审核API契约.md)。A03b：真实提炼、Factory 接线、真实后续任务加载指定发布版本、内置 Skill 回归、撤权不使用、同名拒绝、加载证据与运行快照一致。F02：目录/Diff/审核/发布/禁用/回滚/历史证据呈现。Q02：真实闭环第二次执行加载已发布 Skill。

## 二、多 Agent 协同拓扑（PROPOSED-待选入，未冻结）

以下仅为 E01 选入时的既定边界（来自主计划 §4.5/§6 E01 行），**不是已冻结设计**；E01a 领取时须先补齐拓扑合同评审并修订本节为 ACCEPTED：

- 只支持声明式固定拓扑（Planner → Coder → Reviewer，可配置启用项），不支持 Agent 自由创建子 Agent；
- 整体仍是一个 TaskExecution：复用既有状态机、Human Gate、恢复与对账语义，不新建并行执行内核；
- 段间产出结构化、可检查、可中断；版本化 Checkpoint 按段；总预算与分段证据；
- 包名 `agentscope.topology`（可选），不与既有执行编排混淆；
- F02/F03 增量契约随 E01 交付，未选入时无虚假入口。

重新评估条件：放开自由子 Agent、跨 TaskExecution 拓扑或独立执行内核时增补本 ADR；不得以「Agent 能力足够」为由绕过工具授权或 Human Gate 合同。
