import {
  bulkSummaryMessage,
  exclusionReason,
  summarizeBulkResults,
  type WorkItemBulkRow,
  type WorkItemBulkRowResult,
} from './bulk'
import type { WorkItemAvailableTransition } from './types'

describe('exclusionReason', () => {
  /**
   * Silence is not a refusal.
   *
   * A response that predates `availableActions`, or one that simply did not publish this edge, says
   * nothing about it. Reading that as "no" would let a degraded list exclude rows the member can
   * actually move, and the command endpoint — the only authority — never gets asked.
   */
  it('treats a row that says nothing about the target as actionable', () => {
    expect(exclusionReason(row([]), 'IN_REVIEW')).toBeNull()
    expect(exclusionReason(row([action({ targetStatus: 'DONE', enabled: true })]), 'IN_REVIEW')).toBeNull()
  })

  it('carries the server’s own words for a refused action', () => {
    const refused = action({ targetStatus: 'DONE', enabled: false, reasonMessage: '评审尚未通过' })
    expect(exclusionReason(row([refused]), 'DONE')).toBe('评审尚未通过')
  })

  it('still explains a refusal the server left unworded', () => {
    // A reason with no message is a server omission, not a licence to drop the row silently.
    const refused = action({ targetStatus: 'DONE', enabled: false, reasonMessage: null })
    expect(exclusionReason(row([refused]), 'DONE')).toBe('该工作项当前不能执行这个动作')
  })

  it('ignores a disabled entry for a different target', () => {
    const rows = row([action({ targetStatus: 'BLOCKED', enabled: false, reasonMessage: '依赖未完成' })])
    expect(exclusionReason(rows, 'IN_REVIEW')).toBeNull()
    expect(exclusionReason(rows, 'BLOCKED')).toBe('依赖未完成')
  })
})

describe('summarizeBulkResults', () => {
  it('puts every row in exactly one bucket', () => {
    const summary = summarizeBulkResults([
      result('a', 'executed'), result('b', 'executed'), result('c', 'excluded'),
      result('d', 'refused'), result('e', 'failed'),
    ])

    expect(summary).toEqual({ total: 5, executed: 2, excluded: 1, refused: 1, failed: 1, partial: true })
    expect(summary.executed + summary.excluded + summary.refused + summary.failed).toBe(summary.total)
  })

  it('is not partial only when every row executed', () => {
    expect(summarizeBulkResults([result('a', 'executed')]).partial).toBe(false)
    expect(summarizeBulkResults([result('a', 'excluded')]).partial).toBe(true)
    // 空批量既不是完成也不是部分完成；文案由 bulkSummaryMessage 单独承担。
    expect(summarizeBulkResults([]).partial).toBe(false)
  })
})

describe('bulkSummaryMessage', () => {
  it('never reports a partial batch as done', () => {
    const summary = summarizeBulkResults([result('a', 'executed'), result('b', 'refused')])
    expect(bulkSummaryMessage(summary, '提交评审'))
      .toBe('2 项中 1 项已执行「提交评审」，其余 1 项见逐项结果')
  })

  it('reports a full batch as its own count', () => {
    const summary = summarizeBulkResults([result('a', 'executed'), result('b', 'executed')])
    expect(bulkSummaryMessage(summary, '提交评审')).toBe('已对 2 项执行「提交评审」')
  })

  it('says there was nothing to do rather than counting zero', () => {
    expect(bulkSummaryMessage(summarizeBulkResults([]), '提交评审')).toBe('没有可执行「提交评审」的工作项')
  })
})

function row(availableActions: readonly WorkItemAvailableTransition[]): WorkItemBulkRow {
  return { id: 'work-1', key: 'DEMO-1', availableActions }
}

function action(overrides: Partial<WorkItemAvailableTransition> = {}): WorkItemAvailableTransition {
  return {
    actionId: 'request-review', targetStatus: 'IN_REVIEW', label: '提交评审', strength: 'PRIMARY',
    reversible: true, enabled: true, reason: null, reasonMessage: null, remedyLabel: null, remedyRoute: null,
    ...overrides,
  }
}

function result(id: string, outcome: WorkItemBulkRowResult['outcome']): WorkItemBulkRowResult {
  return { id, key: id.toUpperCase(), title: `标题 ${id}`, outcome, message: null }
}
