import { mount } from '@vue/test-utils'
import ConversationParticipantsPanel from './ConversationParticipantsPanel.vue'
import type { ConversationParticipant } from '../../domains/conversation/types'

const participant: ConversationParticipant = {
  id: 'participant-1', conversationId: 'conversation-1', principalId: 'agent-1', teamMemberId: null,
  displayName: 'Personal Agent', principalType: 'PERSONAL_AGENT', ownerPrincipalId: 'member-1', ownerDisplayName: '成员 A',
  role: 'AGENT', status: 'ACTIVE', joinedByPrincipalId: 'member-1', joinedAt: '2026-08-24T01:00:00Z', leftAt: null, version: 1,
}

describe('ConversationParticipantsPanel', () => {
  it('renders participant identity and delegates pane toggling', async () => {
    const wrapper = mount(ConversationParticipantsPanel, {
      props: {
        selected: true, collapsed: false, participants: [participant],
        participantName: item => item.displayName, participantRole: item => item.role, participantKind: () => 'Personal Agent',
      },
    })
    expect(wrapper.text()).toContain('Personal Agent')
    await wrapper.get('.pane-collapse').trigger('click')
    expect(wrapper.emitted('toggle')).toHaveLength(1)
  })
})
