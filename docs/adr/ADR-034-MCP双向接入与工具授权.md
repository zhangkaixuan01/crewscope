# ADR-034：MCP 双向接入与工具授权

> 状态：已登记（选入前不生效）<br>
> 日期：2026-10-09（M11-S01 登记）<br>
> 归属：M11-S01 薄登记；选入时在本文件细化，不另建新号<br>
> 关联决策：[ADR-038](ADR-038-团队成员生命周期与责任转移.md)（授权版本与撤权）、[ADR-019](ADR-019-ActionBundle调度与外部结果对账协议.md)（外部结果对账）<br>
> 影响里程碑：M11（可选 I02/F03）

## 范围边界（登记时的边界声明）

- **Client 侧**：外部 MCP Server 的工具进入 Team 工具目录，受「Team 工具目录 + Task Tool Policy」双重治理；未启用工具不可调用；工具清单变化必须显式重新授权；本地 stdio 工具沙箱隔离与远程 HTTP 工具出站边界分别验证，远程调用不虚称在本机沙箱内。
- **Server 侧**：CrewScope 作为 MCP Server 只暴露只读查询与明确受权的命令，不暴露跨 Team 能力；外部身份有独立作用域、到期与撤销，不复用浏览器 Cookie/CSRF/Task Token 作通用外部凭证。
- **写副作用**：与内部工具走同一授权、幂等与 Audit 路径；UNKNOWN 对账沿用 ADR-019；Human Gate 不被绕过。
- 落点：`crewscope-agentscope` 新包 `io.crewscope.agentscope.mcp`（不进 `crewscope-integration`——MCP 是 Agent 工具面而非外部系统集成）。

## 已知事实（M11-S01 核实）

- **主计划 §10.3 第 3 条的构件名修正**：`agentscope-extensions-protocol` **不存在**（agentscope-bom 2.0.0 与本地 m2 仓库均无此 artifact）。MCP 能力实际位于 `agentscope-core` 的 `io.agentscope.core.tool.mcp`（`McpClientBuilder` 支持 Stdio/Http/Sse/StreamableHttp 传输、`McpTool`、`McpContentConverter`）与 `io.agentscope.core.tool.McpClientManager`。BOM 中最接近的是 `agentscope-extensions-agent-protocol`（A2A 方向，本地未下载）。I02 选入时按本事实落实依赖，落点结论不变。
- 当前仓库无任何 MCP 代码；Task Tool Policy、Team 工具治理与 Human Gate 机制是 M5–M8 已交付的接入面。

## 选入后须回答（进入实现前闭合）

1. MCP 注册信息是否需要持久化；若需要，迁移文件独立成文并明确归属（M11 §10.8 开放项 5）。
2. 工具清单的版本化与漂移检测：清单变化如何触发重新授权，审计口径是什么。
3. Server 侧外部身份的 audience / scope / 到期 / 撤销模型，与 ADR-038 授权版本的关系。
4. 远程出站控制矩阵：SSRF、重定向、私网地址、超时与巨量响应的处置。
5. stdio 本地工具的沙箱边界与宿主资源限制。
6. F03 管理面（注册、连接测试、清单预览、启用/禁用、统计）的前置条件与权限模型。
7. 工具描述作为不可信内容的渲染与注入防护边界。

## 结果

登记不产生任何实现义务；未选入时本文不阻塞 M11 主线，也不得被引用为「已验证」。选入后 I02/F03 按本文件细化并在原文件上完成决策，进入实现。
