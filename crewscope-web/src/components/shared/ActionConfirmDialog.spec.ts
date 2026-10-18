import { mount } from '@vue/test-utils'
import ActionConfirmDialog from './ActionConfirmDialog.vue'

describe('ActionConfirmDialog', () => {
  it('wires aria-describedby to the impact statement and submits with a null reason in plain mode', async () => {
    const wrapper = mounted({ impact: '发布会产出新的不可变修订并更新生效指针。' })

    // 契约：alertdialog 的 aria-describedby 指向承载影响说明的元素（id 由 useId 生成，不断言具体值）。
    const describedId = wrapper.get('[role="alertdialog"]').attributes('aria-describedby')
    expect(wrapper.get(`[id="${describedId}"]`).text()).toContain('生效指针')

    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('confirm')?.[0]).toEqual([null])
  })

  it('forwards a trimmed reason and blocks an over-limit one', async () => {
    const wrapper = mounted({ mode: 'reason', reasonMaxlength: 200 })

    await wrapper.get('textarea').setValue('  弃用，改用 v3  ')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('confirm')?.[0]).toEqual(['弃用，改用 v3'])

    await wrapper.get('textarea').setValue('长'.repeat(201))
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('confirm')).toHaveLength(1)
  })

  it('sends a null reason when the reason field is left blank', async () => {
    const wrapper = mounted({ mode: 'reason', reasonMaxlength: 200 })

    await wrapper.get('form').trigger('submit')

    expect(wrapper.emitted('confirm')?.[0]).toEqual([null])
  })

  it('keeps the destructive button gated behind the checkbox in confirm mode', async () => {
    const wrapper = mounted({ mode: 'confirm', confirmCheckboxLabel: '我确认删除此条目并保留墓碑' })

    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('confirm')).toBeUndefined()

    await wrapper.get('input[type="checkbox"]').setValue(true)
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('confirm')?.[0]).toEqual([null])
  })

  it('emits cancel on Escape through the topmost-modal guard', async () => {
    const wrapper = mounted({})

    await wrapper.get('form').trigger('keydown', { key: 'Escape' })

    expect(wrapper.emitted('cancel')).toHaveLength(1)
  })
})

function mounted(overrides: Record<string, unknown> = {}) {
  return mount(ActionConfirmDialog, {
    props: {
      heading: '确认操作',
      impact: '影响说明。',
      confirmLabel: '确认',
      submitting: false,
      errorMessage: null,
      ...overrides,
    },
    attachTo: document.body,
    global: { stubs: { teleport: true } },
  })
}
