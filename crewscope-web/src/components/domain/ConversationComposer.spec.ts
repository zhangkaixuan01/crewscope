import { mount } from '@vue/test-utils'
import ConversationComposer from './ConversationComposer.vue'

describe('ConversationComposer', () => {
  it('submits trimmed content with Enter and preserves Shift+Enter for a newline', async () => {
    const wrapper = mount(ConversationComposer, { props: { modelValue: '  规划 Provider  ' } })
    const textarea = wrapper.get('textarea')

    await textarea.trigger('keydown', { key: 'Enter', shiftKey: true })
    expect(wrapper.emitted('submit')).toBeUndefined()

    await textarea.trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('submit')).toEqual([['规划 Provider']])
  })

  it('retains long draft input and blocks empty or disabled submission', async () => {
    const draft = '边界'.repeat(1000)
    const wrapper = mount(ConversationComposer, { props: { modelValue: draft } })
    const textarea = wrapper.get('textarea')

    expect(textarea.attributes('maxlength')).toBe('50000')
    expect((textarea.element as HTMLTextAreaElement).value).toBe(draft)
    await wrapper.setProps({ disabled: true })
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('submit')).toBeUndefined()
  })

  it('keeps the draft editable while offline and blocks only submission', async () => {
    const wrapper = mount(ConversationComposer, {
      props: { modelValue: '离线草稿', submitDisabled: true, offline: true },
    })

    expect(wrapper.get('textarea').attributes('disabled')).toBeUndefined()
    expect(wrapper.get('button[type="submit"]').attributes()).toHaveProperty('disabled')
    expect(wrapper.text()).toContain('当前离线，可继续编辑草稿')
    await wrapper.get('textarea').trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('submit')).toBeUndefined()
  })
})
