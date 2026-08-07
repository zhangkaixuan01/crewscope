<script setup lang="ts">
import { Bot, CircleStop, Radio } from '@lucide/vue'
import type { AgentSnapshot } from '../../domains/demo/fixtures'
import StatusBadge from '../base/StatusBadge.vue'

defineProps<{ agent: AgentSnapshot }>()
</script>

<template>
  <article class="agent-presence">
    <div class="agent-presence__identity">
      <span class="agent-presence__icon"><Bot :size="19" aria-hidden="true" /></span>
      <div>
        <strong>{{ agent.name }}</strong>
        <span>{{ agent.type }}</span>
      </div>
      <StatusBadge :tone="agent.status === 'running' ? 'agent' : 'neutral'" dot>
        {{ agent.status === 'running' ? '运行中' : agent.status === 'waiting' ? '等待中' : '离线' }}
      </StatusBadge>
    </div>
    <div class="agent-presence__step">
      <Radio :size="15" aria-hidden="true" />
      <span>{{ agent.step }}</span>
    </div>
    <footer>
      <span>{{ agent.runtime }}</span>
      <button type="button"><CircleStop :size="14" aria-hidden="true" />接管</button>
    </footer>
  </article>
</template>

<style scoped>
.agent-presence { padding: 14px; border: 1px solid #ddd3ef; border-radius: var(--cs-radius-md); background: linear-gradient(135deg, var(--cs-surface), var(--cs-agent-soft)); }
.agent-presence__identity { display: grid; grid-template-columns: 38px 1fr auto; align-items: center; gap: 9px; }
.agent-presence__icon { display: grid; width: 38px; height: 38px; place-items: center; border-radius: 11px; background: var(--cs-agent); color: white; }
.agent-presence__identity strong, .agent-presence__identity span { display: block; }
.agent-presence__identity > div > span { color: var(--cs-text-muted); font-size: 11px; }
.agent-presence__step { display: flex; align-items: center; gap: 8px; margin: 13px 0; padding: 10px; border-radius: var(--cs-radius-sm); background: rgb(255 255 255 / 72%); color: var(--cs-text-secondary); font-size: 12px; }
.agent-presence__step svg { flex: 0 0 auto; color: var(--cs-agent); }
.agent-presence footer { display: flex; align-items: center; justify-content: space-between; gap: 8px; color: var(--cs-text-muted); font-size: 10px; }
.agent-presence footer button { display: inline-flex; align-items: center; gap: 4px; padding: 5px 7px; border-radius: 6px; background: transparent; color: var(--cs-agent); font-weight: 700; cursor: pointer; }
</style>
