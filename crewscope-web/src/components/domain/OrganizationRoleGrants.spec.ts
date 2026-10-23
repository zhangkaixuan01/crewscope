import { flushPromises, mount } from '@vue/test-utils'
import { AUTH_PRINCIPAL, permissions, type AuthenticatedPrincipal } from '../../app/auth'
import { AUTH_STORE } from '../../domains/identity/store'
import { fixtureAuthStore } from '../../test/authFixtures'
import { CrewScopeApiError } from '../../api/client'
import type { OrganizationGateway } from '../../domains/organization/gateway'
import { ORGANIZATION_STORE, createOrganizationStore } from '../../domains/organization/store'
import type { DeletionJob, ExportJob, OrganizationRoleGrant } from '../../domains/organization/types'
import OrganizationRoleGrants from './OrganizationRoleGrants.vue'
import PrincipalPicker from './PrincipalPicker.vue'

const organizationId = '00000000-0000-0000-0000-000000000001'

const admin: AuthenticatedPrincipal = {
  id: 'p-admin', accountId: 'a-1', displayName: '组织管理员', role: 'ORG_ADMIN',
  organizationId, organization: 'CrewScope Organization',
  permissions: new Set([permissions.orgAuditRead, permissions.orgManageSettings]),
}
const auditor: AuthenticatedPrincipal = {
  ...admin, id: 'p-auditor', displayName: '组织审计员', role: 'ORG_AUDITOR',
  permissions: new Set([permissions.orgAuditRead]),
}

describe('OrganizationRoleGrants', () => {
  it('renders the grant history with the status filter', async () => {
    const gateway = fakeGateway()
    gateway.roleGrants.mockResolvedValue([grant('g-1', 'ACTIVE'), grant('g-2', 'REVOKED')])
    const { wrapper } = await mountComponent(admin, gateway)

    expect(wrapper.text()).toContain('principal-9')
    expect(wrapper.text()).toContain('组织管理员')
    expect(wrapper.text()).toContain('已撤销')
    expect(wrapper.findAll('tbody tr')).toHaveLength(2)

    await wrapper.findAll('input[name="grant-status"]').find(input => (input.element as HTMLInputElement).value === 'REVOKED')!.setValue()
    expect(wrapper.findAll('tbody tr')).toHaveLength(1)
  })

  it('hides the grant form and revoke buttons from an auditor', async () => {
    const gateway = fakeGateway()
    gateway.roleGrants.mockResolvedValue([grant('g-1', 'ACTIVE')])
    const { wrapper } = await mountComponent(auditor, gateway)

    expect(wrapper.find('form.org-grant-form').exists()).toBe(false)
    expect(wrapper.findAll('button').some(button => button.text().includes('撤销'))).toBe(false)
  })

  it('gates the grant on a directory pick instead of a typed UUID (M13b-F02 R06)', async () => {
    const gateway = fakeGateway()
    gateway.roleGrants.mockResolvedValue([])
    const { wrapper } = await mountComponent(admin, gateway)

    expect((wrapper.get('button[type="submit"]').element as HTMLButtonElement).disabled).toBe(true)
    expect(wrapper.text()).toContain('请先从组织成员目录选择成员')
    expect(wrapper.find('#org-grant-principal').exists()).toBe(false)

    await wrapper.findComponent(PrincipalPicker).vm.$emit('update:modelValue', '00000000-0000-0000-0000-000000000009')
    expect((wrapper.get('button[type="submit"]').element as HTMLButtonElement).disabled).toBe(false)
    await wrapper.get('form.org-grant-form').trigger('submit')
    await flushPromises()

    // The command gateway fingerprints arguments through JSON, which drops undefined-valued
    // keys — an empty optional reason therefore reaches the adapter as an absent key.
    expect(gateway.grantRole).toHaveBeenCalledWith(
      organizationId,
      { principalId: '00000000-0000-0000-0000-000000000009', role: 'ORG_AUDITOR' },
      expect.any(String),
    )
    expect(wrapper.text()).toContain('角色命令已受理，列表已刷新。')
  })

  it('keeps the OWNER option out of reach for a non-owner admin session', async () => {
    const gateway = fakeGateway()
    gateway.roleGrants.mockResolvedValue([])
    const { wrapper } = await mountComponent(admin, gateway, 'ORG_ADMIN')

    expect(wrapper.get('#org-grant-role').find('option[value="ORG_OWNER"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('组织 Owner 角色只能由现任 Owner 授予。')

    const owner = await mountComponent(admin, gateway, 'ORG_OWNER')
    expect(owner.wrapper.get('#org-grant-role').find('option[value="ORG_OWNER"]').exists()).toBe(true)
  })

  it('confirms a revoke through the dialog and explains the last-owner protection on 409', async () => {
    const gateway = fakeGateway()
    gateway.roleGrants.mockResolvedValue([grant('g-1', 'ACTIVE')])
    gateway.revokeRoleGrant.mockRejectedValueOnce(apiError(409, 'last_owner_protection'))
    const { wrapper } = await mountComponent(admin, gateway)

    await wrapper.findAll('button').find(button => button.text().includes('撤销'))!.trigger('click')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
    // M13b-F02 R39: revoking a role is a destructive confirmation — the button says so in danger.
    expect(wrapper.find('[role="dialog"] .base-button--danger').exists()).toBe(true)
    await wrapper.get('.org-revoke-body').trigger('submit')
    await flushPromises()

    expect(gateway.revokeRoleGrant).toHaveBeenCalledWith(organizationId, 'g-1', {}, 2, expect.any(String))
    expect(wrapper.text()).toContain('最后一个组织 Owner 保护')
    expect(wrapper.text()).toContain('至少要保留一名有效 Owner')
  })
})

async function mountComponent(principal: AuthenticatedPrincipal, gateway: OrganizationGateway, sessionRole: 'ORG_ADMIN' | 'ORG_OWNER' = 'ORG_ADMIN') {
  const authStore = fixtureAuthStore(principal)
  const session = {
    phase: 'authenticated' as const, activeTeamId: null, errorCode: null, errorMessage: null,
    session: {
      authenticated: true, registrationMode: 'OPEN' as const,
      csrf: { headerName: 'X-XSRF-TOKEN' as const, parameterName: '_csrf' as const, token: 'csrf-roles' },
      account: null, principal: null,
      organization: { organizationId, name: 'CrewScope Organization', role: sessionRole },
      teams: [], permissions: [],
    },
  }
  ;(authStore as { state: unknown }).state = session
  const store = createOrganizationStore(gateway)
  const wrapper = mount(OrganizationRoleGrants, {
    props: { organizationId },
    global: {
      stubs: { teleport: true },
      provide: {
        [AUTH_PRINCIPAL as symbol]: principal,
        [AUTH_STORE as symbol]: authStore,
        [ORGANIZATION_STORE as symbol]: store,
      },
    },
  })
  await flushPromises()
  await flushPromises()
  return { wrapper, gateway, store }
}

function grant(id: string, status: 'ACTIVE' | 'REVOKED'): OrganizationRoleGrant {
  return {
    id, organizationId, principalId: 'principal-9', role: 'ORG_ADMIN', status,
    grantedByPrincipalId: 'principal-1', grantedAt: '2026-10-13T08:00:00Z',
    revokedAt: status === 'REVOKED' ? '2026-10-13T09:00:00Z' : null,
    revokedByPrincipalId: status === 'REVOKED' ? 'principal-1' : null,
    reason: null, version: 2,
  }
}

function fakeGateway(): OrganizationGateway & {
  roleGrants: ReturnType<typeof vi.fn>
  grantRole: ReturnType<typeof vi.fn>
  revokeRoleGrant: ReturnType<typeof vi.fn>
} {
  return {
    profile: vi.fn(async () => ({ organizationId, name: 'CrewScope Organization', status: 'ACTIVE' as const, version: 1 })),
    settings: vi.fn(async () => ({ auditEventRetentionDays: null, version: 0 })),
    updateSettings: vi.fn(async () => receipt('c-1')),
    roleGrants: vi.fn(async () => []),
    grantRole: vi.fn(async () => receipt('c-2')),
    revokeRoleGrant: vi.fn(async () => receipt('c-3')),
    // The roles tab never exercises governance; the methods exist to satisfy the interface.
    exportJob: vi.fn(async () => exportJobFixture()),
    createExport: vi.fn(async () => ({ job: exportJobFixture(), receipt: null, replayed: false })),
    processExport: vi.fn(async () => exportJobFixture()),
    cancelExport: vi.fn(async () => exportJobFixture()),
    openExportArtifacts: vi.fn(async () => ({ manifest: null, artifacts: [] })),
    deletionPrecheck: vi.fn(async () => ({ accountId: 'account-1', username: 'lin.yue', displayName: '林悦', inFlightTaskExecutions: 0, activeResponsibilities: 0, openReservations: null })),
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

function receipt(correlationId: string) {
  return { commandId: 'command-1', domainEventId: 'event-1', committedVersion: 1, correlationId, replayed: false }
}

function apiError(status: number, code: string): CrewScopeApiError {
  return new CrewScopeApiError(status, {
    code, message: 'server message', correlationId: 'c-1', retryable: false, currentVersion: null, details: {},
  })
}
