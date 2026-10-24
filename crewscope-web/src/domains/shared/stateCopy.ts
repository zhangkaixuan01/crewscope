/**
 * M13c（S3）：跨域逐字同句的复句文案单源。labels.ts 只管枚举词表（「deliberately does
 * not own any wording」），这里是复句的家；收编门槛是「两个以上域逐字同句」，单域同句
 * 去各自域的 labels.ts。改动这里的句子等于同时改所有使用点的同屏语义——先数使用点。
 */

/** 403 面板说明句——六个域的 forbidden 面板逐字同句（M13c 前 Review §4.3）。 */
export const teamMemberUnresolvedDescription = '服务端没有为当前身份解析出这个 Team 的活动成员。'

/** 蒸馏幂等重放回执句——两蒸馏对话框逐字同句（M13c 前 Review §4.3）。 */
export const distillReplayedNote = '此蒸馏命令此前已确认过，本次为幂等重放，没有重复创建内容。'
