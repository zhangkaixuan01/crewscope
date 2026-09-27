<script setup lang="ts">
import { Bot, Check, User, X } from '@lucide/vue'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { principalDirectoryGateway, type PrincipalDirectoryGateway } from '../../domains/principal/gateway'
import { isImeComposition } from '../../composables/useImeGuard'
import { principalKindLabels, principalStatusLabels } from '../../domains/principal/labels'
import type { PrincipalEntry, PrincipalKind, PrincipalScope } from '../../domains/principal/types'
import BaseButton from '../base/BaseButton.vue'
import BaseInput from '../base/BaseInput.vue'
import StatusBadge from '../base/StatusBadge.vue'

/**
 * Picks a Team subject by name instead of asking anyone to paste a UUID.
 *
 * A Principal ID is a database fact, not something a member can be expected to know, type or
 * verify. This component is the only sanctioned way to name a subject in a form: it searches the
 * member-safe A07 directory, shows who the match actually is, and hands the caller the identifier
 * it resolved. Callers therefore never need a free-text identifier field at all.
 */
const props = withDefaults(defineProps<{
  scope: PrincipalScope
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

const emit = defineEmits<{ 'update:modelValue': [value: string | null] }>()

const domId = `principal-picker-${Math.random().toString(36).slice(2, 9)}`
const query = ref('')
const open = ref(false)
/** A chosen subject stays as the chip while the member searches for a replacement (R21). */
const replacing = ref(false)
const phase = ref<'idle' | 'loading' | 'ready' | 'empty' | 'error'>('idle')
const errorMessage = ref<string | null>(null)
const entries = ref<PrincipalEntry[]>([])
const activeIndex = ref(-1)
const selected = ref<PrincipalEntry | null>(null)
const nextCursor = ref<string | null>(null)
const loadingMore = ref(false)
const loadMoreError = ref<string | null>(null)

let searchTimer: number | undefined
let searchVersion = 0
let controller: AbortController | null = null

const kindHint = computed(() => props.kind ? `仅显示${principalKindLabels[props.kind]}` : '成员与 Agent')
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

// Candidates belong to one Team scope; switching it must not leave another Team's names on screen.
watch(() => [props.scope.organizationId, props.scope.teamId] as const, () => {
  window.clearTimeout(searchTimer)
  controller?.abort()
  searchVersion += 1
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
  controller?.abort()
})

function focusInput(): void {
  open.value = true
  if (phase.value === 'idle') void search()
}

async function search(): Promise<void> {
  const version = ++searchVersion
  controller?.abort()
  controller = new AbortController()
  phase.value = 'loading'
  errorMessage.value = null
  loadMoreError.value = null
  try {
    const page = await props.gateway.search(
      // The kind filter rides the request so pagination walks the filtered set (R21),
      // not a client-side slice of an unfiltered page.
      { ...props.scope, q: query.value.trim() || undefined, types: props.kind ? [props.kind] : undefined, purpose: props.purpose, limit: 20 },
      controller.signal,
    )
    if (version !== searchVersion) return
    entries.value = page.items
    nextCursor.value = page.nextCursor
    activeIndex.value = page.items.length ? 0 : -1
    phase.value = page.items.length ? 'ready' : 'empty'
  } catch (error) {
    if (version !== searchVersion || (error instanceof DOMException && error.name === 'AbortError')) return
    entries.value = []
    nextCursor.value = null
    activeIndex.value = -1
    phase.value = 'error'
    errorMessage.value = '暂时无法加载团队成员，请稍后重试'
  }
}

/** Appends the next directory page for the same committed query; never resets the visible rows. */
async function loadMore(): Promise<void> {
  if (!nextCursor.value || loadingMore.value) return
  const version = searchVersion
  loadingMore.value = true
  loadMoreError.value = null
  try {
    const page = await props.gateway.search(
      { ...props.scope, q: query.value.trim() || undefined, types: props.kind ? [props.kind] : undefined, purpose: props.purpose, limit: 20, after: nextCursor.value },
    )
    if (version !== searchVersion) return
    const known = new Set(entries.value.map(entry => entry.principalId))
    entries.value = [...entries.value, ...page.items.filter(entry => !known.has(entry.principalId))]
    nextCursor.value = page.nextCursor
  } catch {
    // The rows already on screen stay; only the continuation failed, so surface it beside the
    // button instead of throwing through the click handler or swapping the list for the error state.
    if (version === searchVersion) loadMoreError.value = '暂时无法加载更多候选，请稍后重试'
  } finally {
    loadingMore.value = false
  }
}

function choose(entry: PrincipalEntry): void {
  selected.value = entry
  replacing.value = false
  emit('update:modelValue', entry.principalId)
  open.value = false
  query.value = ''
}

function clear(): void {
  selected.value = null
  emit('update:modelValue', null)
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
        <small>{{ principalKindLabels[selected.kind] }} · {{ principalStatusLabels[selected.status] }}</small>
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
          {{ query.trim() ? `没有匹配「${query.trim()}」的${kindHint}` : `当前团队没有可选的${kindHint}` }}
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
            <small>{{ principalKindLabels[entry.kind] }}{{ entry.roles.length ? ` · ${entry.roles.join(' · ')}` : '' }}</small>
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
