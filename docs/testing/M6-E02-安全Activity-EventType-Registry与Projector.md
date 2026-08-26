# M6-E02 安全 Activity EventType Registry 与 Projector

> 日期：2026-08-26
> 范围：`crewscope-application`、`crewscope-infrastructure`
> 结论：通过

## 1. 交付内容

- 建立不可变 `ActivityEventTypeRegistry`，按 `EventType + source SchemaVersion` 精确匹配；重复坐标、映射字段与公开 Schema 不一致时拒绝启动。
- 登记 35 个 M0–M5 评审事件类型：Team 3 个、WorkItem/责任 10 个、Task 5 个、Review 8 个、Action 6 个、GitHub Provider 3 个；Task 的 V1/V2 均显式登记，共 40 个精确 Schema 坐标。
- 每项注册定义固定 Category、Visibility、Subject、Reference、公开 Payload Schema 和白名单标量来源；评论正文、原始 Payload、Credential、Token、Provider Body 和未登记字段不进入 Activity。
- 实现 Generation-aware `team-activity` Projector，稳定 Activity ID 由 DomainEvent ID 派生，Activity 与 Reference 同 Generation 事务写入。
- 新 Organization 首事件使用独立本地事务原子引导 Projection Definition、Generation 1 与 Pointer，提交后再进入 Generation 事务；已存在终态 Generation 时不重新激活，Definition 坐标冲突失败关闭。
- 同 Team 投影先锁 Team 行，再按 Projection Generation 分配连续 TeamSequence；不同 Aggregate 并发不会产生重复或间断序号。
- 未知 EventType、未评审 Schema 和有效非 Team Provider 生命周期事件安全忽略，Generation Receipt 与 Checkpoint 正常推进，WARN 不包含 Payload。
- 已注册 Team 事件缺失必填字段、错误类型、非法 UUID 或非法 Team Scope 时失败关闭，Activity、Receipt 与 Checkpoint 全部回滚。
- `activity.canonical-v1` 排除 Generation、TeamSequence 和写入时间，规范化稳定身份、Scope、事件、公开 Schema/Payload 与有序 Reference，使在线和影子代际可直接比较。

## 2. 注册范围

| 类别 | 数量 | 代表事件 | 默认可见性 |
|---|---:|---|---|
| Team | 3 | `TEAM_CREATED`、`TEAM_MEMBER_JOINED` | Team Member；初始化完成仅 Team Admin |
| WorkItem/责任 | 10 | `WORK_ITEM_CREATED`、`WORK_ITEM_STATUS_CHANGED`、责任分配/释放 | WorkItem Participant |
| Task | 5 | `TASK_DELEGATED_TO_AGENT`、成员 Pause/Resume/Cancel/Retry | 委托为 WorkItem Participant，其余为 Team Member |
| Review | 8 | Review Request、Finding、Decision、Modification Round | Team Member |
| Action | 6 | Bundle、Confirmation、Dispatch、Receipt、External Merge | Team Member |
| GitHub Provider | 3 | Connection Created/Revoked、Provider Bound | Team Admin |

## 3. 验证结果

专项命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  -Dtest=ActivityEventTypeRegistryM6E02Test,ActivityEventProjectorM6E02IntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：13 / 13 通过，其中 Application 4 个、PostgreSQL/Testcontainers 集成 9 个。

覆盖项：

1. 35 个事件类型、40 个精确 Schema 坐标及六类代表事件；
2. Task V1/V2 精确登记，未知 EventType 和未登记 Schema 精确拒绝；
3. 重复注册坐标失败关闭；
4. 评论公开 Payload 排除正文和原始 Payload；
5. WorkItem 公开 Payload 结构快照及敏感字段探针；
6. 新 Organization 首事件从外层幂等分发事务引导并提交 Definition、Generation 和 Pointer；
7. 同事件重复投递与同 Aggregate 多版本去重/顺序；
8. 八个跨 Aggregate 并发首事件安全竞争同一引导坐标并获得连续 TeamSequence；
9. 未知事件忽略后同 Aggregate 后续版本继续消费；
10. USER-owned Provider 事件不进入 Team Activity；
11. 已注册损坏 Payload 回滚 Activity、Receipt 与 Checkpoint；
12. 有界历史重放构建影子 Generation，期望、在线和影子 Count/Hash 完全一致。

## 4. 后续边界

M6-E03 在同一 Generation-aware 运行时上实现五类成员 Inbox 来源投影，并与 Generation 外 `InboxDisposition` 合并。Activity 查询 Adapter、API、SSE 与前端展示分别由 M6-I01、M6-A01、M6-E05 和 M6-F02 交付。
