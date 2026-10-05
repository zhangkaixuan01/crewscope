import { CrewScopeApiError } from '../../api/client'
import { flushPromises, mount } from '@vue/test-utils'
import { fixtureIds } from '../../test/scopeFixtures'
import type { InjectionGateway } from '../../domains/injection/gateway'
import { createInjectionStore, INJECTION_STORE } from '../../domains/injection/store'
import type {
  InjectionAttempt,
  InjectionReference,
  InjectionReferenceKey,
  InjectionReferences,
} from '../../domains/injection/types'
import InjectionReferencesPanel from './InjectionReferencesPanel.vue'

const routerLinkStub = { RouterLink: { props: ['to'], template: '<a href="#" :data-to="JSON.stringify(to)"><slot /></a>' } }

const scope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform }
const taskId = '00000000-0000-0000-0000-000000006301'
const executionId = '00000000-0000-0000-0000-000000006401'

describe('InjectionReferencesPanel', () => {
  it('explains itself when no execution is selected and reads nothing', async () => {
    const gateway = new FixtureInjectionGateway()
    gateway.list = vi.fn()
    const wrapper = await mountPanel(gateway, { executionId: null })

    expect(wrapper.text()).toContain('选择一次执行后')
    expect(gateway.list).not.toHaveBeenCalled()
  })

  it('stacks attempts ascending with both claimed wordings once loaded', async () => {
    const gateway = new FixtureInjectionGateway()
    gateway.list = vi.fn(async () => view({
      attempts: [attempt({ attempt: 2, claimed: [] }), attempt({ attempt: 1, claimed: null })],
    }))
    const wrapper = await mountPanel(gateway)

    expect(wrapper.text()).toContain('第 1 次尝试')
    expect(wrapper.text()).toContain('第 2 次尝试')
    expect(wrapper.text()).toContain('模型尚未提交引用回执')
    expect(wrapper.text()).toContain('已提交回执：零声明')
    expect(wrapper.text()).toContain('仅本人可见')
  })

  it('answers 403 with the forbidden panel and the work:read explanation', async () => {
    const gateway = new FixtureInjectionGateway()
    gateway.list = vi.fn(async () => { throw apiError(403, 'policy_denied') })
    const wrapper = await mountPanel(gateway)

    expect(wrapper.text()).toContain('无权读取注入与引用')
    expect(wrapper.find('a').attributes('data-to')).toContain('work:read')
  })

  it('keeps loaded evidence readable while offline and closes the marking entry', async () => {
    const gateway = new FixtureInjectionGateway()
    const wrapper = await mountPanel(gateway, {}, false)

    expect(wrapper.text()).toContain('正在展示最近读取的注入清单')
    const mark = wrapper.findAll('button').find(item => item.text().includes('标记不适用'))!
    expect(mark.attributes('disabled')).toBeDefined()
  })

  it('offers a retry on a hard error and an honest empty wording on a manifest-free execution', async () => {
    const gateway = new FixtureInjectionGateway()
    gateway.list = vi.fn()
      .mockImplementationOnce(async () => { throw apiError(500, 'internal_error') })
      .mockImplementationOnce(async () => view({ attempts: [] }))
    const wrapper = await mountPanel(gateway)

    expect(wrapper.text()).toContain('加载失败')
    await wrapper.findAll('button').find(item => item.text().includes('刷新事实'))!.trigger('click')
    await flushPromises()

    expect(gateway.list).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('该执行没有注入清单')
  })

  it('marks a reference and patches exactly that row in place', async () => {
    const gateway = new FixtureInjectionGateway()
    gateway.submitFeedback = vi.fn(async () => key())
    gateway.list = vi.fn(async () => view({
      attempts: [attempt({ references: [
        reference({ sourceId: 'entry-1' }),
        reference({ sourceId: 'entry-2' }),
      ] })],
    }))
    const wrapper = await mountPanel(gateway)

    const mark = wrapper.findAll('button').find(item => item.text().includes('标记不适用'))!
    await mark.trigger('click')
    await flushPromises()

    expect(gateway.submitFeedback).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('你已标记不适用（不可撤销）')
    // The sibling row keeps its button — the patch touched only the marked quadruple.
    expect(wrapper.findAll('button').filter(item => item.text().includes('标记不适用'))).toHaveLength(1)
    const banner = wrapper.get('.injection-banner')
    expect(banner.attributes('role')).toBe('status')
    expect(banner.text()).toBe('已标记为不适用')
  })

  it('wording a 422 outside-manifest as an alert scoped to the union of INJECTED sets', async () => {
    const gateway = new FixtureInjectionGateway()
    gateway.submitFeedback = vi.fn(async () => { throw apiError(422, 'feedback_reference_outside_manifest') })
    const wrapper = await mountPanel(gateway)

    await wrapper.findAll('button').find(item => item.text().includes('标记不适用'))!.trigger('click')
    await flushPromises()

    const banner = wrapper.get('.injection-banner')
    expect(banner.attributes('role')).toBe('alert')
    expect(banner.text()).toContain('不在本次执行任何 attempt 的已注入并集')
  })
})

async function mountPanel(
  gateway: FixtureInjectionGateway,
  props: Record<string, unknown> = {},
  online = true,
) {
  const store = createInjectionStore(gateway)
  store.activateScope(scope)
  const wrapper = mount(InjectionReferencesPanel, {
    props: { taskId, executionId, online, ...props },
    global: { provide: { [INJECTION_STORE as symbol]: store }, stubs: routerLinkStub },
  })
  await flushPromises()
  return wrapper
}

function apiError(status: number, code: string): CrewScopeApiError {
  return new CrewScopeApiError(status, {
    code, message: code, correlationId: '00000000-0000-0000-0000-000000000901',
    retryable: false, currentVersion: null, details: {},
  })
}

class FixtureInjectionGateway implements InjectionGateway {
  list = vi.fn(async (): Promise<InjectionReferences> => view())
  submitFeedback = vi.fn(async (): Promise<InjectionReferenceKey> => key())
}

function view(extra: Partial<InjectionReferences> = {}): InjectionReferences {
  return { executionId, taskId, attempts: [attempt()], ...extra }
}

function attempt(extra: Partial<InjectionAttempt> = {}): InjectionAttempt {
  return {
    manifestId: '00000000-0000-0000-0000-000000006411',
    attempt: 1,
    createdAt: '2026-10-04T09:00:00Z',
    budget: { totalTokens: 8192, knowledgeTokens: 3072, chunkTokens: 4096, memoryTokens: 1024 },
    degradations: [],
    trims: [],
    references: [reference()],
    claimed: null,
    ...extra,
  }
}

function reference(extra: Partial<InjectionReference> = {}): InjectionReference {
  return {
    type: 'KNOWLEDGE_ENTRY', sourceId: 'entry-1', version: 3, contentHash: 'a'.repeat(64),
    stage: 'INJECTED', notApplicable: false, ...extra,
  }
}

function key(): InjectionReferenceKey {
  return { type: 'KNOWLEDGE_ENTRY', sourceId: 'entry-1', version: 3, contentHash: 'a'.repeat(64) }
}
