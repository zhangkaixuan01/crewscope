# M0-D02 Flyway 迁移测试基线

> 模块：`crewscope-infrastructure`  
> Flyway：`11.14.1`  
> PostgreSQL：`17-alpine`  
> 验证日期：2026-08-06

## 1. 目标

CrewScope 数据库迁移具备空库初始化、历史版本前向升级和独立 Schema 隔离能力。所有迁移由
Flyway 管理，应用对象统一位于 `crewscope` Schema。

## 2. 迁移契约

Flyway 使用以下固定配置：

```text
location       = classpath:db/migration
schema         = crewscope
defaultSchema  = crewscope
createSchemas  = true
namingValidate = true
```

迁移文件遵循以下规则：

1. 版本迁移只向前追加；
2. 已发布迁移保持内容和校验和稳定；
3. 表、引用目标和索引显式使用 `crewscope.*`；
4. 每个迁移同时通过空库和历史版本升级测试；
5. Flyway 历史表位于 `crewscope.flyway_schema_history`；
6. V2 及后续迁移在源码中说明表、关键字段、约束和索引的业务目的。

应用默认 JDBC URL 使用：

```text
currentSchema=crewscope,public
```

JDBC 与原生查询优先解析 CrewScope 对象，`public` 保留 PostgreSQL 扩展兼容能力。Hibernate 同时使用
`hibernate.default_schema=crewscope`。

## 3. 集成测试

测试类：

```text
FlywayMigrationIntegrationTest
```

测试继承 M0-D01 Testcontainers 基线，每个用例重建 `crewscope` 和 `migration_probe` Schema，隔离同一
PostgreSQL 容器内的数据库状态。

### 3.1 空库到最新版本

执行全部可解析迁移，验证：

- 空库至少存在一个待执行迁移；
- 实际执行数等于迁移前待执行数；
- 执行后不存在 Pending 迁移；
- Flyway 校验和验证通过；
- V1 基础表与历史表位于 `crewscope`。

### 3.2 V1 到最新版本

第一次迁移设置 `target=1`，第二次移除 Target 并迁移到运行时发现的最新版本。升级执行数等于第二次
迁移前的 Pending 数量。

当前最新生产版本为 V4，第二次迁移执行真实 V1→V4 升级。后续版本加入后，该用例继续自动覆盖从
V1 到最新版本的完整升级链。

### 3.3 非默认 search_path

测试连接设置：

```text
currentSchema=migration_probe
```

Flyway 仍将全部应用表和历史表写入 `crewscope`。`migration_probe` 中不存在 CrewScope 表和
`flyway_schema_history`，证明迁移结果不依赖连接初始 `search_path`。

## 4. 自动化证据

定向命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  -Dtest=FlywayMigrationIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：

```text
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

`disabledWithoutDocker=true` 支持普通开发环境在 Docker 不可用时跳过容器测试。M0-Q01 CI 提供
Docker，并校验容器测试的 Skipped 数量为 0。

## 5. 结论

M0-D02 已完成。Flyway 的空库初始化、V1 前向升级、校验和与 Schema 隔离已形成自动化基线，当前
升级目标为 V4。
