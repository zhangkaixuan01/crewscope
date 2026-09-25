import { flushPromises, mount } from '@vue/test-utils'
import WorkItemCreateDialog from './WorkItemCreateDialog.vue'

describe('WorkItemCreateDialog', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('validates and emits a normalized WorkItem command', async () => {
    const wrapper = mount(WorkItemCreateDialog, {
      attachTo: document.body,
      props: { projectKey: 'CREW', scope: null, initialKey: 'CREW-3', submitting: false, errorMessage: null },
    })
    const inputs = document.body.querySelectorAll<HTMLInputElement>('input')
    inputs[0]!.value = '  Fix login flow  '
    inputs[0]!.dispatchEvent(new Event('input', { bubbles: true }))
    const form = document.body.querySelector<HTMLFormElement>('form')!
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flushPromises()

    expect(wrapper.emitted('submit')?.[0]?.[0]).toEqual({
      type: 'TASK', title: 'Fix login flow', description: null,
      priority: 'MEDIUM', labels: [], dueAt: null,
    })
    wrapper.unmount()
  })

  it('rejects an empty title and restores opener focus without a key field', async () => {
    const opener = document.createElement('button')
    document.body.append(opener)
    opener.focus()
    const wrapper = mount(WorkItemCreateDialog, {
      attachTo: document.body,
      props: { projectKey: 'CREW', scope: null, initialKey: 'OTHER-1', submitting: false, errorMessage: null },
    })
    const form = document.body.querySelector<HTMLFormElement>('form')!
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flushPromises()

    expect(wrapper.emitted('submit')).toBeUndefined()
    expect(document.body.textContent).toContain('请填写 1–240 字的标题')
    expect(document.body.textContent).not.toContain('工作项 Key')
    wrapper.unmount()
    expect(document.activeElement).toBe(opener)
  })
})
