import type { SortDirection } from '../../composables/useListSort'
import { exclusionReason, type WorkItemBulkRow } from './bulk'
import {
  workItemPriorities,
  workItemStatuses,
  type WorkItemPriority,
  type WorkItemStatus,
  type WorkItemSummary,
  type WorkItemTransitionStrength,
} from './types'

/**
 * The list capabilities of the WorkItem collection: how its rows are ordered and which batch
 * actions its selection offers.
 *
 * Both are derived from the row itself. The order is computed from fields the row carries, and the
 * batch targets are read out of the availability verdict the server published with the row — this
 * file never decides that an action exists or that a member may take it.
 */

export type WorkItemSortKey = 'updatedAt' | 'priority' | 'dueAt' | 'title'

export interface WorkItemSortOption {
  readonly key: WorkItemSortKey
  readonly label: string
}

export const workItemSortOptions: readonly WorkItemSortOption[] = [
  { key: 'updatedAt', label: '更新时间' },
  { key: 'priority', label: '优先级' },
  { key: 'dueAt', label: '截止时间' },
  { key: 'title', label: '标题' },
]

export const workItemSortKeys: readonly WorkItemSortKey[] = workItemSortOptions.map(option => option.key)

/**
 * The field the list deliberately does not offer, with the reason it does not.
 *
 * 负责人 is the one thing a member would reasonably want to order this list by and cannot:
 * `WorkItemSummary` carries no responsibility facts, so the field is not on the row to order. It is
 * named rather than omitted so the control says why instead of leaving the member to wonder whether
 * the feature exists.
 */
export const unsortableWorkItemField = {
  label: '负责人',
  hint: '工作项列表响应不携带责任链，无法按负责人排序；负责人可在工作项详情中查看',
} as const

/** The direction a key is worth switching to when it is chosen, before any toggling. */
export function defaultSortDirection(key: WorkItemSortKey): SortDirection {
  return key === 'title' || key === 'dueAt' ? 'asc' : 'desc'
}

/**
 * Orders the rows this page has loaded.
 *
 * The server lists one WorkProject by its updated-time/ID keyset and takes no sort parameter, so
 * this orders the loaded set and no more: a member who sorts by priority sees the rows in hand in
 * priority order, not the project's. Every surface that shows the control therefore says which set
 * it is ordering — the counts in the toolbar are that statement, not decoration.
 *
 * Ties keep the order they arrived in. `Array.prototype.sort` is stable, so rows with the same
 * priority stay in the server's updated-time order instead of shuffling on every re-render.
 */
export function sortWorkItems(
  items: readonly WorkItemSummary[],
  key: WorkItemSortKey,
  direction: SortDirection,
): WorkItemSummary[] {
  return [...items].sort((left, right) => compareWorkItems(left, right, key, direction))
}

function compareWorkItems(left: WorkItemSummary, right: WorkItemSummary, key: WorkItemSortKey, direction: SortDirection): number {
  // 截止时间是唯一一个「缺失值必须固定在一端」的键，它自己处理方向；其余键整体反序即可。
  if (key === 'dueAt') return compareDueAt(left.dueAt, right.dueAt, direction)

  const factor = direction === 'asc' ? 1 : -1
  switch (key) {
    case 'updatedAt':
      return factor * (timestamp(left.updatedAt) - timestamp(right.updatedAt))
    case 'priority':
      // Read off the contract's own order (LOW…URGENT) rather than a rank table kept here, which
      // would be one more place for the enum to drift out of step with the backend.
      return factor * (workItemPriorities.indexOf(left.priority as WorkItemPriority)
        - workItemPriorities.indexOf(right.priority as WorkItemPriority))
    case 'title':
      return factor * left.title.localeCompare(right.title, 'zh-CN')
    default:
      return 0
  }
}

/**
 * Rows without a due date sink to the bottom in both directions.
 *
 * Reversing the whole comparison would put every undated row above the whole dated list on `desc`,
 * which reads as "these are the most urgent" when it means "nobody set a date". The missing-date
 * case is therefore decided before the direction is applied, and only the dated rows reverse.
 */
function compareDueAt(left: string | null, right: string | null, direction: SortDirection): number {
  if (!left && !right) return 0
  if (!left) return 1
  if (!right) return -1
  const delta = timestamp(left) - timestamp(right)
  return direction === 'asc' ? delta : -delta
}

function timestamp(value: string): number {
  const parsed = Date.parse(value)
  return Number.isFinite(parsed) ? parsed : 0
}

/** Reads a sort key out of a URL query, falling back rather than trusting what arrived. */
export function readWorkItemSortKey(value: unknown, fallback: WorkItemSortKey = 'updatedAt'): WorkItemSortKey {
  return typeof value === 'string' && (workItemSortKeys as readonly string[]).includes(value)
    ? value as WorkItemSortKey
    : fallback
}

export function readWorkItemSortDirection(value: unknown, fallback: SortDirection = 'desc'): SortDirection {
  return value === 'asc' || value === 'desc' ? value : fallback
}

export interface WorkItemBulkTarget {
  readonly targetStatus: WorkItemStatus
  /** The server's own label for the edge, exactly as the row published it. */
  readonly label: string
  readonly strength: WorkItemTransitionStrength
  /** How many of the selected rows can actually take it, by the rows' own published verdicts. */
  readonly eligible: number
  readonly total: number
}

/**
 * The targets a selection can move to, with the count each would apply to.
 *
 * A target appears only when some selected row offers it as an executable action — the union of what
 * was published, never a wider set derived from the state machine. The count is on the button
 * because the alternative is a member pressing "提交评审" on twelve rows and finding out afterwards
 * that three of them were not eligible: the ones that cannot are excluded here, in the open.
 *
 * Ordering follows the generated status order so the same selection always offers its targets in
 * the same sequence, whatever order the rows arrived in.
 */
export function bulkTransitionTargets(rows: readonly WorkItemBulkRow[]): WorkItemBulkTarget[] {
  const targets = new Map<WorkItemStatus, { label: string; strength: WorkItemTransitionStrength }>()
  for (const row of rows) {
    for (const action of row.availableActions) {
      if (!action.enabled || targets.has(action.targetStatus)) continue
      targets.set(action.targetStatus, { label: action.label, strength: action.strength })
    }
  }
  return [...targets.entries()]
    .map(([targetStatus, action]) => ({
      targetStatus,
      label: action.label,
      strength: action.strength,
      total: rows.length,
      eligible: rows.filter(row => exclusionReason(row, targetStatus) === null).length,
    }))
    .sort((left, right) => statusOrder(left.targetStatus) - statusOrder(right.targetStatus))
}

function statusOrder(status: WorkItemStatus): number {
  return workItemStatuses.indexOf(status)
}
