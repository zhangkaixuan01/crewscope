<script setup lang="ts">
import { skillRepublishRestores } from '../domains/skill/labels'
import { ListPlus, RefreshCw, Sparkles } from '@lucide/vue'
import { computed, inject, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { oneOf, queryValue, revisionQuery, uuidQuery } from '../utils/queryParams'
import { AUTH_PRINCIPAL, can, permissions } from '../app/auth'
import { useNetworkStatus } from '../app/network'
import BaseButton from '../components/base/BaseButton.vue'
import SkillCreateDialog from '../components/domain/SkillCreateDialog.vue'
import SkillDetail from '../components/domain/SkillDetail.vue'
import SkillDistillationDialog from '../components/domain/SkillDistillationDialog.vue'
import SkillList from '../components/domain/SkillList.vue'
import AppShell from '../components/layout/AppShell.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
import ActionConfirmDialog from '../components/shared/ActionConfirmDialog.vue'
import { idleVersionPage, useEntryLibraryPage } from '../composables/useEntryLibraryPage'
import { usePageRequestScope } from '../composables/usePageRequestScope'
import { useSkillStore } from '../domains/skill/store'
import type { SkillVersionPageResource } from '../domains/skill/store'
import type {
  CreateSkillInput,
  DistillSkillInput,
  SkillFilter,
  SkillStatus,
  UpdateSkillDraftInput,
} from '../domains/skill/types'
import { SKILL_DISABLE_REASON_MAX, skillStatuses } from '../domains/skill/types'
import type { SettingsScope } from '../domains/settings/types'
import { useScopeStore } from '../domains/scope/store'

/** Injection-attempt deep links carry skillKey·revision·hash — never a skill id (F02 D5). */
const DEEP_LINK_PAGE_CAP = 5

/**
 * One confirmation, three commands (F02 D3): publish, disable and rollback each carry
 * contract-specific consequences that a bare "确定？" would hide.
 */
type SkillActionIntent =
  | { kind: 'publish', skillKey: string, nextRevision: number }
  | { kind: 'disable', skillKey: string }
  | { kind: 'rollback', skillKey: string, toRevision: number, effectiveRevision: number }

const pageRequests = usePageRequestScope()

const route = useRoute()
const router = useRouter()
const principal = inject(AUTH_PRINCIPAL)
const scopeStore = useScopeStore()
const store = useSkillStore()
const online = useNetworkStatus()

const createOpen = ref(false)
const distillOpen = ref(false)
const distillReceipt = ref(store.distillationReceipt())
const actionIntent = ref<SkillActionIntent | null>(null)
const deepLinkMiss = ref<string | null>(null)
let deepLinkPages = 0

const scope = computed<SettingsScope | null>(() => principal && scopeStore.state.selectedTeamId
  ? { organizationId: principal.organizationId, teamId: scopeStore.state.selectedTeamId }
  : null)
const scopeMissing = computed(() => scopeStore.state.phase === 'ready' && !scope.value)
const canManage = computed(() => Boolean(principal && can(principal, permissions.skillManage)))

const statusFilter = computed<SkillStatus | 'ALL'>(() => oneOf(route.query.status, ['ALL', ...skillStatuses] as const, 'ALL'))
const deepLinkKey = computed(() => queryValue(route.query.skillKey))
const selectedSkillId = computed(() => uuidQuery(route.query.skill))
const selectedRevision = computed(() => revisionQuery(route.query.revision))
const tab = computed(() => oneOf(route.query.tab, ['draft', 'versions'] as const, 'draft'))
/** A deep link must page the unfiltered catalog — a status filter could hide the target. */
const filter = computed<SkillFilter>(() => deepLinkKey.value
  ? {}
  : { ...(statusFilter.value === 'ALL' ? {} : { status: statusFilter.value }) })

const detailResource = computed(() => selectedSkillId.value ? store.state.skillDetails[selectedSkillId.value] ?? null : null)
const skill = computed(() => detailResource.value?.value?.value ?? null)
const history = computed<SkillVersionPageResource>(() => selectedSkillId.value
  ? store.state.versionHistory[selectedSkillId.value] ?? idleVersionPage()
  : idleVersionPage())
const versionResource = computed(() => selectedSkillId.value && selectedRevision.value
  ? store.state.versionDetails[`${selectedSkillId.value}:${selectedRevision.value}`] ?? null
  : null)
const effective = computed(() => selectedSkillId.value ? store.state.effectiveVersions[selectedSkillId.value] ?? null : null)
/** Commands belong to the selected skill only; dialog outcomes surface inside their dialogs. */
const commandForSelection = computed(() => store.state.command.resourceId === selectedSkillId.value && selectedSkillId.value !== null
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
const actionPending = computed(() => actionIntent.value !== null && store.state.command.phase === 'pending'
  && (store.state.command.operation === 'publish' || store.state.command.operation === 'disable' || store.state.command.operation === 'rollback'))
const actionError = computed(() => actionIntent.value !== null && commandForSelection.value?.phase === 'error'
  ? store.state.command.errorMessage
  : null)
/** The editor's diff baseline: the effective revision content, or the server draft when one exists. */
const baselineContent = computed(() => effective.value?.value?.value.content ?? skill.value?.draft?.content ?? '')
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
  ] as const,
  scopePresent: () => Boolean(scope.value),
  activateScope: () => store.activateScope(scope.value!),
  // Deep-link paging state belongs to the catalog reset, not the selection.
  onCatalogReset: () => {
    deepLinkMiss.value = null
    deepLinkPages = 0
  },
  queryKeys: { selected: 'skill' },
  filters: [{ key: 'status', value: () => statusFilter.value, fallback: 'ALL' }],
  // skillKey retriggers normalization (the deep-link resolver clears it when it settles).
  extraQueryKeys: ['skillKey'],
  scopeReady: () => scopeStore.state.phase === 'ready' && Boolean(scope.value),
  teamId: () => scope.value?.teamId,
  online: () => online.value,
  selectedId: () => selectedSkillId.value,
  selectedRevision: () => selectedRevision.value,
  tab: () => tab.value,
  clearCommand: () => store.clearCommand(),
  commandPhase: () => store.state.command,
  loadCatalog: force => store.loadSkills(filter.value, false, force),
  loadDetail: (id, force) => store.loadSkill(id, force),
  loadVersions: (id, more, force) => store.loadVersions(id, more, force),
  loadVersion: (id, revision) => store.loadVersion(id, revision),
  // The effective revision feeds the editor diff baseline, so it loads on both tabs.
  loadEffective: (id, force) => store.loadEffectiveVersion(id, force),
  effectiveOnDraftTab: true,
  onEnterVersionsTab: id => { void store.loadVersions(id) },
})

// Resolves a skillKey deep link by paging the unfiltered skillKey-ascending catalog, then
// rewrites the coordinate to the stable skill id. A miss is surfaced, never faked.
watch(
  () => [scopeStore.state.phase, deepLinkKey.value, store.state.skills.phase, store.state.skills.nextAfter] as const,
  ([phase, key, listPhase, nextAfter]) => {
    if (phase !== 'ready' || !scope.value || !key) return
    const hit = (store.state.skills.value ?? []).find(item => item.skillKey === key)
    if (hit) {
      const query = { ...route.query }
      delete query.skillKey
      query.skill = hit.id
      void router.replace({ query })
      return
    }
    if (listPhase === 'ready' && nextAfter !== null && deepLinkPages < DEEP_LINK_PAGE_CAP) {
      deepLinkPages += 1
      void store.loadSkills({}, true)
      return
    }
    if (listPhase === 'ready' && (nextAfter === null || deepLinkPages >= DEEP_LINK_PAGE_CAP)) {
      deepLinkMiss.value = key
      const query = { ...route.query }
      delete query.skillKey
      void router.replace({ query })
    }
  },
  { immediate: true },
)

function openDistill(): void {
  distillOpen.value = true
  distillReceipt.value = store.distillationReceipt()
}

function loadMoreSkills(): void {
  void store.loadSkills(filter.value, true)
}

async function saveDraft(skillId: string, input: UpdateSkillDraftInput): Promise<void> {
  await library.runCommand('save-draft', skillId, key => store.saveDraft(skillId, input, key))
}

function requestPublish(): void {
  if (!skill.value) return
  actionIntent.value = { kind: 'publish', skillKey: skill.value.skillKey, nextRevision: skill.value.latestRevision + 1 }
}

function requestDisable(): void {
  if (!skill.value) return
  actionIntent.value = { kind: 'disable', skillKey: skill.value.skillKey }
}

function requestRollback(toRevision: number): void {
  if (!skill.value) return
  actionIntent.value = {
    kind: 'rollback',
    skillKey: skill.value.skillKey,
    toRevision,
    effectiveRevision: skill.value.effectiveRevision ?? toRevision,
  }
}

function cancelAction(): void {
  actionIntent.value = null
}

const actionHeading = computed(() => {
  const intent = actionIntent.value
  if (!intent) return ''
  if (intent.kind === 'publish') return '发布当前草稿？'
  if (intent.kind === 'disable') return '禁用这个 Skill？'
  return `回滚到 r${intent.toRevision}？`
})
const actionImpact = computed(() => {
  const intent = actionIntent.value
  if (!intent) return ''
  if (intent.kind === 'publish') return `发布会产出新的不可变修订 r${intent.nextRevision} 并更新生效指针。服务端会对全部内容（frontmatter 与正文）执行无条件披露扫描——Skill 会注入未来任务的指令，审核标准比知识条目更严。发布后仍可回滚。`
  if (intent.kind === 'disable') return '禁用即下线：后续任务不再装载这个 Skill，但目录与历史版本全部保留（没有删除操作）。' + skillRepublishRestores
  return `回滚不是删除：会产出一个新的不可变修订，其内容与哈希与 r${intent.toRevision} 完全相同（相邻修订同哈希属正常形态），当前生效的 r${intent.effectiveRevision} 之后的中间修订全部保留在历史中。`
})
const actionConfirmLabel = computed(() => {
  const intent = actionIntent.value
  if (!intent) return ''
  if (intent.kind === 'publish') return '确认发布'
  if (intent.kind === 'disable') return '确认禁用'
  return '确认回滚'
})

async function confirmAction(reason: string | null): Promise<void> {
  const intent = actionIntent.value
  const skillId = selectedSkillId.value
  if (!intent || !skillId) return
  const op = intent.kind
  const done = await library.runCommand(op, skillId, key => {
    if (intent.kind === 'publish') return store.publishSkill(skillId, key)
    if (intent.kind === 'disable') return store.disableSkill(skillId, reason, key)
    return store.rollbackSkill(skillId, intent.toRevision, key)
  })
  if (done) actionIntent.value = null
}

async function createSkill(input: CreateSkillInput, idempotencyKey: string): Promise<void> {
  const pageOwner = pageRequests.captureSelection()
  const success = await store.createSkill(input, idempotencyKey)
  if (!pageOwner.isCurrent()) return
  if (success) {
    createOpen.value = false
    await store.loadSkills(filter.value, false, true)
  }
}

async function distill(input: DistillSkillInput, idempotencyKey: string): Promise<void> {
  const pageOwner = pageRequests.captureSelection()
  const success = await store.distill(input, idempotencyKey)
  if (!pageOwner.isCurrent()) return
  if (success) {
    distillReceipt.value = store.distillationReceipt()
    await store.loadSkills(filter.value, false, true)
  }
}

function closeDistill(): void {
  distillOpen.value = false
  distillReceipt.value = null
  store.clearCommand()
}

function openDistilledSkill(skillId: string): void {
  closeDistill()
  library.select(skillId)
}
</script>

<template>
  <AppShell title="Skill 目录" eyebrow="团队 · Skill 管理">
    <template #actions>
      <BaseButton variant="secondary" size="small" :disabled="!scope || !online" :aria-describedby="scopeMissing ? 'skills-scope-reason' : undefined" @click="library.reload"><RefreshCw :size="14" />刷新</BaseButton>
      <BaseButton v-if="canManage" variant="secondary" size="small" :disabled="!online" @click="createOpen = true"><ListPlus :size="14" />创建 Skill</BaseButton>
      <!-- Distillation stays for every member: the right belongs to the execution creator (§8). -->
      <BaseButton variant="secondary" size="small" :disabled="!online" @click="openDistill"><Sparkles :size="14" />从执行蒸馏</BaseButton>
    </template>

    <StatePanel v-if="scopeMissing" id="skills-scope-reason" state="empty" title="请选择 Team" />
    <StatePanel v-else-if="deepLinkMiss" compact state="empty" :title="`未在目录中找到 Skill ${deepLinkMiss}`" description="它可能属于其他团队、已被移除，或超出本次翻页范围。" />

    <div v-if="scopeStore.state.phase === 'ready' && scope" class="skills-workspace" :class="{ 'has-detail': Boolean(selectedSkillId) }">
      <SkillList
        :phase="store.state.skills.phase"
        :items="store.state.skills.value ?? []"
        :next-after="store.state.skills.nextAfter"
        :loading-more="store.state.skills.loadingMore"
        :error-message="store.state.skills.errorMessage"
        :error-status="store.state.skills.errorStatus"
        :online="online"
        :status-filter="statusFilter"
        :can-manage="canManage"
        :selected-skill-id="selectedSkillId"
        @select="library.select"
        @change-status="(value) => library.replaceFilter('status', value, 'ALL')"
        @load-more="loadMoreSkills"
        @create="createOpen = true"
        @distill="openDistill"
        @retry="library.reload"
      />

      <SkillDetail
        v-if="selectedSkillId"
        :skill-resource="detailResource"
        :command="commandForSelection"
        :history="history"
        :version-resource="versionResource"
        :effective="effective"
        :effective-revision="skill?.effectiveRevision ?? null"
        :selected-revision="selectedRevision"
        :tab="tab"
        :scope="draftScope!"
        :can-manage="canManage"
        :online="online"
        :baseline-content="baselineContent"
        @close="library.closeDetail"
        @save="input => saveDraft(selectedSkillId!, input)"
        @publish="requestPublish"
        @disable="requestDisable"
        @rollback="requestRollback"
        @change-tab="library.changeTab"
        @select-revision="library.selectRevision"
        @load-more-versions="library.loadMoreVersions"
        @retry-skill="library.retryDetail"
        @retry-versions="library.retryVersions"
      />
    </div>

    <ActionConfirmDialog
      v-if="actionIntent"
      :heading="actionHeading"
      eyebrow="Team skill"
      :subtitle="actionIntent.skillKey"
      :impact="actionImpact"
      :mode="actionIntent.kind === 'disable' ? 'reason' : 'plain'"
      :variant="actionIntent.kind === 'disable' ? 'danger' : 'primary'"
      reason-label="禁用原因（可选）"
      :reason-maxlength="SKILL_DISABLE_REASON_MAX"
      reason-placeholder="会展示给 Team 全体成员。"
      :confirm-label="actionConfirmLabel"
      close-label="取消操作"
      :submitting="actionPending"
      :error-message="actionError"
      @cancel="cancelAction"
      @confirm="confirmAction"
    />

    <SkillCreateDialog
      v-if="createOpen"
      :team-id="scope?.teamId ?? ''"
      :submitting="createPending"
      :error-message="createError"
      @close="createOpen = false; store.clearCommand()"
      @create="createSkill"
    />
    <SkillDistillationDialog
      v-if="distillOpen"
      :team-id="scope?.teamId ?? ''"
      :submitting="distillPending"
      :error-message="distillError"
      :receipt="distillReceipt"
      @close="closeDistill"
      @distill="distill"
      @open-skill="openDistilledSkill"
    />
  </AppShell>
</template>

<style scoped>
/* M13b-F09（S4）：未选中不再保留 44% 空栏——默认单栏列表，选中后右侧 340px 详情，≤900 下移。 */
.skills-workspace { display: grid; grid-template-columns: minmax(0, 1fr); gap: var(--cs-space-16); align-items: start; }.skills-workspace.has-detail { grid-template-columns: minmax(0, 1fr) 340px; }
@media (max-width: 900px) { .skills-workspace { grid-template-columns: 1fr; }.skills-workspace.has-detail { grid-template-columns: 1fr; } }
</style>
