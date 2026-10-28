<script setup lang="ts">
import { Keyboard, LogOut, Monitor, Moon, Settings, Sun, UserRound } from '@lucide/vue'
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { isThemePreference, type ThemePreference } from '../../app/preference'
import { SHORTCUT_HELP_EVENT } from '../../app/shortcuts'
import { usePreference } from '../../composables/usePreference'
import { platformRoleLabels } from '../../domains/principal/labels'
import { enumLabel } from '../../domains/shared/labels'
import BaseMenu from '../base/BaseMenu.vue'

/**
 * The top-bar account menu. 键盘合同（方向键/Home/End/Esc/Tab、外点关闭、焦点归还）由
 * BaseMenu 单源承担（M12b-F03）——迁移前它连点外面都不关。
 */
const props = defineProps<{
  displayName: string
  role: string
  pending?: boolean
  error?: string | null
  compact?: boolean
}>()

const emit = defineEmits<{ signOut: [] }>()
const open = ref(false)
const avatar = computed(() => Array.from(props.displayName.trim())[0] ?? '?')
/** `role` carries the `PlatformRole` constant; the menu is read on every page, so it never shows it raw. */
const roleLabel = computed(() => enumLabel(props.role, platformRoleLabels))

/**
 * M13b-F08（R50）：主题切换从账号设置页前移到账号菜单——任何页面两步可达。与 AccountPage
 * 共用同一持久化键；写入经 preference-change 事件由 App.vue 统一应用到根节点。
 */
const themePreference = usePreference<ThemePreference>('cs.pref.device.theme.v1', 'system', { version: 1, validate: isThemePreference })
const themeOptions: Array<{ value: ThemePreference, label: string, icon: typeof Sun }> = [
  { value: 'system', label: '跟随系统', icon: Monitor },
  { value: 'light', label: '浅色', icon: Sun },
  { value: 'dark', label: '深色', icon: Moon },
]
function setTheme(value: ThemePreference): void { themePreference.value.value = value }
/**
 * M13b-F12（二轮 A22）：快捷键帮助此前只能靠 "?" 键唤出，纯键盘入口对发现性反而是负循环——
 * 菜单里给一个可见入口，与 shortcuts 管理器走同一条 SHORTCUT_HELP_EVENT 通道（CommandPalette 监听）。
 * 主题三选里的「跟随系统」即默认外观（默认值 system），恢复默认一键可达，不再另设按钮。
 */
function openShortcuts(): void {
  open.value = false
  window.dispatchEvent(new CustomEvent(SHORTCUT_HELP_EVENT))
}
</script>

<template>
  <BaseMenu
    v-model:open="open"
    class="user-menu"
    :class="{ 'user-menu--compact': compact }"
    :placement="compact ? 'bottom-end' : 'top-start'"
    menu-class="user-menu__popover"
    :trigger-label="`账号菜单：${displayName || '当前账号'}`"
    label="账号菜单"
    trigger-class="user-menu__trigger"
  >
    <template #trigger>
      <span class="user-menu__avatar">{{ avatar }}</span>
      <span v-if="!compact" class="user-menu__identity"><strong>{{ displayName }}</strong><small>{{ roleLabel }}</small></span>
      <Settings v-if="!compact" :size="16" aria-hidden="true" />
    </template>
    <div class="user-menu__summary"><span class="user-menu__avatar">{{ avatar }}</span><div><strong>{{ displayName }}</strong><small>{{ roleLabel }}</small></div></div>
    <p v-if="error" class="user-menu__error" role="alert">{{ error }}</p>
    <RouterLink role="menuitem" :to="{ name: 'account' }" @click="open = false"><UserRound :size="16" />账号设置</RouterLink>
    <button role="menuitem" type="button" @click="openShortcuts"><Keyboard :size="16" />键盘快捷键</button>
    <div class="user-menu__theme" role="group" aria-label="主题">
      <button
        v-for="option in themeOptions"
        :key="option.value"
        role="menuitemradio"
        type="button"
        :aria-checked="themePreference.value.value === option.value"
        @click="setTheme(option.value)"
      ><component :is="option.icon" :size="16" aria-hidden="true" />{{ option.label }}</button>
    </div>
    <button role="menuitem" type="button" :disabled="pending" @click="emit('signOut')"><LogOut :size="16" />{{ pending ? '正在退出…' : '退出当前设备' }}</button>
  </BaseMenu>
</template>

<style scoped>
.user-menu { width: 100%; }
.user-menu :deep(.user-menu__trigger) { display: grid; width: 100%; min-height: 48px; grid-template-columns: 32px 1fr 16px; align-items: center; gap: var(--cs-space-8); padding: var(--cs-space-8); background: transparent; text-align: left; cursor: pointer; }
.user-menu__avatar { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 50%; background: var(--cs-brand-600); color: var(--cs-text-on-dark); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.user-menu__identity strong, .user-menu__identity small, .user-menu__summary strong, .user-menu__summary small { display: block; }.user-menu__identity strong, .user-menu__summary strong { font-size: var(--cs-text-sm); }.user-menu__identity small, .user-menu__summary small { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.user-menu :deep(.user-menu__popover) { width: 218px; gap: var(--cs-space-4); padding: var(--cs-space-8); border-radius: 12px; background: var(--cs-surface); bottom: calc(100% + 8px); }
.user-menu__summary { display: grid; grid-template-columns: 32px 1fr; align-items: center; gap: var(--cs-space-8); padding: var(--cs-space-4) var(--cs-space-8) var(--cs-space-8); }
.user-menu__theme { display: grid; gap: var(--cs-space-4); padding: var(--cs-space-4) 0; }
.user-menu__theme button[aria-checked='true'] { background: var(--cs-surface-accent); color: var(--cs-text-brand-strong); }
.user-menu :deep(.user-menu__popover) a, .user-menu :deep(.user-menu__popover) button { display: flex; min-height: 36px; align-items: center; gap: var(--cs-space-8); padding: 0 var(--cs-space-8); border-radius: 8px; background: transparent; color: var(--cs-text-secondary); font-size: var(--cs-text-sm); text-align: left; cursor: pointer; }.user-menu :deep(.user-menu__popover) a:hover, .user-menu :deep(.user-menu__popover) button:hover:not(:disabled) { background: var(--cs-surface-accent); color: var(--cs-text-brand-strong); }.user-menu :deep(.user-menu__popover) button:disabled { cursor: wait; opacity: .6; }
.user-menu__error { padding: var(--cs-space-8) var(--cs-space-8); margin: 0; border-radius: 7px; background: var(--cs-danger-soft); color: var(--cs-danger); font-size: var(--cs-text-xs); }
.user-menu--compact { width: var(--cs-density-control-height); }.user-menu--compact :deep(.user-menu__trigger) { display: grid; min-height: var(--cs-density-control-height); grid-template-columns: 1fr; padding: 0; border: 0; }
</style>
