# M9-A04 行级 Review 评论验证

实现覆盖四个接口：创建、游标列表、作者编辑、作者软删除。所有写请求要求 `Idempotency-Key`，编辑和删除要求强 `If-Match`；列表通过 ReviewRequest 与 Team 可见性策略授权。

验证要点：

- 锚点复用 `FindingLocation`，并与 `ContextPackage.hunks`（由 `ReviewPatchHunkParser` 产生）校验文件、行、Diff Generation 和行内容 hash；无法映射时返回 `OUTDATED`，保留原文和作者。
- `OLD` 锚点按 unified diff 的旧文件行号解析，`NEW` 锚点按新文件行号解析；同时要求 hunk header 完全匹配，避免仅凭新文件范围误判旧行。
- 评论正文限制 20,000 字符，响应不包含 Patch 正文、宿主路径、Prompt、Credential 或 Provider 原始错误。
- 删除为软删除，保留审计字段和原始锚点；删除他人评论由领域所有权校验拒绝。
- 数据表使用组织租户列、ReviewRequest 外键、幂等键唯一约束和版本字段；跨 Team 或撤权后通过既有 `WorkItemAccessPolicy` 立即失效。
- 更新采用组织、Team、Workspace、Project、评论 ID 与版本的条件更新；并发写入只允许一个版本提交，失败返回稳定的乐观锁冲突。

本地验证命令：

```bash
/Users/zhangkaixuan/Downloads/apache-maven-3.9.6/bin/mvn -pl crewscope-domain,crewscope-application,crewscope-infrastructure,crewscope-server -am -DskipTests compile
git diff --check
```

专项测试：

```bash
/Users/zhangkaixuan/Downloads/apache-maven-3.9.6/bin/mvn -q -pl crewscope-domain,crewscope-application -am \
  -Dtest=ReviewLineCommentTest,ReviewLineCommentQueryServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
/Users/zhangkaixuan/Downloads/apache-maven-3.9.6/bin/mvn -q -pl crewscope-infrastructure -am \
  -Dtest=JdbcReviewPersistenceM5I07IntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：领域/应用锚点测试通过；真实 PostgreSQL/Redis Testcontainers 集成测试 9/9 通过，Flyway 已执行至 V39，Hibernate Schema Validation 通过。

## 收口修正：命令键被更新覆盖（复核发现）

上面第 11 条写的「幂等键唯一约束」在第一次编辑之后是**失效**的，本轮修正：

| 环节 | 事实 |
|---|---|
| 结构 | 评论表只有一列 `idempotency_key`（`V37` 的 `uk_review_line_comment_idempotency`），而更新语句把它 `SET` 成本次请求的新键 |
| 后果 | 一次编辑后 create 键被腾空：客户端重放原始 create 时 `findByIdempotencyKey` 查不到回执，绕过重放分支，唯一索引也不再拦截 → **静默写入第二条评论**（新 id、同内容、同锚点） |
| 次要后果 | 被更晚编辑取代的旧更新键同样被挤掉，重放时直接走 `current.edit(expectedVersion)`，版本已前进 → 乐观锁冲突（响亮的失败，可接受） |
| 修正 | create 键改为不可变（更新不再写它），更新命令使用独立槽位 `last_command_idempotency_key`（`V39`，带独立的部分唯一索引与键格式约束）；键查找改为两槽位取并集，因此「同一键被另一类命令复用」仍会被命令服务拒绝 |

代价与升级路径：只记住**最近一次**更新命令的键；若要按任意历史命令返回回执，应改为 `V5` 那种独立回执表。这一例外按 M9 计划 §10.9 第 16 条的口径批准并留档。

```bash
./mvnw -q -pl crewscope-infrastructure -am \
  -Dtest='JpaReviewLineCommentIdempotencyIntegrationTest,V39ReviewLineCommentCommandKeyMigrationIntegrationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

- `JpaReviewLineCommentIdempotencyIntegrationTest`（3 条）：编辑后 create 键仍可解析、重放 create 被唯一约束拒绝且表内仍只有一行、编辑键仍可重放。**修正前以「编辑后按 create 键查为空」与「Expected DataIntegrityViolationException to be thrown, but nothing was thrown」失败**，即静默重复被真实复现。
- `V39ReviewLineCommentCommandKeyMigrationIntegrationTest`（3 条）：存量行升级后 create 键不变、新槽位为空；槽位按组织唯一且拒绝不合语法的键；列、约束与部分索引齐备。删掉新索引后这两类断言正是失败项，因此不是空跑。
