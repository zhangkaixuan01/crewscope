import { defineComponent, h, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { useRouteIntent } from './useRouteIntent'

function mountWithIntent(knownIntents: readonly string[], ready: () => boolean, handle: (intent: string) => void) {
  const component = defineComponent({
    setup() { useRouteIntent('today', knownIntents, ready, handle); return () => h('div') },
  })
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { name: 'today', path: '/today', component }, { name: 'setup', path: '/setup', component },
  ] })
  return { router, component }
}

it('consumes a known intent once, strips it from the URL and skips unknown values', async () => {
  const ready = ref(false)
  const handle = vi.fn()
  const { router, component } = mountWithIntent(['create-project'], () => ready.value, handle)
  await router.push('/today?intent=create-project&team=team')
  const wrapper = mount(component, { global: { plugins: [router] } })
  await flushPromises()
  expect(handle).not.toHaveBeenCalled()
  ready.value = true
  await flushPromises()
  expect(handle).toHaveBeenCalledWith('create-project')
  expect(router.currentRoute.value.query).toEqual({ team: 'team' })
  // The parameter is consumed once: navigating again on the stripped URL never re-triggers.
  await router.replace({ query: { team: 'team' } })
  await flushPromises()
  expect(handle).toHaveBeenCalledOnce()
  wrapper.unmount()
})

it('ignores intents the caller did not register', async () => {
  const handle = vi.fn()
  const { router, component } = mountWithIntent(['create-project'], () => true, handle)
  await router.push('/today?intent=delete-everything')
  const wrapper = mount(component, { global: { plugins: [router] } })
  await flushPromises()
  expect(handle).not.toHaveBeenCalled()
  expect(router.currentRoute.value.query.intent).toBe('delete-everything')
  wrapper.unmount()
})
