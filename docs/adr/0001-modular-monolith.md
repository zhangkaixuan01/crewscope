# ADR-0001：采用六模块模块化单体

状态：Accepted

## 决策

CrewScope 首期使用一个 Maven Reactor 和一个 Spring Boot 可执行应用。领域、应用、AgentScope、集成、基础设施和服务入口由六个 Maven 模块承载。

依赖方向：

```text
crewscope-server
  ├── crewscope-agentscope
  ├── crewscope-integration
  ├── crewscope-infrastructure
  └── crewscope-application
        └── crewscope-domain
```

## 结果

- 一个部署单元支持 MVP 快速交付。
- Provider、Connector、Worker 和 Sandbox 保持清晰边界。
- 领域规则保持框架独立。
- 达到独立发布、依赖隔离或扩缩容阈值后，可以沿现有 Port 拆分。
