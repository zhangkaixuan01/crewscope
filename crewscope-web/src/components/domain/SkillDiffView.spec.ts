import { mount } from '@vue/test-utils'
import SkillDiffView from './SkillDiffView.vue'

describe('SkillDiffView', () => {
  it('renders a single unified column with added and removed markers', () => {
    const wrapper = mount(SkillDiffView, {
      props: { before: 'alpha\nbeta', after: 'alpha\nBETA\ngamma', beforeLabel: '已保存草稿', afterLabel: '未保存草稿' },
    })

    const lines = wrapper.findAll('.skill-diff__line')
    expect(lines).toHaveLength(4)
    expect(lines[1]!.classes()).toContain('skill-diff__line--removed')
    expect(lines[2]!.classes()).toContain('skill-diff__line--added')
    expect(wrapper.text()).toContain('-1 行')
    expect(wrapper.text()).toContain('+2 行')
  })

  it('hides the diff entirely for an identical pair — the legal rollback shape', () => {
    const wrapper = mount(SkillDiffView, {
      props: { before: 'same', after: 'same', beforeLabel: 'a', afterLabel: 'b' },
    })

    expect(wrapper.find('.skill-diff__added').exists()).toBe(false)
    expect(wrapper.find('.skill-diff__removed').exists()).toBe(false)
    expect(wrapper.text()).toContain('same')
  })

  it('marks a collapsed diff instead of pretending to be minimal', () => {
    const before = Array.from({ length: 1001 }, (_unused, index) => `b-${index}`).join('\n')
    const after = Array.from({ length: 1001 }, (_unused, index) => `a-${index}`).join('\n')
    const wrapper = mount(SkillDiffView, {
      props: { before, after, beforeLabel: 'a', afterLabel: 'b' },
    })

    expect(wrapper.text()).toContain('已折叠为全量变更对照')
    expect(wrapper.get('.skill-diff__lines').attributes('data-collapsed')).toBe('true')
  })
})
