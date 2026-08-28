# M6-Q01 团队观测固定攻击集与安全加固

> 任务：`M6-Q01`<br>
> 状态：已完成<br>
> 日期：2026-08-27<br>
> 范围：Activity、Inbox、Audit、Team Observer、Lark/Notification、Operations、Web 数据层与路由层

## 1. 目标

M6-Q01 把团队观测和运维能力的权限、披露与只读边界固化为可重复运行的攻击门禁。固定攻击集必须证明：

- 越权工具和资源访问阻断率为 100%；
- Team Observer 可用工具始终为五个只读工具，写调用为 0；
- Secret、PII、原始 Provider/DomainEvent/Audit Payload、Prompt、Tool 参数和运行时租约泄漏为 0；
- 普通成员可执行的投影重建、Dead Letter 重放和通知再次投递命令为 0；
- Cursor 不能跨 Organization、Team、Generation、Projection、Correlation 或 Filter 重放；
- Lark Credential、`open_id` 和长期 Secret 只存在于单次命令调用栈，不能进入公开 DTO、Store State、Story、日志或证据链接。

## 2. 固定攻击集

固定攻击集冻结 110 个独立样本。每个样本使用稳定编号，样本减少会使安全门禁失败。

| 分组 | 编号 | 数量 | 攻击面 | 预期结果 |
|---|---:|---:|---|---|
| Cursor 规范化与签名边界 | `CU-01`–`CU-36` | 36 | 六类 Cursor 分别注入 null、空白、非法字符、超长 Token、非规范 Padding、截断 Token | 全部返回稳定 `invalid_cursor`，不进入查询 |
| 公开 Projection 泄漏 | `LK-01`–`LK-50` | 50 | Activity、Inbox、Audit、Correlation、Lark、Notification、Operations、Team Observer 的公开 Record 形状 | Record 字段名不含 Secret、Token、原始 Payload、Prompt、Tool、租约、数据库与 Provider 私有坐标 |
| Web 证据与导航路由 | `WR-01`–`WR-24` | 24 | 外部 URL、协议相对 URL、Query/Fragment、编码绕过、路径遍历、反斜杠、跨 Organization/Team、未批准资源、非法 UUID | 全部拒绝生成站内导航路径 |
| 合计 |  | **110** |  | **110/110 阻断** |

固定样本之外，一键门禁继续运行既有行为攻击集，覆盖成员资格撤销、跨 Team Session、PERSONAL Model Connection、写 Tool 扩权、Prompt 注入、Structured Output 未知字段、重复 Evidence、固定模板变量/版本替换、映射漂移、强确认篡改、陈旧版本、幂等冲突和普通成员运维命令。这些行为用例不计入 110 个固定分母，避免后续正常增加回归用例改变固定指标。

## 3. 安全边界

### 3.1 Cursor

- Team Activity Cursor 使用 HMAC，绑定 Organization、Team、Projection、Generation、Schema、Filter、位置和有效期；
- Audit、Correlation、Lark Mapping、Notification Delivery Cursor 使用 HMAC 并绑定各自 Scope 与 Filter；
- Inbox Cursor 使用完整定长规范编码，绑定 Organization、Team、Generation、Filter 和位置；
- 任意 Token 格式、签名、Scope、Filter、版本、时间或 Key Ring 校验失败均关闭为 `invalid_cursor`；
- 只有已通过签名和 Scope 校验的 Team Activity Cursor 才能返回 `cursor_expired`。

### 3.2 Team Observer

- 固定工具集合为 `team.activity.read`、`team.inbox.summary.read`、`workitem.summary.read`、`task.summary.read`、`artifact.summary.read`；
- 每次 Tool 调用和 Evidence 解析重新验证当前成员与 Team Scope；
- 只允许 TEAM/ORGANIZATION Model Connection，拒绝 PERSONAL Connection 和 PERSONAL Execution Scope；
- Prompt 按纯文本数据处理并完成 XML 转义；Structured Output 使用闭集字段、闭集 Scope、连续且唯一的 Evidence；
- AgentScope Runtime 增加写工具、未知工具或身份漂移时，在调用前或结果公开前失败关闭。

### 3.3 Lark、Notification 与 Operations

- Lark Secret、Tenant Key 和 Open ID 是一次性命令输入；公开 DTO 仅保留掩码、内部安全引用、状态和版本；
- 成员映射必须绑定当前 Organization、Team、Connection、Grant Proof、Provider Binding 与版本；
- Notification 只使用固定模板和变量白名单，模板、收件人、Binding、Policy 或变量 Hash 漂移均拒绝投递；
- Operations 查询对成员只公开低基数健康摘要；诊断、重建、切换、取消、失败、Outbox/Projection 重放和通知再次投递仅管理员可用；
- 危险命令绑定精确目标、版本、强确认和 Idempotency Command ID，授权失败时 Repository、Verifier 和外部 Provider 零交互。

### 3.4 Web

- `teamops/types.ts` 和 `teamobserver/types.ts` 是公开浏览器 DTO 闭集；
- `TeamOpsStoreState`、`ActivityRealtimeState` 和 `TeamObserverState` 禁止保存 Secret、Token、Open ID、Tenant Key、原始 Payload、Prompt 内部数据、Provider Message ID 和运行时租约；
- Gateway 可以在单次命令调用栈接收 `appSecret`、`tenantKey`、`openId`，返回值必须重新按公开 DTO 白名单构造；
- Story 禁止敏感字段和疑似真实凭证；Evidence 只允许映射到当前 Team 的 Activity、Inbox、WorkItem 和 Task 路由。

## 4. 自动化入口

```bash
./scripts/m6-q01-security-gate.sh
```

门禁分四层执行：

1. 运行 110 个固定攻击样本并校验稳定分母；
2. 运行 Application、Controller、AgentScope、Notification、Projection 与 Operations 行为安全回归；
3. 运行 TeamOps/TeamObserver/三流恢复 Web 安全回归及敏感字段静态扫描；
4. 执行受影响模块格式、编译、测试和生产构建。

## 5. 验收记录

执行命令：

```bash
export PATH=/Users/zhangkaixuan/.nvm/versions/node/v24.13.1/bin:$PATH
./scripts/m6-q01-security-gate.sh
```

结果：

| 门禁 | 结果 |
|---|---|
| Cursor 固定攻击 | `36 / 36` 阻断 |
| 公开 Projection 泄漏探针 | `50 / 50` 阻断 |
| Web Evidence 路由攻击 | `24 / 24` 阻断 |
| 固定攻击合计 | `110 / 110` 阻断 |
| Java 固定与行为安全回归 | `173 / 173` 通过，零失败、零跳过 |
| Web 固定与行为安全回归 | `83 / 83` 通过 |
| Web 敏感字段扫描 | `40` 个生产文件、`14` 个 Story 通过 |
| Maven 全量回归 | `2430 / 2430` 通过，零失败、零错误、零跳过 |

本任务发现并修复一个门禁覆盖缺口：既有 Web 扫描只覆盖 Agent、Model、Review 和 Delivery，没有扫描 M6 的 TeamOps、TeamObserver、Activity、Inbox、Audit、Lark/Notification 与 Operations 数据层。扫描范围现已扩展，并增加 M6 公开 DTO 与 Store State 专项规则；合法的 Lark Secret/Open ID 单向输入保持可用，但不能进入公开状态。

全量回归同时发现一个测试夹具随机性：M6-I02 恢复测试把随机 UUID 拼入公开 `projectionName`，随机数字段可能被安全摘要边界识别为 phone-like PII。夹具已改为确定性的安全名称，生产 PII 拦截规则保持不变，恢复路径专项回归 `4 / 4` 通过。

最终指标：越权工具与资源访问阻断率 `100%`；Team Observer 写工具与写调用 `0`；Secret、PII、原始 Payload、Prompt 内部数据和 Provider Body 泄漏 `0`；普通成员 Projection 重建、切换、失败和 Dead Letter/Notification 重放命令 `0`。M6-Q01 验收完成；后续 M6-Q02 固定故障与恢复攻击集、M6-Q03 Canonical/Release Candidate 和 M6-Q04 MVP Release Gate 均已完成。
