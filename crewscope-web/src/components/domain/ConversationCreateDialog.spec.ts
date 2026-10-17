import { mount } from '@vue/test-utils'
import ConversationCreateDialog from './ConversationCreateDialog.vue'

/** BaseDialog 把外壳 Teleport 到 body，断言一律走 document 查询；Esc 是 document 级监听。 */
function inDialog(selector: string): HTMLElement {
  const element = document.querySelector<HTMLElement>(`.base-dialog ${selector}`)
  if (!element) throw new Error(`missing ${selector} inside .base-dialog`)
  return element
}

describe('ConversationCreateDialog', () => {
  it('focuses the title input and emits the selected visibility on submit', async () => {
    const wrapper = mount(ConversationCreateDialog, { attachTo: document.body, props: { scope: null } })
    const input = inDialog('input[placeholder*="规划"]')

    await new Promise(resolve => setTimeout(resolve, 0))
    expect(document.activeElement).toBe(input)
    ;(input as HTMLInputElement).value = '规划接入'
    input.dispatchEvent(new Event('input'))
    inDialog('input[type="radio"][value="TEAM"]').click()
    inDialog('form').dispatchEvent(new Event('submit'))

    expect(wrapper.emitted('submit')).toEqual([[{ title: '规划接入', visibility: 'TEAM' }]])
    wrapper.unmount()
  })

  it('emits close from cancel and Escape while exposing errors and pending state', async () => {
    const wrapper = mount(ConversationCreateDialog, {
      attachTo: document.body,
      props: { scope: null, error: '创建失败，请重试', pending: true },
    })

    expect(inDialog('[role="alert"]').textContent).toContain('创建失败，请重试')
    expect(inDialog('button[type="submit"]').hasAttribute('disabled')).toBe(true)
    inDialog('button[aria-label="关闭新建对话"]').click()
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))

    expect(wrapper.emitted('close')).toHaveLength(2)
  })
})
