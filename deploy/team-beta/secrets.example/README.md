# Team Beta 本地配置

当前 Team Beta 使用简化 Compose，不再读取 `CREWSCOPE_SECRETS_ROOT` 或外部 Config Tree。

运行 `./deploy/team-beta/quickstart.sh up` 时，脚本会在 `deploy/team-beta/.runtime/.env` 生成本地随机密钥，
并将 Operator 密码写入 `deploy/team-beta/.runtime/bootstrap_password`。该目录已被 `.gitignore` 忽略。

不要把 `.runtime`、密码或模型 API Key 提交到仓库。修改普通配置后运行 `./deploy/team-beta/quickstart.sh up`；已有密码和加密密钥不要随意更换，备份时必须保留原值。完整操作见 [单机运维手册](../../../docs/runbooks/Team-Beta单机运维手册.md)。
