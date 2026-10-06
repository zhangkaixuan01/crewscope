import { CrewScopeApiClient } from '../../api/client'
import { HttpObservabilityGateway } from './gateway'

const scope = { organizationId: 'org 1', teamId: 'team/1' }

describe('HttpObservabilityGateway', () => {
  it('listCostMonths maps the month page and encodes the scope', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({
      months: [costMonth('2026-09'), costMonth('2026-08')],
      nextAfter: '2026-08',
    }))
    const gateway = new HttpObservabilityGateway(new CrewScopeApiClient('/api/v1', fetcher))

    const page = await gateway.listCostMonths(scope, '2026-09', 12)

    expect(page.months.map(item => item.month)).toEqual(['2026-09', '2026-08'])
    expect(page.nextAfter).toBe('2026-08')
    expect(page.months[0]!.roles.EXECUTION?.inputTokens).toBe(1_200_000)
    expect(page.months[0]!.currencies[0]!.inputCost).toBe('0.528000000000')
    expect(fetcher.mock.calls[0]![0]).toContain('/organizations/org%201/teams/team%2F1/observability/cost/months?limit=12&after=2026-09')
  })

  it('listCostMonths accepts a null cursor as the end of the page', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({ months: [], nextAfter: null }))
    const gateway = new HttpObservabilityGateway(new CrewScopeApiClient('/api/v1', fetcher))

    const page = await gateway.listCostMonths(scope)

    expect(page.months).toEqual([])
    expect(page.nextAfter).toBeNull()
  })

  it('costMonth keeps the plain-string amounts and the price revision pair', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({
      month: '2026-09',
      rows: [
        modelRow({ role: 'CHAT_PRIMARY', costStatus: 'PRICED' }),
        modelRow({ role: 'EMBEDDING', currencyCode: 'XXX', catalogRevision: null, priceRevision: null, costStatus: 'UNPRICED', inputCost: null, outputCost: null, cachedInputCost: null }),
      ],
    }))
    const gateway = new HttpObservabilityGateway(new CrewScopeApiClient('/api/v1', fetcher))

    const detail = await gateway.costMonth(scope, '2026-09')

    expect(detail.rows[0]!.priceRevision).toBe(3)
    expect(detail.rows[0]!.inputCost).toBe('0.528000000000')
    expect(detail.rows[1]!.costStatus).toBe('UNPRICED')
    expect(detail.rows[1]!.inputCost).toBeNull()
    expect(fetcher.mock.calls[0]![0]).toContain('/observability/cost/months/2026-09')
  })

  it('qualityMonth maps both rate cards and their null-when-empty rates', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({
      month: '2026-09',
      executionAttempts: { total: 20, completed: 15, failed: 4, cancelled: 1, successRate: 0.75 },
      reviewFirstPass: { enteredReview: 0, firstPassApproved: 0, firstPassRate: null },
    }))
    const gateway = new HttpObservabilityGateway(new CrewScopeApiClient('/api/v1', fetcher))

    const quality = await gateway.qualityMonth(scope, '2026-09')

    expect(quality.executionAttempts.successRate).toBe(0.75)
    expect(quality.reviewFirstPass.firstPassRate).toBeNull()
  })

  it('rejects scientific-notation amounts, unknown roles, and malformed months', async () => {
    const gateway = new HttpObservabilityGateway(new CrewScopeApiClient('/api/v1', vi.fn<typeof fetch>()))
    const scientific = new HttpObservabilityGateway(new CrewScopeApiClient('/api/v1', vi.fn<typeof fetch>().mockResolvedValue(
      jsonResponse({ month: '2026-09', rows: [modelRow({ inputCost: '5.28E-7' })] }),
    )))
    const badRole = new HttpObservabilityGateway(new CrewScopeApiClient('/api/v1', vi.fn<typeof fetch>().mockResolvedValue(
      jsonResponse({ month: '2026-09', rows: [modelRow({ role: 'ROOT' })] }),
    )))

    await expect(gateway.costMonth(scope, '2026-13')).rejects.toThrow(TypeError)
    await expect(scientific.costMonth(scope, '2026-09')).rejects.toThrow(TypeError)
    await expect(badRole.costMonth(scope, '2026-09')).rejects.toThrow(TypeError)
  })
})

function costMonth(month: string) {
  return {
    month,
    roles: {
      EXECUTION: { inputTokens: 1_200_000, outputTokens: 80_000, cachedTokens: 40_000, factCount: 42 },
    },
    currencies: [{ currency: 'USD', inputCost: '0.528000000000', outputCost: '0.105600000000', cachedInputCost: '0' }],
    unpricedTokens: 300_000,
    totalFactCount: 55,
  }
}

function modelRow(overrides: Record<string, unknown> = {}) {
  return {
    role: 'CHAT_PRIMARY',
    providerKey: 'dashscope',
    modelId: 'deepseek-v3',
    currencyCode: 'USD',
    catalogRevision: 2,
    priceRevision: 3,
    attempt: 1,
    inputTokens: 1_000_000,
    outputTokens: 50_000,
    cachedTokens: 10_000,
    inputCost: '0.528000000000',
    outputCost: '0.066000000000',
    cachedInputCost: '0',
    factCount: 30,
    unreportedFactCount: 2,
    costStatus: 'PRICED',
    ...overrides,
  }
}

function jsonResponse(value: unknown): Response {
  return new Response(JSON.stringify(value), { headers: { 'Content-Type': 'application/json' } })
}
