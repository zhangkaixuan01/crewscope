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
.participant-panel { min-height: 640px; overflow: hidden; }.participant-panel h2 { margin-bottom: var(--cs-space-4); font-size: var(--cs-text-md); }.participant-panel header > span { color: var(--cs-text-muted); font-size: var(--cs-text-sm); }.participant-panel header { padding: var(--cs-space-20); }.participant-panel ul { padding: var(--cs-space-8); margin: 0; list-style: none; }.participant-panel li { display: grid; grid-template-columns: 34px minmax(0, 1fr) auto; align-items: center; gap: var(--cs-space-8); padding: var(--cs-space-12); }.participant-panel li:last-child { border: 0; }.participant-panel li > span:first-child { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 50%; background: var(--cs-surface-accent-strong); color: var(--cs-text-brand); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }.participant-panel li > span.agent { background: var(--cs-agent-soft); color: var(--cs-agent); }.participant-panel li strong, .participant-panel li small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.participant-panel li strong { font-size: var(--cs-text-sm); }.participant-online { display: inline-block; width: 8px; height: 8px; margin-left: var(--cs-space-4); border-radius: 50%; background: var(--cs-success); vertical-align: middle; }.participant-panel li small { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
/* M13f：折叠竖条（44px）收敛规则归本组件——此前写在页面 scoped 里选不中组件内部元素，
   从未生效：折叠态一直渲染完整 header（标题+计数+按钮），文字越出面板裁剪盒。
   与左侧 conversation-list-panel 的折叠行为对齐：只剩撑满竖条的折叠钮。 */
.participant-panel.collapsed > :not(header) { display: none; }
.participant-panel.collapsed > header { padding: var(--cs-space-4); border-bottom: 0; }
.participant-panel.collapsed > header > :not(.pane-collapse) { display: none; }
/* M13g：「展开参与者」整词横排在 44px 竖条内必然溢出（5 字 × 12px ≈ 60px）。竖排沿轨
   书写（CJK 轨式折叠惯例），文案完整可读，整轨宽度即触达面积；min-height 保证命中区。 */
.participant-panel.collapsed .pane-collapse { width: 100%; min-width: 0; min-height: 120px; padding: var(--cs-space-8) 0; writing-mode: vertical-rl; }
/* The ≤1280 grid-column belongs to the page (it re-places this panel into a bottom row); here
   only the roster re-flow is ours. */
@media (max-width: 1280px) { .participant-panel ul { display: grid; grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 900px) { .participant-panel { display: none; } }
@media (max-width: 767px) { .participant-panel { min-height: calc(100dvh - 208px); }.participant-panel.collapsed { display: none; } }
</style>
