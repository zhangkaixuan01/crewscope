import { mount } from '@vue/test-utils'
import type { TeamOpsCorrelationResource } from '../../domains/teamops/store'
import type { AuditEvent, CorrelationGraph } from '../../domains/teamops/types'
import AuditExplorer from './AuditExplorer.vue'

describe('AuditExplorer', () => {
  it('renders the public Audit table and emits combination filters', async () => {
    const wrapper = mount(AuditExplorer, { props: props() })

    expect(wrapper.get('table').text()).toContain('TEAM_ACCESS_DENIED')
    expect(wrapper.text()).toContain('SECURITY')
    expect(wrapper.text()).not.toMatch(/Authorization Context|Credential|Endpoint|Trace ID|Provider Body|原始 Payload/)

    const selects = wrapper.findAll<HTMLSelectElement>('.audit-filter__primary select')
    await selects[0]!.setValue('SECURITY')
    await selects[1]!.setValue('DENIED')
    await wrapper.get('form').trigger('submit')

    expect(wrapper.emitted('applyFilter')?.[0]?.[0]).toMatchObject({ category: 'SECURITY', outcome: 'DENIED' })
  })

  it('requires paired Subject filters and valid UUID identifiers', async () => {
    const wrapper = mount(AuditExplorer, { props: props() })
    await wrapper.get('.advanced-toggle').trigger('click')
    const subjectType = wrapper.findAll<HTMLInputElement>('.audit-filter__advanced input')[3]!
    await subjectType.setValue('WORK_ITEM')
    await wrapper.get('form').trigger('submit')

    expect(wrapper.text()).toContain('Subject Type 与 Subject ID 必须同时填写')
    expect(wrapper.emitted('applyFilter')).toBeUndefined()
  })

  it('shows only safe detail fields and opens the Correlation chain', async () => {
    const value = audit()
    const wrapper = mount(AuditExplorer, { props: props({ selectedEvent: value }) })

    expect(wrapper.get('[aria-label="审计事件详情"]').text()).toContain('Provider 安全引用')
    expect(wrapper.text()).toContain('permission_denied')
    await wrapper.findAll('button').find(item => item.text().includes('查看关联链'))!.trigger('click')
    expect(wrapper.emitted('openCorrelation')?.[0]).toEqual([value.correlation.correlationId])
  })

  it('renders Correlation nodes and emits only the Gateway-approved object href', async () => {
    const graph = correlationGraph()
    const wrapper = mount(AuditExplorer, { props: props({ correlation: correlationResource(graph), correlationId: graph.correlationId }) })

    expect(wrapper.get('[aria-label="Correlation 关联链"]').text()).toContain('TEAM_ACCESS_DENIED')
    await wrapper.get('.correlation-objects button').trigger('click')
    expect(wrapper.emitted('navigate')?.[0]).toEqual([graph.objects[0]!.href])
  })

  it('enforces explicit 31-day export bounds and governance permission', async () => {
    const wrapper = mount(AuditExplorer, { props: props({ canExport: false }) })
    expect(wrapper.text()).toContain('当前身份没有治理导出权限')
    const exportButton = wrapper.findAll('button').find(item => item.text().includes('导出 JSON'))!
    expect(exportButton.attributes('disabled')).toBeDefined()

    await wrapper.setProps({ canExport: true, initialFilter: { ...filter(), from: '2026-08-01T08:00', to: '2026-08-20T08:00' } })
    expect(wrapper.text()).not.toContain('导出时间范围不能超过 31 天')
    await exportButton.trigger('click')
    expect(wrapper.emitted('export')?.[0]).toEqual([1000])

    await wrapper.setProps({ initialFilter: { ...filter(), from: '2026-06-01T08:00', to: '2026-08-20T08:00' } })
    expect(wrapper.text()).toContain('导出时间范围不能超过 31 天')
  })

  it.each([
    ['loading', { phase: 'loading', items: [] }, '正在加载审计事实'],
    ['empty', { phase: 'empty', items: [] }, '当前筛选没有审计事实'],
    ['forbidden', { phase: 'error', items: [], error: error('forbidden') }, '无权查看团队审计'],
    ['offline', { phase: 'error', items: [], error: error('offline'), online: false }, '离线时没有可用审计事实'],
    ['cursor-expired cached', { phase: 'error', error: error('cursor-expired') }, '审计续页 Cursor 已过期'],
    ['error', { phase: 'error', items: [], error: error('unknown') }, 'unknown'],
  ] as const)('renders the %s state', (_name, overrides, expected) => {
    const wrapper = mount(AuditExplorer, { props: props(overrides) })
    expect(wrapper.text()).toContain(expected)
  })
})

function props(overrides: Record<string, unknown> = {}) {
  return {
    phase: 'ready' as const, items: [audit()], error: null, nextCursor: 'cursor-2', loadingMore: false,
    selectedEvent: null, correlation: null as TeamOpsCorrelationResource | null, correlationId: '', initialFilter: filter(),
    online: true, canExport: true, exportPhase: 'idle' as const, exportError: null,
    ...overrides,
  }
}

function filter() {
  return { from: '', to: '', category: '', outcome: '', initiator: '', actor: '', agent: '', subjectType: '', subjectId: '', providerBinding: '', correlation: '' }
}

function audit(): AuditEvent {
  return {
    eventId: uuid(1), eventType: 'TEAM_ACCESS_DENIED', sourceSchemaVersion: 1, category: 'SECURITY', outcome: 'DENIED', retentionLevel: 'EXTENDED',
    occurredAt: '2026-08-27T08:00:00Z', identity: { initiatorId: uuid(2), actorType: 'USER', actorId: uuid(2), agentPrincipalId: null },
    subject: { type: 'TEAM', id: uuid(3) }, provider: { providerBindingId: uuid(4), connectionId: uuid(5), externalOperationHash: 'a'.repeat(64) },
    correlation: { correlationId: uuid(6), causationId: null, domainEventId: uuid(7) }, summary: { reasonCode: 'permission_denied' },
  }
}

function correlationGraph(): CorrelationGraph {
  const href = `/activity?team=${uuid(9)}&correlation=${uuid(6)}&objectType=WORK_ITEM&objectId=${uuid(3)}`
  return {
    correlationId: uuid(6), events: [{ eventId: uuid(1), source: 'AUDIT', eventType: 'TEAM_ACCESS_DENIED', actorType: 'USER', actorId: uuid(2), outcome: 'DENIED', occurredAt: '2026-08-27T08:00:00Z', references: [] }],
    objects: [{ type: 'WORK_ITEM', id: uuid(3), href, relatedEventIds: [uuid(1)] }], hasMore: false, nextCursor: null,
  }
}

function correlationResource(value: CorrelationGraph): TeamOpsCorrelationResource {
  return { phase: 'ready', value, error: null, nextCursor: null, loadingMore: false }
}

function error(kind: 'forbidden' | 'offline' | 'cursor-expired' | 'unknown') {
  return { kind, message: kind, status: kind === 'forbidden' ? 403 : null, retryable: false, currentVersion: null }
}

function uuid(index: number): string { return `00000000-0000-4000-8000-${String(index).padStart(12, '0')}` }
