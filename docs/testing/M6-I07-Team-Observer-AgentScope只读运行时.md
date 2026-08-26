# M6-I07 Team Observer AgentScope 只读运行时

> 日期：2026-08-26<br>
> 范围：`crewscope-application`、`crewscope-agentscope`<br>
> 结论：通过

## 1. 交付内容

- `TeamObserverTemplateRuntimeRegistry` 只接受当前 ACTIVE 的精确 `team-observer@1`、TEAM Ownership/Execution Scope、完整五 Tool、空 Skill、空成员补充 Prompt 和冻结 Structured Output Schema。TEAM 模型的 Primary/Fallback Connection Owner 只能是 TEAM 或 ORGANIZATION。
- `TeamObserverModelFactory` 在打开模型 Credential Handle 前核对 Profile、Principal、Template、Configuration、Resolved Configuration、TEAM Binding、模型所有者、版本和 Hash，随后复用统一 `AgentTemplateRuntimeAssembler` 创建 AgentScope Model。
- `TeamObserverReadService` 为每次 Tool 调用重新读取当前 TeamMember，只有精确 Organization/Team/Member 的 ACTIVE 成员才能读取投影；模型返回后再次复验成员资格，关闭调用期间离队或停用的竞态窗口。
- 固定 Tool 集合为 `team.activity.read`、`team.inbox.summary.read`、`workitem.summary.read`、`task.summary.read` 和 `artifact.summary.read`。五个 Tool 均无模型可控 Scope/Member/Limit 参数、标记为只读，并且只返回 Section、摘要和内部 Evidence Path。
- `TeamSummaryProjectionPort` 接收绑定成员和每段上限的 `TeamSummaryProjectionQuery`。应用层拒绝跨 Team、跨成员、错误 DataScope、重复 Evidence 和超过请求预算的 Adapter 结果。
- 每次调用使用新的 Toolkit 和证据目录。Structured Output 中的每个 `summary + evidencePath` 必须与本次已执行 Tool 返回的同 Section 精确匹配，模型虚构、改写、重复或引用未读取事实都会失败关闭。
- 成员指令被限制为 4,000 字符并置于转义后的不可信 Prompt 分区；Prompt 不嵌入 Organization、Team、Member 或原始投影身份。Tool 内容也被声明为不可信数据，不能扩展 Tool、权限和 Schema。
- `TeamObserverRuntimeSession` 用 Organization、Team、Member、确定性 Observer Principal/Profile 和服务端 Session UUID 派生 AgentScope User/Session Key 与 State Reference。同一 Session UUID 在不同成员或 Team 下仍落入不同状态槽。
- 通用 Team Factory 新增专用 `TEAM_OBSERVER` Session Kind，同时保留既有 Task Team Coordinator 行为。Harness 继续关闭文件系统、Shell、Subagent、Memory、动态 Skill 和 Workspace Context。

M6-I07 不提供 HTTP、SSE、Resume、取消、Conversation 持久化或证据打开接口，这些由 M6-A05 在本运行时之上实现。

## 2. 调用与披露边界

```text
current member + active team-observer@1 + current TEAM model configuration
  -> validate exact Template/Profile/Configuration/Resolved model graph
  -> derive Team/member/session-isolated AgentScope state coordinates
  -> create a fresh five-Tool read-only Toolkit and invocation evidence catalog
  -> model calls one or more parameter-free summary Tools
  -> each Tool reauthorizes current ACTIVE Team membership
  -> Tool returns only bounded member-visible summary + internal evidence path
  -> model returns the fixed five-section Structured Output
  -> output tuples must exactly exist in the invocation evidence catalog
  -> reauthorize membership after model completion
  -> construct domain-validated TeamSummaryResult
```

模型不持有 Repository、写 Service、Provider Client、任意 URL/SQL、原始审计 Payload 或私有成员事实。Evidence Path 打开时仍需由 M6-A05 重新授权。

## 3. 验证

专项命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-agentscope -am \
  -Dtest=TeamObserverReadServiceM6I07Test,TeamObserverRuntimeM6I07Test \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：14 个测试通过，0 Failure、0 Error、0 Skip。其中真实 AgentScope Harness Loopback 完成一轮 `team.activity.read` Tool 调用和一轮冻结 Structured Output 返回。

D05、通用 Template Runtime 与 I07 联合回归命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-agentscope -am \
  -Dtest=TeamObserverDomainM6D05Test,DefaultTeamObserverServiceM6D05Test,\
AgentTemplateRuntimeRegistryM5I05Test,TeamObserverReadServiceM6I07Test,\
TeamObserverRuntimeM6I07Test \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

联合回归共 39 个测试通过，0 Failure、0 Error、0 Skip。

仓库全量门禁命令：

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

全量结果：2,241 个测试通过，0 Failure、0 Error、0 Skip，7 个 Maven Reactor 模块全部成功。

| 模块 | 测试数 |
| --- | ---: |
| `crewscope-domain` | 569 |
| `crewscope-application` | 514 |
| `crewscope-agentscope` | 158 |
| `crewscope-integration` | 18 |
| `crewscope-infrastructure` | 646 |
| `crewscope-server` | 336 |
| 合计 | 2,241 |

覆盖：

1. 进度、阻塞、Review、待确认和异常五段输出；
2. 五个 Tool 精确命名、只读属性和写 Tool 缺失；
3. 当前成员逐次复验和模型完成后的撤权竞态；
4. 跨 Team、跨成员、私有行、错误 DataScope、重复与超限查询拒绝；
5. PERSONAL Connection、写 Tool、Skill、Prompt 和 Schema 扩展拒绝；
6. Prompt 边界闭合、未知输出字段、虚构/改写 Evidence 和重复选择拒绝；
7. Organization/Team/Member/Profile/Session 状态隔离；
8. 模型 Credential 打开前的完整 TEAM 坐标验证；
9. 既有 Personal、Task、Specialist 与 Team Template Factory 回归。
