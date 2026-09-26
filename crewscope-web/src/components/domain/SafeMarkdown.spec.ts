import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
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

  // R40: a table survives with its header/row semantics intact — the assertion is the structure,
  // not the flattened text, so a renderer that drops th/td cannot pass by keeping the words.
  it('renders Markdown tables with header and cell semantics', () => {
    const wrapper = mount(SafeMarkdown, {
      props: { content: '| 接口 | 用途 |\n| --- | --- |\n| /work | 工作台 |\n| /conversation | 对话 |' },
    })
    const table = wrapper.get('table')

    expect(table.find('thead').exists()).toBe(true)
    expect(table.findAll('thead th').map(cell => cell.text())).toEqual(['接口', '用途'])
    expect(table.findAll('tbody td').map(cell => cell.text())).toEqual(['/work', '工作台', '/conversation', '对话'])
  })

  it('keeps tables inert against injected handlers and scripts', () => {
    const wrapper = mount(SafeMarkdown, {
      props: { content: '<table><tr><th onmouseover=alert(1)>表头</th></tr></table><script>alert(1)</script>' },
    })

    // html:false keeps raw markup as text; no table, script or attribute may survive as an element.
    expect(wrapper.find('table').exists()).toBe(false)
    expect(wrapper.find('script').exists()).toBe(false)
    expect(wrapper.find('[onmouseover]').exists()).toBe(false)
  })

  it('attaches a copy control to fenced code blocks and copies the code text on click', async () => {
    const wrapper = mount(SafeMarkdown, {
      props: { content: '```\nconst answer = 42\n```' },
      attachTo: document.body,
    })
    const button = wrapper.get('.safe-markdown__code-copy')

    expect(button.text()).toBe('复制代码')
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.assign(navigator, { clipboard: { writeText } })

    await button.trigger('click')
    expect(writeText).toHaveBeenCalledWith('const answer = 42\n')
    await Promise.resolve()
    await nextTick()
    expect(button.text()).toBe('已复制')

    wrapper.unmount()
  })
})
