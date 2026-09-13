<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, useRoute, type RouteLocationRaw } from 'vue-router'

export interface BreadcrumbItem {
  label: string
  to?: RouteLocationRaw
}

const props = defineProps<{ items?: BreadcrumbItem[] }>()
const route = useRoute()
const labels: Record<string, string> = {
  today: '今日', conversation: '对话', work: '工作', activity: '动态', inbox: 'Inbox',
  'team-observer': '团队观测', operations: '运行与发布', audit: '审计中心', members: '团队成员',
  agents: 'Agent 中心', models: '模型与凭证', lark: '飞书与通知', github: 'GitHub 集成', repositories: '仓库设置',
  setup: '配置中心', account: '账号', search: '统一搜索',
}
const items = computed<BreadcrumbItem[]>(() => {
  if (props.items) return props.items
  const section = typeof route.meta.section === 'string' ? route.meta.section : ''
  const label = typeof route.meta.title === 'string' ? route.meta.title : labels[section] ?? 'CrewScope'
  return [{ label: 'CrewScope', to: { name: 'today', query: route.query } }, { label }]
})
</script>

<template>
  <nav v-if="items.length > 0" class="app-breadcrumb" aria-label="面包屑">
    <ol>
      <li v-for="(item, index) in items" :key="`${item.label}-${index}`">
        <RouterLink v-if="item.to && index < items.length - 1" :to="item.to">{{ item.label }}</RouterLink>
        <span v-else aria-current="page">{{ item.label }}</span>
        <span v-if="index < items.length - 1" class="separator" aria-hidden="true">/</span>
      </li>
    </ol>
  </nav>
</template>

<style scoped>
.app-breadcrumb { margin-bottom: var(--cs-space-2); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.app-breadcrumb ol { display: flex; flex-wrap: wrap; align-items: center; gap: var(--cs-space-2); margin: 0; padding: 0; list-style: none; }
.app-breadcrumb li { display: inline-flex; align-items: center; gap: var(--cs-space-2); }
.app-breadcrumb a { color: var(--cs-text-muted); text-decoration: none; }
.app-breadcrumb a:hover, .app-breadcrumb a:focus-visible { color: var(--cs-action-primary); text-decoration: underline; }
.app-breadcrumb [aria-current='page'] { color: var(--cs-text-secondary); font-weight: var(--cs-weight-semibold); }
.separator { color: var(--cs-border-strong); }
</style>
