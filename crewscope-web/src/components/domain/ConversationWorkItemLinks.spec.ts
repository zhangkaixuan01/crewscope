import { mount } from '@vue/test-utils'
import { fixtureConversationWorkItemAssociation } from '../../test/conversationWorkItemFixtures'
import ConversationWorkItemLinks from './ConversationWorkItemLinks.vue'

describe('ConversationWorkItemLinks', () => {
  it('opens the exact server WorkItem fact from Conversation mode', async () => {
    const wrapper = mount(ConversationWorkItemLinks, {
      props: { phase: 'ready', associations: [fixtureConversationWorkItemAssociation], direction: 'conversation' },
    })

    expect(wrapper.text()).toContain('已确认工作项')
    expect(wrapper.text()).toContain('CRW-18')
    await wrapper.get('button[aria-label="查看工作项 CRW-18"]').trigger('click')
    expect(wrapper.emitted('open')?.[0]).toEqual([fixtureConversationWorkItemAssociation])
  })

  it('opens only the visible Conversation fact from Control mode', async () => {
    const wrapper = mount(ConversationWorkItemLinks, {
      props: { phase: 'ready', associations: [fixtureConversationWorkItemAssociation], direction: 'work-item' },
    })

    expect(wrapper.text()).toContain('规划 GitHub Provider 接入')
    await wrapper.get('button[aria-label^="返回对话"]').trigger('click')
    expect(wrapper.emitted('open')).toBeTruthy()
  })

  it('hides an empty Conversation-side result and offers retry for safe failures', async () => {
    const empty = mount(ConversationWorkItemLinks, {
      props: { phase: 'empty', associations: [], direction: 'conversation' },
    })
    expect(empty.find('section').exists()).toBe(false)

    const failed = mount(ConversationWorkItemLinks, {
      props: { phase: 'error', associations: [], direction: 'work-item', errorMessage: '读取失败' },
    })
    await failed.get('button').trigger('click')
    expect(failed.emitted('retry')).toBeTruthy()
  })
})
