import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import SettingsShell from './SettingsShell.vue'

const TEAM = '00000000-0000-4000-8000-000000000001'
const PROJECT = '00000000-0000-4000-8000-000000000002'

async function mountShell(path: string): Promise<{ wrapper: ReturnType<typeof mount>, router: Router }> {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { name: 'work', path: '/work', component: { template: '<div />' } },
    { name: 'agent-settings', path: '/settings/agents', component: { template: '<div />' } },
  ] })
  await router.push(path)
  const wrapper = mount(SettingsShell, {
    props: { title: 'Agent 中心' },
    global: {
      plugins: [router],
      stubs: { AppShell: { template: '<div><slot name="actions" /></div>' }, SettingsFieldSearch: true, RouterLink: true },
    },
  })
  return { wrapper, router }
}

it('offers the registered return target and navigates with the validated coordinates', async () => {
  const { wrapper, router } = await mountShell(`/settings/agents?from=work&team=${TEAM}&project=${PROJECT}&delegate=coding`)
  const button = wrapper.find('button')
  expect(button.text()).toBe('返回工作项')
  await button.trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.name).toBe('work')
  expect(router.currentRoute.value.query).toEqual({ team: TEAM, project: PROJECT, delegate: 'coding' })
})

it('omits the return affordance without a registered origin', async () => {
  const { wrapper } = await mountShell(`/settings/agents?team=${TEAM}`)
  expect(wrapper.find('button').exists()).toBe(false)
})

it('ignores unregistered or malformed origins instead of executing them', async () => {
  const unregistered = await mountShell('/settings/agents?from=evil.example%2Fpath')
  expect(unregistered.wrapper.find('button').exists()).toBe(false)
  const malformed = await mountShell(`/settings/agents?from=work&team=${TEAM}&project=${TEAM}&project=${TEAM}`)
  const button = malformed.wrapper.find('button')
  expect(button.exists()).toBe(true)
  await button.trigger('click')
  await flushPromises()
  expect(malformed.router.currentRoute.value.name).toBe('work')
  // The duplicated (array-valued) project fails the uuid check; the single-valued team survives.
  expect(malformed.router.currentRoute.value.query).toEqual({ team: TEAM })
})
