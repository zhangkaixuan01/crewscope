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
.app-breadcrumb { position: relative; z-index: 1; margin-bottom: var(--cs-space-8); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.app-breadcrumb ol { display: flex; flex-wrap: wrap; align-items: center; gap: var(--cs-space-8); margin: 0; padding: 0; list-style: none; }
.app-breadcrumb li { display: inline-flex; align-items: center; gap: var(--cs-space-8); }
.app-breadcrumb a { display: inline-flex; min-width: 24px; min-height: 24px; align-items: center; padding-inline: var(--cs-space-2); margin-inline: calc(var(--cs-space-2) * -1); color: var(--cs-text-muted); text-decoration: none; }
.app-breadcrumb a:hover, .app-breadcrumb a:focus-visible { color: var(--cs-action-primary); text-decoration: underline; }
.app-breadcrumb [aria-current='page'] { color: var(--cs-text-secondary); font-weight: var(--cs-weight-semibold); }
.separator { color: var(--cs-border-strong); }
/*
 * 窄屏不显示面包屑。它在这里既是冗余也是一处修不好的命中区缺陷：
 * 默认形态是 `CrewScope / <当前页标题>`，第二项与紧跟其下的 <h1> 逐字重复，唯一的链接
 * （回「今日」）与汉堡菜单里的同一条重复；而 24px 高的它撑不大也扩不开——撑到 44px 会把
 * 每一页的页头顶下去 20px，用伪元素扩张则会盖住正上方 21px 处顶栏的图标按钮（实测 56 处
 * 全部报「扩张区被 button.icon-button 占住」）。
 * 一个够不到、又只是重复了下一行的元素，在最缺纵向空间的屏幕上占着 32px，去掉是净收益。
 */
@media (max-width: 767px) { .app-breadcrumb { display: none; } }
</style>
