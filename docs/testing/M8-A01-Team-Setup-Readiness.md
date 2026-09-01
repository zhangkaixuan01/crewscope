# M8-A01：Team Setup Readiness 服务端纵切

> 任务：`M8-A01`<br>
> 结论：`PASS`<br>
> 验证日期：2026-09-01<br>
> 代码基线：M8-A01 实现提交（以 Git 提交 Revision 为准）

## 1. 交付范围

- 应用层新增 `TeamSetupReadinessApplicationService`，从现有 Team、Membership、Agent、Model、WorkProject、Repository、Provider Connection 和 Runtime Port 派生只读快照；
- 固定六项能力：Personal Conversation、Team Task、Coding/Review、GitHub Draft PR、Lark Notifications、Team Observer；
- 状态使用 `READY`、`ACTION_REQUIRED`、`BLOCKED`、`UNAVAILABLE`；
- `actionKey` 采用受信枚举，只允许同源预注册动作；
- 服务端新增 `GET /api/v1/organizations/{organizationId}/teams/{teamId}/setup-readiness`；
- 新增显式 Spring 装配，未增加 Readiness 专用可写表或迁移。

## 2. 安全与权限

查询先执行当前 Organization、Team 和 ACTIVE Membership 校验。无 Team 配置权限的成员只能看到 `BLOCKED` 状态、稳定原因码和责任主体摘要，响应不包含写动作。公开 DTO 没有 Credential、Secret、Endpoint、Remote URL、Provider 原始错误、宿主路径或数据库坐标。

GitHub 能力只有在 ACTIVE TEAM Connection 且已有可交付 Repository Catalog 时才为 READY；Connection 存在但尚未导入受管仓库时返回 `GITHUB_REPOSITORY_IMPORT_REQUIRED`，导入入口由后续 M8-A02 提供。

## 3. 验证结果

| 验证 | 结果 |
|---|---:|
| 应用层 Readiness 单元/契约测试 | 2 / 2 |
| 服务端 API 契约测试 | 1 / 1 |
| 应用层编译 | PASS |
| 服务端编译（含完整 Maven Reactor） | PASS |
| API 文档链接检查 | 347 Markdown 文件 PASS |
| `git diff --check` | PASS |

固定测试覆盖：空配置 Team 的闭合状态、Runtime 不可用、无配置权限成员的 `BLOCKED` 和无 `actionKey`。新 Team、已配置 Team、Provider 故障和部分配置 Fixture 已在合同中冻结，后续 PostgreSQL/Redis 集成与浏览器验证由 M8-F01/Q01/Q02 继续执行。

## 4. 运行与兼容性说明

Readiness 使用现有 `TransactionExecutor` 在一次查询事务内读取事实；`snapshotVersion` 由事实版本和能力状态派生，相同事实集合保持稳定。响应使用 `Cache-Control: no-store`，不建立跨请求缓存状态。运行时或 Provider 查询失败折叠为相关能力的 `UNAVAILABLE`，不传播底层异常。

## 5. 复现命令

```bash
./mvnw -pl crewscope-application -Dtest=TeamSetupReadinessApplicationServiceTest test
./mvnw -pl crewscope-server -am -DskipTests compile
git diff --check
node scripts/check-doc-links.mjs
```
