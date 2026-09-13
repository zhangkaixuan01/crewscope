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

结果：领域/应用锚点测试通过；真实 PostgreSQL/Redis Testcontainers 集成测试 9/9 通过，Flyway 已执行至 V38，Hibernate Schema Validation 通过。
