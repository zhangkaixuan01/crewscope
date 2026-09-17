import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent } from 'vue'
import { useRouteFocus } from './useRouteFocus'

const Harness = defineComponent({
  props: { owner: { type: String, required: true } },
  setup() {
    const { locatedId, locatedRow, bindRow, clear } = useRouteFocus('member')
    return { locatedId, locatedRow, bindRow, clear }
  },
  template: `
    <div>
      <span class="located">{{ locatedId ?? 'none' }}</span>
      <div class="row" :ref="element => bindRow(element, 'row-a')" />
      <div class="row" :ref="element => bindRow(element, 'row-b')" />
      <button class="clear" @click="clear()">清除定位</button>
    </div>
  `,
})

async function mountedAt(query: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/team/members', name: 'team-members', component: { template: '<div />' } }],
  })
  await router.push(`/team/members${query}`)
  await router.isReady()
  const wrapper = mount(Harness, { props: { owner: 'row-b' }, global: { plugins: [router] } })
  await flushPromises()
  return { router, wrapper }
}

describe('useRouteFocus', () => {
  beforeEach(() => { (Element.prototype.scrollIntoView as ReturnType<typeof vi.fn>).mockClear() })

  it('locates the row the URL names and scrolls it into view', async () => {
    const { wrapper } = await mountedAt('?team=team-1&member=row-b')

    expect(wrapper.get('.located').text()).toBe('row-b')
    expect(wrapper.vm.locatedRow).toBe(wrapper.findAll('.row')[1].element)
    expect(Element.prototype.scrollIntoView).toHaveBeenCalledTimes(1)
    expect(Element.prototype.scrollIntoView).toHaveBeenCalledWith({ block: 'center' })
  })

  it('does not claim a row other than the one the URL names', async () => {
    const { wrapper } = await mountedAt('?team=team-1&member=row-a')

    expect(wrapper.vm.locatedRow).toBe(wrapper.findAll('.row')[0].element)
    // The second binding names a different identity, so it must not overwrite the located row.
    expect(wrapper.vm.locatedRow).not.toBe(wrapper.findAll('.row')[1].element)
  })

  it('asks for nothing when the parameter is absent, blank or duplicated', async () => {
    for (const query of ['?team=team-1', '?member=', '?member=row-a&member=row-b']) {
      const { wrapper } = await mountedAt(query)
      expect(wrapper.get('.located').text()).toBe('none')
      expect(wrapper.vm.locatedRow).toBeNull()
    }
    expect(Element.prototype.scrollIntoView).not.toHaveBeenCalled()
  })

  it('clears the parameter without dropping the rest of the query', async () => {
    const { router, wrapper } = await mountedAt('?team=team-1&member=row-b&view=table')

    await wrapper.get('.clear').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query.member).toBeUndefined()
    expect(router.currentRoute.value.query).toMatchObject({ team: 'team-1', view: 'table' })
    expect(wrapper.get('.located').text()).toBe('none')
    expect(wrapper.vm.locatedRow).toBeNull()
  })
})
