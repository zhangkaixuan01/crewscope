import { exclusionReason, type WorkItemBulkRow } from './bulk'
import {
  workItemStatuses,
  type WorkItemStatus,
  type WorkItemTransitionStrength,
} from './types'

/**
 * The list capabilities of the WorkItem collection: which server-owned orderings its list offers
 * and which batch actions its selection offers.
 *
 * The batch targets are read out of the availability verdict the server published with the row —
 * this file never decides that an action exists or that a member may take it. The orderings are
 * equally borrowed: the server owns every sort, its tie-breaker and the cursor that continues it,
 * so what the browser picks is a name, not an algorithm.
 */

/** The sort names the server's list contract accepts (M9b-A06 wire spellings). */
export type WorkItemSortKey = 'updatedAt' | 'priority' | 'dueAt' | 'createdAt'

export interface WorkItemSortOption {
  readonly key: WorkItemSortKey
  readonly label: string
}

export const workItemSortOptions: readonly WorkItemSortOption[] = [
  { key: 'updatedAt', label: '更新时间' },
  { key: 'priority', label: '优先级' },
  { key: 'dueAt', label: '截止时间' },
  { key: 'createdAt', label: '创建时间' },
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

/** How each ordering runs, said in the words a member reads next to the control. */
export const workItemSortDirectionLabels: Readonly<Record<WorkItemSortKey, string>> = {
  updatedAt: '新→旧',
  priority: '高→低',
  dueAt: '近→远',
  createdAt: '新→旧',
}

/**
 * The same directions as the server contract states them, for the control's own arrow icon. Kept
 * beside the labels so the two cannot drift apart.
 */
export const workItemSortDirections: Readonly<Record<WorkItemSortKey, 'asc' | 'desc'>> = {
  updatedAt: 'desc',
  priority: 'desc',
  dueAt: 'asc',
  createdAt: 'desc',
}

/**
 * Reads a sort key out of a URL query, falling back rather than trusting what arrived.
 *
 * A link from before the server sorts existed may still carry `sort=title` or a `direction`; the
 * key falls back to the default ordering and the direction is simply not read anymore — the
 * direction of every ordering is the server's own, fixed in its contract.
 */
export function readWorkItemSortKey(value: unknown, fallback: WorkItemSortKey = 'updatedAt'): WorkItemSortKey {
  return typeof value === 'string' && (workItemSortKeys as readonly string[]).includes(value)
    ? value as WorkItemSortKey
    : fallback
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
