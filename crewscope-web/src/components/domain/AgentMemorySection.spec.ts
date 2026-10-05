import { CrewScopeApiError } from '../../api/client'
import { flushPromises, mount } from '@vue/test-utils'
import { fixtureIds } from '../../test/scopeFixtures'
import { createAgentMemoryStore, AGENT_MEMORY_STORE, type AgentMemoryGateway } from '../../domains/agent/memory-store'
import type { AgentMemoryClearance, AgentMemoryView } from '../../domains/agent/types'
import AgentMemorySection from './AgentMemorySection.vue'

const routerLinkStub = { RouterLink: { props: ['to'], template: '<a href="#" :data-to="JSON.stringify(to)"><slot /></a>' } }
const scope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform }
const profileId = '00000000-0000-0000-0000-000000006101'

describe('AgentMemorySection', () => {
  it('states the unconfigured policy plainly and offers no clearing entry', async () => {
    const gateway = new FixtureMemoryGateway()
    gateway.getMemory = vi.fn(async () => view({ policyReference: null, policy: null, entries: [], entryCount: 0 }))
    const wrapper = await mountSection(gateway)

    expect(wrapper.text()).toContain('未启用辅助记忆')
    expect(wrapper.text()).not.toContain('清除我的辅助记忆')
  })

  it('shows the healthy policy digest and the entry table', async () => {
    const wrapper = await mountSection(new FixtureMemoryGateway())

    expect(wrapper.text()).toContain('90 天')
    expect(wrapper.text()).toContain('100 条')
    expect(wrapper.text()).toContain('1024 字节')
    expect(wrapper.text()).toContain('清空代际')
    expect(wrapper.text()).toContain('reply-language')
    expect(wrapper.text()).toContain('简体中文')
    expect(wrapper.text()).toContain('v2')
    // The clear entry exists but stays closed behind its confirmation.
    const clear = wrapper.findAll('button').find(item => item.text().includes('清除我的辅助记忆'))!
    expect(clear.attributes('disabled')).toBeDefined()
  })

  it('never words the degraded state as an empty memory list', async () => {
    const gateway = new FixtureMemoryGateway()
    gateway.getMemory = vi.fn(async () => view({
      policy: null, degraded: 'POLICY_UNAVAILABLE', entries: [], entryCount: 0,
      policyReference: { id: '7f2c9d64-5b1a-4f0e-9a3d-2c8b1e6f4a20', version: 99 },
    }))
    const wrapper = await mountSection(gateway)

    expect(wrapper.text()).toContain('引用的记忆策略暂不可用')
    expect(wrapper.text()).toContain('7f2c9d64')
    expect(wrapper.text()).toContain('@v99')
    expect(wrapper.text()).toContain('策略不可用期间无法读取条目')
    expect(wrapper.text()).not.toContain('还没有记忆条目')
    // Clearing stays available — the entries exist server-side even while unreadable.
    expect(wrapper.text()).toContain('清除我的辅助记忆')
  })

  it('gates clearing behind the confirmation checkbox and words the receipt', async () => {
    const gateway = new FixtureMemoryGateway()
    let cleared = false
    gateway.getMemory = vi.fn(async () => view(cleared ? { clearanceGeneration: 4, entries: [], entryCount: 0 } : {}))
    gateway.clearMemory = vi.fn(async (): Promise<AgentMemoryClearance> => {
      cleared = true
      return { clearedCount: 2, clearanceGeneration: 4 }
    })
    const wrapper = await mountSection(gateway)

    const clear = wrapper.findAll('button').find(item => item.text().includes('清除我的辅助记忆'))!
    await clear.trigger('click')
    expect(gateway.clearMemory).not.toHaveBeenCalled()

    await wrapper.get('input[type="checkbox"]').setValue(true)
    expect(clear.attributes('disabled')).toBeUndefined()
    await clear.trigger('click')
    await flushPromises()

    expect(gateway.clearMemory).toHaveBeenCalledWith(scope, profileId)
    const banner = wrapper.get('.memory-banner')
    expect(banner.attributes('role')).toBe('status')
    expect(banner.text()).toBe('已清除 2 条记忆 · 清空代际 4')
    // The table re-reads to the cleared state and the checkbox resets.
    expect(wrapper.text()).toContain('还没有记忆条目')
    expect(wrapper.get<HTMLInputElement>('input[type="checkbox"]').element.checked).toBe(false)
  })

  it('words the repeated clear as the zero-entry structural replay it is', async () => {
    const gateway = new FixtureMemoryGateway()
    let generation = 4
    gateway.getMemory = vi.fn(async () => view({ clearanceGeneration: generation, entries: [], entryCount: 0 }))
    gateway.clearMemory = vi.fn(async (): Promise<AgentMemoryClearance> => {
      generation += 1
      return { clearedCount: 0, clearanceGeneration: generation }
    })
    const wrapper = await mountSection(gateway)

    for (let round = 0; round < 2; round += 1) {
      await wrapper.get('input[type="checkbox"]').setValue(true)
      await wrapper.findAll('button').find(item => item.text().includes('清除我的辅助记忆'))!.trigger('click')
      await flushPromises()
    }

    expect(wrapper.get('.memory-banner').text()).toBe('已清除 0 条记忆 · 清空代际 6')
  })

  it('answers 403 with the forbidden panel and a hard error with a retry', async () => {
    const gateway = new FixtureMemoryGateway()
    gateway.getMemory = vi.fn()
      .mockImplementationOnce(async () => { throw apiError(403, 'policy_denied') })
      .mockImplementationOnce(async () => { throw apiError(500, 'internal_error') })
      .mockImplementationOnce(async () => view())
    const wrapper = await mountSection(gateway)

    expect(wrapper.text()).toContain('无权读取辅助记忆')
    expect(wrapper.find('a').attributes('data-to')).toContain('scope:read')

    await wrapper.setProps({ profileId: otherProfileId })
    await flushPromises()
    expect(wrapper.text()).toContain('加载失败')
    await wrapper.findAll('button').find(item => item.text().includes('刷新事实'))!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('90 天')
  })
})

const otherProfileId = '00000000-0000-0000-0000-000000006102'

async function mountSection(gateway: FixtureMemoryGateway, profile = profileId) {
  const store = createAgentMemoryStore(gateway)
  store.activateScope(scope)
  const wrapper = mount(AgentMemorySection, {
    props: { profileId: profile },
    global: { provide: { [AGENT_MEMORY_STORE as symbol]: store }, stubs: routerLinkStub },
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

class FixtureMemoryGateway implements AgentMemoryGateway {
  getMemory = vi.fn(async (): Promise<AgentMemoryView> => view())
  clearMemory = vi.fn(async (): Promise<AgentMemoryClearance> => ({ clearedCount: 0, clearanceGeneration: 1 }))
}

function view(extra: Partial<AgentMemoryView> = {}): AgentMemoryView {
  return {
    policyReference: { id: '7f2c9d64-5b1a-4f0e-9a3d-2c8b1e6f4a20', version: 1 },
    policy: {
      policyId: '7f2c9d64-5b1a-4f0e-9a3d-2c8b1e6f4a20', version: 1,
      ttlDays: 90, maxEntriesPerOwner: 100, valueMaxBytes: 1024,
    },
    degraded: null,
    clearanceGeneration: 3,
    entries: [{
      memoryKey: 'reply-language', value: '简体中文', version: 2,
      expiresAt: '2026-12-01T00:00:00Z', createdAt: '2026-08-04T09:00:00Z',
      updatedAt: '2026-09-04T09:00:00Z', createdBy: fixtureIds.principal, updatedBy: fixtureIds.principal,
    }],
    entryCount: 1,
    ...extra,
  }
}
