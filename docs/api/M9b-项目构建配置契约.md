# M9b 项目构建配置契约

状态：A04 实现中已落地的稳定契约。该接口只选择服务端受控事实，不接受宿主路径、shell、包管理器或任意命令。

## 项目默认值

```text
GET /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/execution-defaults
PUT /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/execution-defaults
GET /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/execution-defaults/options
```

GET 在没有配置时返回 `version=0` 和所有缺项；这不会阻止 API-only 项目创建。返回字段同时包含 `value`、`source`、`availability`、`reason`，让页面说明当前值来自项目默认、继承还是缺失。

PUT 是完整替换：`null` 清除对应可选默认值。必须带 `If-Match: "<version>"` 和 `Idempotency-Key`。首次写入使用版本 0，成功后版本递增并返回新的 ETag；版本冲突返回统一 optimistic-lock 错误，客户端必须读取当前事实后重新确认。

请求字段为 `repositoryBindingId`/`repositoryBindingVersion`、`branch`、精确的 `buildProfile { key, version, profileHash }`，以及可选 `agentProfileId`/`agentProfileRevision`。分支必须与仓库绑定成对；仓库必须是当前项目中 ACTIVE 的受管绑定；BuildProfile 必须存在于服务端目录且 hash 完全匹配。用户不填写 RepositoryKey、宿主路径或内部命令。

项目默认写权限复用现有 Repository Owner/Admin（或平台管理员）边界；普通项目管理权不会自动扩权。项目默认只影响新执行，历史 CodingTarget/Task 快照保持原引用。

## 支持构建矩阵

| 方案 | 版本化事实 | 受控命令 | 限制 |
| --- | --- | --- | --- |
| Java v1 | Maven/Maven Wrapper/Gradle Wrapper/Project Script，Java 17–25 领域范围 | 既有 COMPILE/TEST/VERIFY 等 slot | 旧 hash、序列化和历史任务不变；不声称每个 JDK 已真实测试 |
| Node v2 | Node 24.19.0 + npm 11.17.0 | PREPARE `npm ci --ignore-scripts --no-audit --no-fund` → COMPILE `npm run build` → TEST `npm run test` | 单 package、`package-lock.json`、项目相对目录；缺 lockfile/script 或 lockfile 不一致必须拒绝 |
| pnpm/yarn、多 package、未知栈 | 不支持本期自动执行 | 无 | 不猜栈、不运行任意 shell；Coding 启动提示选择受支持方案或进入配置入口 |

每个命令有独立 CommandSpec/hash/结果，默认超时 60 秒、最大 900 秒。PREPARE 失败不能伪装成业务测试失败，也不能自动 `npm install` 改写依赖。镜像必须是 digest 固定的服务端 profile 事实。

