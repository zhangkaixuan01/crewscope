import { mount } from '@vue/test-utils'
import ConversationComposer from './ConversationComposer.vue'

describe('ConversationComposer', () => {
  it('can receive focus after creation without sending or changing the draft', () => {
    const wrapper = mount(ConversationComposer, { attachTo: document.body, props: { modelValue: '' } })
    wrapper.vm.focus()
    expect(document.activeElement).toBe(wrapper.get('textarea').element)
    expect(wrapper.emitted('submit')).toBeUndefined()
    wrapper.unmount()
  })
  it('submits trimmed content with Enter and preserves Shift+Enter for a newline', async () => {
    const wrapper = mount(ConversationComposer, { props: { modelValue: '  规划 Provider  ' } })
    const textarea = wrapper.get('textarea')

    await textarea.trigger('keydown', { key: 'Enter', shiftKey: true })
    expect(wrapper.emitted('submit')).toBeUndefined()

    await textarea.trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('submit')).toEqual([['规划 Provider']])
  })

  it('submits content containing line breaks without flattening it', async () => {
    const wrapper = mount(ConversationComposer, { props: { modelValue: '  第一行\n第二行  ' } })

    await wrapper.get('textarea').trigger('keydown', { key: 'Enter' })

    expect(wrapper.emitted('submit')).toEqual([['第一行\n第二行']])
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

  it('explains why sending is currently unavailable', () => {
    const wrapper = mount(ConversationComposer, {
      props: { modelValue: '待发送内容', disabled: true, disabledReason: '请先点击“重新连接”' },
    })

    expect(wrapper.get('[id$="-guidance"]').text()).toBe('请先点击“重新连接”')
  })

  it('offers no fake attachments or slash entries', () => {
    const wrapper = mount(ConversationComposer, { props: { modelValue: '' } })
    // Every visible control must work: no "coming soon" attachments, no pseudo slash commands.
    // The preview toggle (R40) is a real control and joins the list.
    expect(wrapper.findAll('button').map(button => button.text())).toEqual(['预览', '发送'])
    expect(wrapper.text()).not.toContain('附件')
    expect(wrapper.text()).not.toContain('Slash')
  })

  it('previews the draft through the same safe Markdown pipeline and keeps the text on toggle back', async () => {
    const wrapper = mount(ConversationComposer, { props: { modelValue: '**要点** | 数字 |\n| --- | --- |\n| 接口 | 2 |' } })
    const toggle = wrapper.get('.composer-preview-toggle')

    expect(toggle.attributes('aria-pressed')).toBe('false')
    await toggle.trigger('click')

    expect(toggle.attributes('aria-pressed')).toBe('true')
    expect(toggle.text()).toContain('编辑')
    // Rendered through SafeMarkdown: table semantics, not flattened text.
    expect(wrapper.get('.composer-preview table thead th').text()).toBe('要点')
    expect(wrapper.find('textarea').exists()).toBe(false)

    await toggle.trigger('click')
    expect(wrapper.find('textarea').exists()).toBe(true)
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('**要点** | 数字 |\n| --- | --- |\n| 接口 | 2 |')
  })

  it('counts characters without inventing a model budget', () => {
    const wrapper = mount(ConversationComposer, { props: { modelValue: '八千'.repeat(2) } })

    // R43: the fixed "预算 32,000" pseudo-fact is gone; only the real character count remains.
    expect(wrapper.get('[id$="-count"]').text()).toBe('4 / 50,000 字符')
    expect(wrapper.text()).not.toContain('预算')
  })
})
