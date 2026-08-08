<script setup lang="ts">
import { ArrowLeft, ShieldX } from '@lucide/vue'
import { useRoute } from 'vue-router'
import AppShell from '../components/layout/AppShell.vue'
import StatePanel from '../components/feedback/StatePanel.vue'

const route = useRoute()
</script>

<template>
  <AppShell eyebrow="Access · Permission guard" title="当前账号无法访问这个区域">
    <section class="access-panel panel" aria-labelledby="access-title">
      <ShieldX :size="30" aria-hidden="true" />
      <div>
        <p class="eyebrow">权限边界</p>
        <h2 id="access-title">需要额外的团队权限</h2>
        <p>界面守卫已阻止进入；服务端仍会对每个资源请求执行完整的 Team Scope 授权。</p>
        <small v-if="route.query.from" class="mono">请求位置：{{ route.query.from }}</small>
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
.access-panel { display: flex; max-width: 760px; align-items: flex-start; gap: 16px; padding: 24px; }
.access-panel > svg { flex: 0 0 auto; padding: 10px; border-radius: var(--cs-radius-md); box-sizing: content-box; background: var(--cs-danger-soft); color: var(--cs-danger); }
.access-panel h2 { margin-bottom: 8px; font-size: 18px; }
.access-panel p { max-width: 620px; margin-bottom: 10px; color: var(--cs-text-secondary); }
.access-panel small { color: var(--cs-text-muted); }
.back-link { display: inline-flex; min-height: 34px; align-items: center; gap: 6px; padding: 0 12px; border-radius: var(--cs-radius-sm); background: var(--cs-brand-800); color: white; font-size: 11px; font-weight: 700; }
</style>
