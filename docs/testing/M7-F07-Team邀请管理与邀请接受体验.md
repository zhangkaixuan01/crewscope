# M7-F07 Team 邀请管理与邀请接受体验

## 1. 交付结果

M7-F07 已在 Team 成员页交付邀请创建、Keyset 列表、一次性链接复制和待接受邀请撤销，并实现正式 `/invite#token=...` 公开邀请入口。拥有 `MEMBER_MANAGE` 的 Owner/Admin 可以管理当前 Team 邀请；普通成员仍可查看成员目录，但不会挂载邀请管理组件，也不会发出邀请管理请求。

匿名访问者可以查看隐私化的 Team 名称、目标角色、有效期和是否定向。已有账号先登录再返回同一个邀请并接受；新用户进入正式注册页，注册事务原子创建账号并加入 Team。已登录账号接受成功后重新读取正式 Session 和 Team Scope，再进入新加入 Team 的 Conversation。

## 2. Gateway、Store 与一次性证明

Invitation Gateway 固定消费：

```text
POST /api/v1/organizations/{organizationId}/teams/{teamId}/invitations
GET  /api/v1/organizations/{organizationId}/teams/{teamId}/invitations?after=&limit=50
POST /api/v1/organizations/{organizationId}/teams/{teamId}/invitations/{invitationId}/revoke
POST /api/v1/invitations/preview
POST /api/v1/invitations/accept
```

管理写请求和 Accept 统一携带 AuthStore 内存 CSRF 与单值 Idempotency Key。Invitation Store 的响应式状态只保存邀请元数据、Preview、阶段与脱敏问题；明文 Token、创建/撤销/接受幂等键只存在 Store 私有闭包或一次组件返回值中。Token 不进入邀请列表、URL Query、Session 投影、LocalStorage、SessionStorage、IndexedDB、Telemetry 或日志。

创建首次成功时，服务端返回的一次性 Token 只被 TeamInvitationManager 转换为当前 Origin 的 Fragment 链接。离开创建表单或切换 Team 后立即清理；幂等重放不允许再次显示 Token，页面会重新读取列表并提示撤销后创建新邀请。网络失败、超时和邀请服务不可用保留相同命令意图，确定性冲突开始新意图。

## 3. 公开 Preview 与身份衔接

`/invite` 只接受单个 `#token=<43-char-base64url>` Fragment。页面把证明读入进程内存后立即使用 Router Replace 清理地址栏和当前历史项，再调用匿名 Preview。Fragment 重复、附加未知参数或格式非法时不调用服务端，直接进入不可用状态。

Preview 只呈现：

- `AVAILABLE / EXPIRED / UNAVAILABLE`；
- Team 名称、目标内置角色、有效期和是否定向；
- 不呈现目标邮箱、邀请人、Organization、Principal、Membership 或内部失败原因。

匿名已有账号通过无 Token 的 `returnTo=/invite` 登录；一次性证明保留在当前 JavaScript 进程的 Invitation Store 私有内存，登录成功后重新 Preview。新用户进入 `/register` 时也通过相同内存交接证明，不把 Token 再写回地址栏；Registration Mode 继续由正式 Session 决定，带邀请注册沿用 M7-A01 原子事务。

## 4. 接受、撤销与体验合同

- Accept 请求体只提交 Token，账号、Organization、Principal、Team、Membership 与 Role 均由服务端从当前 Session 解析；
- Accept 成功后 AuthStore 重读 Session，ScopeStore 选择新增或与 Preview Team 名称匹配的 Team，再进入带 Team Query 的 Conversation；
- 无效、已使用、已撤销、邮箱不匹配和跨账号目标统一显示“无法使用这个邀请”；
- `EXPIRED` 使用明确过期标题，`UNAVAILABLE` 不解释具体内部原因；
- 撤销使用 `aria-modal` 对话框，支持初始焦点、Tab 焦点陷阱、Escape 和取消焦点恢复；撤销后原按钮消失，焦点移动到稳定的“团队邀请”标题；
- 创建表单初始聚焦邮箱，成功后聚焦一次性链接，错误聚焦脱敏摘要；
- 桌面与 390px 保持单一 `main`、零横向溢出，列表使用合法 Table/List ARIA 容器。

## 5. 验证证据

验证结果：

- 全量 Vitest：107 个文件、577 个测试全部通过；
- M7-F07 Playwright：Desktop Chromium 与 390px Chromium 共 14/14 通过；
- 全量 Playwright/视觉/Axe：240/240 通过；
- Production Build：`vue-tsc --noEmit` 与 Vite Build 通过；
- Histoire：21 个 Story、153 个 Variant 构建通过；
- Sensitive Field Gate：73 个生产文件、21 个 Story 通过。

真实浏览器额外验证了公开邀请 Available 状态在 1280px 和 390px 下的视觉层级、标题初始焦点、单一 `main` 与零横向溢出。浏览器视口、标签和 Histoire 服务已在验收后清理。

## 6. 后续边界

M7-F07 不聚合整个开放身份体验的 Release Gate，也不删除部署/README 中剩余的占位入口。下一任务 M7-F08 收口 M7 全状态、响应式、Axe、视觉、公开字段、部署入口与文档说明。
