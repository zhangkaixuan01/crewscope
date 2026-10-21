// @vitest-environment node
import { intakeTrendChart, quotaAdmissionProblem, quotaProblem, tokenCompositionChart, tokenTrendChart } from './presentation'
import type { OrganizationQuotaConfiguration, QuotaUsageView } from './types'

describe('quotaProblem', () => {
  it('names the 422 limit floor with the explicit-low-limit guidance', () => {
    expect(quotaProblem(422, 'invalid_value')).toMatchObject({
      title: '配额值无效',
      message: expect.stringContaining('不小于 1'),
    })
  })

  it('names the 409 optimistic-lock conflict with the re-read guidance', () => {
    expect(quotaProblem(409, 'optimistic_lock_conflict')).toMatchObject({
      title: '内容已变化',
      message: expect.stringContaining('最新'),
    })
  })

  it('names the 403 role fact for a revoked mid-session role', () => {
    expect(quotaProblem(403, 'capability_missing')).toMatchObject({ title: '组织角色不足' })
  })

  it('returns null for unknown shapes so the caller keeps the raw message', () => {
    expect(quotaProblem(503, 'unavailable')).toBeNull()
    expect(quotaProblem(null, null)).toBeNull()
  })
})

describe('quotaAdmissionProblem', () => {
  it('maps both wire constants verbatim to the tailored Chinese guidance', () => {
    const intake = quotaAdmissionProblem('create more agent tasks this month (organization monthly task intake limit reached)')
    expect(intake).toContain('本月组织任务受理数已达上限')
    expect(intake).toContain('组织设置 → 配额与用量')

    const budget = quotaAdmissionProblem('create an agent task reserving additional model tokens this month (organization monthly token budget reached)')
    expect(budget).toContain('Token 预算已用尽')
    expect(budget).toContain('等待下月配额重置')
  })

  it('returns null for unknown or absent actions so the raw message survives', () => {
    expect(quotaAdmissionProblem('a vocabulary the backend has not shipped')).toBeNull()
    expect(quotaAdmissionProblem(null)).toBeNull()
    expect(quotaAdmissionProblem(undefined)).toBeNull()
  })
})

describe('chart builders', () => {
  it('builds the intake trend from the window with the current limit as reference', () => {
    const chart = intakeTrendChart([usage('2026-09', 4), usage('2026-10', 7)], configured(5, null))

    expect(chart.series).toHaveLength(1)
    expect(chart.series[0]).toMatchObject({ key: 'intake', tone: 'info' })
    expect(chart.series[0]!.points).toEqual([{ x: '2026-09', y: 4 }, { x: '2026-10', y: 7 }])
    expect(chart.reference).toEqual({ label: '当前受理上限', value: 5 })
  })

  it('omits the reference line when the dimension is unconfigured', () => {
    const chart = intakeTrendChart([usage('2026-10', 7)], { monthlyTaskIntakeLimit: null, monthlyTokenBudget: null, version: 0 })

    expect(chart.reference).toBeNull()
  })

  it('keeps reserved and actual as two side-by-side series with the budget reference', () => {
    const chart = tokenTrendChart([usage('2026-10', 7)], configured(null, 1_000_000))

    expect(chart.series.map(series => series.key)).toEqual(['reserved', 'actual'])
    expect(chart.series[1]!.points[0]).toMatchObject({ x: '2026-10', y: 175_000 })
    expect(chart.reference).toEqual({ label: '当前 Token 预算', value: 1_000_000 })
  })

  it('builds the composition bars in the label order of the token part vocabulary', () => {
    const chart = tokenCompositionChart(usage('2026-10', 0))

    expect(chart.bars.map(bar => bar.key)).toEqual(['input', 'output', 'cachedInput', 'total'])
    expect(chart.bars.map(bar => bar.value)).toEqual([100_000, 50_000, 25_000, 175_000])
  })
})

function configured(intake: number | null, budget: number | null): OrganizationQuotaConfiguration {
  return { monthlyTaskIntakeLimit: intake, monthlyTokenBudget: budget, version: 2 }
}

function usage(month: string, intakeCount: number): QuotaUsageView {
  return {
    month,
    monthlyTaskIntake: { limit: 5, count: intakeCount },
    monthlyTokenBudget: {
      limit: 1_000_000, reservedTokens: 600_000,
      actualTokens: { input: 100_000, output: 50_000, cachedInput: 25_000, total: 175_000 },
    },
    materialized: true,
  }
}