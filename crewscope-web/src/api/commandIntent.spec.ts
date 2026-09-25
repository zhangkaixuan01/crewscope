import { CrewScopeApiError } from './client'
import { canonicalCommandInput, commandFailure, createCommandIntents } from './commandIntent'

const scope = { account: 'alice', organization: 'org', team: 'team', target: 'project', commandType: 'CREATE' }
const error = (status: number) => new CrewScopeApiError(status, {
  code: 'test', message: 'test', correlationId: 'test', retryable: true, currentVersion: null, details: {},
})

describe('opt-in command intents', () => {
  it.each([0, 408, 500, 502, 503])('treats HTTP %i as unknown, never definitely rejected', status => {
    expect(commandFailure(error(status))).toBe('unknown')
  })
  it('classifies Abort and malformed success as unknown, conflicts and rejection separately', () => {
    expect(commandFailure(new DOMException('aborted', 'AbortError'))).toBe('unknown')
    expect(commandFailure(new SyntaxError('response'))).toBe('unknown')
    expect(commandFailure(error(409))).toBe('conflict')
    expect(commandFailure(error(412))).toBe('conflict')
    expect(commandFailure(error(403))).toBe('rejected')
  })
  it('canonicalizes JSON object order and omitted optional fields without reordering arrays', () => {
    expect(canonicalCommandInput({ b: [2, 1], a: { y: null, x: 1 }, optional: undefined }))
      .toBe(canonicalCommandInput({ a: { x: 1, y: null }, b: [2, 1] }))
    expect(canonicalCommandInput([2, 1])).not.toBe(canonicalCommandInput([1, 2]))
    expect(() => canonicalCommandInput({ a: NaN })).toThrow()
    expect(() => canonicalCommandInput(new Array(1))).toThrow()
  })
  it('coalesces duplicate clicks and snapshots nested inputs before dispatch', async () => {
    const intents = createCommandIntents<{ labels: string[] }, string>()
    const send = vi.fn(async (_input: { labels: string[] }, _key: string) => 'receipt')
    const input = { labels: ['original'] }
    const first = intents.execute(scope, input, send)
    const second = intents.execute(scope, input, send)
    input.labels.push('changed')
    expect(first).toBe(second)
    await first
    expect(send).toHaveBeenCalledOnce()
    expect(send.mock.calls[0]?.[0]).toEqual({ labels: ['original'] })
  })
  it('reuses a key after unknown, retains the old intent across edits, and separates targets', async () => {
    const intents = createCommandIntents<{ title: string }, string>()
    const send = vi.fn(async (_input: { title: string }, _key: string): Promise<string> => { throw error(503) })
    await expect(intents.execute(scope, { title: 'old' }, send)).rejects.toThrow()
    await expect(intents.execute(scope, { title: 'new' }, send)).rejects.toThrow()
    await expect(intents.execute(scope, { title: 'old' }, send)).rejects.toThrow()
    await expect(intents.execute({ ...scope, target: 'other' }, { title: 'old' }, send)).rejects.toThrow()
    expect(send.mock.calls[0]?.[1]).toBe(send.mock.calls[2]?.[1])
    expect(new Set(send.mock.calls.map(call => call[1])).size).toBe(3)
  })
  it('does not mutate the replay snapshot when an adapter mutates its argument', async () => {
    const intents = createCommandIntents<{ title: string }, string>()
    const send = vi.fn(async (input: { title: string }, _key: string): Promise<string> => {
      expect(input.title).toBe('original')
      input.title = 'adapter mutation'
      throw error(500)
    })
    await expect(intents.execute(scope, { title: 'original' }, send)).rejects.toThrow()
    await expect(intents.execute(scope, { title: 'original' }, send)).rejects.toThrow()
    expect(send.mock.calls[0]?.[1]).toBe(send.mock.calls[1]?.[1])
  })
  it('refuses overflow instead of forgetting unknown commands and still permits their recovery', async () => {
    const intents = createCommandIntents<string, string>(1)
    const send = vi.fn(async (_input: string, _key: string): Promise<string> => { throw error(500) })
    await expect(intents.execute(scope, 'old', send)).rejects.toThrow()
    expect(() => intents.execute(scope, 'new', send)).toThrow('待确认的操作过多')
    await expect(intents.execute(scope, 'old', send)).rejects.toThrow()
    expect(send.mock.calls[0]?.[1]).toBe(send.mock.calls[1]?.[1])
  })
  it('does not forget an unknown write merely because its replay is now forbidden', async () => {
    const intents = createCommandIntents<string, string>()
    const send = vi.fn(async (_input: string, _key: string): Promise<string> => { throw error(403) })
    send.mockRejectedValueOnce(error(503))
    for (let i = 0; i < 3; i++) await expect(intents.execute(scope, 'input', send)).rejects.toThrow()
    expect(new Set(send.mock.calls.map(call => call[1])).size).toBe(1)
  })
  it('clears identity-local snapshots and prevents dispatch queued before logout', async () => {
    const intents = createCommandIntents<string, string>()
    const send = vi.fn(async () => 'receipt')
    const old = intents.execute(scope, 'input', send)
    intents.clear()
    await expect(old).rejects.toThrow('cleared before dispatch')
    expect(send).not.toHaveBeenCalled()
    await intents.execute(scope, 'input', send)
    expect(send).toHaveBeenCalledOnce()
  })
  it.each([400, 409])('uses a new key after a definite %i outcome', async status => {
    const intents = createCommandIntents<string, string>()
    const send = vi.fn(async (_input: string, _key: string): Promise<string> => { throw error(status) })
    await expect(intents.execute(scope, 'input', send)).rejects.toThrow()
    await expect(intents.execute(scope, 'input', send)).rejects.toThrow()
    expect(send.mock.calls[0]?.[1]).not.toBe(send.mock.calls[1]?.[1])
  })
})
