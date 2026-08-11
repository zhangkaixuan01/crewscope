import { CrewScopeApiError } from '../../api/client'
import { conversationIds } from '../../test/conversationFixtures'
import { fixtureIds } from '../../test/scopeFixtures'
import { fixtureConfirmationPreview, fixtureTaskIntent } from '../../test/taskIntentFixtures'
import type { TaskIntentGateway } from './taskIntentGateway'
import { createTaskIntentStore } from './taskIntentStore'

const scope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform, conversationId: conversationIds.provider }

describe('TaskIntentStore', () => {
  it('previews the exact current proposal before confirming and refreshes server facts', async () => {
    const gateway = new FixtureTaskIntentGateway()
    const store = createTaskIntentStore(gateway)
    await store.synchronize(scope, gateway.intent.id)

    expect(await store.confirm()).toBe(true)

    expect(gateway.calls).toEqual(['get', 'preview', 'confirm', 'get'])
    expect(store.state.intent?.status).toBe('CONFIRMED')
    expect(store.state.commandPending).toBeNull()
  })

  it('refreshes a 412 conflict and requires the user to review the new proposal', async () => {
    const gateway = new FixtureTaskIntentGateway()
    gateway.reviseFailure = conflict()
    const store = createTaskIntentStore(gateway)
    await store.synchronize(scope, gateway.intent.id)

    expect(await store.revise(revision(gateway.intent))).toBe(false)

    expect(gateway.calls).toEqual(['get', 'revise', 'get'])
    expect(store.state.versionConflict).toBe(true)
    expect(store.state.commandErrorMessage).toContain('已刷新')
  })

  it('rejects invalid reasons locally and keeps 403 for route authorization', async () => {
    const gateway = new FixtureTaskIntentGateway()
    const store = createTaskIntentStore(gateway)
    await store.synchronize(scope, gateway.intent.id)
    expect(await store.reject('')).toBe(false)
    gateway.rejectFailure = forbidden()

    expect(await store.reject('不再需要')).toBe(false)
    expect(store.state.commandErrorStatus).toBe(403)
  })
})

class FixtureTaskIntentGateway implements TaskIntentGateway {
  intent = fixtureTaskIntent()
  calls: string[] = []
  reviseFailure: unknown = null
  rejectFailure: unknown = null
  async get() { this.calls.push('get'); return { value: structuredClone(this.intent), etag: `"${this.intent.version}"` } }
  async revise() { this.calls.push('revise'); if (this.reviseFailure) throw this.reviseFailure; this.intent = { ...this.intent, version: 4, proposalRevision: 2 } }
  async previewConfirmation() { this.calls.push('preview'); return { value: fixtureConfirmationPreview(this.intent), etag: `"${this.intent.version}"` } }
  async confirm() { this.calls.push('confirm'); this.intent = { ...this.intent, status: 'CONFIRMED', version: this.intent.version + 1 } }
  async reject() { this.calls.push('reject'); if (this.rejectFailure) throw this.rejectFailure; this.intent = { ...this.intent, status: 'REJECTED', version: this.intent.version + 1 } }
}

function revision(intent: ReturnType<typeof fixtureTaskIntent>) {
  return { schemaVersion: '1' as const, objective: intent.proposal.objective, acceptanceCriteria: intent.proposal.acceptanceCriteria, workProjectId: intent.proposal.workProjectId, ownerMemberId: intent.proposal.owner.teamMemberId!, executorPrincipalId: null, gateReviewerMemberId: null }
}

function conflict() { return error(412, 'version_conflict', '版本已变化') }
function forbidden() { return error(403, 'forbidden', '无权操作') }
function error(status: number, code: string, message: string) {
  return new CrewScopeApiError(status, { code, message, correlationId: 'corr', retryable: false, currentVersion: null, details: {} })
}
