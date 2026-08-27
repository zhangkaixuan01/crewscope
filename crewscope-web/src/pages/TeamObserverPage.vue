<script setup lang="ts">
import { computed, inject } from 'vue'
import { RouterLink } from 'vue-router'
import { MessageSquare } from '@lucide/vue'
import { AUTH_PRINCIPAL } from '../app/auth'
import { useNetworkStatus } from '../app/network'
import BaseButton from '../components/base/BaseButton.vue'
import TeamObserverWorkspace from '../components/domain/TeamObserverWorkspace.vue'
import AppShell from '../components/layout/AppShell.vue'
import { useScopeStore } from '../domains/scope/store'
import type { TeamObserverScope } from '../domains/teamobserver/types'

const principal = inject(AUTH_PRINCIPAL)
const scopeStore = useScopeStore()
const online = useNetworkStatus()
const scope = computed<TeamObserverScope | null>(() => principal && scopeStore.state.selectedTeamId
  ? { organizationId: principal.organizationId, teamId: scopeStore.state.selectedTeamId }
  : null)
</script>

<template>
  <AppShell title="Team Observer" eyebrow="Collaborate / Read-only team summary">
    <template #actions>
      <RouterLink v-slot="{ navigate }" custom :to="{ name: 'conversation', query: { team: scopeStore.state.selectedTeamId, assistant: 'team-observer' } }">
        <BaseButton size="small" variant="secondary" @click="navigate"><MessageSquare :size="14" />对话式提问</BaseButton>
      </RouterLink>
    </template>
    <TeamObserverWorkspace v-if="scopeStore.state.phase === 'ready' && scope" :scope="scope" :team-name="scopeStore.selectedTeam.value?.name ?? '当前团队'" :online="online" variant="summary" />
  </AppShell>
</template>
