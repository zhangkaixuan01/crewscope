import { CrewScopeApiClient } from '../../api/client'
import { fixtureIds } from '../../test/scopeFixtures'
import { workItemIds } from '../../test/workItemFixtures'
import { HttpWorkGraphGateway } from './gateway'
import type { WorkItemRelationCommandScope, WorkGraphQueryScope } from './types'

const commandScope: WorkItemRelationCommandScope = {
  organizationId: fixtureIds.organization,
  teamId: fixtureIds.teamPlatform,
}
const queryScope: WorkGraphQueryScope = {
  ...commandScope,
  projectId: fixtureIds.projectCrewScope,
}

describe('HttpWorkGraphGateway', () => {
  it('targets the six contract paths from their two scope shapes', async () => {
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async (input) => {
      const url = String(input)
      if (url.includes('/work-item-relations')) return jsonResponse(receipt())
      if (url.includes('/relations')) return jsonResponse({ anchor: workItemIds.first, edges: [], truncatedByRows: false })
      if (url.includes('/blocking-chain')) return jsonResponse({ anchor: workItemIds.first, steps: [], truncatedByDepth: false, truncatedByRows: false })
      if (url.includes('/critical-path')) return jsonResponse({ chain: [], truncatedByDepth: false, truncatedByRows: false })
      return jsonResponse(blockedPage())
    })
    const gateway = new HttpWorkGraphGateway(new CrewScopeApiClient('/api/v1', fetcher))

    await gateway.listBlockedWorkItems(commandScope)
    await gateway.listBlockedWorkItems(commandScope, fixtureIds.projectCrewScope, 'cursor-1', 50)
    await gateway.listRelations(queryScope, workItemIds.first)
    await gateway.listBlockingChain(queryScope, workItemIds.first)
    await gateway.listCriticalPath(queryScope, workItemIds.first)
    await gateway.createRelation(commandScope, { type: 'BLOCKS', fromWorkItemId: workItemIds.first, toWorkItemId: workItemIds.second }, 'idem-key')
    await gateway.removeRelation(commandScope, '00000000-0000-0000-0000-000000000901', 'idem-key')

    const root = `/api/v1/organizations/${fixtureIds.organization}/teams/${fixtureIds.teamPlatform}`
    expect(fetcher.mock.calls.map(call => String(call[0]))).toEqual([
      `${root}/work-graph/blocked-work-items?limit=100`,
      `${root}/work-graph/blocked-work-items?limit=50&projectId=${fixtureIds.projectCrewScope}&after=cursor-1`,
      `${root}/work-projects/${fixtureIds.projectCrewScope}/work-items/${workItemIds.first}/relations`,
      `${root}/work-projects/${fixtureIds.projectCrewScope}/work-items/${workItemIds.first}/blocking-chain`,
      `${root}/work-projects/${fixtureIds.projectCrewScope}/work-items/${workItemIds.first}/critical-path`,
      `${root}/work-item-relations`,
      `${root}/work-item-relations/00000000-0000-0000-0000-000000000901`,
    ])
    expect(fetcher.mock.calls[5]?.[1]?.method).toBe('POST')
    expect(fetcher.mock.calls[6]?.[1]?.method).toBe('DELETE')
    expect(new Headers(fetcher.mock.calls[5]?.[1]?.headers).get('Idempotency-Key')).toBe('idem-key')
    expect(new Headers(fetcher.mock.calls[6]?.[1]?.headers).get('Idempotency-Key')).toBe('idem-key')
    // Relation facts carry no If-Match: the edge is a version-0 fact, neither endpoint's version (§2.1).
    expect(new Headers(fetcher.mock.calls[5]?.[1]?.headers).get('If-Match')).toBeNull()
  })

  it('reads the blocked whitelist field by field and ignores unknown extras', async () => {
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async () => jsonResponse({
      items: [{
        ...blockedRow(),
        futureField: 'ignored by contract',
        blockers: [{ ...blockedRow().blockers[0], extra: true }],
      }],
      nextCursor: null,
      serverHint: 'ignored',
    }))
    const page = await new HttpWorkGraphGateway(new CrewScopeApiClient('/api/v1', fetcher))
      .listBlockedWorkItems(commandScope)

    expect(page.nextCursor).toBeNull()
    expect(page.items).toEqual([blockedRow()])
  })

  it('fails loudly on a malformed whitelist field instead of rendering a wrong count', async () => {
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async () => jsonResponse({
      items: [{ ...blockedRow(), blockerCount: 'many' }],
      nextCursor: null,
    }))
    await expect(new HttpWorkGraphGateway(new CrewScopeApiClient('/api/v1', fetcher))
      .listBlockedWorkItems(commandScope)).rejects.toThrow('blocked.blockerCount is not a number')
  })

  it('parses adjacency views including the DEPENDS_ON mirror of one stored BLOCKS edge', async () => {
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async () => jsonResponse({
      anchor: workItemIds.first,
      truncatedByRows: true,
      edges: [{
        relationId: '00000000-0000-0000-0000-000000000902',
        direction: 'INCOMING',
        otherWorkItemId: workItemIds.second,
        otherProjectId: fixtureIds.projectRuntime,
        crossProject: true,
        views: [
          { type: 'BLOCKS', fromWorkItemId: workItemIds.second, toWorkItemId: workItemIds.first },
          { type: 'DEPENDS_ON', fromWorkItemId: workItemIds.first, toWorkItemId: workItemIds.second },
        ],
        createdAt: '2026-10-11T08:00:00Z',
      }],
    }))
    const relations = await new HttpWorkGraphGateway(new CrewScopeApiClient('/api/v1', fetcher))
      .listRelations(queryScope, workItemIds.first)

    expect(relations.anchor).toBe(workItemIds.first)
    expect(relations.truncatedByRows).toBe(true)
    expect(relations.edges[0]?.views.map(view => view.type)).toEqual(['BLOCKS', 'DEPENDS_ON'])
  })

  it('rejects an unknown relation direction or type as a contract break', async () => {
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async () => jsonResponse({
      anchor: workItemIds.first,
      edges: [{ relationId: 'r', direction: 'SIDEWAYS', otherWorkItemId: workItemIds.second,
        otherProjectId: fixtureIds.projectCrewScope, crossProject: false, views: [], createdAt: '2026-10-11T08:00:00Z' }],
    }))
    await expect(new HttpWorkGraphGateway(new CrewScopeApiClient('/api/v1', fetcher))
      .listRelations(queryScope, workItemIds.first)).rejects.toThrow('relation.direction is invalid: SIDEWAYS')
  })
})

function receipt() {
  return { commandId: 'c-1', domainEventId: 'e-1', committedVersion: 0, correlationId: 'x-1' }
}

function blockedRow() {
  return {
    workItemId: workItemIds.first,
    itemKey: 'CRW-18',
    title: '建立团队看板',
    status: 'IN_PROGRESS',
    projectId: fixtureIds.projectCrewScope,
    blockedSince: '2026-10-11T07:00:00Z',
    blockerCount: 1,
    crossesProject: false,
    blockers: [{
      workItemId: workItemIds.second,
      title: '修复 Cursor 重复项',
      status: 'READY',
      projectId: fixtureIds.projectCrewScope,
    }],
  }
}

function blockedPage(nextCursor: string | null = null) {
  return { items: [blockedRow()], nextCursor }
}

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
}
