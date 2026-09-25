<script setup lang="ts">
import { ArrowLeftRight, RefreshCw, X } from '@lucide/vue'
import { computed, nextTick, ref, watch } from 'vue'
import type { TeamMemberSummary } from '../../domains/scope/types'
import { handoverCounts } from '../../domains/scope/types'
import BaseButton from '../base/BaseButton.vue'
import StatusBadge from '../base/StatusBadge.vue'

/**
 * A07 responsibility handover: preview the source member's active assignments, create the
 * job, then process it in place. CONFLICT/DENIED items are terminal per item — the caller
 * resolves them outside this dialog through the normal assignment commands.
 */
const props = defineProps<{
  open: boolean
  source: TeamMemberSummary | null
  candidates: TeamMemberSummary[]
  pending: boolean
  problem: string | null
  job: import('../../domains/scope/types').HandoverJobView | null
  /** Count of the source member's active assignments for the selected role, before any job exists. */
  previewCount: number | null
}>()

const emit = defineEmits<{
  close: []
  preview: [role: string]
  create: [targetPrincipalId: string, role: string]
  process: []
  cancelJob: []
}>()

const dialog = ref<HTMLElement | null>(null)
const selectedRole = ref('OWNER')
const selectedMemberId = ref('')
/** `ResponsibilityRole` on the wire: OWNER / EXECUTOR / REVIEWER (gate vs advisory is server-routed). */
const handoverRoles = ['OWNER', 'EXECUTOR', 'REVIEWER'] as const
const roleLabels: Record<string, string> = {
  OWNER: 'Owner', EXECUTOR: 'Executor', REVIEWER: 'Reviewer',
}
const itemStateLabels: Record<string, string> = { PENDING: '待处理', DONE: '已完成', CONFLICT: '版本冲突', DENIED: '已拒绝' }

const target = computed(() => props.candidates.find(member => member.id === selectedMemberId.value) ?? null)
const counts = computed(() => props.job ? handoverCounts(props.job) : null)

watch(() => props.open, async open => {
  if (!open) return
  selectedMemberId.value = ''
  await nextTick(() => dialog.value?.querySelector<HTMLElement>('select, button:not(:disabled)')?.focus())
})

watch(selectedRole, role => {
  if (props.open && !props.job) emit('preview', role)
})

function close(): void {
  if (props.pending) return
  emit('close')
}

function trap(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    close()
    return
  }
  if (event.key !== 'Tab' || !dialog.value) return
  const items = [...dialog.value.querySelectorAll<HTMLElement>('button:not(:disabled), select')]
  if (!items.length) return
  const first = items[0]!
  const last = items.at(-1)!
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault(); last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault(); first.focus()
  }
}
</script>

<template>
  <div v-if="open && source" class="dialog-backdrop" @mousedown.self="close()">
    <section ref="dialog" class="handover-dialog" role="dialog" aria-modal="true" aria-labelledby="handover-dialog-title" @keydown="trap">
      <p class="eyebrow">Responsibility handover</p>
      <h3 id="handover-dialog-title">交接 {{ source.displayName }} 的责任</h3>

      <template v-if="!job">
        <p class="handover-dialog__hint">先选择要交接的责任角色，确认该角色的在途责任数量，再选择接手成员并创建交接。创建后可以逐项处理，冲突项会保留原状。</p>
        <label for="handover-role">责任角色</label>
        <select id="handover-role" v-model="selectedRole">
          <option v-for="role in handoverRoles" :key="role" :value="role">{{ roleLabels[role] }}</option>
        </select>
        <label for="handover-target">接手成员（须为 ACTIVE 成员）</label>
        <select id="handover-target" v-model="selectedMemberId">
          <option value="">请选择接手成员</option>
          <option v-for="member in candidates" :key="member.id" :value="member.id">{{ member.displayName }}</option>
        </select>
        <p v-if="previewCount !== null" class="handover-dialog__count" role="status">该角色当前有 {{ previewCount }} 项在途责任，创建后会全部纳入交接。</p>
        <p v-else class="handover-dialog__count handover-dialog__count--muted">选择角色后显示在途责任数量。</p>
      </template>

      <template v-else>
        <div class="handover-dialog__summary">
          <StatusBadge tone="neutral" dot>{{ job.status }}</StatusBadge>
          <span>{{ roleLabels[job.role] ?? job.role }} · {{ counts?.done ?? 0 }}/{{ job.items.length }} 项完成</span>
        </div>
        <ul v-if="job.items.length" class="handover-dialog__items">
          <li v-for="item in job.items" :key="item.id">
            <span class="mono" :title="item.workItemId">{{ item.workItemId.slice(0, 8) }}…</span>
            <strong>{{ itemStateLabels[item.state] ?? item.state }}</strong>
            <small v-if="item.state === 'DENIED' && item.errorCode">{{ item.errorCode }}</small>
            <small v-else-if="item.state === 'CONFLICT'">分派版本已变化，需人工处理</small>
          </li>
        </ul>
        <p v-else class="handover-dialog__count">这个角色没有在途责任，无需交接。</p>
        <p v-if="counts && counts.conflict" class="handover-dialog__note">有 {{ counts.conflict }} 项版本冲突、{{ counts.denied }} 项被拒绝：这些项保持原状，请在工作项里逐个处理。</p>
      </template>

      <div v-if="problem" class="handover-dialog__problem" role="alert">{{ problem }}</div>

      <div class="handover-dialog__actions">
        <BaseButton v-if="!job" variant="ghost" :disabled="pending" @click="close()">取消</BaseButton>
        <BaseButton v-if="!job" :loading="pending" :disabled="!target || !selectedRole" @click="emit('create', target!.userPrincipalId, selectedRole)">
          <ArrowLeftRight :size="14" />创建交接
        </BaseButton>
        <template v-else>
          <BaseButton v-if="job.status === 'PENDING' || job.status === 'RUNNING'" variant="ghost" :disabled="pending" @click="emit('cancelJob')">取消剩余项</BaseButton>
          <BaseButton v-if="job.status !== 'COMPLETED' && job.status !== 'CANCELLED'" :loading="pending" @click="emit('process')">
            <RefreshCw :size="14" />处理交接
          </BaseButton>
          <BaseButton v-else variant="ghost" :disabled="pending" @click="close()"><X :size="14" />关闭</BaseButton>
        </template>
      </div>
    </section>
  </div>
</template>

<style scoped>
.dialog-backdrop { position: fixed; z-index: var(--cs-z-dialog); inset: 0; display: grid; place-items: center; padding: var(--cs-space-20); background: var(--cs-scrim); }
.handover-dialog { display: grid; width: min(560px, 100%); max-height: min(80vh, 720px); overflow-y: auto; gap: var(--cs-space-12); padding: var(--cs-space-24); border-radius: 15px; background: var(--cs-surface); box-shadow: var(--cs-shadow-modal); }
.handover-dialog h3 { font-size: var(--cs-text-lg); }
.handover-dialog__hint, .handover-dialog__count { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-sm); line-height: var(--cs-leading-normal); }
.handover-dialog__count--muted { font-size: var(--cs-text-xs); }
.handover-dialog label { color: var(--cs-text-secondary); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.handover-dialog select { min-height: 36px; padding: 0 var(--cs-space-12); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); font-size: var(--cs-text-base); }
.handover-dialog__summary { display: flex; align-items: center; gap: var(--cs-space-12); color: var(--cs-text-secondary); font-size: var(--cs-text-sm); }
.handover-dialog__items { display: grid; gap: var(--cs-space-4); max-height: 260px; margin: 0; padding: 0; list-style: none; overflow-y: auto; }
.handover-dialog__items li { display: grid; grid-template-columns: auto 1fr auto; align-items: center; gap: var(--cs-space-8); padding: var(--cs-space-8) var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: 9px; font-size: var(--cs-text-sm); }
.handover-dialog__items li > span { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.handover-dialog__items li > strong { font-size: var(--cs-text-sm); }
.handover-dialog__items li > small { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.handover-dialog__note { margin: 0; padding: var(--cs-space-8) var(--cs-space-12); border-radius: 8px; background: var(--cs-warning-soft); color: var(--cs-warning); font-size: var(--cs-text-xs); }
.handover-dialog__problem { margin: 0; padding: var(--cs-space-12); border: 1px solid var(--cs-warning-border); border-radius: 9px; background: var(--cs-warning-soft); color: var(--cs-warning); font-size: var(--cs-text-sm); }
.handover-dialog__actions { display: flex; justify-content: flex-end; gap: var(--cs-space-8); }
</style>
