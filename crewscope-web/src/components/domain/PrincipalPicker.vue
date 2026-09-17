<script setup lang="ts">
import { Bot, Check, User, X } from '@lucide/vue'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { principalDirectoryGateway, type PrincipalDirectoryGateway } from '../../domains/principal/gateway'
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
  gateway?: PrincipalDirectoryGateway
}>(), {
  kind: undefined,
  placeholder: '按姓名搜索',
  disabled: false,
  helpText: undefined,
  gateway: () => principalDirectoryGateway,
})

const emit = defineEmits<{ 'update:modelValue': [value: string | null] }>()

const domId = `principal-picker-${Math.random().toString(36).slice(2, 9)}`
const query = ref('')
const open = ref(false)
const phase = ref<'idle' | 'loading' | 'ready' | 'empty' | 'error'>('idle')
const errorMessage = ref<string | null>(null)
const entries = ref<PrincipalEntry[]>([])
const activeIndex = ref(-1)
const selected = ref<PrincipalEntry | null>(null)

let searchTimer: number | undefined
let searchVersion = 0
let controller: AbortController | null = null

const kindHint = computed(() => props.kind ? `仅显示${principalKindLabels[props.kind]}` : '成员与 Agent')
const optionId = (index: number): string => `${domId}-option-${index}`

// A selection made elsewhere (a reset, a restored draft) must clear the visible chip too.
watch(() => props.modelValue, value => {
  if (!value) selected.value = null
  else if (selected.value?.principalId !== value) {
    selected.value = entries.value.find(entry => entry.principalId === value) ?? null
  }
}, { immediate: true })

watch(query, () => {
  window.clearTimeout(searchTimer)
  if (!open.value) return
  searchTimer = window.setTimeout(() => void search(), 200)
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
  try {
    const page = await props.gateway.search(
      { ...props.scope, q: query.value.trim() || undefined, limit: 20 },
      controller.signal,
    )
    if (version !== searchVersion) return
    const matches = props.kind ? page.items.filter(entry => entry.kind === props.kind) : page.items
    entries.value = matches
    activeIndex.value = matches.length ? 0 : -1
    phase.value = matches.length ? 'ready' : 'empty'
  } catch (error) {
    if (version !== searchVersion || (error instanceof DOMException && error.name === 'AbortError')) return
    entries.value = []
    activeIndex.value = -1
    phase.value = 'error'
    errorMessage.value = '暂时无法加载团队成员，请稍后重试'
  }
}

function choose(entry: PrincipalEntry): void {
  selected.value = entry
  emit('update:modelValue', entry.principalId)
  open.value = false
  query.value = ''
}

function clear(): void {
  selected.value = null
  emit('update:modelValue', null)
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    open.value = false
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

    <div v-if="selected" class="principal-chip">
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
            <small>{{ entry.roles.length ? entry.roles.join(' · ') : principalKindLabels[entry.kind] }}</small>
          </span>
          <StatusBadge v-if="entry.status !== 'ACTIVE'" tone="warning">{{ principalStatusLabels[entry.status] }}</StatusBadge>
          <Check v-if="entry.principalId === modelValue" :size="13" />
        </button>
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
.principal-option, .principal-chip { display: grid; grid-template-columns: 24px minmax(0, 1fr) auto auto; align-items: center; gap: var(--cs-space-8); padding: var(--cs-space-8); border-radius: var(--cs-radius-sm); background: transparent; color: var(--cs-text); text-align: left; }
.principal-option { cursor: pointer; transition: background-color var(--cs-motion-fast) var(--cs-ease-out); }
.principal-option--active, .principal-option:hover { background: var(--cs-surface-accent); }
.principal-option i, .principal-chip i { display: grid; width: 24px; height: 24px; place-items: center; border-radius: 50%; background: var(--cs-surface-accent-strong); color: var(--cs-text-brand); }
.principal-chip__icon--agent { background: var(--cs-agent-soft) !important; color: var(--cs-agent) !important; }
.principal-option strong, .principal-option small, .principal-chip strong, .principal-chip small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.principal-option strong, .principal-chip strong { font-size: var(--cs-text-xs); }
.principal-option small, .principal-chip small { margin-top: var(--cs-space-2); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.principal-chip { grid-template-columns: 24px minmax(0, 1fr) auto; border: 1px solid var(--cs-border-strong); background: var(--cs-surface-subtle); }
</style>
