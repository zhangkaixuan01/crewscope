# M9-A06 配置查询与配置健康 API 契约

## Revision 全量载荷

`GET /api/v1/organizations/{organizationId}/teams/{teamId}/agent-profiles/{profileId}/configurations/{revision}`

返回指定 Agent 配置 Revision 的只读公开载荷。响应包含模型绑定、补充指令、启用工具 key、结构化输出 schema hash、技能 key、记忆/预算策略引用、生成参数、Policy Pack 引用、配置 hash 和审计元数据。凭证明文、Provider endpoint、宿主路径、系统 Prompt 和任意外部 URL 不出库。

响应使用 `ETag: <configurationHash>`，`Cache-Control: no-store`。Revision 不存在、属于其他 Agent 或当前成员无权查看时统一走既有错误边界；不存在任何应用历史版本的写接口。

## 配置健康

`GET /api/v1/organizations/{organizationId}/teams/{teamId}/configuration-health`

服务端在请求时从既有 Agent 配置、模型连接、CredentialStore 和 Provider Connection 派生健康快照，不创建健康状态表。响应包括 `overallStatus` 与四类项目：`AGENT_CONFIGURATION`、`MODEL_CONNECTION`、`CREDENTIAL`、`INTEGRATION`。每项提供稳定 `reasonCode`、责任方和可选 `actionKey`，前端不得自行聚合多个接口判断健康。

状态仅使用 `READY`、`ACTION_REQUIRED`、`BLOCKED`、`UNAVAILABLE`。响应不包含 API Key、Token、endpoint 或外部服务原始错误。

## 配置项搜索

`GET /api/v1/organizations/{organizationId}/teams/{teamId}/configuration-search?q={query}`

搜索当前成员可见 Agent 的配置元数据（字段名、中文标签、模板 key），返回 `profileId`、Revision、字段 key、标签和稳定设置路由。查询长度限制为 1–100 个字符，最多返回 100 项；不返回补充指令正文、API Key、模型 endpoint 或其他用户输入内容。该查询为只读派生查询，不建立独立索引。

所有三个端点均使用既有认证、Team 可见性和撤权即时失效规则。参数错误返回 `invalid_request`，资源不存在返回既有 404 错误；查询端点使用 `Cache-Control: no-store`，Revision 详情额外返回配置 hash 的强 ETag。接口不接受 `Idempotency-Key`，不存在应用历史版本的写操作。
