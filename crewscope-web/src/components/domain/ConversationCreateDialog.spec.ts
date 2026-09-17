import { mount } from '@vue/test-utils'
import ConversationCreateDialog from './ConversationCreateDialog.vue'

describe('ConversationCreateDialog', () => {
  it('focuses the title input and emits the selected visibility on submit', async () => {
    const wrapper = mount(ConversationCreateDialog, { attachTo: document.body })
    const input = wrapper.get<HTMLInputElement>('input[placeholder*="规划"]')

    await new Promise(resolve => setTimeout(resolve, 0))
    expect(document.activeElement).toBe(input.element)
    await input.setValue('规划接入')
    await wrapper.get('input[type="radio"][value="TEAM"]').setValue(true)
    await wrapper.get('form').trigger('submit')

    expect(wrapper.emitted('submit')).toEqual([[{ title: '规划接入', visibility: 'TEAM' }]])
  })

  it('emits close from cancel and Escape while exposing errors and pending state', async () => {
    const wrapper = mount(ConversationCreateDialog, {
      attachTo: document.body,
      props: { error: '创建失败，请重试', pending: true },
    })

    expect(wrapper.get('[role="alert"]').text()).toContain('创建失败，请重试')
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    await wrapper.get('button[aria-label="关闭新建对话"]').trigger('click')
    await wrapper.get('.dialog-backdrop').trigger('keydown', { key: 'Escape' })

    expect(wrapper.emitted('close')).toHaveLength(2)
  })
})
