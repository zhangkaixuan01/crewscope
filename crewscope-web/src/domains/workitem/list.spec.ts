import { exclusionReason, type WorkItemBulkRow } from './bulk'
import {
  bulkTransitionTargets,
  readWorkItemSortKey,
  workItemSortDirectionLabels,
  workItemSortDirections,
  workItemSortKeys,
} from './list'
import { workItemStatuses, type WorkItemAvailableTransition } from './types'

describe('the server-owned orderings', () => {
  it('publishes exactly the sort names the server list contract accepts', () => {
    // 工具栏与 URL 读取共用这一份清单；多一个键就会出现「URL 认、按钮没有」或服务端 400 的排序。
    expect(workItemSortKeys).toEqual(['updatedAt', 'priority', 'dueAt', 'createdAt'])
  })

  it('states each ordering’s fixed direction the way the server contract runs it', () => {
    // 方向是服务端合同的一部分，不是浏览器的选择：截止时间从近到远，其余从新/高开始。
    expect(workItemSortDirections).toEqual({
      updatedAt: 'desc', priority: 'desc', dueAt: 'asc', createdAt: 'desc',
    })
    // 每个键都要有给人读的方向文案，且与方向表说同一件事。
    expect(Object.keys(workItemSortDirectionLabels).sort()).toEqual([...workItemSortKeys].sort())
  })
})

describe('reading the sort out of a URL', () => {
  it('falls back rather than trusting what arrived', () => {
    expect(readWorkItemSortKey('priority')).toBe('priority')
    expect(readWorkItemSortKey('createdAt')).toBe('createdAt')
    expect(readWorkItemSortKey('assignee')).toBe('updatedAt')
    expect(readWorkItemSortKey(undefined)).toBe('updatedAt')
    expect(readWorkItemSortKey(7)).toBe('updatedAt')
  })

  it('no longer recognizes the pre-server-sort keys, including title', () => {
    // 服务端不认 title；旧链接带着它进来时落回默认排序，而不是把一个会被拒绝的名字发给服务端。
    expect(readWorkItemSortKey('title')).toBe('updatedAt')
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
