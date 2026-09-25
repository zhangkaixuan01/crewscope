import { CrewScopeApiClient } from './client'
import { createCommandIntents } from './commandIntent'
import type { CommandReceipt } from '../domains/scope/types'
import { acknowledgeCreation, createAndLocate, creationRecovery, observeCreationStorage, resolveCreation, setCreationIdentity, stopCreationQueries, type RecoveryEntry } from './creationRecovery'

const storage = 'crewscope.command-recovery.v1'
const scope = { organizationId: 'org', teamId: 'team', projectId: 'project' }
const receipt = { commandId: 'command', domainEventId: 'event', correlationId: 'correlation', committedVersion: 0 }
const coordinate = { ...scope, type: 'WORK_ITEM', resourceId: 'exact-id', committedVersion: 0, stage: 'COMMITTED' }
const entry = (): RecoveryEntry => ({ ...scope, key: 'original-key', type: 'WORK_ITEM', createdAt: Date.now() })
const json = (body: unknown, status = 200, headers = {}) => new Response(JSON.stringify(body), { status, headers })
const unavailable = (status = 404) => json({ code: 'not_found', message: 'unknown', correlationId: 'c', retryable: false }, status)
const resultFetch = () => vi.fn<typeof fetch>()
  .mockResolvedValueOnce(json({ receipt, result: coordinate }))
  .mockResolvedValueOnce(json({ workItem: { id: 'exact-id' } }))

beforeEach(() => { setCreationIdentity(null); localStorage.clear(); setCreationIdentity('actor-a') })
afterEach(() => { stopCreationQueries(); vi.useRealTimers(); vi.restoreAllMocks() })

it('resolves a committed POST with a lost response by original header and exact detail, never a list', async () => {
  const fetcher = resultFetch()
  const send = vi.fn(async () => { throw new TypeError('response lost') })
  const result = await createAndLocate(new CrewScopeApiClient('/api/v1', fetcher), scope, 'WORK_ITEM', 'original-key', send)
  expect(result.creation?.resourceId).toBe('exact-id')
  expect(send).toHaveBeenCalledOnce()
  expect(fetcher.mock.calls.map(call => call[0])).toEqual([
    '/api/v1/organizations/org/command-results',
    '/api/v1/organizations/org/teams/team/work-projects/project/work-items/exact-id',
  ])
  expect(new Headers(fetcher.mock.calls[0]?.[1]?.headers).get('Idempotency-Key')).toBe('original-key')
  expect(fetcher.mock.calls.every(call => call[1]?.cache === 'no-store')).toBe(true)
  const saved = Object.keys(localStorage).map(key => localStorage.getItem(key)).join('')
  expect(saved).not.toContain('exact-id')
  expect(saved).not.toContain('response lost')
  acknowledgeCreation('original-key', 'org')
  expect(creationRecovery.entries).toHaveLength(0)
})

it('reloads minimal records and a repeated create call only queries the original operation', async () => {
  localStorage.setItem(`${storage}.entry.org.original-key`, JSON.stringify({ owner: 'actor-a', entry: { ...entry(), title: 'must not restore', body: 'secret' } }))
  observeCreationStorage(new StorageEvent('storage', { key: `${storage}.entry.org.original-key` }))
  expect(creationRecovery.entries[0]).not.toHaveProperty('title')
  const send = vi.fn(async () => receipt)
  await createAndLocate(new CrewScopeApiClient('/api/v1', resultFetch()), scope, 'WORK_ITEM', 'original-key', send)
  expect(send).not.toHaveBeenCalled()
})

it('does not overwrite another tab record or resurrect an acknowledged record', async () => {
  localStorage.setItem(`${storage}.entry.org.other`, JSON.stringify({ owner: 'actor-a', entry: { ...entry(), key: 'other' } }))
  await createAndLocate(new CrewScopeApiClient('/api/v1', resultFetch()), scope, 'WORK_ITEM', 'original-key', async () => receipt)
  acknowledgeCreation('original-key', 'org')
  expect(localStorage.getItem(`${storage}.entry.org.other`)).not.toBeNull()
  expect(localStorage.getItem(`${storage}.entry.org.original-key`)).toBeNull()
  observeCreationStorage(new StorageEvent('storage', { key: `${storage}.entry.org.original-key` }))
  expect(creationRecovery.entries.map(item => item.key)).toEqual(['other'])
})

it('logout in another tab aborts pending reads and blocks a stale tab from creating', async () => {
  const fetcher = vi.fn<typeof fetch>((_url, init) => new Promise((_resolve, reject) => {
    init?.signal?.addEventListener('abort', () => reject(new DOMException('stopped', 'AbortError')))
  }))
  const pending = resolveCreation(entry(), new CrewScopeApiClient('/api/v1', fetcher))
  const rejected = expect(pending).rejects.toMatchObject({ name: 'AbortError' })
  observeCreationStorage(new StorageEvent('storage', { key: storage, newValue: null }))
  await rejected
  const send = vi.fn(async () => receipt)
  await expect(createAndLocate(new CrewScopeApiClient(), scope, 'WORK_ITEM', 'next', send)).rejects.toMatchObject({ status: 401 })
  expect(send).not.toHaveBeenCalled()
})

it('bounds legacy 404 retries and never treats not-found as permission to create again', async () => {
  vi.useFakeTimers()
  const fetcher = vi.fn<typeof fetch>().mockImplementation(async () => unavailable())
  const pending = resolveCreation(entry(), new CrewScopeApiClient('/api/v1', fetcher))
  const rejected = expect(pending).rejects.toMatchObject({ envelope: { code: 'creation_result_pending' } })
  await vi.runAllTimersAsync()
  await rejected
  expect(fetcher.mock.calls.length).toBeLessThanOrEqual(8)
  expect(fetcher.mock.calls.length).toBeGreaterThan(1)
})

it('honors Retry-After and stops on leave without waiting for the next tick', async () => {
  vi.useFakeTimers()
  const fetcher = vi.fn<typeof fetch>().mockResolvedValue(json({}, 429, { 'Retry-After': '10' }))
  const pending = resolveCreation(entry(), new CrewScopeApiClient('/api/v1', fetcher))
  const rejected = expect(pending).rejects.toMatchObject({ name: 'AbortError' })
  await vi.advanceTimersByTimeAsync(9999)
  expect(fetcher).toHaveBeenCalledOnce()
  stopCreationQueries()
  await rejected
})

it('fails closed for different actor/scope results and permissions, with no resource request', async () => {
  for (const response of [json({ receipt, result: { ...coordinate, teamId: 'foreign-team' } }), unavailable(403)]) {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(response)
    await expect(resolveCreation(entry(), new CrewScopeApiClient('/api/v1', fetcher))).rejects.toThrow()
    expect(fetcher).toHaveBeenCalledOnce()
  }
})

it('retains memory and warns when local storage is disabled', async () => {
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('disabled') })
  await createAndLocate(new CrewScopeApiClient('/api/v1', resultFetch()), scope, 'WORK_ITEM', 'memory', async () => receipt)
  expect(creationRecovery.entries.map(item => item.key)).toContain('memory')
  expect(creationRecovery.warning).toContain('仅在内存')
})

it('removes expired records without claiming the original command failed', () => {
  localStorage.setItem(`${storage}.entry.org.old`, JSON.stringify({ owner: 'actor-a', entry: { ...entry(), createdAt: Date.now() - 8 * 86400000 } }))
  observeCreationStorage(new StorageEvent('storage', { key: `${storage}.entry.org.old` }))
  expect(creationRecovery.entries).toHaveLength(0)
  expect(creationRecovery.warning).toContain('不代表原操作未提交')
})

it('refuses the 101st command before dispatch without evicting unresolved operations', async () => {
  for (let i = 0; i < 100; i++) localStorage.setItem(`${storage}.entry.org.pending-${i}`, JSON.stringify({ owner: 'actor-a', entry: { ...entry(), key: `pending-${i}` } }))
  const send = vi.fn(async () => receipt)
  await expect(createAndLocate(new CrewScopeApiClient(), scope, 'WORK_ITEM', 'overflow', send))
    .rejects.toMatchObject({ envelope: { code: 'command_recovery_capacity' } })
  expect(send).not.toHaveBeenCalled()
  expect(creationRecovery.entries).toHaveLength(100)
})

it('aborts a hung result fetch at the shared 30 second deadline', async () => {
  vi.useFakeTimers()
  const fetcher = vi.fn<typeof fetch>((_url, init) => new Promise((_resolve, reject) => {
    init?.signal?.addEventListener('abort', () => reject(new DOMException('deadline', 'AbortError')))
  }))
  const pending = resolveCreation(entry(), new CrewScopeApiClient('/api/v1', fetcher))
  const rejected = expect(pending).rejects.toMatchObject({ envelope: { code: 'creation_result_pending' } })
  await vi.advanceTimersByTimeAsync(30_000)
  await rejected
  expect(fetcher).toHaveBeenCalledOnce()
})

it('clears only recovery data on account switch and never exposes the previous actor records', async () => {
  localStorage.setItem('unrelated.preference', 'keep')
  await createAndLocate(new CrewScopeApiClient('/api/v1', resultFetch()), scope, 'WORK_ITEM', 'original-key', async () => receipt)
  setCreationIdentity('actor-b')
  expect(creationRecovery.entries).toHaveLength(0)
  expect(localStorage.getItem(`${storage}.entry.org.original-key`)).toBeNull()
  expect(localStorage.getItem('unrelated.preference')).toBe('keep')
})

it('rejects a resource response whose ID differs from the persisted coordinate', async () => {
  const fetcher = vi.fn<typeof fetch>()
    .mockResolvedValueOnce(json({ receipt, result: coordinate }))
    .mockResolvedValueOnce(json({ workItem: { id: 'some-other-id' } }))
  await expect(resolveCreation(entry(), new CrewScopeApiClient('/api/v1', fetcher))).rejects.toThrow('does not match')
})

it('does not forget a committed intent when a subsequent result read is forbidden', async () => {
  const fetcher = vi.fn<typeof fetch>().mockImplementation(async () => unavailable(403))
  const client = new CrewScopeApiClient('/api/v1', fetcher)
  const intents = createCommandIntents<{ title: string }, CommandReceipt>()
  const send = vi.fn(async () => receipt)
  const keys: string[] = []
  const create = () => intents.execute(scope, { title: 'same' }, (_input, key) => {
    keys.push(key)
    return createAndLocate(client, scope, 'WORK_ITEM', key, send)
  })
  await expect(create()).rejects.toMatchObject({ status: 0 })
  await expect(create()).rejects.toMatchObject({ status: 0 })
  expect(keys[1]).toBe(keys[0])
  expect(send).toHaveBeenCalledOnce()
})

it('reports committed-but-not-visible separately and shares the request budget with detail reads', async () => {
  vi.useFakeTimers()
  const fetcher = vi.fn<typeof fetch>()
    .mockResolvedValueOnce(json({ receipt, result: coordinate }))
    .mockImplementation(async () => unavailable())
  const pending = resolveCreation(entry(), new CrewScopeApiClient('/api/v1', fetcher))
  const rejected = expect(pending).rejects.toMatchObject({ envelope: { code: 'creation_projection_pending', message: expect.stringContaining('已创建') } })
  await vi.runAllTimersAsync()
  await rejected
  expect(fetcher.mock.calls.filter(call => String(call[0]).endsWith('command-results'))).toHaveLength(1)
  expect(fetcher.mock.calls.length).toBeLessThanOrEqual(8)
})
