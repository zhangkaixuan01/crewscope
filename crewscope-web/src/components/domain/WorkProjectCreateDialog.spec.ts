import { flushPromises, mount } from '@vue/test-utils'
import WorkProjectCreateDialog from './WorkProjectCreateDialog.vue'

describe('WorkProjectCreateDialog', () => {
  afterEach(() => {
    vi.useRealTimers()
    document.body.innerHTML = ''
  })

  it('requires only a name and preserves the same key for explicit retry', async () => {
    vi.useFakeTimers()
    const checkKey = vi.fn(async () => true)
    const wrapper = mount(WorkProjectCreateDialog, {
      attachTo: document.body,
      props: {
        teamName: 'Platform Engineering', submitting: false, retryable: false,
        errorMessage: null, checkKey,
      },
    })
    const inputs = document.body.querySelectorAll<HTMLInputElement>('input')

    await wrapper.getComponent(WorkProjectCreateDialog).vm.$nextTick()
    inputs[0]!.value = ' CrewScope Platform '
    inputs[0]!.dispatchEvent(new Event('input', { bubbles: true }))
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()

    expect(checkKey).not.toHaveBeenCalled()
    expect(inputs).toHaveLength(1)
    document.body.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flushPromises()

    const submission = wrapper.emitted('submit')?.[0]
    expect(submission?.[0]).toEqual({ name: 'CrewScope Platform' })
    expect(submission?.[1]).toEqual(expect.any(String))
    await wrapper.setProps({ retryable: true, errorMessage: '最新事实暂时不可用' })
    document.body.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flushPromises()
    expect(wrapper.emitted('submit')?.[1]?.[1]).toBe(submission?.[1])
    wrapper.unmount()
  })

  it('does not submit a blank project name', async () => {
    vi.useFakeTimers()
    const wrapper = mount(WorkProjectCreateDialog, {
      attachTo: document.body,
      props: {
        teamName: 'Platform Engineering', submitting: false, retryable: false,
        errorMessage: null, checkKey: vi.fn(async () => false),
      },
    })
    const inputs = document.body.querySelectorAll<HTMLInputElement>('input')
    inputs[0]!.value = '   '
    inputs[0]!.dispatchEvent(new Event('input', { bubbles: true }))
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()

    expect(document.body.textContent).toContain('项目代号由系统自动生成')
    expect(document.body.querySelector<HTMLButtonElement>('button[type="submit"]')?.disabled).toBe(true)
    wrapper.unmount()
  })

  it('restores focus to the opener when the dialog closes', async () => {
    const opener = document.createElement('button')
    document.body.append(opener)
    opener.focus()
    const wrapper = mount(WorkProjectCreateDialog, {
      attachTo: document.body,
      props: {
        teamName: 'Platform Engineering', submitting: false, retryable: false,
        errorMessage: null, checkKey: vi.fn(async () => true),
      },
    })
    await flushPromises()

    wrapper.unmount()

    expect(document.activeElement).toBe(opener)
  })
})
