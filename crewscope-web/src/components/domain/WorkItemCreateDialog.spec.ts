import { flushPromises, mount } from '@vue/test-utils'
import WorkItemCreateDialog from './WorkItemCreateDialog.vue'

describe('WorkItemCreateDialog', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('validates and emits a normalized WorkItem command', async () => {
    const wrapper = mount(WorkItemCreateDialog, {
      attachTo: document.body,
      props: { projectKey: 'CREW', initialKey: 'CREW-3', submitting: false, errorMessage: null },
    })
    const inputs = document.body.querySelectorAll<HTMLInputElement>('input')
    inputs[1]!.value = '  Fix login flow  '
    inputs[1]!.dispatchEvent(new Event('input', { bubbles: true }))
    const form = document.body.querySelector<HTMLFormElement>('form')!
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flushPromises()

    expect(wrapper.emitted('submit')?.[0]?.[0]).toEqual({
      key: 'CREW-3', type: 'TASK', title: 'Fix login flow', description: null,
      priority: 'MEDIUM', labels: [], dueAt: null,
    })
    wrapper.unmount()
  })

  it('rejects a key outside the current project and restores opener focus', async () => {
    const opener = document.createElement('button')
    document.body.append(opener)
    opener.focus()
    const wrapper = mount(WorkItemCreateDialog, {
      attachTo: document.body,
      props: { projectKey: 'CREW', initialKey: 'OTHER-1', submitting: false, errorMessage: null },
    })
    const form = document.body.querySelector<HTMLFormElement>('form')!
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flushPromises()

    expect(wrapper.emitted('submit')).toBeUndefined()
    expect(document.body.textContent).toContain('请填写有效标题')
    wrapper.unmount()
    expect(document.activeElement).toBe(opener)
  })
})
