# M0-Q01：Release Gate

> 日期：2026-08-07<br>
> 状态：已完成<br>
> 适用范围：M0 全模块

## 目标

把 M0 分散的领域、数据、事件、存储、AgentScope、API、可观测性和前端验证收敛为一个可重复执行的 Release Gate。Gate 对 Docker、固定 Sandbox 镜像、数据库迁移、测试结果、前端构建、组件状态、浏览器流程、视觉基线和文档完整性同时负责。

## 统一入口

本地入口：

```bash
./scripts/m0-release-gate.sh
```

执行顺序：

```text
Docker 与固定 Sandbox 镜像
  -> Markdown 链接检查
  -> Maven clean verify
  -> pnpm frozen install
  -> Vitest coverage
  -> TypeScript + Vite build
  -> Histoire build
  -> Playwright smoke + visual regression
```

Gate 要求 Docker Daemon 和固定摘要 `maven@sha256:29a1658b…f939d4` 已存在。缺少任一前置条件时直接失败，不允许把 Docker Sandbox 或 Testcontainers 静默跳过后标记 M0 完成。

Vitest Coverage 是阻断式门禁，最低要求为 Statements 80%、Branches 70%、Functions 75%、Lines 80%。生成报告但低于任一门槛时 Gate 失败。

## CI 拓扑

GitHub Actions 包含四个 Job：

| Job | 环境 | 验证内容 |
|---|---|---|
| `backend` | Ubuntu、Temurin 17、Docker | 固定 Sandbox 镜像、7 模块 Maven Reactor、AgentScope Docker、PostgreSQL/Redis Testcontainers、V1→V5 迁移 |
| `frontend` | macOS 14、Node 24、pnpm 11、Playwright Chromium | Frozen Install、Vitest Coverage、Vite、Histoire、桌面/窄屏浏览器与视觉回归 |
| `quality` | Ubuntu、Node 24 | Commit Whitespace 与 39 份 Markdown 的本地链接完整性 |
| `release-gate` | Ubuntu | 强制前三个 Job 全部为 `success`，提供单一分支保护入口 |

后端 Surefire 报告与前端 Coverage、应用产物、Histoire、Playwright Report 和 Test Results 保存 14 天。失败时仍上传已经生成的证据。

Push 的 Whitespace 检查覆盖 `before..sha` 完整推送范围，Pull Request 覆盖 Base SHA 到 Head SHA。Quality Job 使用完整 Git 历史，避免浅克隆把 Head 误判为根提交后扫描仓库既有文件。Maven Wrapper 在运行测试前执行最多三次 Bootstrap 探测，只重试 Maven 分发包初始化；测试和构建本身只执行一次。后端未产生测试报告时 Artifact 步骤给出 Warning，不覆盖原始失败原因。

视觉回归使用与本地基线一致的 macOS 光栅化环境。截图路径不包含操作系统名称，桌面与窄屏按 Project 独立保存；浏览器补丁版本产生的文字栅格差异允许最多 2% 像素差，布局、尺寸和明显视觉变化仍会使 Gate 失败。

## 本次验证证据

验证环境：Java 21 编译目标 17、Maven 3.9.6、Docker 29.6.2、PostgreSQL 17、Redis 7.4、Node 24、pnpm 11、Chrome 151。

### 后端

```text
Maven modules       7 / 7 SUCCESS
Tests               177
Failures            0
Errors              0
Skipped             0
Migrations          V1 -> V5
Docker Sandbox      executed
PostgreSQL/Redis    executed through Testcontainers
```

测试分布：Domain 26、Application 35、AgentScope 9、Infrastructure 82、Server 25。Integration 模块当前没有独立测试，仍完成编译与打包。

### 前端

```text
Vitest files        4
Vitest tests        11 passed
Statement coverage 88.65%
Branch coverage    76.34%
Function coverage  79.41%
Line coverage      88.04%
Coverage gate      80 / 70 / 75 / 80 passed
Histoire            3 stories / 9 variants
Playwright          4 passed
Vite build          passed
```

Playwright 覆盖 Conversation/Control 双向切换、`focus/team` Query 保留，以及 1440×960 和 390×844 的 Conversation/Control 截图。

### 文档

`scripts/check-doc-links.mjs` 递归检查根 README 与 `docs` 中的 39 份 Markdown。相对链接 URI 非法或目标不存在时 Gate 失败；HTTP、Mail、Data URL 和页内 Anchor 不作为本地文件检查目标。

## M0 验收结论

1. 空库、V1 升级、非默认 Search Path 和 V1→V5 迁移通过；
2. WorkItem、DomainEvent、Outbox 和 Command Receipt 事务验证通过；
3. Outbox 并发、失败恢复、重复投递、Audit 与 Checkpoint 验证通过；
4. FilesystemArtifactStore 哈希、权限、过期、Tombstone 和清理验证通过；
5. CredentialStore 密文、授权、轮换、Rewrap、撤销和日志安全验证通过；
6. HarnessAgent、Structured Output、AG-UI、Interrupt/Resume 和固定镜像 Docker Sandbox 验证通过；
7. API 命令协议、Correlation、Trace、结构化日志、脱敏和低基数指标验证通过；
8. Conversation/Control AppShell、Design Token、组件状态和桌面/窄屏视觉基线验证通过；
9. 本地统一 Gate 通过，CI 已使用相同验证项并提供单一汇总 Job。

M0 达到出口条件。下一项为 M1-D01：Principal、TeamMember、TeamRole、MemberRole、状态和值对象。
