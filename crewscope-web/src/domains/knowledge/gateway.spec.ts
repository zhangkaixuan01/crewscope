import { CrewScopeApiClient } from '../../api/client'
import { fixtureIds } from '../../test/scopeFixtures'
import { HttpKnowledgeGateway } from './gateway'

const scope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform }
const entryId = '00000000-0000-0000-0000-000000007101'
const executionId = '00000000-0000-0000-0000-000000007201'

describe('HttpKnowledgeGateway', () => {
  it('builds the keyset listing URL with filter, cursor and limit, whitelisting head fields', async () => {
    const fetcher = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => json({ items: [entryPayload({ internalDraftToken: 'private' })], nextAfter: 'deploy-runbook' }))
    const gateway = gatewayWith(fetcher)

    const page = await gateway.listEntries(scope, { status: 'PUBLISHED', category: 'RUNBOOK' }, 'api-guard', 25)

    expect(String(fetcher.mock.calls[0]?.[0])).toContain('status=PUBLISHED')
    expect(String(fetcher.mock.calls[0]?.[0])).toContain('category=RUNBOOK')
    expect(String(fetcher.mock.calls[0]?.[0])).toContain('after=api-guard')
    expect(String(fetcher.mock.calls[0]?.[0])).toContain('limit=25')
    expect(page.nextAfter).toBe('deploy-runbook')
    expect(page.items[0]?.entryKey).toBe('deploy-runbook')
    expect(JSON.stringify(page)).not.toContain('private')
  })

  it('retains strong ETags for the head (version) and version rows (contentHash)', async () => {
    const fetcher = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.endsWith('/effective-version')) return json(versionPayload(), 200, { ETag: `"${'a'.repeat(64)}"` })
      if (url.includes('/versions/2')) return json(versionPayload(), 200, { ETag: `"${'b'.repeat(64)}"` })
      return json(entryPayload(), 200, { ETag: '"3"' })
    })
    const gateway = gatewayWith(fetcher)

    const entry = await gateway.getEntry(scope, entryId)
    const version = await gateway.getVersion(scope, entryId, 2)
    const effective = await gateway.getEffectiveVersion(scope, entryId)

    expect(entry.etag).toBe('"3"')
    expect(version.etag).toBe(`"${'b'.repeat(64)}"`)
    expect(effective?.etag).toBe(`"${'a'.repeat(64)}"`)
  })

  it('resolves an absent effective version as null, not an error', async () => {
    const fetcher = vi.fn(async () => json({ code: 'aggregate_not_found', message: 'not found' }, 404))
    const gateway = gatewayWith(fetcher)

    expect(await gateway.getEffectiveVersion(scope, entryId)).toBeNull()
  })

  it('rethrows non-404 failures from the effective version probe', async () => {
    const gateway = gatewayWith(vi.fn(async () => json({ code: 'policy_denied', message: 'denied', correlationId: 'c-1' }, 403)))

    await expect(gateway.getEffectiveVersion(scope, entryId)).rejects.toThrow('denied')
  })

  it('sends If-Match and Idempotency-Key for PATCH, publish, retire and DELETE commands', async () => {
    const fetcher = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => json(receiptPayload(), 202))
    const gateway = gatewayWith(fetcher)

    await gateway.saveDraft(scope, entryId, { title: 't', content: 'c' }, '"3"', 'draft-key')
    await gateway.publishEntry(scope, entryId, '"4"', 'publish-key')
    await gateway.retireEntry(scope, entryId, '"5"', 'retire-key')
    await gateway.deleteEntry(scope, entryId, '"6"', 'delete-key')

    const calls = fetcher.mock.calls
    expect(new Headers(calls[0]?.[1]?.headers).get('If-Match')).toBe('"3"')
    expect(new Headers(calls[0]?.[1]?.headers).get('Idempotency-Key')).toBe('draft-key')
    expect(calls[0]?.[1]?.method).toBe('PATCH')
    expect(String(calls[1]?.[0])).toContain('/publish')
    expect(new Headers(calls[1]?.[1]?.headers).get('If-Match')).toBe('"4"')
    expect(String(calls[2]?.[0])).toContain('/retire')
    expect(calls[3]?.[1]?.method).toBe('DELETE')
    expect(new Headers(calls[3]?.[1]?.headers).get('If-Match')).toBe('"6"')
    // A draft PATCH keeps an absent category instead of echoing a stale one.
    expect(String(calls[0]?.[1]?.body)).not.toContain('category')
  })

  it('maps the distillation receipt for a first execution and for an idempotent replay', async () => {
    const fetcher = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      const replayed = new Headers(init?.headers).get('Idempotency-Key') === 'replay-key'
      return replayed
        ? json(receiptPayload(), 202, { 'Idempotency-Replayed': 'true' })
        : json({ ...receiptPayload(), entryId, origin: originPayload(), indexStatus: 'PENDING' }, 202)
    })
    const gateway = gatewayWith(fetcher)

    const first = await gateway.distill(scope, { taskExecutionId: executionId, entryKey: 'postmortem-cache' }, 'first-key')
    const replay = await gateway.distill(scope, { taskExecutionId: executionId, entryKey: 'postmortem-cache' }, 'replay-key')

    // Contract §1: the distillation collection sits beside /knowledge/entries, not under it.
    expect(String(fetcher.mock.calls[0]?.[0])).toContain('/knowledge/distillations')
    expect(String(fetcher.mock.calls[0]?.[0])).not.toContain('/entries/distillations')

    expect(first.entryId).toBe(entryId)
    expect(first.origin).toEqual(originPayload())
    expect(first.indexStatus).toBe('PENDING')
    expect(first.replayed).toBe(false)
    // A replayed envelope carries no result body (§11.1); only the echoed entryKey locates the entry.
    expect(replay.entryId).toBeNull()
    expect(replay.origin).toBeNull()
    expect(replay.indexStatus).toBeNull()
    expect(replay.entryKey).toBe('postmortem-cache')
    expect(replay.replayed).toBe(true)
  })

  it('fails closed on enum values this build does not know', async () => {
    const gateway = gatewayWith(vi.fn(async () => json({ items: [entryPayload({ status: 'ARCHIVED' })], nextAfter: null })))

    await expect(gateway.listEntries(scope, {})).rejects.toThrow('entry status')
  })

  it('rejects missing or weak ETags before a versioned resource reaches the store', async () => {
    const gateway = gatewayWith(vi.fn(async () => json(entryPayload(), 200, { ETag: 'W/"3"' })))

    await expect(gateway.getEntry(scope, entryId)).rejects.toThrow('strong ETag')
  })

  it('builds the job listing URL under /knowledge/index and whitelists the snapshot fields', async () => {
    const fetcher = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => json({ items: [jobPayload({ claimToken: 'worker-internal' })], nextAfter: '00000000-0000-0000-0000-000000007402' }))
    const gateway = gatewayWith(fetcher)

    const page = await gateway.listJobs(scope, { source: 'REPOSITORY', status: 'QUEUED' }, '00000000-0000-0000-0000-000000007401', 25)

    // The control plane lives under /knowledge/index, beside the entries collection.
    expect(String(fetcher.mock.calls[0]?.[0])).toContain('/knowledge/index/jobs?')
    expect(String(fetcher.mock.calls[0]?.[0])).toContain('source=REPOSITORY')
    expect(String(fetcher.mock.calls[0]?.[0])).toContain('status=QUEUED')
    expect(String(fetcher.mock.calls[0]?.[0])).toContain('limit=25')
    expect(page.nextAfter).toBe('00000000-0000-0000-0000-000000007402')
    expect(page.items[0]?.status).toBe('EMBEDDING')
    expect(page.items[0]?.indexKey?.modelRevision).toBe(3)
    expect(JSON.stringify(page)).not.toContain('worker-internal')
  })

  it('sends the three control commands without Idempotency-Key or If-Match (structural idempotency)', async () => {
    const fetcher = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => String(input).endsWith('/cancel')
      ? json(jobPayload({ status: 'CANCELLED', failureCode: 'CANCELLED' }), 200)
      : json({ enqueued: 1, job: jobPayload() }, 202))
    const gateway = gatewayWith(fetcher)

    await gateway.rebuild(scope)
    await gateway.enqueueRepositoryBuild(scope, { projectId: fixtureIds.projectCrewScope, bindingId: '00000000-0000-0000-0000-000000007501', commit: 'a'.repeat(40) })
    await gateway.cancelJob(scope, '00000000-0000-0000-0000-000000007401')

    const calls = fetcher.mock.calls
    expect(String(calls[0]?.[0])).toContain('/knowledge/index/rebuilds')
    expect(calls[0]?.[1]?.method).toBe('POST')
    expect(String(calls[1]?.[0])).toContain('/knowledge/index/repository-builds')
    expect(String(calls[1]?.[1]?.body)).toContain(fixtureIds.projectCrewScope)
    expect(String(calls[2]?.[0])).toContain('/knowledge/index/jobs/00000000-0000-0000-0000-000000007401/cancel')
    for (const call of calls) {
      expect(new Headers(call?.[1]?.headers).get('Idempotency-Key')).toBeNull()
      expect(new Headers(call?.[1]?.headers).get('If-Match')).toBeNull()
    }
  })

  it('maps the closed repository-build receipt, including the gate-closed zero shape', async () => {
    let closed = false
    const fetcher = vi.fn(async () => closed
      ? json({ enqueued: 0, job: null }, 202)
      : json({ enqueued: 1, job: jobPayload() }, 202))
    const gateway = gatewayWith(fetcher)
    const input = { projectId: fixtureIds.projectCrewScope, bindingId: '00000000-0000-0000-0000-000000007501', commit: 'b'.repeat(64) }

    const accepted = await gateway.enqueueRepositoryBuild(scope, input)
    closed = true
    const skipped = await gateway.enqueueRepositoryBuild(scope, input)

    expect(accepted.enqueued).toBe(1)
    expect(accepted.job?.id).toBe('00000000-0000-0000-0000-000000007401')
    // enqueued: 0 + job: null is a skip, never an error (contract §7).
    expect(skipped).toEqual({ enqueued: 0, job: null })
  })

  it('fails closed on job enum values this build does not know', async () => {
    const gateway = gatewayWith(vi.fn(async () => json({ items: [jobPayload({ status: 'PAUSED' })], nextAfter: null })))

    await expect(gateway.listJobs(scope, {})).rejects.toThrow('job status')
  })
})

function gatewayWith(fetcher: ReturnType<typeof vi.fn>): HttpKnowledgeGateway {
  return new HttpKnowledgeGateway(new CrewScopeApiClient('/api/v1', fetcher as unknown as typeof fetch))
}

function entryPayload(extra: Record<string, unknown> = {}) {
  return {
    id: entryId, entryKey: 'deploy-runbook', category: 'RUNBOOK', status: 'PUBLISHED',
    indexStatus: 'INDEXED', effectiveRevision: 2, latestRevision: 2,
    draft: { title: '部署手册', content: '先排干连接池。' }, version: 3,
    createdAt: '2026-10-01T01:00:00Z', updatedAt: '2026-10-02T01:00:00Z',
    createdBy: fixtureIds.principal, updatedBy: fixtureIds.principal,
    origin: null, ...extra,
  }
}

function versionPayload(extra: Record<string, unknown> = {}) {
  return {
    entryId, revision: 2, previousRevision: 1, title: '部署手册', content: '先排干连接池，再验证。',
    contentHash: 'a'.repeat(64), indexStatus: 'INDEXED',
    createdAt: '2026-10-02T01:00:00Z', createdBy: fixtureIds.principal, ...extra,
  }
}

function originPayload() {
  return { taskExecutionId: executionId, attempt: 2 }
}

/** Closed job snapshot (contract §3): claimToken never appears, extras must be dropped. */
function jobPayload(extra: Record<string, unknown> = {}) {
  return {
    id: '00000000-0000-0000-0000-000000007401',
    source: 'REPOSITORY',
    status: 'EMBEDDING',
    entryId: null,
    projectId: fixtureIds.projectCrewScope,
    indexKey: {
      bindingId: '00000000-0000-0000-0000-000000007501',
      commit: 'a'.repeat(40),
      chunkPolicyHash: 'c'.repeat(64),
      modelKey: 'text-embedding-v4',
      modelRevision: 3,
    },
    attempt: 2,
    chunksDone: 12,
    chunksTotal: 40,
    failureCode: null,
    generationBuildSequence: 4,
    claimedBy: 'knowledge-index-worker-1',
    leaseExpiresAt: '2026-10-04T09:30:00Z',
    createdBy: fixtureIds.principal,
    createdAt: '2026-10-04T09:00:00Z',
    updatedAt: '2026-10-04T09:05:00Z',
    ...extra,
  }
}

function receiptPayload() {
  return {
    commandId: '00000000-0000-0000-0000-000000007301',
    domainEventId: '00000000-0000-0000-0000-000000007302',
    committedVersion: 0,
    correlationId: '00000000-0000-0000-0000-000000007303',
  }
}

function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  })
}
