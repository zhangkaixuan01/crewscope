// @vitest-environment node
import { CrewScopeApiClient } from '../../api/client'
import { HttpOrganizationGateway } from './gateway'

const organizationId = '00000000-0000-0000-0000-000000000001'

describe('HttpOrganizationGateway', () => {
  it('maps the organization profile through the allowlist (organizationId, not id)', async () => {
    const harness = gatewayWith(vi.fn(async () => json({
      id: 'must-be-ignored',
      organizationId,
      name: 'CrewScope Organization',
      status: 'ACTIVE',
      version: 3,
      billingEmail: 'private@example.com',
    })))

    const profile = await harness.gateway.profile(organizationId)

    expect(profile).toEqual({ organizationId, name: 'CrewScope Organization', status: 'ACTIVE', version: 3 })
    expect(harness.lastPath()).toBe(`/organizations/${organizationId}`)
  })

  it('maps settings with the null forever-retention preserved', async () => {
    const harness = gatewayWith(vi.fn(async () => json({ auditEventRetentionDays: null, version: 0 })))

    const settings = await harness.gateway.settings(organizationId)

    expect(settings).toEqual({ auditEventRetentionDays: null, version: 0 })
    expect(harness.lastPath()).toBe(`/organizations/${organizationId}/settings`)
  })

  it('rejects unknown enum values instead of rendering them', async () => {
    const harness = gatewayWith(vi.fn(async () => json({ organizationId, name: 'org', status: 'SUSPENDED', version: 1 })))

    await expect(harness.gateway.profile(organizationId)).rejects.toThrow(TypeError)
  })

  it('puts the idempotency key and the quoted If-Match version on the settings write', async () => {
    const harness = gatewayWith(vi.fn(async () => receipt(false)))

    const mapped = await harness.gateway.updateSettings(organizationId, { auditEventRetentionDays: 90 }, 0, 'attempt-1')

    const request = harness.lastRequest()
    expect(request?.method).toBe('PUT')
    expect(new URL(request!.url, 'http://crewscope.local').pathname).toBe(`/api/v1/organizations/${organizationId}/settings`)
    expect(JSON.parse(String(request?.body))).toEqual({ auditEventRetentionDays: 90 })
    expect(new Headers(request?.headers).get('Idempotency-Key')).toBe('attempt-1')
    expect(new Headers(request?.headers).get('If-Match')).toBe('"0"')
    expect(mapped).toMatchObject({ commandId: 'command-1', committedVersion: 1, replayed: false })
  })

  // The command gateway keys in-flight deduplication on the response, and the page shows a
  // replay notice from this flag — the header is the only signal distinguishing a replay.
  it('marks a replayed receipt from the Idempotency-Replayed header', async () => {
    const harness = gatewayWith(vi.fn(async () => receipt(true)))

    const mapped = await harness.gateway.updateSettings(organizationId, { auditEventRetentionDays: null }, 2, 'attempt-2')

    expect(mapped.replayed).toBe(true)
  })

  it('maps the bare role-grant array through the allowlist', async () => {
    const harness = gatewayWith(vi.fn(async () => json([roleGrant('g-1', 'ACTIVE'), roleGrant('g-2', 'REVOKED')])))

    const grants = await harness.gateway.roleGrants(organizationId)

    expect(grants).toHaveLength(2)
    expect(grants[0]).toEqual({
      id: 'g-1', organizationId, principalId: 'principal-9', role: 'ORG_ADMIN', status: 'ACTIVE',
      grantedByPrincipalId: 'principal-1', grantedAt: '2026-10-13T08:00:00Z',
      revokedAt: null, revokedByPrincipalId: null, reason: null, version: 2,
    })
    expect(grants[1]?.revokedByPrincipalId).toBe('principal-1')
    expect(harness.lastPath()).toBe(`/organizations/${organizationId}/role-grants`)
  })

  it('rejects a non-array role-grant payload instead of coercing it', async () => {
    const harness = gatewayWith(vi.fn(async () => json({ items: [] })))

    await expect(harness.gateway.roleGrants(organizationId)).rejects.toThrow(TypeError)
  })

  it('posts the grant with the idempotency key and a null reason', async () => {
    const harness = gatewayWith(vi.fn(async () => receipt(false)))

    await harness.gateway.grantRole(organizationId, { principalId: 'principal-9', role: 'ORG_AUDITOR' }, 'grant-1')

    const request = harness.lastRequest()
    expect(request?.method).toBe('POST')
    expect(new URL(request!.url, 'http://crewscope.local').pathname).toBe(`/api/v1/organizations/${organizationId}/role-grants`)
    expect(JSON.parse(String(request?.body))).toEqual({ principalId: 'principal-9', role: 'ORG_AUDITOR', reason: null })
    expect(new Headers(request?.headers).get('Idempotency-Key')).toBe('grant-1')
  })

  it('revokes against the grant path with If-Match quoting the grant version', async () => {
    const harness = gatewayWith(vi.fn(async () => receipt(false)))

    await harness.gateway.revokeRoleGrant(organizationId, 'g-1', { reason: '离任' }, 2, 'revoke-1')

    const request = harness.lastRequest()
    expect(request?.method).toBe('POST')
    expect(new URL(request!.url, 'http://crewscope.local').pathname).toBe(`/api/v1/organizations/${organizationId}/role-grants/g-1/revoke`)
    expect(JSON.parse(String(request?.body))).toEqual({ reason: '离任' })
    expect(new Headers(request?.headers).get('If-Match')).toBe('"2"')
    expect(new Headers(request?.headers).get('Idempotency-Key')).toBe('revoke-1')
  })

  it('answers a first create-export with the mapped job and no receipt', async () => {
    const harness = gatewayWith(vi.fn(async () => json(exportJobBody('PENDING'))))

    const result = await harness.gateway.createExport(organizationId, { includeVectors: false }, 'export-1')

    expect(result).toMatchObject({ replayed: false, receipt: null })
    expect(result.job).toMatchObject({ id: 'job-e1', status: 'PENDING', items: [{ resourceFamily: 'TASK', state: 'PENDING' }] })
    const request = harness.lastRequest()
    expect(request?.method).toBe('POST')
    expect(new URL(request!.url, 'http://crewscope.local').pathname).toBe(`/api/v1/organizations/${organizationId}/data-exports`)
    expect(JSON.parse(String(request?.body))).toEqual({ includeVectors: false })
    expect(new Headers(request?.headers).get('Idempotency-Key')).toBe('export-1')
  })

  it('folds a create-export replay into the receipt branch of the CreationResult', async () => {
    const harness = gatewayWith(vi.fn(async () => receipt(true)))

    const result = await harness.gateway.createExport(organizationId, { includeVectors: true }, 'export-2')

    expect(result.job).toBeNull()
    expect(result.replayed).toBe(true)
    expect(result.receipt).toMatchObject({ commandId: 'command-1', replayed: true })
    expect(JSON.parse(String(harness.lastRequest()?.body))).toEqual({ includeVectors: true })
  })

  it('maps the deletion job without ever disclosing confirmationTokenHash', async () => {
    const harness = gatewayWith(vi.fn(async () => json(deletionJobBody('PENDING_CONFIRMATION'))))

    const job = await harness.gateway.deletionJob(organizationId, 'job-d1')

    expect(job).toEqual({
      id: 'job-d1', organizationId, accountId: 'account-1', targetPrincipalId: 'p-2',
      reason: '离职账号清理，含满足最短长度要求的完整说明。', status: 'PENDING_CONFIRMATION',
      reservationCheck: expect.any(String), backupRetentionStatement: expect.stringContaining('备份'),
      version: 1, createdAt: '2026-10-13T08:00:00Z', updatedAt: '2026-10-13T08:00:00Z',
      items: [{ id: 's-1', stepKey: 'TOMBSTONE_IDENTITY', targetRef: 'ACCOUNT', expectedCount: 1, state: 'PENDING', resultCount: null, errorCode: null, processedAt: null, version: 2 }],
    })
    expect(JSON.stringify(job)).not.toContain('confirmationTokenHash')
  })

  it('prechecks by account query parameter and maps the openReservations null', async () => {
    const harness = gatewayWith(vi.fn(async () => json({
      accountId: 'account-1', username: 'e2e-member', displayName: '林悦',
      inFlightTaskExecutions: 0, activeResponsibilities: 3, openReservations: null,
    })))

    const precheck = await harness.gateway.deletionPrecheck(organizationId, 'account-1')

    expect(precheck).toEqual({
      accountId: 'account-1', username: 'e2e-member', displayName: '林悦',
      inFlightTaskExecutions: 0, activeResponsibilities: 3, openReservations: null,
    })
    expect(new URL(harness.lastRequest()!.url, 'http://crewscope.local').search)
      .toBe('?accountId=account-1')
  })

  it('confirms the deletion with the token body and the quoted job version', async () => {
    const harness = gatewayWith(vi.fn(async () => json(deletionJobBody('CONFIRMED'))))

    await harness.gateway.confirmDeletion(organizationId, 'job-d1', 'e2e-member', 3)

    const request = harness.lastRequest()
    expect(request?.method).toBe('POST')
    expect(new URL(request!.url, 'http://crewscope.local').pathname).toBe(`/api/v1/organizations/${organizationId}/account-deletions/job-d1/confirm`)
    expect(JSON.parse(String(request?.body))).toEqual({ confirmationToken: 'e2e-member' })
    expect(new Headers(request?.headers).get('If-Match')).toBe('"3"')
    expect(new Headers(request?.headers).get('Idempotency-Key')).toBeNull()
  })

  it('posts process and cancel without an idempotency key (the job machine is the idempotence)', async () => {
    const harness = gatewayWith(vi.fn(async (input: RequestInfo | URL) =>
      String(input).includes('account-deletions')
        ? json(deletionJobBody('CONFIRMED'))
        : json(exportJobBody('COMPLETED'))))

    await harness.gateway.processExport(organizationId, 'job-e1')
    expect(harness.lastPath()).toBe(`/organizations/${organizationId}/data-exports/job-e1/process`)
    expect(harness.lastRequest()?.method).toBe('POST')
    expect(new Headers(harness.lastRequest()?.headers).get('Idempotency-Key')).toBeNull()

    await harness.gateway.processDeletion(organizationId, 'job-d1')
    expect(harness.lastPath()).toBe(`/organizations/${organizationId}/account-deletions/job-d1/process`)

    await harness.gateway.cancelDeletion(organizationId, 'job-d1')
    expect(harness.lastPath()).toBe(`/organizations/${organizationId}/account-deletions/job-d1/cancel`)
  })

  it('downloads artifacts through the governance media type and maps the manifest summary', async () => {
    const harness = gatewayWith(vi.fn(async () => json({
      manifest: {
        generatedAt: '2026-10-13T10:00:00Z',
        families: [{ family: 'TASK', itemCount: 2, pinnedRowCount: 1500, checksum: 'a'.repeat(64), skipped: ['VECTOR_STORE_DISABLED'] }],
        vectors: { encoding: 'float32' },
      },
      artifacts: [{ id: 'a-1', itemId: 'i-1', artifactSequence: 0, byteSize: 2048, checksum: 'b'.repeat(64), rows: [{ id: 1 }] }],
    })))

    const artifacts = await harness.gateway.openExportArtifacts(organizationId, 'job-e1')

    expect(artifacts.manifest?.families[0]).toMatchObject({ family: 'TASK', itemCount: 2, pinnedRowCount: 1500, skipped: ['VECTOR_STORE_DISABLED'] })
    expect(artifacts.artifacts[0]).toMatchObject({ byteSize: 2048, rows: [{ id: 1 }] })
    expect(harness.lastPath()).toBe(`/organizations/${organizationId}/data-exports/job-e1/artifacts`)
    expect(new Headers(harness.lastRequest()?.headers).get('Accept')).toBe('application/vnd.crewscope.governance-export+json')
  })
})

function gatewayWith(fetcher: (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>) {
  const calls: { url: string, init?: RequestInit }[] = []
  const wrapped = vi.fn<typeof fetch>(async (input, init) => {
    calls.push({ url: typeof input === 'string' ? input : String(input), init })
    return fetcher(input, init)
  })
  const gateway = new HttpOrganizationGateway(new CrewScopeApiClient('/api/v1', wrapped as unknown as typeof fetch))
  return {
    gateway,
    lastPath: () => calls.length ? new URL(calls.at(-1)!.url, 'http://crewscope.local').pathname.replace('/api/v1', '') : '',
    lastRequest: () => ({ url: calls.at(-1)?.url ?? '', ...calls.at(-1)?.init }) as { url: string, method?: string, body?: unknown, headers?: HeadersInit } | undefined,
  }
}

function json(body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json', ...headers } })
}

function receipt(replayed: boolean): Response {
  return json({
    commandId: 'command-1',
    domainEventId: 'event-1',
    committedVersion: 1,
    correlationId: 'correlation-1',
    replayed: false,
  }, replayed ? { 'Idempotency-Replayed': 'true' } : {})
}

function roleGrant(id: string, status: 'ACTIVE' | 'REVOKED') {
  return {
    id,
    organizationId,
    principalId: 'principal-9',
    role: 'ORG_ADMIN',
    status,
    grantedByPrincipalId: 'principal-1',
    grantedAt: '2026-10-13T08:00:00Z',
    revokedAt: status === 'REVOKED' ? '2026-10-13T09:00:00Z' : null,
    revokedByPrincipalId: status === 'REVOKED' ? 'principal-1' : null,
    reason: null,
    version: 2,
  }
}

function exportJobBody(status: 'PENDING' | 'COMPLETED') {
  return {
    id: 'job-e1',
    organizationId,
    requestedByPrincipalId: 'p-1',
    status,
    includeVectors: false,
    manifest: null,
    version: 1,
    createdAt: '2026-10-13T08:00:00Z',
    updatedAt: '2026-10-13T08:00:00Z',
    items: [{
      id: 'i-1', resourceFamily: 'TASK', batchSequence: 0, rowCount: 10,
      state: 'PENDING', checksum: null, errorCode: null, processedAt: null, version: 2,
    }],
  }
}

function deletionJobBody(status: 'PENDING_CONFIRMATION' | 'CONFIRMED') {
  return {
    id: 'job-d1',
    organizationId,
    accountId: 'account-1',
    targetPrincipalId: 'p-2',
    reason: '离职账号清理，含满足最短长度要求的完整说明。',
    confirmationTokenHash: 'sha256:must-never-enter-the-dto',
    status,
    reservationCheck: '{"inFlightTaskExecutions":0,"activeResponsibilities":0,"openReservations":null,"checkedAt":"2026-10-13T08:00:00Z"}',
    backupRetentionStatement: '备份保留声明：每日快照 7 天、每周快照 4 周。',
    version: 1,
    createdAt: '2026-10-13T08:00:00Z',
    updatedAt: '2026-10-13T08:00:00Z',
    items: [{
      id: 's-1', stepKey: 'TOMBSTONE_IDENTITY', targetRef: 'ACCOUNT', expectedCount: 1,
      state: 'PENDING', resultCount: null, errorCode: null, processedAt: null, version: 2,
    }],
  }
}