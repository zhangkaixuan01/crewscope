<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import AccountWorkspace from '../components/account/AccountWorkspace.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
import AppShell from '../components/layout/AppShell.vue'
import { useNetworkStatus } from '../app/network'
import { offlineAccountProblem, type AccountProblem } from '../domains/account/presentation'
import { useAccountStore } from '../domains/account/store'
import type { AccountPasswordChangeInput, AccountProfileUpdateInput, AccountSessionRevocationInput } from '../domains/account/types'
import { useAuthStore } from '../domains/identity/store'

const router = useRouter()
const authStore = useAuthStore()
const accountStore = useAccountStore()
const online = useNetworkStatus()
const localProblem = ref<AccountProblem | null>(null)

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
  <AppShell eyebrow="Account · Identity" title="账号设置">
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
  </AppShell>
</template>
