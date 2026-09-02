# M8-A02 验收清单

- 仅 Team 管理员可以创建、取消和重试导入；跨 Organization、Team 或 WorkProject 的标识按不可见资源处理。
- Connection、Grant、Allowlist、Catalog 和默认分支在 Worker 执行前再次复验；任何撤权或漂移都会稳定失败关闭。
- 重复请求不会创建第二个活动任务或第二个 RepositoryBinding；Repository Key 作为部署级物理受管仓库身份由数据库全局唯一约束兜底，其他 WorkProject 通过 Catalog 绑定已存在的 Key。
- Worker 只把远程仓库导入到 canonical Managed Root，拒绝符号链接、Root 越界、Owner 不匹配和非 bare 目录。
- AskPass 使用短期凭证文件，凭证不出现在参数、日志、事件、审计、DTO 或模型上下文；远程 Git 输出不返回浏览器。
- `REQUESTED/PREFLIGHTING` 任务支持原子取消；`IMPORTING` 已开始 Git I/O 后稳定返回冲突；`FAILED` 任务支持重试并重新复验授权。Web 在进度面板提供对应的取消/重试操作，恢复后最终只能产生一个 `LOCAL_MANAGED` RepositoryBinding。
- 导入完成后 `GET .../repository-bindings` 可看到仓库，RepositoryBinding Preflight 和 CodingTarget 创建继续通过。
