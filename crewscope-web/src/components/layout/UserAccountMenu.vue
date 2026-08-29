<script setup lang="ts">
import { LogOut, Settings, UserRound } from '@lucide/vue'
import { computed, nextTick, ref } from 'vue'
import { RouterLink } from 'vue-router'

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
      <span v-if="!compact" class="user-menu__identity"><strong>{{ displayName }}</strong><small>{{ role }}</small></span>
      <Settings v-if="!compact" :size="16" aria-hidden="true" />
    </button>
    <div v-if="open" ref="popover" class="user-menu__popover" role="menu" aria-label="账号菜单">
      <div class="user-menu__summary"><span class="user-menu__avatar">{{ avatar }}</span><div><strong>{{ displayName }}</strong><small>{{ role }}</small></div></div>
      <p v-if="error" class="user-menu__error" role="alert">{{ error }}</p>
      <RouterLink role="menuitem" :to="{ name: 'account' }" @click="open = false"><UserRound :size="16" />账号设置</RouterLink>
      <button role="menuitem" type="button" :disabled="pending" @click="emit('signOut')"><LogOut :size="16" />{{ pending ? '正在退出…' : '退出当前设备' }}</button>
    </div>
  </div>
</template>

<style scoped>
.user-menu { position: relative; width: 100%; }.user-menu__trigger { display: grid; width: 100%; min-height: 48px; grid-template-columns: 32px 1fr 16px; align-items: center; gap: 9px; padding: 8px; border-top: 1px solid #d8e4db; background: transparent; text-align: left; cursor: pointer; }
.user-menu__avatar { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 50%; background: var(--cs-brand-600); color: white; font-size: 11px; font-weight: 750; }
.user-menu__identity strong, .user-menu__identity small, .user-menu__summary strong, .user-menu__summary small { display: block; }.user-menu__identity strong, .user-menu__summary strong { font-size: 11px; }.user-menu__identity small, .user-menu__summary small { color: var(--cs-text-muted); font-size: 9px; }
.user-menu__popover { position: absolute; z-index: 80; left: 0; bottom: calc(100% + 8px); display: grid; width: 218px; gap: 4px; padding: 8px; border: 1px solid var(--cs-border); border-radius: 12px; background: white; box-shadow: 0 16px 46px rgb(21 35 29 / 18%); }
.user-menu__summary { display: grid; grid-template-columns: 32px 1fr; align-items: center; gap: 9px; padding: 5px 6px 9px; border-bottom: 1px solid var(--cs-border); }
.user-menu__popover a, .user-menu__popover button { display: flex; min-height: 36px; align-items: center; gap: 8px; padding: 0 9px; border-radius: 8px; background: transparent; color: var(--cs-text-secondary); font-size: 11px; text-align: left; cursor: pointer; }.user-menu__popover a:hover, .user-menu__popover button:hover:not(:disabled) { background: var(--cs-brand-50); color: var(--cs-brand-800); }.user-menu__popover button:disabled { cursor: wait; opacity: .6; }
.user-menu__error { padding: 7px 8px; margin: 0; border-radius: 7px; background: var(--cs-danger-soft); color: #8f3732; font-size: 9px; }
.user-menu--compact { width: 34px; }.user-menu--compact .user-menu__trigger { display: grid; min-height: 34px; grid-template-columns: 1fr; padding: 0; border: 0; }.user-menu--compact .user-menu__popover { right: 0; bottom: auto; left: auto; top: calc(100% + 8px); }
</style>
