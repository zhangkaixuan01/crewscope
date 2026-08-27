<script setup lang="ts">
import '../design/tokens.css'
import '../design/base.css'
import InboxWorkspace from './domain/InboxWorkspace.vue'
import type { InboxCounts, InboxItem } from '../domains/teamops/types'

const noop = (): void => {}
const items: InboxItem[] = [
  inbox('00000000-0000-4000-8000-000000000901', 'OWNERSHIP', 'URGENT', 'UNREAD', 'RESPONSIBILITY_ASSIGNMENT', '2026-08-27T09:00:00Z'),
  inbox('00000000-0000-4000-8000-000000000902', 'OWNERSHIP', 'HIGH', 'READ', 'RESPONSIBILITY_ASSIGNMENT', null),
]
const counts: InboxCounts = {
  total: 8, unread: 5,
  byType: {
    OWNERSHIP: { total: 2, unread: 1 }, EXECUTION: { total: 2, unread: 1 }, REVIEW: { total: 1, unread: 1 },
    CONFIRMATION: { total: 1, unread: 1 }, EXCEPTION: { total: 2, unread: 1 },
  },
}
const base = {
  phase: 'ready' as const, items, countsPhase: 'ready' as const, counts, countsError: null, nextCursor: 'older-cursor', loadingMore: false,
  error: null, selectedItemId: null, detailPhase: 'idle' as const, detail: null, detailError: null,
  targetPhase: 'idle' as const, targetError: null, command: { phase: 'idle' as const, operation: null, targetId: null, receipt: null, error: null },
  itemType: 'OWNERSHIP' as const, sourceStatus: 'OPEN' as const, dispositionStatus: 'ALL' as const, online: true,
}

function inbox(
  inboxItemId: string,
  itemType: InboxItem['itemType'],
  priority: InboxItem['priority'],
  dispositionStatus: InboxItem['dispositionStatus'],
  sourceType: InboxItem['source']['type'],
  deadline: string | null,
): InboxItem {
  const dispositionVersion = dispositionStatus === 'UNREAD' ? 0 : 1
  return {
    inboxItemId, itemType, priority, deadline, openedAt: '2026-08-27T08:00:00Z', sourceStatus: 'OPEN',
    closeReason: null, closedAt: null, dispositionStatus, dispositionVersion, etag: `"${dispositionVersion}"`,
    source: { type: sourceType, id: '00000000-0000-4000-8000-000000000951', revision: 3 },
  }
}
</script>

<template>
  <Story title="M6/Member Inbox" :layout="{ type: 'grid', width: 1000 }">
    <Variant title="Ready"><InboxWorkspace v-bind="base" @retry="noop" /></Variant>
    <Variant title="Detail and actions"><InboxWorkspace v-bind="base" :selected-item-id="items[0]!.inboxItemId" detail-phase="ready" :detail="{ value: items[0]!, etag: '&quot;0&quot;' }" /></Variant>
    <Variant title="Detail loading"><InboxWorkspace v-bind="base" :selected-item-id="items[0]!.inboxItemId" detail-phase="loading" /></Variant>
    <Variant title="Detail error"><InboxWorkspace v-bind="base" :selected-item-id="items[0]!.inboxItemId" detail-phase="error" :detail-error="{ kind: 'unavailable', message: 'Inbox 详情暂不可用', status: 503, retryable: true, currentVersion: null }" /></Variant>
    <Variant title="Conflict"><InboxWorkspace v-bind="base" :selected-item-id="items[1]!.inboxItemId" detail-phase="ready" :detail="{ value: items[1]!, etag: '&quot;1&quot;' }" :command="{ phase: 'conflict', operation: 'inbox-disposition', targetId: items[1]!.inboxItemId, receipt: null, error: { kind: 'conflict', message: '处置版本已变化', status: 409, retryable: true, currentVersion: 2 } }" /></Variant>
    <Variant title="Loading"><InboxWorkspace v-bind="base" phase="loading" :items="[]" counts-phase="loading" :counts="null" /></Variant>
    <Variant title="Count error"><InboxWorkspace v-bind="base" counts-phase="error" :counts="null" :counts-error="{ kind: 'unavailable', message: '计数服务暂不可用', status: 503, retryable: true, currentVersion: null }" /></Variant>
    <Variant title="Empty"><InboxWorkspace v-bind="base" phase="empty" :items="[]" /></Variant>
    <Variant title="Forbidden"><InboxWorkspace v-bind="base" phase="error" :items="[]" :error="{ kind: 'forbidden', message: 'forbidden', status: 403, retryable: false, currentVersion: null }" /></Variant>
    <Variant title="Offline cached"><InboxWorkspace v-bind="base" :online="false" /></Variant>
    <Variant title="Cursor expired"><InboxWorkspace v-bind="base" phase="error" :error="{ kind: 'cursor-expired', message: 'expired', status: 410, retryable: true, currentVersion: null }" /></Variant>
    <Variant title="Cached hard error"><InboxWorkspace v-bind="base" phase="error" :error="{ kind: 'unavailable', message: 'Inbox 服务暂不可用', status: 503, retryable: true, currentVersion: null }" /></Variant>
  </Story>
</template>
