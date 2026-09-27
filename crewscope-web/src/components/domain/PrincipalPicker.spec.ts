import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import PrincipalPicker from './PrincipalPicker.vue'
import type { PrincipalDirectoryGateway } from '../../domains/principal/gateway'
import type { PrincipalDirectoryQuery } from '../../domains/principal/types'
import type { PrincipalEntry, PrincipalPage } from '../../domains/principal/types'

const scope = { organizationId: '00000000-0000-0000-0000-000000000001', teamId: '00000000-0000-0000-0000-000000000201' }

const linYue: PrincipalEntry = {
  principalId: '00000000-0000-0000-0000-000000000101',
  kind: 'USER',
  displayName: '林悦',
  status: 'ACTIVE',
  roles: ['MEMBER_MANAGE'],
}
const linYueNamesake: PrincipalEntry = {
  principalId: '00000000-0000-0000-0000-000000000109',
  kind: 'AGENT',
  displayName: '林悦',
  status: 'ACTIVE',
  roles: [],
}
const codingAgent: PrincipalEntry = {
  principalId: '00000000-0000-0000-0000-000000000301',
  kind: 'AGENT',
  displayName: 'Coding Agent',
  status: 'SUSPENDED',
  roles: [],
}

/**
 * Serves the directory like the server does: the kind filter runs before pagination,
 * so a cursor page only ever contains entries the request asked for.
 */
function gateway(catalog: PrincipalEntry[] = [linYue, linYueNamesake, codingAgent]) {
  const calls: PrincipalDirectoryQuery[] = []
  const search = vi.fn(async (query: PrincipalDirectoryQuery): Promise<PrincipalPage> => {
    calls.push(structuredClone(query))
    const filtered = catalog.filter(entry => !query.types || query.types.includes(entry.kind))
    if (!query.after) return { items: filtered.slice(0, 2), nextOffset: null, nextCursor: filtered.length > 2 ? 'page-2' : null }
    return { items: filtered.slice(2), nextOffset: null, nextCursor: null }
  })
  return { gateway: { search } satisfies PrincipalDirectoryGateway, search, calls }
}

async function openPicker(overrides: Record<string, unknown> = {}) {
  const stub = gateway(overrides.catalog as PrincipalEntry[] | undefined)
  delete overrides.catalog
  const wrapper = mount(PrincipalPicker, {
    props: { scope, modelValue: null, label: '负责人', gateway: stub.gateway, ...overrides },
  })
  await wrapper.find('input').trigger('focus')
  await Promise.resolve()
  await wrapper.vm.$nextTick()
  return { wrapper, ...stub }
}

describe('PrincipalPicker', () => {
  it('never asks for an identifier and searches the directory on focus', async () => {
    const { wrapper, search } = await openPicker()

    expect(wrapper.html()).not.toContain('UUID')
    expect(wrapper.html()).not.toContain('Principal ID')
    expect(search).toHaveBeenCalledTimes(1)
    expect(search.mock.calls[0]![0]).toMatchObject({ ...scope, limit: 20 })
    expect(wrapper.findAll('[role="option"]')).toHaveLength(2)
  })

  it('emits the resolved identifier rather than surfacing it to the member', async () => {
    const { wrapper } = await openPicker()

    await wrapper.findAll('[role="option"]')[0]!.trigger('click')

    expect(wrapper.emitted('update:modelValue')).toEqual([[linYue.principalId]])
    await wrapper.setProps({ modelValue: linYue.principalId })
    expect(wrapper.text()).toContain('林悦')
    expect(wrapper.text()).not.toContain(linYue.principalId)
  })

  it('sends the kind filter before pagination and keeps walking the filtered set', async () => {
    const { wrapper, search, calls } = await openPicker({ kind: 'AGENT' })

    expect(calls[0]!.types).toEqual(['AGENT'])
    const options = wrapper.findAll('[role="option"]')
    expect(options).toHaveLength(2)
    expect(options[0]!.text()).toContain('林悦')
    expect(options[1]!.text()).toContain('Coding Agent')
    // A non-ACTIVE subject stays visible but is labelled, so a failed assignment is predictable.
    expect(options[1]!.text()).toContain('已暂停')
    // The whole filtered set fit one page; no continuation is offered.
    expect(wrapper.find('.principal-more').exists()).toBe(false)
  })

  it('appends the next directory page under the same committed query', async () => {
    const { wrapper, calls } = await openPicker()

    await wrapper.get('.principal-more').trigger('click')
    const names = wrapper.findAll('[role="option"]').map(option => option.text())
    expect(names).toHaveLength(3)
    expect(names.some(option => option.includes('Coding Agent'))).toBe(true)
    expect(wrapper.find('.principal-more').exists()).toBe(false)
    // The continuation repeats the committed text, it never starts a new query.
    expect(calls[1]!.q).toBeUndefined()
    expect(calls[1]!.after).toBe('page-2')
  })

  it('replaces the chosen subject without clearing it first', async () => {
    const { wrapper } = await openPicker()
    await wrapper.findAll('[role="option"]')[0]!.trigger('click')
    await wrapper.setProps({ modelValue: linYue.principalId })
    expect(wrapper.text()).toContain('林悦')

    await wrapper.get('button[aria-label="更换负责人"]').trigger('click')
    expect(wrapper.find('input').exists()).toBe(true)
    expect(wrapper.emitted('update:modelValue')).toHaveLength(1)

    // Escape during a replacement keeps the previous subject; nothing is emitted.
    await wrapper.find('input').trigger('keydown', { key: 'Escape' })
    expect(wrapper.text()).toContain('林悦')
    expect(wrapper.emitted('update:modelValue')).toHaveLength(1)

    await wrapper.get('button[aria-label="更换负责人"]').trigger('click')
    await wrapper.findAll('[role="option"]')[1]!.trigger('click')
    expect(wrapper.emitted('update:modelValue')!.at(-1)).toEqual([linYueNamesake.principalId])
    expect(wrapper.text()).toContain('Agent')
  })

  it('keeps the keyboard path complete and IME confirmation inert', async () => {
    const { wrapper } = await openPicker()
    const input = wrapper.find('input')

    await input.trigger('keydown', { key: 'ArrowDown' })
    await input.trigger('keydown', { key: 'Enter', isComposing: true })
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()

    await input.trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('update:modelValue')).toEqual([[linYueNamesake.principalId]])
  })

  it('consumes Escape while its listbox is open and yields it once closed', async () => {
    // Attached to the real document so the key can actually bubble, like it does in a drawer.
    const container = document.createElement('div')
    document.body.appendChild(container)
    const stub = gateway()
    const wrapper = mount(PrincipalPicker, {
      attachTo: container,
      props: { scope, modelValue: null, label: '负责人', gateway: stub.gateway },
    })
    await wrapper.find('input').trigger('focus')
    await wrapper.vm.$nextTick()
    const element = wrapper.find('input').element
    const escapes: string[] = []
    const onDocument = (event: KeyboardEvent): void => { escapes.push(event.key) }
    document.addEventListener('keydown', onDocument)

    try {
      // Open listbox: the picker keeps the key, a drawer behind it must stay untouched.
      element.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
      await wrapper.vm.$nextTick()
      expect(escapes).toEqual([])
      expect(wrapper.find('[role="listbox"]').exists()).toBe(false)

      // Closed: Escape belongs to whatever layer is behind the picker.
      element.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
      expect(escapes).toEqual(['Escape'])
    } finally {
      document.removeEventListener('keydown', onDocument)
      wrapper.unmount()
      container.remove()
    }
  })

  it('marks the field as a combobox wired to its own listbox', async () => {
    const { wrapper } = await openPicker()

    const input = wrapper.find('input')
    expect(input.attributes('role')).toBe('combobox')
    expect(input.attributes('aria-expanded')).toBe('true')
    const listboxId = input.attributes('aria-controls')
    expect(wrapper.find(`#${listboxId}`).attributes('role')).toBe('listbox')
    expect(input.attributes('aria-activedescendant')).toBe(wrapper.findAll('[role="option"]')[0]!.attributes('id'))
    expect(wrapper.find(`#${input.attributes('aria-describedby')}`).exists()).toBe(true)
  })

  it('distinguishes namesakes through the kind and role subline', async () => {
    const { wrapper } = await openPicker()

    const options = wrapper.findAll('[role="option"]')
    expect(options[0]!.text()).toContain('成员 · MEMBER_MANAGE')
    expect(options[1]!.text()).toContain('Agent')
  })

  it('drops another team\'s candidates when the scope switches', async () => {
    const { wrapper } = await openPicker()
    expect(wrapper.findAll('[role="option"]')).toHaveLength(2)

    await wrapper.setProps({ scope: { ...scope, teamId: '00000000-0000-0000-0000-000000000299' } })

    expect(wrapper.find('[role="listbox"]').exists()).toBe(false)
    expect(wrapper.findAll('[role="option"]')).toHaveLength(0)
    // Re-focusing restarts the session against the new scope.
    await wrapper.find('input').trigger('focus')
    await Promise.resolve()
    await wrapper.vm.$nextTick()
    expect(wrapper.findAll('[role="option"]')).toHaveLength(2)
  })

  it('explains a directory failure and offers a retry instead of falling back to free text', async () => {
    const failing = { search: vi.fn(async () => { throw new Error('directory down') }) }
    const wrapper = mount(PrincipalPicker, {
      props: { scope, modelValue: null, label: '负责人', gateway: failing as unknown as PrincipalDirectoryGateway },
    })
    await wrapper.find('input').trigger('focus')
    await Promise.resolve()
    await wrapper.vm.$nextTick()

    const state = wrapper.find('[role="alert"]')
    expect(state.text()).toContain('暂时无法加载团队成员')
    expect(wrapper.find('input[type="text"], input:not([type])').exists()).toBe(true)
  })

  it('clears the selection back to nothing', async () => {
    const { wrapper } = await openPicker({ modelValue: null })
    await wrapper.findAll('[role="option"]')[0]!.trigger('click')
    await wrapper.setProps({ modelValue: linYue.principalId })

    await wrapper.get('button[aria-label="清除已选择的负责人"]').trigger('click')

    expect(wrapper.emitted('update:modelValue')!.at(-1)).toEqual([null])
  })
})
