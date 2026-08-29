import { mount } from '@vue/test-utils'
import App from './App.vue'
import type { IdentityGateway } from './domains/identity/gateway'
import { AUTH_STORE, createAuthStore } from './domains/identity/store'
import type { AuthSession } from './domains/identity/types'

describe('App authentication boundary', () => {
  it('renders only the Session recovery surface before identity is known', async () => {
    const pending = new Promise<AuthSession>(() => {})
    const authStore = createAuthStore(gateway(async () => pending), { channelFactory: () => null })
    authStore.start()

    const wrapper = mount(App, {
      global: {
        provide: { [AUTH_STORE as symbol]: authStore },
        stubs: { RouterView: { template: '<div data-test="business-page">private workspace</div>' }, GlobalErrorBanner: true },
      },
    })

    expect(wrapper.text()).toContain('正在确认你的会话')
    expect(wrapper.find('[data-test="business-page"]').exists()).toBe(false)
    authStore.stop()
    wrapper.unmount()
  })

  it('keeps business routes hidden when Session recovery fails', async () => {
    const authStore = createAuthStore(gateway(async () => { throw new Error('private transport detail') }), { channelFactory: () => null })
    authStore.start()
    await vi.waitFor(() => expect(authStore.state.phase).toBe('error'))

    const wrapper = mount(App, {
      global: {
        provide: { [AUTH_STORE as symbol]: authStore },
        stubs: { RouterView: { template: '<div data-test="business-page">private workspace</div>' }, GlobalErrorBanner: true },
      },
    })

    expect(wrapper.text()).toContain('没有完成会话恢复')
    expect(wrapper.text()).not.toContain('private transport detail')
    expect(wrapper.find('[data-test="business-page"]').exists()).toBe(false)
    authStore.stop()
    wrapper.unmount()
  })
})

function gateway(load: () => Promise<AuthSession>): IdentityGateway {
  return { session: vi.fn(load), login: vi.fn(), logout: vi.fn(), register: vi.fn() }
}
