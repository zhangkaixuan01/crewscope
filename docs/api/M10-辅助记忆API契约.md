# M10-I02a 辅助记忆 API 契约

> 状态：冻结（I02a 生命周期 + 本人查看/清除）<br>
> 版本：V1<br>
> 日期：2026-10-04<br>
> 适用范围：Agent 辅助记忆的五元组存储、本人级查看与清除 API、运行时写入端口、TTL 清扫与部署开关

基路径：`/api/v1/organizations/{organizationId}/teams/{teamId}/agent-profiles/{profileId}`

## 0. 本轮边界

本契约覆盖 **I02a 辅助记忆生命周期**：策略实体承接 `AgentMemoryPolicyReference` 悬空引用、五元组有界存储（命名键值 ≤1KB、TTL 90 天滑动、每成员×Agent ≤100 条）、本人级查看/清除两个 HTTP 端点、供 I02b 消费的运行时写入端口、TTL 清扫与双层开关。以下不在本契约内：

- **I02b Prompt 注入**：候选如何进入注入清单、注入预算、`touch` 的调用方（使用即续期的触发点）、注入清单透出——已随 I02b 交付（2026-10-04，调用方行为见 §5 touch 补注），契约见 [M10-Prompt注入与注入清单契约](M10-Prompt注入与注入清单契约.md)；
- **I02c 反馈与开关组合验证**：「不适用」反馈 API 与开关组合下的端点行为已随 I02c 交付（2026-10-04，契约见 [M10-执行引用与反馈API契约](M10-执行引用与反馈API契约.md)——三端点零开关门控）；记忆写入触发（反馈提取进记忆）仍不在内，归后续切片；
- **F01c 前端**：无 UI 消费面；
- **策略管理 API**：策略是代码目录（§1），不是表也不是管理资源——不存在策略 CRUD；
- **HTTP 写入端点**：记忆值只由运行时（I02b 注入链）写入，不开放成员手写 HTTP；
- **事件与审计**：本生命周期**零领域事件、零审计事件、不走 outbox**（S01 未冻结要求；审计四列仅行级追溯）。清除是同步 200 回执（I01c cancel 先例：无 Idempotency-Key、结构性幂等——重复清除代际再 +1 无害）。

## 1. 数据模型与五元组

**两表 V58**（纯 PostgreSQL 主链，无向量扩展依赖）：

| 表 | 键 | 语义 |
|---|---|---|
| `agent_memory_owner` | 四元组 `(org, team, agent_profile, owner_principal)` 唯一 | 每个（成员×Agent）空间一行；`clearance_generation` 从 0 起单调递增（清除 +1） |
| `agent_memory_entry` | 七列空间键 `(四元组, policy_id, policy_version, memory_key)` 唯一 | 每条冻结偏好值一行；`version` 乐观锁；`expires_at` 滑动 TTL |

**策略版本进所有者键**：切换策略版本 = 新空间，同 `memory_key` 跨空间可并存；旧空间行由清扫异步删除。`policy_id/policy_version` 列**无 FK**——策略是代码目录（`AgentMemoryPolicyCatalog`），不是表；不可解析的引用显式降级（§3），绝不静默丢弃。

**策略=代码目录**：`AgentMemoryPolicy(policyId, version, ttlDays, maxEntriesPerOwner, valueMaxBytes)`，`DEFAULT_POLICY_ID = 7f2c9d64-5b1a-4f0e-9a3d-2c8b1e6f4a20`，默认 (v1, 90 天, 100 条, 1024 字节)。`DefaultAgentMemoryPolicyCatalog.resolve` 仅 DEFAULT id@v1 命中；新增策略版本 = 目录加条目且旧版本保持可解析。

**三条硬界**（domain 与 V58 CHECK 双侧一致）：

- **值 ≤1024 UTF-8 字节**（`octet_length`，非字符）——多字节值按字节计数；
- **`memory_key` 正则 `^[a-z0-9][a-z0-9-]{0,62}$`**（≤63 字符，小写数字连字符）；
- **每（成员×Agent）≤100 条**，**容量口径=当前策略空间**（当前代际+未过期+同 policy 空间内计数）；超限**显式拒绝**（§5 CAPACITY_EXCEEDED），绝不静默淘汰；同 key 覆盖不占新槽。

**防复活（单调代际）**：清除 = owner 代际 +1 + 删除当前代际所有策略空间行；每次写入携带写入时观察到的代际，适配器在 `SELECT ... FOR UPDATE` 锁 owner 行后校验代际，不符抛 `OptimisticLockConflictException`（服务转 STALE_CLEARANCE）——与清除竞争的在途写入被拒绝，已清除的记忆不可能复活。旧代际残留行（竞争窗口的防御性路径）读时 JOIN owner 当前代际不可见，由清扫物理删除。

## 2. 端点（本人级，仅两个）

### 查看本人记忆

```
GET /api/v1/organizations/{organizationId}/teams/{teamId}/agent-profiles/{profileId}/memory
```

**语义**：「当前用户在该 Agent 的记忆」——路径无 owner 参数，认证主体即所有者，不存在跨成员读路径。查看**不是使用**：TTL 不滑动、不创建 owner 行。响应恒 `200` + `Cache-Control: no-store`：

```json
{
  "policyReference": { "policyId": "7f2c9d64-…", "version": 1 },   // null=未配置（关）
  "policy": { "policyId": "…", "version": 1, "ttlDays": 90,
              "maxEntriesPerOwner": 100, "valueMaxBytes": 1024 },   // null=策略不可用
  "degraded": null,                                                  // "POLICY_UNAVAILABLE" 见 §3
  "clearanceGeneration": 0,
  "entries": [ {
    "memoryKey": "reply-language", "value": "简体中文", "version": 0,
    "expiresAt": "2027-01-02T09:00:00Z", "createdAt": "…", "updatedAt": "…",
    "createdBy": "<uuid>", "updatedBy": "<uuid>"
  } ],
  "entryCount": 1
}
```

`entries` 按 `memory_key` 升序、仅当前代际+未过期+当前策略空间，全量返回（≤100 条，无分页）。

### 清除本人记忆

```
DELETE /api/v1/organizations/{organizationId}/teams/{teamId}/agent-profiles/{profileId}/memory
```

**同步 200 回执**（无 Idempotency-Key，结构性幂等）：代际 +1、删除**所有策略空间**的当前代际行：

```json
{ "clearedCount": 3, "clearanceGeneration": 1 }
```

重复清除无害：`clearedCount=0`、代际再 +1。撤权立即不可读——守卫每请求读当前成员事实（ADR-038），非成员 403，其名下残留行不可达且随 TTL 过期。

## 3. 三态语义（view）

| 状态 | policyReference | policy | degraded | entries | 语义 |
|---|---|---|---|---|---|
| 未配置（本人开关=关） | `null` | `null` | `null` | `[]` | 当前 Agent 配置无 `memoryPolicy`——记忆关 |
| 正常 | 有 | 有 | `null` | 列表 | 健康空间 |
| 策略不可用 | 有 | `null` | `"POLICY_UNAVAILABLE"` | `[]` | 引用的策略版本目录解析不出——**降级不伪装成空** |

**本人开关载体**：Agent 当前配置的 `memoryPolicy` Optional（配即开）。开关后端即既有 `POST .../configurations`，本轮零新增配置端点、零配置链改动；配置时不校验 policyId 可解析（悬空引用由目录解析时显式降级）。

## 4. 守卫与错误码矩阵

**守卫链四步**（本人级，无团队枚举、无角色端口）：

1. 认证主体 = 本组织可行动 USER，否则 403；
2. Team 存在且 ACTIVE：未初始化 → 422（`team.initializationStatus`）；缺失 → 404；非 ACTIVE → 422（`team.status`）；
3. AgentProfile 存在且属本 Team——跨队/缺失**同形 404**（绝不 403 泄漏存在性）；
4. 认证主体是该 Team 的 ACTIVE 成员（`canParticipate`），否则 403。

| 场景 | 状态码 | code | details |
|---|---|---|---|
| 路径字段非 UUID | 400 | `invalid_request` | `field=organizationId\|teamId\|profileId` |
| 非本组织/非成员/撤权后 | 403 | `policy_denied` | — |
| Team/Profile 缺失或跨队 | 404 | `aggregate_not_found` | — |
| Team 未初始化/非 ACTIVE | 422 | `invalid_value` | `field=team.initializationStatus\|team.status` |

## 5. 写入端口契约（供 I02b，非 HTTP）

`AgentMemoryService` 的三个内部端口（授权是调用方契约——注入运行时已持有执行 Scope）：

**`upsert(key, memoryKey, value)` → `AgentMemoryUpsertResult`**：

| Outcome | 触发 | entryCount/maxEntries |
|---|---|---|
| `WRITTEN` | 写入或覆盖成功（TTL 即刷新） | 生效计数 |
| `MEMORY_DISABLED` | 部署开关关（§7） | 0/0 |
| `NOT_CONFIGURED` | 当前配置无 `memoryPolicy`（本人开关关） | 0/0 |
| `POLICY_UNAVAILABLE` | 策略目录解析不出 | 0/0 |
| `CAPACITY_EXCEEDED` | 当前策略空间已满且非同 key 覆盖 | 当前计数/上限，供提示清理 |
| `STALE_CLEARANCE` | 与清除竞争，代际过期——拒绝且不复活 | 当前计数/上限 |

键/值校验（§1 三条硬界）在端口内执行，违例抛 `DomainValidationException`（`agentMemory.memoryKey` / `agentMemory.value`）。

**`touch(key, memoryKey)` → boolean**：使用即续期——仅当开关开、已配置、条目在期且代际当前时把 `expires_at` 刷到 now+ttlDays，返回 true；其余一切（缺条目/过期/关）返回 false，调用方静默跳过，**不失败模型调用**。调用方=I02b 注入链（注入=使用）。

>I02b 补注（2026-10-04，调用方已交付）：注入链**仅对实际注入（INJECTED）的条目**调 touch——被预算裁掉的候选以 CANDIDATE 阶段入清单但**不续期**（被考虑过≠被使用）；touch 返回 false 与抛异常均容忍，续期绝不拖垮模型调用；`crewscope.memory.enabled=false` 时注入链不调 `list` 也不调 `touch`（零调用零引用零 token）。清单/touch 的完整语义见 [M10-Prompt注入与注入清单契约](M10-Prompt注入与注入清单契约.md)。

**`list(key)` → List&lt;AgentMemoryEntry&gt;**：注入读——当前策略空间可见条目，**不续期**（读≠使用）。策略引用已配置但目录解析不出时注入读**让层为空、无降级码**（注入链的冻结约定，view 面的 POLICY_UNAVAILABLE 降级不进入 Prompt 注入路径）。

**容量口径再冻结**：upsert 前按 `findVisible`（当前代际+未过期+同空间）计数；同 key 已存在 → 覆盖不占新槽；空间满且新 key → CAPACITY_EXCEEDED 显式拒绝。

## 6. TTL 与清除

- **写入即刷新**：每次 upsert（含覆盖）把 `expires_at` 置为 now+ttlDays；
- **使用即续期**：`touch` 同上——端口随 I02a 交付，注入链调用已随 I02b 交付（仅 INJECTED 条目，见 §5 补注）；
- **查看不 touch**：查看≠使用；
- **物理清扫**：worker profile 的 `@Scheduled`（`crewscope.memory.sweep.poll-interval`，默认 1h，批 500，AtomicBoolean 防重入）删除过期行与陈旧代际行；**不挂部署开关门**（关掉记忆后存量行仍走 TTL）；
- **清除口径**：`clearedCount` 跨所有策略空间（当前代际行一并删）。

## 7. 开关矩阵

| 开关 | 载体 | 门什么 | 不门什么 |
|---|---|---|---|
| 部署开关 `crewscope.memory.enabled`（默认 false） | 服务构造 boolean（`KnowledgeIndexJobService.refreshEnabled` 先例） | **新增模型向写入**（upsert→MEMORY_DISABLED、touch→false）；HealthIndicator 同门（关=不装配，非 DOWN） | 查看/清除/TTL 清扫 |
| 本人开关 | Agent 配置的 `memoryPolicy` Optional（配即开） | 该（成员×Agent）空间的写入与读取语义 | 配置链零改动，后端=既有 `POST .../configurations` |

## 8. 持久化与迁移

- **V58 `agent_memory_lifecycle.sql`**（tip 57 → 58，纯 PG）：两表 + `uk_agent_memory_owner_scope` 四元组唯一 + `uk_agent_memory_entry_space_key` 七列唯一 + FK（team 复合/agent_profile 复合三列/principal/entry→owner 四元组，全 RESTRICT）+ CHECK（key 正则/value 字节界/policy_version≥1/两代际≥0/version≥0/时间戳序）+ 两索引（owner+generation 复合、expires_at）；无存量回填；
- **policy 列无 FK** 的理由：策略是代码目录非表——加版本=目录加条目，不需要迁移；
- **owner 行 FOR UPDATE 锁**是同空间写/清除的串行化点（§1 防复活）。

## 9. 审计口径

零领域事件、零审计事件、不走 outbox。行级审计四列（created_by/updated_by/时间戳）随写维护；管理面无日志查询承诺。若后续切片引入事件，须另立契约修订本节。
