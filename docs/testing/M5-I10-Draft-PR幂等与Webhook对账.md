# M5-I10：Draft PR 幂等与 Webhook 对账

> 状态：已完成
> 日期：2026-08-23
> 关联：[ADR-018](../adr/ADR-018-GitHub连接与Draft-PR交付边界.md)、[ADR-019](../adr/ADR-019-ActionBundle调度与外部结果对账协议.md)、[M5-I09](M5-I09-GitHub-Mirror-AskPass与幂等Push.md)

## 目标

为确认后的 `CREATE_DRAFT_PR` 提供类型化、幂等且凭证不可见的 GitHub 写边界，并把 GitHub Pull Request Webhook 转换成可持久去重、可进入领域单调合并的 `ExternalObservation`。

## Draft PR 协议

`GitHubDraftPullRequestPort` 接收 WorkItem Scope、ProviderBinding/Connection/Grant 精确版本、RepositoryBinding、Default Branch、Delivery Commit、GitHub Repository ID、Head、Base、Head SHA、标题、正文和 `draft=true`。

执行顺序：

1. 复验 ProviderBinding 与 RepositoryBinding，要求 `source.write` 和 `pull-request.create`；
2. 重新执行 Repository Preflight，并复验 Connection/Grant/Repository/Base；
3. 使用 `state=all + head_owner:head + base` 查询候选，关闭后的同一 PR 也不会导致重复创建；
4. 校验 Repository ID、Draft、Head、Base、Head SHA、标题、正文、PR ID/Number、规范 Web URL 和 Provider 更新时间；
5. 无候选时读取远端 Branch Head，只有精确等于已确认 Head SHA 才允许写入；
6. Create API 固定提交 `draft=true`，不向 GitHub 发送不存在的 `head_sha` 请求字段；
7. 网络中断、`5xx` 或响应丢失后只查询，不盲目重放；精确候选返回 `RECOVERED_AFTER_UNKNOWN`；
8. `422` 先查询疑似重复；无候选时返回确定的 `VALIDATION_FAILED`；
9. 同 Head/Base 的参数或 Commit 漂移返回 `PULL_REQUEST_CONFLICT`，不会创建第二个 PR。

结果只返回 PR ID/Number、规范 URL、Head/Base/Head SHA、标题/正文 Hash、Draft、状态和 Provider 更新时间，不返回 Token、Authorization、Provider Body 或内部 API Endpoint。

## Webhook 协议

`GitHubPullRequestWebhookPort` 的输入由后续 API 路由绑定到已存在的 Action 与 `ExternalResultIdentity`。Adapter 执行：

1. 仅接受 `pull_request` 和受控 Action 集；
2. 通过短窗口 `GitHubWebhookSecretResolver` 计算 HMAC-SHA256，使用常量时间比较；
3. 限制 Payload 为 `1..2 MiB`，签名通过前不解析 JSON；
4. 复验 Connection-scoped PR Identity、Repository ID、PR ID/Number；
5. 把 `open/closed/merged` 与 `updated_at` 归一化为 `ExternalObservation`；
6. Observation Key 由 Connection、`WEBHOOK` 和 Delivery ID 规范派生；
7. `ExternalObservationRepository.appendIfAbsent` 提供数据库级持久去重；
8. 同 Delivery ID 的相同事实返回 `DUPLICATE`，不同 Payload/状态返回 `DELIVERY_CONFLICT`；
9. 原始 Payload 不进入 Observation，证据只保存 Payload SHA-256 的闭合 Hash。

关闭、重开、合并、重复和乱序事件继续使用 M5-D09 的 `ExternalResult.merge`：Provider 更新时间优先于接收时间，旧事件保留 Observation 但不能覆盖新状态，`MERGED` 不可逆。

## Spring 装配

新增配置：

```yaml
crewscope.provider.github.web-base-uri: https://github.com
```

- Draft PR Port 只在 ProviderBinding 与 RepositoryBinding Repository 存在时装配；
- Webhook Port 只在 `GitHubWebhookSecretResolver` 与 `ExternalObservationRepository` 同时存在时装配；
- API/Web Origin 均拒绝凭证、Query、Fragment 和非受控 Scheme；
- HTTP Client 固定禁用 Redirect，并保留 GitHub Accept 与 API Version Header。

## 阶段边界

- M5-I10 交付 Provider 副作用协议、可信 Webhook Observation 和持久去重调用边界；
- M5-I11 已把 Push→Draft PR 接入 Outbox 后 Action Worker、Dispatch/Fencing、Receipt 与依赖释放事务；
- M5-I12 已完成 UNKNOWN 批次、主动查询/Webhook 统一合并、启动恢复、人工队列、指标与诊断，证据见 [M5-I12 UNKNOWN 对账与运行诊断](M5-I12-UNKNOWN对账与运行诊断.md)。

## 验证

专项测试覆盖首次创建、响应丢失恢复、重复请求、唯一 Draft PR、标题与 Head 漂移、`422` 确定失败、PR 关闭/重开、Webhook 伪造/重放/跨 Connection/乱序、同 Delivery 冲突以及 I08/I09 回归。

```bash
./mvnw -q -pl crewscope-infrastructure -am \
  -Dtest=GitHubDraftPullRequestProtocolM5I10IntegrationTest,GitHubPullRequestWebhookAdapterM5I10Test,GitHubPushProtocolM5I09IntegrationTest,GitHubProviderAdapterM5I08Test \
  -Dsurefire.failIfNoSpecifiedTests=false test

./mvnw -q -pl crewscope-server -am \
  -Dtest=GitHubDraftPullRequestApplicationConfigurationM5I10Test,GitHubPushApplicationConfigurationM5I09Test,GitHubProviderApplicationConfigurationM5I08Test \
  -Dsurefire.failIfNoSpecifiedTests=false test
```
