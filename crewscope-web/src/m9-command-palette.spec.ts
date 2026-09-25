import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'
import CommandPalette from './components/action/CommandPalette.vue'
import { ACTION_REGISTRY, createActionRegistry, type ActionDefinition } from './app/actionRegistry'
import { createShortcutManager, SHORTCUT_MANAGER } from './app/shortcuts'
import { AUTH_PRINCIPAL, permissions, type AuthenticatedPrincipal } from './app/auth'

const principal: AuthenticatedPrincipal = {
  id: 'member-1', accountId: 'account-member-1', displayName: '测试成员', role: 'Team Member', organizationId: 'org-1', organization: '测试组织',
  permissions: new Set(Object.values(permissions)),
}

function action(id: string, shortcut: string, execute = vi.fn()): ActionDefinition {
  return { id, label: id, group: '导航', shortcut, execute }
}

describe('M9-F03 interaction kernel', () => {
  it('registers actions, detects shortcut collisions and filters permission-restricted actions', () => {
    const registry = createActionRegistry()
    registry.register(action('nav.one', 'g t'))
    registry.register({ ...action('nav.two', 'g t'), id: 'nav.two', requiredPermission: 'secret:read' })
    expect(registry.conflicts().get('g t')).toEqual(['nav.one', 'nav.two'])
    const context = { router: {} as never, route: {} as never, principal }
    expect(registry.list(context)).toHaveLength(1)
    expect(registry.list(context, true).find(entry => entry.action.id === 'nav.two')?.unavailableReason).toContain('没有执行')
  })

  it('opens with Cmd/Ctrl+K and executes the highlighted action', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/today', name: 'today', component: { template: '<div />' } },
    ] })
    await router.push('/')
    const registry = createActionRegistry()
    const execute = vi.fn(async ({ router: currentRouter }) => { await currentRouter.push({ name: 'today' }) })
    registry.register({ ...action('nav.today', 'g t', execute), label: '打开今日', description: '个人工作摘要' })
    const manager = createShortcutManager({ registry, getContext: () => ({ router, route: router.currentRoute.value, principal }), target: document })
    manager.start()
    const wrapper = mount(CommandPalette, { global: { plugins: [router], provide: { [ACTION_REGISTRY as symbol]: registry, [SHORTCUT_MANAGER as symbol]: manager, [AUTH_PRINCIPAL as symbol]: principal } } })
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', metaKey: true, bubbles: true }))
    await flushPromises()
    expect(document.body.querySelector('[role="dialog"]')?.textContent).toContain('命令面板')
    const actionButton = document.body.querySelector<HTMLButtonElement>('button[aria-label="打开今日"]')
    expect(actionButton).toBeTruthy()
    actionButton?.click()
    await flushPromises()
    await router.isReady()
    expect(execute).toHaveBeenCalledOnce()
    expect(router.currentRoute.value.name).toBe('today')
    manager.stop()
    wrapper.unmount()
  })

  it('runs two-key sequences, expires them, and ignores editable controls', async () => {
    vi.useFakeTimers()
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'home', component: { template: '<div />' } }] })
    const registry = createActionRegistry()
    const execute = vi.fn()
    registry.register(action('nav.home', 'g h', execute))
    const manager = createShortcutManager({ registry, getContext: () => ({ router, route: router.currentRoute.value, principal }), target: document })
    manager.start()
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'g', bubbles: true }))
    expect(manager.state.pending).toEqual(['g'])
    vi.advanceTimersByTime(251)
    expect(manager.state.pending).toEqual([])
    const input = document.createElement('input')
    document.body.append(input)
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'g', bubbles: true }))
    expect(manager.state.pending).toEqual([])
    input.remove()
    manager.stop()
    vi.useRealTimers()
  })
})
