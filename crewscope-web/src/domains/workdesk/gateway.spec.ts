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
    availableActions: ['OPEN'], route: '/work?team=team-1&project=project-1&workItem=item-1',
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
    expect(result.sections[3]?.items[0]?.title).toBe('准备发布')
  })

  it('rejects unsafe navigation routes', async () => {
    expect(safeRoute('/work?team=team-1')).toBe(true)
    expect(safeRoute('//external.example')).toBe(false)
    const unsafe = { ...summary, sections: summary.sections.map((section, index) => index === 3 ? { ...section, items: [{ ...section.items[0]!, route: '//evil.example' }] } : section) }
    const client = new CrewScopeApiClient('/api/v1', async () => new Response(JSON.stringify(unsafe), { status: 200 }))
    await expect(new HttpWorkDeskGateway(client).get(scope)).rejects.toThrow('Unsafe WorkDesk route')
  })
})
