# M0-D01 Testcontainers 测试基线

> 模块：`crewscope-infrastructure`  
> Testcontainers：`2.0.4`  
> 验证日期：2026-08-06

## 1. 目标

Infrastructure 集成测试统一使用容器化 PostgreSQL 和 Redis，不读取本机
`localhost:5432/6379` 服务。

## 2. 容器基线

共享基类：

```text
AbstractPostgresRedisContainerIntegrationTest
```

容器配置：

| 服务 | 镜像 | 测试配置 |
|---|---|---|
| PostgreSQL | `postgres:17-alpine` | database/user=`crewscope`，随机宿主端口 |
| Redis | `redis:7.4-alpine` | 关闭 AOF 和 RDB，随机宿主端口 |

容器使用 Class 生命周期。Testcontainers/Ryuk 在测试结束时清理容器。

## 3. 动态配置

基类通过 `@DynamicPropertySource` 注册：

```text
spring.datasource.url
spring.datasource.username
spring.datasource.password
spring.data.redis.host
spring.data.redis.port
```

属性值直接来自已启动容器。测试不覆盖固定端口，不依赖 `application.yml` 的开发环境默认值。

## 4. Smoke Test

`InfrastructureContainersSmokeTest` 验证：

1. Spring Environment 中的五个属性与容器端点完全一致；
2. PostgreSQL 使用随机端口建立 JDBC 连接；
3. 查询结果为 database/user=`crewscope` 和 PostgreSQL 17；
4. Redis 使用随机端口完成 RESP `PING/PONG`；
5. 测试不启动 Spring Boot 应用，也不执行 Flyway，迁移验证由 M0-D02 负责。

## 5. 使用方式

后续 Infrastructure 集成测试继承：

```java
class RepositoryIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {
}
```

需要 Spring 测试上下文时，子类使用 `@SpringJUnitConfig` 或 `@SpringBootTest`。继承的动态属性在
ApplicationContext 刷新前生效。

## 6. 自动化证据

定向命令：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-infrastructure -am \
  -Dtest=InfrastructureContainersSmokeTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：

```text
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

`disabledWithoutDocker=true` 允许没有 Docker 的普通开发环境跳过容器测试。M0-Q01 CI 必须提供
Docker，并校验容器测试没有 Skipped。

## 7. 结论

M0-D01 已完成。PostgreSQL/Redis 测试端点、生命周期和 Spring 动态配置已经统一，M0-D02、
M0-D05、M0-D06、M0-E02 和 M0-E03 复用该基线。
