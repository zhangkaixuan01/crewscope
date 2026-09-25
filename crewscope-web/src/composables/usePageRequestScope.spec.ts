import { defineComponent, h, reactive } from 'vue'
import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { AUTH_STORE } from '../domains/identity/store'
import { SCOPE_STORE } from '../domains/scope/store'
import { usePageRequestScope } from './usePageRequestScope'

describe('page continuation ownership', () => {
  it('invalidates A → B → A synchronously and distinguishes selection from deliberate navigation', async () => {
    const scope = { state: reactive({ selectedTeamId: 'A', selectedProjectId: 'p' }) }
    const auth = { state: reactive({ session: { account: { accountId: 'user', securityVersion: 1 } } }) }
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/work', name: 'work', component: { template: '<div />' } }] })
    await router.push('/work')
    let requests!: ReturnType<typeof usePageRequestScope>
    const wrapper = mount(defineComponent({ setup() { requests = usePageRequestScope(); return () => h('div') } }), {
      global: { plugins: [router], provide: { [SCOPE_STORE as symbol]: scope, [AUTH_STORE as symbol]: auth } },
    })
    const first = requests.capture()
    scope.state.selectedTeamId = 'B'
    scope.state.selectedTeamId = 'A'
    expect(first.isCurrent()).toBe(false)
    const base = requests.capture()
    const selected = requests.captureSelection()
    await router.replace('/work?item=new')
    expect(base.isCurrent()).toBe(true)
    expect(selected.isCurrent()).toBe(false)
    const identity = requests.captureIdentity()
    scope.state.selectedTeamId = 'accepted-team'
    expect(identity.isCurrent()).toBe(true)
    auth.state.session.account.securityVersion += 1
    expect(identity.isCurrent()).toBe(false)
    const last = requests.begin('read')
    wrapper.unmount()
    expect(last.isCurrent()).toBe(false)
    expect(last.signal.aborted).toBe(true)
  })
})
