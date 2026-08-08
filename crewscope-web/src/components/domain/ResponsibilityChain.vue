<script setup lang="ts">
import { Bot, UserRound, X } from '@lucide/vue'
import { computed } from 'vue'
import type { ResponsibilityAssignment } from '../../domains/workitem/types'

const props = withDefaults(defineProps<{
  members: ResponsibilityAssignment[]
  canManage?: boolean
  commandPending?: string | null
  onRelease?: (assignment: ResponsibilityAssignment) => Promise<void>
}>(), {
  canManage: false,
  commandPending: null,
  onRelease: undefined,
})

const orderedMembers = computed(() => [...props.members].sort((left, right) => {
  const order = { OWNER: 0, EXECUTOR: 1, REVIEWER: 2 }
  return order[left.role] - order[right.role] || left.assignedAt.localeCompare(right.assignedAt)
}))

function roleLabel(member: ResponsibilityAssignment): string {
  if (member.role === 'OWNER') return 'Owner'
  if (member.role === 'EXECUTOR') return 'Executor'
  return member.actorType === 'USER' ? 'Gate Reviewer' : 'Advisory Reviewer'
}

function actorDetail(member: ResponsibilityAssignment): string {
  if (member.role === 'OWNER') return '对交付结果负责'
  if (member.role === 'EXECUTOR') return member.actorType === 'USER' ? '团队执行责任' : 'Agent 执行责任'
  return member.actorType === 'USER' ? '具备 Gate 审查效力' : '提供建议，不具有 Gate 效力'
}

async function release(member: ResponsibilityAssignment): Promise<void> {
  if (!props.onRelease) return
  try {
    await props.onRelease(member)
  } catch {
    // The WorkItem Store refreshes the chain and exposes a sanitized policy/concurrency error.
  }
}
</script>

<template>
  <ol class="responsibility-chain" aria-label="责任链">
    <li v-for="(member, index) in orderedMembers" :key="member.id">
      <span class="responsibility-chain__avatar" :class="{ agent: member.actorType !== 'USER' }">
        <Bot v-if="member.actorType !== 'USER'" :size="15" aria-hidden="true" />
        <UserRound v-else :size="15" aria-hidden="true" />
      </span>
      <span class="responsibility-chain__copy">
        <small>{{ roleLabel(member) }}</small>
        <strong>{{ member.actorDisplayName }}</strong>
        <span>{{ actorDetail(member) }} · {{ member.actorType }}</span>
      </span>
      <button
        v-if="canManage && member.role !== 'OWNER' && onRelease"
        type="button"
        class="responsibility-chain__release"
        :aria-label="`释放 ${member.actorDisplayName} 的 ${roleLabel(member)} 责任`"
        :disabled="Boolean(commandPending)"
        @click="release(member)"
      ><X :size="12" />释放</button>
      <span v-if="index < orderedMembers.length - 1" class="responsibility-chain__line" aria-hidden="true" />
    </li>
  </ol>
</template>

<style scoped>
.responsibility-chain { display: grid; gap: 0; padding: 0; margin: 0; list-style: none; }
.responsibility-chain li { position: relative; display: grid; grid-template-columns: 34px minmax(0, 1fr) auto; gap: 10px; min-height: 61px; }
.responsibility-chain__avatar { z-index: 1; display: grid; width: 32px; height: 32px; place-items: center; border: 1px solid var(--cs-border-strong); border-radius: 50%; background: var(--cs-brand-50); color: var(--cs-brand-700); }
.responsibility-chain__avatar.agent { border-color: #d9cdef; background: var(--cs-agent-soft); color: var(--cs-agent); }
.responsibility-chain__copy { display: grid; align-content: start; }
.responsibility-chain__copy small { color: var(--cs-text-muted); font-size: 10px; font-weight: 750; letter-spacing: .06em; text-transform: uppercase; }
.responsibility-chain__copy strong { color: var(--cs-text); font-size: 13px; }
.responsibility-chain__copy span { color: var(--cs-text-muted); font-size: 11px; }
.responsibility-chain__release { align-self: start; display: inline-flex; min-height: 26px; align-items: center; gap: 3px; padding: 0 7px; border: 1px solid var(--cs-border); border-radius: 7px; background: var(--cs-surface-subtle); color: var(--cs-text-muted); font-size: 9px; cursor: pointer; }
.responsibility-chain__release:hover:not(:disabled) { border-color: var(--cs-border-strong); color: var(--cs-danger); }
.responsibility-chain__release:disabled { cursor: wait; opacity: .55; }
.responsibility-chain__line { position: absolute; top: 32px; bottom: -1px; left: 15px; width: 1px; background: var(--cs-border); }
</style>
