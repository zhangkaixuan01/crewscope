import { CrewScopeApiClient } from '../../api/client'
import { HttpWorkDeskGateway, safeRoute } from './gateway'

const scope = { organizationId: 'org-1', teamId: 'team-1' }
const summary = {
  organizationId: 'org-1', teamId: 'team-1', projectId: null, generatedAt: '2026-09-13T10:00:00Z',
  sections: [
    { key: 'HUMAN_GATE', title: '等我决策', priority: 1, total: 0, truncated: false, items: [] },
    { key: 'REVIEW', title: '待我 Review', priority: 2, total: 0, truncated: false, items: [] },
    { key: 'BLOCKED', title: '被我阻塞', priority: 3, total: 0, truncated: false, items: [] },
    { key: 'WORK_ITEM', title: '我的工作项', priority: 4, total: 1, truncated: false, items: [{
    objectType: 'WORK_ITEM', objectId: 'item-1', projectId: 'project-1', title: '准备发布', status: 'OPEN',
    updatedAt: '2026-09-13T09:00:00Z', responsibilityRole: 'OWNER', needsAction: true, urgency: 'HIGH', progress: 40,
    availableActions: [{ actionId: 'submit-review', targetStatus: 'IN_REVIEW', label: '提交评审', strength: 'PRIMARY', reversible: true, enabled: true, reason: null, reasonMessage: null, remedyLabel: null, remedyRoute: null }],
    route: '/work?team=team-1&project=project-1&workItem=item-1',
    }] },
    { key: 'TASK_EXECUTION', title: '进行中的执行', priority: 5, total: 0, truncated: false, items: [] },
    { key: 'INBOX', title: '未读 Inbox', priority: 6, total: 0, truncated: false, items: [] },
  ],
}

describe('HttpWorkDeskGateway', () => {
  it('maps the personal summary and query parameters', async () => {
    let requestUrl = ''
    const client = new CrewScopeApiClient('/api/v1', async (input) => {
      requestUrl = String(input)
      return new Response(JSON.stringify(summary), { status: 200, headers: { 'content-type': 'application/json' } })
    })
    const result = await new HttpWorkDeskGateway(client).get(scope, { projectId: 'project-1', responsibilityRole: 'OWNER', onlyNeedsAction: true })
    expect(requestUrl).toContain('/organizations/org-1/teams/team-1/work-desk?')
    expect(requestUrl).toContain('onlyNeedsAction=true')
    expect(requestUrl).not.toContain('limit=')
    expect(result.sections[3]?.items[0]?.title).toBe('准备发布')
  })

  it('sends the section limit only when it is given and reads the page cursor and row facts', async () => {
    let requestUrl = ''
    const paged = {
      ...summary,
      sections: summary.sections.map((section, index) => index === 3 ? {
        ...section, total: 61, truncated: false, nextCursor: 'cursor-token',
        items: [{ ...section.items[0]!, rowSummary: {
          taskCount: 2, activeTaskCount: 1, pendingReviewCount: 3, selectionRequired: true,
          currentExecutionStatus: 'WAITING', waitingReason: 'REVIEW', observedAt: '2026-09-13T09:30:00Z', workItemVersion: 4,
        }, waitingOn: { principalId: 'principal-1', displayName: null, role: null } }],
      } : { ...section, nextCursor: null }),
    }
    const client = new CrewScopeApiClient('/api/v1', async (input) => {
      requestUrl = String(input)
      return new Response(JSON.stringify(paged), { status: 200 })
    })
    const result = await new HttpWorkDeskGateway(client).get(scope, {}, 25)
    expect(requestUrl).toContain('limit=25')
    const row = result.sections[3]!
    expect(row.nextCursor).toBe('cursor-token')
    expect(row.total).toBe(61)
    expect(row.items[0]?.rowSummary).toMatchObject({ taskCount: 2, selectionRequired: true, waitingReason: 'REVIEW', workItemVersion: 4 })
    // The server publishes only the principal ID; the name is resolved by the surface, not sent.
    expect(row.items[0]?.waitingOn).toEqual({ principalId: 'principal-1', displayName: null, role: null })
    // The other sections carry no cursor: an absent field is "this page is the whole set".
    expect(result.sections[0]?.nextCursor).toBeNull()

    const malformed = JSON.parse(JSON.stringify(paged)) as Record<string, unknown>
    const firstSection = (malformed.sections as Record<string, unknown>[])[3] as Record<string, unknown>
    const firstItem = (firstSection.items as Record<string, unknown>[])[0] as Record<string, unknown>
    firstItem.rowSummary = { ...firstItem.rowSummary as Record<string, unknown>, taskCount: 'two' }
    const strict = new CrewScopeApiClient('/api/v1', async () => new Response(JSON.stringify(malformed), { status: 200 }))
    await expect(new HttpWorkDeskGateway(strict).get(scope)).rejects.toThrow('Invalid WorkDesk number')
  })

  it('continues exactly one section from its cursor through the per-section endpoint', async () => {
    let requestUrl = ''
    const section = { ...summary.sections[3]!, items: [], nextCursor: null }
    const client = new CrewScopeApiClient('/api/v1', async (input) => {
      requestUrl = String(input)
      return new Response(JSON.stringify(section), { status: 200 })
    })
    const gateway = new HttpWorkDeskGateway(client)
    const continued = await gateway.getSection(scope, 'WORK_ITEM', { projectId: 'project-1' }, 'cursor-token', 25)
    expect(requestUrl).toBe('/api/v1/organizations/org-1/teams/team-1/work-desk/sections/WORK_ITEM?projectId=project-1&after=cursor-token&limit=25')
    expect(continued.key).toBe('WORK_ITEM')

    const other = { ...summary.sections[4]!, items: [], nextCursor: null }
    const mismatched = new CrewScopeApiClient('/api/v1', async () => new Response(JSON.stringify(other), { status: 200 }))
    await expect(new HttpWorkDeskGateway(mismatched).getSection(scope, 'WORK_ITEM')).rejects.toThrow('WorkDesk section key does not match the request')
  })

  it('rejects unsafe navigation routes', async () => {
    expect(safeRoute('/work?team=team-1')).toBe(true)
    expect(safeRoute('//external.example')).toBe(false)
    const unsafe = { ...summary, sections: summary.sections.map((section, index) => index === 3 ? { ...section, items: [{ ...section.items[0]!, route: '//evil.example' }] } : section) }
    const client = new CrewScopeApiClient('/api/v1', async () => new Response(JSON.stringify(unsafe), { status: 200 }))
    await expect(new HttpWorkDeskGateway(client).get(scope)).rejects.toThrow('Unsafe WorkDesk route')
  })
})
