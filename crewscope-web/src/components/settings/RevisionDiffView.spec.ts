import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import RevisionDiffView from './RevisionDiffView.vue'

describe('RevisionDiffView', () => {
  it('renders nested field changes and unchanged revisions', () => {
    const wrapper = mount(RevisionDiffView, { props: { before: { generateOptions: { temperature: 0.2 }, enabled: true }, after: { generateOptions: { temperature: 0.7 }, enabled: true } } })
    expect(wrapper.text()).toContain('generateOptions.temperature')
    expect(wrapper.text()).toContain('1 项变更')
  })
})
