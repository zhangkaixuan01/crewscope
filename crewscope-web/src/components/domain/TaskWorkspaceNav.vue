<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * Anchor navigation across the workspace sections (contract §4.1).
 *
 * The section ids are deliberately not URL parameters — the S01 query whitelist is frozen and a tab
 * key would smuggle a second selection state beside `taskExecution`. Current-section tracking uses
 * an IntersectionObserver scoped to the drawer's scroll container.
 */
const props = defineProps<{
  sections: Array<{ id: string, label: string }>
  scrollContainer: HTMLElement | null
}>()

const emit = defineEmits<{ locate: [sectionId: string] }>()
const currentId = ref<string | null>(null)
let observer: IntersectionObserver | null = null

// The section elements are siblings rendered after this nav; observing waits one tick so the
// drawer body (and any async details rebuild) is on screen before targets are resolved.
onMounted(() => {
  void nextTick(() => {
    if (!props.scrollContainer || typeof IntersectionObserver === 'undefined') return
    observer = new IntersectionObserver(entries => {
      for (const entry of entries) {
        if (entry.isIntersecting) currentId.value = entry.target.id
      }
    }, { root: props.scrollContainer, rootMargin: '-15% 0px -70% 0px' })
    for (const section of props.sections) {
      const element = document.getElementById(section.id)
      if (element) observer.observe(element)
    }
  })
})

onBeforeUnmount(() => observer?.disconnect())
</script>

<template>
  <nav class="workspace-nav" aria-label="工作区分区导航">
    <a
      v-for="section in sections"
      :key="section.id"
      :href="`#${section.id}`"
      :aria-current="currentId === section.id ? 'true' : undefined"
      @click.prevent="emit('locate', section.id)"
    >{{ section.label }}</a>
  </nav>
</template>

<style scoped>
.workspace-nav { display: flex; flex-wrap: wrap; gap: var(--cs-space-4); padding: var(--cs-space-4) 0; border-bottom: 1px solid var(--cs-border); }
.workspace-nav a { padding: var(--cs-space-4) var(--cs-space-12); border-radius: 999px; color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); text-decoration: none; white-space: nowrap; }
.workspace-nav a:hover { background: var(--cs-surface-subtle); color: var(--cs-text-secondary); }
.workspace-nav a[aria-current="true"] { background: var(--cs-surface-accent-strong); color: var(--cs-text-brand); }
</style>
