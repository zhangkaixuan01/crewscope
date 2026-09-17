import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import PrincipalPicker from './PrincipalPicker.vue'
import type { PrincipalDirectoryGateway } from '../../domains/principal/gateway'
import type { PrincipalEntry, PrincipalPage } from '../../domains/principal/types'

const scope = { organizationId: '00000000-0000-0000-0000-000000000001', teamId: '00000000-0000-0000-0000-000000000201' }

const linYue: PrincipalEntry = {
  principalId: '00000000-0000-0000-0000-000000000101',
  kind: 'USER',
  displayName: '林悦',
  status: 'ACTIVE',
  roles: ['MEMBER_MANAGE'],
}
const codingAgent: PrincipalEntry = {
  principalId: '00000000-0000-0000-0000-000000000301',
  kind: 'AGENT',
  displayName: 'Coding Agent',
  status: 'SUSPENDED',
  roles: [],
}

function gateway(page: PrincipalPage | Error = { items: [linYue, codingAgent], nextOffset: null }) {
  const search = vi.fn(async (..._args: unknown[]): Promise<PrincipalPage> => {
    if (page instanceof Error) throw page
    return structuredClone(page)
  })
  return { gateway: { search } satisfies PrincipalDirectoryGateway, search }
}

async function openPicker(overrides: Record<string, unknown> = {}) {
  const stub = gateway(overrides.page as PrincipalPage | Error | undefined)
  delete overrides.page
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
    expect(wrapper.findAll('[role="option"]').map(option => option.text())).toHaveLength(2)
  })

  it('emits the resolved identifier rather than surfacing it to the member', async () => {
    const { wrapper } = await openPicker()

    await wrapper.findAll('[role="option"]')[0]!.trigger('click')

    expect(wrapper.emitted('update:modelValue')).toEqual([[linYue.principalId]])
    await wrapper.setProps({ modelValue: linYue.principalId })
    expect(wrapper.text()).toContain('林悦')
    expect(wrapper.text()).not.toContain(linYue.principalId)
  })

  it('restricts the listbox to the requested subject kind', async () => {
    const { wrapper } = await openPicker({ kind: 'AGENT' })

    const options = wrapper.findAll('[role="option"]')
    expect(options).toHaveLength(1)
    expect(options[0]!.text()).toContain('Coding Agent')
    // A non-ACTIVE subject stays visible but is labelled, so a failed assignment is predictable.
    expect(options[0]!.text()).toContain('已暂停')
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

  it('keeps the keyboard path complete', async () => {
    const { wrapper } = await openPicker()
    const input = wrapper.find('input')

    await input.trigger('keydown', { key: 'ArrowDown' })
    await input.trigger('keydown', { key: 'Enter' })

    expect(wrapper.emitted('update:modelValue')).toEqual([[codingAgent.principalId]])
  })

  it('explains a directory failure and offers a retry instead of falling back to free text', async () => {
    const { wrapper, search } = await openPicker({ page: new Error('directory down') })

    const state = wrapper.find('[role="alert"]')
    expect(state.text()).toContain('暂时无法加载团队成员')
    expect(wrapper.find('input[type="text"], input:not([type])').exists()).toBe(true)

    await state.find('button').trigger('click')
    expect(search).toHaveBeenCalledTimes(2)
  })

  it('clears the selection back to nothing', async () => {
    const { wrapper } = await openPicker({ modelValue: null })
    await wrapper.findAll('[role="option"]')[0]!.trigger('click')
    await wrapper.setProps({ modelValue: linYue.principalId })

    await wrapper.find('.principal-chip button').trigger('click')

    expect(wrapper.emitted('update:modelValue')!.at(-1)).toEqual([null])
  })
})
