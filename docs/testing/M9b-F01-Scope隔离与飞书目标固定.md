# M9b-F01：Scope 隔离与飞书目标固定

> 日期：2026-09-22；历史批次：本批代码与自动化验证已交付，当时 F01 父包仍进行中。当前 F01 已完成，见 [完整关闭记录](M9b-F01-安全命令与请求隔离完成.md)；工作区未提交/推送。<br>
> 前置：[S01 核心流程与合同冻结](../spikes/M9b-S01-核心流程与合同冻结.md)、[接口权限细表](../spikes/M9b-S01-接口权限与查询冻结.md)；承接 [安全提交首批](M9b-F01-安全提交首批实现.md)。范围仍以 [M9b 主计划](../plans/M9b-核心流程与使用体验收口.md) 为准。

## 1. 切片边界

| 项 | 本批结果 |
| --- | --- |
| 父包/切片 | F01/scope-isolation 的公共机制及 GitHub 先行接线；F01/lark-target 的验证、确认和轮换固定目标 |
| 用户结果 | 切换 Team/连接/项目后不显示旧请求结果；导入读取失败可重读原任务；飞书旧证明不能误绑新成员；轮换不静默升级版本 |
| 关联 | R04/R35、C03/C12 的部分反例；不是这些完整验收组或 F01 的关闭证据 |
| 未改变 | 现有 API、幂等和授权规则、后端业务实现、四服务 HTTP 部署、API 内含 Worker；没有新增服务或 TLS 要求 |
| 执行约束 | 不启动已停业务/Compose 服务、不使用真实 Provider 凭证、不发送通知；无真人验收、无新依赖/数据库迁移 |

## 2. 实现与行为

### 2.1 请求坐标与代次

[requestScope.ts](../../crewscope-web/src/api/requestScope.ts) 提供 `capture / begin / invalidate / dispose`：

- 坐标检查与单调递增代次共同拒绝旧响应，A→B→A 不能仅因字符串相同恢复旧请求。
- 同一通道新读取中止旧读取；不同通道独立。旧 finally 不能结束新请求。
- Abort 只是节约读取资源；即便适配器忽略 AbortSignal，写回前仍检查坐标与代次。
- 卸载后所有读取/回调失效。**不声称中止客户端等待能撤销已发送的服务端命令**。

### 2.2 GitHub 页面

[GitHubSettingsPage.vue](../../crewscope-web/src/pages/GitHubSettingsPage.vue) 分别管理页面、连接详情、导入请求范围：

- 页面坐标包括账号/安全版本、Principal、Organization、Team 与管理权限；连接详情增加选择对象，导入增加 Project 与仓库。切换同步清理旧事实、忙态、凭证输入和轮询。
- Connection 列表及仓库/健康/授权详情只接受当前请求；详情三组事实一起发布，不拼接不同连接的结果。
- 验证、同步、撤销固定原连接；撤销确认弹窗打开后切换目标，不再派发旧撤销。晚到验证不把用户切回旧连接。
- 导入创建/取消/重试捕获原 Team、Project、job。计时回调和状态查询均受代次保护；切换或卸载停止后续读取。
- 状态读取失败保留原 job，并提示“原任务未被判定失败”，提供“重新读取状态”。恢复只读原 job，不重新创建；`CANCELLED` 显示“导入已取消”并停止轮询。
- 旧创建完成不能关闭新打开的凭证表单；清理表单推进独立代次，旧 finally 不能覆盖新表单状态。
- 导入字段锁定及命令在途按钮关联可见原因；同时补齐首批注册成功后的字段锁定说明。AuthField/AuthPasswordField 将禁用原因与原 hint/error 一起关联到原生输入，注册页回归验证四个字段的读屏描述。

这里未实现 A02 的 GitHub 授权/RepositoryBinding 新流程、导入历史与跨刷新恢复；创建/重试命令的未知结果意图接线仍需后续完成。读取失败恢复不等于所有导入提交都已具备幂等重放体验。

### 2.3 飞书验证、确认与轮换

[LarkNotificationAdmin.vue](../../crewscope-web/src/components/domain/LarkNotificationAdmin.vue) 与 [LarkSettingsPage.vue](../../crewscope-web/src/pages/LarkSettingsPage.vue) 使用请求专属回调传递 Proof，不再监听共享成功回执并绑定“当前成员”：

1. 验证时固定原成员、Connection/Binding 及版本；立即清空精确 open_id 输入。只有本次验证的回调能安装 Proof。
2. 成员、连接、授权版本/状态或外部身份输入变化即失效；即使改回原选择，旧验证回调也不能恢复证明。切账号/Team 重建组件，卸载清理。
3. 确认携带原成员/Binding/Proof 与原幂等键；失败/未知后同一表单重试仍使用同一键，不产生新映射意图。确定成功清空证明；切目标后的迟到回调不能更改新目标。
4. 打开轮换弹窗时固定连接 ID 和观察到的版本；连接/版本变化关闭并擦除凭证。Store 不把原版本悄悄升级为随后读取的新 ETag，由后端按原 If-Match 判定冲突。
5. 页面 await 后刷新/选择/导航也检查范围。偏好保存、再次投递、撤销映射的完成回调不向新 Team 发起后续刷新。

[TeamOpsStore](../../crewscope-web/src/domains/teamops/store.ts) 额外固定共享命令槽身份；旧命令被清除后，其 success/error 不覆盖后来的命令。需要先读详情再写入的命令还检查 Scope generation 和 command epoch，切 Team 或同 Team 清除原意图后不再派发写入。清除槽不撤销服务端已经受理的写入。

### 2.4 后端 Proof 合同核对

核对 [映射应用服务](../../crewscope-application/src/main/java/io/crewscope/application/collaboration/LarkMemberMappingApplicationService.java)、[管理命令服务](../../crewscope-application/src/main/java/io/crewscope/application/collaboration/LarkAdministrationCommandService.java)、[Proof 领域对象](../../crewscope-domain/src/main/java/io/crewscope/domain/collaboration/LarkMemberVerificationProof.java) 和既有测试，结论如下：

- 验证回执的 `domainEventId` 承载 Proof ID；前端沿用现有协议，没有创建新的结果字段。
- **验证阶段的后端 Proof 不绑定 CrewScope 内部成员**，而是绑定外部身份、Organization/Team/Binding、授权/凭证版本、租户与有效期。
- 确认请求显式指定内部成员；服务端再检查成员有效、精确 Team/Binding、当前授权/租户/版本、Proof 期限及内外部映射唯一性。
- 因而本批前端固定用户选择的成员是必要约束，不能把它描述成“后端已经记录该成员”。本批无需修改后端业务代码；持续撤权和成员生命周期仍按 A07 推进。

## 3. 自动化证据

| 层次 | 覆盖/结果 |
| --- | --- |
| 前端全量 | 最终完整运行 167 文件、994 测试通过；包含既有基线，不是 994 项新增测试 |
| 本批相关用例 | requestScope、GitHub 页面、飞书组件/页面、TeamOpsStore 共 5 文件、50 用例；含已有用例，新增 32 项 |
| 后端定向回归 | LarkCollaborationDomainM6D04Test 5 项 + LarkMemberMappingM6D04Test 8 项通过，BUILD SUCCESS；不是后端全量回归 |
| 构建与浏览器 | vue-tsc/Vite 通过；2 项隔离 Chromium HTTP 测试通过，继续验证首批安全随机/注册同键规则，不冒充飞书/GitHub 真实 Provider 端到端 |
| 质量 | TypeScript、ESLint、Stylelint、format、敏感字段、裸枚举、Q01 全套及 bundle 门禁通过；最大 JS 409,070 字节，总 JS 1,383,338 字节，未降低预算 |
| 冻结清单回归 | S01 contract-prototype 的 6 项本地测试通过；不代表本批重跑容器 Spike 或业务后端 |

页面用例使用真实 Vue 页面/组件、Router、ScopeStore、TeamOpsStore，Gateway 使用受控 fixture 和延迟 Promise，覆盖忽略 Abort 的迟到响应。GitHub 另测导入轮询切项目/卸载、读取失败恢复、取消终态、撤销弹窗目标变更及旧表单回执；飞书另测同一证明确认丢响应重试、成员切换后新旧验证交错、切连接/Team/卸载、轮换版本传递。组件覆盖目标 A→B→A 与授权版本漂移；Store 覆盖同 Team 清理和跨 Team 预读取中止写入。

补充门禁发现注册锁定字段/导入按钮缺少禁用说明，已补真实文案及 ARIA 关联并回归，不新增豁免或放宽扫描基线。最终 disabled-reason 门禁为 0 个未解释绑定。

可复跑：

```sh
# crewscope-web 目录；全量测试与生产构建顺序执行
pnpm test
pnpm check:quality
pnpm check:sensitive
pnpm check:enum-labels
pnpm check:q01
pnpm test:e2e:m9b-f01
pnpm check:bundle
```

```sh
# 仓库根目录，使用项目要求的 JDK 17
./mvnw -pl crewscope-application -am -Dtest=LarkCollaborationDomainM6D04Test,LarkMemberMappingM6D04Test -Dsurefire.failIfNoSpecifiedTests=false test
node --test scripts/spikes/m9b-s01/contract-prototype.test.mjs
node scripts/check-doc-links.mjs
git diff --check
```

专用 Playwright 配置无 webServer；浏览器加载构建产物、拦截全部网络、API 使用模拟数据。没有启动产品服务或调用真实 GitHub/飞书。

## 4. 本批结束时的交接（历史，当前进度见完整关闭记录）

1. **F01/safe-command**：其余写入口逐项接入意图状态和原请求恢复；包括 GitHub 创建/导入、飞书创建/轮换等。映射确认的当前表单同键重试不能替代所有凭证命令的 unknown 处理，也没有跨刷新恢复。
2. **F01/scope-isolation**：继续按 S01 全页面清单推广账号/对象/请求代次与 await 后续行为隔离。本批 GitHub/飞书覆盖不等于全站已完成。
3. **F01/lark-target**：核心防错目标链路已补齐，仍需结合上述 unknown 处理完善原事实读取/恢复提示和剩余反例；保持父切片未勾选。
4. **A01/A02/F04**：持久创建结果、准确定位、GitHub 导入恢复及集成菜单产品化按各包交付；不把当前对话列表/标题猜结果、显式无效飞书连接回退首项等旧问题写成已修复。

本报告只记录这批实现与可复跑证据；未提交/推送，不提前关闭 F01 或启动下游 A01。
