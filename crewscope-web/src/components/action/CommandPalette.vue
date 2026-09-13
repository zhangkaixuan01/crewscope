<script setup lang="ts">
import { ArrowRight, Command, Search, X } from '@lucide/vue'
import { computed, inject, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AUTH_PRINCIPAL } from '../../app/auth'
import { ACTION_REGISTRY, type ActionContext, type ActionEntry, type ActionRegistry } from '../../app/actionRegistry'
import { COMMAND_PALETTE_EVENT, SHORTCUT_HELP_EVENT, SHORTCUT_MANAGER, type ShortcutManager } from '../../app/shortcuts'
import { useFocusTrap } from '../../composables/useFocusTrap'
import { SCOPE_STORE } from '../../domains/scope/store'
import { SEARCH_STORE, type SearchStore } from '../../domains/search/store'
import type { SearchResultItem } from '../../domains/search/types'
import { searchObjectTypeLabels } from '../../domains/search/labels'

interface RecentItem {
  kind: 'action' | 'object'
  id: string
  label: string
  route?: string
}

const registry = inject<ActionRegistry | null>(ACTION_REGISTRY, null)
const shortcutManager = inject<ShortcutManager | null>(SHORTCUT_MANAGER, null)
const principal = inject(AUTH_PRINCIPAL, null)
const scopeStore = inject(SCOPE_STORE, null)
const searchStore = inject<SearchStore | null>(SEARCH_STORE, null)
const route = useRoute()
const router = useRouter()
const open = ref(false)
const helpOpen = ref(false)
const query = ref('')
const selectedIndex = ref(0)
const surface = ref<HTMLElement | null>(null)
const recent = ref<RecentItem[]>(readRecent())
useFocusTrap(surface, open)

const context = computed<ActionContext>(() => ({ router, route, principal }))
const entries = computed<ActionEntry[]>(() => registry?.list(context.value, true) ?? [])
const normalizedQuery = computed(() => query.value.trim().toLocaleLowerCase())
const filteredEntries = computed(() => entries.value.filter(entry => {
  if (!normalizedQuery.value) return true
  const action = entry.action
  return [action.id, action.label, action.description ?? '', ...(action.keywords ?? [])]
    .join(' ').toLocaleLowerCase().includes(normalizedQuery.value)
}))
const availableEntries = computed(() => filteredEntries.value.filter(entry => entry.available))
const unavailableEntries = computed(() => filteredEntries.value.filter(entry => !entry.available))
const objectItems = computed(() => searchStore?.state.result?.items ?? [])
const objectSearchLoading = computed(() => searchStore?.state.phase === 'loading')
const objectSearchOffline = computed(() => searchStore?.state.phase === 'offline')
const objectSearchError = computed(() => searchStore?.state.phase === 'error')
const hasResults = computed(() => availableEntries.value.length > 0 || objectItems.value.length > 0 || unavailableEntries.value.length > 0)
const recentItems = computed(() => {
  if (normalizedQuery.value) return []
  return recent.value.map(item => {
    if (item.kind === 'action') {
      const entry = entries.value.find(candidate => candidate.action.id === item.id)
      return entry?.available ? { ...item, action: entry.action } : null
    }
    return item
  }).filter((item): item is RecentItem & { action?: ActionEntry['action'] } => item !== null)
})

let searchTimer: number | null = null
function onOpen(): void {
  open.value = true
  helpOpen.value = false
  query.value = ''
  selectedIndex.value = 0
  // A02 results are shared with the full Search page; clear them so a new palette session
  // never shows a result for a query the user did not enter in this session.
  if (searchStore) void searchStore.search({ text: '' })
  void nextTick(() => surface.value?.querySelector<HTMLInputElement>('input')?.focus())
}
function onHelp(): void { open.value = true; helpOpen.value = true; query.value = ''; selectedIndex.value = 0 }
function close(): void { open.value = false; helpOpen.value = false; shortcutManager?.closeHelp(); query.value = '' }

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') { event.preventDefault(); close(); return }
  if (helpOpen.value || !hasResults.value || selectableCount.value === 0) return
  if (event.key === 'ArrowDown') { event.preventDefault(); selectedIndex.value = Math.min(selectedIndex.value + 1, selectableCount.value - 1); scrollSelectedIntoView(); return }
  if (event.key === 'ArrowUp') { event.preventDefault(); selectedIndex.value = Math.max(selectedIndex.value - 1, 0); scrollSelectedIntoView(); return }
  if (event.key === 'Enter') { event.preventDefault(); void executeSelected() }
}

const selectableCount = computed(() => availableEntries.value.length + objectItems.value.length)
function scrollSelectedIntoView(): void { void nextTick(() => surface.value?.querySelector<HTMLElement>('[aria-selected="true"]')?.scrollIntoView?.({ block: 'nearest' })) }

async function executeSelected(): Promise<void> {
  if (selectedIndex.value < availableEntries.value.length) {
    await executeAction(availableEntries.value[selectedIndex.value].action.id)
    return
  }
  const item = objectItems.value[selectedIndex.value - availableEntries.value.length]
  if (item) openObject(item)
}

async function executeAction(id: string): Promise<void> {
  if (!registry) return
  const executed = await registry.execute(id, context.value)
  if (!executed) return
  remember({ kind: 'action', id, label: registry.get(id)?.label ?? id })
  close()
}

function openObject(item: SearchResultItem): void {
  remember({ kind: 'object', id: `${item.objectType}:${item.objectId}`, label: item.title, route: item.route })
  close()
  void router.push(item.route)
}

function onQueryChanged(): void {
  selectedIndex.value = 0
  if (searchTimer !== null && typeof window !== 'undefined') window.clearTimeout(searchTimer)
  if (!searchStore || !scopeStore || !principal || normalizedQuery.value.length < 2 || !scopeStore.state.selectedTeamId) {
    if (searchStore) void searchStore.search({ text: '' })
    return
  }
  if (typeof window !== 'undefined') searchTimer = window.setTimeout(() => {
    searchTimer = null
    const teamId = scopeStore.state.selectedTeamId
    if (!teamId) return
    searchStore.activateScope({ organizationId: principal.organizationId, teamId })
    void searchStore.search({ text: query.value, projectId: scopeStore.state.selectedProjectId })
  }, 180)
}

function remember(item: RecentItem): void {
  recent.value = [item, ...recent.value.filter(existing => !(existing.kind === item.kind && existing.id === item.id))].slice(0, 8)
  try { localStorage.setItem('cs.pref.device.command-recent.v1', JSON.stringify(recent.value)) } catch { /* Storage can be unavailable in private browsing. */ }
}

function readRecent(): RecentItem[] {
  try {
    const value: unknown = JSON.parse(localStorage.getItem('cs.pref.device.command-recent.v1') ?? '[]')
    if (!Array.isArray(value)) return []
    return value.filter((item): item is RecentItem => item && (item.kind === 'action' || item.kind === 'object') && typeof item.id === 'string' && typeof item.label === 'string').slice(0, 8)
  } catch { return [] }
}

watch(query, onQueryChanged)
onMounted(() => {
  window.addEventListener(COMMAND_PALETTE_EVENT, onOpen)
  window.addEventListener(SHORTCUT_HELP_EVENT, onHelp)
})
onBeforeUnmount(() => {
  window.removeEventListener(COMMAND_PALETTE_EVENT, onOpen)
  window.removeEventListener(SHORTCUT_HELP_EVENT, onHelp)
  if (searchTimer !== null) window.clearTimeout(searchTimer)
})
</script>

<template>
  <Teleport to="body">
    <div v-if="!open && shortcutManager?.state.pending.length" class="command-palette__global-sequence" role="status" aria-live="polite">
      正在输入：<kbd>{{ shortcutManager.state.pending.join(' ') }}</kbd>
    </div>
    <Transition name="command-palette-fade">
      <div v-if="open && registry" class="command-palette__backdrop" @mousedown.self="close">
        <section ref="surface" class="command-palette" role="dialog" aria-modal="true" aria-labelledby="command-palette-title" tabindex="-1" @keydown="onKeydown">
          <header class="command-palette__header">
            <div><Command :size="17" aria-hidden="true" /><h2 id="command-palette-title">命令面板</h2></div>
            <button type="button" aria-label="关闭命令面板" @click="close"><X :size="17" /></button>
          </header>
          <div v-if="helpOpen" class="command-palette__help">
            <p class="command-palette__hint">快捷键帮助</p>
            <ul><li v-for="entry in entries" :key="entry.action.id"><span><strong>{{ entry.action.label }}</strong><small>{{ entry.action.description }}</small></span><kbd>{{ entry.action.shortcut ?? '—' }}</kbd></li></ul>
            <p class="command-palette__note">输入框、弹窗和抽屉打开时，序列快捷键会自动暂停。</p>
          </div>
          <template v-else>
            <label class="command-palette__search"><Search :size="18" aria-hidden="true" /><input v-model="query" type="search" autocomplete="off" placeholder="搜索动作或对象…" aria-label="搜索动作或对象"><kbd><Command :size="11" /> K</kbd></label>
            <div v-if="shortcutManager?.state.pending.length" class="command-palette__sequence" role="status">正在输入：{{ shortcutManager.state.pending.join(' ') }} …</div>
            <div class="command-palette__results" role="listbox" aria-label="命令面板结果">
              <section v-if="recentItems.length" class="command-palette__group"><h3>最近访问</h3><ul><li v-for="item in recentItems" :key="`${item.kind}:${item.id}`"><button type="button" :aria-label="item.label" @click="item.kind === 'action' && item.action ? executeAction(item.id) : item.route && openObject({ objectType: 'WORK_ITEM', objectId: item.id, projectId: null, title: item.label, subtitle: null, status: '', updatedAt: '', route: item.route, snippet: null })"><span>{{ item.label }}</span><ArrowRight :size="14" aria-hidden="true" /></button></li></ul></section>
              <template v-for="group in ['导航', '创建', '视图', '执行控制'] as const" :key="group">
                <section v-if="availableEntries.some(entry => entry.action.group === group)" class="command-palette__group"><h3>{{ group }}</h3><ul><li v-for="entry in availableEntries.filter(candidate => candidate.action.group === group)" :key="entry.action.id"><button type="button" :aria-label="entry.action.label" :aria-selected="availableEntries.indexOf(entry) === selectedIndex" @click="executeAction(entry.action.id)"><component :is="entry.action.icon" v-if="entry.action.icon" :size="15" aria-hidden="true" /><span><strong>{{ entry.action.label }}</strong><small>{{ entry.action.description }}</small></span><kbd v-if="entry.action.shortcut">{{ entry.action.shortcut }}</kbd></button></li></ul></section>
              </template>
              <section v-if="objectSearchLoading" class="command-palette__state" role="status">正在搜索对象…</section>
              <section v-else-if="objectSearchOffline" class="command-palette__state" role="status">当前离线，暂时无法搜索对象。</section>
              <section v-else-if="objectSearchError" class="command-palette__state" role="alert">搜索服务暂时不可用，请稍后重试。</section>
              <section v-if="objectItems.length" class="command-palette__group"><h3>对象</h3><ul><li v-for="(item, index) in objectItems" :key="`${item.objectType}:${item.objectId}`"><button type="button" :aria-selected="availableEntries.length + index === selectedIndex" @click="openObject(item)"><span><strong>{{ item.title }}</strong><small>{{ searchObjectTypeLabels[item.objectType] }} · {{ item.subtitle ?? item.status }}</small></span><ArrowRight :size="14" aria-hidden="true" /></button></li></ul></section>
              <section v-if="unavailableEntries.length && !availableEntries.length && !objectItems.length" class="command-palette__state command-palette__state--forbidden" role="status">当前身份没有可执行的匹配动作。</section>
              <section v-else-if="normalizedQuery && !hasResults && !objectSearchLoading && !objectSearchError && !objectSearchOffline" class="command-palette__state" role="status">没有找到匹配的动作或对象。</section>
              <section v-else-if="!normalizedQuery && !availableEntries.length" class="command-palette__state" role="status">当前范围暂无可用动作。</section>
            </div>
          </template>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.command-palette__backdrop { position: fixed; z-index: var(--cs-z-modal); inset: 0; display: flex; align-items: flex-start; justify-content: center; padding: 12vh 18px 24px; background: rgb(16 28 22 / 42%); }
.command-palette { width: min(680px, 100%); max-height: min(680px, 76vh); overflow: hidden; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-lg); background: var(--cs-surface); color: var(--cs-text); box-shadow: var(--cs-shadow-float); }
.command-palette__header, .command-palette__search { display: flex; align-items: center; gap: var(--cs-space-2); padding: 13px 16px; border-bottom: 1px solid var(--cs-border); }.command-palette__header { justify-content: space-between; }.command-palette__header > div { display: flex; align-items: center; gap: 8px; color: var(--cs-brand-700); }.command-palette h2 { margin: 0; color: var(--cs-text); font-size: var(--cs-text-base); }.command-palette__header button { display: grid; width: 28px; height: 28px; place-items: center; border: 0; border-radius: var(--cs-radius-sm); background: transparent; color: var(--cs-text-muted); cursor: pointer; }.command-palette__header button:hover { background: var(--cs-surface-subtle); color: var(--cs-text); }
.command-palette__search { border-bottom: 1px solid var(--cs-border); color: var(--cs-text-muted); }.command-palette__search input { min-width: 0; flex: 1; min-height: 32px; border: 0; outline: 0; background: transparent; color: var(--cs-text); font: inherit; }.command-palette__search kbd, .command-palette li kbd { display: inline-flex; align-items: center; gap: 2px; padding: 3px 6px; border: 1px solid var(--cs-border); border-radius: 5px; color: var(--cs-text-muted); font: 10px var(--cs-font-mono); }
.command-palette__sequence { padding: 7px 16px; border-bottom: 1px solid var(--cs-border); background: var(--cs-brand-50); color: var(--cs-brand-800); font-size: var(--cs-text-2xs); }.command-palette__results { max-height: calc(min(680px, 76vh) - 110px); overflow: auto; padding: 6px 0 12px; }.command-palette__group h3, .command-palette__hint { margin: 8px 16px 5px; color: var(--cs-text-muted); font-size: var(--cs-text-2xs); font-weight: var(--cs-weight-semibold); letter-spacing: .05em; text-transform: uppercase; }.command-palette__group ul { padding: 0; margin: 0; list-style: none; }.command-palette__group li button { display: flex; width: 100%; min-height: 48px; align-items: center; gap: 10px; padding: 7px 16px; border: 0; background: transparent; color: var(--cs-text-secondary); text-align: left; cursor: pointer; }.command-palette__group li button:hover, .command-palette__group li button[aria-selected='true'] { background: var(--cs-brand-50); color: var(--cs-text); }.command-palette__group li button > span { display: grid; min-width: 0; flex: 1; gap: 2px; }.command-palette__group li strong { overflow: hidden; color: var(--cs-text); font-size: var(--cs-text-sm); text-overflow: ellipsis; white-space: nowrap; }.command-palette__group li small { overflow: hidden; color: var(--cs-text-muted); font-size: var(--cs-text-2xs); text-overflow: ellipsis; white-space: nowrap; }.command-palette__state { padding: 24px 16px; color: var(--cs-text-muted); font-size: var(--cs-text-sm); text-align: center; }.command-palette__state--forbidden { color: var(--cs-warning); }.command-palette__help { max-height: calc(min(680px, 76vh) - 65px); overflow: auto; }.command-palette__help ul { padding: 0; margin: 0; list-style: none; }.command-palette__help li { display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 9px 16px; border-bottom: 1px solid var(--cs-border); }.command-palette__help li span { display: grid; gap: 2px; }.command-palette__help li strong { font-size: var(--cs-text-sm); }.command-palette__help li small { color: var(--cs-text-muted); font-size: var(--cs-text-2xs); }.command-palette__note { margin: 12px 16px 16px; color: var(--cs-text-muted); font-size: var(--cs-text-2xs); }
.command-palette__global-sequence { position: fixed; z-index: var(--cs-z-toast); top: var(--cs-space-4); left: 50%; display: flex; align-items: center; gap: var(--cs-space-2); padding: 7px 10px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface-raised); color: var(--cs-text-secondary); box-shadow: var(--cs-shadow-float); font-size: var(--cs-text-2xs); transform: translateX(-50%); }.command-palette__global-sequence kbd { padding: 2px 5px; border: 1px solid var(--cs-border); border-radius: 4px; font: 10px var(--cs-font-mono); }
.command-palette-fade-enter-active, .command-palette-fade-leave-active { transition: opacity var(--cs-motion-base) var(--cs-ease-out); }.command-palette-fade-enter-from, .command-palette-fade-leave-to { opacity: 0; }
</style>
