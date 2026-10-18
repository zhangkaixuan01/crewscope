<script setup lang="ts">
import BaseSelect from '../base/BaseSelect.vue'
import { CircleSlash, Network } from '@lucide/vue'
import { computed, inject, ref, watch } from 'vue'
import { secureId } from '../../api/secureId'
import type { PrincipalScope } from '../../domains/principal/types'
import { WORK_ITEM_STORE } from '../../domains/workitem/store'
import type { WorkItemSummary } from '../../domains/workitem/types'
import { WORK_GRAPH_STORE } from '../../domains/workgraph/store'
import { relationOptionLabels, relationSentence, workItemRelationTypeLabels } from '../../domains/workgraph/labels'
import type { RelationView, WorkItemRelationEdge } from '../../domains/workgraph/types'
import BaseButton from '../base/BaseButton.vue'
import StatePanel from '../feedback/StatePanel.vue'

/**
 * The relation facts of the open WorkItem (M11-F02): who blocks it, what it blocks, what it was
 * split from — each edge said in one plain sentence, removable here, addable here.
 *
 * The panel never submits DEPENDS_ON: the three sentences are exactly the three submittable
 * meanings, and a stored edge's DEPENDS_ON view renders as the equivalent note instead of a
 * fourth sentence. Both stores are optional injections (collaboration-store precedent): without
 * them the section renders its states and does nothing, which is what standalone specs mount.
 */
const props = defineProps<{
  scope: PrincipalScope | null
  item: WorkItemSummary
  canParticipate: boolean
}>()

const workItemStore = inject(WORK_ITEM_STORE, null)
const graphStore = inject(WORK_GRAPH_STORE, null)

const relationScope = computed(() => props.scope && props.item
  ? { organizationId: props.scope.organizationId, teamId: props.scope.teamId, projectId: props.item.projectId }
  : null)

watch(
  () => [relationScope.value, props.item.id] as const,
  ([scope, workItemId]) => {
    if (scope && workItemId) void graphStore?.loadRelations(scope, workItemId)
  },
  { immediate: true },
)

const anchor = computed(() => graphStore?.state.anchor.workItemId === props.item.id ? graphStore.state.anchor : null)
const edges = computed(() => anchor.value?.relations?.edges ?? [])

/** The candidate list is the whole loaded project list, never the filtered view. */
const candidates = computed(() => (workItemStore?.state.items ?? [])
  .filter(candidate => candidate.id !== props.item.id)
  .filter(candidate => !edges.value.some(edge => edge.otherWorkItemId === candidate.id)))

function titleOf(workItemId: string): string {
  const known = workItemStore?.state.items.find(item => item.id === workItemId)?.title
    ?? graphStore?.state.nodes.get(workItemId)?.title
  return known ?? `工作项 ${workItemId.slice(0, 8)}…`
}

/** The sentence for one stored edge: the BLOCKS view of it, or SPLIT_FROM's single view. */
function sentenceOf(edge: WorkItemRelationEdge): string {
  const view = primaryView(edge.views)
  return view ? relationSentence(view, props.item.id, titleOf(edge.otherWorkItemId)) : '关系数据不完整'
}

function primaryView(views: RelationView[]): RelationView | null {
  return views.find(view => view.type !== 'DEPENDS_ON') ?? views[0] ?? null
}

/** A second SPLIT_FROM source is a guaranteed 409 (one source per item): the option short-circuits. */
const splitSourceTaken = computed(() => edges.value.some(edge =>
  edge.views.some(view => view.type === 'SPLIT_FROM' && view.fromWorkItemId === props.item.id)))

const selection = ref('')
const sentenceKind = ref<'incomingBlocker' | 'outgoingBlocker' | 'splitFromSource'>('incomingBlocker')
const submitted = ref(false)
const confirmingRemovalId = ref<string | null>(null)

const command = computed(() => graphStore?.state.command ?? null)
const pending = computed(() => command.value?.pending === true)
const commandError = computed(() => command.value?.errorMessage ?? null)

async function submitRelation(): Promise<void> {
  submitted.value = true
  const scope = props.scope
  const target = selection.value
  if (!scope || !target) return
  const input = sentenceKind.value === 'splitFromSource'
    ? { type: 'SPLIT_FROM' as const, fromWorkItemId: props.item.id, toWorkItemId: target }
    : sentenceKind.value === 'outgoingBlocker'
      ? { type: 'BLOCKS' as const, fromWorkItemId: props.item.id, toWorkItemId: target }
      : { type: 'BLOCKS' as const, fromWorkItemId: target, toWorkItemId: props.item.id }
  const receipt = await graphStore?.createRelation(
    { organizationId: scope.organizationId, teamId: scope.teamId },
    input,
    secureId(),
  )
  if (receipt) {
    selection.value = ''
    sentenceKind.value = 'incomingBlocker'
    submitted.value = false
  }
}

async function removeRelation(edge: WorkItemRelationEdge): Promise<void> {
  const scope = props.scope
  if (!scope) return
  // Two-step confirmation on the row itself (transition-confirm precedent): the first click arms,
  // the second executes; anything else disarms.
  if (confirmingRemovalId.value !== edge.relationId) {
    confirmingRemovalId.value = edge.relationId
    return
  }
  confirmingRemovalId.value = null
  await graphStore?.removeRelation(
    { organizationId: scope.organizationId, teamId: scope.teamId },
    edge.relationId,
    secureId(),
  )
}
</script>

<template>
  <section class="detail-section relations-section">
    <div class="section-heading"><div><p>Relations</p><h3>阻塞与拆分 <span>{{ edges.length }}</span></h3></div><Network :size="17" /></div>
    <StatePanel v-if="anchor?.phase === 'loading' && !anchor.relations" state="loading" description="正在加载工作项关系" />
    <p v-else-if="anchor?.phase === 'error'" class="relations-error" role="alert">
      {{ anchor.errorMessage ?? '暂时无法加载工作项关系' }}
      <button type="button" @click="relationScope && item && graphStore?.loadRelations(relationScope, item.id, true)">重试</button>
    </p>
    <template v-else>
      <p v-if="anchor?.relations?.truncatedByRows" class="relations-note">关系数量已达读取上限，仅显示最近的部分。</p>
      <ul v-if="edges.length" class="relations-list">
        <li v-for="edge in edges" :key="edge.relationId">
          <span :class="{ 'relations-list__cross': edge.crossProject }" :title="edge.crossProject ? '跨项目关系' : undefined">
            {{ sentenceOf(edge) }}<em v-if="edge.crossProject">（跨项目）</em>
          </span>
          <button
            v-if="canParticipate"
            type="button"
            class="relations-remove"
            :class="{ 'relations-remove--armed': confirmingRemovalId === edge.relationId }"
            :disabled="pending"
            @click="removeRelation(edge)"
            @blur="confirmingRemovalId === edge.relationId && (confirmingRemovalId = null)"
          >{{ confirmingRemovalId === edge.relationId ? '确认移除' : '移除' }}</button>
        </li>
      </ul>
      <p v-else class="section-note">还没有阻塞或拆分关系。</p>
      <p class="relations-note">「{{ workItemRelationTypeLabels.DEPENDS_ON }}」关系与阻塞等价：建立「本项阻塞所选工作项」后，所选工作项即显示为依赖本项。</p>
      <form v-if="canParticipate" class="relations-form" @submit.prevent="submitRelation">
        <label><span>目标工作项</span>
          <BaseSelect v-model="selection" :aria-invalid="submitted && !selection">
            <option value="">选择工作项…</option>
            <option v-for="candidate in candidates" :key="candidate.id" :value="candidate.id">{{ candidate.key }} · {{ candidate.title }}</option>
          </BaseSelect>
        </label>
        <label><span>关系</span>
          <BaseSelect v-model="sentenceKind">
            <option value="incomingBlocker">{{ relationOptionLabels.incomingBlocker }}</option>
            <option value="outgoingBlocker">{{ relationOptionLabels.outgoingBlocker }}</option>
            <option value="splitFromSource" :disabled="splitSourceTaken">{{ splitSourceTaken ? '本项已有拆分来源' : relationOptionLabels.splitFromSource }}</option>
          </BaseSelect>
        </label>
        <BaseButton type="submit" size="small" variant="secondary" :loading="pending" :disabled="!selection"><CircleSlash :size="13" />添加关系</BaseButton>
      </form>
      <p v-if="commandError" class="relations-error" role="alert">{{ commandError }}</p>
    </template>
  </section>
</template>

<style scoped>
.relations-list { display: grid; gap: var(--cs-space-4); margin: 0; padding: 0; list-style: none; }
.relations-list li { display: flex; align-items: center; justify-content: space-between; gap: var(--cs-space-8); padding: var(--cs-space-8); border: 1px solid var(--cs-border); border-radius: 8px; background: var(--cs-surface-subtle); }
.relations-list span { display: inline-flex; min-width: 0; align-items: center; gap: var(--cs-space-4); overflow: hidden; color: var(--cs-text-secondary); font-size: var(--cs-text-sm); text-overflow: ellipsis; }
.relations-list em { flex: 0 0 auto; color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-style: normal; }
.relations-list__cross { color: var(--cs-text); font-weight: var(--cs-weight-semibold); }
.relations-remove { flex: 0 0 auto; min-height: 30px; padding: 0 var(--cs-space-8); border: 1px solid var(--cs-border); border-radius: 7px; background: var(--cs-surface); color: var(--cs-text-muted); font-size: var(--cs-text-xs); cursor: pointer; }
.relations-remove:hover { border-color: var(--cs-danger); color: var(--cs-danger); }
.relations-remove--armed { border-color: var(--cs-danger); background: var(--cs-danger-soft); color: var(--cs-danger); font-weight: var(--cs-weight-semibold); }
.relations-note { margin: var(--cs-space-8) 0 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); line-height: var(--cs-leading-normal); }
.relations-form { display: grid; gap: var(--cs-space-8); margin-top: var(--cs-space-12); }
.relations-form label { display: grid; gap: var(--cs-space-4); color: var(--cs-text-secondary); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }
.relations-form :deep(select) { width: 100%; min-height: 34px; padding: 0 var(--cs-space-8); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); color: var(--cs-text); font: var(--cs-text-base) var(--cs-font-sans); }
.relations-form select[aria-invalid="true"] { border-color: var(--cs-danger); }
.relations-form > button { justify-self: end; }
.relations-error { margin: var(--cs-space-8) 0 0; color: var(--cs-danger); font-size: var(--cs-text-xs); }
.relations-error button { color: inherit; text-decoration: underline; cursor: pointer; }
</style>
