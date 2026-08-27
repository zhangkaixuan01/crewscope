<script setup lang="ts">
import '../design/tokens.css'
import '../design/base.css'
import LarkNotificationAdmin from './domain/LarkNotificationAdmin.vue'
import type { LarkConnection, LarkMapping, NotificationDelivery } from '../domains/teamops/types'

const noop = (): void => {}
const connection: LarkConnection = { connectionId: uuid(1), teamId: uuid(2), providerBindingId: uuid(3), providerBindingVersion: 6, maskedAppId: '****9x2k', status: 'ACTIVE', credentialStatus: 'ACTIVE', expiresAt: '2026-12-31T00:00:00Z', createdAt: '2026-08-27T01:00:00Z', updatedAt: '2026-08-27T02:00:00Z', version: 4 }
const mapping: LarkMapping = { mappingId: uuid(4), memberId: uuid(5), providerBindingId: uuid(3), status: 'ACTIVE', terminalReason: null, verifiedAt: '2026-08-27T01:30:00Z', updatedAt: '2026-08-27T01:30:00Z', version: 1 }
const delivery: NotificationDelivery = { organizationId: uuid(9), teamId: uuid(2), deliveryId: uuid(6), recipientMemberId: uuid(5), itemType: 'REVIEW', template: { templateId: uuid(7), version: 1 }, providerBindingId: uuid(3), status: 'FAILED_FINAL', attemptCount: 3, failureCode: 'RETRY_EXHAUSTED', evidenceCode: 'LARK_RETRY_EXHAUSTED', redeliveryOf: null, createdAt: '2026-08-27T01:00:00Z', updatedAt: '2026-08-27T02:00:00Z', version: 3 }
const base = {
  phase: 'ready' as const, error: null, connections: [connection], selectedConnection: connection,
  health: { status: 'HEALTHY' as const, retryable: false, retryAfterSeconds: null, evidenceCode: 'LARK_PROVIDER_HEALTHY', checkedAt: '2026-08-27T02:00:00Z' },
  mappings: [mapping], mappingPhase: 'ready' as const, mappingError: null, mappingNextCursor: null, mappingLoadingMore: false,
  members: [{ id: uuid(5), userPrincipalId: uuid(10), status: 'ACTIVE', joinMethod: 'INVITED', joinedAt: '2026-08-27T01:00:00Z', version: 0 }], currentMemberId: uuid(5),
  templates: [{ ref: { templateId: uuid(7), version: 1 }, serverTemplateKey: 'review-requested', status: 'PUBLISHED' as const, variables: [{ name: 'title', type: 'TEXT' as const, maximumLength: 120 }] }],
  preference: { memberId: uuid(5), enabled: true, enabledItemTypes: ['REVIEW' as const, 'CONFIRMATION' as const], mutedUntil: null, version: 1 },
  deliveries: [delivery], deliveryPhase: 'ready' as const, deliveryError: null, deliveryNextCursor: null, deliveryLoadingMore: false,
  selectedDelivery: null, command: { phase: 'idle' as const, operation: null, targetId: null, receipt: null, error: null }, online: true,
  selectedTab: 'connection' as const, mappingStatus: null, deliveryStatus: null, deliveryType: null, recipient: '',
}
function uuid(index: number): string { return `00000000-0000-4000-8000-${String(index).padStart(12, '0')}` }
</script>

<template>
  <Story title="M6/Lark and Notification Administration" :layout="{ type: 'grid', width: 1180 }">
    <Variant title="Connection ready"><LarkNotificationAdmin v-bind="base" @refresh="noop" /></Variant>
    <Variant title="Mapping"><LarkNotificationAdmin v-bind="base" selected-tab="mapping" /></Variant>
    <Variant title="Notification history"><LarkNotificationAdmin v-bind="base" selected-tab="notification" /></Variant>
    <Variant title="Failed delivery detail"><LarkNotificationAdmin v-bind="base" selected-tab="notification" :selected-delivery="delivery" /></Variant>
    <Variant title="Loading"><LarkNotificationAdmin v-bind="base" phase="loading" :connections="[]" :selected-connection="null" /></Variant>
    <Variant title="Empty"><LarkNotificationAdmin v-bind="base" phase="empty" :connections="[]" :selected-connection="null" /></Variant>
    <Variant title="Forbidden"><LarkNotificationAdmin v-bind="base" phase="error" :connections="[]" :selected-connection="null" :error="{ kind: 'forbidden', message: 'forbidden', status: 403, retryable: false, currentVersion: null }" /></Variant>
    <Variant title="Connection error"><LarkNotificationAdmin v-bind="base" phase="error" :connections="[]" :selected-connection="null" :error="{ kind: 'unavailable', message: 'Connection 服务暂不可用', status: 503, retryable: true, currentVersion: null }" /></Variant>
    <Variant title="Mapping loading"><LarkNotificationAdmin v-bind="base" selected-tab="mapping" mapping-phase="loading" :mappings="[]" /></Variant>
    <Variant title="Mapping error"><LarkNotificationAdmin v-bind="base" selected-tab="mapping" mapping-phase="error" :mappings="[]" :mapping-error="{ kind: 'unavailable', message: 'Mapping 服务暂不可用', status: 503, retryable: true, currentVersion: null }" /></Variant>
    <Variant title="Delivery loading"><LarkNotificationAdmin v-bind="base" selected-tab="notification" delivery-phase="loading" :deliveries="[]" /></Variant>
    <Variant title="Delivery error"><LarkNotificationAdmin v-bind="base" selected-tab="notification" delivery-phase="error" :deliveries="[]" :delivery-error="{ kind: 'unavailable', message: 'Delivery 服务暂不可用', status: 503, retryable: true, currentVersion: null }" /></Variant>
    <Variant title="Offline cached"><LarkNotificationAdmin v-bind="base" :online="false" /></Variant>
    <Variant title="Cursor expired"><LarkNotificationAdmin v-bind="base" selected-tab="notification" delivery-phase="error" :delivery-error="{ kind: 'cursor-expired', message: 'expired', status: 410, retryable: false, currentVersion: null }" /></Variant>
    <Variant title="Conflict"><LarkNotificationAdmin v-bind="base" :command="{ phase: 'conflict', operation: 'lark-rotate', targetId: connection.connectionId, receipt: null, error: { kind: 'conflict', message: '版本冲突', status: 409, retryable: false, currentVersion: 5 } }" /></Variant>
  </Story>
</template>
