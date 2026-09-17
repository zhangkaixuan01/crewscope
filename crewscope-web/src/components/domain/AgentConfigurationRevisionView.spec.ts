import { mount } from '@vue/test-utils'
import AgentConfigurationRevisionView from './AgentConfigurationRevisionView.vue'

const history = [
  { revision: 1, previousRevision: null, templateKey: 'coding-specialist', templateVersion: 3, templateContentHash: 'a'.repeat(64), personalBinding: null, teamBinding: null, configurationHash: '1'.repeat(64), createdAt: '2026-08-24T01:00:00Z', createdBy: 'member-1' },
  { revision: 2, previousRevision: 1, templateKey: 'coding-specialist', templateVersion: 3, templateContentHash: 'b'.repeat(64), personalBinding: null, teamBinding: null, configurationHash: '2'.repeat(64), createdAt: '2026-08-25T01:00:00Z', createdBy: 'member-1', configuration: { temperature: 0.7 } },
]

describe('AgentConfigurationRevisionView', () => {
  it('renders immutable details and emits coordination events', async () => {
    const wrapper = mount(AgentConfigurationRevisionView, {
      props: {
        selectedRevision: 2,
        currentRevision: 3,
        selectedHistory: history[1],
        history,
        selectedHistoryConfiguration: { temperature: 0.7 },
        selectedPreviousConfiguration: { temperature: 0.2 },
        compareRevision: 1,
        copiedHash: null,
      },
    })
    expect(wrapper.text()).toContain('历史版本不可编辑')
    await wrapper.get('.copy-button').trigger('click')
    expect(wrapper.emitted('copyHash')).toEqual([['2'.repeat(64), 'revision-2']])
    await wrapper.get('select').setValue('')
    expect(wrapper.emitted('updateCompareRevision')).toEqual([[null]])
    await wrapper.get('.base-button').trigger('click')
    expect(wrapper.emitted('returnCurrent')).toEqual([[3]])
  })
})
