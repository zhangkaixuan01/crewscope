<script setup lang="ts">
import { RefreshCw } from '@lucide/vue'
import AuthCard from './AuthCard.vue'
import AuthErrorSummary from './AuthErrorSummary.vue'
import AuthLayout from './AuthLayout.vue'
import BaseButton from '../base/BaseButton.vue'
import { useAuthStore } from '../../domains/identity/store'

const authStore = useAuthStore()
</script>

<template>
  <AuthLayout>
    <AuthCard
      v-if="authStore.state.phase !== 'error'"
      kicker="正在恢复工作入口"
      title="正在确认你的会话"
      description="读取当前账号、团队与权限后继续。"
      busy
      focus-on-mount
    >
      <p class="auth-session-boundary__status" role="status">正在恢复安全会话…</p>
    </AuthCard>
    <AuthCard
      v-else
      kicker="暂时无法连接"
      title="没有完成会话恢复"
      description="业务页面尚未载入，也没有使用缓存身份。"
    >
      <AuthErrorSummary
        title="会话服务暂时不可用"
        :messages="[authStore.state.errorMessage ?? '请稍后重新检查。']"
      />
      <BaseButton class="auth-session-boundary__retry" @click="authStore.retry">
        <template #icon><RefreshCw :size="16" /></template>
        重新检查
      </BaseButton>
    </AuthCard>
  </AuthLayout>
</template>

<style scoped>
.auth-session-boundary__status { margin: 0; color: var(--cs-text-muted); font-size: 10px; }
.auth-session-boundary__retry { width: 100%; min-height: var(--cs-auth-control-height); }
</style>
