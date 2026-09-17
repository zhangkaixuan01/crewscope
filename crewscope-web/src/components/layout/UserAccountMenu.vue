<script setup lang="ts">
import { LogOut, Settings, UserRound } from '@lucide/vue'
import { computed, nextTick, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { platformRoleLabels } from '../../domains/principal/labels'
import { enumLabel } from '../../domains/shared/labels'

const props = defineProps<{
  displayName: string
  role: string
  pending?: boolean
  error?: string | null
  compact?: boolean
}>()

const emit = defineEmits<{ signOut: [] }>()
const open = ref(false)
const trigger = ref<HTMLButtonElement | null>(null)
const popover = ref<HTMLElement | null>(null)
const avatar = computed(() => Array.from(props.displayName.trim())[0] ?? '?')
/** `role` carries the `PlatformRole` constant; the menu is read on every page, so it never shows it raw. */
const roleLabel = computed(() => enumLabel(props.role, platformRoleLabels))

async function toggle(): Promise<void> {
  open.value = !open.value
  if (open.value) await nextTick(() => popover.value?.querySelector<HTMLElement>('[role="menuitem"]')?.focus())
}

async function close(): Promise<void> {
  open.value = false
  await nextTick(() => trigger.value?.focus())
}

function keydown(event: KeyboardEvent): void {
  if (event.key !== 'Escape') return
  event.preventDefault()
  void close()
}
</script>

<template>
  <div class="user-menu" :class="{ 'user-menu--compact': compact }" @keydown="keydown">
    <button ref="trigger" class="user-menu__trigger" type="button" :aria-label="`账号菜单：${displayName || '当前账号'}`" :aria-expanded="open" aria-haspopup="menu" @click="toggle">
      <span class="user-menu__avatar">{{ avatar }}</span>
      <span v-if="!compact" class="user-menu__identity"><strong>{{ displayName }}</strong><small>{{ roleLabel }}</small></span>
      <Settings v-if="!compact" :size="16" aria-hidden="true" />
    </button>
    <div v-if="open" ref="popover" class="user-menu__popover" role="menu" aria-label="账号菜单">
      <div class="user-menu__summary"><span class="user-menu__avatar">{{ avatar }}</span><div><strong>{{ displayName }}</strong><small>{{ roleLabel }}</small></div></div>
      <p v-if="error" class="user-menu__error" role="alert">{{ error }}</p>
      <RouterLink role="menuitem" :to="{ name: 'account' }" @click="open = false"><UserRound :size="16" />账号设置</RouterLink>
      <button role="menuitem" type="button" :disabled="pending" @click="emit('signOut')"><LogOut :size="16" />{{ pending ? '正在退出…' : '退出当前设备' }}</button>
    </div>
  </div>
</template>

<style scoped>
.user-menu { position: relative; width: 100%; }.user-menu__trigger { display: grid; width: 100%; min-height: 48px; grid-template-columns: 32px 1fr 16px; align-items: center; gap: var(--cs-space-8); padding: var(--cs-space-8); border-top: 1px solid var(--cs-border); background: transparent; text-align: left; cursor: pointer; }
.user-menu__avatar { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 50%; background: var(--cs-brand-600); color: var(--cs-text-on-dark); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.user-menu__identity strong, .user-menu__identity small, .user-menu__summary strong, .user-menu__summary small { display: block; }.user-menu__identity strong, .user-menu__summary strong { font-size: var(--cs-text-sm); }.user-menu__identity small, .user-menu__summary small { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.user-menu__popover { position: absolute; z-index: var(--cs-z-popover); left: 0; bottom: calc(100% + 8px); display: grid; width: 218px; gap: var(--cs-space-4); padding: var(--cs-space-8); border: 1px solid var(--cs-border); border-radius: 12px; background: var(--cs-surface); box-shadow: var(--cs-shadow-float); }
.user-menu__summary { display: grid; grid-template-columns: 32px 1fr; align-items: center; gap: var(--cs-space-8); padding: var(--cs-space-4) var(--cs-space-8) var(--cs-space-8); border-bottom: 1px solid var(--cs-border); }
.user-menu__popover a, .user-menu__popover button { display: flex; min-height: 36px; align-items: center; gap: var(--cs-space-8); padding: 0 var(--cs-space-8); border-radius: 8px; background: transparent; color: var(--cs-text-secondary); font-size: var(--cs-text-sm); text-align: left; cursor: pointer; }.user-menu__popover a:hover, .user-menu__popover button:hover:not(:disabled) { background: var(--cs-surface-accent); color: var(--cs-text-brand-strong); }.user-menu__popover button:disabled { cursor: wait; opacity: .6; }
.user-menu__error { padding: var(--cs-space-8) var(--cs-space-8); margin: 0; border-radius: 7px; background: var(--cs-danger-soft); color: var(--cs-danger); font-size: var(--cs-text-xs); }
.user-menu--compact { width: var(--cs-density-control-height); }.user-menu--compact .user-menu__trigger { display: grid; min-height: var(--cs-density-control-height); grid-template-columns: 1fr; padding: 0; border: 0; }.user-menu--compact .user-menu__popover { right: 0; bottom: auto; left: auto; top: calc(100% + 8px); }
</style>
