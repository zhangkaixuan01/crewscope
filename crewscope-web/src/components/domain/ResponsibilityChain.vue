<script setup lang="ts">
import { Bot, UserRound } from '@lucide/vue'
import type { ResponsibilityMember } from '../../domains/demo/fixtures'

defineProps<{ members: ResponsibilityMember[] }>()
</script>

<template>
  <ol class="responsibility-chain" aria-label="责任链">
    <li v-for="(member, index) in members" :key="member.role">
      <span class="responsibility-chain__avatar" :class="{ agent: member.kind === 'agent' }">
        <Bot v-if="member.kind === 'agent'" :size="15" aria-hidden="true" />
        <UserRound v-else :size="15" aria-hidden="true" />
      </span>
      <span class="responsibility-chain__copy">
        <small>{{ member.role }}</small>
        <strong>{{ member.name }}</strong>
        <span>{{ member.detail }}</span>
      </span>
      <span v-if="index < members.length - 1" class="responsibility-chain__line" aria-hidden="true" />
    </li>
  </ol>
</template>

<style scoped>
.responsibility-chain { display: grid; gap: 0; padding: 0; margin: 0; list-style: none; }
.responsibility-chain li { position: relative; display: grid; grid-template-columns: 34px 1fr; gap: 10px; min-height: 61px; }
.responsibility-chain__avatar { z-index: 1; display: grid; width: 32px; height: 32px; place-items: center; border: 1px solid var(--cs-border-strong); border-radius: 50%; background: var(--cs-brand-50); color: var(--cs-brand-700); }
.responsibility-chain__avatar.agent { border-color: #d9cdef; background: var(--cs-agent-soft); color: var(--cs-agent); }
.responsibility-chain__copy { display: grid; align-content: start; }
.responsibility-chain__copy small { color: var(--cs-text-muted); font-size: 10px; font-weight: 750; letter-spacing: .06em; text-transform: uppercase; }
.responsibility-chain__copy strong { color: var(--cs-text); font-size: 13px; }
.responsibility-chain__copy span { color: var(--cs-text-muted); font-size: 11px; }
.responsibility-chain__line { position: absolute; top: 32px; bottom: -1px; left: 15px; width: 1px; background: var(--cs-border); }
</style>
