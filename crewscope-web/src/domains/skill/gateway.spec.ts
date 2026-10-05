import { CrewScopeApiClient } from '../../api/client'
import { fixtureIds } from '../../test/scopeFixtures'
import { HttpSkillGateway } from './gateway'

const scope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform }
const skillId = '00000000-0000-0000-0000-000000008101'
const executionId = '00000000-0000-0000-0000-000000008201'

describe('HttpSkillGateway', () => {
  it('builds the keyset listing URL with filter, cursor and limit, whitelisting head fields', async () => {
    const fetcher = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => json({ items: [skillPayload({ internalDraftToken: 'private' })], nextAfter: 'deploy-runbook-v2' }))
    const gateway = gatewayWith(fetcher)

    const page = await gateway.listSkills(scope, { status: 'PUBLISHED' }, 'api-guard', 25)

    expect(String(fetcher.mock.calls[0]?.[0])).toContain('/skills?')
    expect(String(fetcher.mock.calls[0]?.[0])).toContain('status=PUBLISHED')
    expect(String(fetcher.mock.calls[0]?.[0])).toContain('after=api-guard')
    expect(String(fetcher.mock.calls[0]?.[0])).toContain('limit=25')
    expect(page.nextAfter).toBe('deploy-runbook-v2')
    expect(page.items[0]?.skillKey).toBe('deploy-runbook-v2')
    expect(JSON.stringify(page)).not.toContain('private')
  })

  it('retains strong ETags for the head (version) and version rows (contentHash)', async () => {
    const fetcher = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.endsWith('/effective-version')) return json(versionPayload(), 200, { ETag: `"${'a'.repeat(64)}"` })
      if (url.includes('/versions/2')) return json(versionPayload(), 200, { ETag: `"${'b'.repeat(64)}"` })
      return json(skillPayload(), 200, { ETag: '"3"' })
    })
    const gateway = gatewayWith(fetcher)

    const skill = await gateway.getSkill(scope, skillId)
    const version = await gateway.getVersion(scope, skillId, 2)
    const effective = await gateway.getEffectiveVersion(scope, skillId)

    expect(skill.etag).toBe('"3"')
    expect(version.etag).toBe(`"${'b'.repeat(64)}"`)
    expect(effective?.etag).toBe(`"${'a'.repeat(64)}"`)
  })

  it('resolves an absent effective version as null, not an error', async () => {
    const gateway = gatewayWith(vi.fn(async () => json({ code: 'aggregate_not_found', message: 'not found' }, 404)))

    expect(await gateway.getEffectiveVersion(scope, skillId)).toBeNull()
  })

  it('rethrows non-404 failures from the effective version probe', async () => {
    const gateway = gatewayWith(vi.fn(async () => json({ code: 'policy_denied', message: 'denied', correlationId: 'c-1' }, 403)))

    await expect(gateway.getEffectiveVersion(scope, skillId)).rejects.toThrow('denied')
  })

  it('sends the create body with only skillKey and content', async () => {
    const fetcher = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => json(receiptPayload(), 202))
    const gateway = gatewayWith(fetcher)

    await gateway.createSkill(scope, { skillKey: 'deploy-runbook-v2', content: document() }, 'create-key')

    expect(fetcher.mock.calls[0]?.[1]?.method).toBe('POST')
    expect(String(fetcher.mock.calls[0]?.[0])).toMatch(/\/skills$/)
    expect(JSON.parse(String(fetcher.mock.calls[0]?.[1]?.body))).toEqual({ skillKey: 'deploy-runbook-v2', content: document() })
    expect(new Headers(fetcher.mock.calls[0]?.[1]?.headers).get('Idempotency-Key')).toBe('create-key')
    expect(new Headers(fetcher.mock.calls[0]?.[1]?.headers).get('If-Match')).toBeNull()
  })

  it('sends If-Match and Idempotency-Key for PATCH, publish, disable and rollback commands', async () => {
    const fetcher = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => json(receiptPayload(), 202))
    const gateway = gatewayWith(fetcher)

    await gateway.saveDraft(scope, skillId, { content: document('next') }, '"3"', 'draft-key')
    await gateway.publishSkill(scope, skillId, '"4"', 'publish-key')
    await gateway.disableSkill(scope, skillId, '弃用', '"5"', 'disable-key')
    await gateway.rollbackSkill(scope, skillId, 1, '"6"', 'rollback-key')

    const calls = fetcher.mock.calls
    expect(calls[0]?.[1]?.method).toBe('PATCH')
    expect(new Headers(calls[0]?.[1]?.headers).get('If-Match')).toBe('"3"')
    expect(new Headers(calls[0]?.[1]?.headers).get('Idempotency-Key')).toBe('draft-key')
    expect(JSON.parse(String(calls[0]?.[1]?.body))).toEqual({ content: document('next') })
    expect(String(calls[1]?.[0])).toContain('/publish')
    expect(calls[1]?.[1]?.body).toBeUndefined()
    expect(new Headers(calls[1]?.[1]?.headers).get('If-Match')).toBe('"4"')
    expect(String(calls[2]?.[0])).toContain('/disable')
    expect(JSON.parse(String(calls[2]?.[1]?.body))).toEqual({ reason: '弃用' })
    expect(new Headers(calls[2]?.[1]?.headers).get('If-Match')).toBe('"5"')
    expect(String(calls[3]?.[0])).toContain('/rollback')
    expect(JSON.parse(String(calls[3]?.[1]?.body))).toEqual({ toRevision: 1 })
    expect(new Headers(calls[3]?.[1]?.headers).get('If-Match')).toBe('"6"')
  })

  it('omits the disable body entirely when no reason is given (contract §2)', async () => {
    const fetcher = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => json(receiptPayload(), 202))
    const gateway = gatewayWith(fetcher)

    await gateway.disableSkill(scope, skillId, null, '"5"', 'disable-key')

    expect(fetcher.mock.calls[0]?.[1]?.body).toBeUndefined()
  })

  it('maps the distillation receipt for a first execution and for an idempotent replay', async () => {
    const fetcher = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      const replayed = new Headers(init?.headers).get('Idempotency-Key') === 'replay-key'
      return replayed
        ? json(receiptPayload(), 202, { 'Idempotency-Replayed': 'true' })
        : json({ ...receiptPayload(), skillId, status: 'DRAFT', origin: originPayload() }, 202)
    })
    const gateway = gatewayWith(fetcher)

    const first = await gateway.distill(scope, { taskExecutionId: executionId, skillKey: 'postmortem-cache' }, 'first-key')
    const replay = await gateway.distill(scope, { taskExecutionId: executionId, skillKey: 'postmortem-cache' }, 'replay-key')

    // Contract §1: the distillation collection sits under /skills, beside the skill items.
    expect(String(fetcher.mock.calls[0]?.[0])).toContain('/skills/distillations')

    expect(first.skillId).toBe(skillId)
    expect(first.status).toBe('DRAFT')
    expect(first.origin).toEqual(originPayload())
    expect(first.replayed).toBe(false)
    // A replayed envelope carries no result body (§11); only the echoed skillKey locates the skill.
    expect(replay.skillId).toBeNull()
    expect(replay.status).toBeNull()
    expect(replay.origin).toBeNull()
    expect(replay.skillKey).toBe('postmortem-cache')
    expect(replay.replayed).toBe(true)
  })

  it('fails closed on status values this build does not know', async () => {
    const gateway = gatewayWith(vi.fn(async () => json({ items: [skillPayload({ status: 'ARCHIVED' })], nextAfter: null })))

    await expect(gateway.listSkills(scope, {})).rejects.toThrow('Skill status')
  })

  it('rejects missing or weak ETags before a versioned resource reaches the store', async () => {
    const gateway = gatewayWith(vi.fn(async () => json(skillPayload(), 200, { ETag: 'W/"3"' })))

    await expect(gateway.getSkill(scope, skillId)).rejects.toThrow('strong ETag')
  })

  it('rejects a non-numeric head ETag before it can back an If-Match', async () => {
    const gateway = gatewayWith(vi.fn(async () => json(skillPayload(), 200, { ETag: `"${'a'.repeat(64)}"` })))

    await expect(gateway.saveDraft(scope, skillId, { content: document() }, `"${'a'.repeat(64)}"`, 'draft-key'))
      .rejects.toThrow('ETag is invalid')
  })
})

function gatewayWith(fetcher: ReturnType<typeof vi.fn>): HttpSkillGateway {
  return new HttpSkillGateway(new CrewScopeApiClient('/api/v1', fetcher as unknown as typeof fetch))
}

function skillPayload(extra: Record<string, unknown> = {}) {
  return {
    id: skillId, skillKey: 'deploy-runbook-v2', status: 'PUBLISHED',
    effectiveRevision: 2, latestRevision: 2,
    draft: { name: 'deploy-runbook-v2', description: 'Marker d1 of the drill.', content: document() },
    disableReason: null, version: 3,
    createdAt: '2026-10-01T01:00:00Z', updatedAt: '2026-10-02T01:00:00Z',
    createdBy: fixtureIds.principal, updatedBy: fixtureIds.principal,
    origin: null, ...extra,
  }
}

function versionPayload(extra: Record<string, unknown> = {}) {
  return {
    skillId, revision: 2, previousRevision: 1, content: document(), contentHash: 'a'.repeat(64),
    createdAt: '2026-10-02T01:00:00Z', createdBy: fixtureIds.principal, ...extra,
  }
}

function originPayload() {
  return { taskExecutionId: executionId, attempt: 2 }
}

function receiptPayload() {
  return {
    commandId: '00000000-0000-0000-0000-000000008301',
    domainEventId: '00000000-0000-0000-0000-000000008302',
    committedVersion: 0,
    correlationId: '00000000-0000-0000-0000-000000008303',
  }
}

function document(marker = 'd1') {
  return `---\nname: deploy-runbook-v2\ndescription: Marker ${marker} of the drill.\n---\n\nBody of the document.`
}

function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  })
}
