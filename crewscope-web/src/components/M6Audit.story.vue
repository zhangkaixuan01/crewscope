<script setup lang="ts">
import '../design/tokens.css'
import '../design/base.css'
import AuditExplorer from './domain/AuditExplorer.vue'
import type { TeamOpsCorrelationResource } from '../domains/teamops/store'
import type { AuditEvent, CorrelationGraph } from '../domains/teamops/types'

const noop = (): void => {}
const emptyFilter = { from: '2026-08-01T08:00', to: '2026-08-27T08:00', category: '', outcome: '', initiator: '', actor: '', agent: '', subjectType: '', subjectId: '', providerBinding: '', correlation: '' }
const events: AuditEvent[] = [audit(1, 'TEAM_ACCESS_DENIED', 'SECURITY', 'DENIED'), audit(2, 'ACTION_DELIVERED', 'ACTION', 'SUCCEEDED')]
const graph: CorrelationGraph = {
  correlationId: uuid(6), events: [{ eventId: uuid(1), source: 'AUDIT', eventType: 'TEAM_ACCESS_DENIED', actorType: 'USER', actorId: uuid(2), outcome: 'DENIED', occurredAt: '2026-08-27T08:00:00Z', references: [] }],
  objects: [{ type: 'WORK_ITEM', id: uuid(3), href: `/activity?team=${uuid(9)}&objectType=WORK_ITEM&objectId=${uuid(3)}`, relatedEventIds: [uuid(1)] }], hasMore: false, nextCursor: null,
}
const correlation: TeamOpsCorrelationResource = { phase: 'ready', value: graph, error: null, nextCursor: null, loadingMore: false }
const correlationLoading: TeamOpsCorrelationResource = { phase: 'loading', value: null, error: null, nextCursor: null, loadingMore: false }
const correlationError: TeamOpsCorrelationResource = { phase: 'error', value: null, error: { kind: 'unavailable', message: 'Correlation 服务暂不可用', status: 503, retryable: true, currentVersion: null }, nextCursor: null, loadingMore: false }
const base = { phase: 'ready' as const, items: events, error: null, nextCursor: 'cursor-2', loadingMore: false, selectedEvent: null, correlation: null, correlationId: '', initialFilter: emptyFilter, online: true, canExport: true, exportPhase: 'idle' as const, exportError: null }

function audit(index: number, eventType: string, category: AuditEvent['category'], outcome: AuditEvent['outcome']): AuditEvent {
  return { eventId: uuid(index), eventType, sourceSchemaVersion: 1, category, outcome, retentionLevel: 'EXTENDED', occurredAt: '2026-08-27T08:00:00Z', identity: { initiatorId: uuid(2), actorType: 'USER', actorId: uuid(2), agentPrincipalId: null }, subject: { type: 'WORK_ITEM', id: uuid(3) }, provider: { providerBindingId: uuid(4), connectionId: uuid(5), externalOperationHash: 'a'.repeat(64) }, correlation: { correlationId: uuid(6), causationId: null, domainEventId: uuid(7) }, summary: { reasonCode: outcome.toLowerCase() } }
}
function uuid(index: number): string { return `00000000-0000-4000-8000-${String(index).padStart(12, '0')}` }
</script>

<template>
  <Story title="M6/Team Audit Explorer" :layout="{ type: 'grid', width: 1180 }">
    <Variant title="Ready"><AuditExplorer v-bind="base" @retry="noop" /></Variant>
    <Variant title="Detail"><AuditExplorer v-bind="base" :selected-event="events[0]!" /></Variant>
    <Variant title="Correlation"><AuditExplorer v-bind="base" :correlation="correlation" :correlation-id="graph.correlationId" /></Variant>
    <Variant title="Correlation loading"><AuditExplorer v-bind="base" :correlation="correlationLoading" :correlation-id="graph.correlationId" /></Variant>
    <Variant title="Correlation error"><AuditExplorer v-bind="base" :correlation="correlationError" :correlation-id="graph.correlationId" /></Variant>
    <Variant title="Loading"><AuditExplorer v-bind="base" phase="loading" :items="[]" /></Variant>
    <Variant title="Empty"><AuditExplorer v-bind="base" phase="empty" :items="[]" /></Variant>
    <Variant title="Forbidden"><AuditExplorer v-bind="base" phase="error" :items="[]" :error="{ kind: 'forbidden', message: 'forbidden', status: 403, retryable: false, currentVersion: null }" /></Variant>
    <Variant title="Offline cached"><AuditExplorer v-bind="base" :online="false" /></Variant>
    <Variant title="Cursor expired"><AuditExplorer v-bind="base" phase="error" :error="{ kind: 'cursor-expired', message: 'expired', status: 410, retryable: true, currentVersion: null }" /></Variant>
    <Variant title="Export success"><AuditExplorer v-bind="base" export-phase="ready" /></Variant>
    <Variant title="Export error"><AuditExplorer v-bind="base" export-phase="error" :export-error="{ kind: 'forbidden', message: '当前身份没有治理导出权限', status: 403, retryable: false, currentVersion: null }" /></Variant>
  </Story>
</template>
