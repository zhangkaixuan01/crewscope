# M9-I01：OpenAPI 与状态机契约生成

> 任务：`M9-I01`
> 状态：已完成（本地实现，CI 持续执行漂移门禁）
> 范围：Springdoc OpenAPI 3.1、前端路径目录、领域状态机生成与 CI 漂移检查

## 1. 交付内容

- `crewscope-server` 引入 `springdoc-openapi-starter-webflux-api`，运行时文档地址固定为 `/v3/api-docs`，版本为 OpenAPI 3.1。
- `OpenApiConfiguration` 固定 CrewScope API 的标题、描述和版本；Controller 注解仍是端点与 DTO 的唯一来源。
- `scripts/generate-openapi-types.mjs` 从全部 `*Controller.java` 生成确定性的前端路径目录：
  `crewscope-web/src/api/generated/openapi.ts`。完整 DTO Schema、错误码和分页信封由运行时 Springdoc 文档提供，避免维护第二份 DTO 镜像。
  生成器同时解析 `value`/`path` 与常量拼接，并忽略 `produces` 等非路径参数，确保 SSE、导出等带附加注解参数的端点保留完整路径。
- `scripts/generate-state-machine.mjs` 从 domain 聚合的 `ALLOWED_TRANSITIONS`/`TRANSITIONS` 生成：
  `crewscope-web/src/api/generated/state-machines.ts`。当前覆盖 16 个聚合；WorkItem 包含 8 个状态和 17 条合法边。
- `domains/workitem/types.ts` 的状态流转集合改为引用生成物。生成物仅用于动作发现，服务端命令仍重新执行权限、版本、责任链、Gate 和幂等校验。
- `scripts/check-openapi-drift.mjs` 同时执行两个生成器的 `--check`，并验证 OpenAPI 3.1、217 个端点、16 个聚合及 WorkItem 状态基线。CI `quality` Job 已接入该门禁。M9-A02、M9-A03、M9-A04、M9-A05、M9-A06 与 M9-A07 新增的端点也由 Controller 路径目录自动纳入。

## 2. 本地验证

在仓库根目录运行：

```bash
node scripts/generate-openapi-types.mjs
node scripts/generate-state-machine.mjs
node scripts/check-openapi-drift.mjs
./mvnw -q -pl crewscope-server -am -DskipTests compile
cd crewscope-web && pnpm lint && pnpm build
```

`--check` 模式只读比较生成结果，不会修改工作区；当 Controller 映射、领域状态边或生成器发生变化而未重新生成时，命令返回非零退出码。

## 3. 运行时验证

启动 Server 后请求：

```bash
curl -fsS http://localhost:8080/v3/api-docs | jq '.openapi, (.paths | length)'
```

该端点遵循既有认证与网络边界，不新增匿名 API；生产环境是否暴露文档由网关和部署策略决定。文档中不输出 Credential、Token、Endpoint 密钥或宿主路径。

## 4. 基线与变更规则

当前基线为 217 个 Controller 操作、16 个状态机聚合、WorkItem 8 状态/17 条边。由于 OpenAPI 的 `paths` 对同一路径和 HTTP 方法只能保留一个操作，带不同响应媒体类型的重载端点会在 `openApiDocument.paths` 中合并；完整的 217 条操作仍保留在 `openApiOperations`，并由 CI 计数校验。新增端点或状态机边时，必须在同一变更中重新生成并更新基线断言与契约文档；删除或收紧状态边会让 CI 先失败，需完成对应前端动作消费和安全评审后再合并。
