# M2-A06：NativeWorkItem Provider 初始化与 Binding 查询

> 状态：已完成
> 日期：2026-08-11
> 模块：`crewscope-application`、`crewscope-integration`、`crewscope-infrastructure`、`crewscope-server`

## 交付目标

M2-A06 把 CrewScope 原生 WorkItem 能力注册为可解析的 Provider 事实，为新旧 Team 建立默认 Workspace Binding，并向对话式入口和传统管理入口提供同一只读查询结果。TaskIntent 最终确认与 WorkItem 创建仍由 M2-A07 完成。

## 内置注册契约

```text
ProviderDefinition
  key: work-item
  type: WORK_ITEM
  interfaceVersion: 1.0.0
  displayName: CrewScope WorkItem

ProviderImplementation
  key: native-work-item
  implementationVersion: 1.0.0
  connectionRequirement: NONE

Capabilities
  workitem.read
  workitem.create
  workitem.update
  workitem.comment
  workitem.resource-link
```

每个 READY Team 默认 Workspace 建立一个 TEAM Owner、`defaultUsage=true` 的 ACTIVE Binding。Binding 不包含 Connection、ConnectionGrant 和外部执行身份，有效资源固定为 `workspace:{workspaceId}`。

## 初始化与迁移

Definition、Implementation 和 Binding 使用产品 Key、Organization 与 Team 派生的稳定 raw MD5 UUID。Java 与 PostgreSQL `md5(text)::uuid` 产生相同 ID。

新 Team 创建和遗留 Team 补全在原 Team foundation 的 REQUIRED 事务内调用 Provider 初始化。初始化先获取 Organization 级 PostgreSQL advisory transaction lock，再按 Definition、Implementation、Binding 顺序查找或创建并验证完整产品契约。重复调用不产生额外写入；被停用的 Binding 保持停用。

V9 只处理具有 Owner Member、默认 ACTIVE Team Workspace 的完整 ACTIVE Team。迁移创建相同稳定 ID，并在每个阶段验证已存在事实；稳定 Key 或 ID 指向不兼容契约时中止迁移。未完成 Team 等待既有补全流程。

## 查询 API

```text
GET /api/v1/organizations/{organizationId}/teams/{teamId}/provider-bindings
```

请求必须解析为同 Organization 的 ACTIVE USER 和当前 ACTIVE TeamMember。服务端固定使用 Team 默认 Workspace、TEAM Owner、WORK_ITEM 类型和 NativeWorkItem 能力范围调用只读 `ProviderBindingResolver`。响应包含：

- Provider Type、Definition/Implementation Key 和请求能力；
- `RESOLVED`、`NOT_FOUND` 或 `AMBIGUOUS` 状态及解析层级；
- 已解析 Binding 的固化 Definition/Implementation 版本、状态、connectionless 标记、有效能力和资源；
- 歧义时按 UUID 稳定排序的 Binding ID；
- `Cache-Control: no-store`。

查询不创建、不修复、不激活任何 Provider 事实。

## 验证结果

- 新增 13 项测试或测试方法；全仓 `clean verify` 共执行 678 项测试，零失败、零错误、零跳过；
- Application 测试覆盖稳定 ID、幂等重放、产品契约冲突、Membership Scope、停用和歧义；
- Integration 测试锁定 NativeWorkItem 描述与能力全集；
- HTTP 测试覆盖三种解析结果、connectionless DTO、稳定歧义 ID、非法路由 ID 与 `no-store`；
- V8→V9 真实 PostgreSQL 迁移测试覆盖既有完整 Team 补全、未完成 Team 跳过、Java/SQL 稳定 ID 一致和 Binding 形状；
- 真实 PostgreSQL 应用测试覆盖新 Team 原子初始化、4 路并发重复执行、唯一默认 Binding 和失败全回滚；
- Spring 装配测试确认 NativeWorkItem、注册、初始化、Team Initializer 与查询服务均只有一个生产 Bean。

验证命令：

```bash
/Users/zhangkaixuan/Downloads/apache-maven-3.9.6/bin/mvn clean verify
node scripts/check-doc-links.mjs
git diff --check
```
