import { CrewScopeApiClient } from '../../api/client'
import { deliveryIds, actionBundle, githubConnection } from '../../test/deliveryFixtures'
import { fixtureIds } from '../../test/scopeFixtures'
import { taskIds } from '../../test/taskFixtures'
import { HttpDeliveryGateway, safeExternalHref } from './gateway'

describe('HttpDeliveryGateway', () => {
  const scope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform }
  const coordinates = { taskId: taskIds.first, executionId: taskIds.execution }

  it('whitelists ActionBundle and rejects internal execution or provider fields', async () => {
    const raw = {
      ...actionBundle({ taskId: coordinates.taskId, taskExecutionId: coordinates.executionId }),
      credentialId: 'secret', connectionId: 'internal',
      actions: actionBundle().actions.map(item => ({
        ...item, idempotencyKey: 'secret', dispatch: item.kind === 'PUSH_BRANCH' ? {
          id: deliveryIds.pushDispatch, version: 3, status: 'UNKNOWN', claimAttempts: 1,
          reconciliationAttempts: 2, nextAttemptAt: '2026-08-25T08:10:00Z',
          cancellationReason: null, compensationDisposition: 'NONE', workerId: 'private', fencingToken: 99,
        } : null,
      })),
    }
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(raw, 200, { ETag: '"0"' }))
    const gateway = new HttpDeliveryGateway(new CrewScopeApiClient('/api/v1', fetcher))

    const result = await gateway.getBundle(scope, coordinates, deliveryIds.bundle)

    expect(result.etag).toBe('"0"')
    expect(result.value.actions[0]?.dispatch?.status).toBe('UNKNOWN')
    expect(JSON.stringify(result)).not.toMatch(/credentialId|connectionId|idempotencyKey|workerId|fencingToken|secret|private/)
  })

  it('sends exact digest, ETag and original command inputs without browser-derived authority', async () => {
    const receipt = commandReceipt()
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(receipt, 202))
    const gateway = new HttpDeliveryGateway(new CrewScopeApiClient('/api/v1', fetcher))
    const bundle = { value: actionBundle({ taskId: coordinates.taskId, taskExecutionId: coordinates.executionId, version: 4 }), etag: '"4"' }

    await gateway.confirm(scope, coordinates, bundle, 'confirm-key')

    const call = fetcher.mock.calls[0]!
    expect(call[0]).toContain(`/bundles/${deliveryIds.bundle}/confirmations`)
    expect(new Headers(call[1]?.headers).get('If-Match')).toBe('"4"')
    expect(new Headers(call[1]?.headers).get('Idempotency-Key')).toBe('confirm-key')
    expect(call[1]?.body).toBe(JSON.stringify({ bundleDigest: 'a'.repeat(64) }))
  })

  it('uses stable Catalog IDs and pinned Connection version for Remote Preflight', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({
      connectionVersion: 3, externalRepositoryId: '101', fullName: 'crewscope/crewscope-java',
      defaultBranch: 'main', permissionsHash: '1'.repeat(64), remoteUrl: 'https://token@github.com/private',
    }))
    const gateway = new HttpDeliveryGateway(new CrewScopeApiClient('/api/v1', fetcher))

    const result = await gateway.preflight(scope, githubConnection(), deliveryIds.binding, '101')

    expect(fetcher.mock.calls[0]?.[0]).toContain(`/repositories/101/preflight?bindingId=${deliveryIds.binding}`)
    expect(new Headers(fetcher.mock.calls[0]?.[1]?.headers).get('If-Match')).toBe('"3"')
    expect(JSON.stringify(result)).not.toContain('remoteUrl')
  })

  it('admits only credential-free HTTPS external links', () => {
    expect(safeExternalHref('https://github.com/crewscope/crewscope-java/pull/42')).toBe('https://github.com/crewscope/crewscope-java/pull/42')
    expect(safeExternalHref('http://github.com/crewscope/crewscope-java/pull/42')).toBeNull()
    expect(safeExternalHref('javascript:alert(1)')).toBeNull()
    expect(safeExternalHref('https://token@github.com/private')).toBeNull()
  })
})

function commandReceipt() {
  return { commandId: crypto.randomUUID(), domainEventId: crypto.randomUUID(), committedVersion: 1, correlationId: crypto.randomUUID() }
}

function jsonResponse(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } })
}
