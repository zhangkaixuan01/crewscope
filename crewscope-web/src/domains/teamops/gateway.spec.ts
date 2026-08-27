import { CrewScopeApiClient } from '../../api/client'
import { HttpTeamOpsGateway } from './gateway'

const scope = { organizationId: 'org-1', teamId: 'team-1' }

describe('HttpTeamOpsGateway', () => {
  it('reconstructs Activity and Operations DTOs through public allowlists', async () => {
    const fetcher = vi.fn(async (input: RequestInfo | URL) => String(input).includes('/diagnostics')
      ? json({
          summary: healthPayload({ internalMetricKey: 'private' }),
          projections: [projectionPayload({ internalCheckpoint: 'private' })],
          recoveryCandidates: [recoveryPayload({ rawPayload: 'private' })],
          databaseDsn: 'private',
        })
      : json({
          items: [activityPayload({ credentialSnapshot: 'private' })],
          hasMore: false,
          nextCursor: null,
          snapshotCursor: 'snapshot-1',
          projectionGeneration: 7,
        }))
    const gateway = gatewayWith(fetcher)

    const activity = await gateway.teamActivitySnapshot(scope, {})
    const diagnostics = await gateway.administratorDiagnostics(scope.organizationId)

    expect(activity.snapshotCursor).toBe('snapshot-1')
    expect(activity.items[0]?.eventId).toBe('event-1')
    expect(diagnostics.projections[0]?.projectionName).toBe('team-activity')
    expect(JSON.stringify({ activity, diagnostics })).not.toContain('private')
    expect(diagnostics).not.toHaveProperty('databaseDsn')
  })

  it('serializes a Recovery target through the request closed union', async () => {
    const fetcher = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => json({
      commandId: uuid(11), action: 'REPLAY_OUTBOX_DEAD_LETTER',
      targetReferenceHash: 'a'.repeat(64), status: 'SCHEDULED', acceptedAt: '2026-08-27T01:00:00Z',
    }, 202))
    const gateway = gatewayWith(fetcher)

    await gateway.recover(scope.organizationId, recoveryPayload(), 'REPLAY safe-hash', 'recovery-key')

    const body = JSON.parse(String(fetcher.mock.calls[0]?.[1]?.body))
    expect(body.target).toEqual({
      type: 'OUTBOX_DEAD_LETTER', outboxEventId: uuid(2), domainEventId: uuid(3), expectedVersion: 1,
    })
    expect(body.target).not.toHaveProperty('action')
    expect(body.target).not.toHaveProperty('referenceHash')
    expect(body.target).not.toHaveProperty('confirmation')
    expect(new Headers(fetcher.mock.calls[0]?.[1]?.headers).get('Idempotency-Key')).toBe('recovery-key')
  })

  it.each([
    healthPayload({ health: 'UNKNOWN' }),
    healthPayload({ components: healthComponents().slice(0, 4) }),
    projectionPayload({ shadowGeneration: 2, shadowStatus: null }),
  ])('fails closed for an invalid Operations DTO', async payload => {
    const gateway = gatewayWith(vi.fn(async () => json(
      'health' in payload && !('projectionName' in payload)
        ? payload
        : { summary: healthPayload(), projections: [payload], recoveryCandidates: [] },
    )))
    if ('projectionName' in payload) await expect(gateway.administratorDiagnostics(scope.organizationId)).rejects.toThrow()
    else await expect(gateway.operationsHealth(scope)).rejects.toThrow()
  })

  it('fails closed when Inbox header, body ETag and disposition version disagree', async () => {
    const gateway = gatewayWith(vi.fn(async () => json(inboxPayload({ etag: '"3"' }), 200, { ETag: '"4"' })))

    await expect(gateway.inboxDetail(scope, 'inbox-1')).rejects.toThrow('strong ETag')
  })

  it('fails closed when Inbox returns an unknown closed-set value', async () => {
    const gateway = gatewayWith(vi.fn(async () => json({
      items: [inboxPayload({ itemType: 'UNRECOGNIZED' })],
      nextCursor: null,
    })))

    await expect(gateway.inbox(scope, {})).rejects.toThrow('response enum is invalid')
  })

  it.each([
    'https://attacker.example/work',
    '//attacker.example/work',
    '/admin',
    '/work#credential',
  ])('rejects an unapproved Inbox target: %s', async href => {
    const gateway = gatewayWith(vi.fn(async () => json({ kind: 'WORK_ITEM', href })))

    await expect(gateway.inboxTarget(scope, 'inbox-1')).rejects.toThrow(/internal route/)
  })

  it('admits Lark credentials only to the one command body and whitelists its receipt', async () => {
    const fetcher = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => json({
      commandId: 'command-1', domainEventId: 'event-1', committedVersion: 1,
      correlationId: 'correlation-1', credentialHandle: 'private',
    }, 202))
    const gateway = gatewayWith(fetcher)

    const receipt = await gateway.createLarkConnection(scope, 0, {
      tenantKey: 'tenant-1', appId: 'app-1', appSecret: 'one-way-secret', expiresAt: null,
    }, 'idempotency-1')

    const request = fetcher.mock.calls[0]?.[1]
    const headers = new Headers(request?.headers)
    expect(headers.get('If-Match')).toBe('"0"')
    expect(headers.get('Idempotency-Key')).toBe('idempotency-1')
    expect(String(request?.body)).toContain('one-way-secret')
    expect(JSON.stringify(receipt)).not.toContain('private')
  })

  it('uses the frozen Audit route and export media type', async () => {
    const fetcher = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => init?.method === 'POST'
      ? json({ generatedAt: '2026-08-27T01:00:00Z', rowCount: 0, maximumRows: 100, events: [] })
      : json({ items: [], nextCursor: null }))
    const gateway = gatewayWith(fetcher)

    await gateway.audit(scope, {})
    await gateway.exportAudit(scope, {}, 100)

    expect(String(fetcher.mock.calls[0]?.[0])).toContain('/audit-events?')
    expect(String(fetcher.mock.calls[1]?.[0])).toContain('/audit-events/export')
    expect(new Headers(fetcher.mock.calls[1]?.[1]?.headers).get('Accept')).toBe('application/vnd.crewscope.audit-export+json')
  })

  it('reconstructs Audit and Correlation DTOs through closed vocabularies and safe links', async () => {
    const fetcher = vi.fn(async (input: RequestInfo | URL) => String(input).includes('/correlations/')
      ? json(correlationPayload())
      : json({ items: [auditPayload({ authorizationContext: 'private' })], nextCursor: null }))
    const gateway = gatewayWith(fetcher)

    const audit = await gateway.audit(scope, {})
    const correlation = await gateway.correlation(scope, uuid(8))

    expect(audit.items[0]?.category).toBe('SECURITY')
    expect(correlation.objects[0]?.href).toContain('/activity?')
    expect(JSON.stringify({ audit, correlation })).not.toContain('private')
  })

  it.each([
    auditPayload({ category: 'UNRECOGNIZED' }),
    auditPayload({ summary: { authorizationToken: 'private' } }),
    auditPayload({ provider: { providerBindingId: uuid(5), connectionId: uuid(6), externalOperationHash: 'not-a-hash' } }),
  ])('fails closed for an invalid public Audit DTO', async payload => {
    const gateway = gatewayWith(vi.fn(async () => json({ items: [payload], nextCursor: null })))

    await expect(gateway.audit(scope, {})).rejects.toThrow()
  })

  it.each(['https://attacker.example/activity', '//attacker.example/activity', '/work', '/activity#payload'])
    ('rejects an unsafe Correlation object target: %s', async href => {
      const gateway = gatewayWith(vi.fn(async () => json(correlationPayload(href))))

      await expect(gateway.correlation(scope, uuid(8))).rejects.toThrow(/Correlation target/)
    })

  it('rejects an Audit export whose declared bounds disagree with its rows', async () => {
    const gateway = gatewayWith(vi.fn(async () => json({
      generatedAt: '2026-08-27T01:00:00Z', rowCount: 2, maximumRows: 1, events: [auditPayload()],
    })))

    await expect(gateway.exportAudit(scope, {}, 1)).rejects.toThrow(/bounds/)
  })

  it('normalizes single-value domain records without admitting their unknown siblings', async () => {
    const fetcher = vi.fn(async () => json([{
      connectionId: { value: 'connection-1', secret: 'private' },
      teamId: { value: scope.teamId }, providerBindingId: { value: 'binding-1' }, providerBindingVersion: 6,
      maskedAppId: 'cli***123', status: 'ACTIVE', credentialStatus: 'ACTIVE', expiresAt: null,
      createdAt: { value: '2026-08-27T01:00:00Z' }, updatedAt: { value: '2026-08-27T02:00:00Z' },
      version: 4, credentialId: 'private',
    }]))
    const gateway = gatewayWith(fetcher)

    const connections = await gateway.larkConnections(scope)

    expect(connections[0]?.connectionId).toBe('connection-1')
    expect(connections[0]?.createdAt).toBe('2026-08-27T01:00:00Z')
    expect(JSON.stringify(connections)).not.toContain('private')
  })

  it.each([
    { providerBindingId: 'binding-1', providerBindingVersion: null },
    { providerBindingId: null, providerBindingVersion: 6 },
  ])('rejects an incomplete Lark Provider Binding coordinate: $providerBindingId/$providerBindingVersion', async coordinate => {
    const gateway = gatewayWith(vi.fn(async () => json([{
      connectionId: 'connection-1', teamId: scope.teamId, ...coordinate,
      maskedAppId: '****1234', status: 'ACTIVE', credentialStatus: 'ACTIVE', expiresAt: null,
      createdAt: '2026-08-27T01:00:00Z', updatedAt: '2026-08-27T02:00:00Z', version: 4,
    }])))

    await expect(gateway.larkConnections(scope)).rejects.toThrow(/present together/)
  })

  it.each([
    { path: '/lark/connections', body: [{ connectionId: 'c', teamId: 't', providerBindingId: null, providerBindingVersion: null, maskedAppId: '****', status: 'UNKNOWN', credentialStatus: 'ACTIVE', expiresAt: null, createdAt: '2026-08-27T01:00:00Z', updatedAt: '2026-08-27T01:00:00Z', version: 0 }] },
    { path: '/lark/notification-templates', body: [{ ref: { templateId: 't', version: 1 }, serverTemplateKey: 'review', status: 'DRAFT', variables: [] }] },
    { path: '/lark/notification-deliveries', body: { items: [{ organizationId: 'o', teamId: 't', deliveryId: 'd', recipientMemberId: 'm', itemType: 'REVIEW', template: { templateId: 'x', version: 1 }, providerBindingId: 'b', status: 'LOST', attemptCount: 0, failureCode: null, evidenceCode: null, redeliveryOf: null, createdAt: '2026-08-27T01:00:00Z', updatedAt: '2026-08-27T01:00:00Z', version: 0 }], nextCursor: null } },
  ])('fails closed for unknown Lark and Notification vocabularies at $path', async ({ path, body }) => {
    const gateway = gatewayWith(vi.fn(async () => json(body)))
    if (path.endsWith('/connections')) await expect(gateway.larkConnections(scope)).rejects.toThrow('response enum')
    else if (path.endsWith('/notification-templates')) await expect(gateway.notificationTemplates(scope)).rejects.toThrow('response enum')
    else await expect(gateway.notificationDeliveries(scope, {})).rejects.toThrow('response enum')
  })
})

function gatewayWith(fetcher: ReturnType<typeof vi.fn>): HttpTeamOpsGateway {
  return new HttpTeamOpsGateway(new CrewScopeApiClient('/api/v1', fetcher as unknown as typeof fetch))
}

function activityPayload(extra: Record<string, unknown> = {}) {
  return {
    eventId: 'event-1', domainEventId: 'domain-event-1', teamSequence: 3,
    eventType: 'TASK_STARTED', category: 'EXECUTION', visibility: 'TEAM',
    subject: { type: 'TASK', id: 'task-1', internalAggregate: 'private' },
    actor: { type: 'MEMBER', principalId: 'principal-1', email: 'private' },
    references: [{ type: 'WORK_ITEM', id: 'work-item-1', path: 'private' }],
    occurredAt: '2026-08-27T01:00:00Z',
    payload: { schemaName: 'task-summary', schemaVersion: 1, values: { status: 'RUNNING' }, raw: 'private' },
    ...extra,
  }
}

function inboxPayload(extra: Record<string, unknown> = {}) {
  return {
    inboxItemId: 'inbox-1', itemType: 'REVIEW', priority: 'HIGH', deadline: null,
    openedAt: '2026-08-27T01:00:00Z', sourceStatus: 'OPEN', closeReason: null, closedAt: null,
    dispositionStatus: 'UNREAD', dispositionVersion: 4, etag: '"4"',
    source: { type: 'REVIEW_REQUEST', id: 'review-1', revision: 2 },
    ...extra,
  }
}

function auditPayload(extra: Record<string, unknown> = {}) {
  return {
    eventId: uuid(1), eventType: 'TEAM_ACCESS_DENIED', sourceSchemaVersion: 1,
    category: 'SECURITY', outcome: 'DENIED', retentionLevel: 'EXTENDED', occurredAt: '2026-08-27T01:00:00Z',
    identity: { initiatorId: uuid(2), actorType: 'USER', actorId: uuid(2), agentPrincipalId: null },
    subject: { type: 'TEAM', id: uuid(3) },
    provider: { providerBindingId: uuid(5), connectionId: uuid(6), externalOperationHash: 'a'.repeat(64) },
    correlation: { correlationId: uuid(8), causationId: null, domainEventId: uuid(9) },
    summary: { reasonCode: 'permission_denied' },
    ...extra,
  }
}

function correlationPayload(href = `/activity?team=${scope.teamId}&correlation=${uuid(8)}&objectType=WORK_ITEM&objectId=${uuid(3)}`) {
  return {
    correlationId: uuid(8),
    events: [{
      eventId: uuid(1), source: 'AUDIT', eventType: 'TEAM_ACCESS_DENIED', actorType: 'USER',
      actorId: uuid(2), outcome: 'DENIED', occurredAt: '2026-08-27T01:00:00Z',
      references: [{ type: 'WORK_ITEM', id: uuid(3), href }],
    }],
    objects: [{ type: 'WORK_ITEM', id: uuid(3), href, relatedEventIds: [uuid(1)] }],
    hasMore: false, nextCursor: null,
  }
}

function uuid(index: number): string { return `00000000-0000-4000-8000-${String(index).padStart(12, '0')}` }

function healthPayload(extra: Record<string, unknown> = {}) {
  return {
    observedAt: '2026-08-27T01:00:00Z', health: 'DEGRADED',
    components: healthComponents(),
    ...extra,
  }
}

function healthComponents() {
  return ['PROJECTION', 'OUTBOX', 'DEAD_LETTER', 'CURSOR', 'NOTIFICATION'].map((component, index) => ({
    component, health: index === 0 ? 'DEGRADED' : 'HEALTHY', backlog: index === 0 ? 1 : 0,
    inFlight: 0, failures: 0, affected: index === 0 ? 1 : 0, oldestOutstandingAgeSeconds: index === 0 ? 3 : 0, stale: false,
  }))
}

function projectionPayload(extra: Record<string, unknown> = {}) {
  return {
    projectionName: 'team-activity', definitionVersion: 1, activeGeneration: 1,
    pointerVersion: 2, activeGenerationVersion: 3, shadowGeneration: null,
    shadowStatus: null, shadowGenerationVersion: null, rebuildJobId: null, rebuildJobVersion: null,
    lagSeconds: 2, gapCount: 0, deadLetterCount: 0, latestFailureCode: null,
    startConfirmation: 'START team-activity', validateConfirmation: null, switchConfirmation: null,
    cancelConfirmation: null, failConfirmation: null, ...extra,
  }
}

function recoveryPayload(extra: Record<string, unknown> = {}) {
  return {
    type: 'OUTBOX_DEAD_LETTER', action: 'REPLAY_OUTBOX_DEAD_LETTER', outboxEventId: uuid(2),
    domainEventId: uuid(3), expectedVersion: 1, referenceHash: 'a'.repeat(64),
    confirmation: 'REPLAY safe-hash', ...extra,
  } as const
}

function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } })
}
