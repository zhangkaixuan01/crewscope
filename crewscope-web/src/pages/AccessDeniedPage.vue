<script setup lang="ts">
import { ArrowLeft, ShieldX } from '@lucide/vue'
import { RouterLink, useRoute } from 'vue-router'
import AppShell from '../components/layout/AppShell.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
import { permissions } from '../app/auth'

const route = useRoute()
const permissionLabels: Record<string, string> = {
  [permissions.scopeRead]: '查看团队范围', [permissions.conversationUse]: '使用对话', [permissions.workRead]: '查看工作项',
  [permissions.teamMembersRead]: '查看团队成员', [permissions.repositoriesManage]: '管理受管仓库', [permissions.providerManage]: '管理集成与模型',
  [permissions.agentManage]: '管理 Agent', [permissions.teamMembersManage]: '管理团队成员', [permissions.workProjectsManage]: '管理 WorkProject',
  [permissions.auditRead]: '查看审计', [permissions.operationsManage]: '管理运维',
}
const requiredPermissionLabel = () => permissionLabels[String(route.query.requiredPermission ?? '')] ?? '访问此区域'
</script>

<template>
  <AppShell eyebrow="访问 · 权限守卫" title="当前账号无法访问这个区域">
    <section class="access-panel panel" aria-labelledby="access-title">
      <ShieldX :size="30" aria-hidden="true" />
      <div>
        <p class="eyebrow">权限边界</p>
        <h2 id="access-title">需要额外的团队权限</h2>
        <p class="access-required">当前区域需要“{{ requiredPermissionLabel() }}”权限。</p>
        <p>界面守卫已阻止进入；服务端仍会对每个资源请求执行完整的 Team Scope 授权。</p>
        <small>请联系 Team Owner 或管理员授予所需权限。</small>
      </div>
    </section>
    <StatePanel state="forbidden" title="返回可访问的工作区" description="你可以回到 Today，或切换到其他 Team 后重试。">
      <template #action>
        <RouterLink class="back-link" :to="{ name: 'today' }"><ArrowLeft :size="14" />返回 Today</RouterLink>
      </template>
    </StatePanel>
  </AppShell>
</template>

<style scoped>
.access-panel { display: flex; max-width: 760px; align-items: flex-start; gap: var(--cs-space-16); padding: var(--cs-space-24); }
.access-panel > svg { flex: 0 0 auto; padding: var(--cs-space-12); border-radius: var(--cs-radius-md); box-sizing: content-box; background: var(--cs-danger-soft); color: var(--cs-danger); }
.access-panel h2 { margin-bottom: var(--cs-space-8); font-size: var(--cs-text-lg); }
.access-panel p { max-width: 620px; margin-bottom: var(--cs-space-12); color: var(--cs-text-secondary); }
.access-panel small { color: var(--cs-text-muted); }
.back-link { display: inline-flex; min-height: 34px; align-items: center; gap: var(--cs-space-8); padding: 0 var(--cs-space-12); border-radius: var(--cs-radius-sm); background: var(--cs-brand-800); color: var(--cs-text-on-dark); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
</style>
