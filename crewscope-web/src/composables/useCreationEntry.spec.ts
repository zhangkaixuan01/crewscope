import { defineComponent, h, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { useCreationEntry } from './useCreationEntry'
import { ActionRegistry } from '../app/actionRegistry'
import { registerDefaultActions } from '../app/defaultActions'
import { bootstrapPrincipal } from '../test/authFixtures'

it.each(['work', 'conversation'])('opens %s creation only once after scope readiness and removes stale object query', async name => {
  const ready = ref<string | null>(null)
  const open = vi.fn()
  const component = defineComponent({ setup() { useCreationEntry(name, () => ready.value, open); return () => h('div') } })
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { name: 'work', path: '/work', component }, { name: 'conversation', path: '/conversation', component },
  ] })
  await router.push(`/work?team=team&project=project&workItem=old&task=old-task`)
  const registry = new ActionRegistry()
  registerDefaultActions(registry, router, bootstrapPrincipal)
  await registry.get(`create.${name}`)!.execute({ router, route: router.currentRoute.value, principal: bootstrapPrincipal })
  expect(router.currentRoute.value.query).toEqual(name === 'work'
    ? { team: 'team', project: 'project', create: '1' } : { team: 'team', create: '1' })
  const wrapper = mount(component, { global: { plugins: [router] } })
  await flushPromises()
  expect(open).not.toHaveBeenCalled()
  ready.value = 'authorized-scope'
  await flushPromises()
  expect(open).toHaveBeenCalledOnce()
  expect(router.currentRoute.value.query.create).toBeUndefined()
  ready.value = 'other-scope'
  await flushPromises()
  expect(open).toHaveBeenCalledOnce()
  wrapper.unmount()
  const reopened = mount(component, { global: { plugins: [router] } })
  await flushPromises()
  expect(open).toHaveBeenCalledOnce()
  reopened.unmount()
})
