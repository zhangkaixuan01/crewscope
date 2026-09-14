# M9-Q02 Release Gate

M9-Q02 是 M9 的功能发布验收。门禁把可在开发机重复执行的合同检查与需要真实环境、真实凭证和真实用户参与的证据分开，避免把模拟结果误称为生产验证。

## 本机门禁

```bash
./scripts/m9-q02-local-gate.sh contracts-only
./scripts/m9-q02-local-gate.sh local-precheck
```

`contracts-only` 执行文档链接、M9-Q01 体验回归、OpenAPI、环境配置合同；`local-precheck` 在此基础上顺序执行 Maven、前端单元覆盖率、生产构建、质量门禁和浏览器 E2E。浏览器测试必须使用独占资源，避免并行容器争用污染结果。

## 真实环境验收记录

| 验收项 | 证据要求 | 状态 |
|---|---|---|
| 空环境 Setup | 全新 PostgreSQL/Redis/生产 Web，完成注册、首 Team、模型配置、仓库导入 | 待执行 |
| 双用户协作 | 两个独立账号完成责任分配、Coding、Review 和权限边界 | 待执行 |
| Draft PR 闭环 | 使用授权 GitHub App 完成受管仓库导入和 Draft PR | 待执行 |
| 首屏理解度 | 3 名新用户在 30 秒内说出当前待办 | 待执行 |
| Diff 评论可发现性 | 至少 2/3 新用户无需提示完成行级 Review 评论 | 待执行 |
| 配置路径 | 3 名用户完成模型/仓库配置，记录耗时、放弃次数和切 Team 草稿恢复 | 待执行 |

真实环境记录必须包含时间、部署版本、测试账号标识（不可写入密码或 Token）、卡点和截图/日志摘要。未执行的项目保持“待执行”，不得以本机 Mock 或快照替代。
