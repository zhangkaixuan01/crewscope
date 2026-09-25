# M9b：创建结果与命令恢复契约

> 状态：2026-09-22 A01 前后端已接通（自动编号、准确创建/刷新恢复、基本编辑）；部署方式不变<br>
> 依据：[ADR-007](../adr/ADR-007-API命令与并发协议.md)、[S01 D01](../spikes/M9b-S01-核心流程与合同冻结.md#3-d01命令身份结果查询与编号)<br>
> 交付与验证：[完整验证记录](../testing/M9b-A01-创建编号恢复与基本编辑完成.md)；[首批记录](../testing/M9b-A01-持久创建结果首批.md)保留历史

## 1. 本批边界

项目创建、普通原生工作项创建、对话创建成功时，后端保存唯一资源坐标。它与业务事实、DomainEvent、Outbox、四字段 CommandReceipt 在同一个 REQUIRED 事务提交。任何一步失败全部回滚，不留下一个“成功回执但没有结果”的新命令。

原创建 API 的 HTTP 状态和四字段回执不变；相同类型/规范载荷/原键重放仍返回原回执，不重复创建，不重写结果。新读接口只查原事实，不执行命令。该机制不需要新增服务、强制 TLS、外部凭据或部署步骤。

当前覆盖的命令为 CREATE_WORK_PROJECT、CREATE_NATIVE_WORK_ITEM、CREATE_CONVERSATION。TaskIntent 确认、导入、邀请、执行等并未因此获得通用结果记录；它们的接线按各工作包推进，不能把“接口存在”当成全部命令均支持恢复。

## 2. 唯一读接口

```http
GET /api/v1/organizations/{organizationId}/command-results
Idempotency-Key: <原创建命令的键>
```

- 使用既有登录身份。键只从单值 Header 读取，不接受 query/path/body 替代。重复 Header、逗号多值、空白或非法格式按既有协议返回 400。
- 不把键写入 URL、日志或响应，不接受客户端提供 actor/team/resource 来覆盖服务端存储事实。
- 所有结果响应（含 400/401/404）不缓存：`Cache-Control: no-store`。跨域调用沿用现有允许该 Header 的 CORS 配置，不新增任意来源授权。

成功响应示例（UUID 仅为示意）：

```json
{
  "receipt": {
    "commandId": "11111111-1111-4111-8111-111111111111",
    "domainEventId": "22222222-2222-4222-8222-222222222222",
    "committedVersion": 0,
    "correlationId": "33333333-3333-4333-8333-333333333333"
  },
  "result": {
    "type": "WORK_ITEM",
    "organizationId": "44444444-4444-4444-8444-444444444444",
    "teamId": "55555555-5555-4555-8555-555555555555",
    "projectId": "66666666-6666-4666-8666-666666666666",
    "resourceId": "77777777-7777-4777-8777-777777777777",
    "committedVersion": 0,
    "stage": "COMMITTED"
  }
}
```

| type | projectId | resourceId |
| --- | --- | --- |
| WORK_PROJECT | 创建的项目 ID | 与 projectId 相同 |
| WORK_ITEM | 所属项目 ID | 创建的工作项 ID |
| CONVERSATION | null | 创建的对话 ID |

`receipt` 是原四字段回执，不是本次 GET 的请求身份。`result.committedVersion` 是创建提交时版本，不是当前对象版本；编辑、状态变化后也不修改它。三类本地原子创建的 stage 固定 COMMITTED，没有未完成子步骤，不表示已启动 Agent、执行成功或外部交付成功。详情必须用唯一坐标再查当前受权资源，不能据此制造实时状态。

不返回名称、标题、正文、请求体、请求 hash、凭据、操作者详情或原键。后续扩展新类型/多步骤阶段时必须补充授权、语义、测试与本契约，不用自由 JSON 或同名对象搜索绕过合同。

## 3. 授权与错误

1. 验证登录身份和 Organization；当前必须是有效 USER。
2. 按 Organization + 原键 + 当前 Principal 查询，原创建者必须与当前操作者相同。平台管理员不能恢复他人的命令。
3. 每次查询重新校验当前对象可见性：工作/项目复用 WorkItemAccessPolicy；对话复用现有 Conversation 可见性与参与规则。旧键不是访问令牌，撤权、退出团队、隐藏或删除对象后不继续披露坐标。

| 响应 | 含义与调用方下一步 |
| --- | --- |
| 200 | 原命令已提交，按唯一坐标读取/打开资源，不重新创建 |
| 400 | Header/Organization 参数格式无效；修正协议，不轮询错误参数 |
| 401 | 认证失效，走原登录流程；不改原键重发命令 |
| 404 command_result_not_found | 统一“不存在、其他创建者、当前无组织/对象权限、旧回执无结果”；显示“暂未找到可访问的结果” |
| 5xx | 查询基础设施失败，不伪装成 404/创建失败；保留未知事实供显式重查 |

错误沿用既有信封，404 不带资源/actor 详情。404 **不证明原命令未发生**：事务在途时记录不可见也会返回 404；因此不能触发换键重试或同名列表猜测。

## 4. 持久化、升级与兼容

- V40 新增 `command_result`：保存原 actor、命令身份/类型、Scope、唯一坐标、创建版本、COMMITTED 和创建时间，关联已完成回执。
- JDBC 写入必须位于已有事务；精确匹配原回执的 org/key/command/type/event/version/correlation 后才允许插入。复合外键约束 key、commandId、commandType 属于同一回执；actor/team/project 同组织约束继续生效。
- 每个 org/key、org/command 最多一条结果；UPDATE/DELETE 被拒绝，不覆盖历史结果，不增加自动 TTL，也不删除回执释放旧幂等键。
- 空库迁移与 V39 → V40 有旧回执升级均测试。旧回执没有结果，不猜测回填；旧命令重放保持原回执，无重复业务事实，也不会补造坐标。
- 迁移仅加表/约束，旧创建客户端仍可调用。不要把退回旧二进制当成恢复保证：旧代码不写新结果，期间命令会缺失自动定位能力。优先前滚修复；恢复备份可能丢失备份后的业务提交，需明确协调，不删除回执“重试”。

## 5. 前端创建与刷新恢复

- 项目、工作项、对话创建统一以原键查结果，替换按名称、列表差集或最新一项定位。命令第一次成功与原键重放使用同一条定位路径。
- 自动查询首次立即，随后 1/2/4/8 秒退避；单轮 30 秒、最多 8 次，任一预算达到即停止；429 尊重 Retry-After。离页/卸载/身份切换停止轮询，晚到回包不污染新 Scope。
- 200 后投影暂不可见：保留成功事实，按唯一坐标有界读取，并提供“再次确认”；不能扫描所有列表或重复创建。
- 刷新后只允许从身份隔离的最小恢复坐标恢复查询；不持久化规范请求体/秘密，不自动重建或重放原写请求。404/过期/存储丢失均不能转成“原命令未执行”。
- 本机命名空间 `crewscope.command-recovery.v1` 只保存当前 account/principal/organization/securityVersion 身份标记；每条恢复记录用独立子键保存 org/team/project、原 key、类型、时间。待确认状态由记录存在表达，不保存标题/正文/完整请求/返回对象。独立记录避免多标签页覆盖整份集合，确认只删除自己的记录，旧回包不能重写整个集合复活旧记录。
- 单身份最多 100 条、7 天；达到容量拒绝新发送，先确认已有结果。过期清理会提示“不代表原操作未提交”。存储不可用时保留内存并明确提示刷新无法恢复。退出/换账号只清理此命名空间；跨标签页身份变化停止查询并要求重新认证。
- 页面顶部“创建结果待确认”入口只做读操作；点击“再次确认并打开”按服务端 ID 打开受权对象。不在列表首屏的项目通过项目详情 GET 校验，不回落到第一个项目。显式非法 Team/Project 不静默改为别的范围。
- `work` / `conversation` 的快捷创建使用一次性 `create=1`。合法 Scope 就绪后先 replace 消费参数，再开既有表单，不发业务命令；刷新/后退不会重复开表单。来源 query 不整体复制，旧 task/execution/workItem 不带到新对象。

## 6. 最小创建与兼容编号

普通界面：项目只填名称；工作项只填标题（trim 后 1–240 字），描述可选，“更多选项”折叠类型/优先级/标签/到期时间，默认 TASK/MEDIUM；对话填标题（1–200 字），默认 PRIVATE。标签 trim/去空/去重，到期时间按界面标注的本地时区转换。创建不运行 Agent、不发送消息。

原 POST 路径不变。项目与普通原生工作项的 `key` 现在可省略/null；显式空值/非法格式仍拒绝，绝不忽略旧客户端传入的合法 Key。普通创建与 TaskIntent 确认共用项目行锁和 `nextKey`，归档/取消不回收编号；只在幂等 reserve 成功之后分配。显式旧 Key 的 hash 保持原样，自动键使用不会与合法 Key 相撞的空标记。M0 内部 `WorkItemApplicationService` 已核对无 Server 生产装配，不是新的公开创建入口。

项目代号在 Team 锁内生成（显式 Key 创建也拿同一锁）：提取 ASCII 字母数字→大写，首字符不是字母则加 P，空值用 PRJ，单字符补 P，基础最多 6 位；碰撞从 2 起追加数字，必要时截断基础以保证总长 ≤10。例如“中文项目”→PRJ、PRJ2；“123”→P123；“a”→AP。旧 ID/编号/深链不修改，不承诺号码绝对连续。

## 7. 基本内容编辑

```http
PATCH /api/v1/organizations/{org}/teams/{team}/work-projects/{project}/work-items/{id}
Idempotency-Key: <本次编辑意图>
If-Match: "<当前版本>"
Content-Type: application/json

{"title":"更新后的标题","description":null}
```

仅开放 `title / description / priority / labels / dueAt`。省略不改；description/dueAt 的 null 清空，labels 的 null 或 [] 清空；title/priority 不可 null。标题 trim 后 1–240 字；未知字段（包括 key/type/status/source/责任/执行参数）拒绝；空 patch 拒绝。标签遵循领域数量/长度限制，非法时间拒绝。缺失/弱 If-Match、非法 Header 400；非法领域值 422；无成员/WORK_PARTICIPATE 权限拒绝；外部托管来源不编辑；ARCHIVED 只读；DONE/CANCELLED 不重开。

返回沿用 202 四字段回执。expectedVersion、字段 presence 与规范值进入 hash；相同原键/原载荷重放不二次写入，版本冲突 409，不自动覆盖。内容保存、版本递增、WORK_ITEM_CONTENT_UPDATED 事件、Outbox、回执同事务。事件只保存项目/编号/发生变化的字段名，不复制正文到 Activity/Audit；历史 Task/Execution 输入快照、链接、责任与状态均不改。

详情“编辑内容”保留本地输入，保存只 PATCH 实际改动字段（避免未触碰的日期秒数被 datetime-local 截断）。冲突后定点读当前五字段对照，读取失败可“获取最新内容”；显式“以当前版本重新确认”后才允许用新版本保存。若已归档则保留输入但禁止保存。取消/关闭/切对象/离页保护未保存内容；刷新给浏览器标准离开提示，不持久化编辑正文。保存后刷新同一详情/列表，Today 再进入强制读取当前事实。
