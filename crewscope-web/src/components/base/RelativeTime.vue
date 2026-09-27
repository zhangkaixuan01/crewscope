<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { formatAbsoluteTime, formatRelativeTime } from '../../composables/useRelativeTime'
import BaseTooltip from './BaseTooltip.vue'

/**
 * The one live relative-time label (R32): every list surface shows “N 分钟前”, and the label must
 * actually roll over (“59 分钟前” → “1 小时前”) instead of freezing at whatever the first render
 * computed. One bounded 60s tick keeps it honest while the tab is visible; hidden tabs pause the
 * timer and recompute once on return, and the absolute timestamp — the only authoritative value —
 * stays one hover away.
 */
const props = defineProps<{ value: string | number | Date }>()

const now = ref(Date.now())
let timer: ReturnType<typeof setInterval> | null = null

const isoValue = computed(() => {
  const timestamp = props.value instanceof Date ? props.value.getTime()
    : typeof props.value === 'number' ? props.value
    : new Date(props.value).getTime()
  return Number.isFinite(timestamp) ? new Date(timestamp).toISOString() : ''
})
const relative = computed(() => formatRelativeTime(props.value, now.value))
const absolute = computed(() => formatAbsoluteTime(props.value))

function onVisibilityChange(): void {
  if (document.hidden) stop()
  else {
    // A tab can spend hours hidden; recompute immediately instead of showing a stale label
    // until the next tick boundary.
    now.value = Date.now()
    start()
  }
}
function start(): void {
  if (timer === null && !document.hidden) timer = setInterval(() => { now.value = Date.now() }, 60_000)
}
function stop(): void {
  if (timer !== null) {
    clearInterval(timer)
    timer = null
  }
}

onMounted(() => {
  start()
  document.addEventListener('visibilitychange', onVisibilityChange)
})
onBeforeUnmount(() => {
  stop()
  document.removeEventListener('visibilitychange', onVisibilityChange)
})
</script>

<template>
  <!-- The label must flow like the plain text it replaced: BaseTooltip's inline-flex root is an
       atomic box that wraps as a whole in narrow cards and reshapes the line box (one extra line
       per board card), so this instance is inline. The absolute-time bubble still anchors fine. -->
  <BaseTooltip :text="absolute" class="relative-time"><time :datetime="isoValue">{{ relative }}</time></BaseTooltip>
</template>

<style scoped>
/* Specificity must beat the shared .base-tooltip rule: single class ties on 0-1-0 and loses to
   whatever chunk injects later. */
.base-tooltip.relative-time { display: inline; }
</style>
