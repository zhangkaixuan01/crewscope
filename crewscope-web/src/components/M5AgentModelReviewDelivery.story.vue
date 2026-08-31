<script setup lang="ts">
import type { App } from 'vue'
import '../design/tokens.css'
import '../design/base.css'
import type { DeliveryGateway } from '../domains/delivery/gateway'
import { createDeliveryStore, DELIVERY_STORE } from '../domains/delivery/store'
import type {
  ActionBundle,
  DeliveryCoordinates,
  DeliveryScope,
  EtaggedActionBundle,
  GitHubConnectionOwnerType,
  PlanActionBundleInput,
} from '../domains/delivery/types'
import type { ModelCommandState, ModelResource } from '../domains/model/store'
import type { ModelConnectionSummary, ModelProviderSummary } from '../domains/model/types'
import type { ReviewCommandState } from '../domains/review/store'
import type { CommandReceipt } from '../domains/scope/types'
import type { Etagged } from '../domains/settings/types'
import {
  etaggedActionBundle,
  githubBinding,
  githubConnection,
  githubHealth,
  githubPreflight,
  githubRepository,
} from '../test/deliveryFixtures'
import { etaggedReview, reviewIds, reviewSummary } from '../test/reviewFixtures'
import { fixtureIds } from '../test/scopeFixtures'
import { taskIds } from '../test/taskFixtures'
import ActionDeliveryWorkbench from './domain/ActionDeliveryWorkbench.vue'
import AgentCreateDialog from './domain/AgentCreateDialog.vue'
import ModelConnectionDetail from './domain/ModelConnectionDetail.vue'
import ModelCredentialDialog from './domain/ModelCredentialDialog.vue'
import ReviewWorkbench from './domain/ReviewWorkbench.vue'

const noop = (): void => {}
const success = async (): Promise<boolean> => true
const provider: ModelProviderSummary = {
  key: 'deepseek', displayName: 'DeepSeek', availableRegions: ['cn'], retentionMode: 'NONE',
  maximumRetentionSeconds: null, trainingUsagePolicy: 'DISALLOWED', status: 'ACTIVE', version: 4,
}
const connection: ModelConnectionSummary = {
  id: '00000000-0000-0000-0000-000000009301', organizationId: fixtureIds.organization,
  providerKey: 'deepseek', ownerType: 'TEAM', ownerId: fixtureIds.teamPlatform, region: 'cn',
  billingSubjectType: 'TEAM', billingSubjectId: fixtureIds.teamPlatform, credentialVersion: 3,
  status: 'ACTIVE', healthStatus: 'UNHEALTHY', healthFailureCode: 'AUTHENTICATION_FAILED',
  checkedAt: '2026-08-25T08:00:00Z', lastHealthyAt: '2026-08-24T08:00:00Z', consecutiveFailures: 2,
  revocationReason: null, createdAt: '2026-08-24T08:00:00Z', updatedAt: '2026-08-25T08:00:00Z', version: 4,
}
const connectionResource: ModelResource<Etagged<ModelConnectionSummary>> = {
  phase: 'ready', value: { value: connection, etag: '"4"' }, errorMessage: null, errorStatus: null,
}
const modelCommand: ModelCommandState = {
  phase: 'idle', operation: null, connectionId: null, receipt: null,
  errorMessage: null, errorStatus: null, retryable: false,
}
const template = {
  publisherType: 'ORGANIZATION', publisherId: fixtureIds.organization, key: 'coding-specialist', version: 3,
  runtimeRole: 'CODING', allowedOwnershipTypes: ['USER', 'TEAM'], allowedExecutionScopes: ['PERSONAL', 'TEAM'],
  declaredCapabilities: ['coding', 'test'], requiredModelCapabilities: ['TOOLS'],
  approvedSkillKeys: ['coding-baseline'], memberConfigurableSlots: ['MODEL_BINDING'],
  administratorConfigurableSlots: [], creatable: true, platformManaged: false,
  contentHash: '1'.repeat(64), status: 'ACTIVE', lifecycleVersion: 1,
}
const reviewCommand: ReviewCommandState = {
  phase: 'idle', operation: null, reviewRequestId: null, receiptCorrelationId: null, execution: null,
  errorMessage: null, errorStatus: null, errorCode: null, errorDetails: {}, retryable: false,
}
const reviewProps = {
  listPhase: 'ready' as const, reviews: [reviewSummary()], selectedReviewRequestId: reviewIds.request,
  detailPhase: 'ready' as const, review: etaggedReview(), listErrorMessage: null, detailErrorMessage: null,
  codingAttempt: null, tests: null, canGate: true, online: true, command: reviewCommand,
  onSelect: noop, onRetryList: noop, onRetryDetail: noop, onExecute: success, onDecide: success,
  onRequestChanges: success, onRetryCommand: success, onClearCommand: noop,
}
const invalidatedReviewProps = {
  ...reviewProps,
  review: etaggedReview({ status: 'INVALIDATED', invalidationReason: 'DIFF_CHANGED' }),
  canGate: false,
}
const coordinates = { taskId: taskIds.first, executionId: taskIds.execution }
const approvedReview = etaggedReview({
  reviewerRelationship: 'INDEPENDENT',
  decisions: [{
    id: '00000000-0000-0000-0000-000000009302', revision: 1, type: 'APPROVED',
    rationale: '证据与验收标准完整。', reviewerMemberId: fixtureIds.principal,
    eligibilityMode: 'ASSIGNED_REVIEWER', decidedAt: '2026-08-25T08:00:00Z',
  }],
})

function setupDelivery(offline = false) {
  return async ({ app }: { app: App }): Promise<void> => {
    const store = createDeliveryStore(new StoryDeliveryGateway())
    await store.synchronize({ organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform }, coordinates)
    app.provide(DELIVERY_STORE, store)
    // The component receives the network state as a prop; the Store remains a server-fact fixture.
    void offline
  }
}

class StoryDeliveryGateway implements DeliveryGateway {
  private readonly bundle = etaggedActionBundle({ taskId: coordinates.taskId, taskExecutionId: coordinates.executionId })
  async listConnections(_scope: DeliveryScope, ownerType: GitHubConnectionOwnerType) { return ownerType === 'TEAM' ? [githubConnection()] : [] }
  async listBindings() { return [githubBinding()] }
  async listRepositories() { return [githubRepository()] }
  async synchronizeRepositories() { return [githubRepository()] }
  async preflight() { return githubPreflight() }
  async health() { return githubHealth() }
  async listBundles(): Promise<ActionBundle[]> { return [this.bundle.value] }
  async getBundle(): Promise<EtaggedActionBundle> { return this.bundle }
  async plan(_scope: DeliveryScope, _coordinates: DeliveryCoordinates, _input: PlanActionBundleInput, _key: string) { return receipt() }
  async confirm() { return receipt() }
  async cancel() { return receipt() }
  async resolveFailure() { return receipt() }
}

function receipt(): CommandReceipt {
  return {
    commandId: '00000000-0000-0000-0000-000000009303',
    domainEventId: '00000000-0000-0000-0000-000000009304', committedVersion: 1,
    correlationId: '00000000-0000-0000-0000-000000009305',
  }
}
</script>

<template>
  <Story title="M5/Agent Model Review Delivery" :layout="{ type: 'grid', width: 1040 }">
    <Variant title="Agent · approved template">
      <AgentCreateDialog :user-templates="[template]" :team-templates="[template]" :loading="false" :can-manage-team-agents="true" :submitting="false" :retryable="false" :error-message="null" :template-error-message="null" @close="noop" @submit="noop" @retry-templates="noop" />
    </Variant>
    <Variant title="Model · unhealthy connection">
      <div class="m5-story"><ModelConnectionDetail :resource="connectionResource" :can-manage="true" :command="modelCommand" @close="noop" @refresh="noop" @verify="noop" @rotate="noop" @suspend="noop" @revoke="noop" /></div>
    </Variant>
    <Variant title="Model · one-way credential input">
      <ModelCredentialDialog mode="create" :providers="[provider]" :connection="null" :team-id="fixtureIds.teamPlatform" :can-manage-team="true" :can-manage-organization="false" :submitting="false" :retryable="false" :error-message="null" @close="noop" @create="noop" @rotate="noop" />
    </Variant>
    <Variant title="Review · advisory and human gate"><div class="m5-story"><ReviewWorkbench v-bind="reviewProps" /></div></Variant>
    <Variant title="Review · invalidated"><div class="m5-story"><ReviewWorkbench v-bind="invalidatedReviewProps" /></div></Variant>
    <Variant title="Action · current bundle" :setup-app="setupDelivery()"><div class="m5-story"><ActionDeliveryWorkbench v-bind="coordinates" objective="完成受控 GitHub 交付" :review="approvedReview" :online="true" :can-confirm="true" /></div></Variant>
    <Variant title="Action · offline read-only" :setup-app="setupDelivery(true)"><div class="m5-story"><ActionDeliveryWorkbench v-bind="coordinates" objective="完成受控 GitHub 交付" :review="approvedReview" :online="false" :can-confirm="true" /></div></Variant>
  </Story>
</template>

<style scoped>
.m5-story { min-height: 520px; padding: 22px; background: var(--cs-canvas); font-family: var(--cs-font-sans); }
</style>
