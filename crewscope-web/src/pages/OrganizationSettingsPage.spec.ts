import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { AUTH_PRINCIPAL, permissions, type AuthenticatedPrincipal } from '../app/auth'
import { AUTH_STORE } from '../domains/identity/store'
import { createCrewScopeRouter } from '../app/router'
import { fixtureAuthStore } from '../test/authFixtures'
import { CrewScopeApiError } from '../api/client'
import type { OrganizationGateway } from '../domains/organization/gateway'
import { ORGANIZATION_STORE, createOrganizationStore } from '../domains/organization/store'
import type { DeletionJob, ExportJob } from '../domains/organization/types'
import OrganizationSettingsPage from './OrganizationSettingsPage.vue'

const organizationId = '00000000-0000-0000-0000-000000000001'

/** The four role tiers the page must progressively render (M12-F01 permission matrix). */
const plainMember: AuthenticatedPrincipal = {
  id: 'p-member', accountId: 'a-1', displayName: '普通成员', role: 'Member',
  organizationId, organization: 'CrewScope Organization',
  permissions: new Set([permissions.scopeRead]),
}
const auditor: AuthenticatedPrincipal = {
  ...plainMember, id: 'p-auditor', displayName: '组织审计员', role: 'ORG_AUDITOR',
  permissions: new Set([permissions.orgAuditRead]),
}
const admin: AuthenticatedPrincipal = {
  ...plainMember, id: 'p-admin', displayName: '组织管理员', role: 'ORG_ADMIN',
  permissions: new Set([permissions.orgAuditRead, permissions.orgManageSettings, permissions.orgGovern]),
}

describe('OrganizationSettingsPage', () => {
  it('shows every member the organization card but hides the retention card body without audit-read', async () => {
    const { wrapper } = await mountPage(plainMember)

    expect(wrapper.text()).toContain('组织信息')
    expect(wrapper.text()).toContain('CrewScope Organization')
    expect(wrapper.text()).toContain('审计保留策略由组织管理员配置，当前角色无权查看。')
    expect(wrapper.findAll('button').some(button => button.text().includes('调整策略'))).toBe(false)
  })

  it('lets an auditor read the retention policy without any editing entry', async () => {
    const { wrapper } = await mountPage(auditor)

    expect(wrapper.text()).toContain('当前策略：永久保留')
    expect(wrapper.findAll('button').some(button => button.text().includes('调整策略'))).toBe(false)
  })

  it('renders the forbidden phase instead of a retryable error when settings deny the role', async () => {
    const gateway = fakeGateway()
    gateway.settings.mockRejectedValueOnce(apiError(403, 'capability_missing'))
    const { wrapper } = await mountPage(auditor, gateway)

    expect(wrapper.text()).toContain('需要组织审计角色')
    expect(wrapper.text()).toContain('查看权限说明')
  })

  it('validates the limited retention before submitting and persists on save', async () => {
    const gateway = fakeGateway()
    gateway.settings
      .mockResolvedValueOnce({ auditEventRetentionDays: null, version: 0 })
      .mockResolvedValueOnce({ auditEventRetentionDays: 90, version: 1 })
    const { wrapper } = await mountPage(admin, gateway)

    await wrapper.findAll('button').find(button => button.text().includes('调整策略'))!.trigger('click')
    await wrapper.get('input[value="limited"]').setValue()
    // The day input appears with the limited choice; both an empty field and a sub-30 value
    // keep the submit disabled and the field becomes invalid once submit is attempted.
    expect((wrapper.get('button[type="submit"]').element as HTMLButtonElement).disabled).toBe(true)
    await wrapper.get('#org-retention-days').setValue('20')
    expect((wrapper.get('button[type="submit"]').element as HTMLButtonElement).disabled).toBe(true)
    await wrapper.get('form').trigger('submit')
    expect(wrapper.get('#org-retention-days').attributes('aria-invalid')).toBe('true')
    expect(wrapper.text()).toContain('保留天数至少为 30 天。')

    await wrapper.get('#org-retention-days').setValue('90')
    expect((wrapper.get('button[type="submit"]').element as HTMLButtonElement).disabled).toBe(false)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(gateway.updateSettings).toHaveBeenCalledWith(
      organizationId, { auditEventRetentionDays: 90 }, 0, expect.any(String),
    )
    expect(wrapper.text()).toContain('保留策略已保存。')
    expect(wrapper.text()).toContain('当前策略：保留 90 天')
  })

  it('explains a 409 conflict and re-reads so the next save starts from persisted state', async () => {
    const gateway = fakeGateway()
    gateway.settings
      .mockResolvedValueOnce({ auditEventRetentionDays: null, version: 0 })
      .mockResolvedValueOnce({ auditEventRetentionDays: 30, version: 4 })
    gateway.updateSettings.mockRejectedValueOnce(apiError(409, 'optimistic_lock_conflict'))
    const { wrapper } = await mountPage(admin, gateway)

    await wrapper.findAll('button').find(button => button.text().includes('调整策略'))!.trigger('click')
    await wrapper.get('input[value="limited"]').setValue()
    await wrapper.get('#org-retention-days').setValue('60')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('内容已变化')
    expect(wrapper.text()).toContain('其他操作先一步更新了这份事实')
    // The editor stays open on failure and keeps the typed value; the re-read only moves the
    // version the next save will quote.
    expect((wrapper.get('#org-retention-days').element as HTMLInputElement).value).toBe('60')
    expect(gateway.settings).toHaveBeenCalledTimes(2)
  })

  it('offers the audit-center entry on the general tab', async () => {
    const { wrapper } = await mountPage(admin)

    expect(wrapper.find('a[href="/audit"]').exists()).toBe(true)
  })

  it('renders the roles tab for audit-read holders and gates it for plain members', async () => {
    const { wrapper } = await mountPage(auditor, fakeGateway(), '?tab=roles')
    expect(wrapper.text()).toContain('组织角色')

    const plain = await mountPage(plainMember, fakeGateway(), '?tab=roles')
    expect(plain.wrapper.text()).toContain('需要组织审计角色')
    expect(plain.wrapper.text()).toContain('组织 Owner、组织管理员或组织审计员可以查看与管理组织角色。')
  })

  it('gates the governance tab behind org:govern and the deletion panel behind the owner role', async () => {
    const auditorView = await mountPage(auditor, fakeGateway(), '?tab=governance')
    expect(auditorView.wrapper.text()).toContain('需要治理角色')
    expect(auditorView.wrapper.text()).toContain('仅组织 Owner 与组织管理员可以执行组织级治理（数据导出与账号删除）。')

    // An admin sees the export workflow but the deletion entry stays unrendered.
    const adminView = await mountPage(admin, fakeGateway(), '?tab=governance')
    expect(adminView.wrapper.text()).toContain('组织数据导出')
    expect(adminView.wrapper.text()).toContain('发起数据导出')
    expect(adminView.wrapper.text()).toContain('账号删除仅限组织 Owner 执行')
    expect(adminView.wrapper.text()).not.toContain('第一步：选择目标成员并预检')

    // The owner session renders both workflows.
    const ownerView = await mountPage(admin, fakeGateway(), '?tab=governance', 'ORG_OWNER')
    expect(ownerView.wrapper.text()).toContain('发起数据导出')
    expect(ownerView.wrapper.text()).toContain('第一步：选择目标成员并预检')
    expect(ownerView.wrapper.text()).toContain('治理作业没有列表端点')
  })
})

async function mountPage(
  principal: AuthenticatedPrincipal,
  gateway: OrganizationGateway = fakeGateway(),
  query = '',
  sessionRole: 'ORG_ADMIN' | 'ORG_OWNER' = 'ORG_ADMIN',
) {
  const authStore = fixtureAuthStore(principal)
  if (sessionRole) {
    ;(authStore as { state: unknown }).state = {
      phase: 'authenticated', activeTeamId: null, errorCode: null, errorMessage: null,
      session: {
        authenticated: true, registrationMode: 'OPEN' as const,
        csrf: { headerName: 'X-XSRF-TOKEN' as const, parameterName: '_csrf' as const, token: 'csrf-page' },
        account: null, principal: null,
        organization: { organizationId, name: 'CrewScope Organization', role: sessionRole },
        teams: [], permissions: [],
      },
    }
  }
  const router = createCrewScopeRouter(createMemoryHistory(), authStore)
  const store = createOrganizationStore(gateway)
  await router.push(`/settings/organization${query}`)
  await router.isReady()
  const wrapper = mount(OrganizationSettingsPage, {
    global: {
      plugins: [router],
      provide: {
        [AUTH_PRINCIPAL as symbol]: principal,
        [AUTH_STORE as symbol]: authStore,
        [ORGANIZATION_STORE as symbol]: store,
      },
      stubs: { AppShell: { template: '<main><slot name="actions"/><slot/></main>' } },
    },
  })
  await flushPromises()
  await flushPromises()
  return { wrapper, gateway, store, router }
}

function fakeGateway(): OrganizationGateway & {
  settings: ReturnType<typeof vi.fn>
  updateSettings: ReturnType<typeof vi.fn>
  roleGrants: ReturnType<typeof vi.fn>
} {
  return {
    profile: vi.fn(async () => ({ organizationId, name: 'CrewScope Organization', status: 'ACTIVE' as const, version: 1 })),
    settings: vi.fn(async () => ({ auditEventRetentionDays: null, version: 0 })),
    updateSettings: vi.fn(async () => ({
      commandId: 'command-1', domainEventId: 'event-1', committedVersion: 1,
      correlationId: 'c-1', replayed: false,
    })),
    roleGrants: vi.fn(async () => []),
    grantRole: vi.fn(async () => ({
      commandId: 'command-2', domainEventId: 'event-2', committedVersion: 1,
      correlationId: 'c-2', replayed: false,
    })),
    revokeRoleGrant: vi.fn(async () => ({
      commandId: 'command-3', domainEventId: 'event-3', committedVersion: 2,
      correlationId: 'c-3', replayed: false,
    })),
    // The page-level governance tests only assert entry visibility; the workflows'
    // own specs exercise these methods.
    exportJob: vi.fn(async () => exportJobFixture()),
    createExport: vi.fn(async () => ({ job: exportJobFixture(), receipt: null, replayed: false })),
    processExport: vi.fn(async () => exportJobFixture()),
    cancelExport: vi.fn(async () => exportJobFixture()),
    openExportArtifacts: vi.fn(async () => ({ manifest: null, artifacts: [] })),
    deletionPrecheck: vi.fn(async () => ({ accountId: 'account-1', username: 'e2e-member', displayName: '林悦', inFlightTaskExecutions: 0, activeResponsibilities: 0, openReservations: null })),
    createDeletion: vi.fn(async () => ({ job: deletionJobFixture(), receipt: null, replayed: false })),
    deletionJob: vi.fn(async () => deletionJobFixture()),
    confirmDeletion: vi.fn(async () => deletionJobFixture()),
    processDeletion: vi.fn(async () => deletionJobFixture()),
    cancelDeletion: vi.fn(async () => deletionJobFixture()),
  }
}

function exportJobFixture(): ExportJob {
  return {
    id: 'job-e1', organizationId, requestedByPrincipalId: 'p-1', status: 'PENDING',
    includeVectors: false, manifest: null, version: 1,
    createdAt: '2026-10-13T08:00:00Z', updatedAt: '2026-10-13T08:00:00Z', items: [],
  }
}

function deletionJobFixture(): DeletionJob {
  return {
    id: 'job-d1', organizationId, accountId: 'account-1', targetPrincipalId: 'p-2',
    reason: '离职账号清理，含满足最短长度要求的完整说明。',
    status: 'PENDING_CONFIRMATION',
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
