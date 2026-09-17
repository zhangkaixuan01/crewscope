import { mount } from '@vue/test-utils'
import AgentConfigurationHistoryPanel from './AgentConfigurationHistoryPanel.vue'

const history = [{
  revision: 2,
  previousRevision: 1,
  templateKey: 'coding-specialist',
  templateVersion: 3,
  templateContentHash: 'a'.repeat(64),
  personalBinding: null,
  teamBinding: null,
  configurationHash: 'b'.repeat(64),
  createdAt: '2026-08-24T01:00:00Z',
  createdBy: 'member-1',
}]

describe('AgentConfigurationHistoryPanel', () => {
  it('selects a revision and delegates pagination without owning store state', async () => {
    const wrapper = mount(AgentConfigurationHistoryPanel, {
      props: {
        historyResource: { phase: 'ready', value: history, errorMessage: null, errorStatus: null, nextOffset: 1, loadingMore: false },
        history,
        selectedRevision: null,
        currentRevision: 2,
      },
    })

    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('selectRevision')).toEqual([[2]])
    await wrapper.findAll('button')[1]!.trigger('click')
    expect(wrapper.emitted('loadMore')).toHaveLength(1)
  })

  it('reports loading failures through the retry event', async () => {
    const wrapper = mount(AgentConfigurationHistoryPanel, {
      props: {
        historyResource: { phase: 'error', value: null, errorMessage: 'history unavailable', errorStatus: 503, nextOffset: null, loadingMore: false },
        history: [],
        selectedRevision: null,
        currentRevision: null,
      },
    })
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })
})
