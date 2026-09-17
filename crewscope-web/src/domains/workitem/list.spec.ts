import { exclusionReason, type WorkItemBulkRow } from './bulk'
import {
  bulkTransitionTargets,
  defaultSortDirection,
  readWorkItemSortDirection,
  readWorkItemSortKey,
  sortWorkItems,
  workItemSortKeys,
} from './list'
import { workItemStatuses, type WorkItemAvailableTransition, type WorkItemSummary } from './types'

describe('defaultSortDirection', () => {
  it('opens date and title lists at their natural end and activity lists at the newest', () => {
    expect(defaultSortDirection('title')).toBe('asc')
    expect(defaultSortDirection('dueAt')).toBe('asc')
    expect(defaultSortDirection('updatedAt')).toBe('desc')
    expect(defaultSortDirection('priority')).toBe('desc')
  })
})

describe('sortWorkItems', () => {
  it('orders by the contract’s own priority order, not the alphabetical one', () => {
    // HIGH < LOW 按字母序，但契约顺序是 LOW…URGENT；这里若手抄一份 rank 就会漂移。
    const items = [summary({ id: 'a', priority: 'LOW' }), summary({ id: 'b', priority: 'URGENT' }), summary({ id: 'c', priority: 'HIGH' })]

    expect(sortWorkItems(items, 'priority', 'asc').map(item => item.id)).toEqual(['a', 'c', 'b'])
    expect(sortWorkItems(items, 'priority', 'desc').map(item => item.id)).toEqual(['b', 'c', 'a'])
  })

  it('sinks rows without a due date in both directions', () => {
    const dated = [summary({ id: 'early', dueAt: '2026-09-01T00:00:00Z' }), summary({ id: 'late', dueAt: '2026-09-30T00:00:00Z' })]
    const items = [summary({ id: 'none' }), ...dated]

    // 反序不能把「没人设截止时间」顶成「最紧急」。
    expect(sortWorkItems(items, 'dueAt', 'asc').map(item => item.id)).toEqual(['early', 'late', 'none'])
    expect(sortWorkItems(items, 'dueAt', 'desc').map(item => item.id)).toEqual(['late', 'early', 'none'])
  })

  it('keeps ties in the order the server sent them', () => {
    // 并列时保留服务端的 updated-time 顺序；否则每次重渲染都会重新洗牌。
    const items = [summary({ id: 'first', priority: 'HIGH' }), summary({ id: 'second', priority: 'HIGH' }), summary({ id: 'third', priority: 'HIGH' })]
    expect(sortWorkItems(items, 'priority', 'desc').map(item => item.id)).toEqual(['first', 'second', 'third'])
  })

  it('orders by update time and by title without mutating the input', () => {
    const items = [
      summary({ id: 'a', updatedAt: '2026-09-01T00:00:00Z', title: '乙' }),
      summary({ id: 'b', updatedAt: '2026-09-10T00:00:00Z', title: '甲' }),
    ]

    expect(sortWorkItems(items, 'updatedAt', 'desc').map(item => item.id)).toEqual(['b', 'a'])
    expect(sortWorkItems(items, 'title', 'asc').map(item => item.id)).toEqual(['b', 'a'])
    expect(items.map(item => item.id)).toEqual(['a', 'b'])
  })

  it('survives an unparseable timestamp instead of handing back NaN', () => {
    const items = [summary({ id: 'broken', updatedAt: 'not-a-date' }), summary({ id: 'ok', updatedAt: '2026-09-01T00:00:00Z' })]
    expect(sortWorkItems(items, 'updatedAt', 'asc').map(item => item.id)).toEqual(['broken', 'ok'])
  })
})

describe('reading the sort out of a URL', () => {
  it('falls back rather than trusting what arrived', () => {
    expect(readWorkItemSortKey('priority')).toBe('priority')
    expect(readWorkItemSortKey('assignee')).toBe('updatedAt')
    expect(readWorkItemSortKey(undefined)).toBe('updatedAt')
    expect(readWorkItemSortKey(7)).toBe('updatedAt')

    expect(readWorkItemSortDirection('asc')).toBe('asc')
    expect(readWorkItemSortDirection('descending')).toBe('desc')
    expect(readWorkItemSortDirection(null)).toBe('desc')
  })

  it('publishes exactly the keys the control offers', () => {
    // 工具栏与 URL 读取共用这一份清单；多一个键就会出现「URL 认、按钮没有」的排序。
    expect(workItemSortKeys).toEqual(['updatedAt', 'priority', 'dueAt', 'title'])
  })
})

describe('bulkTransitionTargets', () => {
  it('offers the union of the rows’ executable actions and nothing wider', () => {
    const rows = [
      row({ availableActions: [action({ targetStatus: 'IN_REVIEW', label: '提交评审' })] }),
      row({ availableActions: [action({ targetStatus: 'DONE', label: '完成', enabled: false, reasonMessage: '评审尚未通过' })] }),
    ]

    // DONE 只在被拒绝的行上出现过 —— 它不是可执行动作，因此不成为批量目标。
    expect(bulkTransitionTargets(rows).map(target => target.targetStatus)).toEqual(['IN_REVIEW'])
  })

  it('counts exactly the rows the batch will attempt', () => {
    const rows = [
      row({ id: 'open', availableActions: [action({ targetStatus: 'IN_REVIEW' })] }),
      row({ id: 'refused', availableActions: [action({ targetStatus: 'IN_REVIEW', enabled: false, reasonMessage: '已有评审' })] }),
      // 未表态：既不提供也不拒绝，按 exclusionReason 的口径照发命令，所以它计入 eligible 而不是被丢掉。
      row({ id: 'silent', availableActions: [] }),
    ]

    const [target] = bulkTransitionTargets(rows)
    expect(target).toMatchObject({ targetStatus: 'IN_REVIEW', eligible: 2, total: 3 })

    // 按钮上的数字必须就是批量真正会尝试的行数：这正是预告值的全部意义。
    const attempted = rows.filter(candidate => exclusionReason(candidate, 'IN_REVIEW') === null)
    expect(target!.eligible).toBe(attempted.length)
  })

  it('orders targets by the generated status order whatever order the rows arrived in', () => {
    const forwards = [
      row({ availableActions: [action({ targetStatus: 'READY' }), action({ targetStatus: 'BLOCKED' })] }),
      row({ availableActions: [action({ targetStatus: 'DONE' })] }),
    ]
    const reversed = [...forwards].reverse()

    const expected = [...forwards.flatMap(r => r.availableActions.map(a => a.targetStatus))]
      .sort((left, right) => workItemStatuses.indexOf(left) - workItemStatuses.indexOf(right))
    expect(bulkTransitionTargets(forwards).map(target => target.targetStatus)).toEqual(expected)
    expect(bulkTransitionTargets(reversed).map(target => target.targetStatus)).toEqual(expected)
  })

  it('keeps the label and strength the row published', () => {
    const rows = [row({ availableActions: [action({ targetStatus: 'IN_REVIEW', label: '送审', strength: 'SECONDARY' })] })]
    expect(bulkTransitionTargets(rows)[0]).toMatchObject({ label: '送审', strength: 'SECONDARY' })
  })

  it('offers nothing for an empty selection', () => {
    expect(bulkTransitionTargets([])).toEqual([])
  })
})

function action(overrides: Partial<WorkItemAvailableTransition> = {}): WorkItemAvailableTransition {
  return {
    actionId: 'request-review', targetStatus: 'IN_REVIEW', label: '提交评审', strength: 'PRIMARY',
    reversible: true, enabled: true, reason: null, reasonMessage: null, remedyLabel: null, remedyRoute: null,
    ...overrides,
  }
}

function row(overrides: Partial<WorkItemBulkRow> = {}): WorkItemBulkRow {
  return { id: 'work-1', key: 'DEMO-1', availableActions: [], ...overrides }
}

function summary(overrides: Partial<WorkItemSummary> = {}): WorkItemSummary {
  return {
    id: 'work-1', organizationId: 'org-1', teamId: 'team-1', workspaceId: 'workspace-1', projectId: 'project-1',
    key: 'DEMO-1', title: '拆分工作台', description: null, type: 'TASK', status: 'READY', priority: 'HIGH',
    labels: [], dueAt: null, source: 'MANUAL', sourceReference: null, version: 1,
    createdAt: '2026-08-24T01:00:00Z', createdByPrincipalId: null, updatedAt: '2026-08-24T01:00:00Z',
    updatedByPrincipalId: null, availableActions: [],
    ...overrides,
  }
}
