import { computed, type ComputedRef } from 'vue'
import type { useScopeStore } from '../domains/scope/store'
import { principalDisplayName, principalNameDirectory } from '../domains/scope/memberDirectory'
import type { ConversationMessage, ConversationParticipant, ConversationSummary } from '../domains/conversation/types'

/**
 * Display-name derivations for the conversation faces: the participant roster labels, the
 * message-author line, and own-message detection — all resolved against the Team directory.
 */
export function useConversationParticipants(options: {
  principal: { id: string } | null | undefined
  selected: ComputedRef<ConversationSummary | null>
  activeParticipants: ComputedRef<ConversationParticipant[]>
  scopeStore: ReturnType<typeof useScopeStore>
}) {
  const { principal, selected, activeParticipants, scopeStore } = options
  const principalNames = computed(() => principalNameDirectory(scopeStore.state.members))

  function participantName(participant: ConversationParticipant): string {
    return participant.displayName?.trim()
      || principalDisplayName(
        principalNames.value,
        participant.principalId,
        participant.role === 'AGENT' ? 'Agent' : '成员',
      )
  }

  function participantRole(participant: ConversationParticipant): string {
    if (participant.role === 'OWNER') return '对话创建者 · OWNER'
    if (participant.role === 'MEMBER') return '团队参与者 · MEMBER'
    if (participant.principalType === 'PERSONAL_AGENT') {
      const ownerName = participant.ownerDisplayName?.trim()
        || (participant.ownerPrincipalId
          ? principalDisplayName(principalNames.value, participant.ownerPrincipalId)
          : null)
      return ownerName ? `${ownerName}的 Personal Agent · AGENT` : 'Personal Agent · AGENT'
    }
    if (participant.principalType === 'TEAM_AGENT') return '团队 Agent · AGENT'
    return '执行 Agent · AGENT'
  }

  function participantKind(participant: ConversationParticipant): string {
    if (participant.role !== 'AGENT') return '团队成员'
    return ({
      PERSONAL_AGENT: '个人 Agent',
      TEAM_AGENT: '团队 Agent',
      SPECIALIST_AGENT: '专项 Agent',
      USER: '执行主体',
      SERVICE: '服务主体',
    })[participant.principalType]
  }

  function messageAuthor(message: ConversationMessage): string {
    if (message.type === 'SYSTEM_NOTICE') return 'CrewScope'
    if (message.authorPrincipalId === principal?.id) return '你'
    if (message.authorPrincipalId === selected.value?.personalAgentPrincipalId) return 'Personal Agent'
    const participant = activeParticipants.value.find(item => item.principalId === message.authorPrincipalId)
    return participant
      ? participantName(participant)
      : message.authorPrincipalId
        ? principalDisplayName(principalNames.value, message.authorPrincipalId)
        : '未知成员'
  }

  function isOwnMessage(message: ConversationMessage): boolean {
    return message.type === 'USER_MESSAGE' && message.authorPrincipalId === principal?.id
  }

  return { principalNames, participantName, participantRole, participantKind, messageAuthor, isOwnMessage }
}
