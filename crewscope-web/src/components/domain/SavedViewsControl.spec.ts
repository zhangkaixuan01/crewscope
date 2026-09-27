import { mount } from '@vue/test-utils'
import { activateF05Identity, clearF05UserData, readF05Views } from '../../app/f05Storage'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SavedViewsControl from './SavedViewsControl.vue'

const owner = { accountId: 'account-1', principalId: 'principal-1', organizationId: 'org-1', teamId: 'team-1' }

beforeEach(() => {
  activateF05Identity('account-1')
})

afterEach(() => {
  clearF05UserData()
  localStorage.clear()
  sessionStorage.clear()
})

async function openControl(filters: Record<string, string> = { status: 'IN_PROGRESS' }) {
  const wrapper = mount(SavedViewsControl, {
    props: { routeName: 'work' as const, filters, owner },
    attachTo: document.body,
  })
  await wrapper.get('button[aria-haspopup="menu"]').trigger('click')
  return wrapper
}

describe('SavedViewsControl', () => {
  it('saves the committed filters under a name and keeps them across mounts', async () => {
    const wrapper = await openControl()
    await wrapper.get('input[aria-label="视图名称"]').setValue('进行中的事')
    await wrapper.get('button[type="submit"]').trigger('submit')

    expect(wrapper.text()).toContain('进行中的事')
    expect(wrapper.text()).toContain('当前')
    expect(wrapper.text()).toContain('本机存储，不跨设备同步')

    const stored = readF05Views({ ...owner, projectId: null })
    expect(stored[0]).toMatchObject({ name: '进行中的事', routeName: 'work', filters: { status: 'IN_PROGRESS' } })
    wrapper.unmount()
  })

  it('applies a stored definition, and 默认 resets to nothing', async () => {
    const wrapper = await openControl()
    await wrapper.get('input[aria-label="视图名称"]').setValue('进行中的事')
    await wrapper.get('button[type="submit"]').trigger('submit')

    await wrapper.get('button[aria-label="应用视图 进行中的事"]').trigger('click')
    expect(wrapper.emitted('apply')![0]).toEqual([expect.objectContaining({ name: '进行中的事' })])
    // Applying closes the menu; re-opening for the 默认 reset.
    await wrapper.get('button[aria-haspopup="menu"]').trigger('click')
    const defaultButton = wrapper.findAll('[role="menuitem"]').find(button => button.text().includes('默认'))!
    await defaultButton.trigger('click')
    expect(wrapper.emitted('apply')![1]).toEqual([null])
    wrapper.unmount()
  })

  it('updates a view to the current filters and pins it as the default', async () => {
    const wrapper = await openControl()
    await wrapper.get('input[aria-label="视图名称"]').setValue('进行中的事')
    await wrapper.get('button[type="submit"]').trigger('submit')

    await wrapper.get('button[aria-label="置顶 进行中的事"]').trigger('click')
    expect(wrapper.get('button[aria-label="取消置顶 进行中的事"]').isVisible()).toBe(true)

    await wrapper.setProps({ filters: { status: 'OPEN', type: 'DEFECT' } })
    await wrapper.get('button[aria-label="把 进行中的事 更新为当前筛选"]').trigger('click')
    const stored = readF05Views({ ...owner, projectId: null })
    expect(stored[0]).toMatchObject({ name: '进行中的事', pinned: true, filters: { status: 'OPEN', type: 'DEFECT' } })

    await wrapper.get('button[aria-label="删除视图 进行中的事"]').trigger('click')
    expect(readF05Views({ ...owner, projectId: null })).toEqual([])
    expect(wrapper.text()).toContain('还没有保存的视图')
    wrapper.unmount()
  })

  it('reports a failing local storage instead of pretending the view was saved', async () => {
    const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('full', 'QuotaExceededError') })
    const wrapper = await openControl()

    await wrapper.get('input[aria-label="视图名称"]').setValue('存不下的视图')
    await wrapper.get('button[type="submit"]').trigger('submit')

    expect(wrapper.get('[role="alert"]').text()).toContain('本机存储不可用或已满')
    expect(wrapper.text()).not.toContain('存不下的视图')
    spy.mockRestore()
    wrapper.unmount()
  })

  it('consumes Escape while open so a drawer behind it stays put', async () => {
    const wrapper = await openControl()
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[role="menu"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
