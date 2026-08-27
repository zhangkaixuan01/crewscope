<script setup lang="ts">
import '../design/tokens.css'
import '../design/base.css'
import TeamObserverWorkspace from './domain/TeamObserverWorkspace.vue'
import type { TeamObserverStore, TeamObserverState } from '../domains/teamobserver/store'
import type { DeepReadonly } from 'vue'

const scope = { organizationId: 'org-1', teamId: 'team-platform' }
const summary = {
  observerProfileId: 'team-observer@1', generatedAt: '2026-08-27T08:30:00Z',
  progress: [{ section: 'PROGRESS', dataScope: 'TEAM_ACTIVITY', summary: 'M6-A05 已完成并通过 API 验证。', evidenceIndex: 0 }],
  blockers: [{ section: 'BLOCKERS', dataScope: 'WORK_ITEM_SUMMARY', summary: 'MVP 发布演练仍等待恢复验证。', evidenceIndex: 1 }],
  reviewBacklog: [{ section: 'REVIEW_BACKLOG', dataScope: 'WORK_ITEM_SUMMARY', summary: 'CRW-214 等待 Reviewer Agent 复核。', evidenceIndex: 2 }],
  pendingConfirmations: [{ section: 'PENDING_CONFIRMATIONS', dataScope: 'TEAM_INBOX_SUMMARY', summary: '生产通知重投需要管理员确认。', evidenceIndex: 3 }],
  anomalies: [{ section: 'ANOMALIES', dataScope: 'TEAM_ACTIVITY', summary: 'Notification Lag 高于团队基线。', evidenceIndex: 4 }],
}
const ready = fixtureStore({ phase: 'completed', summary, session: { sessionId: 'session-1', observerProfileId: 'team-observer@1', mode: 'READ_ONLY', createdAt: '2026-08-27T08:00:00Z' }, invocationId: 'invocation-1' })
const reconnecting = fixtureStore({ phase: 'reconnecting', invocationId: 'invocation-1' })
const running = fixtureStore({ phase: 'running', invocationId: 'invocation-1' })
const cancelled = fixtureStore({ phase: 'cancelled', invocationId: 'invocation-1' })
const failed = fixtureStore({ phase: 'error', invocationId: 'invocation-1', errorMessage: 'Team Observer 连接暂不可用', errorStatus: 503, retryable: true })
const idle = fixtureStore({ phase: 'idle' })

function fixtureStore(overrides: Partial<TeamObserverState>): TeamObserverStore {
  const state: TeamObserverState = { phase: 'idle', session: null, invocationId: null, instruction: '', summary: null, lastSequence: -1, errorMessage: null, errorStatus: null, retryable: false, ...overrides }
  return {
    state: state as DeepReadonly<TeamObserverState>, activateScope() {}, async invoke() { return false }, async retry() { return false },
    async cancel() { return false }, async refreshSummary() { return false }, async resolveEvidence() { return null }, reset() {},
  }
}
</script>

<template>
  <Story title="M6/Team Observer" :layout="{ type: 'grid', width: 1100 }">
    <Variant title="Conversation summary"><TeamObserverWorkspace :scope="scope" team-name="Platform Engineering" :online="true" variant="conversation" :observer-store="ready" /></Variant>
    <Variant title="Control summary"><TeamObserverWorkspace :scope="scope" team-name="Platform Engineering" :online="true" variant="summary" :observer-store="ready" /></Variant>
    <Variant title="Reconnect same invocation"><TeamObserverWorkspace :scope="scope" team-name="Platform Engineering" :online="true" variant="conversation" :observer-store="reconnecting" /></Variant>
    <Variant title="Running"><TeamObserverWorkspace :scope="scope" team-name="Platform Engineering" :online="true" variant="conversation" :observer-store="running" /></Variant>
    <Variant title="Cancelled"><TeamObserverWorkspace :scope="scope" team-name="Platform Engineering" :online="true" variant="conversation" :observer-store="cancelled" /></Variant>
    <Variant title="Retryable error"><TeamObserverWorkspace :scope="scope" team-name="Platform Engineering" :online="true" variant="conversation" :observer-store="failed" /></Variant>
    <Variant title="Empty"><TeamObserverWorkspace :scope="scope" team-name="Platform Engineering" :online="true" variant="summary" :observer-store="idle" /></Variant>
    <Variant title="Offline cached"><TeamObserverWorkspace :scope="scope" team-name="Platform Engineering" :online="false" variant="summary" :observer-store="ready" /></Variant>
  </Story>
</template>
