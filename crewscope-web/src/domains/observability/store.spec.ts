import type { ObservabilityGateway } from './gateway'
import { createObservabilityStore } from './store'
import type {
  ObservabilityCostMonth,
  ObservabilityCostMonthDetail,
  ObservabilityQualityMonth,
  ObservabilityScope,
} from './types'

const platform = { organizationId: 'org-1', teamId: 'platform' }
const security = { organizationId: 'org-1', teamId: 'security' }

describe('ObservabilityStore', () => {
  it('loads the month listing and reports an empty month list as empty, not error', async () => {
    const gateway = fixtureGateway({ listCostMonths: vi.fn(async () => ({ months: [], nextAfter: null })) })
    const store = createObservabilityStore(gateway)
    store.activateScope(platform)

    await store.loadMonths()

    expect(store.state.months.phase).toBe('empty')
    expect(store.state.months.value).toEqual([])
  })

  it('loads one month detail and quality together', async () => {
    const gateway = fixtureGateway()
    const store = createObservabilityStore(gateway)
    store.activateScope(platform)

    await store.loadMonth('2026-09')

    expect(store.state.detail.phase).toBe('ready')
    expect(store.state.detail.month).toBe('2026-09')
    expect(store.state.detail.value?.rows[0]!.modelId).toBe('deepseek-v3')
    expect(store.state.quality.phase).toBe('ready')
    expect(store.state.quality.value?.executionAttempts.total).toBe(20)
    expect(gateway.costMonth).toHaveBeenCalledWith(platform, '2026-09', expect.any(AbortSignal))
    expect(gateway.qualityMonth).toHaveBeenCalledWith(platform, '2026-09', expect.any(AbortSignal))
  })

  it('treats a zero-row detail as empty while quality stays a real answer', async () => {
    const gateway = fixtureGateway({
      costMonth: vi.fn(async () => ({ month: '2026-09', rows: [] })),
    })
    const store = createObservabilityStore(gateway)
    store.activateScope(platform)

    await store.loadMonth('2026-09')

    expect(store.state.detail.phase).toBe('empty')
    expect(store.state.quality.phase).toBe('ready')
  })

  it('keeps one month failing independently of the other read', async () => {
    const gateway = fixtureGateway({
      costMonth: vi.fn(async () => { throw new TypeError('detail lost') }),
    })
    const store = createObservabilityStore(gateway)
    store.activateScope(platform)

    await store.loadMonth('2026-09')

    expect(store.state.detail.phase).toBe('error')
    expect(store.state.detail.errorMessage).toBe('暂时无法加载当月成本明细')
    expect(store.state.quality.phase).toBe('ready')
  })

  it('ignores a malformed month instead of sending it', async () => {
    const gateway = fixtureGateway()
    const store = createObservabilityStore(gateway)
    store.activateScope(platform)

    await store.loadMonth('2026-13')

    expect(gateway.costMonth).not.toHaveBeenCalled()
    expect(store.state.detail.phase).toBe('idle')
  })

  it('merges month pages on load-more and stops at a null cursor', async () => {
    const gateway = fixtureGateway({
      listCostMonths: vi.fn()
        .mockResolvedValueOnce({ months: [costMonth('2026-09')], nextAfter: '2026-09' })
        .mockResolvedValueOnce({ months: [costMonth('2026-08')], nextAfter: null }),
    })
    const store = createObservabilityStore(gateway)
    store.activateScope(platform)

    await store.loadMonths()
    await store.loadMonths(true)

    expect(store.state.months.value?.map(item => item.month)).toEqual(['2026-09', '2026-08'])
    expect(store.state.months.nextAfter).toBeNull()
    await store.loadMonths(true)
    expect(gateway.listCostMonths).toHaveBeenCalledTimes(2)
  })

  it('aborts in-flight month reads and clears state on Scope change', async () => {
    const pending = deferred<ObservabilityCostMonthDetail>()
    const signals: AbortSignal[] = []
    const gateway = fixtureGateway({
      costMonth: vi.fn(async (_scope: ObservabilityScope, _month: string, signal: AbortSignal) => {
        signals.push(signal)
        return pending.promise
      }),
    })
    const store = createObservabilityStore(gateway)
    store.activateScope(platform)
    const load = store.loadMonth('2026-09')
    await Promise.resolve()

    store.activateScope(security)
    pending.resolve({ month: '2026-09', rows: [] })
    await load

    expect(signals[0]!.aborted).toBe(true)
    expect(store.state.detail.phase).toBe('idle')
    expect(store.state.detail.value).toBeNull()
  })
})

function costMonth(month: string): ObservabilityCostMonth {
  return {
    month,
    roles: { EXECUTION: { inputTokens: 1000, outputTokens: 100, cachedTokens: 0, factCount: 3 } },
    currencies: [],
    unpricedTokens: 0,
    totalFactCount: 3,
  }
}

function fixtureGateway(overrides: Partial<ObservabilityGateway> = {}): ObservabilityGateway {
  return {
    listCostMonths: vi.fn(async () => ({ months: [costMonth('2026-09')], nextAfter: null })),
    costMonth: vi.fn(async () => ({
      month: '2026-09',
      rows: [{
        role: 'CHAT_PRIMARY' as const, providerKey: 'dashscope', modelId: 'deepseek-v3',
        currencyCode: 'USD', catalogRevision: 2, priceRevision: 3, attempt: 1,
        inputTokens: 1000, outputTokens: 100, cachedTokens: 0,
        inputCost: '0.44', outputCost: '0.132', cachedInputCost: null,
        factCount: 3, unreportedFactCount: 0, costStatus: 'PRICED' as const,
      }],
    })),
    qualityMonth: vi.fn(async () => qualityMonth()),
    ...overrides,
  }
}

function qualityMonth(): ObservabilityQualityMonth {
  return {
    month: '2026-09',
    executionAttempts: { total: 20, completed: 15, failed: 4, cancelled: 1, successRate: 0.75 },
    reviewFirstPass: { enteredReview: 8, firstPassApproved: 6, firstPassRate: 0.75 },
  }
}

function deferred<T>(): { promise: Promise<T>, resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(resolveDeferred => { resolve = resolveDeferred })
  return { promise, resolve }
}
