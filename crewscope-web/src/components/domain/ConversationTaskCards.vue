<script setup lang="ts">
import { Bot, CircleUserRound, Clock3, ExternalLink, Radio, RefreshCw } from '@lucide/vue'
import type { SemanticTone } from '../base/types'
import type { TaskLiveState, TaskPhase } from '../../domains/task/store'
import type { TaskAssociationSummary, TaskStatus } from '../../domains/task/types'
import { principalDisplayName, type PrincipalNameDirectory } from '../../domains/scope/memberDirectory'
import BaseButton from '../base/BaseButton.vue'
import StatusBadge from '../base/StatusBadge.vue'
import StatePanel from '../feedback/StatePanel.vue'

defineProps<{
  phase: TaskPhase
  associations: TaskAssociationSummary[]
  liveTasks: Readonly<Record<string, TaskLiveState>>
  errorMessage: string | null
  currentPrincipalId: string
  principalNames: PrincipalNameDirectory
}>()

defineEmits<{
  openTask: [association: TaskAssociationSummary]
  openWorkItem: [association: TaskAssociationSummary]
  retry: []
}>()

const statusLabels: Record<TaskStatus, string> = {
  CREATED: '已创建', ACTIVE: '执行中', WAITING: '等待中', COMPLETED: '已完成',
  FAILED: '失败', CANCELLED: '已取消',
}

function statusTone(status: TaskStatus): SemanticTone {
  if (status === 'COMPLETED') return 'success'
  if (status === 'WAITING') return 'warning'
  if (status === 'FAILED' || status === 'CANCELLED') return 'danger'
  return status === 'ACTIVE' ? 'agent' : 'neutral'
}

function executionLabel(association: TaskAssociationSummary): string {
  const task = association.task
  return task.currentAttempt === null
    ? '等待首个 Attempt'
    : `Attempt ${task.currentAttempt} · ${task.currentExecutionStatus ?? 'UNKNOWN'}`
}

function ownerName(
  association: TaskAssociationSummary,
  currentPrincipalId: string,
  principalNames: PrincipalNameDirectory,
): string {
  const owner = association.task.ownerPrincipalId
  if (!owner) return '未记录 Owner'
  return owner === currentPrincipalId ? '你' : principalDisplayName(principalNames, owner)
}

function originLabel(origin: string): string {
  return ({
    SOURCE: '来自当前对话',
    CONVERSATION_SOURCE: '来自当前对话',
    WORK_ITEM_ROOT: '通过关联工作项',
    MANUAL: '手动关联',
    EXPLICIT: '显式关联',
  } as Record<string, string>)[origin] ?? '关联 Task'
}

function liveLabel(live: TaskLiveState | undefined): string {
  if (!live) return ''
  if (live.phase === 'connected') return live.projectionGap ? '实时 · 正在校准' : '实时'
  if (live.phase === 'error') return '实时连接不可用'
  return live.phase === 'reconnecting' ? '正在重连' : '正在连接'
}

function displayDate(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false })
    .format(new Date(value))
}
</script>

<template>
  <section
    v-if="associations.length > 0 || phase === 'loading' || phase === 'error'"
    class="conversation-tasks"
    aria-labelledby="conversation-task-heading"
    data-testid="conversation-task-cards"
  >
    <header class="conversation-tasks__header">
      <div>
        <p>Durable execution</p>
        <h3 id="conversation-task-heading">关联 Task <span v-if="associations.length">{{ associations.length }}</span></h3>
      </div>
      <span>服务端事实</span>
    </header>

    <StatePanel
      v-if="phase === 'loading' && associations.length === 0"
      state="loading"
      title="正在恢复关联 Task"
      description="Task 卡片独立于对话流，从耐久关联事实恢复。"
    />
    <StatePanel
      v-else-if="phase === 'error' && associations.length === 0"
      state="error"
      title="无法加载关联 Task"
      :description="errorMessage ?? undefined"
      @retry="$emit('retry')"
    />

    <div v-else class="conversation-tasks__list">
      <article v-for="association in associations" :key="association.task.id" class="conversation-task-card" :data-task-id="association.task.id">
        <div class="conversation-task-card__icon"><Bot :size="16" aria-hidden="true" /></div>
        <div class="conversation-task-card__body">
          <div class="conversation-task-card__title">
            <strong>{{ association.task.objective }}</strong>
            <StatusBadge :tone="statusTone(association.task.status)" dot>{{ statusLabels[association.task.status] }}</StatusBadge>
          </div>
          <div class="conversation-task-card__facts">
            <span><CircleUserRound :size="12" aria-hidden="true" />{{ ownerName(association, currentPrincipalId, principalNames) }}</span>
            <span><Clock3 :size="12" aria-hidden="true" />{{ executionLabel(association) }}</span>
            <span v-if="liveTasks[association.task.id]" class="live-fact" :class="liveTasks[association.task.id]?.phase">
              <Radio :size="11" aria-hidden="true" />{{ liveLabel(liveTasks[association.task.id]) }}
            </span>
          </div>
          <p v-if="association.task.currentWaitingReason" class="conversation-task-card__waiting">
            等待原因 · {{ association.task.currentWaitingReason }}
          </p>
          <p class="conversation-task-card__origin">{{ originLabel(association.origin) }} · {{ displayDate(association.associatedAt) }}</p>
        </div>
        <div class="conversation-task-card__actions">
          <BaseButton size="small" @click="$emit('openTask', association)">查看 Task<ExternalLink :size="12" /></BaseButton>
          <BaseButton size="small" variant="ghost" @click="$emit('openWorkItem', association)">工作项</BaseButton>
        </div>
      </article>
      <p v-if="errorMessage" class="conversation-tasks__error" role="alert">
        {{ errorMessage }}
        <button type="button" @click="$emit('retry')"><RefreshCw :size="11" />重新同步</button>
      </p>
    </div>
  </section>
</template>

<style scoped>
.conversation-tasks { max-width: 740px; margin: 0 auto 12px; overflow: hidden; border: 1px solid var(--cs-brand-200); border-radius: var(--cs-radius-md); background: rgb(255 255 255 / 92%); box-shadow: 0 5px 18px rgb(21 35 29 / 4%); }.conversation-tasks__header { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 10px 12px; border-bottom: 1px solid var(--cs-border); background: var(--cs-brand-50); }.conversation-tasks__header p, .conversation-tasks__header h3 { margin: 0; }.conversation-tasks__header p { color: var(--cs-brand-600); font-size: 8px; font-weight: 800; letter-spacing: .08em; text-transform: uppercase; }.conversation-tasks__header h3 { margin-top: 2px; font-size: 11px; }.conversation-tasks__header h3 span { color: var(--cs-text-muted); font-weight: 600; }.conversation-tasks__header > span { color: var(--cs-text-muted); font-size: 8px; }.conversation-tasks :deep(.state-panel) { min-height: 120px; border: 0; border-radius: 0; }.conversation-tasks__list { display: grid; gap: 1px; background: var(--cs-border); }.conversation-task-card { display: grid; grid-template-columns: 32px minmax(0, 1fr) auto; align-items: start; gap: 9px; padding: 11px 12px; background: var(--cs-surface); }.conversation-task-card__icon { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 9px; background: var(--cs-agent-soft); color: var(--cs-agent); }.conversation-task-card__body { min-width: 0; }.conversation-task-card__title { display: flex; min-width: 0; align-items: flex-start; justify-content: space-between; gap: 10px; }.conversation-task-card__title strong { overflow: hidden; font-size: 10px; line-height: 1.4; text-overflow: ellipsis; white-space: nowrap; }.conversation-task-card__facts { display: flex; flex-wrap: wrap; gap: 5px 11px; margin-top: 5px; color: var(--cs-text-muted); font-size: 8px; }.conversation-task-card__facts span { display: inline-flex; align-items: center; gap: 4px; }.live-fact { color: var(--cs-success); }.live-fact.connecting, .live-fact.reconnecting { color: var(--cs-warning); }.live-fact.error { color: var(--cs-danger); }.conversation-task-card__waiting { width: fit-content; margin: 6px 0 0; padding: 3px 6px; border-radius: 6px; background: var(--cs-warning-soft); color: #7c4a12; font-size: 8px; }.conversation-task-card__origin { margin: 6px 0 0; color: var(--cs-text-muted); font-size: 8px; }.conversation-task-card__actions { display: flex; align-items: center; gap: 3px; }.conversation-tasks__error { display: flex; align-items: center; justify-content: center; gap: 8px; padding: 8px; margin: 0; background: #fff7f6; color: var(--cs-danger); font-size: 8px; }.conversation-tasks__error button { display: inline-flex; align-items: center; gap: 3px; color: inherit; font-size: inherit; font-weight: 750; cursor: pointer; }
@media (max-width: 767px) { .conversation-task-card { grid-template-columns: 30px minmax(0, 1fr); padding: 10px; }.conversation-task-card__icon { width: 30px; height: 30px; }.conversation-task-card__actions { grid-column: 2; }.conversation-task-card__actions :deep(button:first-child) { flex: 1; }.conversation-task-card__title { align-items: flex-start; }.conversation-task-card__title strong { white-space: normal; } }
</style>
