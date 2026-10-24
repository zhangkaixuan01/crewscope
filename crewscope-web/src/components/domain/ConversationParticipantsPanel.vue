<script setup lang="ts">
import { Bot } from '@lucide/vue'
import type { ConversationParticipant } from '../../domains/conversation/types'
import StatusBadge from '../base/StatusBadge.vue'

defineProps<{
  selected: boolean
  collapsed: boolean
  participants: ConversationParticipant[]
  /** Bare-UUID ids currently viewing the conversation (M11-F01 presence, optional). */
  presentPrincipalIds?: ReadonlySet<string>
  participantName: (participant: ConversationParticipant) => string
  participantRole: (participant: ConversationParticipant) => string
  participantKind: (participant: ConversationParticipant) => string
}>()

const emit = defineEmits<{ toggle: [] }>()
</script>

<template>
  <aside class="panel participant-panel" :class="{ collapsed }" aria-label="对话参与者">
    <header>
      <p class="eyebrow">Current facts</p>
      <h2>参与者</h2>
      <span>{{ selected ? `${participants.length} 个当前主体` : '选择对话后查看' }}</span>
      <button class="pane-collapse touch-target" type="button" aria-label="折叠参与者面板" @click="emit('toggle')">{{ collapsed ? '展开参与者' : '折叠参与者' }}</button>
    </header>
    <ul v-if="selected">
      <li v-for="participant in participants" :key="participant.id">
        <span :class="{ agent: participant.role === 'AGENT' }">
          <Bot v-if="participant.role === 'AGENT'" :size="15" />
          <template v-else>{{ participantName(participant).slice(0, 1) }}</template>
        </span>
        <div>
          <strong>
            {{ participantName(participant) }}
            <span
              v-if="presentPrincipalIds?.has(participant.principalId)"
              class="participant-online"
              role="img"
              aria-label="正在查看此对话"
            />
          </strong>
          <small>{{ participantRole(participant) }}</small>
        </div>
        <StatusBadge :tone="participant.role === 'AGENT' ? 'agent' : 'neutral'">{{ participantKind(participant) }}</StatusBadge>
      </li>
    </ul>
  </aside>
</template>

<style scoped>
.participant-panel { min-height: 640px; overflow: hidden; }.participant-panel header { padding: var(--cs-space-20); border-bottom: 1px solid var(--cs-border); }.participant-panel h2 { margin-bottom: var(--cs-space-4); font-size: var(--cs-text-md); }.participant-panel header > span { color: var(--cs-text-muted); font-size: var(--cs-text-sm); }.participant-panel ul { padding: var(--cs-space-8); margin: 0; list-style: none; }.participant-panel li { display: grid; grid-template-columns: 34px 1fr auto; align-items: center; gap: var(--cs-space-8); padding: var(--cs-space-12); border-bottom: 1px solid var(--cs-border); }.participant-panel li:last-child { border: 0; }.participant-panel li > span:first-child { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 50%; background: var(--cs-surface-accent-strong); color: var(--cs-text-brand); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }.participant-panel li > span.agent { background: var(--cs-agent-soft); color: var(--cs-agent); }.participant-panel li strong, .participant-panel li small { display: block; }.participant-panel li strong { font-size: var(--cs-text-sm); }.participant-online { display: inline-block; width: 8px; height: 8px; margin-left: var(--cs-space-4); border-radius: 50%; background: var(--cs-success); vertical-align: middle; }.participant-panel li small { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
/* The ≤1280 grid-column belongs to the page (it re-places this panel into a bottom row); here
   only the roster re-flow is ours. */
@media (max-width: 1280px) { .participant-panel ul { display: grid; grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 900px) { .participant-panel { display: none; } }
@media (max-width: 767px) { .participant-panel { min-height: calc(100dvh - 208px); }.participant-panel.collapsed { display: none; } }
</style>
