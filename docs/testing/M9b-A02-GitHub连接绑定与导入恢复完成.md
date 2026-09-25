# M9b-A02：GitHub 连接、绑定与导入恢复

> 状态：实现完成（2026-09-23）；工作区仍保留 A01 及本包未提交改动。

## 1. 已交付

- Connection 创建允许普通用户只提交认证方式、凭据和可选过期时间；远端 Verify 返回真实 GitHub identity，Account ID 不再是必填。
- 空 Allowlist 进入 discovery；Catalog 同步后由用户选择仓库，ProviderBinding 只写入所选 external repository resource，不把可读目录自动扩大为全仓库交付权限。
- 导入请求的 RepositoryKey 可省略，服务端根据已验证 full name 自动生成并以稳定 hash 处理冲突；旧显式 Key 请求保持兼容。
- GitHub Settings 页面按 Connection → Verify → Catalog → 选择仓库 → 绑定并导入展示，不再要求用户填写 Account ID 或内部 Key；高级兼容字段明确标记为可选。
- `connection`/`importJob` 白名单路由坐标支持刷新、离开返回和原 job 点查；读取失败显示 UNKNOWN，不创建新任务；READY、FAILED、CANCELLED 分开展示。已有其他仓库绑定时不会被错误复用，页面会先尝试为当前选择建立精确绑定。
- 导入完成仅提供受管仓库/原项目入口，不自动执行遗留草稿；取消仍是显式命令。

本包不新增一套只服务 GitHub 的“影响摘要”接口：页面直接复用当前 Team、WorkProject、Connection、Catalog 和 import job 坐标，通用交付影响摘要由后续 F02/F03 统一定义，避免同一事实在多个 API 中漂移。

## 2. 验证

```bash
./mvnw -q -pl crewscope-application,crewscope-server,crewscope-infrastructure -am -DskipTests compile
./mvnw -q -pl crewscope-application -am \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='GitHubConnectionApplicationServiceM5A06Test,GitHubRepositoryImportApplicationServiceM8Q02Test,GitHubRepositoryImportWorkerM8Q02Test' test
./mvnw -q -pl crewscope-infrastructure -am \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='GitHubProviderAdapterM5I08Test' test
cd crewscope-web
pnpm exec vue-tsc --noEmit
pnpm exec vitest run src/domains/delivery/gateway.spec.ts src/pages/GitHubSettingsPage.spec.ts
cd ..
node scripts/generate-openapi-types.mjs
node scripts/check-openapi-drift.mjs
git diff --check
```

当前定向结果：Java 编译通过；Connection/Import/Worker 定向测试通过，GitHub Provider Adapter 另有 pending identity 回归；GitHub Gateway 与页面隔离/取消/未知读取/刷新恢复测试通过（22/22），并覆盖 discovery 创建、可选字段省略、所选仓库绑定请求及不复用其他仓库绑定。A02 当时的 OpenAPI 基线为 219；A03 后新增模型连接 activate，当前基线为 220，状态机仍为 16 个聚合。未启动产品部署、未读取 `.env`、未进行真实 GitHub 写操作。

## 3. 交接

F02 消费页面返回的 Team/Project/Connection/job 坐标与 readiness；F03 消费所选 ProviderBinding、受管 RepositoryBinding 和 default branch。A02 不改变 Review、ActionBundle、Push/PR UNKNOWN 对账合同；真实外部凭据和真实仓库只在隔离的 Q02 路径验证。
