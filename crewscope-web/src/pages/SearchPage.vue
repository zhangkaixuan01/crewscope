<script setup lang="ts">
import { ArrowRight, Check, Search as SearchIcon, SlidersHorizontal } from '@lucide/vue'
import { computed, inject, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AUTH_PRINCIPAL } from '../app/auth'
import BaseButton from '../components/base/BaseButton.vue'
import BaseTooltip from '../components/base/BaseTooltip.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
import AppShell from '../components/layout/AppShell.vue'
import { useScopeStore } from '../domains/scope/store'
import { useSearchStore } from '../domains/search/store'
import { searchObjectTypes, type SearchObjectType, type SearchResultItem } from '../domains/search/types'
import { searchObjectTypeLabels } from '../domains/search/labels'
import { formatAbsoluteTime, formatRelativeTime } from '../composables/formatRelativeTime'

const route = useRoute()
const router = useRouter()
const principal = inject(AUTH_PRINCIPAL)
const scopeStore = useScopeStore()
const store = useSearchStore()
const text = ref(queryString(route.query.q))
const selectedTypes = ref<SearchObjectType[]>(parseTypes(route.query.types))
const loadingMore = ref(false)

const scope = computed(() => principal && scopeStore.state.selectedTeamId
  ? { organizationId: principal.organizationId, teamId: scopeStore.state.selectedTeamId }
  : null)
const groupedItems = computed(() => {
  const groups = new Map<SearchObjectType, SearchResultItem[]>()
  for (const item of store.state.result?.items ?? []) {
    const group = groups.get(item.objectType) ?? []
    group.push(item)
    groups.set(item.objectType, group)
  }
  return [...groups.entries()]
})
const activeTypes = computed(() => selectedTypes.value.length ? selectedTypes.value : undefined)

watch(
  () => [scopeStore.state.phase, scope.value?.organizationId, scope.value?.teamId, scopeStore.state.selectedProjectId, route.query.q, route.query.types] as const,
  async ([phase, organizationId, teamId]) => {
    if (phase !== 'ready' || !scope.value || !organizationId || !teamId) return
    store.activateScope({ organizationId, teamId })
    text.value = queryString(route.query.q)
    selectedTypes.value = parseTypes(route.query.types)
    await store.search({ text: text.value, projectId: scopeStore.state.selectedProjectId, types: activeTypes.value })
  },
  { immediate: true },
)

function submit(): void {
  const query = { ...route.query, q: text.value.trim() || undefined, types: selectedTypes.value.length ? selectedTypes.value.join(',') : undefined }
  void router.replace({ query })
}

function toggleType(type: SearchObjectType): void {
  selectedTypes.value = selectedTypes.value.includes(type)
    ? selectedTypes.value.filter(value => value !== type)
    : [...selectedTypes.value, type]
  submit()
}

function clearTypes(): void {
  selectedTypes.value = []
  submit()
}

async function loadMore(): Promise<void> {
  const cursor = store.state.result?.nextCursor
  if (!cursor || !text.value.trim() || loadingMore.value) return
  loadingMore.value = true
  try {
    await store.search({ text: text.value, projectId: scopeStore.state.selectedProjectId, types: activeTypes.value, after: cursor })
  } finally {
    loadingMore.value = false
  }
}

function openResult(item: SearchResultItem): void { void router.push(item.route) }
function displayMeta(item: SearchResultItem): string {
  const labels: Record<string, string> = {
    ACTIVE: '进行中', ARCHIVED: '已归档', DISABLED: '已停用', OPEN: '待处理', COMPLETED: '已完成',
    FAILED: '失败', WAITING: '等待中', TEAM: '团队', PRIVATE: '私有', MEMBER: '成员', PERSONAL: '个人',
    SPECIALIST: 'Specialist', LOCAL_MANAGED: '受管本地仓库',
  }
  const subtitle = item.subtitle ? (labels[item.subtitle] ?? item.subtitle) : null
  const status = labels[item.status] ?? '状态已更新'
  return subtitle ? `${subtitle} · ${status}` : status
}
function queryString(value: unknown): string { return typeof value === 'string' ? value : '' }
function parseTypes(value: unknown): SearchObjectType[] {
  if (typeof value !== 'string') return []
  return value.split(',').filter((candidate): candidate is SearchObjectType => (searchObjectTypes as readonly string[]).includes(candidate))
}
</script>

<template>
  <AppShell eyebrow="搜索 · 团队范围" title="统一搜索">
    <div class="search-page page-shell">
      <section class="search-intro">
        <div><p class="eyebrow"><SearchIcon :size="13" />跨团队工作事实检索</p><h2>找到你要推进的对象。</h2><p>搜索只返回当前 Team 与权限范围内的工作项、对话、任务、仓库、Agent 和成员。</p></div>
        <span class="scope-note">{{ scopeStore.selectedTeam.value?.name ?? '当前 Team' }}<small>{{ scopeStore.selectedProject.value?.name ?? '全部 WorkProject' }}</small></span>
      </section>

      <form class="search-form panel" role="search" @submit.prevent="submit">
        <label class="search-input"><SearchIcon :size="18" aria-hidden="true" /><input v-model="text" type="search" maxlength="100" placeholder="搜索标题、成员、仓库或对话" autocomplete="off" aria-label="搜索内容"><kbd>Enter</kbd></label>
        <BaseButton type="submit" :disabled="!text.trim()">搜索</BaseButton>
      </form>

      <section class="type-filter panel" aria-label="搜索对象类型">
        <div class="type-filter__heading"><span><SlidersHorizontal :size="14" />筛选对象类型</span><button v-if="selectedTypes.length" type="button" @click="clearTypes">清除筛选</button></div>
        <div class="type-filter__options">
          <button v-for="type in searchObjectTypes" :key="type" type="button" :class="{ selected: selectedTypes.includes(type) }" :aria-pressed="selectedTypes.includes(type)" @click="toggleType(type)"><Check v-if="selectedTypes.includes(type)" :size="13" />{{ searchObjectTypeLabels[type] }}</button>
        </div>
      </section>

      <StatePanel v-if="scopeStore.state.phase === 'empty'" state="empty" title="当前账号还没有 Team" description="加入 Team 后即可搜索团队工作事实。" />
      <StatePanel v-else-if="store.state.phase === 'idle'" state="empty" title="输入关键词开始搜索" description="搜索当前 Team 中你有权限查看的工作事实。" />
      <StatePanel v-else-if="store.state.phase === 'loading'" state="loading" title="正在搜索" description="正在按当前范围和权限读取最新结果。" />
      <StatePanel v-else-if="store.state.phase === 'offline'" state="offline" :description="store.state.errorMessage ?? undefined" />
      <StatePanel v-else-if="store.state.phase === 'error'" state="error" :description="store.state.errorMessage ?? undefined" @retry="submit" />
      <StatePanel v-else-if="store.state.phase === 'empty'" state="empty" title="没有找到匹配结果" description="换一个关键词，或清除对象类型筛选后重试。"><template #action><BaseButton size="small" variant="secondary" @click="clearTypes">清除筛选</BaseButton></template></StatePanel>
      <template v-else>
        <section class="result-summary" aria-live="polite">找到 {{ store.state.result?.items.length ?? 0 }} 条结果 <span>结果按更新时间排序</span></section>
        <div class="result-groups">
          <section v-for="[type, items] in groupedItems" :key="type" class="result-group panel" :aria-labelledby="`search-${type}`">
            <header><div><p class="eyebrow">{{ type }}</p><h2 :id="`search-${type}`">{{ searchObjectTypeLabels[type] }}</h2></div><span>{{ items.length }} 条</span></header>
            <ul><li v-for="item in items" :key="`${item.objectType}:${item.objectId}`"><button type="button" @click="openResult(item)"><span class="result-main"><strong>{{ item.title }}</strong><small>{{ displayMeta(item) }} · <BaseTooltip :text="formatAbsoluteTime(item.updatedAt)"><span>{{ formatRelativeTime(item.updatedAt) }}</span></BaseTooltip></small><em v-if="item.snippet">{{ item.snippet }}</em></span><ArrowRight :size="15" aria-hidden="true" /></button></li></ul>
          </section>
        </div>
        <div v-if="store.state.result?.nextCursor" class="load-more"><BaseButton variant="secondary" :loading="loadingMore" @click="loadMore">加载更多</BaseButton></div>
      </template>
    </div>
  </AppShell>
</template>

<style scoped>
.search-page { display: grid; gap: 14px; max-width: 980px; margin: 0 auto; }.search-intro { display: flex; align-items: end; justify-content: space-between; gap: 20px; padding: 6px 2px; }.search-intro h2 { margin: 5px 0 6px; font-size: 22px; }.search-intro p:not(.eyebrow) { max-width: 590px; margin: 0; color: var(--cs-text-muted); font-size: 11px; line-height: 1.55; }.eyebrow { display: flex; align-items: center; gap: 5px; margin: 0; color: var(--cs-text-muted); font-size: 9px; font-weight: 750; letter-spacing: .08em; text-transform: uppercase; }.scope-note { display: grid; min-width: 150px; padding: 10px 12px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface-subtle); color: var(--cs-text-secondary); font-size: 11px; font-weight: 750; text-align: right; }.scope-note small { margin-top: 3px; color: var(--cs-text-muted); font-size: 9px; font-weight: 500; }.search-form { display: flex; gap: 10px; padding: 12px; }.search-input { display: grid; min-width: 0; flex: 1; grid-template-columns: 20px minmax(0, 1fr) auto; align-items: center; gap: 8px; padding: 0 10px; border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text-muted); }.search-input input { width: 100%; min-height: 40px; border: 0; outline: 0; background: transparent; color: var(--cs-text); font: inherit; }.search-input kbd { padding: 3px 6px; border: 1px solid var(--cs-border); border-radius: 5px; color: var(--cs-text-muted); font-size: 9px; }.type-filter { display: grid; gap: 10px; padding: 12px 14px; }.type-filter__heading { display: flex; align-items: center; justify-content: space-between; color: var(--cs-text-muted); font-size: 10px; }.type-filter__heading span { display: flex; align-items: center; gap: 6px; font-weight: 750; }.type-filter__heading button { border: 0; background: transparent; color: var(--cs-brand-700); font-size: 10px; cursor: pointer; }.type-filter__options { display: flex; flex-wrap: wrap; gap: 7px; }.type-filter__options button { display: inline-flex; align-items: center; gap: 5px; padding: 6px 9px; border: 1px solid var(--cs-border); border-radius: 999px; background: var(--cs-surface); color: var(--cs-text-secondary); font-size: 10px; cursor: pointer; }.type-filter__options button.selected { border-color: var(--cs-brand-300); background: var(--cs-brand-50); color: var(--cs-brand-800); }.result-summary { color: var(--cs-text-secondary); font-size: 11px; font-weight: 700; }.result-summary span { margin-left: 8px; color: var(--cs-text-muted); font-size: 9px; font-weight: 500; }.result-groups { display: grid; gap: 12px; }.result-group { overflow: hidden; }.result-group > header { display: flex; align-items: center; justify-content: space-between; padding: 13px 15px; border-bottom: 1px solid var(--cs-border); }.result-group > header h2 { margin: 3px 0 0; font-size: 14px; }.result-group > header > span { color: var(--cs-text-muted); font-size: 10px; }.result-group ul { padding: 0; margin: 0; list-style: none; }.result-group li + li { border-top: 1px solid var(--cs-border); }.result-group li button { display: flex; width: 100%; align-items: center; justify-content: space-between; gap: 14px; padding: 13px 15px; background: var(--cs-surface); color: var(--cs-text-secondary); text-align: left; cursor: pointer; }.result-group li button:hover { background: var(--cs-brand-50); }.result-main { display: grid; min-width: 0; gap: 3px; }.result-main strong { overflow: hidden; color: var(--cs-text); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }.result-main small { color: var(--cs-text-muted); font-size: 9px; }.result-main em { overflow: hidden; color: var(--cs-text-muted); font-size: 9px; font-style: normal; text-overflow: ellipsis; white-space: nowrap; }.load-more { display: flex; justify-content: center; padding: 4px; }
@media (max-width: 640px) { .search-intro { display: grid; align-items: start; }.scope-note { width: max-content; min-width: 0; text-align: left; }.search-form { display: grid; }.search-form :deep(button) { width: 100%; }.result-group li button { padding-inline: 12px; } }
</style>
