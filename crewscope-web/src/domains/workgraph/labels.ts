import type { RelationView, WorkItemRelationType } from './types'

/**
 * User-facing labels for the WorkGraph relation vocabulary (M11 contract §2–§3).
 *
 * `Record<Enum, string>` on purpose: a new relation type from the backend widens the union and
 * fails this file's compile, which is the guard between a raw `SPLIT_FROM` and the screen.
 */
export const workItemRelationTypeLabels: Record<WorkItemRelationType, string> = {
  BLOCKS: '阻塞',
  DEPENDS_ON: '依赖',
  SPLIT_FROM: '拆分自',
}

/**
 * Error copy keyed by the server's error `code` (contract §2.4). Known codes get a sentence that
 * tells the member what to do next; anything else falls back to the server's own message.
 */
export const workItemRelationErrorLabels: Record<string, string> = {
  work_item_relation_duplicate: '两个工作项之间已存在同方向的阻塞关系。',
  work_item_relation_cycle: '这条关系会形成循环阻塞，无法创建。',
  work_item_relation_split_source_conflict: '此工作项已有拆分来源，一项只能拆分自一个来源。',
  invalid_value: '关系两端不合法：不能指向自身、归档项或不存在的项目。',
  policy_denied: '当前成员没有在关系两端项目中建立阻塞关系的权限。',
  aggregate_not_found: '目标工作项不存在或已被移除。',
}

/** The one-line description of a workspace-level blocked graph (used by the graph header). */
export function blockedGraphSummaryLabel(nodeCount: number, edgeCount: number): string {
  return `${nodeCount} 个工作项 · ${edgeCount} 条阻塞关系`
}

/**
 * The three relation sentences (F02 panel contract). The panel never submits DEPENDS_ON — it says
 * what it means in BLOCKS terms — but a stored edge may present a DEPENDS_ON view, which renders
 * with the equivalent note instead of a fourth sentence shape.
 */
export function relationSentence(view: RelationView, selfId: string, otherTitle: string): string {
  if (view.type === 'SPLIT_FROM') {
    // SPLIT_FROM is stored from the split item to its source.
    return view.fromWorkItemId === selfId ? `本项拆分自「${otherTitle}」` : `「${otherTitle}」拆分自本项`
  }
  // BLOCKS view: from blocks to. DEPENDS_ON view: from depends on to, i.e. to blocks from.
  const blockerIsSelf = view.type === 'BLOCKS'
    ? view.fromWorkItemId === selfId
    : view.toWorkItemId === selfId
  return blockerIsSelf ? `本项阻塞「${otherTitle}」` : `「${otherTitle}」阻塞本项`
}

/**
 * The add-form option copy — exactly the three submittable sentences (F02 panel contract).
 * The panel offers no fourth option: DEPENDS_ON is never submitted, and SPLIT_FROM is only ever
 * created from this item toward its source.
 */
export const relationOptionLabels = {
  incomingBlocker: '所选工作项阻塞本项',
  outgoingBlocker: '本项阻塞所选工作项',
  splitFromSource: '本项拆分自所选工作项',
} as const
