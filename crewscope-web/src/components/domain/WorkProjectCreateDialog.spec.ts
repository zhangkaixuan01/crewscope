import { flushPromises, mount } from '@vue/test-utils'
import WorkProjectCreateDialog from './WorkProjectCreateDialog.vue'

describe('WorkProjectCreateDialog', () => {
  afterEach(() => {
    vi.useRealTimers()
    document.body.innerHTML = ''
  })

  it('checks the normalized key and emits an idempotent create command', async () => {
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
    inputs[0]!.value = 'crew'
    inputs[0]!.dispatchEvent(new Event('input', { bubbles: true }))
    inputs[1]!.value = ' CrewScope Platform '
    inputs[1]!.dispatchEvent(new Event('input', { bubbles: true }))
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()

    expect(checkKey).toHaveBeenCalledWith('CREW', expect.any(AbortSignal))
    document.body.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flushPromises()

    const submission = wrapper.emitted('submit')?.[0]
    expect(submission?.[0]).toEqual({ key: 'CREW', name: 'CrewScope Platform' })
    expect(submission?.[1]).toEqual(expect.any(String))
    await wrapper.setProps({ retryable: true, errorMessage: '最新事实暂时不可用' })
    document.body.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flushPromises()
    expect(wrapper.emitted('submit')?.[1]?.[1]).toBe(submission?.[1])
    wrapper.unmount()
  })

  it('fails closed when the key is already used', async () => {
    vi.useFakeTimers()
    const wrapper = mount(WorkProjectCreateDialog, {
      attachTo: document.body,
      props: {
        teamName: 'Platform Engineering', submitting: false, retryable: false,
        errorMessage: null, checkKey: vi.fn(async () => false),
      },
    })
    const inputs = document.body.querySelectorAll<HTMLInputElement>('input')
    inputs[0]!.value = 'CREW'
    inputs[0]!.dispatchEvent(new Event('input', { bubbles: true }))
    inputs[1]!.value = 'CrewScope'
    inputs[1]!.dispatchEvent(new Event('input', { bubbles: true }))
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()

    expect(document.body.textContent).toContain('这个 Key 已被当前 Team 使用')
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
