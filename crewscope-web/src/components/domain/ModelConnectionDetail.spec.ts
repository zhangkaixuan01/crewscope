import { mount } from '@vue/test-utils'
import type { ModelCommandState, ModelResource } from '../../domains/model/store'
import type { ModelConnectionSummary } from '../../domains/model/types'
import type { Etagged } from '../../domains/settings/types'
import ModelConnectionDetail from './ModelConnectionDetail.vue'

describe('ModelConnectionDetail', () => {
  it('shows sanitized health facts and keeps Team members read-only', () => {
    const wrapper = mount(ModelConnectionDetail, {
      props: { resource: ready({ healthStatus: 'UNHEALTHY', healthFailureCode: 'AUTHENTICATION_FAILED', consecutiveFailures: 2 }), canManage: false, command: idleCommand() },
    })

    expect(wrapper.text()).toContain('身份验证失败')
    expect(wrapper.text()).toContain('Provider 原始错误保持在服务端边界内')
    expect(wrapper.text()).toContain('需要 Provider Manager 权限')
    expect(wrapper.findAll('button').some(button => button.text().includes('轮换凭证'))).toBe(false)
    expect(wrapper.text()).not.toContain('provider-stack')
  })

  it('requires explicit confirmation and emits only a stable revocation reason', async () => {
    const wrapper = mount(ModelConnectionDetail, {
      attachTo: document.body,
      props: { resource: ready(), canManage: true, command: idleCommand() },
    })
    const trigger = wrapper.findAll('button').find(button => button.text() === '永久撤销')!
    await trigger.trigger('click')
    expect(document.activeElement).toBe(wrapper.get('[role="alertdialog"]').element)
    const confirm = wrapper.findAll('button').find(button => button.text() === '确认永久撤销')!
    expect(confirm.attributes('disabled')).toBeDefined()
    await wrapper.get('select').setValue('SECURITY_INCIDENT')
    await wrapper.get('input[type="checkbox"]').setValue(true)
    await confirm.trigger('click')

    expect(wrapper.emitted('revoke')?.[0]).toEqual(['connection-1', 'SECURITY_INCIDENT'])
    await wrapper.get('[role="alertdialog"]').trigger('keydown', { key: 'Escape' })
    expect(trigger.element).toBe(document.activeElement)
    wrapper.unmount()
  })
})

function ready(overrides: Partial<ModelConnectionSummary> = {}): ModelResource<Etagged<ModelConnectionSummary>> {
  return { phase: 'ready', value: { value: connection(overrides), etag: '"4"' }, errorMessage: null, errorStatus: null }
}

function connection(overrides: Partial<ModelConnectionSummary> = {}): ModelConnectionSummary {
  return {
    id: 'connection-1', organizationId: 'organization-1', providerKey: 'deepseek', ownerType: 'TEAM',
    ownerId: 'team-1', region: 'cn', billingSubjectType: 'TEAM', billingSubjectId: 'team-1',
    credentialVersion: 2, status: 'ACTIVE', healthStatus: 'HEALTHY', healthFailureCode: null,
    checkedAt: '2026-08-25T01:00:00Z', lastHealthyAt: '2026-08-25T01:00:00Z',
    consecutiveFailures: 0, revocationReason: null, createdAt: '2026-08-24T01:00:00Z',
    updatedAt: '2026-08-25T01:00:00Z', version: 4, ...overrides,
  }
}

function idleCommand(): ModelCommandState {
  return { phase: 'idle', operation: null, connectionId: null, receipt: null, errorMessage: null, errorStatus: null, retryable: false }
}
