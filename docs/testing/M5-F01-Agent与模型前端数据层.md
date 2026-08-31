# M5-F01 Agent 与模型前端数据层

> 状态：已完成<br>
> 日期：2026-08-25<br>
> 范围：Model Provider、Catalog、ModelConnection、AgentTemplate、AgentProfile、AgentConfiguration 与 Conversation Configuration 前端数据契约

## 1. 交付内容

M5-F01 建立 M5-F02 至 F05 共用的浏览器数据基础：

- `domains/model` 定义 Model Provider、Catalog、价格、Connection 和单向 Credential 输入类型，接入 A01 全部查询与命令 API；
- `domains/agent` 定义 AgentTemplate、AgentProfile、Configuration History、当前 Configuration、可选模型、Preflight 和 Conversation Configuration 类型，接入 A02/A03 全部 API；
- Model Store 与 Agent Store 按 `organizationId + teamId` 分区，提供有界分页、详情、配置历史、Preflight、生命周期命令和缓存失效；
- `domains/settings/route.ts` 固定 `/settings/agents` 与 `/settings/models` 的 Team、Agent、Configuration Revision、Provider、Connection 和 OwnerType 深链接坐标；
- 应用组合根安装 `HttpModelGateway`、`HttpAgentGateway` 及两个独立 Store；
- 本阶段不交付设置页面；“Agent 中心”列表由 F02 接续，Agent 创建与配置由 F03 接续，“模型与凭证”页面由 F04 接续。

## 2. 浏览器披露边界

Gateway 对所有顶层和嵌套 DTO 重新执行字段白名单。浏览器只持有产品 Provider、公开模型坐标、价格、Connection 健康摘要、Agent 产品属性、非秘密 Binding、配置 Revision/Hash 和安全 Preflight 证据。

以下字段不进入 Store：

- Provider Endpoint 与 AgentScope Adapter Key；
- Credential ID、API Key、Secret Metadata 和 Provider 原始错误；
- System Prompt、Tool/Schema Payload 与内部策略载荷；
- Principal 内部版本、AgentState Reference 和运行时实现坐标。

API Key 只作为创建或轮换函数的瞬时参数传入 Gateway。Store 不保存 Secret、Credential 请求体、幂等键或可重放命令闭包；页面失败重试必须继续持有本地输入并显式决定是否复用原幂等键。

## 3. Scope、分页与版本

完整 Store Scope Key 为：

```text
organizationId:teamId
```

Scope 切换会取消全部活动读取、推进请求代次并清空旧 Team 缓存。底层请求即使忽略 `AbortSignal`，也必须同时通过 Scope Key、请求代次和当前请求身份后才能写入。旧 Scope 命令完成后同样不能失效新 Scope 缓存。

A01 至 A03 使用 `offset/limit`。Store 原样传递服务端 offset，按页面项数推导下一 offset，并按稳定 ID 或 Revision 去除页边界重叠，不模拟签名 Cursor。

强 ETag 作为独立版本证据保留：

- ModelConnection 与 AgentProfile：聚合版本；
- 当前 Agent Configuration：Configuration Revision；
- Conversation Configuration：Runtime Session Version。

缺失/弱 ETag、ETag 与响应 Revision 不一致、非法 Ownership/ExecutionScope、跨 Organization/Team DTO、Provider 与 Catalog 不一致以及不连续配置历史均失败关闭。命令只提交服务端 ETag 对应的 `If-Match`，成功后失效缓存并重新读取权威事实。

## 4. 设置深链接

```text
/settings/agents?team=<teamId>&agent=<agentProfileId>&configurationRevision=<revision>
/settings/models?team=<teamId>&provider=<providerKey>&connection=<connectionId>&ownerType=<type>
```

重复 Query、缺少 Team 的资源坐标、未知 OwnerType、非法正 Revision 和 Scope 不匹配不能恢复。F02/F04 注册页面时直接复用本阶段路径常量和解析器。

## 5. 自动验证

专项验证：

```bash
pnpm --dir crewscope-web exec vitest run \
  src/domains/settings/route.spec.ts \
  src/domains/model/gateway.spec.ts \
  src/domains/model/store.spec.ts \
  src/domains/agent/gateway.spec.ts \
  src/domains/agent/store.spec.ts
```

5 个测试文件、23 项测试全部通过，覆盖：

- Provider、Catalog、Connection、Template、Agent、Configuration、Selectable Model 与 Preflight DTO 白名单；
- offset 续页、重叠去除和路由坐标失败关闭；
- 强 ETag 捕获、命令头转发、Revision/Session Version 一致性；
- 读取与命令完成晚到时的 Scope 隔离；
- 跨 Team Connection/Agent、错误 OwnerType 与 Provider 坐标拒绝；
- API Key 仅进入一次 HTTP 请求且不进入响应、Receipt、Store 或重试状态；
- Configuration 追加和 Conversation 安全刷新后的派生缓存失效。

全量验证：

```bash
pnpm --dir crewscope-web test
pnpm --dir crewscope-web build
```

前端全量 58 个测试文件、260 项测试通过；`vue-tsc --noEmit` 与 Vite 生产构建通过。

## 6. 下一阶段

M5-F02 使用 Agent Gateway、Store 与设置深链接契约交付“Agent 中心”列表，区分默认 Personal Agent、USER-owned Specialist 和 TEAM-owned Agent，并补齐加载、空态、权限、生命周期和响应式页面验证。
