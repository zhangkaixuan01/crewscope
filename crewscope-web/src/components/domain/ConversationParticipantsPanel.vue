<script setup lang="ts">
import { Bot, X } from '@lucide/vue'
import type { ConversationParticipant } from '../../domains/conversation/types'
import StatusBadge from '../base/StatusBadge.vue'

defineProps<{
  selected: boolean
  participants: ConversationParticipant[]
  /** Bare-UUID ids currently viewing the conversation (M11-F01 presence, optional). */
  presentPrincipalIds?: ReadonlySet<string>
  participantName: (participant: ConversationParticipant) => string
  participantRole: (participant: ConversationParticipant) => string
  participantKind: (participant: ConversationParticipant) => string
}>()

const emit = defineEmits<{ close: [] }>()
</script>

<template>
  <!-- M14 B1：参与者退出布局网格，改为覆盖在消息区右缘的浮层（不挤压消息列）。
       定位锚是 conversation-detail（position: relative）；开/关与点外/Esc 关闭由页面持有。 -->
  <aside class="participant-panel" aria-label="对话参与者">
    <header>
      <h2>参与者</h2>
      <span>{{ selected ? `${participants.length} 个当前主体` : '选择对话后查看' }}</span>
      <button class="panel-close touch-target" type="button" aria-label="关闭参与者面板" @click="emit('close')">
        <X :size="16" aria-hidden="true" />
      </button>
    </header>
    <ul v-if="selected">
      <li v-for="participant in participants" :key="participant.id">
        <span :class="{ agent: participant.role === 'AGENT' }">
          <Bot v-if="participant.role === 'AGENT'" :size="14" />
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
/* 浮层壳：右侧滑出、覆盖消息区但不占布局宽度。 */
.participant-panel { position: absolute; z-index: 5; top: 0; right: 0; bottom: 0; display: flex; flex-direction: column; width: 300px; max-width: calc(100% - 32px); overflow: hidden; border-left: 1px solid var(--cs-border); background: var(--cs-surface); box-shadow: var(--cs-shadow-raised); }
.participant-panel h2 { margin-bottom: var(--cs-space-4); font-size: var(--cs-text-md); }
.participant-panel header { display: flex; align-items: baseline; gap: var(--cs-space-8); padding: var(--cs-space-16); border-bottom: 1px solid var(--cs-border); }
.participant-panel header > span { flex: 1 1 auto; color: var(--cs-text-muted); font-size: var(--cs-text-sm); }
.panel-close { display: grid; flex: 0 0 auto; width: 28px; height: 28px; align-self: center; place-items: center; border: 1px solid var(--cs-border); border-radius: 7px; background: var(--cs-surface); color: var(--cs-text-secondary); cursor: pointer; }
.panel-close:hover { background: var(--cs-surface-subtle); color: var(--cs-text); }
.participant-panel ul { flex: 1 1 auto; overflow-y: auto; padding: var(--cs-space-8); margin: 0; list-style: none; }
.participant-panel li { display: grid; grid-template-columns: 34px minmax(0, 1fr) auto; align-items: center; gap: var(--cs-space-8); padding: var(--cs-space-12); }
.participant-panel li:last-child { border: 0; }
.participant-panel li > span:first-child { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 50%; background: var(--cs-surface-accent-strong); color: var(--cs-text-brand); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.participant-panel li > span.agent { background: var(--cs-agent-soft); color: var(--cs-agent); }
.participant-panel li strong, .participant-panel li small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.participant-panel li strong { font-size: var(--cs-text-sm); }
.participant-online { display: inline-block; width: 8px; height: 8px; margin-left: var(--cs-space-4); border-radius: 50%; background: var(--cs-success); vertical-align: middle; }
.participant-panel li small { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
</style>
