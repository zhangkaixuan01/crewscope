import { flushPromises, mount } from '@vue/test-utils'
import { CrewScopeApiError } from '../../api/client'
import type { OrganizationGateway } from '../../domains/organization/gateway'
import { ORGANIZATION_STORE, createOrganizationStore } from '../../domains/organization/store'
import type { DeletionItem, DeletionJob, DeletionJobStatus } from '../../domains/organization/types'
import OrganizationDeletionWorkflow from './OrganizationDeletionWorkflow.vue'
import PrincipalPicker from './PrincipalPicker.vue'

const organizationId = '00000000-0000-0000-0000-000000000001'
const accountId = '00000000-0000-0000-0000-000000000111'
const reason = '离职账号清理，含满足最短长度要求的完整说明。'

describe('OrganizationDeletionWorkflow', () => {
  it('blocks create until a clear precheck and a valid reason, then queues PENDING_CONFIRMATION', async () => {
    const gateway = fakeGateway()
    gateway.deletionPrecheck.mockResolvedValueOnce({
      accountId, username: 'e2e-member', displayName: '林悦', inFlightTaskExecutions: 2, activeResponsibilities: 1, openReservations: null,
    })
    const { wrapper } = await mountComponent(gateway)

    await pickTarget(wrapper)
    await wrapper.findAll('form')[0].trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('在途任务执行')
    // M13b-A01: the precheck echoes the target's name and username — the token's reference.
    expect(wrapper.text()).toContain('林悦（e2e-member）')
    expect(wrapper.text()).toContain('存在未收尾的在途执行或未转移的责任，暂不能发起删除。')
    expect((createSubmit(wrapper).element as HTMLButtonElement).disabled).toBe(true)
    expect(wrapper.text()).toContain('在途任务执行')
    expect(wrapper.text()).toContain('存在未收尾的在途执行或未转移的责任，暂不能发起删除。')
    expect((createSubmit(wrapper).element as HTMLButtonElement).disabled).toBe(true)

    gateway.deletionPrecheck.mockResolvedValueOnce({
      accountId, username: 'e2e-member', displayName: '林悦', inFlightTaskExecutions: 0, activeResponsibilities: 0, openReservations: null,
    })
    await wrapper.findAll('form')[0].trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('三项先决均为零，可以发起删除作业。')
    expect((createSubmit(wrapper).element as HTMLButtonElement).disabled).toBe(true)

    await wrapper.get('#org-deletion-reason').setValue('太短')
    expect((createSubmit(wrapper).element as HTMLButtonElement).disabled).toBe(true)
    await wrapper.get('#org-deletion-reason').setValue(reason)
    expect((createSubmit(wrapper).element as HTMLButtonElement).disabled).toBe(false)
    await wrapper.findAll('form')[1].trigger('submit')
    await flushPromises()

    expect(gateway.createDeletion).toHaveBeenCalledWith(
      organizationId, { accountId, reason }, expect.any(String),
    )
    expect(wrapper.text()).toContain('待确认')
    expect(wrapper.text()).toContain('确认令牌（目标账号的用户名）')
  })

  it('confirms with the username token, clears the field on success, and processes to COMPLETED', async () => {
    const gateway = fakeGateway()
    gateway.deletionPrecheck.mockResolvedValueOnce(clearPrecheck())
    gateway.createDeletion.mockResolvedValueOnce(
      { job: deletionJob('PENDING_CONFIRMATION', [step('s-1', 'PENDING')]), receipt: null, replayed: false })
    gateway.confirmDeletion
      .mockRejectedValueOnce(apiError(422, 'token_mismatch'))
      .mockResolvedValueOnce(deletionJob('CONFIRMED', [step('s-1', 'PENDING')]))
    gateway.processDeletion.mockResolvedValueOnce(deletionJob('COMPLETED', [
      step('s-1', 'DONE'), step('s-2', 'CONFLICT'), step('s-3', 'DENIED'),
    ]))
    const { wrapper } = await mountComponent(gateway)
    await prepareCreate(wrapper)

    const token = wrapper.get('#org-deletion-token')
    expect(token.attributes('autocomplete')).toBe('off')
    await token.setValue('wrong-username')
    await confirmSubmit(wrapper).trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('确认令牌不匹配')
    expect((token.element as HTMLInputElement).value).toBe('wrong-username')

    await token.setValue('e2e-member')
    await confirmSubmit(wrapper).trigger('submit')
    await flushPromises()
    expect(gateway.confirmDeletion).toHaveBeenCalledWith(organizationId, 'job-d1', 'e2e-member', 1)
    // The confirmed echo is one-shot: the confirm form unmounts with the state change. The
    // username itself stays visible — it is the A01 directory fact, not the typed token.
    expect(wrapper.find('#org-deletion-token').exists()).toBe(false)
    expect(wrapper.find('.org-confirm').exists()).toBe(false)

    await wrapper.findAll('button').find(button => button.text().includes('开始执行'))!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('已完成')
    expect(wrapper.findAll('tbody tr')).toHaveLength(3)
    expect(wrapper.text()).toContain('备份保留声明')
    expect(wrapper.text()).toContain('重新执行本作业对账')
  })

  it('keeps the token out of storage while flowing through the confirm step', async () => {
    const gateway = fakeGateway()
    gateway.deletionPrecheck.mockResolvedValueOnce(clearPrecheck())
    gateway.createDeletion.mockResolvedValueOnce(
      { job: deletionJob('PENDING_CONFIRMATION', []), receipt: null, replayed: false })
    const { wrapper } = await mountComponent(gateway)
    await prepareCreate(wrapper)

    await wrapper.get('#org-deletion-token').setValue('e2e-member')
    await confirmSubmit(wrapper).trigger('submit')
    await flushPromises()

    expect(sessionStorage.getItem('confirmationToken')).toBeNull()
    expect(localStorage.getItem('confirmationToken')).toBeNull()
    expect(Object.values(sessionStorage).concat(Object.values(localStorage))).not.toContain('e2e-member')
  })

  it('locks both forms behind a live job instead of offering a second entry (M13b-F02 R40)', async () => {
    const gateway = fakeGateway()
    gateway.deletionPrecheck.mockResolvedValueOnce(clearPrecheck())
    gateway.createDeletion.mockResolvedValueOnce(
      { job: deletionJob('PENDING_CONFIRMATION', []), receipt: null, replayed: false })
    const { wrapper } = await mountComponent(gateway)
    await prepareCreate(wrapper)

    expect(wrapper.text()).toContain('已有进行中的删除作业')
    const precheckButton = wrapper.findAll('button').find(button => button.text().includes('运行预检'))!
    expect((precheckButton.element as HTMLButtonElement).disabled).toBe(true)
    const picker = wrapper.findComponent(PrincipalPicker)
    expect(picker.props('disabled')).toBe(true)
    expect((createSubmit(wrapper).element as HTMLButtonElement).disabled).toBe(true)
    expect(gateway.deletionPrecheck).toHaveBeenCalledTimes(1)
  })
})

async function prepareCreate(wrapper: ReturnType<typeof mount>) {
  await pickTarget(wrapper)
  await wrapper.findAll('form')[0].trigger('submit')
  await flushPromises()
  await wrapper.get('#org-deletion-reason').setValue(reason)
  await wrapper.findAll('form')[1].trigger('submit')
  await flushPromises()
}

/** Drives the org-scope picker the way a real pick does — account facts ride the pick event. */
async function pickTarget(wrapper: ReturnType<typeof mount>) {
  const picker = wrapper.findComponent(PrincipalPicker)
  picker.vm.$emit('pick', {
    principalId: 'p-2', displayName: '林悦', status: 'ACTIVE', kind: 'USER',
    optionSubtitle: 'e2e-member', chipSubtitle: 'e2e-member · 正常', accountId, username: 'e2e-member',
  })
  picker.vm.$emit('update:modelValue', 'p-2')
  await flushPromises()
}

function createSubmit(wrapper: ReturnType<typeof mount>) {
  return wrapper.findAll('button').find(button => button.text().includes('创建删除作业'))!
}

function confirmSubmit(wrapper: ReturnType<typeof mount>) {
  return wrapper.get('.org-confirm')
}

async function mountComponent(gateway: OrganizationGateway) {
  const store = createOrganizationStore(gateway)
  const wrapper = mount(OrganizationDeletionWorkflow, {
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

function clearPrecheck() {
  return { accountId, username: 'e2e-member', displayName: '林悦', inFlightTaskExecutions: 0, activeResponsibilities: 0, openReservations: null }
}

function step(id: string, state: DeletionItem['state']): DeletionItem {
  return {
    id, stepKey: 'TOMBSTONE_IDENTITY', targetRef: 'ACCOUNT', expectedCount: 1, state,
    resultCount: state === 'PENDING' ? null : 0, errorCode: null,
    processedAt: state === 'PENDING' ? null : '2026-10-13T09:00:00Z', version: 2,
  }
}

function deletionJob(status: DeletionJobStatus, items: DeletionItem[]): DeletionJob {
  return {
    id: 'job-d1', organizationId, accountId, targetPrincipalId: 'p-2', reason, status,
    reservationCheck: '{"inFlightTaskExecutions":0,"activeResponsibilities":0,"openReservations":null,"checkedAt":"2026-10-13T08:00:00Z"}',
    backupRetentionStatement: '备份保留声明：每日快照 7 天、每周快照 4 周。',
    version: 1, createdAt: '2026-10-13T08:00:00Z', updatedAt: '2026-10-13T08:00:00Z', items,
  }
}

function fakeGateway(): OrganizationGateway & {
  deletionPrecheck: ReturnType<typeof vi.fn>
  createDeletion: ReturnType<typeof vi.fn>
  confirmDeletion: ReturnType<typeof vi.fn>
  processDeletion: ReturnType<typeof vi.fn>
} {
  return {
    profile: vi.fn(async () => ({ organizationId, name: 'CrewScope Organization', status: 'ACTIVE' as const, version: 1 })),
    settings: vi.fn(async () => ({ auditEventRetentionDays: null, version: 0 })),
    updateSettings: vi.fn(async () => receipt()),
    roleGrants: vi.fn(async () => []),
    grantRole: vi.fn(async () => receipt()),
    revokeRoleGrant: vi.fn(async () => receipt()),
    exportJob: vi.fn(async () => exportJobFixture()),
    createExport: vi.fn(async () => ({ job: exportJobFixture(), receipt: null, replayed: false })),
    processExport: vi.fn(async () => exportJobFixture()),
    cancelExport: vi.fn(async () => exportJobFixture()),
    openExportArtifacts: vi.fn(async () => ({ manifest: null, artifacts: [] })),
    deletionPrecheck: vi.fn(async () => clearPrecheck()),
    createDeletion: vi.fn(async () => ({ job: deletionJob('PENDING_CONFIRMATION', []), receipt: null, replayed: false })),
    deletionJob: vi.fn(async () => deletionJob('PENDING_CONFIRMATION', [])),
    confirmDeletion: vi.fn(async () => deletionJob('CONFIRMED', [])),
    processDeletion: vi.fn(async () => deletionJob('COMPLETED', [])),
    cancelDeletion: vi.fn(async () => deletionJob('CANCELLED', [])),
  }
}

function exportJobFixture() {
  return {
    id: 'job-e1', organizationId, requestedByPrincipalId: 'p-1', status: 'PENDING' as const,
    includeVectors: false, manifest: null, version: 1,
    createdAt: '2026-10-13T08:00:00Z', updatedAt: '2026-10-13T08:00:00Z', items: [],
  }
}

function receipt() {
  return { commandId: 'command-9', domainEventId: 'event-9', committedVersion: 1, correlationId: 'c-9', replayed: false }
}

function apiError(status: number, code: string): CrewScopeApiError {
  return new CrewScopeApiError(status, {
    code, message: 'server message', correlationId: 'c-1', retryable: false, currentVersion: null, details: {},
  })
}
