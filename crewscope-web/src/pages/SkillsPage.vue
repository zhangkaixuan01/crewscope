<script setup lang="ts">
import { secureId } from '../api/secureId'
import { ListPlus, RefreshCw, Sparkles } from '@lucide/vue'
import { computed, inject, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AUTH_PRINCIPAL, can, permissions } from '../app/auth'
import { useNetworkStatus } from '../app/network'
import BaseButton from '../components/base/BaseButton.vue'
import SkillActionConfirmDialog, { type SkillActionIntent } from '../components/domain/SkillActionConfirmDialog.vue'
import SkillCreateDialog from '../components/domain/SkillCreateDialog.vue'
import SkillDetail from '../components/domain/SkillDetail.vue'
import SkillDistillationDialog from '../components/domain/SkillDistillationDialog.vue'
import SkillList from '../components/domain/SkillList.vue'
import AppShell from '../components/layout/AppShell.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
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
import { skillStatuses } from '../domains/skill/types'
import type { SettingsScope } from '../domains/settings/types'
import { useScopeStore } from '../domains/scope/store'

/** Injection-attempt deep links carry skillKey·revision·hash — never a skill id (F02 D5). */
const DEEP_LINK_PAGE_CAP = 5

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
/** Skill-scoped commands mint their key here; create/distill dialogs own their own keys. */
const commandAttempt = ref<{ op: string, skillId: string, key: string } | null>(null)

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

// deepLinkKey is deliberately not a source here: the resolver watch clears it when it finishes,
// and that cleanup must not reset the miss note or re-read the catalog.
watch(
  () => [
    scopeStore.state.phase,
    scope.value?.organizationId,
    scope.value?.teamId,
    statusFilter.value,
  ] as const,
  async ([phase]) => {
    const pageOwner = pageRequests.capture()
    if (phase !== 'ready' || !scope.value) return
    store.activateScope(scope.value)
    commandAttempt.value = null
    deepLinkMiss.value = null
    deepLinkPages = 0
    store.clearCommand()
    await store.loadSkills(filter.value, false, true)
    if (!pageOwner.isCurrent()) return
  },
  { immediate: true },
)

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

watch(
  () => [scopeStore.state.phase, route.query.status, route.query.skill, route.query.skillKey, route.query.revision, route.query.tab] as const,
  ([phase]) => {
    if (phase !== 'ready') return
    const query = { ...route.query }
    let changed = false
    changed = normalizeQuery(query, 'status', statusFilter.value, 'ALL') || changed
    changed = normalizeQuery(query, 'tab', tab.value, 'draft') || changed
    if (route.query.skill != null && !selectedSkillId.value) {
      delete query.skill
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
  () => [scopeStore.state.phase, scope.value?.teamId, selectedSkillId.value, tab.value, selectedRevision.value] as const,
  ([phase, _teamId, skillId, currentTab, revision]) => {
    commandAttempt.value = null
    store.clearCommand()
    if (phase !== 'ready' || !scope.value || !skillId) return
    void store.loadSkill(skillId)
    // The effective revision feeds the editor diff baseline, so it loads on both tabs.
    void store.loadEffectiveVersion(skillId)
    if (currentTab === 'versions') {
      void store.loadVersions(skillId)
      if (revision) void store.loadVersion(skillId, revision)
    }
  },
  { immediate: true },
)

function selectSkill(skillId: string): void {
  const query = { ...route.query } as Record<string, string>
  delete query.revision
  query.skill = skillId
  void router.replace({ query })
}

function closeDetail(): void {
  const query = { ...route.query }
  delete query.skill
  delete query.revision
  void router.replace({ query })
}

function changeStatus(value: SkillStatus | 'ALL'): void {
  const query = { ...route.query }
  if (value === 'ALL') delete query.status
  else query.status = value
  delete query.skill
  delete query.revision
  void router.replace({ query })
}

function changeTab(next: 'draft' | 'versions'): void {
  const query = { ...route.query }
  if (next === 'draft') delete query.tab
  else query.tab = next
  if (next === 'versions' && selectedSkillId.value) {
    void store.loadVersions(selectedSkillId.value)
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
  await store.loadSkills(filter.value, false, true)
  if (selectedSkillId.value) await store.loadSkill(selectedSkillId.value, true)
  if (!pageOwner.isCurrent()) return
}

function retryDetail(): void {
  if (selectedSkillId.value) void store.loadSkill(selectedSkillId.value, true)
}

function retryVersions(): void {
  if (selectedSkillId.value) void store.loadVersions(selectedSkillId.value, false, true)
}

function loadMoreSkills(): void {
  void store.loadSkills(filter.value, true)
}

function loadMoreVersions(): void {
  if (selectedSkillId.value) void store.loadVersions(selectedSkillId.value, true)
}

function attemptKeyFor(op: string, skillId: string): string {
  const current = commandAttempt.value
  if (current && current.op === op && current.skillId === skillId) return current.key
  const next = { op, skillId, key: secureId() }
  commandAttempt.value = next
  return next.key
}

async function saveDraft(skillId: string, input: UpdateSkillDraftInput): Promise<void> {
  await runSkillCommand('save-draft', skillId, key => store.saveDraft(skillId, input, key))
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

async function confirmAction(reason: string | null): Promise<void> {
  const intent = actionIntent.value
  const skillId = selectedSkillId.value
  if (!intent || !skillId) return
  const op = intent.kind
  const done = await runSkillCommand(op, skillId, key => {
    if (intent.kind === 'publish') return store.publishSkill(skillId, key)
    if (intent.kind === 'disable') return store.disableSkill(skillId, reason, key)
    return store.rollbackSkill(skillId, intent.toRevision, key)
  })
  if (done) actionIntent.value = null
}

async function runSkillCommand(
  op: 'save-draft' | 'publish' | 'disable' | 'rollback',
  skillId: string,
  run: (key: string) => Promise<boolean>,
): Promise<boolean> {
  const pageOwner = pageRequests.captureSelection()
  if (!online.value) return false
  const success = await run(attemptKeyFor(op, skillId))
  if (!pageOwner.isCurrent()) return success
  if (success) {
    commandAttempt.value = null
    await refreshFacts(skillId)
    return true
  }
  if (store.state.command.phase === 'conflict') {
    // Drop the attempt key (the next submit is a new logical request) and reload facts
    // without touching the editor.
    commandAttempt.value = null
    await Promise.all([
      store.loadSkills(filter.value, false, true),
      store.loadSkill(skillId, true),
    ])
  } else if (!store.state.command.retryable) {
    commandAttempt.value = null
  }
  return false
}

async function refreshFacts(skillId: string): Promise<void> {
  await Promise.all([
    store.loadSkills(filter.value, false, true),
    store.loadSkill(skillId, true),
    store.loadVersions(skillId, false, true),
    store.loadEffectiveVersion(skillId, true),
  ])
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
  selectSkill(skillId)
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

function idleVersionPage(): SkillVersionPageResource {
  return { phase: 'idle', value: null, errorMessage: null, errorStatus: null, nextAfter: null, loadingMore: false }
}
</script>

<template>
  <AppShell title="Skill 目录" eyebrow="团队 · Skill 管理">
    <template #actions>
      <BaseButton variant="secondary" size="small" :disabled="!scope || !online" :aria-describedby="scopeMissing ? 'skills-scope-reason' : undefined" @click="reload"><RefreshCw :size="14" />刷新</BaseButton>
      <BaseButton v-if="canManage" variant="secondary" size="small" :disabled="!online" @click="createOpen = true"><ListPlus :size="14" />创建 Skill</BaseButton>
      <!-- Distillation stays for every member: the right belongs to the execution creator (§8). -->
      <BaseButton variant="secondary" size="small" :disabled="!online" @click="openDistill"><Sparkles :size="14" />从执行蒸馏</BaseButton>
    </template>

    <StatePanel v-if="scopeMissing" id="skills-scope-reason" state="empty" title="请选择 Team" description="Skill 目录始终属于明确的 Organization 与 Team。" />
    <StatePanel v-else-if="deepLinkMiss" compact state="empty" :title="`未在目录中找到 Skill ${deepLinkMiss}`" description="它可能属于其他团队、已被移除，或超出本次翻页范围。" />

    <div v-if="scopeStore.state.phase === 'ready' && scope" class="skills-workspace">
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
        @select="selectSkill"
        @change-status="changeStatus"
        @load-more="loadMoreSkills"
        @create="createOpen = true"
        @distill="openDistill"
        @retry="reload"
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
        @close="closeDetail"
        @save="input => saveDraft(selectedSkillId!, input)"
        @publish="requestPublish"
        @disable="requestDisable"
        @rollback="requestRollback"
        @change-tab="changeTab"
        @select-revision="selectRevision"
        @load-more-versions="loadMoreVersions"
        @retry-skill="retryDetail"
        @retry-versions="retryVersions"
      />
    </div>

    <SkillActionConfirmDialog
      v-if="actionIntent"
      :intent="actionIntent"
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
.skills-workspace { display: grid; grid-template-columns: minmax(0, 5fr) minmax(0, 4fr); gap: var(--cs-space-16); align-items: start; }
@media (max-width: 767px) { .skills-workspace { grid-template-columns: 1fr; } }
</style>
