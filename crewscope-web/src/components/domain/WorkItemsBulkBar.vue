<script setup lang="ts">
import { computed, ref } from 'vue'
import { X } from '@lucide/vue'
import {
  summarizeBulkResults,
  workItemBulkOutcomeLabels,
  type WorkItemBulkAssignmentRole,
  type WorkItemBulkProgress,
  type WorkItemBulkRowResult,
} from '../../domains/workitem/bulk'
import type { WorkItemBulkTarget } from '../../domains/workitem/list'
import { workItemResponsibilityRoleLabels, workItemTransitionVariants } from '../../domains/workitem/labels'
import type { WorkItemStatus } from '../../domains/workitem/types'
import BaseButton from '../base/BaseButton.vue'
import BaseTooltip from '../base/BaseTooltip.vue'

/**
 * The batch bar of a WorkItem selection: what is selected, what can be done to it, and what
 * happened.
 *
 * It renders what the rows published and nothing more. A target is here because a selected row
 * offered it as executable, and the button carries the count it would actually apply to — the
 * eligibility ratio — so a member who selects twelve rows and reads 9/12 has already been told what
 * the summary will say. The three rows that cannot are not hidden: they come back named, one by one,
 * with the server's own reason.
 *
 * Nothing is disabled by a rule the member cannot see. The only states that block a control here are
 * offline (stated in the bar) and a missing assignee (stated by the control's own tooltip); a member
 * without the permission for a batch action never sees it offered, and never sees a checkbox that
 * leads nowhere.
 */
const props = withDefaults(defineProps<{
  /** Every selected row, including rows on pages that are no longer loaded. */
  selectedCount: number
  /** The selected rows a batch will act on: loaded, and visible under the current filters. */
  actionableCount: number
  /** How many rows the current result holds — the denominator of 「全选当页」. */
  visibleCount: number
  targets: readonly WorkItemBulkTarget[]
  members: readonly { principalId: string; displayName: string }[]
  progress?: WorkItemBulkProgress | null
  results?: readonly WorkItemBulkRowResult[] | null
  canTransition: boolean
  canAssign: boolean
  online: boolean
}>(), {
  progress: null,
  results: null,
})

const emit = defineEmits<{
  transition: [targetStatus: WorkItemStatus]
  assign: [payload: { role: WorkItemBulkAssignmentRole; actorPrincipalId: string }]
  selectPage: []
  clearSelection: []
  dismissResults: []
}>()

const assignRole = ref<WorkItemBulkAssignmentRole>('EXECUTOR')
const assignee = ref('')

const summary = computed(() => (props.results ? summarizeBulkResults(props.results) : null))
const allVisibleSelected = computed(() => props.visibleCount > 0 && props.actionableCount === props.visibleCount)
/** Rows selected on a page that the current filters no longer show. Named, never silently dropped. */
const offScreenCount = computed(() => Math.max(0, props.selectedCount - props.actionableCount))

const summaryCounts = computed(() => {
  const totals = summary.value
  if (!totals) return ''
  return (Object.keys(workItemBulkOutcomeLabels) as Array<keyof typeof workItemBulkOutcomeLabels>)
    .filter(outcome => totals[outcome] > 0)
    .map(outcome => `${workItemBulkOutcomeLabels[outcome]} ${totals[outcome]}`)
    .join('、')
})

function runTarget(target: WorkItemBulkTarget): void {
  if (!props.online || props.progress) return
  emit('transition', target.targetStatus)
}

function runAssign(): void {
  if (!props.online || props.progress || !assignee.value) return
  emit('assign', { role: assignRole.value, actorPrincipalId: assignee.value })
}
</script>

<template>
  <section class="bulk-bar panel" :class="{ 'bulk-bar--busy': progress }" aria-label="批量操作">
    <div class="bulk-bar__selection" role="toolbar" aria-label="选择">
      <p>
        已选 {{ selectedCount }} 项（跨页保留）
        <span v-if="offScreenCount">，其中 {{ offScreenCount }} 项不在当前结果中，批量操作只作用于当前结果的 {{ actionableCount }} 项</span>
      </p>
      <BaseButton v-if="!allVisibleSelected" size="small" variant="ghost" @click="emit('selectPage')">全选当页 {{ visibleCount }} 项</BaseButton>
      <BaseButton size="small" variant="ghost" @click="emit('clearSelection')">清除选择</BaseButton>
    </div>

    <p v-if="!online" class="bulk-bar__offline" role="status">当前离线，批量操作不可提交；恢复连接后可继续。</p>
    <p v-else-if="progress" class="bulk-bar__progress" role="status">正在逐项执行 {{ progress.completed }} / {{ progress.total }} 项…</p>

    <template v-else>
      <div v-if="canTransition && targets.length" class="bulk-bar__actions" role="group" aria-label="批量改状态">
        <BaseButton
          v-for="target in targets"
          :key="target.targetStatus"
          size="small"
          :variant="workItemTransitionVariants[target.strength]"
          @click="runTarget(target)"
        >{{ target.label }}（{{ target.eligible }}/{{ target.total }}）</BaseButton>
      </div>

      <div v-if="canAssign" class="bulk-bar__assign" role="group" aria-label="批量指派">
        <label><span>责任角色</span>
          <select v-model="assignRole">
            <option value="EXECUTOR">{{ workItemResponsibilityRoleLabels.EXECUTOR }}</option>
            <option value="OWNER">{{ workItemResponsibilityRoleLabels.OWNER }}</option>
          </select>
        </label>
        <label><span>指派给</span>
          <select v-model="assignee">
            <option value="">选择成员</option>
            <option v-for="member in members" :key="member.principalId" :value="member.principalId">{{ member.displayName }}</option>
          </select>
        </label>
        <BaseTooltip v-if="!assignee" text="先选择要指派的成员">
          <BaseButton size="small" disabled>指派</BaseButton>
        </BaseTooltip>
        <BaseButton v-else size="small" @click="runAssign">指派</BaseButton>
      </div>
    </template>

    <section v-if="results" class="bulk-results" aria-live="polite">
      <header>
        <h3>批量结果</h3>
        <p>{{ summaryCounts }}</p>
        <BaseButton size="small" variant="ghost" @click="emit('dismissResults')"><X :size="13" />关闭结果</BaseButton>
      </header>
      <ul>
        <li v-for="result in results" :key="result.id" :class="`bulk-results__item bulk-results__item--${result.outcome}`">
          <span class="mono">{{ result.key }}</span>
          <span class="bulk-results__title">{{ result.title }}</span>
          <span class="bulk-results__outcome">{{ workItemBulkOutcomeLabels[result.outcome] }}</span>
          <span v-if="result.message" class="bulk-results__message">{{ result.message }}</span>
        </li>
      </ul>
    </section>
  </section>
</template>

<style scoped>
.bulk-bar { position: sticky; top: var(--cs-space-8); z-index: var(--cs-z-sticky); display: grid; gap: var(--cs-space-8); padding: var(--cs-space-12) var(--cs-space-16); border-color: var(--cs-border-accent); background: var(--cs-surface-accent); }
.bulk-bar__selection { display: flex; flex-wrap: wrap; align-items: center; gap: var(--cs-space-8); color: var(--cs-text-secondary); font-size: var(--cs-text-xs); }
.bulk-bar__selection p { margin: 0; }
.bulk-bar__offline { margin: 0; color: var(--cs-warning); font-size: var(--cs-text-xs); }
.bulk-bar__progress { margin: 0; color: var(--cs-text-secondary); font-size: var(--cs-text-xs); }
.bulk-bar__actions, .bulk-bar__assign { display: flex; flex-wrap: wrap; align-items: flex-end; gap: var(--cs-space-8); }
.bulk-bar__assign label { display: grid; gap: var(--cs-space-4); color: var(--cs-text-secondary); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }
.bulk-bar__assign select { min-height: var(--cs-density-control-height); padding: 0 var(--cs-space-8); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text); font: var(--cs-text-base) var(--cs-font-sans); }
.bulk-results { display: grid; gap: var(--cs-space-8); padding-top: var(--cs-space-8); border-top: 1px solid var(--cs-border); }
.bulk-results header { display: flex; flex-wrap: wrap; align-items: center; gap: var(--cs-space-8); }
.bulk-results h3 { margin: 0; font-size: var(--cs-text-sm); }
.bulk-results header p { margin: 0; color: var(--cs-text-secondary); font-size: var(--cs-text-xs); }
.bulk-results ul { display: grid; max-height: 220px; gap: var(--cs-space-4); overflow-y: auto; margin: 0; padding: 0; list-style: none; }
.bulk-results__item { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; gap: var(--cs-space-8); align-items: baseline; padding: var(--cs-space-4) var(--cs-space-8); border-radius: var(--cs-radius-sm); background: var(--cs-surface); font-size: var(--cs-text-xs); }
.bulk-results__item--failed, .bulk-results__item--refused { border-left: 3px solid var(--cs-danger); }
.bulk-results__item--excluded { border-left: 3px solid var(--cs-warning); }
.bulk-results__title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.bulk-results__outcome { color: var(--cs-text-secondary); }
.bulk-results__message { grid-column: 2 / -1; color: var(--cs-text-muted); }
@media (max-width: 767px) { .bulk-bar { position: static; }.bulk-results__item { grid-template-columns: 1fr auto; }.bulk-results__title { grid-column: 1 / -1; }.bulk-results__message { grid-column: 1 / -1; } }
</style>
