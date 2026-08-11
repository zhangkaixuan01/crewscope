import { mount } from '@vue/test-utils'
import SafeMarkdown from './SafeMarkdown.vue'

describe('SafeMarkdown', () => {
  it('renders Markdown while keeping raw HTML inert', () => {
    const wrapper = mount(SafeMarkdown, { props: { content: '**安全文本** <img src=x onerror=alert(1)>' } })

    expect(wrapper.find('strong').text()).toBe('安全文本')
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>')
  })

  it('allows safe links and removes executable protocols', () => {
    const wrapper = mount(SafeMarkdown, { props: { content: '[官网](https://crewscope.dev) [危险](javascript:alert(1))' } })
    const safeLink = wrapper.find('a')

    expect(safeLink.attributes()).toMatchObject({ href: 'https://crewscope.dev', target: '_blank', rel: 'noopener noreferrer' })
    expect(wrapper.findAll('a')).toHaveLength(1)
    expect(wrapper.text()).toContain('[危险](javascript:alert(1))')
  })
})
