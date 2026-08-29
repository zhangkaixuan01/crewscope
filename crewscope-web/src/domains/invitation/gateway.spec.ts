import { CrewScopeApiClient } from '../../api/client'
import { HttpInvitationGateway } from './gateway'

const csrf = { headerName: 'X-XSRF-TOKEN' as const, parameterName: '_csrf' as const, token: 'csrf-invitation' }
const token = 'A'.repeat(43)

describe('HttpInvitationGateway', () => {
  it('reconstructs a bounded management page without token material', async () => {
    const fetcher = vi.fn<typeof fetch>(async () => json({ items: [invitation()], nextCursor: 'next-page' }))
    const gateway = gatewayWith(fetcher)

    const page = await gateway.list('organization/1', 'team 1', 'cursor/value')

    expect(page.items[0]).toMatchObject({ targetEmail: 'member@example.com', status: 'PENDING' })
    expect(fetcher.mock.calls[0]?.[0]).toContain('/organizations/organization%2F1/teams/team%201/invitations?')
    expect(fetcher.mock.calls[0]?.[0]).toContain('after=cursor%2Fvalue')
    expect(JSON.stringify(page)).not.toMatch(/token|digest/i)
  })

  it('creates an invitation with CSRF and idempotency while retaining the proof only in the result', async () => {
    const fetcher = vi.fn<typeof fetch>(async () => json({ command: receipt(), invitation: invitation(), token }, 202))
    const gateway = gatewayWith(fetcher)

    const result = await gateway.create('organization-1', 'team-1', {
      targetEmail: 'member@example.com', targetRole: 'MEMBER', expiresInMinutes: 10_080,
    }, { csrf, idempotencyKey: 'issue-1' })

    const request = fetcher.mock.calls[0]?.[1]
    expect(new Headers(request?.headers).get('Idempotency-Key')).toBe('issue-1')
    expect(new Headers(request?.headers).get('X-XSRF-TOKEN')).toBe('csrf-invitation')
    expect(JSON.parse(String(request?.body))).toEqual({ targetEmail: 'member@example.com', targetRole: 'MEMBER', expiresInMinutes: 10_080 })
    expect(result.token).toBe(token)
  })

  it('accepts a receipt-only replay and rejects replayed issue material', async () => {
    const replay = gatewayWith(vi.fn(async () => json(
      { command: receipt(), invitation: null, token: null }, 202, { 'Idempotency-Replayed': 'true' },
    )))
    await expect(replay.create('organization-1', 'team-1', {
      targetRole: 'AUDITOR', expiresInMinutes: 60,
    }, { csrf, idempotencyKey: 'replay-1' })).resolves.toMatchObject({ replayed: true, invitation: null, token: null })

    const leakingReplay = gatewayWith(vi.fn(async () => json(
      { command: receipt(), invitation: invitation(), token }, 202, { 'Idempotency-Replayed': 'true' },
    )))
    await expect(leakingReplay.create('organization-1', 'team-1', {
      targetRole: 'MEMBER', expiresInMinutes: 60,
    }, { csrf, idempotencyKey: 'replay-2' })).rejects.toThrow('replay')
  })

  it('maps privacy-bounded preview and one-way accept/revoke commands', async () => {
    const fetcher = vi.fn<typeof fetch>(async input => String(input).endsWith('/invitations/preview')
      ? json({ state: 'AVAILABLE', invitationId: 'invitation-1', teamName: 'Platform Engineering', targetRole: 'TEAM_LEAD', expiresAt: '2026-09-01T00:00:00Z', targetRestricted: true })
      : json(receipt(), 202))
    const gateway = gatewayWith(fetcher)

    const preview = await gateway.preview(token)
    await gateway.accept(token, { csrf, idempotencyKey: 'accept-1' })
    await gateway.revoke('organization-1', 'team-1', 'invitation-1', { csrf, idempotencyKey: 'revoke-1' })

    expect(preview).toEqual({ state: 'AVAILABLE', invitationId: 'invitation-1', teamName: 'Platform Engineering', targetRole: 'TEAM_LEAD', expiresAt: '2026-09-01T00:00:00Z', targetRestricted: true })
    expect(JSON.parse(String(fetcher.mock.calls[1]?.[1]?.body))).toEqual({ token })
    expect(String(fetcher.mock.calls[1]?.[1]?.body)).not.toMatch(/account|principal|membership/i)
  })
})

function gatewayWith(fetcher: ReturnType<typeof vi.fn>): HttpInvitationGateway {
  return new HttpInvitationGateway(new CrewScopeApiClient('/api/v1', fetcher as unknown as typeof fetch))
}

function invitation() {
  return {
    id: 'invitation-1', organizationId: 'organization-1', teamId: 'team-1', invitedByPrincipalId: 'principal-1',
    targetEmail: 'member@example.com', targetRole: 'MEMBER', status: 'PENDING', expiresAt: '2026-09-01T00:00:00Z',
    acceptedMemberId: null, resolvedAt: null, version: 0,
    createdAt: '2026-08-29T00:00:00Z', updatedAt: '2026-08-29T00:00:00Z',
  }
}

function receipt() {
  return { commandId: 'command-1', domainEventId: 'event-1', committedVersion: 1, correlationId: 'correlation-1' }
}

function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } })
}
