<script setup lang="ts">
import { ChevronRight } from '@lucide/vue'
import type { WorkspaceAction } from '../../domains/task/workspaceAction'
import BaseButton from '../base/BaseButton.vue'

/**
 * The single primary action strip (contract §4.7): one headline, one button, one link at most.
 *
 * RESUME is safe to fire directly — it continues the same AgentRun. Operations that need a reason or
 * a revision (PAUSE / CANCEL / RETRY) never bypass the control panel's confirmation; the strip only
 * takes the member there.
 */
defineProps<{
  action: WorkspaceAction
  disabled?: boolean
}>()

const emit = defineEmits<{
  operation: [operation: 'RESUME']
  locate: [anchor: 'ws-execution' | 'ws-review']
}>()

const toneByState: Record<WorkspaceAction['state'], 'neutral' | 'warning' | 'danger' | 'info' | 'success'> = {
  FORBIDDEN: 'neutral',
  HISTORY_VIEW: 'neutral',
  COMMAND_PENDING: 'info',
  VERSION_CONFLICT: 'warning',
  SELECTION_REQUIRED: 'warning',
  CONFIGURATION_MISSING: 'warning',
  EXECUTING: 'info',
  AWAITING_DECISION: 'warning',
  IDLE: 'neutral',
}
</script>

<template>
  <section class="workspace-action" :data-action-state="action.state" aria-label="当前主动作">
    <div class="workspace-action__facts">
      <strong>{{ action.headline }}</strong>
      <span v-if="action.detail">{{ action.detail }}</span>
    </div>
    <div class="workspace-action__controls">
      <BaseButton
        v-if="action.operation === 'RESUME'"
        size="small"
        :disabled="disabled"
        @click="emit('operation', 'RESUME')"
      >恢复执行</BaseButton>
      <BaseButton
        v-else-if="action.operation"
        variant="secondary"
        size="small"
        :disabled="disabled"
        @click="emit('locate', 'ws-execution')"
      >{{ action.operation === 'PAUSE' ? '暂停' : action.operation === 'CANCEL' ? '取消' : '重试' }}</BaseButton>
      <a
        v-if="action.anchor"
        class="workspace-action__link"
        :href="`#${action.anchor}`"
        @click.prevent="emit('locate', action.anchor!)"
      >前往{{ action.anchor === 'ws-review' ? '审查与交付' : '执行控制' }}<ChevronRight :size="13" aria-hidden="true" /></a>
    </div>
  </section>
</template>

<style scoped>
.workspace-action { display: flex; align-items: center; justify-content: space-between; gap: var(--cs-space-12); padding: var(--cs-space-8) var(--cs-space-12); border: 1px solid var(--cs-border-accent); border-radius: 9px; background: linear-gradient(145deg, var(--cs-surface-accent), var(--cs-surface) 72%); }
.workspace-action__facts { min-width: 0; }
.workspace-action__facts strong { display: block; color: var(--cs-text-brand); font-size: var(--cs-text-sm); }
.workspace-action__facts span { display: block; margin-top: var(--cs-space-2); color: var(--cs-text-muted); font-size: var(--cs-text-xs); line-height: var(--cs-leading-normal); }
.workspace-action__controls { display: flex; flex: 0 0 auto; align-items: center; gap: var(--cs-space-8); }
.workspace-action__link { display: inline-flex; align-items: center; gap: var(--cs-space-2); color: var(--cs-text-brand); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); text-decoration: none; text-underline-offset: 3px; }
.workspace-action__link:hover { text-decoration: underline; }
@media (max-width: 767px) { .workspace-action { align-items: stretch; flex-direction: column; } .workspace-action__controls { justify-content: flex-end; } }
</style>
