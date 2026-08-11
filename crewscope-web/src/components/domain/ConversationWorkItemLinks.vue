<script setup lang="ts">
import { ArrowRight, Link2, MessageSquare, RefreshCw } from '@lucide/vue'
import type {
  ConversationWorkItemAssociation,
} from '../../domains/conversation/workItemLinkGateway'
import type { ConversationWorkItemLinkPhase } from '../../domains/conversation/workItemLinkStore'
import BaseButton from '../base/BaseButton.vue'
import StatusBadge from '../base/StatusBadge.vue'

withDefaults(defineProps<{
  phase: ConversationWorkItemLinkPhase
  associations: ConversationWorkItemAssociation[]
  direction: 'conversation' | 'work-item'
  errorMessage?: string | null
}>(), { errorMessage: null })

const emit = defineEmits<{
  open: [association: ConversationWorkItemAssociation]
  retry: []
}>()

function originLabel(origin: ConversationWorkItemAssociation['origin']): string {
  return ({
    TASK_INTENT_CONFIRMATION: '任务提案确认',
    MANUAL: '手动关联',
    WORK_ITEM_DISCUSSION: '工作项讨论',
  })[origin]
}
</script>

<template>
  <section
    v-if="phase !== 'idle' && (phase !== 'empty' || direction === 'work-item')"
    class="association-card"
    :aria-label="direction === 'conversation' ? '已确认工作项' : '关联对话'"
  >
    <header>
      <span><Link2 :size="16" aria-hidden="true" /></span>
      <div>
        <p>{{ direction === 'conversation' ? 'Confirmed result' : 'Conversation context' }}</p>
        <h3>{{ direction === 'conversation' ? '已确认工作项' : '关联对话' }}</h3>
      </div>
      <StatusBadge v-if="phase === 'ready'" tone="success">{{ associations.length }} 个事实</StatusBadge>
    </header>

    <div v-if="phase === 'loading'" class="association-state" role="status">
      <RefreshCw :size="14" class="spin" aria-hidden="true" />正在同步关联事实…
    </div>
    <div v-else-if="phase === 'error'" class="association-state error" role="alert">
      <span>{{ errorMessage }}</span><button type="button" @click="emit('retry')">重试</button>
    </div>
    <p v-else-if="phase === 'empty'" class="association-state">当前账号没有可发现的关联对话。</p>
    <ul v-else>
      <li v-for="association in associations" :key="association.linkId">
        <span class="association-icon">
          <MessageSquare v-if="direction === 'work-item'" :size="15" aria-hidden="true" />
          <Link2 v-else :size="15" aria-hidden="true" />
        </span>
        <div>
          <strong>{{ direction === 'conversation' ? association.workItem.key : association.conversation.title }}</strong>
          <span>{{ direction === 'conversation' ? association.workItem.title : `${association.conversation.visibility} Conversation` }}</span>
          <small>{{ originLabel(association.origin) }}</small>
        </div>
        <StatusBadge>{{ direction === 'conversation' ? association.workItem.status : association.conversation.status }}</StatusBadge>
        <BaseButton
          variant="ghost"
          size="small"
          :aria-label="direction === 'conversation' ? `查看工作项 ${association.workItem.key}` : `返回对话 ${association.conversation.title}`"
          @click="emit('open', association)"
        >
          {{ direction === 'conversation' ? '查看执行' : '返回对话' }}<ArrowRight :size="13" aria-hidden="true" />
        </BaseButton>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.association-card { max-width: 740px; padding: 14px; margin: 0 auto 14px; border: 1px solid #cfe2d5; border-radius: var(--cs-radius-md); background: linear-gradient(135deg, #fff 0%, var(--cs-brand-50) 100%); box-shadow: 0 6px 18px rgb(27 75 48 / 5%); }
.association-card > header { display: grid; grid-template-columns: 32px 1fr auto; align-items: center; gap: 9px; margin-bottom: 10px; }.association-card > header > span { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 10px; background: var(--cs-brand-100); color: var(--cs-brand-700); }.association-card header p { margin: 0 0 2px; color: var(--cs-brand-700); font-size: 8px; font-weight: 800; letter-spacing: .07em; text-transform: uppercase; }.association-card h3 { margin: 0; font-size: 12px; }
.association-card ul { display: grid; gap: 7px; padding: 0; margin: 0; list-style: none; }.association-card li { display: grid; grid-template-columns: 30px minmax(0, 1fr) auto auto; align-items: center; gap: 8px; padding: 9px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: rgb(255 255 255 / 82%); }.association-icon { display: grid; width: 30px; height: 30px; place-items: center; border-radius: 9px; background: var(--cs-surface-subtle); color: var(--cs-brand-700); }.association-card li strong, .association-card li div > span, .association-card li small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.association-card li strong { color: var(--cs-text); font-size: 10px; }.association-card li div > span { margin-top: 2px; color: var(--cs-text-secondary); font-size: 9px; }.association-card li small { margin-top: 2px; color: var(--cs-text-muted); font-size: 8px; }.association-state { display: flex; min-height: 42px; align-items: center; justify-content: center; gap: 7px; margin: 0; color: var(--cs-text-muted); font-size: 9px; }.association-state.error { color: var(--cs-danger); }.association-state button { border: 0; background: transparent; color: inherit; font-weight: 750; text-decoration: underline; cursor: pointer; }.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 600px) { .association-card li { grid-template-columns: 30px minmax(0, 1fr) auto; }.association-card li > button { grid-column: 2 / -1; justify-self: stretch; }.association-card > header { grid-template-columns: 32px 1fr; }.association-card > header > :deep(.status-badge) { grid-column: 2; justify-self: start; } }
</style>
