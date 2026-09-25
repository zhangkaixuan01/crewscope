import { CrewScopeApiError } from './client'
import { createCommandGateway } from './commandGateway'

describe('explicit command gateway intents', () => {
  it('coalesces double clicks, snapshots payload and preserves the first supplied key', async () => {
    const pending = deferred<string>()
    const source = { write: vi.fn((_scope: object, _input: object, _key: string) => pending.promise) }
    const { gateway } = createCommandGateway(source, { write: 2 })
    const input = { title: 'original', nested: { version: 2 } }
    const first = gateway.write({ team: 'A' }, input, 'original-key')
    const second = gateway.write({ team: 'A' }, input, 'fresh-click-key')
    input.nested.version = 9
    expect(source.write).toHaveBeenCalledTimes(1)
    expect(source.write.mock.calls[0]).toEqual([{ team: 'A' }, { title: 'original', nested: { version: 2 } }, 'original-key'])
    expect(second).toBe(first)
    pending.resolve('receipt')
    expect(await second).toBe('receipt')
  })

  it('retains original identity after unknown, payload edits, changing back and later forbidden', async () => {
    const write = vi.fn(async (_scope: object, _input: object, _key: string) => { throw error(503) })
    const { gateway } = createCommandGateway({ write }, { write: 2 })
    await expect(gateway.write({ team: 'A' }, { a: 1, b: 2 }, 'first')).rejects.toMatchObject({ status: 503 })
    await expect(gateway.write({ team: 'A' }, { a: 3, b: 2 }, 'first')).rejects.toThrow('结果尚未确认')
    write.mockRejectedValueOnce(error(403))
    await expect(gateway.write({ team: 'A' }, { b: 2, a: 1 }, 'new-key')).rejects.toMatchObject({ status: 403 })
    await expect(gateway.write({ team: 'A' }, { a: 1, b: 2 }, 'another-key')).rejects.toThrow()
    expect(write.mock.calls.map(call => call[2])).toEqual(['first', expect.not.stringMatching(/^first$/), 'first', 'first'])
  })

  it('separates scope, operation and original version without background retries', async () => {
    const source = { write: vi.fn(async (..._args: unknown[]) => { throw error(503) }), cancel: vi.fn(async (..._args: unknown[]) => 'ok') }
    const { gateway } = createCommandGateway(source, { write: 2, cancel: 2 })
    await expect(gateway.write({ team: 'A' }, { version: 2 }, 'a')).rejects.toThrow()
    await expect(gateway.write({ team: 'B' }, { version: 2 }, 'b')).rejects.toThrow()
    await expect(gateway.write({ team: 'A' }, { version: 3 }, 'c')).rejects.toThrow()
    await gateway.cancel({ team: 'A' }, { version: 2 }, 'd')
    expect(source.write).toHaveBeenCalledTimes(3)
    expect(source.write.mock.calls.map(call => call[2])).toEqual(['a', 'b', 'c'])
  })

  it('does not enroll reads, unkeyed preflights, authentication or streaming adapters', async () => {
    const read = vi.fn(async () => 'fresh')
    const { gateway } = createCommandGateway({ read }, {})
    await Promise.all([gateway.read(), gateway.read()])
    expect(read).toHaveBeenCalledTimes(2)
  })

  it('starts a new intent after certain rejection and after certain success', async () => {
    const write = vi.fn(async (_body: object, _key: string) => 'receipt').mockRejectedValueOnce(error(400))
    const { gateway } = createCommandGateway({ write }, { write: 1 })
    await expect(gateway.write({}, 'first')).rejects.toThrow()
    await gateway.write({}, 'corrected')
    await gateway.write({}, 'intentional-new')
    expect(write.mock.calls.map(call => call[1])).toEqual(['first', 'corrected', 'intentional-new'])
  })

  it('never evicts unresolved commands at capacity and clears all identities on owner reset', async () => {
    const write = vi.fn(async (_body: object, _key: string) => { throw error(503) })
    const commands = createCommandGateway({ write }, { write: 1 }, 1)
    await expect(commands.gateway.write({ id: 1 }, 'old')).rejects.toThrow()
    expect(() => commands.gateway.write({ id: 2 }, 'new')).toThrow('待确认操作过多')
    commands.clear()
    await expect(commands.gateway.write({ id: 1 }, 'new-account')).rejects.toThrow()
    expect(write.mock.calls[1]![1]).toBe('new-account')
  })

  it('suppresses late success after reset and cannot overwrite the next account', async () => {
    const old = deferred<string>()
    const write = vi.fn((_body: object, _key: string) => old.promise)
    const commands = createCommandGateway({ write }, { write: 1 })
    const pending = commands.gateway.write({}, 'old-account')
    commands.clear()
    old.resolve('old-receipt')
    await expect(pending).rejects.toMatchObject({ name: 'AbortError' })
  })
})

function error(status: number) {
  return new CrewScopeApiError(status, { code: 'fixture', message: 'fixture', correlationId: 'fixture', retryable: status >= 500, currentVersion: null, details: {} })
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(yes => { resolve = yes })
  return { promise, resolve }
}
