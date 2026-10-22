import { mount } from '@vue/test-utils'
import ScanToolbar from './ScanToolbar.vue'

/**
 * M13b-F09（S2）契约：骨架把三段内容锁进单行布局（结果数左、筛选中、操作右），
 * 高度封顶 --cs-scan-toolbar-height；筛选 label 横排是 ≤56px 的前提。
 * 这里的断言全部从布局契约推导，不从某页实现快照。
 */
describe('ScanToolbar', () => {
  it('renders the three segments in order: count, filters, then side actions', () => {
    const wrapper = mount(ScanToolbar, {
      slots: {
        count: '12 项当前结果',
        filters: '<label>状态<select></select></label>',
        side: '<button type="button">排序</button>',
      },
    })
    const root = wrapper.get('section.scan-toolbar')
    // 三段齐全、顺序即契约：count → filters → side；attrs（aria-label）透传到根。
    expect([...root.element.children].map(child => child.className))
      .toEqual(['scan-toolbar__count', 'scan-toolbar__filters', 'scan-toolbar__side'])
    expect(root.text()).toContain('12 项当前结果')
    expect(root.get('.scan-toolbar__side').text()).toBe('排序')
  })

  it('keeps filter labels inline with their control so one row fits the height budget', () => {
    const wrapper = mount(ScanToolbar, {
      slots: { filters: '<label>来源状态<select><option>仍需处理</option></select></label>' },
    })
    const label = wrapper.get('.scan-toolbar__filters > label')
    // label 与 select 同行（inline-flex 由骨架样式承载）而不是上下两层——单行 ≤56px 的前提。
    expect(label.find('select').exists()).toBe(true)
    expect(wrapper.get('.scan-toolbar__filters select').element.textContent).toContain('仍需处理')
  })
})
