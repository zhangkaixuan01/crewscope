import { flushPromises, mount } from '@vue/test-utils'
import LarkNotificationAdmin from './LarkNotificationAdmin.vue'
import type { TeamOpsCommandState } from '../../domains/teamops/store'
import type { LarkConnection, NotificationDelivery } from '../../domains/teamops/types'

const connection: LarkConnection = {
  connectionId: uuid(1), teamId: uuid(2), providerBindingId: uuid(3), providerBindingVersion: 6, maskedAppId: '****9x2k',
  status: 'ACTIVE', credentialStatus: 'ACTIVE', expiresAt: null,
  createdAt: '2026-08-27T01:00:00Z', updatedAt: '2026-08-27T02:00:00Z', version: 4,
}
const failedDelivery: NotificationDelivery = {
  organizationId: uuid(4), teamId: uuid(2), deliveryId: uuid(5), recipientMemberId: uuid(6),
  itemType: 'REVIEW', template: { templateId: uuid(7), version: 1 }, providerBindingId: uuid(3),
  status: 'FAILED_FINAL', attemptCount: 3, failureCode: 'RETRY_EXHAUSTED', evidenceCode: 'LARK_RETRY_EXHAUSTED',
  redeliveryOf: null, createdAt: '2026-08-27T01:00:00Z', updatedAt: '2026-08-27T02:00:00Z', version: 3,
}

describe('LarkNotificationAdmin', () => {
  it('keeps credentials in the one-way dialog event and erases fields after success', async () => {
    const wrapper = mountAdmin({ connections: [], selectedConnection: null })
    await wrapper.findAll('button').find(button => button.text().includes('创建连接'))!.trigger('click')
    const dialog = wrapper.get('[role="dialog"]')
    const inputs = dialog.findAll('input')
    await inputs[0]!.setValue('tenant-key')
    await inputs[1]!.setValue('app-id')
    await inputs[2]!.setValue('one-way-secret')
    await dialog.get('form').trigger('submit')

    const input = wrapper.emitted('createConnection')?.[0]?.[0] as Record<string, unknown>
    expect(input).toEqual({ tenantKey: 'tenant-key', appId: 'app-id', appSecret: 'one-way-secret', expiresAt: null })
    expect(wrapper.html()).toContain('type="password"')

    await wrapper.setProps({ command: command('success', 'lark-create') })
    await flushPromises()
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(wrapper.html()).not.toContain('one-way-secret')
  })

  it('closes the credential dialog with Escape and restores focus to its opener', async () => {
    const wrapper = mountAdmin({ connections: [], selectedConnection: null }, true)
    const opener = wrapper.findAll('button').find(button => button.text().includes('创建连接'))!
    ;(opener.element as HTMLButtonElement).focus()
    await opener.trigger('click')
    expect(document.activeElement).toBe(wrapper.get('#credential-title').element)

    await wrapper.get('[role="dialog"]').trigger('keydown', { key: 'Escape' })
    await flushPromises()
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(document.activeElement).toBe(opener.element)
    wrapper.unmount()
  })

  it('erases exact open_id after verification and carries only the Receipt proof into confirmation', async () => {
    const wrapper = mountAdmin({ selectedTab: 'mapping' })
    const selects = wrapper.findAll('select')
    await selects[0]!.setValue(uuid(6))
    const exactInput = wrapper.get('input[type="password"]')
    await exactInput.setValue('ou_exact_identity')
    await wrapper.get('.mapping-steps form').trigger('submit')
    expect(wrapper.emitted('verifyMember')?.[0]?.slice(0, 4)).toEqual([uuid(3), 6, uuid(6), 'ou_exact_identity'])

    await wrapper.setProps({ command: command('success', 'lark-member-verify', uuid(8)) })
    await flushPromises()
    expect((exactInput.element as HTMLInputElement).value).toBe('')
    expect(wrapper.html()).not.toContain('ou_exact_identity')
    await wrapper.findAll('button').find(button => button.text().includes('确认映射'))!.trigger('click')
    expect(wrapper.emitted('confirmMapping')?.[0]?.slice(0, 3)).toEqual([uuid(6), uuid(3), uuid(8)])
  })

  it('fails closed when a connection has no Provider Binding strong version', async () => {
    const incomplete = { ...connection, providerBindingVersion: null }
    const wrapper = mountAdmin({ selectedTab: 'mapping', connections: [incomplete], selectedConnection: incomplete })
    await wrapper.get('input[type="password"]').setValue('ou_exact_identity')

    const verify = wrapper.findAll('button').find(button => button.text().includes('验证身份'))!
    expect(verify.attributes('disabled')).toBeDefined()
    await wrapper.get('.mapping-steps form').trigger('submit')
    expect(wrapper.emitted('verifyMember')).toBeUndefined()
  })

  it('allows explicit redelivery only for FAILED_FINAL and disables commands offline', async () => {
    const wrapper = mountAdmin({ selectedTab: 'notification', deliveries: [failedDelivery], selectedDelivery: failedDelivery })
    const redeliver = wrapper.findAll('button').find(button => button.text().includes('再次投递'))!
    expect(redeliver.attributes('disabled')).toBeUndefined()
    await redeliver.trigger('click')
    expect(wrapper.emitted('redeliver')?.[0]?.[0]).toBe(failedDelivery.deliveryId)

    await wrapper.setProps({ online: false })
    expect(wrapper.findAll('button').find(button => button.text().includes('再次投递'))!.attributes('disabled')).toBeDefined()
  })

  it('renders authoritative conflict and cursor-expired facts without discarding loaded rows', () => {
    const wrapper = mountAdmin({
      selectedTab: 'mapping', mappings: [{
        mappingId: uuid(9), memberId: uuid(6), providerBindingId: uuid(3), status: 'ACTIVE', terminalReason: null,
        verifiedAt: '2026-08-27T01:00:00Z', updatedAt: '2026-08-27T02:00:00Z', version: 2,
      }],
      mappingPhase: 'error', mappingError: { kind: 'cursor-expired', message: 'Cursor expired', status: 410, retryable: false, currentVersion: null },
      command: { phase: 'conflict', operation: 'lark-mapping-revoke', targetId: uuid(9), receipt: null, error: { kind: 'conflict', message: 'Conflict', status: 409, retryable: false, currentVersion: 3 } },
    })
    expect(wrapper.text()).toContain('服务端当前版本 v3')
    expect(wrapper.text()).toContain('ACTIVE')
  })

  it('emits connection selection, health, preflight, rotation and revocation coordinates', async () => {
    const wrapper = mountAdmin()
    await wrapper.get('.connection-row').trigger('click')
    expect(wrapper.emitted('selectConnection')?.[0]).toEqual([connection.connectionId])

    const buttons = wrapper.findAll('button')
    await buttons.find(button => button.text().includes('Preflight'))!.trigger('click')
    await buttons.find(button => button.text().includes('健康检查'))!.trigger('click')
    expect(wrapper.emitted('preflight')?.[0]).toEqual([connection.providerBindingId, connection.providerBindingVersion])
    expect(wrapper.emitted('health')?.[0]).toEqual([connection.providerBindingId])

    await buttons.find(button => button.text().includes('轮换凭证'))!.trigger('click')
    const rotateInputs = wrapper.get('[role="dialog"]').findAll('input')
    await rotateInputs[0]!.setValue('rotated-app')
    await rotateInputs[1]!.setValue('rotated-secret')
    await wrapper.get('[role="dialog"] form').trigger('submit')
    expect(wrapper.emitted('rotateConnection')?.[0]?.slice(0, 2)).toEqual([
      connection.connectionId, { appId: 'rotated-app', appSecret: 'rotated-secret' },
    ])

    await wrapper.setProps({ command: command('idle') })
    await wrapper.get('[aria-label="关闭凭证对话框"]').trigger('click')
    await wrapper.get('.danger-zone input').setValue('TEAM_PROVIDER_REPLACED')
    await wrapper.get('.danger-zone').trigger('submit')
    expect(wrapper.emitted('revokeConnection')?.[0]?.slice(0, 2)).toEqual([connection.connectionId, 'TEAM_PROVIDER_REPLACED'])
  })

  it('filters, pages and revokes member mappings through explicit events', async () => {
    const mapping = {
      mappingId: uuid(9), memberId: uuid(6), providerBindingId: uuid(3), status: 'ACTIVE' as const,
      terminalReason: null, verifiedAt: '2026-08-27T01:00:00Z', updatedAt: '2026-08-27T02:00:00Z', version: 2,
    }
    const wrapper = mountAdmin({ selectedTab: 'mapping', mappings: [mapping], mappingNextCursor: 'next' })
    await wrapper.get('select[aria-label="成员映射状态"]').setValue('ACTIVE')
    await wrapper.get('[aria-label="成员映射历史"] header form').trigger('submit')
    expect(wrapper.emitted('mappingFilter')?.[0]).toEqual(['ACTIVE'])

    await wrapper.findAll('button').find(button => button.text() === '撤销')!.trigger('click')
    expect(wrapper.emitted('revokeMapping')?.[0]?.slice(0, 3)).toEqual([mapping.mappingId, 2, 'ADMIN_REVOKED'])
    await wrapper.findAll('button').find(button => button.text().includes('加载更多'))!.trigger('click')
    expect(wrapper.emitted('loadMoreMappings')).toHaveLength(1)

    await wrapper.setProps({ online: false })
    expect(wrapper.text()).toContain('正在展示最近同步的成员映射')
    expect(wrapper.findAll('button').find(button => button.text().includes('加载更多'))!.attributes('disabled')).toBeDefined()
  })

  it('saves notification preferences and drives delivery filters, paging and detail close', async () => {
    const wrapper = mountAdmin({
      selectedTab: 'notification', deliveries: [failedDelivery], selectedDelivery: failedDelivery,
      deliveryNextCursor: 'next', templates: [{ templateId: uuid(7), version: 1, status: 'PUBLISHED' }],
      preference: { memberId: uuid(6), enabled: true, enabledItemTypes: ['REVIEW'], mutedUntil: '2026-08-28T08:00:00Z', version: 1 },
    })
    const itemTypes = wrapper.findAll('.preference fieldset input')
    await itemTypes.find(input => (input.element as HTMLInputElement).parentElement?.textContent?.includes('OWNERSHIP'))!.setValue(true)
    await wrapper.get('.preference form').trigger('submit')
    expect(wrapper.emitted('savePreference')?.[0]?.[0]).toBe(uuid(6))
    expect(wrapper.emitted('savePreference')?.[0]?.[1]).toMatchObject({ enabled: true, enabledItemTypes: ['REVIEW', 'OWNERSHIP'] })

    await wrapper.get('select[aria-label="投递状态"]').setValue('FAILED_FINAL')
    await wrapper.get('select[aria-label="通知类型"]').setValue('REVIEW')
    await wrapper.get('input[aria-label="Recipient Member UUID"]').setValue(`  ${uuid(6)}  `)
    await wrapper.get('.delivery-filter').trigger('submit')
    expect(wrapper.emitted('deliveryFilter')?.[0]?.[0]).toEqual({ status: 'FAILED_FINAL', itemType: 'REVIEW', recipient: uuid(6) })

    await wrapper.get('.delivery-row').trigger('click')
    expect(wrapper.emitted('selectDelivery')?.[0]).toEqual([failedDelivery.deliveryId])
    await wrapper.findAll('button').find(button => button.text().includes('加载更多'))!.trigger('click')
    expect(wrapper.emitted('loadMoreDeliveries')).toHaveLength(1)
    await wrapper.get('button[aria-label="关闭投递详情"]').trigger('click')
    expect(wrapper.emitted('closeDelivery')).toHaveLength(1)
  })

  it('traps credential-dialog focus and refuses dismissal while a command is pending', async () => {
    const wrapper = mountAdmin({ connections: [], selectedConnection: null }, true)
    await wrapper.findAll('button').find(button => button.text().includes('创建连接'))!.trigger('click')
    const dialog = wrapper.get('[role="dialog"]')
    const close = dialog.get('button[aria-label="关闭凭证对话框"]')
    ;(close.element as HTMLElement).focus()
    await dialog.trigger('keydown', { key: 'Tab', shiftKey: true })
    expect(document.activeElement).toBe(dialog.findAll('button').at(-1)!.element)

    ;(dialog.findAll('button').at(-1)!.element as HTMLElement).focus()
    await dialog.trigger('keydown', { key: 'Tab' })
    expect(document.activeElement).toBe(close.element)

    await wrapper.setProps({ command: command('pending', 'lark-create') })
    await dialog.trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
    wrapper.unmount()
  })
})

function mountAdmin(overrides: Record<string, unknown> = {}, attachToDocument = false) {
  return mount(LarkNotificationAdmin, { props: {
    phase: 'ready', error: null, connections: [connection], selectedConnection: connection,
    health: { status: 'HEALTHY', retryable: false, retryAfterSeconds: null, evidenceCode: 'LARK_PROVIDER_HEALTHY', checkedAt: '2026-08-27T02:00:00Z' },
    mappings: [], mappingPhase: 'ready', mappingError: null, mappingNextCursor: null, mappingLoadingMore: false,
    members: [{ id: uuid(6), userPrincipalId: uuid(10), displayName: 'Zhang Kaixuan', status: 'ACTIVE', joinMethod: 'INVITED', joinedAt: '2026-08-27T01:00:00Z', version: 0 }], currentMemberId: uuid(6),
    templates: [], preference: { memberId: uuid(6), enabled: true, enabledItemTypes: ['REVIEW'], mutedUntil: null, version: 1 },
    deliveries: [], deliveryPhase: 'ready', deliveryError: null, deliveryNextCursor: null, deliveryLoadingMore: false,
    selectedDelivery: null, command: command('idle'), online: true, selectedTab: 'connection', mappingStatus: null,
    deliveryStatus: null, deliveryType: null, recipient: '', ...overrides,
  } as never, attachTo: attachToDocument ? document.body : undefined })
}

function command(phase: TeamOpsCommandState['phase'], operation: string | null = null, domainEventId = uuid(8)): TeamOpsCommandState {
  return {
    phase, operation, targetId: null, error: null,
    receipt: phase === 'success' ? { commandId: uuid(11), domainEventId, committedVersion: 1, correlationId: uuid(12) } : null,
  }
}
function uuid(index: number): string { return `00000000-0000-4000-8000-${String(index).padStart(12, '0')}` }
