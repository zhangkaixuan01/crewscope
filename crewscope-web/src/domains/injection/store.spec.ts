import { CrewScopeApiError } from '../../api/client'
import { fixtureIds } from '../../test/scopeFixtures'
import { nextTick, watch } from 'vue'
import type { SettingsScope } from '../settings/types'
import type { InjectionGateway } from './gateway'
import { createInjectionStore, viewKey } from './store'
import type { InjectionFeedbackInput, InjectionReferenceKey, InjectionReferences } from './types'

const platformScope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform }
const securityScope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamSecurity }
const taskId = '00000000-0000-0000-0000-000000006301'
const executionId = '00000000-0000-0000-0000-000000006401'
const otherExecutionId = '00000000-0000-0000-0000-000000006402'

describe('InjectionStore', () => {
  it('publishes view phases through the reactive proxy and resolves manifest-free executions to empty', async () => {
    const gateway = new FixtureInjectionGateway()
    vi.spyOn(gateway, 'list').mockImplementation(async () => references({ attempts: [] }))
    const store = createInjectionStore(gateway)
    store.activateScope(platformScope)
    const phases: Array<string | undefined> = []
    const stop = watch(() => store.state.views[viewKey(taskId, executionId)]?.phase, phase => phases.push(phase))

    await store.load(taskId, executionId)

    expect(phases).toContain('loading')
    expect(store.state.views[viewKey(taskId, executionId)]?.phase).toBe('empty')
    expect(store.state.views[viewKey(taskId, executionId)]?.value?.attempts).toEqual([])
    stop()
  })

  it('keeps one view resource per taskId:executionId and serves ready views from cache', async () => {
    const gateway = new FixtureInjectionGateway()
    const calls: string[] = []
    vi.spyOn(gateway, 'list').mockImplementation(async (_scope, taskId, executionId) => {
      calls.push(`${taskId}:${executionId}`)
      return references({})
    })
    const store = createInjectionStore(gateway)
    store.activateScope(platformScope)

    await store.load(taskId, executionId)
    await store.load(taskId, executionId)
    await store.load(taskId, otherExecutionId)

    expect(calls).toEqual([`${taskId}:${executionId}`, `${taskId}:${otherExecutionId}`])
    expect(store.state.views[viewKey(taskId, executionId)]?.phase).toBe('ready')
    expect(store.state.views[viewKey(taskId, otherExecutionId)]?.phase).toBe('ready')
  })

  it('isolates a late response after the selected Team Scope changes', async () => {
    const gateway = new FixtureInjectionGateway()
    const first = deferred<InjectionReferences>()
    vi.spyOn(gateway, 'list')
      .mockImplementationOnce(async () => first.promise)
      .mockImplementationOnce(async () => references({ attempts: [attempt({ attempt: 1 })] }))
    const store = createInjectionStore(gateway)

    store.activateScope(platformScope)
    const slow = store.load(taskId, executionId)
    store.activateScope(securityScope)
    await store.load(taskId, executionId)
    first.resolve(references({ attempts: [attempt({ attempt: 9 })] }))
    await slow
    await nextTick()

    expect(store.state.views[viewKey(taskId, executionId)]?.value?.attempts.map(item => item.attempt)).toEqual([1])
  })

  it('patches the marked quadruple in place across every attempt on feedback success', async () => {
    const gateway = new FixtureInjectionGateway()
    vi.spyOn(gateway, 'list').mockImplementation(async () => references({
      attempts: [
        attempt({ attempt: 1, references: [reference({}), reference({ sourceId: 'entry-2' })] }),
        attempt({ attempt: 2, references: [reference({})] }),
      ],
    }))
    vi.spyOn(gateway, 'submitFeedback').mockImplementation(async () => key())
    const store = createInjectionStore(gateway)
    store.activateScope(platformScope)
    await store.load(taskId, executionId)

    expect(await store.submitFeedback(taskId, executionId, feedback())).toBe(true)

    expect(store.state.feedback.phase).toBe('success')
    expect(store.state.feedback.message).toBe('已标记为不适用')
    const value = store.state.views[viewKey(taskId, executionId)]?.value
    // The judgement is execution-scoped: the same quadruple reads back as marked in both attempts,
    // while the sibling row stays untouched.
    expect(value?.attempts[0]?.references.map(row => row.notApplicable)).toEqual([true, false])
    expect(value?.attempts[1]?.references[0]?.notApplicable).toBe(true)
    // A replay (structural idempotency, contract §3) returns success and changes nothing.
    expect(await store.submitFeedback(taskId, executionId, feedback())).toBe(true)
    expect(store.state.views[viewKey(taskId, executionId)]?.value).toBe(value)
  })

  it('words the 422 outside-manifest verdict on the feedback banner and lands its code', async () => {
    const gateway = new FixtureInjectionGateway()
    vi.spyOn(gateway, 'list').mockImplementation(async () => references({}))
    vi.spyOn(gateway, 'submitFeedback').mockImplementation(async () => {
      throw new CrewScopeApiError(422, {
        code: 'feedback_reference_outside_manifest',
        message: 'Feedback target is outside the injected union',
        correlationId: '00000000-0000-0000-0000-000000000901',
        retryable: false,
        currentVersion: null,
        details: { source: 'entry-9' },
      })
    })
    const store = createInjectionStore(gateway)
    store.activateScope(platformScope)
    await store.load(taskId, executionId)

    expect(await store.submitFeedback(taskId, executionId, feedback())).toBe(false)

    expect(store.state.feedback.phase).toBe('error')
    expect(store.state.feedback.errorMessage).toBe('该引用不在本次执行任何 attempt 的已注入并集中，无法标记。')
    expect(store.state.feedback.errorCode).toBe('feedback_reference_outside_manifest')
    expect(store.state.feedback.errorStatus).toBe(422)
    // The evidence view itself stays untouched by a failed command.
    expect(store.state.views[viewKey(taskId, executionId)]?.phase).toBe('ready')
  })

  it('rejects a second feedback while one is pending and resets on demand', async () => {
    const gateway = new FixtureInjectionGateway()
    vi.spyOn(gateway, 'list').mockImplementation(async () => references({}))
    const first = deferred<ReturnType<typeof key>>()
    vi.spyOn(gateway, 'submitFeedback').mockImplementationOnce(async () => first.promise)
    const store = createInjectionStore(gateway)
    store.activateScope(platformScope)
    await store.load(taskId, executionId)

    const slow = store.submitFeedback(taskId, executionId, feedback())
    expect(await store.submitFeedback(taskId, executionId, feedback({ sourceId: 'entry-2' }))).toBe(false)
    first.resolve(key())
    expect(await slow).toBe(true)

    store.clearFeedback()
    expect(store.state.feedback.phase).toBe('idle')

    store.reset()
    expect(store.state.views[viewKey(taskId, executionId)]).toBeUndefined()
    expect(() => store.load(taskId, executionId)).rejects.toThrow('Scope is not active')
  })
})

class FixtureInjectionGateway implements InjectionGateway {
  async list(_scope: SettingsScope, _taskId: string, _executionId: string): Promise<InjectionReferences> { throw new Error('not stubbed') }
  async submitFeedback(_scope: SettingsScope, _taskId: string, _executionId: string, _input: InjectionFeedbackInput): Promise<InjectionReferenceKey> { throw new Error('not stubbed') }
}

function references(extra: Partial<InjectionReferences> = {}): InjectionReferences {
  return {
    executionId, taskId,
    attempts: [attempt({})],
    ...extra,
  }
}

function attempt(extra: Record<string, unknown> = {}): InjectionReferences['attempts'][number] {
  return {
    manifestId: '00000000-0000-0000-0000-000000006411',
    attempt: 1,
    createdAt: '2026-10-04T09:00:00Z',
    budget: { totalTokens: 8192, knowledgeTokens: 3072, chunkTokens: 4096, memoryTokens: 1024 },
    degradations: [],
    trims: [],
    references: [reference({})],
    claimed: null,
    ...extra,
  } as InjectionReferences['attempts'][number]
}

function reference(extra: Record<string, unknown> = {}): InjectionReferences['attempts'][number]['references'][number] {
  return {
    type: 'KNOWLEDGE_ENTRY', sourceId: 'entry-1', version: 3, contentHash: 'a'.repeat(64),
    stage: 'INJECTED', notApplicable: false, ...extra,
  } as InjectionReferences['attempts'][number]['references'][number]
}

function key(extra: Record<string, unknown> = {}): InjectionReferenceKey {
  return {
    type: 'KNOWLEDGE_ENTRY', sourceId: 'entry-1', version: 3, contentHash: 'a'.repeat(64), ...extra,
  } as InjectionReferenceKey
}

function feedback(extra: Partial<InjectionFeedbackInput> = {}): InjectionFeedbackInput {
  return { type: 'KNOWLEDGE_ENTRY', sourceId: 'entry-1', version: 3, contentHash: 'a'.repeat(64), ...extra }
}

function deferred<T>(): { promise: Promise<T>, resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(next => { resolve = next })
  return { promise, resolve }
}
