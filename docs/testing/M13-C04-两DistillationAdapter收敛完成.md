# M13-C04：两 DistillationAdapter 收敛 — 完成记录

> 来源：[M13-后端结构收敛执行计划](../plans/M13-后端结构收敛.md) C04 工作包（Review §3.1 server 行）。
> 性质：纯结构收敛（零行为变化、零 API 契约变更）；Adapter 非 Controller，OpenAPI 指纹不受影响（已复验）。

## 1. 交付：共享执行图 + 两薄壳

新增 `crewscope-server/.../config/application/DistillerExecutionGraph`（包私有，237 行），承载两侧原本逐行重复的解析链：

| 阶段 | 共享内容 |
| --- | --- |
| Team 校验 | `findById + isActive` → `distillation.teamId` 422 |
| Distiller 对 | `stableProfileId` + 模板契约 `requireProfile` → `<prefix>.agentProfile` 422 |
| 当前配置 | `findCurrent` → `<prefix>.configuration` 422 |
| 精确模板 | Organization 作用域 + 版本 + contentHash 精确匹配 + `requireDefinition` → `<prefix>.template` 422 |
| 可用连接 | TEAM + ORGANIZATION 连接合并、按 id 排序（USER 连接永不进入） |
| 发起人 | `principals.findById` 存在性校验 → `distillation.initiatedBy` 422 |
| 解析链 | `governance.resolve` → `resolver.resolve` → `registry.requireCoordinates` → `assembler.assemble` |
| 会话 | `DistillerRuntimeSession(kind, org, team, initiatedBy, agentPrincipal, profile, version, commandId)` |
| usage 归因 | `attribution(observedModelName, attempts)`：provider/model/connection/version 统一折叠 |

两侧 Adapter 变为 117/116 行的薄壳：声明 per-kind `Contract`（kind、模板版本、stableProfileId、两个域校验钩子、错误前缀与三条文案），构造共享图，组装各自的 RuntimeRequest（Knowledge 的 category 提示 vs Skill 的 skillKey）与 Result 类型。

## 2. 行为等价性

- 所有 422 错误键与文案逐字保留：`distillation.teamId`、`knowledgeDistiller.agentProfile/configuration/template`、`skillDistiller.agentProfile/configuration/template`、`distillation.initiatedBy`。
- 解析顺序逐字保留：team → profile → configuration → template → usableConnections → actor → governance → resolver → registry → assembler。
- 连接集合语义不变：TEAM 与 ORGANIZATION 合并、按 id 字符串排序、USER 永不进入。
- usage 归因折叠逻辑逐字保留（observedModelName 优先，否则 catalogCoordinate.modelId）。
- 构造器 12 参的 null 检查顺序与消息不变（适配器内显式局部校验保持 registry → runtime → timeProvider 原序）。
- Skill 请求仍携带 skillKey（D4），Knowledge 请求仍携带 category 提示与 `MODEL_SUGGESTED_CATEGORY` 兜底。

## 3. 行数盘点（如实入档）

| 口径 | 数值 |
| --- | --- |
| 两 Adapter 基线 | 421 行（212+209） |
| 两 Adapter 现况 | 233 行（117+116） |
| 共享执行图 | 237 行 |
| 合计 | 470 行，**净 +49** |
| 追加重合度 | 原 81% 重复面归零；per-kind 差异显式化为 `Contract` 的 9 个字段 |

净增原因与 C03 同：显式契约（9 字段）+ 泛型分发骨架的固定成本高于两文件内的直接重复；结构目标是解析链单一来源，不以行数充作收益。C02/C03/C04/C05 累计净删在 Q01 统一盘点。

## 4. 测试证据

| 面 | 结果 |
| --- | --- |
| server 定向 | KnowledgeDistillationControllerTest 4/4、TeamSkillControllerTest 15/15、TeamSkillConfigurationTest 4/4（合计 23） |
| server 模块 verify（`-pl crewscope-server -am`） | BUILD SUCCESS：server 模块 1096 用例，0 失败 / 0 错误 / 2 跳过（2026-10-19 23:10） |
| OpenAPI 漂移 | `node scripts/check-openapi-drift.mjs` exit 0——操作数 306、状态机 18 聚合、GenerateOptions 4 字段均未变；Adapter 不在 api/ 包，无指纹触发 |
| 生成物指纹 | C03 的 domain 源码改动使 `state-machines.ts` 的 SHA 头过期，已单独提交 `10a7db5` 被动再生（仅头注释变化） |

## 5. 复跑入口

```sh
./mvnw -pl crewscope-server -am test -Dtest='KnowledgeDistillationControllerTest,TeamSkillControllerTest,TeamSkillConfigurationTest' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl crewscope-server -am verify
node scripts/check-openapi-drift.mjs
```

全 reactor 与真实栈由 Q01 收口统一背书。
