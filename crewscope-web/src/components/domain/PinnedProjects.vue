<script setup lang="ts">
import { Pin, PinOff, X } from '@lucide/vue'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { subscribeF05Epoch } from '../../app/f05Storage'
import {
  readPinnedProjects,
  togglePinnedProject,
  writePinnedProjects,
  type PinnedProject,
  type SavedViewOwner,
} from '../../domains/views/savedViews'

/**
 * R41 fixed projects: a quick-entry row of the projects the member pinned, ordered by hand.
 *
 * The pin stores an identifier only. Names are resolved again through the currently authorized
 * project list on every render, so a revoked project appears as 「项目已不可见」 and offers to be
 * unpinned — a stale title must never speak for an object the member can no longer see.
 */
const props = defineProps<{
  owner: SavedViewOwner | null
  /** The projects the current authorization still returns, in the scope's own order. */
  projects: Array<{ id: string, key: string, name: string }>
  /** The project the page is focused on right now; null or 'all' offers no pin. */
  activeProjectId?: string | null
}>()

const emit = defineEmits<{ select: [projectId: string] }>()

const pins = ref<PinnedProject[]>([])
const dragging = ref<string | null>(null)
const dragOver = ref<string | null>(null)
const unavailable = ref<string | null>(null)

const resolved = computed(() => pins.value.map(pin => ({
  pin,
  project: props.projects.find(project => project.id === pin.projectId) ?? null,
})))
const pinnableActive = computed(() => {
  const active = props.activeProjectId
  if (!props.owner || !active || active === 'all') return null
  if (pins.value.some(pin => pin.projectId === active)) return null
  const project = props.projects.find(candidate => candidate.id === active)
  return project ?? null
})

function refresh(): void {
  pins.value = props.owner ? readPinnedProjects(props.owner) : []
}

// On a reload the page renders after principal and team are already resolved, so the first read
// must be immediate; a later owner change (switching Team) re-reads. After a reload the owner may
// also settle before the F05 epoch is activated — a read in that window yields nothing, and every
// epoch change re-reads, so pins appear once the namespace seals.
watch(() => props.owner, refresh, { immediate: true })
onBeforeUnmount(subscribeF05Epoch(() => refresh()))

/** Pins the focused project; hidden while it is already pinned or not in the authorized list. */
function pinActive(): void {
  const project = pinnableActive.value
  if (!props.owner || !project) return
  const outcome = togglePinnedProject(props.owner, project.id, true)
  // A failed write must not repaint the chip as pinned — a reload would silently revert it.
  if (outcome.result.ok) pins.value = outcome.pins
  unavailable.value = outcome.result.ok ? null : '本机存储不可用或已满，本次未能固定项目。'
}

function unpin(projectId: string): void {
  if (!props.owner) return
  const outcome = togglePinnedProject(props.owner, projectId, false)
  if (outcome.result.ok) pins.value = outcome.pins
  unavailable.value = outcome.result.ok ? null : '本机存储不可用或已满，本次未能调整固定项目。'
}

// Hand ordering: dragging a chip over another swaps their stored order immediately.
function onDrop(target: string): void {
  const source = dragging.value
  dragging.value = null
  dragOver.value = null
  if (!props.owner || !source || source === target) return
  const order = pins.value.map(pin => pin.projectId)
  const from = order.indexOf(source)
  const to = order.indexOf(target)
  if (from < 0 || to < 0) return
  order.splice(to, 0, ...order.splice(from, 1))
  const reordered = order.map(projectId => pins.value.find(pin => pin.projectId === projectId)!)
  const result = writePinnedProjects(props.owner, reordered)
  if (result.ok) pins.value = reordered
  unavailable.value = result.ok ? null : '本机存储不可用或已满，本次未能调整顺序。'
}
</script>

<template>
  <div v-if="owner && (pins.length || projects.length)" class="pinned-projects">
    <ul aria-label="固定的项目">
      <li v-if="pinnableActive">
        <button type="button" class="pinned-projects__pin" :aria-label="`固定当前项目 ${pinnableActive.name}`" @click="pinActive">
          <Pin :size="12" aria-hidden="true" />
          固定当前项目
        </button>
      </li>
      <li
        v-for="entry in resolved"
        :key="entry.pin.projectId"
        :class="{ 'pinned-projects__drag-over': dragOver === entry.pin.projectId, 'pinned-projects__dragging': dragging === entry.pin.projectId }"
        :draggable="pins.length > 1"
        @dragstart="dragging = entry.pin.projectId"
        @dragend="dragging = null; dragOver = null"
        @dragover.prevent="dragOver = entry.pin.projectId"
        @drop.prevent="onDrop(entry.pin.projectId)"
      >
        <button v-if="entry.project" type="button" class="pinned-projects__open" @click="emit('select', entry.pin.projectId)">
          <Pin :size="12" aria-hidden="true" />
          <span>{{ entry.project.key }} · {{ entry.project.name }}</span>
        </button>
        <span v-else class="pinned-projects__gone">
          <PinOff :size="12" aria-hidden="true" />
          项目已不可见
        </span>
        <button type="button" class="pinned-projects__unpin" :aria-label="`取消固定 ${entry.project ? entry.project.name : '已不可见的项目'}`" @click="unpin(entry.pin.projectId)"><X :size="12" aria-hidden="true" /></button>
      </li>
    </ul>
    <p v-if="unavailable" class="pinned-projects__notice" role="alert">{{ unavailable }}</p>
  </div>
</template>

<style scoped>
.pinned-projects { display: grid; gap: var(--cs-space-4); }
.pinned-projects ul { display: flex; flex-wrap: wrap; gap: var(--cs-space-8); margin: 0; padding: 0; list-style: none; }
.pinned-projects li { display: inline-flex; align-items: center; gap: var(--cs-space-4); border: 1px solid var(--cs-border); border-radius: 999px; background: var(--cs-surface-subtle); }
.pinned-projects li[draggable="true"] { cursor: grab; }
.pinned-projects__drag-over { border-color: var(--cs-border-accent-strong); }
.pinned-projects__dragging { opacity: .5; }
.pinned-projects__open { display: inline-flex; align-items: center; gap: var(--cs-space-4); padding: var(--cs-space-4) var(--cs-space-4) var(--cs-space-4) var(--cs-space-8); border: 0; border-radius: 999px; background: transparent; color: var(--cs-text-secondary); font-size: var(--cs-text-xs); cursor: pointer; }
.pinned-projects__open:hover { color: var(--cs-text-brand); }
.pinned-projects__open svg { color: var(--cs-text-brand); }
.pinned-projects__gone { display: inline-flex; align-items: center; gap: var(--cs-space-4); padding: var(--cs-space-4) var(--cs-space-8); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.pinned-projects__pin { display: inline-flex; align-items: center; gap: var(--cs-space-4); padding: var(--cs-space-4) var(--cs-space-12); border: 1px dashed var(--cs-border-strong); border-radius: 999px; background: transparent; color: var(--cs-text-muted); font-size: var(--cs-text-xs); cursor: pointer; }
.pinned-projects__pin:hover { color: var(--cs-text-brand); border-color: var(--cs-border-accent-strong); }
.pinned-projects__unpin { display: grid; width: 22px; height: 22px; margin-right: var(--cs-space-4); place-items: center; border: 0; border-radius: 50%; background: transparent; color: var(--cs-text-muted); cursor: pointer; }
.pinned-projects__unpin:hover { background: var(--cs-surface); color: var(--cs-danger); }
.pinned-projects__notice { margin: 0; color: var(--cs-danger); font-size: var(--cs-text-xs); }
</style>
