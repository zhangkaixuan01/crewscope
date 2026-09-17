<script setup lang="ts">
import AuthBrandPanel from './AuthBrandPanel.vue'
import '../../design/auth-tokens.css'

withDefaults(defineProps<{
  brandTone?: 'light' | 'dark'
  stageTone?: 'mist' | 'neutral'
  motion?: 'system' | 'reduced'
  mainId?: string
}>(), {
  brandTone: 'light',
  stageTone: 'mist',
  motion: 'system',
  mainId: 'identity-primary',
})
</script>

<template>
  <div
    class="auth-layout cs-auth-theme"
    :data-auth-brand-tone="brandTone"
    :data-auth-stage-tone="stageTone"
    :data-auth-motion="motion"
  >
    <a class="auth-layout__skip" :href="`#${mainId}`">跳到主要内容</a>
    <AuthBrandPanel />
    <main :id="mainId" class="auth-layout__stage">
      <slot />
    </main>
  </div>
</template>

<style scoped>
.auth-layout {
  display: grid;
  min-width: 320px;
  min-height: 100vh;
  grid-template-columns: minmax(360px, min(46vw, 650px)) minmax(0, 1fr);
  background: var(--cs-auth-canvas);
  color: var(--cs-text);
  font-family: var(--cs-font-sans);
}
.auth-layout__skip {
  position: fixed;
  z-index: var(--cs-z-toast);
  top: 10px;
  left: 10px;
  padding: var(--cs-space-8) var(--cs-space-12);
  border-radius: 8px;
  background: var(--cs-brand-950);
  color: var(--cs-text-on-dark);
  transform: translateY(-160%);
  transition: transform var(--cs-auth-transition);
}
.auth-layout__skip:focus { transform: translateY(0); }
.auth-layout__stage {
  display: grid;
  min-width: 0;
  min-height: 100vh;
  place-items: center;
  padding: var(--cs-space-64) clamp(var(--cs-space-24), 5vw, var(--cs-space-64));
  background: var(--cs-auth-stage-surface);
}

@media (max-width: 680px) {
  .auth-layout { grid-template-columns: 1fr; }
  .auth-layout__stage { min-height: calc(100vh - 190px); align-content: start; padding: var(--cs-space-48) var(--cs-space-16) var(--cs-space-32); }
}
</style>
