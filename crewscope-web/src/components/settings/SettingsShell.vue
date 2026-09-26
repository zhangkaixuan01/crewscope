<script setup lang="ts">
import { Undo2 } from '@lucide/vue'
import { computed, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { applyConfigureReturn } from '../../app/configureReturn'
import AppShell from '../layout/AppShell.vue'
import BaseButton from '../base/BaseButton.vue'
import SettingsFieldSearch from './SettingsFieldSearch.vue'

export interface SettingsNavItem { key: string; label: string; route: string; description?: string }
const props = withDefaults(defineProps<{ title: string; eyebrow?: string; items?: SettingsNavItem[] }>(), {
  eyebrow: 'Settings · 配置中心',
  items: () => [
    { key: 'agents', label: 'Agent 配置', route: '/settings/agents' },
    { key: 'models', label: '模型与凭证', route: '/settings/models' },
    { key: 'repositories', label: '受管仓库', route: '/settings/repositories' },
    { key: 'github', label: 'GitHub', route: '/settings/integrations/github' },
    { key: 'lark', label: '飞书', route: '/settings/integrations/lark' },
    { key: 'setup', label: '配置健康', route: '/setup' },
    { key: 'account', label: '账号', route: '/account' },
    { key: 'members', label: '团队成员', route: '/team/members' },
    { key: 'operations', label: '运维', route: '/operations' },
  ],
})
const route = useRoute()
const router = useRouter()
const search = ref('')
const filteredItems = computed(() => {
  const needle = search.value.trim().toLocaleLowerCase()
  return needle ? props.items.filter(item => `${item.label} ${item.description ?? ''}`.toLocaleLowerCase().includes(needle)) : props.items
})
/** F02 configure-return: only a registered origin coordinate offers a way back to the task. */
const configureReturn = computed(() => applyConfigureReturn(route.query))
async function goConfigureReturn(): Promise<void> {
  const target = configureReturn.value
  if (!target) return
  await router.push({ name: target.routeName, query: target.query })
}
</script>

<template>
  <AppShell :title="title" :eyebrow="eyebrow">
    <template #actions>
      <BaseButton v-if="configureReturn" variant="ghost" size="small" @click="goConfigureReturn"><Undo2 :size="14" />{{ configureReturn.label }}</BaseButton>
      <slot name="actions" />
    </template>
    <div class="settings-shell">
      <nav class="settings-shell__nav" aria-label="Settings 二级导航">
        <input v-model="search" type="search" aria-label="搜索配置项" placeholder="搜索配置" /><SettingsFieldSearch :query="search" />
        <RouterLink v-for="item in filteredItems" :key="item.key" :to="item.route" :aria-current="route.path === item.route ? 'page' : undefined">{{ item.label }}</RouterLink>
        <span v-if="filteredItems.length === 0" class="settings-shell__empty">没有匹配配置</span>
      </nav>
      <section class="settings-shell__content" aria-label="配置内容"><slot /></section>
    </div>
  </AppShell>
</template>

<style scoped>
.settings-shell { display: grid; grid-template-columns: 190px minmax(0, 1fr); gap: var(--cs-space-20); min-width: 0; }
.settings-shell__nav { display: grid; align-content: start; gap: var(--cs-space-4); position: sticky; top: var(--cs-space-16); height: fit-content; padding: var(--cs-space-8); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); }
.settings-shell__nav a { min-height: var(--cs-density-control-height); display: flex; align-items: center; padding: 0 var(--cs-space-12); border-radius: var(--cs-radius-sm); color: var(--cs-text-secondary); font-size: var(--cs-text-sm); text-decoration: none; }
.settings-shell__nav input { min-height: var(--cs-density-control-height); padding: 0 var(--cs-space-8); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); font-size: var(--cs-text-base); }.settings-shell__empty { padding: var(--cs-space-8); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.settings-shell__nav a:hover, .settings-shell__nav a[aria-current='page'] { background: var(--cs-surface-accent); color: var(--cs-text-brand-strong); font-weight: var(--cs-weight-semibold); }
.settings-shell__content { min-width: 0; }
@media (max-width: 767px) { .settings-shell { grid-template-columns: 1fr; gap: var(--cs-space-12); } .settings-shell__nav { position: static; display: flex; overflow-x: auto; } .settings-shell__nav a { flex: 0 0 auto; } }
</style>
