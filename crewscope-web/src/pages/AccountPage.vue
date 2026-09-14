<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import AccountWorkspace from '../components/account/AccountWorkspace.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
import SettingsShell from '../components/settings/SettingsShell.vue'
import { useNetworkStatus } from '../app/network'
import { offlineAccountProblem, type AccountProblem } from '../domains/account/presentation'
import { useAccountStore } from '../domains/account/store'
import type { AccountPasswordChangeInput, AccountProfileUpdateInput, AccountSessionRevocationInput } from '../domains/account/types'
import { useAuthStore } from '../domains/identity/store'
import { isDensityPreference, isThemePreference, usePreference } from '../app/preference'

const router = useRouter()
const authStore = useAuthStore()
const accountStore = useAccountStore()
const online = useNetworkStatus()
const localProblem = ref<AccountProblem | null>(null)
const themePreference = usePreference<'system' | 'light' | 'dark'>('cs.pref.device.theme.v1', 'system', { version: 1, validate: isThemePreference })
const densityPreference = usePreference<'comfortable' | 'compact'>('cs.pref.device.density.v1', 'comfortable', { version: 1, validate: isDensityPreference })

onMounted(() => accountStore.load())
onBeforeUnmount(() => accountStore.reset())

async function saveProfile(input: AccountProfileUpdateInput): Promise<void> {
  const csrf = commandCsrf()
  if (!csrf) return
  const success = await accountStore.updateProfile(input, csrf)
  if (success) await authStore.refresh()
  else if (accountStore.state.commandProblem?.conflict) await accountStore.load(true)
}

async function changePassword(input: AccountPasswordChangeInput): Promise<void> {
  const csrf = commandCsrf()
  if (!csrf) return
  if (await accountStore.changePassword(input, csrf)) {
    authStore.signOutLocally()
    await router.replace({ name: 'login' })
  } else if (accountStore.state.commandProblem?.conflict) await accountStore.load(true)
}

async function revokeSessions(input: AccountSessionRevocationInput): Promise<void> {
  const csrf = commandCsrf()
  if (!csrf) return
  if (await accountStore.revokeAllSessions(input, csrf)) {
    authStore.signOutLocally()
    await router.replace({ name: 'login' })
  } else if (accountStore.state.commandProblem?.conflict) await accountStore.load(true)
}

function commandCsrf() {
  localProblem.value = null
  if (!online.value) {
    localProblem.value = offlineAccountProblem()
    return null
  }
  const csrf = authStore.state.session?.csrf
  if (!csrf) {
    localProblem.value = { code: 'csrf_rejected', title: '安全校验已失效', message: '请重新检查当前会话后再提交。', tone: 'warning', conflict: false }
    return null
  }
  return csrf
}
</script>

<template>
  <SettingsShell eyebrow="账号 · 身份安全" title="账号设置">
    <template #actions>
      <label class="preference-control">主题<select v-model="themePreference.value.value"><option value="system">跟随系统</option><option value="light">浅色</option><option value="dark">深色</option></select></label>
      <label class="preference-control">密度<select v-model="densityPreference.value.value"><option value="comfortable">舒适</option><option value="compact">紧凑</option></select></label>
    </template>
    <StatePanel v-if="accountStore.state.phase === 'idle' || accountStore.state.phase === 'loading'" state="loading" />
    <StatePanel v-else-if="accountStore.state.phase === 'error'" state="error" :message="accountStore.state.problem?.message" @retry="accountStore.load(true)" />
    <AccountWorkspace
      v-else-if="accountStore.state.profile"
      :profile="accountStore.state.profile"
      :command-phase="accountStore.state.commandPhase"
      :operation="accountStore.state.operation"
      :problem="localProblem ?? accountStore.state.commandProblem"
      :command-generation="accountStore.state.commandGeneration"
      :online="online"
      @save-profile="saveProfile"
      @change-password="changePassword"
      @revoke-sessions="revokeSessions"
    />
  </SettingsShell>
</template>

<style scoped>
.preference-control { display: inline-flex; align-items: center; gap: 6px; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.preference-control select { min-height: 32px; padding: 0 8px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text); font-size: var(--cs-text-sm); }
</style>
