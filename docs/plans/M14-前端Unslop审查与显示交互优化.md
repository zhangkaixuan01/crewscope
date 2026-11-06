# M14：前端 Unslop 全面审查与显示交互优化

## 背景与定位

用户裁定 M14 = 对前端的彻底全面审查、显示与交互优化，以 unslop 方法论执行（审计 → 方向 → 分 pass 修复）。2026-11-06 完成全量审计：22 页运行时探针（signs.md 逐组定量取证）+ 长内容压力数据（超长项目名/WorkItem 标题）× 三档宽度 × 折叠态组合扫描 + 用户指认复核。

**判定：light slop → polish mode**。多轮打磨后底子好（零 emoji/零 loop 动画/零真 transition-all/字号 5 档/单 accent/对比度 5.0 契约），残留是轻量 sign 加一组审美债。

## 方向五线（已确认）

- **Product**：团队协作式 AI 工作执行平台——Personal Agent 承接执行，责任、Review 与交付证据在同一工作空间汇合
- **Character**：安静、精确、机构台账感
- **World**：组织工作台账/控制室，绿色品牌 ramp 为世界 tint
- **Moments**：Agent 消息流（agent-soft 微底 + streaming）；状态徽章系统
- **Signature**：mono 编号/时间戳/计数（#1、7 分钟前、0 token）

## 审计残留清单

| # | Sign | 证据 |
|---|---|---|
| S1 | uppercase + 0.08em 宽字距 eyebrow/表头族 | 全站 `.eyebrow` 模式；表头 operations 22 处/agents 12/members 10/audit 9（裁定：全改 sentence case） |
| S2 | 字重 700 | 每页 4–20 处（走 `--cs-weight-bold` token） |
| S3 | 图标 11–30 共 14 种尺寸 | icon census（today 同屏 7 种） |
| S4 | surface 渐变 2 处 | operations 告警横幅、agents 空态卡 |
| S5 | gap 7px 族 | 运行时探针（下拉菜单项/分段控件） |
| S6 | tabular-nums 未用 | 数字密集场景待有数据团队验证 |
| S7 | 杂项 | avatar 首字母进 aria-label（「MM14」读屏冗余）等 |
| S8 | 折叠态审美债（用户指认） | 参与者面板竖排「展开参与者」120px 竖条；会话列表折叠轨文字钮；会话列表条目 67px 双行 + 图标底色块 + 行尾 chevron + 「团队可见 · N 条消息」副题（M13c 判例应砍型）；侧栏窄轨与设置页分栏折叠复核 |
| S9 | 对话工作区结构（用户 2026-11-06 追加指认：「不要现在这种样式，整体重新设」） | >1280 五列 grid `22% 8px 1fr 8px 18%` + gap 16px×4 + resizer 8px×2 = 纯缝隙 ~80px；双 resizer 常驻可见（opacity .65 竖线×2）；三面板分离卡片语言与台账贴合语言不符；空间利用差 |

## 批次

- **B1 对话工作区重设计**（S8+S9，用户最痛，幅度待裁定）：见下方「B1 方案」专节
- **B2 折叠/收窄态复核**：侧栏 76px 窄轨、设置页分栏面板、≤900 档——对齐/密度几何探针复核，修发现项
- **B3 Type pass**（S1+S2）：uppercase/tracking 全站改 sentence case（约 20 产品文件 + layout.css + BaseDialog/Drawer eyebrow）；`--cs-weight-bold` 语义位降 600；starter copy 感叹号
- **B4 Icons pass**（S3）：尺寸机械收敛 11→12、13→14、15→14、17→16、19→18、21→20、22→20、23→24、26→24（12/14/16/18/20/24/30 语义档）；spikes/stories 不动
- **B5 Color/杂项**（S4+S5+S6+S7）：两处渐变中性化（operations 告警横幅、agents 空态卡）+ message-stage 底部渐变并入裁定；gap 7px 归格；avatar aria-hidden；tabular-nums 数字密集场景补证

### B1 方案：对话工作区重设计（2026-11-06 侦察定稿）

**现状诊断（>1280 桌面，定量）**

- grid 五列 `var(--left,22%) 8px minmax(0,1fr) 8px var(--right,18%)`，外加油 gap 16px×4：**纯缝隙 ~80px**（1440 视口中列实得 ~950px）
- 三面板各自边框圆角浮在页面上（分离卡片语言），与「安静、精确、机构台账感」方向相悖——台账语言是贴合 + hairline
- 会话条目 67px 双行：副题「团队可见 · N 条消息」低价值（M13c 判例应砍型）、32px 图标底色盒、行尾 chevron 三重冗余
- 参与者栏常驻 18%（1440 默认折叠仍留 44px 竖条 + 竖排文字钮 120px min-height）；右 resizer 常驻可见
- resizer 视觉常驻：`::after` opacity .65 的双竖线即用户指认「两个分割线占地方、不好看」

**已裁定方案（2026-11-06 用户确认：A 案 + 隐形拖拽保留 + 44px 图标窄轨）**

- **A 贴合双栏 + 参与者浮层**：grid 改 `minmax(240px, var(--left,280px)) 8px(minmax(0,1fr)`（resizer 保留 8px hit 列）、gap 0，列表面板右缘 1px hairline 分隔、面板去卡片化；参与者彻底退出 grid——detail header 放头像栈入口（前 3 avatar + 溢出计数）+ 点击右侧滑出 overlay sheet（Esc/点外关闭，不挤压消息区）；左 resizer 隐形化（默认透明，hover/focus 显 2px brand 线；拖拽+键盘契约原样保留，ratio 由 % 改 px 语义并 bump preference version）；消息区拿回 ~290px。44px 竖条/右 resizer/「展开参与者」竖排钮根除
- 落选：B（折叠右栏，空间收益少）、C（保守微调，不解决结构问题）

**公共改动（三案通用）**

- 会话条目单行 ~36px：16px 行内图标（UsersRound/LockKeyhole 编码 TEAM/PRIVATE 可见性）、标题 truncate、RelativeTime、未读 pill；删副题/chevron/图标底色盒；`.conversation-item`/`.conversation-unread`/aria-label 拼接（「打开对话 {标题}，{N} 条未读」）锚点不动
- 折叠钮图标化（PanelLeftClose/PanelLeftOpen 系），aria-label 随状态动态（折叠对话列表 ↔ 展开对话列表），e2e 同步改
- ≤1280 参与者底部行、≤900 隐藏规则随方案简化重排；focus mode（R51）独占语义不动；message-list max-width 900px 阅读行长不动；useResizablePane 契约（ratio 持久化/键盘）不动

**连锁清单（A 案口径）**

- e2e：m13g `conversation-right-pane-expanded`/`both-panes-collapsed` 断言改写（浮层语义）、`conversation-left-pane-collapsed` aria-label 跟随；app-shell conversation 快照 ~8 张（darwin/linux × desktop/narrow）按判例重生成；m9b-f03 `rect('.participant-panel')` 几何锚点改浮层内；first-run 描述文本复核
- vitest：ConversationParticipantsPanel.spec（`.pane-collapse`/折叠语义）、ConversationPage.spec（条目断言锚点已兼容）
- 组件：ConversationParticipantsPanel.vue（grid 成员 → overlay）、ConversationPage.vue（grid/header/折叠轨/条目）、detail header 增头像栈入口

## 纪律

- 每批独立提交（英文粗粒度）；vitest + build + check:q01 + CI quality 全套门禁逐批过
- 快照基线按判例流程：失败审查确认为预期变化 → `--update= all` + stat mtime 证实；不做无声重生成
- 词表/文案改动先全仓 rg 旧词断言（display-label-swap 判例）
- 折叠态改动全量 rg e2e 手动点击语义（r02 判例）
