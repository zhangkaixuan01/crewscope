import { mount } from '@vue/test-utils'
import ModelCredentialDialog from './ModelCredentialDialog.vue'

describe('ModelCredentialDialog', () => {
  it('keeps the API Key local, reuses an unchanged retry key and clears it on close', async () => {
    const wrapper = mount(ModelCredentialDialog, {
      attachTo: document.body,
      props: {
        mode: 'create', providers: [provider()], connection: null, teamId: 'team-1',
        canManageTeam: true, canManageOrganization: false, submitting: false,
        retryable: true, errorMessage: 'Provider 暂时不可用',
      },
    })
    const secret = wrapper.get<HTMLInputElement>('input[type="password"]')
    await secret.setValue('one-way-secret')
    await wrapper.get('form').trigger('submit')
    await wrapper.get('form').trigger('submit')

    const creates = wrapper.emitted('create')!
    expect(creates).toHaveLength(2)
    expect(creates[0]?.[0]).toMatchObject({ apiKey: 'one-way-secret', ownerType: 'USER' })
    expect(creates[0]?.[1]).toBe(creates[1]?.[1])
    expect(JSON.stringify(wrapper.props())).not.toContain('one-way-secret')
    expect(wrapper.text()).not.toContain('one-way-secret')

    await secret.setValue('replacement-secret')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('create')?.[2]?.[1]).not.toBe(creates[0]?.[1])

    await wrapper.get('button[aria-label="关闭创建模型连接"]').trigger('click')
    expect(secret.element.value).toBe('')
    wrapper.unmount()
  })

  it('fails Team creation closed without Provider manage permission', async () => {
    const wrapper = mount(ModelCredentialDialog, {
      props: {
        mode: 'create', providers: [provider()], connection: null, teamId: 'team-1',
        canManageTeam: false, canManageOrganization: false, submitting: false,
        retryable: false, errorMessage: null,
      },
    })

    const team = wrapper.findAll('button').find(button => button.text().includes('团队连接'))!
    expect(team.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('需要 Team Provider 管理权限')
  })
})

function provider() {
  return {
    key: 'deepseek', displayName: 'DeepSeek', availableRegions: ['cn'], retentionMode: 'NONE',
    maximumRetentionSeconds: null, trainingUsagePolicy: 'DISABLED', status: 'ACTIVE', version: 1,
  }
}
