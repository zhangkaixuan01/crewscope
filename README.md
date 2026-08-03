# CrewScope

CrewScope 是面向技术团队的协作式 AI 工作执行平台。成员、Personal Agent、Team Agent 和 Specialist Agent 在共享工作上下文中完成计划、执行、Review、Handoff、Takeover 和跨系统交付。

## 技术基线

- Java 17
- Spring Boot 4.0.4
- AgentScope Java 2.0.0
- PostgreSQL 17
- Redis 7.4
- Vue 3、TypeScript、Vite 和 pnpm

## 工程结构

```text
crewscope-domain          领域模型与状态机
crewscope-application     用例、Port 与事务边界
crewscope-agentscope      AgentScope Runtime Adapter
crewscope-integration     Provider 与 Connector 实现
crewscope-infrastructure  PostgreSQL、Redis、Outbox 与基础设施
crewscope-server          Spring Boot、REST、AG-UI 与管理端点
crewscope-web             Vue 3 团队工作台
```

## 本地启动

准备 Java 17 和 Docker，然后执行：

```bash
docker compose up -d
./mvnw clean verify
java -jar crewscope-server/target/crewscope-server-0.1.0-SNAPSHOT.jar
```

本地默认初始管理员为 `crewscope / crewscope`。部署环境使用
`CREWSCOPE_BOOTSTRAP_USERNAME` 和 `CREWSCOPE_BOOTSTRAP_PASSWORD` 覆盖，并设置独立强密码。
数据库、端口与执行配置的环境变量示例见 `.env.example`。

系统信息接口：

```text
GET http://localhost:8080/api/v1/system/info
GET http://localhost:8080/actuator/health
```

启动前端：

```bash
cd crewscope-web
pnpm install
pnpm dev
```

前端使用 Node.js 20。通过 nvm 初始化环境：

```bash
cd crewscope-web
nvm use
pnpm install --frozen-lockfile
```

使用 IntelliJ IDEA 时，打开仓库根目录并导入根 `pom.xml`。Project SDK 可使用
JDK 17 或更高版本，Language level 保持 Java 17；Maven 可选择工程自带的 Wrapper，
也可以选择本机 Maven 3.9.6。

## 开发规则

- 公开 API 统一使用 `/api/v1`。
- 领域层保持纯 Java。
- 外部系统能力通过 Provider 与 Connector Port 接入。
- 外部副作用统一进入 PlannedAction、授权、Worker、Receipt 和 Reconcile 链路。
- 领域状态、DomainEvent 和 Outbox 在同一事务提交。
