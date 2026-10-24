<script setup lang="ts">
import { ArrowRight, Command, Search } from '@lucide/vue'
import { computed, inject, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AUTH_PRINCIPAL } from '../../app/auth'
import { ACTION_REGISTRY, type ActionContext, type ActionEntry, type ActionRegistry } from '../../app/actionRegistry'
import { COMMAND_PALETTE_EVENT, SHORTCUT_HELP_EVENT, SHORTCUT_MANAGER, type ShortcutManager } from '../../app/shortcuts'
import BaseDialog from '../base/BaseDialog.vue'
import { isImeComposition } from '../../composables/useImeGuard'
import { SCOPE_STORE } from '../../domains/scope/store'
import { PALETTE_SEARCH_STORE, type SearchStore } from '../../domains/search/store'
import type { SearchResultItem } from '../../domains/search/types'
import { searchObjectTypeLabels } from '../../domains/search/labels'
import { readF05Recent, subscribeF05Epoch, writeF05Recent, type F05RecentItem, type F05SavedView } from '../../app/f05Storage'
import { filterKeysFor, listViews, viewQuery, type SavedViewOwner } from '../../domains/views/savedViews'

interface RecentItem {
  kind: 'action' | 'object'
  /** Stable object type of the visited target; actions use the literal `ACTION`. */
  objectType?: string
  id: string
  label?: string
  subtitle?: string | null
  route?: string
  accessedAt?: number
}
interface RecentDisplay extends RecentItem { label: string, action?: ActionEntry['action'] }

const registry = inject<ActionRegistry | null>(ACTION_REGISTRY, null)
const shortcutManager = inject<ShortcutManager | null>(SHORTCUT_MANAGER, null)
const principal = inject(AUTH_PRINCIPAL, null)
const scopeStore = inject(SCOPE_STORE, null)
const searchStore = inject<SearchStore | null>(PALETTE_SEARCH_STORE, null)
const route = useRoute()
const router = useRouter()
const open = ref(false)
const helpOpen = ref(false)
const query = ref('')
const selectedIndex = ref(0)
const recent = ref<RecentItem[]>([])
// 焦点陷阱、滚动锁与 Escape 关闭都由 BaseDialog 承担（F02 收敛）。
const paletteBody = ref<HTMLElement | null>(null)

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
const hasResults = computed(() => availableEntries.value.length > 0 || objectItems.value.length > 0 || unavailableEntries.value.length > 0 || savedViewItems.value.length > 0)
const recentItems = computed<RecentDisplay[]>(() => {
  if (normalizedQuery.value) return []
  return recent.value.map(item => {
    if (item.kind === 'action') {
      const entry = entries.value.find(candidate => candidate.action.id === item.id)
      return entry?.available ? { ...item, label: entry.action.label, action: entry.action } : null
    }
    return { ...item, label: item.label || '最近访问对象' }
  }).filter((item): item is RecentDisplay => item !== null)
})

let searchTimer: number | null = null
function onOpen(): void {
  open.value = true
  helpOpen.value = false
  query.value = ''
  selectedIndex.value = 0
  // R20: the palette owns its own session; clearing it on open never touches the search page.
  if (searchStore) void searchStore.search({ text: '' })
  refreshSavedViews()
}
function onHelp(): void { open.value = true; helpOpen.value = true; query.value = ''; selectedIndex.value = 0 }
function close(): void { open.value = false; helpOpen.value = false; shortcutManager?.closeHelp(); query.value = '' }

function onKeydown(event: KeyboardEvent): void {
  // Escape 由 BaseDialog 的焦点陷阱关闭并归还焦点；这里只做列表导航。
  if (helpOpen.value || !hasResults.value || selectableCount.value === 0) return
  if (event.key === 'ArrowDown') { event.preventDefault(); selectedIndex.value = Math.min(selectedIndex.value + 1, selectableCount.value - 1); scrollSelectedIntoView(); return }
  if (event.key === 'ArrowUp') { event.preventDefault(); selectedIndex.value = Math.max(selectedIndex.value - 1, 0); scrollSelectedIntoView(); return }
  if (event.key === 'Enter') {
    // IME confirmation must never run the highlighted command (R27).
    if (isImeComposition(event)) return
    event.preventDefault(); void executeSelected()
  }
}

// M13b-F12（二轮 A01）：键盘索引与渲染顺序严格一致——最近访问 → 已保存视图 → 动作 → 对象。
// 此前 selectableCount 排除 recentItems，「最近访问」是鼠标专属行；现在四段同权进入
// aria-activedescendant 虚焦点模型（DOM 焦点留在 combobox 输入框，读屏按 option 播报）。
const recentOptions = computed(() => recentItems.value.map(item => ({
  key: `recent-${item.kind}-${item.id}`,
  label: item.label,
  run: () => { if (item.kind === 'action' && item.action) void executeAction(item.id); else openRecent(item) },
})))
const viewOptions = computed(() => savedViewItems.value.map(view => ({
  key: `view-${view.id}`,
  label: view.name,
  run: () => openSavedView(view),
})))
const actionOptions = computed(() => availableEntries.value.map(entry => ({
  key: `action-${entry.action.id}`,
  label: entry.action.label,
  run: () => void executeAction(entry.action.id),
})))
const objectOptions = computed(() => objectItems.value.map(item => ({
  key: `object-${item.objectType}-${item.objectId}`,
  label: item.title,
  run: () => openObject(item),
})))
const flatOptions = computed(() => [...recentOptions.value, ...viewOptions.value, ...actionOptions.value, ...objectOptions.value])
const selectableCount = computed(() => flatOptions.value.length)
/** 段起始下标：模板里每段的 option id 需要全局序号与输入框 aria-activedescendant 对齐。 */
const viewOptionOffset = computed(() => recentOptions.value.length)
const actionOptionOffset = computed(() => viewOptionOffset.value + viewOptions.value.length)
const objectOptionOffset = computed(() => actionOptionOffset.value + actionOptions.value.length)
const activeDescendantId = computed(() => {
  if (selectedIndex.value < 0 || selectedIndex.value >= flatOptions.value.length) return undefined
  return `command-palette-option-${selectedIndex.value}`
})
function optionId(index: number): string { return `command-palette-option-${index}` }
function scrollSelectedIntoView(): void { void nextTick(() => paletteBody.value?.querySelector<HTMLElement>('[role="option"][aria-selected="true"]')?.scrollIntoView?.({ block: 'nearest' })) }

async function executeSelected(): Promise<void> {
  const option = flatOptions.value[selectedIndex.value]
  option?.run()
}

async function executeAction(id: string): Promise<void> {
  if (!registry) return
  const executed = await registry.execute(id, context.value)
  if (!executed) return
  remember({ kind: 'action', id, label: registry.get(id)?.label ?? id })
  close()
}

function openObject(item: SearchResultItem): void {
  remember({ kind: 'object', objectType: item.objectType, id: item.objectId, label: item.title, subtitle: item.subtitle, route: item.route })
  close()
  void router.push(item.route)
}

/** R20: the palette only previews; 「搜索全部」 is the one action that submits the text to the search page. */
function searchEverywhere(): void {
  const text = query.value.trim()
  if (!text) return
  const searchQuery: Record<string, string> = { q: text }
  const teamId = scopeStore?.state.selectedTeamId
  if (teamId) searchQuery.team = teamId
  close()
  void router.push({ name: 'search', query: searchQuery })
}

/** Re-opens a stored recent object: refreshes the timestamp and navigates by its saved route. */
function openRecent(item: RecentDisplay): void {
  remember({ kind: 'object', objectType: item.objectType, id: item.id, label: item.label, subtitle: item.subtitle ?? null, route: item.route })
  close()
  if (item.route) void router.push(item.route)
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
  const teamId = scopeStore?.state.selectedTeamId
  if (!principal || !principal.accountId || !teamId) return
  // Recents are a Team-level concept: pinning them to the selected WorkProject would key the
  // write by whichever project happened to be resolved at click time, while the reload-time
  // read below runs before project selection is restored — the entry would vanish (M9b-F05).
  const scope = { accountId: principal.accountId, principalId: principal.id, organizationId: principal.organizationId, teamId, projectId: null }
  const entry: F05RecentItem = { kind: item.kind, objectType: item.objectType ?? 'ACTION', id: item.id, label: item.label ?? '', subtitle: item.subtitle ?? null, route: item.route, accessedAt: Date.now() }
  const next = [entry, ...readF05Recent(scope).filter(existing => !(existing.kind === entry.kind && existing.id === entry.id))]
  recent.value = next
  // Recents carry no user-facing save promise; a failed write degrades silently.
  writeF05Recent(scope, next)
}

function recentScope(teamId: string | null | undefined) {
  if (!principal || !principal.accountId || !teamId) return null
  return { accountId: principal.accountId, principalId: principal.id, organizationId: principal.organizationId, teamId, projectId: null }
}

watch(() => scopeStore?.state.selectedTeamId, teamId => { const scope = recentScope(teamId); recent.value = scope ? readF05Recent(scope) : [] }, { immediate: true })

// --- R41: saved views are jump targets too; the definitions live on this machine only ------

const savedViews = ref<F05SavedView[]>([])
function viewsOwner(teamId: string | null | undefined): SavedViewOwner | null {
  if (!principal || !principal.accountId || !teamId) return null
  return { accountId: principal.accountId, principalId: principal.id, organizationId: principal.organizationId, teamId }
}
function refreshSavedViews(): void {
  const owner = viewsOwner(scopeStore?.state.selectedTeamId)
  savedViews.value = owner ? [...listViews(owner, 'work'), ...listViews(owner, 'today')] : []
}
watch(() => scopeStore?.state.selectedTeamId, () => refreshSavedViews(), { immediate: true })
subscribeF05Epoch(epoch => { if (epoch === null) savedViews.value = [] })

const savedViewItems = computed(() => {
  if (!normalizedQuery.value) return savedViews.value
  return savedViews.value.filter(view => view.name.toLocaleLowerCase().includes(normalizedQuery.value))
})

/** Jumps to the page with the definition applied; an explicit link still wins once landed. */
function openSavedView(view: F05SavedView): void {
  const teamId = scopeStore?.state.selectedTeamId
  close()
  void router.push({ name: view.routeName, query: viewQuery({ team: teamId }, view, filterKeysFor(view.routeName as 'work' | 'today')) })
}
// Sign-out or an account switch seals the namespace; drop the in-memory copy too.
subscribeF05Epoch(epoch => { if (epoch === null) recent.value = [] })

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
  </Teleport>
  <BaseDialog :open="open && registry !== null" title="命令面板" width="min(680px, 100%)" anchor="top" padding="0" close-label="关闭命令面板" @close="close">
    <template #icon><Command :size="18" aria-hidden="true" /></template>
    <div ref="paletteBody" class="command-palette__main" @keydown="onKeydown">
      <div v-if="helpOpen" class="command-palette__help">
            <p class="command-palette__hint">快捷键帮助</p>
            <ul><li v-for="entry in entries" :key="entry.action.id"><span><strong>{{ entry.action.label }}</strong><small v-if="entry.action.description">{{ entry.action.description }}</small></span><kbd>{{ entry.action.shortcut ?? '—' }}</kbd></li></ul>
            <p class="command-palette__note">输入框、弹窗和抽屉打开时，序列快捷键会自动暂停。</p>
          </div>
          <template v-else>
            <label class="command-palette__search"><Search :size="18" aria-hidden="true" /><input v-model="query" type="search" autocomplete="off" placeholder="搜索动作或对象…" role="combobox" aria-label="搜索动作或对象" aria-expanded="true" aria-haspopup="listbox" aria-controls="command-palette-results" aria-autocomplete="list" :aria-activedescendant="activeDescendantId" autofocus><kbd><Command :size="11" /> K</kbd></label>
            <div v-if="shortcutManager?.state.pending.length" class="command-palette__sequence" role="status">正在输入：{{ shortcutManager.state.pending.join(' ') }} …</div>
            <!-- M13b-F12（二轮 A01）：option 行不可聚焦（li[role=option]，虚焦点由 combobox 的
                 aria-activedescendant 承担）；鼠标点击保留，键盘 Enter 在输入框统一执行。
                 「搜索全部」移出 listbox——listbox 的子项只允许 option，普通按钮会破坏子项契约。 -->
            <div id="command-palette-results" class="command-palette__results" role="listbox" aria-label="命令面板结果">
              <section v-if="recentItems.length" class="command-palette__group" role="group" aria-labelledby="command-palette-group-recent"><h3 id="command-palette-group-recent">最近访问</h3><ul><li v-for="(item, i) in recentItems" :id="optionId(i)" :key="`${item.kind}:${item.id}`" role="option" :aria-selected="i === selectedIndex" @click="item.kind === 'action' && item.action ? executeAction(item.id) : openRecent(item)"><span>{{ item.label }}</span><ArrowRight :size="14" aria-hidden="true" /></li></ul></section>
              <section v-if="savedViewItems.length" class="command-palette__group" role="group" aria-labelledby="command-palette-group-views"><h3 id="command-palette-group-views">已保存视图</h3><ul><li v-for="(view, i) in savedViewItems" :id="optionId(viewOptionOffset + i)" :key="view.id" role="option" :aria-selected="viewOptionOffset + i === selectedIndex" @click="openSavedView(view)"><span>{{ view.name }}<small>{{ view.routeName === 'work' ? '工作项' : '今日工作台' }}</small></span><ArrowRight :size="14" aria-hidden="true" /></li></ul></section>
              <template v-for="group in ['导航', '创建', '视图', '执行控制'] as const" :key="group">
                <section v-if="availableEntries.some(entry => entry.action.group === group)" class="command-palette__group" role="group" :aria-labelledby="`command-palette-group-${group}`"><h3 :id="`command-palette-group-${group}`">{{ group }}</h3><ul><li v-for="entry in availableEntries.filter(candidate => candidate.action.group === group)" :id="optionId(actionOptionOffset + availableEntries.indexOf(entry))" :key="entry.action.id" role="option" :aria-selected="actionOptionOffset + availableEntries.indexOf(entry) === selectedIndex" @click="executeAction(entry.action.id)"><component :is="entry.action.icon" v-if="entry.action.icon" :size="15" aria-hidden="true" /><span><strong>{{ entry.action.label }}</strong><small v-if="entry.action.description">{{ entry.action.description }}</small></span><kbd v-if="entry.action.shortcut">{{ entry.action.shortcut }}</kbd></li></ul></section>
              </template>
              <section v-if="objectSearchLoading" class="command-palette__state" role="status">正在搜索对象…</section>
              <section v-else-if="objectSearchOffline" class="command-palette__state" role="status">当前离线，暂时无法搜索对象。</section>
              <section v-else-if="objectSearchError" class="command-palette__state" role="alert">搜索服务暂时不可用，请稍后重试。</section>
              <section v-if="objectItems.length" class="command-palette__group" role="group" aria-labelledby="command-palette-group-objects"><h3 id="command-palette-group-objects">对象</h3><ul><li v-for="(item, i) in objectItems" :id="optionId(objectOptionOffset + i)" :key="`${item.objectType}:${item.objectId}`" role="option" :aria-selected="objectOptionOffset + i === selectedIndex" @click="openObject(item)"><span><strong>{{ item.title }}</strong><small>{{ searchObjectTypeLabels[item.objectType] }} · {{ item.subtitle ?? item.status }}</small></span><ArrowRight :size="14" aria-hidden="true" /></li></ul></section>
              <section v-if="unavailableEntries.length && !availableEntries.length && !objectItems.length" class="command-palette__state command-palette__state--forbidden" role="status">当前身份没有可执行的匹配动作。</section>
              <section v-else-if="normalizedQuery && !hasResults && !objectSearchLoading && !objectSearchError && !objectSearchOffline" class="command-palette__state" role="status">没有找到匹配的动作或对象。</section>
              <section v-else-if="!normalizedQuery && !availableEntries.length" class="command-palette__state" role="status">当前范围暂无可用动作。</section>
            </div>
            <button v-if="query.trim()" type="button" class="command-palette__search-all" aria-label="搜索全部" @click="searchEverywhere"><Search :size="14" aria-hidden="true" />搜索全部「{{ query.trim() }}」<ArrowRight :size="14" aria-hidden="true" /></button>
      </template>
    </div>
  </BaseDialog>
</template>

<style scoped>
.command-palette__main { display: grid; }
.command-palette__search { display: flex; align-items: center; gap: var(--cs-space-8); padding: var(--cs-space-12) var(--cs-space-16); border-bottom: 1px solid var(--cs-border); color: var(--cs-text-muted); }.command-palette__search input { min-width: 0; flex: 1; min-height: 32px; border: 0; outline: 0; background: transparent; color: var(--cs-text); font: inherit; }.command-palette__search kbd, .command-palette li kbd { display: inline-flex; align-items: center; gap: var(--cs-space-2); padding: var(--cs-space-4) var(--cs-space-8); border: 1px solid var(--cs-border); border-radius: 5px; color: var(--cs-text-muted); font: var(--cs-text-sm) var(--cs-font-mono); }
.command-palette__sequence { padding: var(--cs-space-8) var(--cs-space-16); border-bottom: 1px solid var(--cs-border); background: var(--cs-surface-accent); color: var(--cs-text-brand-strong); font-size: var(--cs-text-xs); }.command-palette__results { max-height: calc(min(680px, 76vh) - 110px); overflow: auto; padding: var(--cs-space-8) 0 var(--cs-space-12); }.command-palette__group h3, .command-palette__hint { margin: var(--cs-space-8) var(--cs-space-16) var(--cs-space-4); color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); letter-spacing: .05em; text-transform: uppercase; }.command-palette__group ul { padding: 0; margin: 0; list-style: none; }.command-palette__group li[role='option'] { display: flex; width: 100%; min-height: 48px; align-items: center; gap: var(--cs-space-12); padding: var(--cs-space-8) var(--cs-space-16); border: 0; background: transparent; color: var(--cs-text-secondary); text-align: left; cursor: pointer; }.command-palette__group li[role='option']:hover, .command-palette__group li[role='option'][aria-selected='true'] { background: var(--cs-surface-accent); color: var(--cs-text); }.command-palette__group li[role='option'] > span { display: grid; min-width: 0; flex: 1; gap: var(--cs-space-2); }.command-palette__group li strong { overflow: hidden; color: var(--cs-text); font-size: var(--cs-text-sm); text-overflow: ellipsis; white-space: nowrap; }.command-palette__group li small { overflow: hidden; color: var(--cs-text-muted); font-size: var(--cs-text-xs); text-overflow: ellipsis; white-space: nowrap; }.command-palette__state { padding: var(--cs-space-24) var(--cs-space-16); color: var(--cs-text-muted); font-size: var(--cs-text-sm); text-align: center; }.command-palette__state--forbidden { color: var(--cs-warning); }.command-palette__search-all { display: flex; width: 100%; align-items: center; justify-content: center; gap: var(--cs-space-8); margin-top: var(--cs-space-4); padding: var(--cs-space-12) var(--cs-space-16); border: 0; border-top: 1px solid var(--cs-border); background: transparent; color: var(--cs-text-brand); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); cursor: pointer; }.command-palette__search-all:hover, .command-palette__search-all:focus-visible { background: var(--cs-surface-accent); }.command-palette__help { max-height: calc(min(680px, 76vh) - 65px); overflow: auto; }.command-palette__help ul { padding: 0; margin: 0; list-style: none; }.command-palette__help li { display: flex; align-items: center; justify-content: space-between; gap: var(--cs-space-16); padding: var(--cs-space-8) var(--cs-space-16); border-bottom: 1px solid var(--cs-border); }.command-palette__help li span { display: grid; gap: var(--cs-space-2); }.command-palette__help li strong { font-size: var(--cs-text-sm); }.command-palette__help li small { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.command-palette__note { margin: var(--cs-space-12) var(--cs-space-16) var(--cs-space-16); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.command-palette__global-sequence { position: fixed; z-index: var(--cs-z-toast); top: var(--cs-space-16); left: 50%; display: flex; align-items: center; gap: var(--cs-space-8); padding: var(--cs-space-8) var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface-raised); color: var(--cs-text-secondary); box-shadow: var(--cs-shadow-float); font-size: var(--cs-text-xs); transform: translateX(-50%); }.command-palette__global-sequence kbd { padding: var(--cs-space-2) var(--cs-space-4); border: 1px solid var(--cs-border); border-radius: 4px; font: var(--cs-text-sm) var(--cs-font-mono); }
</style>
