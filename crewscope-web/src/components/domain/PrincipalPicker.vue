<script setup lang="ts">
import { Bot, Check, User, X } from '@lucide/vue'
import { computed, onBeforeUnmount, ref, useId, watch } from 'vue'
import { isAbort } from '../../api/errors'
import { createRequestScope } from '../../api/requestScope'
import { principalDirectoryGateway, type PrincipalDirectoryGateway } from '../../domains/principal/gateway'
import { isImeComposition } from '../../composables/useImeGuard'
import { principalKindLabels, principalStatusLabels } from '../../domains/principal/labels'
import type { OrganizationMemberEntry, OrganizationScope, PrincipalEntry, PrincipalKind, PrincipalScope } from '../../domains/principal/types'
import BaseButton from '../base/BaseButton.vue'
import BaseInput from '../base/BaseInput.vue'
import StatusBadge from '../base/StatusBadge.vue'

/**
 * Picks a subject by name instead of asking anyone to paste a UUID.
 *
 * A Principal ID is a database fact, not something a member can be expected to know, type or
 * verify. This component is the only sanctioned way to name a subject in a form: it searches the
 * member-safe A07 directory (Team scope) or the org-grain member directory (org scope, M13b-A01),
 * shows who the match actually is, and hands the caller the identifier it resolved. Callers
 * therefore never need a free-text identifier field at all.
 */

/** One candidate in either directory, flattened for rendering; org-only facts ride along. */
export interface PickerSelection {
  principalId: string
  displayName: string
  status: PrincipalEntry['status']
  kind: PrincipalKind
  /** Listbox second line: kind · roles (team) or username · org roles (org). */
  optionSubtitle: string
  /** Chip second line: kind · status (team) or username · status (org). */
  chipSubtitle: string
  /** Org directory only — the account coordinate deletion workflows and echoes key on. */
  accountId?: string
  /** Org directory only — the username the deletion confirmation token echoes. */
  username?: string
}

function fromTeamEntry(entry: PrincipalEntry): PickerSelection {
  return {
    principalId: entry.principalId,
    displayName: entry.displayName,
    status: entry.status,
    kind: entry.kind,
    optionSubtitle: `${principalKindLabels[entry.kind]}${entry.roles.length ? ` · ${entry.roles.join(' · ')}` : ''}`,
    chipSubtitle: `${principalKindLabels[entry.kind]} · ${principalStatusLabels[entry.status]}`,
  }
}

function fromOrgEntry(entry: OrganizationMemberEntry): PickerSelection {
  return {
    principalId: entry.principalId,
    displayName: entry.displayName,
    status: entry.status,
    kind: 'USER',
    optionSubtitle: `${entry.username}${entry.orgRoles.length ? ` · ${entry.orgRoles.join(' · ')}` : ''}`,
    chipSubtitle: `${entry.username} · ${principalStatusLabels[entry.status]}`,
    accountId: entry.accountId,
    username: entry.username,
  }
}

const props = withDefaults(defineProps<{
  /** Team scope searches the A07 directory; an org scope (no teamId) searches the A01 directory. */
  scope: PrincipalScope | OrganizationScope
  modelValue: string | null
  label: string
  /** Restricts the directory to one subject category; both are offered when omitted. */
  kind?: PrincipalKind
  placeholder?: string
  disabled?: boolean
  helpText?: string
  /** AUDIT searches historical Team identities too, not just the currently seated members. */
  purpose?: 'AUDIT'
  gateway?: PrincipalDirectoryGateway
}>(), {
  kind: undefined,
  placeholder: '按姓名搜索',
  disabled: false,
  helpText: undefined,
  purpose: undefined,
  gateway: () => principalDirectoryGateway,
})

const emit = defineEmits<{
  'update:modelValue': [value: string | null]
  /** Fires with the full selection on every choose/clear — org scopes carry accountId/username. */
  pick: [selection: PickerSelection | null]
}>()

const domId = `principal-picker-${useId()}`
const query = ref('')
const open = ref(false)
/** A chosen subject stays as the chip while the member searches for a replacement (R21). */
const replacing = ref(false)
const phase = ref<'idle' | 'loading' | 'ready' | 'empty' | 'error'>('idle')
const errorMessage = ref<string | null>(null)
const entries = ref<PickerSelection[]>([])
const activeIndex = ref(-1)
const selected = ref<PickerSelection | null>(null)
const nextCursor = ref<string | null>(null)
const loadingMore = ref(false)
const loadMoreError = ref<string | null>(null)

/** Org-grain scopes read the A01 member directory (governance forms); teams keep the A07 path. */
/** Team vs org is one structural fact: the teamId key (M13b-A01). Narrows the props union. */
function isPrincipalScope(scope: PrincipalScope | OrganizationScope): scope is PrincipalScope {
  return 'teamId' in scope
}
const isOrgScope = computed(() => !isPrincipalScope(props.scope))

let searchTimer: number | undefined
// The scope is the coordinate (M12b-F05): switching it voids every in-flight search —
// the version counter + controller this replaces.
const scopeKey = computed(() => JSON.stringify(props.scope))
const requests = createRequestScope(() => scopeKey.value)

const kindHint = computed(() => {
  if (isOrgScope.value) return '组织成员'
  return props.kind ? `仅显示${principalKindLabels[props.kind]}` : '成员与 Agent'
})
const loadFailure = computed(() => isOrgScope.value ? '暂时无法加载组织成员，请稍后重试' : '暂时无法加载团队成员，请稍后重试')
const emptyHint = computed(() => isOrgScope.value ? '当前组织没有可选的成员' : `当前团队没有可选的${kindHint.value}`)
const optionId = (index: number): string => `${domId}-option-${index}`

// A selection made elsewhere (a reset, a restored draft) must clear the visible chip too.
watch(() => props.modelValue, value => {
  if (!value) { selected.value = null; replacing.value = false }
  else if (selected.value?.principalId !== value) {
    selected.value = entries.value.find(entry => entry.principalId === value) ?? null
  }
}, { immediate: true })

watch(query, () => {
  window.clearTimeout(searchTimer)
  if (!open.value) return
  searchTimer = window.setTimeout(() => void search(), 200)
})

// Candidates belong to one scope; switching it must not leave another scope's names on screen.
watch(scopeKey, () => {
  window.clearTimeout(searchTimer)
  requests.invalidate()
  open.value = false
  replacing.value = false
  query.value = ''
  entries.value = []
  nextCursor.value = null
  activeIndex.value = -1
  phase.value = 'idle'
  errorMessage.value = null
  loadMoreError.value = null
})

onBeforeUnmount(() => {
  window.clearTimeout(searchTimer)
  requests.dispose()
})

function focusInput(): void {
  open.value = true
  if (phase.value === 'idle') void search()
}

async function search(): Promise<void> {
  const request = requests.begin('search')
  phase.value = 'loading'
  errorMessage.value = null
  loadMoreError.value = null
  // A local copy lets the structural guard narrow the union for both branches below.
  const scope = props.scope
  try {
    // The kind filter rides the request so pagination walks the filtered set (R21),
    // not a client-side slice of an unfiltered page.
    let items: PickerSelection[]
    let cursor: string | null
    if (isPrincipalScope(scope)) {
      const page = await props.gateway.search(
        { ...scope, q: query.value.trim() || undefined, types: props.kind ? [props.kind] : undefined, purpose: props.purpose, limit: 20 },
        request.signal,
      )
      if (!request.isCurrent()) return
      items = page.items.map(fromTeamEntry)
      cursor = page.nextCursor
    } else {
      const page = await props.gateway.searchOrganizationMembers(
        { organizationId: scope.organizationId, namePrefix: query.value.trim() || undefined, limit: 20 },
        request.signal,
      )
      if (!request.isCurrent()) return
      items = page.items.map(fromOrgEntry)
      cursor = page.nextCursor
    }
    entries.value = items
    nextCursor.value = cursor
    activeIndex.value = items.length ? 0 : -1
    phase.value = items.length ? 'ready' : 'empty'
  } catch (error) {
    if (!request.isCurrent() || isAbort(error)) return
    entries.value = []
    nextCursor.value = null
    activeIndex.value = -1
    phase.value = 'error'
    errorMessage.value = loadFailure.value
  } finally {
    request.finish()
  }
}

/** Appends the next directory page for the same committed query; never resets the visible rows. */
async function loadMore(): Promise<void> {
  if (!nextCursor.value || loadingMore.value) return
  const owner = requests.capture()
  loadingMore.value = true
  loadMoreError.value = null
  const scope = props.scope
  try {
    let incoming: PickerSelection[]
    let cursor: string | null
    if (isPrincipalScope(scope)) {
      const page = await props.gateway.search(
        { ...scope, q: query.value.trim() || undefined, types: props.kind ? [props.kind] : undefined, purpose: props.purpose, limit: 20, after: nextCursor.value },
      )
      if (!owner.isCurrent()) return
      incoming = page.items.map(fromTeamEntry)
      cursor = page.nextCursor
    } else {
      const page = await props.gateway.searchOrganizationMembers(
        { organizationId: scope.organizationId, namePrefix: query.value.trim() || undefined, limit: 20, after: nextCursor.value },
      )
      if (!owner.isCurrent()) return
      incoming = page.items.map(fromOrgEntry)
      cursor = page.nextCursor
    }
    const known = new Set(entries.value.map(entry => entry.principalId))
    entries.value = [...entries.value, ...incoming.filter(entry => !known.has(entry.principalId))]
    nextCursor.value = cursor
  } catch {
    // The rows already on screen stay; only the continuation failed, so surface it beside the
    // button instead of throwing through the click handler or swapping the list for the error state.
    if (owner.isCurrent()) loadMoreError.value = '暂时无法加载更多候选，请稍后重试'
  } finally {
    loadingMore.value = false
  }
}

function choose(entry: PickerSelection): void {
  selected.value = entry
  replacing.value = false
  emit('update:modelValue', entry.principalId)
  emit('pick', entry)
  open.value = false
  query.value = ''
}

function clear(): void {
  selected.value = null
  emit('update:modelValue', null)
  emit('pick', null)
}

/** Re-opens the search box without dropping the current value first (R21). */
function startReplacing(): void {
  replacing.value = true
  open.value = true
  if (phase.value === 'idle') void search()
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    // Cancelling the listbox (or a replacement) keeps the previous subject; nothing is emitted,
    // and the consumed key must not bubble on to close the drawer behind the picker.
    if (open.value) {
      event.stopPropagation()
      open.value = false
      replacing.value = false
    }
    return
  }
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault()
    if (!open.value) { focusInput(); return }
    if (!entries.value.length) return
    const step = event.key === 'ArrowDown' ? 1 : -1
    activeIndex.value = (activeIndex.value + step + entries.value.length) % entries.value.length
    return
  }
  if (event.key === 'Enter') {
    // IME confirmation must never submit the highlighted candidate (R27).
    if (isImeComposition(event)) return
    const entry = entries.value[activeIndex.value]
    if (!entry) return
    event.preventDefault()
    choose(entry)
  }
}
</script>

<template>
  <div class="principal-picker" :class="{ 'principal-picker--disabled': disabled }">
    <label :for="`${domId}-input`">{{ label }}</label>

    <div v-if="selected && !replacing" class="principal-chip">
      <i :class="`principal-chip__icon--${selected.kind.toLowerCase()}`">
        <Bot v-if="selected.kind === 'AGENT'" :size="13" />
        <User v-else :size="13" />
      </i>
      <span>
        <strong>{{ selected.displayName }}</strong>
        <small>{{ selected.chipSubtitle }}</small>
      </span>
      <BaseButton
        v-if="!disabled"
        variant="ghost"
        size="small"
        :aria-label="`更换${label}`"
        @click="startReplacing"
      >更换</BaseButton>
      <BaseButton
        v-if="!disabled"
        variant="ghost"
        size="small"
        :aria-label="`清除已选择的${label}`"
        @click="clear"
      ><X :size="13" /></BaseButton>
    </div>

    <div v-else class="principal-search">
      <BaseInput
        :id="`${domId}-input`"
        v-model="query"
        role="combobox"
        aria-autocomplete="list"
        :aria-expanded="open"
        :aria-controls="`${domId}-listbox`"
        :aria-activedescendant="open && activeIndex >= 0 ? optionId(activeIndex) : undefined"
        :aria-describedby="`${domId}-help`"
        :placeholder="placeholder"
        :disabled="disabled"
        @focus="focusInput"
        @keydown="onKeydown"
      />
      <p :id="`${domId}-help`" class="principal-help">{{ helpText ?? `按姓名搜索，${kindHint}；无需填写任何标识符。` }}</p>

      <div v-if="open" :id="`${domId}-listbox`" class="principal-listbox" role="listbox" :aria-label="label">
        <p v-if="phase === 'loading'" class="principal-state">正在搜索…</p>
        <p v-else-if="phase === 'error'" class="principal-state principal-state--error" role="alert">
          {{ errorMessage }}
          <button type="button" @click="search">重试</button>
        </p>
        <p v-else-if="phase === 'empty'" class="principal-state">
          {{ query.trim() ? `没有匹配「${query.trim()}」的${kindHint}` : emptyHint }}
        </p>
        <button
          v-for="(entry, index) in entries"
          :key="entry.principalId"
          :id="optionId(index)"
          type="button"
          role="option"
          class="principal-option"
          :class="{ 'principal-option--active': index === activeIndex }"
          :aria-selected="entry.principalId === modelValue"
          @mouseenter="activeIndex = index"
          @click="choose(entry)"
        >
          <i>
            <Bot v-if="entry.kind === 'AGENT'" :size="13" />
            <User v-else :size="13" />
          </i>
          <span>
            <strong>{{ entry.displayName }}</strong>
            <small>{{ entry.optionSubtitle }}</small>
          </span>
          <StatusBadge v-if="entry.status !== 'ACTIVE'" tone="warning">{{ principalStatusLabels[entry.status] }}</StatusBadge>
          <Check v-if="entry.principalId === modelValue" :size="13" />
        </button>
        <p v-if="loadMoreError" class="principal-state principal-state--error" role="alert">
          {{ loadMoreError }}
          <button type="button" @click="loadMore">重试</button>
        </p>
        <button
          v-if="nextCursor && entries.length"
          type="button"
          class="principal-more"
          :disabled="loadingMore"
          @click="loadMore"
        >{{ loadingMore ? '正在加载…' : '加载更多候选' }}</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.principal-picker { display: grid; gap: var(--cs-space-4); }
.principal-picker > label { color: var(--cs-text-secondary); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }
.principal-picker--disabled { opacity: .6; }
.principal-search { position: relative; display: grid; gap: var(--cs-space-4); }
.principal-help { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); line-height: var(--cs-leading-normal); }
.principal-listbox { position: absolute; z-index: var(--cs-z-popover); top: 100%; right: 0; left: 0; display: grid; max-height: 232px; overflow-y: auto; padding: var(--cs-space-4); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface-raised); box-shadow: var(--cs-shadow-float); }
.principal-state { display: flex; align-items: center; justify-content: space-between; gap: var(--cs-space-8); margin: 0; padding: var(--cs-space-12); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.principal-state--error { color: var(--cs-danger); }
.principal-state button { color: inherit; font-weight: var(--cs-weight-semibold); text-decoration: underline; cursor: pointer; }
.principal-more { padding: var(--cs-space-8); border: 0; border-top: 1px solid var(--cs-border); background: transparent; color: var(--cs-text-brand); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); cursor: pointer; }
.principal-more:disabled { color: var(--cs-text-muted); cursor: default; }
.principal-option, .principal-chip { display: grid; grid-template-columns: 24px minmax(0, 1fr) auto auto; align-items: center; gap: var(--cs-space-8); padding: var(--cs-space-8); border-radius: var(--cs-radius-sm); background: transparent; color: var(--cs-text); text-align: left; }
.principal-option { cursor: pointer; transition: background-color var(--cs-motion-fast) var(--cs-ease-out); }
.principal-option--active, .principal-option:hover { background: var(--cs-surface-accent); }
.principal-option i, .principal-chip i { display: grid; width: 24px; height: 24px; place-items: center; border-radius: 50%; background: var(--cs-surface-accent-strong); color: var(--cs-text-brand); }
.principal-chip__icon--agent { background: var(--cs-agent-soft) !important; color: var(--cs-agent) !important; }
.principal-option strong, .principal-option small, .principal-chip strong, .principal-chip small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.principal-option strong, .principal-chip strong { font-size: var(--cs-text-xs); }
.principal-option small, .principal-chip small { margin-top: var(--cs-space-2); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.principal-chip { grid-template-columns: 24px minmax(0, 1fr) auto auto; border: 1px solid var(--cs-border-strong); background: var(--cs-surface-subtle); }
</style>
