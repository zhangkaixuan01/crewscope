# ADR-029：行级 Review 与配置体验合同

> 状态：ACCEPTED<br>
> 日期：2026-09-12<br>
> 影响里程碑：M9-S01、M9-A04、M9-A06、M9-F08、M9-F10<br>
> 关联文档：[M9 执行清单](../plans/M9-产品体验重构与设计系统.md)、[M9-S01 Spike 记录](../spikes/M9-S01-设计系统交互内核与高亮选型验证记录.md)

## 背景

Diff 是 Agent 交付价值被验收的主要界面。当前 Diff 没有行号、分栏、语法高亮和行级评论；Review 只能整体记录结论。配置页面同样缺少可读字号、脏态保护、版本差异和健康汇总，导致用户无法安全地配置 Agent、模型和连接。

## 决策

### 1. Diff 与行级评论

高亮库选型结论见 M9-S01 Spike：采用 Prism Core + 按语言懒加载的 grammar 组件。渲染使用 `Prism.tokenize()` 的 Token 流逐行生成安全 DOM，不使用未经清洗的 `v-html`；加载失败回退为纯文本。主题颜色映射到设计系统语义 Token，语言包通过动态 import 加载，主包增量预算不超过 30KB gzip。

评论锚点复用既有 `FindingLocation` 与 `ReviewDiffHunk`，不创建第二套文件/行号值对象。公开锚点字段为：

```text
(taskExecutionId, filePath, side, lineNumber,
 hunkHeader, lineContentHash, diffGeneration)
```

`side` 只能是 `OLD` 或 `NEW`；`lineNumber` 为正整数；`filePath` 是规范化仓库相对路径。`hunkHeader` 使用既有 Patch 解析器的 hunk 坐标，`lineContentHash` 用于快速判断行内容是否改变，`diffGeneration` 绑定当前 Diff 快照。

锚点状态只有 `ACTIVE` 与 `OUTDATED`：

- 当前 `diffGeneration` 相同、hunk 仍存在、文件路径和行坐标可由既有 hunk 解析器定位，状态为 `ACTIVE`；
- Diff Generation 变化、文件被删除/重命名、hunk 消失、行内容 hash 不匹配或无法安全映射时，状态转为 `OUTDATED`；
- `OUTDATED` 评论保留原文、作者和时间，只读展示“锚点已过时”，不得静默移动到相邻行或删除；
- 评论存在与否不改变 Review Decision、Gate Eligibility、状态机和 Delivery 路径。

行级评论写入使用独立 Idempotency-Key 和强 ETag；读取按 Task/Execution/ReviewRequest Scope 授权。Patch 正文仍沿用既有 Artifact 权限和分页校验，不进入评论 DTO。

### 2. 配置体验

配置页面统一收敛到 Settings 外壳，二级导航归位如下：

| Settings 分组 | 既有入口（路由） |
|---|---|
| 配置健康 | 配置中心（`/setup`） |
| Agent | Agent 中心（`/settings/agents`），Personal Agent/Team Agent 为页内入口 |
| 模型与凭证 | 模型目录、模型连接、凭证（`/settings/models`） |
| 项目与仓库 | WorkProject、受管仓库（`/settings/repositories`） |
| GitHub 集成 | GitHub Connection/导入（`/settings/integrations/github`） |
| 飞书集成 | Lark Connection/通知（`/settings/integrations/lark`） |
| 账号 | 账号与会话（`/account`） |
| 团队成员 | 成员只读目录（`/team/members`） |
| 运维 | 运行健康与诊断（`/operations`） |

不新增配置语义或写路径。输入控件字号至少 `--cs-text-base`（14px），标签和说明至少 `--cs-text-xs`（12px），控件高度使用 `--cs-control-md/lg`；参数边界同时出现在标签、`min/max/step` 和字段级错误文案中。API Key 提交前 `trim`，明文核对只存在于组件内存，不写 Store、URL 或日志。

表单脏态规则：

- 用户主动关闭、路由离开、刷新或切换页面时拦截并确认；
- Team/Scope 强制切换不阻塞导航，先把草稿按 Organization/Team/Project/对象写入带版本的本地草稿键，新 Scope 提供恢复入口；
- 损坏或未知版本草稿丢弃并回默认，不清空其他偏好键、不抛异常；
- 历史 Revision 只读，逐字段显示新增、删除和修改；不提供“应用历史版本”按钮，回滚只能创建新的正常 Revision。

配置健康唯一由 `application/setup/` 从既有权威事实派生，覆盖模型连接健康、凭证即将过期/已过期、集成撤销和成员/运行配置缺口。页面不自行聚合多个端点，也不创建健康状态表；每项返回稳定 `reasonCode`、责任方和受信站内 `actionKey`。

## 结果与验证

- 3000 行 Diff 的渲染基线、语法包懒加载、明暗主题和纯文本降级在 M9-F08 记录；Chromium 渲染预算由 `e2e/m9-performance.spec.ts` 验证并纳入 M9-Q01。
- 行级评论可创建、读取、失效和只读保留；失效映射与 `ReviewPatchHunkParser` 共用并有回归测试。
- 配置页面通过字号、脏态、参数边界、Revision 差异、配置搜索、枚举映射和健康派生测试。
- 新增端点进入敏感字段白名单扫描，禁止 Credential、Endpoint、Prompt、宿主路径和外链泄露。

## 重新评估条件

需要评论参与审批裁决、需要应用历史版本、需要新增配置状态字段、需要向第三方发送代码内容或更换 Patch 锚点解析器时，必须新增 ADR。新增 Diff 视图或配置页面只要复用本合同，不需要重新决策。
