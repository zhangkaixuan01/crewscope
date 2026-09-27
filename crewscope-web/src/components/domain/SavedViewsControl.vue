<script setup lang="ts">
import { Bookmark, BookmarkCheck, Pin, PinOff, RefreshCw, RotateCcw, Trash2 } from '@lucide/vue'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { secureId } from '../../api/secureId'
import type { F05SavedView } from '../../app/f05Storage'
import {
  buildSavedView,
  deleteView,
  filterKeysFor,
  listViews,
  pinView,
  saveView,
  viewFilters,
  type SavedViewFilters,
  type SavedViewOwner,
  type SavedViewRouteName,
} from '../../domains/views/savedViews'

/**
 * R41 saved views: the member's own filter definitions, kept on this machine only.
 *
 * The control is deliberately dumb about pages — it stores whatever filter keys the page
 * committed and asks the page to apply a definition back. Priority stays in the page: an
 * explicit URL always beats the stored default (契约 §4.6), so this control never rewrites a
 * query on its own.
 */
const props = defineProps<{
  routeName: SavedViewRouteName
  /** The filter coordinates the page has committed right now (captured from its URL). */
  filters: SavedViewFilters
  owner: SavedViewOwner | null
}>()

const emit = defineEmits<{ apply: [view: F05SavedView | null] }>()

const open = ref(false)
const views = ref<F05SavedView[]>([])
const nameDraft = ref('')
const notice = ref<string | null>(null)

const keys = computed(() => filterKeysFor(props.routeName))
const currentView = computed(() => views.value.find(view => sameFilters(viewFilters(view, keys.value), props.filters)))

function sameFilters(left: SavedViewFilters, right: SavedViewFilters): boolean {
  const leftKeys = Object.keys(left).sort()
  const rightKeys = Object.keys(right).sort()
  return leftKeys.length === rightKeys.length && leftKeys.every((key, index) => key === rightKeys[index] && left[key] === right[key])
}

function refresh(): void {
  views.value = props.owner ? listViews(props.owner, props.routeName) : []
}

function toggle(): void {
  open.value = !open.value
  if (open.value) { refresh(); notice.value = null }
}

function close(): void { open.value = false; nameDraft.value = '' }

const root = ref<HTMLElement | null>(null)

function onDocumentClick(event: MouseEvent): void {
  if (!open.value) return
  if (!(event.target instanceof Node) || !event.target.isConnected) return
  if (root.value?.contains(event.target)) return
  close()
}

function onKeydown(event: KeyboardEvent): void {
  if (!open.value || event.key !== 'Escape') return
  // The closing key is consumed here; it must not bubble into a drawer behind the control.
  event.stopPropagation()
  close()
}
watch(open, value => {
  if (typeof document === 'undefined') return
  if (value) {
    document.addEventListener('click', onDocumentClick)
    document.addEventListener('keydown', onKeydown)
  } else {
    document.removeEventListener('click', onDocumentClick)
    document.removeEventListener('keydown', onKeydown)
  }
})
onBeforeUnmount(() => {
  document.removeEventListener('click', onDocumentClick)
  document.removeEventListener('keydown', onKeydown)
})

function applyView(view: F05SavedView | null): void {
  emit('apply', view)
  close()
}

function saveNew(): void {
  const name = nameDraft.value.trim()
  if (!props.owner || !name) return
  const result = saveView(props.owner, buildSavedView({ id: secureId(), name, routeName: props.routeName, filters: { ...props.filters } }))
  if (!result.ok) {
    notice.value = '本机存储不可用或已满，本次未能保存视图。'
    return
  }
  notice.value = null
  nameDraft.value = ''
  refresh()
}

function updateToCurrent(view: F05SavedView): void {
  if (!props.owner) return
  const rebuilt = buildSavedView({ id: view.id, name: view.name, routeName: props.routeName, filters: { ...props.filters } })
  const result = saveView(props.owner, { ...rebuilt, pinned: view.pinned })
  if (!result.ok) {
    notice.value = '本机存储不可用或已满，本次未能更新视图。'
    return
  }
  notice.value = null
  refresh()
}

function togglePin(view: F05SavedView): void {
  if (!props.owner) return
  if (!pinView(props.owner, view.id, !view.pinned).ok) {
    notice.value = '本机存储不可用或已满，本次未能调整置顶。'
    return
  }
  notice.value = null
  refresh()
}

function remove(view: F05SavedView): void {
  if (!props.owner) return
  if (!deleteView(props.owner, view.id).ok) {
    notice.value = '本机存储不可用或已满，本次未能删除视图。'
    return
  }
  notice.value = null
  refresh()
}

// Switching Team swaps the storage namespace; an open menu must not show the previous Team's names.
watch(() => props.owner, refresh)
</script>

<template>
  <div v-if="owner" ref="root" class="saved-views">
    <button type="button" class="saved-views__trigger" aria-haspopup="menu" :aria-expanded="open" @click="toggle">
      <BookmarkCheck v-if="currentView" :size="13" aria-hidden="true" />
      <Bookmark v-else :size="13" aria-hidden="true" />
      视图
    </button>
    <div v-if="open" class="saved-views__menu" role="menu" aria-label="已保存视图">
      <p class="saved-views__storage">本机存储，不跨设备同步</p>

      <button type="button" role="menuitem" class="saved-views__default" @click="applyView(null)">
        <RotateCcw :size="13" aria-hidden="true" />
        默认
        <small>{{ currentView ? '回到页面默认筛选' : '页面默认筛选' }}</small>
      </button>

      <p v-if="!views.length" class="saved-views__empty">还没有保存的视图</p>
      <ul v-else>
        <li v-for="view in views" :key="view.id" :class="{ 'saved-views__item--current': view.id === currentView?.id }">
          <button type="button" role="menuitem" class="saved-views__apply" :aria-label="`应用视图 ${view.name}`" @click="applyView(view)">
            <Pin v-if="view.pinned" :size="12" aria-hidden="true" />
            <span>{{ view.name }}</span>
            <em v-if="view.id === currentView?.id">当前</em>
          </button>
          <span class="saved-views__actions">
            <button v-if="view.id !== currentView?.id" type="button" :aria-label="`把 ${view.name} 更新为当前筛选`" @click="updateToCurrent(view)"><RefreshCw :size="12" aria-hidden="true" /></button>
            <button type="button" :aria-label="view.pinned ? `取消置顶 ${view.name}` : `置顶 ${view.name}`" @click="togglePin(view)"><component :is="view.pinned ? PinOff : Pin" :size="12" aria-hidden="true" /></button>
            <button type="button" :aria-label="`删除视图 ${view.name}`" @click="remove(view)"><Trash2 :size="12" aria-hidden="true" /></button>
          </span>
        </li>
      </ul>

      <form class="saved-views__save" @submit.prevent="saveNew">
        <input v-model="nameDraft" type="text" maxlength="40" placeholder="为当前筛选命名" aria-label="视图名称">
        <button type="submit" :disabled="!nameDraft.trim()">保存当前筛选</button>
      </form>
      <p v-if="notice" class="saved-views__notice" role="alert">{{ notice }}</p>
    </div>
  </div>
</template>

<style scoped>
.saved-views { position: relative; }.saved-views__trigger { display: inline-flex; align-items: center; gap: var(--cs-space-4); min-height: var(--cs-density-control-height); padding: 0 var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: 9px; background: var(--cs-surface-subtle); color: var(--cs-text-secondary); font-size: var(--cs-text-sm); cursor: pointer; }.saved-views__trigger:hover { color: var(--cs-text-brand); }.saved-views__trigger[aria-expanded="true"] { border-color: var(--cs-border-accent-strong); color: var(--cs-text-brand); }
.saved-views__menu { position: absolute; z-index: var(--cs-z-popover); top: calc(100% + var(--cs-space-8)); right: 0; display: grid; width: min(320px, calc(100vw - 32px)); gap: var(--cs-space-8); padding: var(--cs-space-12); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-md); background: var(--cs-surface-raised); box-shadow: var(--cs-shadow-float); }
.saved-views__storage { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.saved-views__default { display: flex; align-items: center; gap: var(--cs-space-8); padding: var(--cs-space-8); border: 0; border-radius: var(--cs-radius-sm); background: transparent; color: var(--cs-text); font-size: var(--cs-text-sm); cursor: pointer; }.saved-views__default:hover { background: var(--cs-surface-subtle); }.saved-views__default small { margin-left: auto; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.saved-views__empty { margin: 0; padding: var(--cs-space-8); color: var(--cs-text-muted); font-size: var(--cs-text-sm); }
.saved-views__menu ul { display: grid; gap: var(--cs-space-4); margin: 0; padding: 0; list-style: none; }
.saved-views__menu li { display: flex; align-items: stretch; gap: var(--cs-space-4); border-radius: var(--cs-radius-sm); }
.saved-views__menu li:hover { background: var(--cs-surface-subtle); }
.saved-views__item--current { background: var(--cs-surface-accent); }
.saved-views__apply { display: flex; min-width: 0; flex: 1; align-items: center; gap: var(--cs-space-8); padding: var(--cs-space-8); border: 0; background: transparent; color: var(--cs-text); font-size: var(--cs-text-sm); text-align: left; cursor: pointer; }
.saved-views__apply span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.saved-views__apply em { margin-left: auto; color: var(--cs-text-brand); font-size: var(--cs-text-xs); font-style: normal; font-weight: var(--cs-weight-semibold); }
.saved-views__actions { display: inline-flex; align-items: center; gap: var(--cs-space-2); padding-right: var(--cs-space-8); }
.saved-views__actions button { display: grid; width: 26px; height: 26px; place-items: center; border: 0; border-radius: 6px; background: transparent; color: var(--cs-text-muted); cursor: pointer; }.saved-views__actions button:hover { background: var(--cs-surface); color: var(--cs-text-brand); }
.saved-views__save { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: var(--cs-space-8); padding-top: var(--cs-space-8); border-top: 1px solid var(--cs-border); }
.saved-views__save input { min-height: 32px; padding: 0 var(--cs-space-8); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text); font: inherit; }
.saved-views__save button { padding: 0 var(--cs-space-12); border: 0; border-radius: var(--cs-radius-sm); background: var(--cs-surface-accent-strong); color: var(--cs-text-brand-strong); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); cursor: pointer; }.saved-views__save button:disabled { opacity: .5; cursor: default; }
.saved-views__notice { margin: 0; color: var(--cs-danger); font-size: var(--cs-text-xs); }
@media (max-width: 640px) { .saved-views__save { grid-template-columns: 1fr; } }
</style>
