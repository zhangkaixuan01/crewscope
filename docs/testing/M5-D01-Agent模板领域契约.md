# M5-D01 Agent 模板领域契约

> 任务：`M5-D01`<br>
> 日期：2026-08-22<br>
> 状态：通过<br>
> 关联决策：[ADR-016](../adr/ADR-016-Agent所有权、模板与执行配置.md)

## 1. 交付范围

M5-D01 在 `crewscope-domain` 与 `crewscope-application` 交付：

- `AgentOwnership` 与 `USER/TEAM/ORGANIZATION` 所有权坐标；
- `AgentRuntimeRole` 与 `AgentExecutionScope`；
- Organization/Team 两类 `AgentTemplatePublisherScope`；
- 稳定 `AgentTemplateKey`、连续追加 `AgentTemplateVersion` 和 PreviousVersion 链；
- `AgentTemplateCapabilities`、声明能力、所需模型能力和 CapabilityHash；
- `AgentTemplatePolicy`、System Prompt 基线、Tool、批准 Skill、Structured Output Schema、成员/管理员可配置槽位和 PolicyHash；
- `AgentTemplateDefinition` ContentHash、ACTIVE/DISABLED/ARCHIVED 生命周期和 Scope 校验；
- `AgentTemplateRepository` 追加定义与乐观生命周期 Port；
- 模板实例化 `AgentProfile`，显式保存 Ownership、RuntimeRole 和 TemplateVersion；
- M2–M4 AgentProfile 的确定性兼容投影与默认 Personal 唯一链路。

Model Catalog、ModelConnection、AgentConfigurationVersion、双执行 Binding 和 V20 持久化分别由 M5-D02、D03、D04 与 D10 交付，不进入本任务。

## 2. 固定领域规则

1. Ownership、RuntimeRole、TemplateVersion 和 ExecutionScope 分别建模，不从显示名、Prompt 或输出文本推断。
2. Template Key 在 PublisherScope 内稳定；新版本只能引用同 Key 的直接前一版本，旧版本对象与 Hash 不变。
3. Team Publisher 不能发布 ORGANIZATION-owned Agent，也不能向其他 Team 实例化。
4. Template 停用阻止新 Agent；历史 Profile 继续引用原 TemplateVersion。
5. System Prompt、Tool 和 Structured Output Schema 是平台固定边界，不属于成员可配置槽位。
6. 用户补充指令需要模板显式开放槽位；运行 Tool 只能收窄，Schema 必须精确匹配。
7. 只有 USER-owned Personal Assistant 可以标记为默认 Profile；Repository 继续按 TeamMember 原子保证唯一。
8. 旧 `PERSONAL/TEAM/SPECIALIST` 只投影为 `personal-assistant@1/team-coordinator@1/coding@1`，稳定 ID、版本和审计不变。

## 3. Hash 闭合

```text
CapabilityHash = SHA-256(声明能力 + 所需模型能力)
PolicyHash     = SHA-256(System Prompt + Tool + Skill + SchemaHash + 配置槽位)
ContentHash    = SHA-256(PublisherScope + Key/Version + PreviousVersion
                         + RuntimeRole + Ownership/ExecutionScope
                         + CapabilityHash + PolicyHash)
```

集合按稳定键排序，字符串采用长度前缀编码。`reconstitute` 会重新计算 Hash 并拒绝不一致事实；Template Lifecycle 状态不覆盖不可变内容 Hash。

## 4. 自动化验证

新增测试：

- `AgentTemplateDefinitionTest`：7 个场景；
- `AgentProfileTemplateTest`：6 个场景。

联合专项同时包含既有 Profile 生命周期、M5-S02 兼容和默认 Personal 原子唯一测试：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-application -am \
  -Dtest=AgentTemplateDefinitionTest,AgentProfileTemplateTest,AgentProfileTest,\
AgentOwnershipM5S02CompatibilityTest,DefaultPersonalAgentServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：

```text
Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Domain 与 Application 全量回归：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-application -am test
```

结果：Domain `428 / 428`、Application `319 / 319` 通过。

全仓 Maven 回归：

```bash
./mvnw --batch-mode --no-transfer-progress test
```

结果：`1553 / 1553` 通过，`BUILD SUCCESS`。分模块计数为 Domain 428、Application 319、AgentScope Adapter 118、Integration 1、Infrastructure 470、Server 217。

文档链接门禁：

```bash
/Users/zhangkaixuan/.nvm/versions/node/v20.20.0/bin/node scripts/check-doc-links.mjs
```

结果：199 份 Markdown 文档链接全部通过。`git diff --check` 同步通过。
