<script setup lang="ts">
import { secureId } from '../api/secureId'
import { BookPlus, RefreshCw, Sparkles } from '@lucide/vue'
import { computed, inject, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AUTH_PRINCIPAL, can, permissions } from '../app/auth'
import { useNetworkStatus } from '../app/network'
import BaseButton from '../components/base/BaseButton.vue'
import KnowledgeCreateDialog from '../components/domain/KnowledgeCreateDialog.vue'
import KnowledgeDistillationDialog from '../components/domain/KnowledgeDistillationDialog.vue'
import KnowledgeEntryDetail from '../components/domain/KnowledgeEntryDetail.vue'
import KnowledgeEntryList from '../components/domain/KnowledgeEntryList.vue'
import AppShell from '../components/layout/AppShell.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
import { usePageRequestScope } from '../composables/usePageRequestScope'
import { useKnowledgeStore } from '../domains/knowledge/store'
import type { KnowledgeResource, KnowledgeVersionPageResource } from '../domains/knowledge/store'
import type {
  CreateKnowledgeEntryInput,
  DistillKnowledgeInput,
  KnowledgeCategory,
  KnowledgeEntryFilter,
  KnowledgeEntryStatus,
  UpdateKnowledgeDraftInput,
} from '../domains/knowledge/types'
import { knowledgeCategories, knowledgeEntryStatuses } from '../domains/knowledge/types'
import type { SettingsScope } from '../domains/settings/types'
import { useScopeStore } from '../domains/scope/store'

const pageRequests = usePageRequestScope()

const route = useRoute()
const router = useRouter()
const principal = inject(AUTH_PRINCIPAL)
const scopeStore = useScopeStore()
const store = useKnowledgeStore()
const online = useNetworkStatus()

const createOpen = ref(false)
const distillOpen = ref(false)
const distillReceipt = ref(store.distillationReceipt())
const deleteTarget = ref<string | null>(null)
const deleteConfirmed = ref(false)
/** Entry-scoped commands mint their key here; create/distill dialogs own their own keys. */
const commandAttempt = ref<{ op: string, entryId: string, key: string } | null>(null)

const scope = computed<SettingsScope | null>(() => principal && scopeStore.state.selectedTeamId
  ? { organizationId: principal.organizationId, teamId: scopeStore.state.selectedTeamId }
  : null)
const scopeMissing = computed(() => scopeStore.state.phase === 'ready' && !scope.value)
const canManage = computed(() => Boolean(principal && can(principal, permissions.knowledgeManage)))

const statusFilter = computed<KnowledgeEntryStatus | 'ALL'>(() => oneOf(route.query.status, ['ALL', ...knowledgeEntryStatuses] as const, 'ALL'))
const categoryFilter = computed<KnowledgeCategory | 'ALL'>(() => oneOf(route.query.category, ['ALL', ...knowledgeCategories] as const, 'ALL'))
const selectedEntryId = computed(() => uuidQuery(route.query.entry))
const selectedRevision = computed(() => revisionQuery(route.query.revision))
const tab = computed(() => oneOf(route.query.tab, ['draft', 'versions'] as const, 'draft'))
const filter = computed<KnowledgeEntryFilter>(() => ({
  ...(statusFilter.value === 'ALL' ? {} : { status: statusFilter.value }),
  ...(categoryFilter.value === 'ALL' ? {} : { category: categoryFilter.value }),
}))

const detailResource = computed(() => selectedEntryId.value ? store.state.entryDetails[selectedEntryId.value] ?? null : null)
const entry = computed(() => detailResource.value?.value?.value ?? null)
const history = computed<KnowledgeVersionPageResource>(() => selectedEntryId.value
  ? store.state.versionHistory[selectedEntryId.value] ?? idleVersionPage()
  : idleVersionPage())
const versionResource = computed(() => selectedEntryId.value && selectedRevision.value
  ? store.state.versionDetails[`${selectedEntryId.value}:${selectedRevision.value}`] ?? null
  : null)
const effective = computed(() => selectedEntryId.value ? store.state.effectiveVersions[selectedEntryId.value] ?? null : null)
/** Commands belong to the selected entry only; dialog outcomes surface inside their dialogs. */
const commandForSelection = computed(() => store.state.command.resourceId === selectedEntryId.value && selectedEntryId.value !== null
  ? store.state.command
  : null)
const createError = computed(() => createOpen.value && store.state.command.operation === 'create' && store.state.command.phase === 'error'
  ? store.state.command.errorMessage
  : null)
const distillError = computed(() => distillOpen.value && store.state.command.operation === 'distill' && store.state.command.phase === 'error'
  ? store.state.command.errorMessage
  : null)
const createPending = computed(() => store.state.command.phase === 'pending' && store.state.command.operation === 'create')
const distillPending = computed(() => store.state.command.phase === 'pending' && store.state.command.operation === 'distill')
const deleting = computed(() => store.state.command.phase === 'pending' && store.state.command.operation === 'delete' && store.state.command.resourceId === deleteTarget.value)
const draftScope = computed(() => principal && scope.value
  ? { accountId: principal.accountId, principalId: principal.id, organizationId: principal.organizationId, teamId: scope.value.teamId }
  : null)

watch(
  () => [
    scopeStore.state.phase,
    scope.value?.organizationId,
    scope.value?.teamId,
    statusFilter.value,
    categoryFilter.value,
  ] as const,
  async ([phase]) => {
    const pageOwner = pageRequests.capture()
    if (phase !== 'ready' || !scope.value) return
    store.activateScope(scope.value)
    commandAttempt.value = null
    store.clearCommand()
    await store.loadEntries(filter.value, false, true)
    if (!pageOwner.isCurrent()) return
  },
  { immediate: true },
)

watch(
  () => [scopeStore.state.phase, route.query.status, route.query.category, route.query.entry, route.query.revision, route.query.tab] as const,
  ([phase]) => {
    if (phase !== 'ready') return
    const query = { ...route.query }
    let changed = false
    changed = normalizeQuery(query, 'status', statusFilter.value, 'ALL') || changed
    changed = normalizeQuery(query, 'category', categoryFilter.value, 'ALL') || changed
    changed = normalizeQuery(query, 'tab', tab.value, 'draft') || changed
    if (route.query.entry != null && !selectedEntryId.value) {
      delete query.entry
      changed = true
    }
    if (route.query.revision != null && !selectedRevision.value) {
      delete query.revision
      changed = true
    }
    if (changed) void router.replace({ query })
  },
  { immediate: true },
)

watch(
  () => [scopeStore.state.phase, scope.value?.teamId, selectedEntryId.value, tab.value, selectedRevision.value] as const,
  ([phase, _teamId, entryId, currentTab, revision]) => {
    commandAttempt.value = null
    store.clearCommand()
    if (phase !== 'ready' || !scope.value || !entryId) return
    void store.loadEntry(entryId)
    if (currentTab === 'versions') {
      void store.loadVersions(entryId)
      void store.loadEffectiveVersion(entryId)
      if (revision) void store.loadVersion(entryId, revision)
    }
  },
  { immediate: true },
)

function selectEntry(entryId: string): void {
  const query = { ...route.query } as Record<string, string>
  delete query.revision
  query.entry = entryId
  void router.replace({ query })
}

function closeDetail(): void {
  const query = { ...route.query }
  delete query.entry
  delete query.revision
  void router.replace({ query })
}

function changeStatus(value: KnowledgeEntryStatus | 'ALL'): void {
  replaceFilter('status', value, 'ALL')
}

function changeCategory(value: KnowledgeCategory | 'ALL'): void {
  replaceFilter('category', value, 'ALL')
}

function replaceFilter(key: string, value: string, defaultValue: string): void {
  const query = { ...route.query }
  if (value === defaultValue) delete query[key]
  else query[key] = value
  delete query.entry
  delete query.revision
  void router.replace({ query })
}

function changeTab(next: 'draft' | 'versions'): void {
  const query = { ...route.query }
  if (next === 'draft') delete query.tab
  else query.tab = next
  if (next === 'versions' && selectedEntryId.value) {
    void store.loadVersions(selectedEntryId.value)
    void store.loadEffectiveVersion(selectedEntryId.value)
  }
  void router.replace({ query })
}

function selectRevision(revision: number): void {
  void router.replace({ query: { ...route.query, revision: String(revision) } })
}

function openDistill(): void {
  distillOpen.value = true
  distillReceipt.value = store.distillationReceipt()
}

async function reload(): Promise<void> {
  const pageOwner = pageRequests.capture()
  if (!scope.value || !online.value) return
  await store.loadEntries(filter.value, false, true)
  if (selectedEntryId.value) await store.loadEntry(selectedEntryId.value, true)
  if (!pageOwner.isCurrent()) return
}

function retryDetail(): void {
  if (selectedEntryId.value) void store.loadEntry(selectedEntryId.value, true)
}

function retryVersions(): void {
  if (selectedEntryId.value) void store.loadVersions(selectedEntryId.value, false, true)
}

function loadMoreEntries(): void {
  void store.loadEntries(filter.value, true)
}

function loadMoreVersions(): void {
  if (selectedEntryId.value) void store.loadVersions(selectedEntryId.value, true)
}

function attemptKeyFor(op: string, entryId: string): string {
  const current = commandAttempt.value
  if (current && current.op === op && current.entryId === entryId) return current.key
  const next = { op, entryId, key: secureId() }
  commandAttempt.value = next
  return next.key
}

async function saveDraft(entryId: string, input: UpdateKnowledgeDraftInput): Promise<void> {
  await runEntryCommand('save-draft', entryId, key => store.saveDraft(entryId, input, key))
}

async function publish(entryId: string): Promise<void> {
  await runEntryCommand('publish', entryId, key => store.publishEntry(entryId, key))
}

async function retire(entryId: string): Promise<void> {
  await runEntryCommand('retire', entryId, key => store.retireEntry(entryId, key))
}

function requestDelete(entryId: string): void {
  deleteTarget.value = entryId
  deleteConfirmed.value = false
}

function cancelDelete(): void {
  deleteTarget.value = null
  deleteConfirmed.value = false
}

async function confirmDelete(): Promise<void> {
  const entryId = deleteTarget.value
  if (!entryId || !deleteConfirmed.value) return
  const done = await runEntryCommand('delete', entryId, key => store.deleteEntry(entryId, key))
  if (done) cancelDelete()
}

async function runEntryCommand(
  op: 'save-draft' | 'publish' | 'retire' | 'delete',
  entryId: string,
  run: (key: string) => Promise<boolean>,
): Promise<boolean> {
  const pageOwner = pageRequests.captureSelection()
  if (!online.value) return false
  const success = await run(attemptKeyFor(op, entryId))
  if (!pageOwner.isCurrent()) return success
  if (success) {
    commandAttempt.value = null
    await refreshFacts(entryId)
    return true
  }
  if (store.state.command.phase === 'conflict') {
    // D4: drop the attempt key (the next submit is a new logical request) and reload facts
    // without touching the editor.
    commandAttempt.value = null
    await Promise.all([
      store.loadEntries(filter.value, false, true),
      store.loadEntry(entryId, true),
    ])
  } else if (!store.state.command.retryable) {
    commandAttempt.value = null
  }
  return false
}

async function refreshFacts(entryId: string): Promise<void> {
  await Promise.all([
    store.loadEntries(filter.value, false, true),
    store.loadEntry(entryId, true),
    store.loadVersions(entryId, false, true),
    store.loadEffectiveVersion(entryId, true),
  ])
}

async function createEntry(input: CreateKnowledgeEntryInput, idempotencyKey: string): Promise<void> {
  const pageOwner = pageRequests.captureSelection()
  const success = await store.createEntry(input, idempotencyKey)
  if (!pageOwner.isCurrent()) return
  if (success) {
    createOpen.value = false
    await store.loadEntries(filter.value, false, true)
  }
}

async function distill(input: DistillKnowledgeInput, idempotencyKey: string): Promise<void> {
  const pageOwner = pageRequests.captureSelection()
  const success = await store.distill(input, idempotencyKey)
  if (!pageOwner.isCurrent()) return
  if (success) {
    distillReceipt.value = store.distillationReceipt()
    await store.loadEntries(filter.value, false, true)
  }
}

function closeDistill(): void {
  distillOpen.value = false
  distillReceipt.value = null
  store.clearCommand()
}

function openDistilledEntry(entryId: string): void {
  closeDistill()
  selectEntry(entryId)
}

function normalizeQuery(query: Record<string, unknown>, key: string, value: string, defaultValue: string): boolean {
  const current = queryValue(query[key])
  const expected = value === defaultValue ? null : value
  if (current === expected) return false
  if (expected) query[key] = expected
  else delete query[key]
  return true
}

function oneOf<T extends string>(value: unknown, allowed: readonly T[], fallback: T): T {
  const parsed = queryValue(value)
  return parsed && allowed.includes(parsed as T) ? parsed as T : fallback
}

function uuidQuery(value: unknown): string | null {
  const parsed = queryValue(value)
  return parsed && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(parsed) ? parsed : null
}

function revisionQuery(value: unknown): number | null {
  const parsed = queryValue(value)
  return parsed && /^\d+$/.test(parsed) && Number(parsed) >= 1 ? Number(parsed) : null
}

function queryValue(value: unknown): string | null {
  return typeof value === 'string' && value.length > 0 ? value : null
}

function idleVersionPage(): KnowledgeVersionPageResource {
  return { phase: 'idle', value: null, errorMessage: null, errorStatus: null, nextAfter: null, loadingMore: false }
}
</script>

<template>
  <AppShell title="知识库" eyebrow="团队 · 知识管理">
    <template #actions>
      <BaseButton variant="secondary" size="small" :disabled="!scope || !online" :aria-describedby="scopeMissing ? 'knowledge-scope-reason' : undefined" @click="reload"><RefreshCw :size="14" />刷新</BaseButton>
      <BaseButton v-if="canManage" variant="secondary" size="small" :disabled="!online" @click="createOpen = true"><BookPlus :size="14" />创建条目</BaseButton>
      <BaseButton v-if="canManage" variant="secondary" size="small" :disabled="!online" @click="openDistill"><Sparkles :size="14" />从执行蒸馏</BaseButton>
    </template>

    <StatePanel v-if="scopeMissing" id="knowledge-scope-reason" state="empty" title="请选择 Team" description="知识库始终属于明确的 Organization 与 Team。" />

    <div v-if="scopeStore.state.phase === 'ready' && scope" class="knowledge-workspace">
      <KnowledgeEntryList
        :phase="store.state.entries.phase"
        :items="store.state.entries.value ?? []"
        :next-after="store.state.entries.nextAfter"
        :loading-more="store.state.entries.loadingMore"
        :error-message="store.state.entries.errorMessage"
        :error-status="store.state.entries.errorStatus"
        :online="online"
        :status-filter="statusFilter"
        :category-filter="categoryFilter"
        :can-manage="canManage"
        :selected-entry-id="selectedEntryId"
        @select="selectEntry"
        @change-status="changeStatus"
        @change-category="changeCategory"
        @load-more="loadMoreEntries"
        @create="createOpen = true"
        @distill="openDistill"
        @retry="reload"
      />

      <KnowledgeEntryDetail
        v-if="selectedEntryId"
        :entry-resource="detailResource"
        :command="commandForSelection"
        :history="history"
        :version-resource="versionResource"
        :effective="effective"
        :effective-revision="entry?.effectiveRevision ?? null"
        :selected-revision="selectedRevision"
        :tab="tab"
        :scope="draftScope!"
        :can-manage="canManage"
        :online="online"
        @close="closeDetail"
        @save="input => saveDraft(selectedEntryId!, input)"
        @publish="publish(selectedEntryId!)"
        @retire="retire(selectedEntryId!)"
        @delete="requestDelete(selectedEntryId!)"
        @change-tab="changeTab"
        @select-revision="selectRevision"
        @load-more-versions="loadMoreVersions"
        @retry-entry="retryDetail"
        @retry-versions="retryVersions"
      />
    </div>

    <div v-if="deleteTarget" class="knowledge-delete-backdrop" @click.self="cancelDelete">
      <section class="knowledge-delete-dialog panel" role="alertdialog" aria-modal="true" aria-labelledby="knowledge-delete-title" aria-describedby="knowledge-delete-impact" tabindex="-1">
        <header><h3 id="knowledge-delete-title">删除知识条目</h3></header>
        <p id="knowledge-delete-impact">删除后条目进入删除墓碑状态：列表保留墓碑行，历史引用不会失效，已发布版本转为只读。此操作可通过服务端审计追溯，但界面不提供恢复入口。</p>
        <label class="knowledge-delete-confirm"><input v-model="deleteConfirmed" type="checkbox" :disabled="deleting" />我确认删除此条目并保留墓碑</label>
        <p v-if="commandForSelection?.phase === 'error' && commandForSelection.operation === 'delete'" class="knowledge-delete-error" role="alert">{{ commandForSelection.errorMessage }}</p>
        <footer>
          <BaseButton variant="ghost" :disabled="store.state.command.phase === 'pending'" @click="cancelDelete">取消</BaseButton>
          <BaseButton variant="danger" :loading="deleting" :disabled="!deleteConfirmed" @click="confirmDelete">确认删除</BaseButton>
        </footer>
      </section>
    </div>

    <KnowledgeCreateDialog
      v-if="createOpen"
      :team-id="scope?.teamId ?? ''"
      :submitting="createPending"
      :error-message="createError"
      @close="createOpen = false; store.clearCommand()"
      @create="createEntry"
    />
    <KnowledgeDistillationDialog
      v-if="distillOpen"
      :team-id="scope?.teamId ?? ''"
      :submitting="distillPending"
      :error-message="distillError"
      :receipt="distillReceipt"
      @close="closeDistill"
      @distill="distill"
      @open-entry="openDistilledEntry"
    />
  </AppShell>
</template>

<style scoped>
.knowledge-workspace { display: grid; grid-template-columns: minmax(0, 5fr) minmax(0, 4fr); gap: var(--cs-space-16); align-items: start; }
.knowledge-delete-backdrop { position: fixed; inset: 0; z-index: var(--cs-z-dialog); display: grid; place-items: center; padding: var(--cs-space-20); background: var(--cs-scrim); backdrop-filter: blur(3px); }
.knowledge-delete-dialog { width: min(500px, 100%); padding: var(--cs-space-20); border: 1px solid var(--cs-danger-border); box-shadow: var(--cs-shadow-float); }
.knowledge-delete-dialog h3 { margin: 0; font-size: var(--cs-text-md); }
.knowledge-delete-dialog > p { margin: var(--cs-space-12) 0 0; color: var(--cs-text-secondary); font-size: var(--cs-text-sm); line-height: var(--cs-leading-normal); }
.knowledge-delete-confirm { display: flex; align-items: center; gap: var(--cs-space-8); margin-top: var(--cs-space-12); color: var(--cs-danger); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.knowledge-delete-error { margin: var(--cs-space-12) 0 0; color: var(--cs-danger); font-size: var(--cs-text-sm); }
.knowledge-delete-dialog footer { display: flex; justify-content: flex-end; gap: var(--cs-space-8); margin-top: var(--cs-space-16); }
@media (max-width: 767px) {
  .knowledge-workspace { grid-template-columns: 1fr; }
  .knowledge-delete-backdrop { align-items: end; padding: 0; }
  .knowledge-delete-dialog footer { display: grid; }
  .knowledge-delete-dialog footer > * { width: 100%; }
}
</style>
