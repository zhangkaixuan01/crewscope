import { flushPromises, mount } from '@vue/test-utils'
import { CrewScopeApiError } from '../../api/client'
import type { OrganizationGateway } from '../../domains/organization/gateway'
import { ORGANIZATION_STORE, createOrganizationStore } from '../../domains/organization/store'
import type { ExportItem, ExportJob, ExportJobStatus } from '../../domains/organization/types'
import OrganizationExportWorkflow from './OrganizationExportWorkflow.vue'

const organizationId = '00000000-0000-0000-0000-000000000001'

describe('OrganizationExportWorkflow', () => {
  it('creates a job, renders the family table, and settles it through process', async () => {
    const gateway = fakeGateway()
    gateway.createExport.mockResolvedValueOnce(creation(exportJob('PENDING', [
      item('i-1', 'TASK', 'PENDING', null),
    ])))
    gateway.processExport.mockResolvedValueOnce(exportJob('COMPLETED', [
      item('i-1', 'TASK', 'DONE', 'checksum-1'),
    ], manifest()))
    const { wrapper } = await mountComponent(gateway)

    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(gateway.createExport).toHaveBeenCalledWith(
      organizationId, { includeVectors: false }, expect.any(String),
    )
    expect(wrapper.text()).toContain('待处理')
    expect(wrapper.text()).toContain('任务')
    expect(wrapper.findAll('tbody tr')).toHaveLength(1)

    await wrapper.findAll('button').find(button => button.text().includes('生成产物'))!.trigger('click')
    await flushPromises()
    expect(gateway.processExport).toHaveBeenCalledWith(organizationId, 'job-e1')
    expect(wrapper.text()).toContain('已完成')
    expect(wrapper.text()).toContain('对账清单')
    expect(wrapper.text()).toContain('下载产物')
  })

  it('offers resume for an interrupted RUNNING job with pending items', async () => {
    const gateway = fakeGateway()
    gateway.createExport.mockResolvedValueOnce(creation(exportJob('RUNNING', [
      item('i-1', 'TASK', 'DONE', 'checksum-1'),
      item('i-2', 'AUDIT_EVENT', 'PENDING', null),
    ])))
    const { wrapper } = await mountComponent(gateway)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('继续处理')
    expect(wrapper.text()).toContain('上次处理被中断')
    // A RUNNING job with pending items is still cancellable.
    expect(wrapper.findAll('button').some(button => button.text().includes('取消作业'))).toBe(true)
  })

  it('folds an idempotent replay into a notice without losing the panel job', async () => {
    const gateway = fakeGateway()
    gateway.createExport
      .mockResolvedValueOnce(creation(exportJob('PENDING', [])))
      .mockResolvedValueOnce({ job: null, receipt: receipt(), replayed: true })
    const { wrapper } = await mountComponent(gateway)

    await wrapper.get('form').trigger('submit')
    await flushPromises()
    // Same input would dedupe in-flight against the first dispatch (same derived key); a
    // changed include-vectors flag forces the second HTTP call whose replay is folded. The
    // component clears the flow before each create, so a replay leaves no job panel.
    await wrapper.get('input[type="checkbox"]').setValue(true)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('幂等重放')
    expect(wrapper.text()).toContain('作业历史以审计中心为准')
  })

  it('explains a terminal-state 409 and re-reads the job', async () => {
    const gateway = fakeGateway()
    gateway.createExport.mockResolvedValueOnce(creation(exportJob('RUNNING', [
      item('i-1', 'AUDIT_EVENT', 'PENDING', null),
    ])))
    gateway.cancelExport.mockRejectedValueOnce(apiError(409, 'job_already_terminal'))
    gateway.exportJob.mockResolvedValueOnce(exportJob('COMPLETED', []))
    const { wrapper } = await mountComponent(gateway)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    await wrapper.findAll('button').find(button => button.text().includes('取消作业'))!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('作业状态不允许')
    expect(gateway.exportJob).toHaveBeenCalledWith(organizationId, 'job-e1')
    expect(wrapper.text()).toContain('已完成')
  })
})

async function mountComponent(gateway: OrganizationGateway) {
  const store = createOrganizationStore(gateway)
  const wrapper = mount(OrganizationExportWorkflow, {
    props: { organizationId },
    global: {
      provide: { [ORGANIZATION_STORE as symbol]: store },
      stubs: { RouterLink: { template: '<a><slot/></a>' } },
    },
  })
  await flushPromises()
  await flushPromises()
  return { wrapper, gateway, store }
}

function item(id: string, family: ExportItem['resourceFamily'], state: ExportItem['state'], checksum: string | null): ExportItem {
  return {
    id, resourceFamily: family, batchSequence: 0, rowCount: 10, state, checksum,
    errorCode: null, processedAt: state === 'PENDING' ? null : '2026-10-13T09:00:00Z', version: 2,
  }
}

function exportJob(status: ExportJobStatus, items: ExportItem[], manifestValue: ExportJob['manifest'] = null): ExportJob {
  return {
    id: 'job-e1', organizationId, requestedByPrincipalId: 'p-1', status,
    includeVectors: false, manifest: manifestValue, version: 1,
    createdAt: '2026-10-13T08:00:00Z', updatedAt: '2026-10-13T08:00:00Z', items,
  }
}

function manifest(): NonNullable<ExportJob['manifest']> {
  return {
    generatedAt: '2026-10-13T10:00:00Z',
    families: [{ family: 'TASK', itemCount: 1, pinnedRowCount: 10, checksum: 'a'.repeat(64), skipped: [] }],
    vectors: { encoding: 'float32' },
  }
}

function creation(job: ExportJob) {
  return { job, receipt: null, replayed: false }
}

function receipt() {
  return { commandId: 'command-9', domainEventId: 'event-9', committedVersion: 1, correlationId: 'c-9', replayed: true }
}

function fakeGateway(): OrganizationGateway & {
  exportJob: ReturnType<typeof vi.fn>
  createExport: ReturnType<typeof vi.fn>
  processExport: ReturnType<typeof vi.fn>
  cancelExport: ReturnType<typeof vi.fn>
  openExportArtifacts: ReturnType<typeof vi.fn>
} {
  return {
    profile: vi.fn(async () => ({ organizationId, name: 'CrewScope Organization', status: 'ACTIVE' as const, version: 1 })),
    settings: vi.fn(async () => ({ auditEventRetentionDays: null, version: 0 })),
    updateSettings: vi.fn(async () => receipt()),
    roleGrants: vi.fn(async () => []),
    grantRole: vi.fn(async () => receipt()),
    revokeRoleGrant: vi.fn(async () => receipt()),
    exportJob: vi.fn(async () => exportJob('PENDING', [])),
    createExport: vi.fn(async () => creation(exportJob('PENDING', []))),
    processExport: vi.fn(async () => exportJob('COMPLETED', [])),
    cancelExport: vi.fn(async () => exportJob('CANCELLED', [])),
    openExportArtifacts: vi.fn(async () => ({ manifest: null, artifacts: [] })),
    deletionPrecheck: vi.fn(async () => ({ accountId: 'account-1', inFlightTaskExecutions: 0, activeResponsibilities: 0, openReservations: null })),
    createDeletion: vi.fn(async () => ({ job: null, receipt: null, replayed: false })),
    deletionJob: vi.fn(async () => deletionJobFixture()),
    confirmDeletion: vi.fn(async () => deletionJobFixture()),
    processDeletion: vi.fn(async () => deletionJobFixture()),
    cancelDeletion: vi.fn(async () => deletionJobFixture()),
  }
}

function deletionJobFixture() {
  return {
    id: 'job-d1', organizationId, accountId: 'account-1', targetPrincipalId: 'p-2',
    reason: '离职账号清理，含满足最短长度要求的完整说明。',
    status: 'PENDING_CONFIRMATION' as const,
    reservationCheck: '{"inFlightTaskExecutions":0,"activeResponsibilities":0,"openReservations":null,"checkedAt":"2026-10-13T08:00:00Z"}',
    backupRetentionStatement: '备份保留声明：每日快照 7 天、每周快照 4 周。',
    version: 1, createdAt: '2026-10-13T08:00:00Z', updatedAt: '2026-10-13T08:00:00Z', items: [],
  }
}

function apiError(status: number, code: string): CrewScopeApiError {
  return new CrewScopeApiError(status, {
    code, message: 'server message', correlationId: 'c-1', retryable: false, currentVersion: null, details: {},
  })
}
