<script setup lang="ts">
import { BookPlus, RefreshCw, Sparkles } from '@lucide/vue'
import { computed, inject, ref } from 'vue'
import { useRoute } from 'vue-router'
import { oneOf, revisionQuery, uuidQuery } from '../utils/queryParams'
import { AUTH_PRINCIPAL, can, permissions } from '../app/auth'
import { useNetworkStatus } from '../app/network'
import BaseButton from '../components/base/BaseButton.vue'
import KnowledgeCreateDialog from '../components/domain/KnowledgeCreateDialog.vue'
import KnowledgeDistillationDialog from '../components/domain/KnowledgeDistillationDialog.vue'
import KnowledgeEntryDetail from '../components/domain/KnowledgeEntryDetail.vue'
import KnowledgeEntryList from '../components/domain/KnowledgeEntryList.vue'
import AppShell from '../components/layout/AppShell.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
import ActionConfirmDialog from '../components/shared/ActionConfirmDialog.vue'
import { idleVersionPage, useEntryLibraryPage } from '../composables/useEntryLibraryPage'
import { usePageRequestScope } from '../composables/usePageRequestScope'
import { useKnowledgeStore } from '../domains/knowledge/store'
import type { KnowledgeVersionPageResource } from '../domains/knowledge/store'
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
const principal = inject(AUTH_PRINCIPAL)
const scopeStore = useScopeStore()
const store = useKnowledgeStore()
const online = useNetworkStatus()

const createOpen = ref(false)
const distillOpen = ref(false)
const distillReceipt = ref(store.distillationReceipt())
const deleteTarget = ref<string | null>(null)

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
const deleteError = computed(() => commandForSelection.value?.phase === 'error' && commandForSelection.value.operation === 'delete'
  ? store.state.command.errorMessage
  : null)
const draftScope = computed(() => principal && scope.value
  ? { accountId: principal.accountId, principalId: principal.id, organizationId: principal.organizationId, teamId: scope.value.teamId }
  : null)

/** Shared library lifecycle (M12b-F04): catalog/selection loads, query coordination, commands. */
const library = useEntryLibraryPage<string>({
  pageRequests,
  catalogSources: () => [
    scopeStore.state.phase,
    scope.value?.organizationId,
    scope.value?.teamId,
    statusFilter.value,
    categoryFilter.value,
  ] as const,
  scopePresent: () => Boolean(scope.value),
  activateScope: () => store.activateScope(scope.value!),
  queryKeys: { selected: 'entry' },
  filters: [
    { key: 'status', value: () => statusFilter.value, fallback: 'ALL' },
    { key: 'category', value: () => categoryFilter.value, fallback: 'ALL' },
  ],
  scopeReady: () => scopeStore.state.phase === 'ready' && Boolean(scope.value),
  teamId: () => scope.value?.teamId,
  online: () => online.value,
  selectedId: () => selectedEntryId.value,
  selectedRevision: () => selectedRevision.value,
  tab: () => tab.value,
  clearCommand: () => store.clearCommand(),
  commandPhase: () => store.state.command,
  loadCatalog: force => store.loadEntries(filter.value, false, force),
  loadDetail: (id, force) => store.loadEntry(id, force),
  loadVersions: (id, more, force) => store.loadVersions(id, more, force),
  loadVersion: (id, revision) => store.loadVersion(id, revision),
  // Knowledge reads the effective version on the versions tab only (no editor baseline here).
  loadEffective: (id, force) => store.loadEffectiveVersion(id, force),
  effectiveOnDraftTab: false,
  onEnterVersionsTab: id => {
    void store.loadVersions(id)
    void store.loadEffectiveVersion(id)
  },
})

function openDistill(): void {
  distillOpen.value = true
  distillReceipt.value = store.distillationReceipt()
}

function loadMoreEntries(): void {
  void store.loadEntries(filter.value, true)
}

async function saveDraft(entryId: string, input: UpdateKnowledgeDraftInput): Promise<void> {
  await library.runCommand('save-draft', entryId, key => store.saveDraft(entryId, input, key))
}

async function publish(entryId: string): Promise<void> {
  await library.runCommand('publish', entryId, key => store.publishEntry(entryId, key))
}

async function retire(entryId: string): Promise<void> {
  await library.runCommand('retire', entryId, key => store.retireEntry(entryId, key))
}

function requestDelete(entryId: string): void {
  deleteTarget.value = entryId
}

function cancelDelete(): void {
  deleteTarget.value = null
}

async function confirmDelete(): Promise<void> {
  const entryId = deleteTarget.value
  if (!entryId) return
  const done = await library.runCommand('delete', entryId, key => store.deleteEntry(entryId, key))
  if (done) cancelDelete()
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
  library.select(entryId)
}
</script>

<template>
  <AppShell title="知识库" eyebrow="团队 · 知识管理">
    <template #actions>
      <BaseButton variant="secondary" size="small" :disabled="!scope || !online" :aria-describedby="scopeMissing ? 'knowledge-scope-reason' : undefined" @click="library.reload"><RefreshCw :size="14" />刷新</BaseButton>
      <BaseButton v-if="canManage" variant="secondary" size="small" :disabled="!online" @click="createOpen = true"><BookPlus :size="14" />创建条目</BaseButton>
      <BaseButton v-if="canManage" variant="secondary" size="small" :disabled="!online" @click="openDistill"><Sparkles :size="14" />从执行蒸馏</BaseButton>
    </template>

    <StatePanel v-if="scopeMissing" id="knowledge-scope-reason" state="empty" title="请选择 Team" description="知识库始终属于明确的 Organization 与 Team。" />

    <div v-if="scopeStore.state.phase === 'ready' && scope" class="knowledge-workspace" :class="{ 'has-detail': Boolean(selectedEntryId) }">
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
        @select="library.select"
        @change-status="(value) => library.replaceFilter('status', value, 'ALL')"
        @change-category="(value) => library.replaceFilter('category', value, 'ALL')"
        @load-more="loadMoreEntries"
        @create="createOpen = true"
        @distill="openDistill"
        @retry="library.reload"
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
        @close="library.closeDetail"
        @save="input => saveDraft(selectedEntryId!, input)"
        @publish="publish(selectedEntryId!)"
        @retire="retire(selectedEntryId!)"
        @delete="requestDelete(selectedEntryId!)"
        @change-tab="library.changeTab"
        @select-revision="library.selectRevision"
        @load-more-versions="library.loadMoreVersions"
        @retry-entry="library.retryDetail"
        @retry-versions="library.retryVersions"
      />
    </div>

    <ActionConfirmDialog
      v-if="deleteTarget !== null"
      heading="删除知识条目"
      impact="删除后条目进入删除墓碑状态：列表保留墓碑行，历史引用不会失效，已发布版本转为只读。此操作可通过服务端审计追溯，但界面不提供恢复入口。"
      mode="confirm"
      confirm-checkbox-label="我确认删除此条目并保留墓碑"
      confirm-label="确认删除"
      variant="danger"
      width="min(500px, 100%)"
      close-label="关闭删除确认"
      :submitting="deleting"
      :error-message="deleteError"
      @cancel="cancelDelete"
      @confirm="confirmDelete"
    />

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
/* M13b-F09（S4）：未选中不再保留 44% 空栏——默认单栏列表，选中后右侧 340px 详情，≤900 下移。 */
.knowledge-workspace { display: grid; grid-template-columns: minmax(0, 1fr); gap: var(--cs-space-16); align-items: start; }.knowledge-workspace.has-detail { grid-template-columns: minmax(0, 1fr) 340px; }
@media (max-width: 900px) {
  .knowledge-workspace { grid-template-columns: 1fr; }
}
</style>
