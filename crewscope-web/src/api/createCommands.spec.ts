import { CrewScopeApiError } from './client'
import type { WorkItemGateway } from '../domains/workitem/gateway'
import type { ConversationGateway } from '../domains/conversation/gateway'
import { createWorkItemStore } from '../domains/workitem/store'
import { createConversationStore } from '../domains/conversation/store'
import { FixtureWorkItemGateway, workItemIds } from '../test/workItemFixtures'
import { FixtureConversationGateway, conversationIds } from '../test/conversationFixtures'
import { fixtureIds } from '../test/scopeFixtures'
import type { CommandReceipt } from '../domains/scope/types'

const scope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform, projectId: fixtureIds.projectCrewScope }
const receipt = { commandId: 'command', domainEventId: 'event', committedVersion: 0, correlationId: 'correlation' }
const unknown = () => new CrewScopeApiError(503, {
  code: 'service_unavailable', message: 'upstream error', correlationId: 'c', retryable: true, currentVersion: null, details: {},
})

function fixture(kind: 'work' | 'conversation', send: (key: string) => Promise<CommandReceipt>) {
  if (kind === 'work') {
    const gateway: WorkItemGateway = new FixtureWorkItemGateway()
    gateway.createWorkItem = async (_scope, _input, key) => ({ ...await send(key),
      creation: { ...scope, type: 'WORK_ITEM', resourceId: workItemIds.first, stage: 'COMMITTED', committedVersion: 0 } })
    const store = createWorkItemStore(gateway)
    return { state: store.state, load: () => store.load(scope), reset: store.reset,
      create: (title: string) => store.create({ key: 'CRW-21', title, type: 'TASK', priority: 'MEDIUM', description: null, dueAt: null, labels: [] }) }
  }
  const gateway: ConversationGateway = new FixtureConversationGateway()
  gateway.createConversation = async (_scope, _input, key) => ({ ...await send(key),
    creation: { ...scope, projectId: null, type: 'CONVERSATION', resourceId: conversationIds.provider, stage: 'COMMITTED', committedVersion: 0 } })
  const store = createConversationStore(gateway)
  return { state: store.state, load: () => store.load(scope), reset: store.reset,
    create: (title: string) => store.create(scope, { title, visibility: 'PRIVATE' }) }
}

describe.each(['work', 'conversation'] as const)('%s create intent integration', kind => {
  it('keeps the key after 503 and restores it after edits, without automatic retries', async () => {
    const send = vi.fn(async (_key: string): Promise<CommandReceipt> => { throw unknown() })
    const store = fixture(kind, send)
    await store.load()
    await expect(store.create('original')).rejects.toThrow()
    expect(store.state.commandErrorMessage).toContain('提交结果尚未确认')
    expect(send).toHaveBeenCalledOnce()
    await expect(store.create('edited')).rejects.toThrow()
    await expect(store.create('original')).rejects.toThrow()
    expect(send.mock.calls[0]?.[0]).toBe(send.mock.calls[2]?.[0])
    expect(send.mock.calls[0]?.[0]).not.toBe(send.mock.calls[1]?.[0])
  })

  it('coalesces duplicate submits until the original outcome arrives', async () => {
    let resolve!: (receipt: CommandReceipt) => void
    const send = vi.fn((_key: string) => new Promise<CommandReceipt>(accept => { resolve = accept }))
    const store = fixture(kind, send)
    await store.load()
    const first = store.create('original')
    const second = store.create('original')
    expect(first).toBe(second)
    await Promise.resolve()
    expect(send).toHaveBeenCalledOnce()
    expect(store.state.commandPending).toBe(true)
    resolve(receipt)
    await first
    expect(store.state.commandPending).toBe(false)
  })

  it('ignores stale error and finally after logout then reload of the same scope', async () => {
    const rejects: Array<(error: Error) => void> = []
    const send = vi.fn((_key: string) => new Promise<CommandReceipt>((_resolve, reject) => { rejects.push(reject) }))
    const store = fixture(kind, send)
    await store.load()
    const old = store.create('same')
    const oldRejected = expect(old).rejects.toThrow()
    await Promise.resolve()
    store.reset()
    await store.load()
    const current = store.create('same')
    const currentRejected = expect(current).rejects.toThrow()
    await Promise.resolve()
    rejects[0]!(unknown())
    await oldRejected
    expect(store.state.commandPending).toBe(true)
    expect(store.state.commandErrorMessage).toBeNull()
    expect(send.mock.calls[0]?.[0]).not.toBe(send.mock.calls[1]?.[0])
    rejects[1]!(unknown())
    await currentRejected
    expect(store.state.commandPending).toBe(false)
    expect(store.state.commandErrorMessage).toContain('提交结果尚未确认')
  })
})
