<script setup lang="ts">
import { nextTick, onMounted, ref, useId } from 'vue'

const props = withDefaults(defineProps<{
  kicker?: string
  title: string
  description?: string
  size?: 'normal' | 'wide'
  busy?: boolean
  focusOnMount?: boolean
}>(), {
  kicker: undefined,
  description: undefined,
  size: 'normal',
  busy: false,
  focusOnMount: false,
})

const titleId = `auth-card-${useId()}`
const titleElement = ref<HTMLElement | null>(null)

onMounted(() => {
  if (props.focusOnMount) void nextTick(() => titleElement.value?.focus())
})
</script>

<template>
  <article
    class="auth-card"
    :class="`auth-card--${size}`"
    :aria-labelledby="titleId"
    :aria-busy="busy"
  >
    <slot name="before" />
    <header class="auth-card__heading">
      <p v-if="kicker" class="auth-card__kicker">{{ kicker }}</p>
      <h2 :id="titleId" ref="titleElement" :tabindex="focusOnMount ? -1 : undefined"><slot name="title">{{ title }}</slot></h2>
      <p v-if="description" class="auth-card__description">{{ description }}</p>
    </header>
    <slot />
    <slot name="footer" />
  </article>
</template>

<style scoped>
.auth-card {
  width: min(100%, 430px);
  padding: clamp(var(--cs-space-24), 3.5vw, var(--cs-space-40));
  border: 1px solid var(--cs-auth-card-border);
  border-radius: var(--cs-auth-card-radius);
  background: var(--cs-auth-card-surface);
  box-shadow: var(--cs-auth-card-shadow);
}
.auth-card--wide { width: min(100%, 560px); }
.auth-card__heading { margin-bottom: var(--cs-space-24); }
.auth-card__kicker {
  margin-bottom: var(--cs-space-8);
  color: var(--cs-text-brand);
  font-size: var(--cs-text-sm);
  font-weight: var(--cs-weight-semibold);
  letter-spacing: .1em;
  text-transform: uppercase;
}
.auth-card__heading h2 {
  margin-bottom: var(--cs-space-8);
  font-family: var(--cs-font-display);
  font-size: clamp(var(--cs-text-xl), 3vw, 34px);
  font-weight: var(--cs-weight-medium);
  letter-spacing: -.025em;
}
.auth-card__description { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-base); }

@media (max-width: 680px) {
  .auth-card { padding: var(--cs-space-24) var(--cs-space-20); border-radius: 17px; }
  .auth-card__heading h2 { font-size: var(--cs-text-xl); }
}
</style>
