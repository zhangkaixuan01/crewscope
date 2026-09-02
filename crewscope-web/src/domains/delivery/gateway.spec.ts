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

  it('creates a Team GitHub Connection with one-shot credentials and idempotency metadata', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(commandReceipt(), 202))
    const gateway = new HttpDeliveryGateway(new CrewScopeApiClient('/api/v1', fetcher))

    await gateway.createConnection(scope, {
      authenticationType: 'APP_INSTALLATION',
      teamId: scope.teamId,
      credentialSubjectType: 'TEAM',
      externalAccountId: '123456',
      repositoryAllowlist: ['crewscope/crewscope-java'],
      oneShotCredential: 'one-shot-token',
      expiresAt: null,
    }, 'github-create-key')

    const call = fetcher.mock.calls[0]!
    expect(call[0]).toContain(`/organizations/${scope.organizationId}/github-connections`)
    expect(new Headers(call[1]?.headers).get('Idempotency-Key')).toBe('github-create-key')
    expect(JSON.parse(String(call[1]?.body))).toEqual(expect.objectContaining({
      authenticationType: 'APP_INSTALLATION', credentialSubjectType: 'TEAM',
      teamId: scope.teamId, externalAccountId: '123456', accessToken: 'one-shot-token',
    }))
  })

  it('verifies a Connection with its persisted version', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({ ...githubConnection(), verifiedAt: '2026-09-01T12:00:00Z' }))
    const gateway = new HttpDeliveryGateway(new CrewScopeApiClient('/api/v1', fetcher))

    await gateway.verifyConnection(scope, githubConnection({ version: 4 }),)

    const call = fetcher.mock.calls[0]!
    expect(call[0]).toContain(`/github-connections/${deliveryIds.connection}/verify`)
    expect(new Headers(call[1]?.headers).get('If-Match')).toBe('"4"')
  })

  it('revokes a Connection as an idempotent logical delete', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(commandReceipt(), 202))
    const gateway = new HttpDeliveryGateway(new CrewScopeApiClient('/api/v1', fetcher))

    await gateway.revokeConnection(scope, githubConnection({ version: 5 }), 'OWNER_REQUESTED', 'github-revoke-key')

    const call = fetcher.mock.calls[0]!
    expect(call[0]).toContain(`/github-connections/${deliveryIds.connection}/revoke`)
    expect(new Headers(call[1]?.headers).get('If-Match')).toBe('"5"')
    expect(new Headers(call[1]?.headers).get('Idempotency-Key')).toBe('github-revoke-key')
    expect(call[1]?.body).toBe(JSON.stringify({ reason: 'OWNER_REQUESTED' }))
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

  it('uses idempotent scoped commands to cancel and retry repository imports', async () => {
    const imported = {
      id: crypto.randomUUID(), organizationId: scope.organizationId, teamId: scope.teamId,
      projectId: crypto.randomUUID(), connectionId: deliveryIds.connection, connectionVersion: 3,
      externalRepositoryId: '101', repositoryFullName: 'crewscope/crewscope-java',
      repositoryKey: 'crewscope-java', defaultBranch: 'main', status: 'CANCELLED',
      progressPercent: 10, attempt: 1, failureCode: 'CANCELLED_BY_USER', bindingId: null,
      createdAt: '2026-09-03T00:00:00Z', updatedAt: '2026-09-03T00:01:00Z',
    } as const
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse(imported))
      .mockResolvedValueOnce(jsonResponse({ ...imported, status: 'REQUESTED' }))
    const gateway = new HttpDeliveryGateway(new CrewScopeApiClient('/api/v1', fetcher))

    await gateway.cancelRepositoryImport(scope, imported.projectId, imported.id, 'cancel-import')
    await gateway.retryRepositoryImport(scope, imported.projectId, imported.id, 'retry-import')

    expect(fetcher.mock.calls[0]?.[0]).toContain(`/github-imports/${imported.id}/cancel`)
    expect(new Headers(fetcher.mock.calls[0]?.[1]?.headers).get('Idempotency-Key')).toBe('cancel-import')
    expect(fetcher.mock.calls[1]?.[0]).toContain(`/github-imports/${imported.id}/retry`)
    expect(new Headers(fetcher.mock.calls[1]?.[1]?.headers).get('Idempotency-Key')).toBe('retry-import')
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
