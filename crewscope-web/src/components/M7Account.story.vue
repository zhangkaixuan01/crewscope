<script setup lang="ts">
import '../design/tokens.css'
import '../design/base.css'
import '../design/layout.css'
import AccountWorkspace from './account/AccountWorkspace.vue'

const profile = {
  accountId: 'account-story', username: 'zhangkaixuan', email: 'zhangkaixuan@example.com', displayName: '张凯旋',
  status: 'ACTIVE' as const, platformRole: 'USER' as const, securityVersion: 3, version: 7,
  createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-29T00:00:00Z',
}
</script>

<template>
  <Story title="M7/Account settings" :layout="{ type: 'single', iframe: true, width: 1100 }">
    <Variant title="Ready"><main class="story-stage"><AccountWorkspace :profile="profile" command-phase="idle" :operation="null" :problem="null" :command-generation="0" online /></main></Variant>
    <Variant title="Offline"><main class="story-stage"><AccountWorkspace :profile="profile" command-phase="idle" :operation="null" :problem="null" :command-generation="0" :online="false" /></main></Variant>
    <Variant title="Version conflict"><main class="story-stage"><AccountWorkspace :profile="profile" command-phase="error" operation="profile" :problem="{ code: 'optimistic_lock_conflict', title: '账号资料已在其他位置更新', message: '已重新读取最新资料，请确认后再次提交。', tone: 'warning', conflict: true }" :command-generation="1" online /></main></Variant>
  </Story>
</template>

<style scoped>.story-stage { min-height: 100vh; padding: 24px; background: var(--cs-canvas); }</style>
