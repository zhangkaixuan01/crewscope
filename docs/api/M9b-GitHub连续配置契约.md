# M9b-A02：GitHub 连续配置与导入契约

> 状态：A02 实现收口；默认路径面向已有凭据的单机/HTTP 部署，不要求公网 OAuth 回调、TLS、curl、预置数据库或用户填写内部标识。

## 1. 用户路径

```text
已有 Access Token / App Installation 凭据
→ 创建 Connection（服务端保存加密凭据引用）
→ Verify：GitHub 返回真实身份
→ 同步 Catalog：只展示当前凭据实际可读且满足组织策略的仓库
→ 选择一个仓库
→ ProviderBinding 只固定所选仓库
→ 导入 WorkProject（服务端生成 RepositoryKey）
→ 按原 job ID 读取进度并回到仓库/原项目
```

Account ID、Allowlist、ProviderBinding ID、RepositoryBinding ID 和 RepositoryKey 均不是普通路径的必填输入。旧客户端仍可提交 Account ID/Allowlist/Key 以兼容历史连接，但服务端不信任客户端身份；留空时进入 discovery 模式。

## 2. Connection 创建与身份

`POST /api/v1/organizations/{organizationId}/github-connections` 仍使用 `Idempotency-Key`。请求最小字段为：

```json
{
  "authenticationType": "OAUTH_USER",
  "credentialSubjectType": "PRINCIPAL",
  "accessToken": "<one-shot-secret>"
}
```

团队 App 仍需要服务端确定的 `teamId` 和 `TEAM` credential subject。`accessToken` 只在请求内读取，响应永不回显。

留空 Account ID 时，Connection 内部只保存不可用作身份凭证的 `pending:<connection-id>` 标记。`POST .../{connectionId}/verify` 由 Provider 访问 `/user` 或 installation catalog，返回真实 external identity/login/权限；服务端以该结果建立当前版本 Profile。客户端不能用自报数字 ID 覆盖远端身份，也不能把“Connection 存在”显示成“已验证”。

留空 Allowlist 时，初始 Grant 仅用于 discovery。Catalog 可读不等于可写或可导入；组织 owner/visibility、GitHub 最小权限和当前凭据仍逐次检查。

凭据过期、撤销或无法读取时显示不可用并提供“更新/创建新 Connection → 验证”的路径。没有配置自动刷新时不得宣传为永久授权；REVOKED 不可原地恢复。

## 3. ProviderBinding 选择性授权

`POST .../github-connections/{connectionId}/bindings` 的兼容请求：

```json
{
  "teamId": "<team-id>",
  "defaultUsage": true,
  "repositoryIds": ["<catalog-external-repository-id>"]
}
```

`repositoryIds` 来自服务端 Catalog，不能提交 URL、owner/repo 拼接值或宿主路径。服务端重新读取当前 Connection/Grant/Catalog，确认版本、可交付状态和 Team `PROVIDER_MANAGE`，再把所选资源写入 ProviderBinding。Discovery Grant 若没有具体选择，绑定拒绝；不会自动把所有可读仓库变成可交付权限。

ProviderBinding 与 WorkProject 的 `RepositoryBinding` 不同：前者是外部 Provider 身份/资源授权，后者是导入完成后受管仓库的项目内执行对象。页面负责串联两者，但不混用 ID 或权限。

## 4. 导入请求与自动 Key

`POST .../work-projects/{projectId}/github-imports` 仍要求 Connection/Grant 版本、Catalog external repository ID 和 default branch；`repositoryKey` 可省略：

```json
{
  "connectionId": "<uuid>",
  "connectionVersion": 0,
  "grantId": "<uuid>",
  "grantVersion": 0,
  "externalRepositoryId": "123456",
  "defaultBranch": "main"
}
```

服务端以已授权 Catalog 的 `owner/repository` 生成小写、路径无关的受管 Key；同名冲突追加稳定的 external repository hash 后缀。用户不需要理解或填写该 Key，响应仍返回最终 Key 供后续深链和诊断使用。相同原意图重放复用原 job；不同项目不会因短仓库名重复导入同一个受管 Key。

## 5. job 状态与恢复

状态为 `REQUESTED → PREFLIGHTING → IMPORTING → READY`，或 `FAILED`/`CANCELLED` 终态。`READY` 必须有 `bindingId`；`FAILED/CANCELLED` 必须有稳定 failure code。取消只在 Worker 开始 Git I/O 前接受，取消与完成竞争以数据库事实为准。

`GET .../github-imports/{jobId}` 是唯一恢复入口。页面把 `connection` 与 `importJob` 放入白名单路由参数，刷新/离开返回后按原 Team、Project、Connection 和 job 精确查询；读取失败显示 UNKNOWN，不创建第二个 job，也不把原任务改写成 FAILED。关闭面板只关闭 UI，不取消任务；取消必须显式调用 cancel。

完成只提供“前往受管仓库/回原项目”的入口，不自动启动遗留草稿任务、不自动执行 Coding Task。轮询响应必须核对当前 job ID、Team/Project 和请求代次，旧响应不能污染新范围。

## 6. 权限与非目标

- USER Connection 只允许本人管理；TEAM Connection/Binding/Import 需要既有 Team Provider/仓库管理权限。
- 连接、Grant、ProviderBinding、RepositoryBinding 的版本在提交时重新校验；前一页预检不是下一步授权通行证。
- 不自动扩大 GitHub scope，不把读取失败变成新建理由，不重复 Push/PR，不把 UNKNOWN 当作失败。
- 标准 GitHub OAuth/App 安装重定向仅在部署具备对应回调前提时启用；已有凭据路径不依赖公网 TLS。
