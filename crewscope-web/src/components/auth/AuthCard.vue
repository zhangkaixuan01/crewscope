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
  padding: clamp(24px, 3.5vw, 38px);
  border: 1px solid var(--cs-auth-card-border);
  border-radius: var(--cs-auth-card-radius);
  background: var(--cs-auth-card-surface);
  box-shadow: var(--cs-auth-card-shadow);
}
.auth-card--wide { width: min(100%, 560px); }
.auth-card__heading { margin-bottom: 24px; }
.auth-card__kicker {
  margin-bottom: 8px;
  color: var(--cs-brand-700);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: .1em;
  text-transform: uppercase;
}
.auth-card__heading h2 {
  margin-bottom: 8px;
  font-family: var(--cs-font-display);
  font-size: clamp(26px, 3vw, 34px);
  font-weight: 580;
  letter-spacing: -.025em;
}
.auth-card__description { margin: 0; color: var(--cs-text-muted); font-size: 12px; }

@media (max-width: 680px) {
  .auth-card { padding: 24px 20px; border-radius: 17px; }
  .auth-card__heading h2 { font-size: 27px; }
}
</style>
