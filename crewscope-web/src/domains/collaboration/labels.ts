import type { CollaborationPhase } from './store'

/**
 * User-facing text for the M11 collaboration signal UI. 中文直写——vue-i18n 归 F04,
 * 在那之前所有文案集中在这里,页面组件不散落字符串。
 */

/** The status pill's three visible states; idle/connecting render nothing. */
export const collaborationPhaseLabels: Record<CollaborationPhase, string> = {
  idle: '',
  connecting: '',
  live: '实时协作在线',
  reconnecting: '正在恢复实时协作…',
  degraded: '实时协作不可用',
}

/** §2.2 — the presence strip's accessible summary of who is viewing the scope. */
export const presenceLabels = {
  summary: (names: string[]) => (names.length > 0 ? `在场：${names.join('、')}` : ''),
  unavailable: '实时协作暂不可用',
}

/** §2.3 — typing indicator wording for one, two, and many typers. */
export function typingIndicatorLabel(names: string[]): string {
  if (names.length === 0) return ''
  if (names.length === 1) return `${names[0]} 正在输入…`
  if (names.length === 2) return `${names[0]} 和 ${names[1]} 正在输入…`
  return `${names.length} 人正在输入…`
}

/** §2.4 — the change banner text frozen by ADR-032 (no author in the frame). */
export const resourceChangeLabels = {
  banner: '该工作项已被更新',
  action: '点击刷新',
}
