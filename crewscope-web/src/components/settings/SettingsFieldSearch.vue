<script setup lang="ts">
import { computed, inject, onUnmounted, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { SCOPE_STORE } from '../../domains/scope/store'
import { SETUP_STORE } from '../../domains/setup/store'
import type { SetupStore } from '../../domains/setup/store'
import { configurationSearchTarget } from '../../domains/setup/route'
import type { SettingsScope } from '../../domains/settings/types'

/**
 * Field-level configuration search (`GET …/configuration-search`) inside the shared Settings nav.
 *
 * The shell owns the input — the nav already had a filter box, and a second one would both confuse
 * the member and shift every settings page's layout — so this component only turns the shell's
 * query into a debounced server call and renders the hits under the input.
 */
const props = defineProps<{ query: string }>()

/*
 * `inject` with a default rather than `useSetupStore()`/`useScopeStore()`: the store is installed by
 * `main.ts`, and the settings page specs mount this shell with only what the page itself needs.
 * Without the store the block renders nothing, which is also the honest state for a deployment that
 * does not serve the projection.
 */
const store = inject<SetupStore | null>(SETUP_STORE, null)
const scopeStore = inject(SCOPE_STORE, null)

const DEBOUNCE_MS = 250
/** The boundary rejects anything outside 1–100 characters; the UI never sends a query it would reject. */
const MAX_QUERY_LENGTH = 100

const teamId = computed(() => scopeStore?.state.selectedTeamId ?? null)
const scope = computed<SettingsScope | null>(() => {
  const team = scopeStore?.selectedTeam.value
  return team && teamId.value ? { organizationId: team.organizationId, teamId: teamId.value } : null
})
const term = computed(() => props.query.trim())
const tooLong = computed(() => term.value.length > MAX_QUERY_LENGTH)
const active = computed(() => Boolean(term.value) && !tooLong.value)
const visible = computed(() => Boolean(store) && (tooLong.value || active.value))
const phase = computed(() => store?.state.searchPhase ?? 'idle')
const searchQuery = computed(() => store?.state.searchQuery ?? '')
/** Hits describe the query that was actually sent; while the input keeps moving they are withheld. */
const settled = computed(() => active.value && searchQuery.value === term.value)
const hits = computed(() => {
  const team = teamId.value
  if (!settled.value || !team) return []
  return (store?.state.searchResults ?? []).flatMap(hit => {
    const to = configurationSearchTarget(hit.route, team)
    // A server route this shell cannot normalise is dropped rather than rendered as a dead link.
    return to ? [{ key: `${hit.profileId}:${hit.revision}:${hit.field}`, label: hit.label, field: hit.field, to }] : []
  })
})

let timer: ReturnType<typeof setTimeout> | null = null

function cancelTimer(): void {
  if (timer === null) return
  clearTimeout(timer)
  timer = null
}

function retry(): void {
  if (!store || !active.value) return
  void store.search(term.value)
}

watch(term, value => {
  cancelTimer()
  if (!store) return
  if (!value || value.length > MAX_QUERY_LENGTH) {
    store.clearSearch()
    return
  }
  timer = setTimeout(() => {
    timer = null
    void store.search(value)
  }, DEBOUNCE_MS)
}, { immediate: true })

watch(scope, value => {
  if (!store || !value) return
  // A query that arrives already formed is not something to debounce; the pending timer would only duplicate it.
  cancelTimer()
  store.activateScope(value)
  // `activateScope` drops the previous Team's results; the query in the input is re-issued against the new one.
  if (active.value) void store.search(term.value)
}, { immediate: true })

onUnmounted(cancelTimer)
</script>

<template>
  <div v-if="visible" class="field-search">
    <p v-if="tooLong" class="field-search__hint" role="status">字段搜索最多 {{ MAX_QUERY_LENGTH }} 个字符，请缩短查询。</p>
    <template v-else>
      <p v-if="!settled || phase === 'loading'" class="field-search__hint" role="status">正在搜索配置字段…</p>
      <p v-else-if="phase === 'offline'" class="field-search__hint" role="status">离线：无法搜索配置字段。</p>
      <p v-else-if="phase === 'forbidden'" class="field-search__hint" role="status">需要 Team 成员权限才能搜索配置字段。</p>
      <p v-else-if="phase === 'unavailable'" class="field-search__hint" role="status">当前部署没有提供配置字段搜索。</p>
      <p v-else-if="phase === 'error'" class="field-search__hint" role="status">
        <span>配置搜索暂时不可用。</span><button type="button" @click="retry">重试</button>
      </p>
      <ul v-else-if="hits.length" class="field-search__results" aria-label="配置字段搜索结果">
        <li v-for="hit in hits" :key="hit.key">
          <RouterLink :to="hit.to"><strong>{{ hit.label }}</strong><small>{{ hit.field }}</small></RouterLink>
        </li>
      </ul>
      <p v-else class="field-search__hint" role="status">没有匹配的配置字段</p>
    </template>
  </div>
</template>

<style scoped>
.field-search { display: grid; gap: var(--cs-space-4); min-width: 0; }
.field-search__hint { display: flex; align-items: center; gap: var(--cs-space-8); margin: 0; padding: var(--cs-space-4) var(--cs-space-8); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.field-search__hint button { padding: 0 var(--cs-space-4); border-radius: var(--cs-radius-sm); color: var(--cs-text-brand); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); text-decoration: underline; cursor: pointer; }
.field-search__results { display: grid; gap: var(--cs-space-2); margin: 0; padding: 0; list-style: none; }
.field-search__results a { display: grid; gap: var(--cs-space-2); min-height: var(--cs-density-control-height); align-content: center; padding: var(--cs-space-4) var(--cs-space-8); border-radius: var(--cs-radius-sm); color: var(--cs-text-secondary); text-decoration: none; }
.field-search__results a:hover { background: var(--cs-surface-accent); color: var(--cs-text-brand-strong); }
.field-search__results strong { font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.field-search__results small { overflow: hidden; color: var(--cs-text-muted); font-family: var(--cs-font-mono); font-size: var(--cs-text-xs); text-overflow: ellipsis; white-space: nowrap; }
/*
 * 窄屏下导航是横向滚动的一行；命中区自己占一行，避免把 190px 的命中块挤到内容里造成横向溢出。
 */
@media (max-width: 767px) { .field-search { flex: 1 0 100%; } }
</style>
