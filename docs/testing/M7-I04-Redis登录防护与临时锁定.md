# M7-I04 Redis 登录防护与临时锁定

> 任务：`M7-I04`<br>
> 日期：2026-08-28<br>
> 结论：通过

## 1. 实现边界

M7-I04 增加独立 `LoginDefense` 应用端口和 `RedisLoginDefense` 适配器。登录与注册在账号查询前提交规范标识和受控网络资源；适配器使用一条 Lua 同时裁决两个资源，只有二者都允许时才原子消费。登录标识固定为 10 次/15 分钟，受控网络固定为 60 次/5 分钟，第 10/60 次允许，第 11/61 次拒绝。`LOGIN` 与 `REGISTRATION` 使用不同 Key 空间，成功认证不会清除资源窗口。

已知 Account 的失败状态由第二条 Lua 原子维护：15 分钟内第 10 次失败建立精确 15 分钟临时锁，锁定期继续观察但不增长失败计数；成功认证只在未锁定时清除账号失败窗口。Redis 返回完整失败时间序列，Java 端通过领域 `AccountLoginAttemptState.reconstitute` 校验数量、顺序、窗口和锁定关系，再投影为不含失败时间和账号坐标的 `AccountLoginDefenseState`。临时锁不修改持久 `UserAccount.status`。

Lua 使用注入 `Clock` 的毫秒时间，并容忍 1 秒并发重排；有效时间取请求时间与 Redis 最近时间的较大值。明显反向时间、Redis 异常和损坏脚本结果统一抛出无 cause、无栈内容和无身份坐标的 `LoginDefenseUnavailableException`，认证调用方失败关闭。

## 2. 摘要与网络边界

Redis 不保存原始登录标识、网络前缀或 Account UUID。所有坐标先使用至少 32 byte 的外部密钥执行 HMAC-SHA256，消息域固定包含版本、认证流程和资源用途；Redis Key 带 Key ID 与 Base64URL Digest，并统一使用 `{login-defense}` Hash Tag，两个 Lua 的多 Key 在 Redis Cluster 中位于同一 Slot。超长标识先形成有界 SHA-256 预摘要再进入 HMAC，既避免保存攻击者输入，也避免所有超长输入共享一个全局桶。

`ControlledNetworkSourceResolver` 默认只使用直接 TCP 来源。只有直接来源命中配置的可信代理 CIDR 时才读取有界 `X-Forwarded-For`，并从右向左剥离可信代理，首个非可信字面地址作为客户端来源；非可信来源提交的代理头完全忽略。解析器不对 Header 执行 DNS 查询，只接受有界 IPv4/IPv6 字面量；无效可信代理链失败关闭。网络桶固定为 IPv4 `/24` 与 IPv6 `/64`。

防护默认关闭，避免 Bootstrap/Worker 和尚未接入本地认证 API 的 Profile 被隐式启用。部署启用时必须提供：

```text
CREWSCOPE_LOGIN_DEFENSE_ENABLED=true
CREWSCOPE_LOGIN_DEFENSE_HMAC_KEY_ID=v1
CREWSCOPE_LOGIN_DEFENSE_HMAC_KEY=<Base64 32-byte-or-stronger secret>
CREWSCOPE_LOGIN_DEFENSE_TRUSTED_PROXIES=<comma-separated CIDRs>
```

正式 Secret、可信代理和生产启用 Guard 由 `M7-I08` 收口。

## 3. 可观测性与公开边界

唯一新增指标是 `crewscope.authentication.defense.operations`，标签固定为三个枚举坐标：

```text
flow      = login | registration
operation = resource_admission | account_observe | account_failure | account_success
outcome   = allowed | identifier_limited | network_limited | both_limited |
            unlocked | locked | cleared | unavailable
```

指标不能接收标识、网络、Account ID、Redis Key 或异常文本。内部资源结果保持固定枚举，后续 `M7-A01/A02/A06` 把所有资源限制统一映射为 `429 too_many_requests + Retry-After: 1`，把账号不存在、密码错误、临时锁定、账号禁用和 Credential 损坏统一映射为 `401 invalid_credentials`；M7-I04 本身不增加可枚举账号的查询或响应。

## 4. 自动化验证

执行：

```bash
./mvnw -pl crewscope-server -am \
  -Dtest=LoginDefenseBoundaryM7I04Test,RedisLoginDefenseM7I04IntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：

- 边界与真实 Redis 专项 `13 / 13` 通过；
- Java 17 编译目标的 server 联合 Reactor 编译通过；
- 覆盖第 10/11 标识边界、第 60/61 网络边界、精确窗口下界、注册/登录隔离、成功只清账号失败、10 次失败锁定、锁定期不增长、精确到期恢复，以及资源和账号各 16 路并发精确预算；
- 覆盖非可信代理头忽略、可信代理链从右向左解析、IPv4 `/24`、IPv6 `/64`、无效可信 Header 失败关闭和零 Header DNS；
- 覆盖用途分离 HMAC、Redis Key 零原始标识/网络/Account UUID、脱敏字符串、固定三标签指标、明显反向时间、不完整/不可能脚本结果、Redis 停止时失败关闭和重启后恢复。

M7-I04 完成。`M7-I05` 将把本地 Session Account 经 Account/Organization Binding 解析为现有 Principal；注册、登录 API 在 `M7-A01/A02` 接入本端口。
