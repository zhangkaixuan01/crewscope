import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import CopyButton from './CopyButton.vue'

describe('CopyButton', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    Reflect.deleteProperty(navigator, 'clipboard')
  })

  it('swaps the visible label and announces the copy success', async () => {
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText: vi.fn().mockResolvedValue(undefined) } })
    const wrapper = mount(CopyButton, { props: { value: 'principal-1', label: '复制 Principal ID' } })

    await wrapper.get('button').trigger('click')
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('已复制')
      expect(wrapper.find('[role="status"]').exists()).toBe(true)
    })
  })

  it('states explicitly when neither the API nor the fallback could copy', async () => {
    // jsdom has no clipboard API and execCommand throws, so copy() resolves false —
    // exactly the restricted-browser case the component must not stay silent about.
    const wrapper = mount(CopyButton, { props: { value: 'principal-1', iconOnly: true } })

    await wrapper.get('button').trigger('click')
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('无法复制，请手动选择')
      expect(wrapper.find('[role="alert"]').exists()).toBe(true)
    })
  })
})
