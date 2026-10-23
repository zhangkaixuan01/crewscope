<script setup lang="ts">
import { ArrowLeft, RotateCcw, ShieldX } from '@lucide/vue'
import { RouterLink, useRoute, type RouteLocationRaw } from 'vue-router'
import BaseButton from '../components/base/BaseButton.vue'
import AppShell from '../components/layout/AppShell.vue'
import { permissions } from '../app/auth'

const route = useRoute()
const permissionLabels: Record<string, string> = {
  [permissions.scopeRead]: '查看团队范围', [permissions.conversationUse]: '使用对话', [permissions.workRead]: '查看工作项',
  [permissions.teamMembersRead]: '查看团队成员', [permissions.repositoriesManage]: '管理受管仓库', [permissions.providerManage]: '管理集成与模型',
  [permissions.agentManage]: '管理 Agent', [permissions.teamMembersManage]: '管理团队成员', [permissions.teamRolesManage]: '管理团队角色',
  [permissions.workProjectsManage]: '管理 WorkProject',
  [permissions.auditRead]: '查看审计', [permissions.operationsManage]: '管理运维',
}
const requiredPermissionLabel = () => permissionLabels[String(route.query.requiredPermission ?? '')] ?? '访问此区域'

// 「重试进入」只接受站内路径（/ 开头且非协议相对 //），from 由路由守卫写入。
const retryTarget = (): RouteLocationRaw => {
  const from = route.query.from
  return typeof from === 'string' && from.startsWith('/') && !from.startsWith('//') ? from : { name: 'today' }
}
</script>

<template>
  <!-- M13b-F10（L14）：三块合一——context header 的 h1 之后只剩一张居中卡片（h2 + 所需权限 +
    一句说明 + 并排两行动），不再追加整宽 StatePanel。唯一 h1 在 context header。 -->
  <AppShell eyebrow="访问 · 权限守卫" title="当前账号无法访问这个区域">
    <section class="access-panel panel" aria-labelledby="access-title">
      <ShieldX :size="30" aria-hidden="true" />
      <div>
        <p class="eyebrow">权限边界</p>
        <h2 id="access-title">需要额外的团队权限</h2>
        <p class="access-required">当前区域需要“{{ requiredPermissionLabel() }}”权限。</p>
        <p>界面守卫已阻止进入；服务端仍会对每个资源请求执行完整的 Team Scope 授权。刚被授予权限可重试进入；顶部的范围切换器可切换 Team 后再试。</p>
        <small>请联系 Team Owner 或管理员授予所需权限。</small>
        <div class="access-panel__actions">
          <RouterLink v-slot="{ navigate }" custom :to="{ name: 'today' }">
            <BaseButton @click="navigate"><ArrowLeft :size="15" />返回 Today</BaseButton>
          </RouterLink>
          <RouterLink v-slot="{ navigate }" custom :to="retryTarget()">
            <BaseButton variant="secondary" @click="navigate"><RotateCcw :size="15" />重试进入</BaseButton>
          </RouterLink>
        </div>
      </div>
    </section>
  </AppShell>
</template>

<style scoped>
.access-panel { display: flex; max-width: 720px; margin-inline: auto; align-items: flex-start; gap: var(--cs-space-16); padding: var(--cs-space-24); }
.access-panel > svg { flex: 0 0 auto; padding: var(--cs-space-12); border-radius: var(--cs-radius-md); box-sizing: content-box; background: var(--cs-danger-soft); color: var(--cs-danger); }
.access-panel h2 { margin-bottom: var(--cs-space-8); font-size: var(--cs-text-lg); }
.access-panel p { max-width: 620px; margin-bottom: var(--cs-space-12); color: var(--cs-text-secondary); }
.access-panel small { display: block; margin-bottom: var(--cs-space-16); color: var(--cs-text-muted); }
.access-panel__actions { display: flex; flex-wrap: wrap; gap: var(--cs-space-8); }
@media (max-width: 767px) {
  .access-panel { flex-direction: column; }
}
</style>
