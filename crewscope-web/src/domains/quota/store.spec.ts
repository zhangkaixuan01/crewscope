import { CrewScopeApiError } from '../../api/client'
import type { QuotaGateway } from './gateway'
import type { QuotaUsageView } from './types'
import { createQuotaStore } from './store'

const organizationId = '00000000-0000-0000-0000-000000000001'
const ANCHOR = '2026-10'

describe('QuotaStore', () => {
  it('loads the configuration once and keeps the value for repeat loads', async () => {
    const gateway = fakeGateway()
    const store = createQuotaStore(gateway)

    await store.loadConfiguration(organizationId)
    await store.loadConfiguration(organizationId)

    expect(store.state.configuration).toMatchObject({
      phase: 'ready',
      value: { monthlyTaskIntakeLimit: null, monthlyTokenBudget: null, version: 0 },
    })
    expect(gateway.quota).toHaveBeenCalledOnce()
  })

  it('maps a 403 on the configuration to the forbidden phase instead of a retryable error', async () => {
    const gateway = fakeGateway()
    gateway.quota.mockRejectedValueOnce(apiError(403, 'capability_missing'))
    const store = createQuotaStore(gateway)

    await store.loadConfiguration(organizationId)

    expect(store.state.configuration.phase).toBe('forbidden')
    expect(store.state.configuration.value).toBeNull()
  })

  it('treats the 202 receipt as acceptance and immediately re-reads the configuration', async () => {
    const gateway = fakeGateway()
    gateway.quota
      .mockResolvedValueOnce({ monthlyTaskIntakeLimit: null, monthlyTokenBudget: null, version: 0 })
      .mockResolvedValueOnce({ monthlyTaskIntakeLimit: 5, monthlyTokenBudget: 1_000_000, version: 1 })
    const store = createQuotaStore(gateway)
    await store.loadConfiguration(organizationId)

    const ok = await store.updateQuota(
      organizationId,
      { monthlyTaskIntakeLimit: 5, monthlyTokenBudget: 1_000_000 },
      0,
      'attempt-1',
    )

    expect(ok).toBe(true)
    expect(store.state.command.phase).toBe('success')
    expect(store.state.command.receipt).toMatchObject({ commandId: 'command-1', replayed: false })
    expect(store.state.configuration.value).toMatchObject({ monthlyTaskIntakeLimit: 5, version: 1 })
    expect(gateway.quota).toHaveBeenCalledTimes(2)
  })

  it('records the 409 conflict coordinates and re-reads so the next edit starts from persisted state', async () => {
    const gateway = fakeGateway()
    gateway.quota
      .mockResolvedValueOnce({ monthlyTaskIntakeLimit: null, monthlyTokenBudget: null, version: 0 })
      .mockResolvedValueOnce({ monthlyTaskIntakeLimit: 6, monthlyTokenBudget: null, version: 3 })
    gateway.updateQuota.mockRejectedValueOnce(apiError(409, 'optimistic_lock_conflict'))
    const store = createQuotaStore(gateway)
    await store.loadConfiguration(organizationId)

    const ok = await store.updateQuota(organizationId, { monthlyTaskIntakeLimit: 5, monthlyTokenBudget: null }, 0, 'attempt-1')

    expect(ok).toBe(false)
    expect(store.state.command).toMatchObject({ phase: 'error', errorStatus: 409, errorCode: 'optimistic_lock_conflict' })
    expect(store.state.configuration.value).toMatchObject({ version: 3 })
    expect(gateway.quota).toHaveBeenCalledTimes(2)
  })

  it('keeps the 422 invalid_value coordinates for the page copy', async () => {
    const gateway = fakeGateway()
    gateway.updateQuota.mockRejectedValueOnce(apiError(422, 'invalid_value'))
    const store = createQuotaStore(gateway)

    const ok = await store.updateQuota(organizationId, { monthlyTaskIntakeLimit: 0, monthlyTokenBudget: null }, 0, 'attempt-1')

    expect(ok).toBe(false)
    expect(store.state.command).toMatchObject({ phase: 'error', errorStatus: 422, errorCode: 'invalid_value' })
    // A 422 is not a version fact: no re-read was forced.
    expect(gateway.quota).not.toHaveBeenCalled()
  })

  it('passes the idempotency key through to the gateway (command-gateway whitelist slot 3)', async () => {
    const gateway = fakeGateway()
    const store = createQuotaStore(gateway)

    await store.updateQuota(organizationId, { monthlyTaskIntakeLimit: null, monthlyTokenBudget: null }, 0, 'attempt-1')

    expect(gateway.updateQuota).toHaveBeenCalledWith(
      organizationId,
      { monthlyTaskIntakeLimit: null, monthlyTokenBudget: null },
      0,
      'attempt-1',
    )
  })

  it('anchors the usage window on the parameterless call and fans the five previous months out', async () => {
    const gateway = fakeGateway()
    const store = createQuotaStore(gateway)

    await store.loadUsageWindow(organizationId)

    expect(store.state.usage.phase).toBe('ready')
    expect(store.state.usage.anchorMonth).toBe(ANCHOR)
    expect(store.state.usage.windowMonths).toEqual(['2026-05', '2026-06', '2026-07', '2026-08', '2026-09', ANCHOR])
    expect(gateway.usage).toHaveBeenCalledWith(organizationId, null)
    for (const month of ['2026-05', '2026-06', '2026-07', '2026-08', '2026-09']) {
      expect(gateway.usage).toHaveBeenCalledWith(organizationId, month, expect.any(AbortSignal))
    }
    for (const month of store.state.usage.windowMonths) {
      expect(store.state.usage.months[month]).toMatchObject({ phase: 'ready' })
    }
  })

  it('maps a 403 on the anchor usage call to the forbidden phase', async () => {
    const gateway = fakeGateway()
    gateway.usage.mockRejectedValueOnce(apiError(403, 'capability_missing'))
    const store = createQuotaStore(gateway)

    await store.loadUsageWindow(organizationId)

    expect(store.state.usage.phase).toBe('forbidden')
    expect(store.state.usage.anchorMonth).toBeNull()
  })

  it('keeps one failed window month as that month\'s error without failing the window', async () => {
    const gateway = fakeGateway()
    gateway.usage.mockImplementation(async (_id, month) => {
      if (month === '2026-07') throw apiError(503, 'unavailable')
      return usage(month ?? ANCHOR)
    })
    const store = createQuotaStore(gateway)

    await store.loadUsageWindow(organizationId)

    expect(store.state.usage.phase).toBe('ready')
    expect(store.state.usage.months['2026-07']).toMatchObject({ phase: 'error', errorMessage: '暂时不可用' })
    expect(store.state.usage.months['2026-08']).toMatchObject({ phase: 'ready' })
  })

  it('loads a navigated month on demand and skips already-ready months', async () => {
    const gateway = fakeGateway()
    const store = createQuotaStore(gateway)
    await store.loadUsageWindow(organizationId)
    const callsAfterWindow = gateway.usage.mock.calls.length

    await store.loadUsageMonth(organizationId, '2026-09')
    expect(gateway.usage).toHaveBeenCalledTimes(callsAfterWindow)

    await store.loadUsageMonth(organizationId, '2026-03')
    expect(gateway.usage).toHaveBeenCalledWith(organizationId, '2026-03', expect.any(AbortSignal))
    expect(store.state.usage.months['2026-03']).toMatchObject({ phase: 'ready', value: { month: '2026-03' } })
  })

  it('never dispatches a future or malformed month (the contract answers 400)', async () => {
    const gateway = fakeGateway()
    const store = createQuotaStore(gateway)
    await store.loadUsageWindow(organizationId)
    const callsAfterWindow = gateway.usage.mock.calls.length

    await store.loadUsageMonth(organizationId, '2026-11')
    await store.loadUsageMonth(organizationId, 'not-a-month')

    expect(gateway.usage).toHaveBeenCalledTimes(callsAfterWindow)
  })

  it('holds on-demand months until the anchor names the reporting ceiling', async () => {
    const gateway = fakeGateway()
    const store = createQuotaStore(gateway)

    // The deep-link race: the page asks before the parameterless anchor call resolves.
    await store.loadUsageMonth(organizationId, '2026-03')
    expect(gateway.usage).not.toHaveBeenCalled()
  })

  it('reset aborts in-flight loads and returns every resource to idle', async () => {
    const gateway = fakeGateway()
    let resolveQuota!: (value: { monthlyTaskIntakeLimit: number | null, monthlyTokenBudget: number | null, version: number }) => void
    gateway.quota.mockImplementationOnce(() => new Promise(resolve => { resolveQuota = resolve }))
    const store = createQuotaStore(gateway)
    const loading = store.loadConfiguration(organizationId)
    expect(store.state.configuration.phase).toBe('loading')

    store.reset()
    resolveQuota({ monthlyTaskIntakeLimit: 5, monthlyTokenBudget: null, version: 1 })
    await loading

    expect(store.state.configuration.phase).toBe('idle')
    expect(store.state.configuration.value).toBeNull()
    expect(store.state.command.phase).toBe('idle')
    expect(store.state.usage).toMatchObject({ phase: 'bootstrap', anchorMonth: null, windowMonths: [] })
  })
})

function fakeGateway(): QuotaGateway & {
  quota: ReturnType<typeof vi.fn>
  usage: ReturnType<typeof vi.fn>
  updateQuota: ReturnType<typeof vi.fn>
} {
  return {
    quota: vi.fn(async () => ({ monthlyTaskIntakeLimit: null, monthlyTokenBudget: null, version: 0 })),
    usage: vi.fn(async (_organizationId: string, month: string | null) => usage(month ?? ANCHOR)),
    updateQuota: vi.fn(async () => ({
      commandId: 'command-1', domainEventId: 'event-1', committedVersion: 1,
      correlationId: 'c-1', replayed: false,
    })),
  }
}

function usage(month: string): QuotaUsageView {
  return {
    month,
    monthlyTaskIntake: { limit: null, count: 0 },
    monthlyTokenBudget: {
      limit: null, reservedTokens: 0,
      actualTokens: { input: 0, output: 0, cachedInput: 0, total: 0 },
    },
    materialized: false,
  }
}

function apiError(status: number, code: string): CrewScopeApiError {
  return new CrewScopeApiError(status, {
    code, message: '暂时不可用', correlationId: 'c-1', retryable: false, currentVersion: null, details: {},
  })
}
