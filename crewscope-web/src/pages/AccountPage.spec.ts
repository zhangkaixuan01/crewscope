import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { AccountGateway } from '../domains/account/gateway'
import { ACCOUNT_STORE, createAccountStore } from '../domains/account/store'
import type { AccountProfile } from '../domains/account/types'
import { AUTH_STORE } from '../domains/identity/store'
import { fixtureAuthStore } from '../test/authFixtures'
import AccountPage from './AccountPage.vue'

describe('AccountPage', () => {
  it('loads the current account on entry and clears it on exit', async () => {
    const gateway: AccountGateway = {
      current: vi.fn(async () => ({ value: profile(), etag: 4 })),
      updateProfile: vi.fn(), changePassword: vi.fn(), revokeAllSessions: vi.fn(),
    }
    const accountStore = createAccountStore(gateway)
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/account', name: 'account', component: { template: '<div />' } },
      { path: '/login', name: 'login', component: { template: '<div />' } },
    ] })
    await router.push('/account')
    await router.isReady()

    const wrapper = mount(AccountPage, {
      global: {
        plugins: [router],
        provide: {
          [ACCOUNT_STORE as symbol]: accountStore,
          [AUTH_STORE as symbol]: fixtureAuthStore(),
        },
        stubs: {
          AppShell: { template: '<main><slot /></main>' },
          StatePanel: { template: '<div data-state-panel />' },
          AccountWorkspace: {
            props: ['profile'],
            template: '<section data-account-workspace>{{ profile.username }}</section>',
          },
        },
      },
    })
    await flushPromises()

    expect(gateway.current).toHaveBeenCalledOnce()
    expect(wrapper.get('[data-account-workspace]').text()).toBe('alice')
    expect(accountStore.state.phase).toBe('ready')

    wrapper.unmount()
    expect(accountStore.state.phase).toBe('idle')
    expect(accountStore.state.profile).toBeNull()
  })
})

function profile(): AccountProfile {
  return {
    accountId: 'account-1', username: 'alice', email: 'alice@example.com', displayName: 'Alice',
    status: 'ACTIVE', platformRole: 'USER', securityVersion: 3, version: 4,
    createdAt: '2026-08-29T00:00:00Z', updatedAt: '2026-08-29T00:00:00Z',
  }
}
