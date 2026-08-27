# Team Beta Secret 文件

在 `CREWSCOPE_SECRETS_ROOT` 指向的受保护目录中创建以下文件。Linux 生产环境在启动前执行
`operations/prepare-secret-permissions.sh <operator.env>`：目录固定为 `root:root/0700`，API、Worker
和 Prometheus 需要的文件固定为 `root:10001/0440`，Redis ACL 与备份口令固定为
`root:root/0600`。

- `database_password`：PostgreSQL 强随机密码；
- `bootstrap_password`：`crewscope-monitor` 的强随机密码；
- `credential_keys`：`v1=<32-byte Base64>` 格式的 AES-256 Key Ring；
- `activity_cursor_key`：32 字节随机值的 Base64；
- `diff_cursor_secret`：至少 32 UTF-8 字节的随机值；
- `task_token_key`：32 字节随机值的 Base64；
- `redis_url`：与 Redis ACL 一致的 `redis://default:<password>@redis:6379`；
- `redis_acl`：至少包含受密码保护的 `default` 用户，以及只能执行 `PING` 的无密码 `health` 用户。
- `backup_passphrase`：至少 32 字节的独立备份加密口令，由 Operator 环境文件的 `CREWSCOPE_BACKUP_PASSPHRASE_FILE` 引用，不挂载给应用容器。

这些文件只描述格式，不提供可用凭证。不要把实际 Secret 放入仓库、Compose 环境、日志或发布证据。

`redis_acl` 在宿主机保持 `root:root/0600`。Redis 启动时先将它复制到容器私有 `tmpfs`，设置为
`redis:redis/0400`，再由官方入口降权运行；无需放宽宿主机 Secret 权限。
