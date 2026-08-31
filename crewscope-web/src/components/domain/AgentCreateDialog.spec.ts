import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import type { AgentTemplateSummary } from '../../domains/agent/types'
import AgentCreateDialog from './AgentCreateDialog.vue'

describe('AgentCreateDialog', () => {
  it('submits only the public Template coordinate, ownership and display name', async () => {
    const wrapper = mount(AgentCreateDialog, {
      attachTo: document.body,
      props: defaults({ userTemplates: [template('coding-specialist', 'USER')] }),
    })
    await wrapper.get('input[placeholder*="Java Coding Agent"]').setValue('  我的 Java Agent  ')
    await wrapper.get('form').trigger('submit')

    expect(wrapper.emitted('submit')?.[0]?.[0]).toEqual({
      publisherType: 'ORGANIZATION',
      templateKey: 'coding-specialist',
      templateVersion: 3,
      ownershipType: 'USER',
      displayName: '我的 Java Agent',
    })
    expect(JSON.stringify(wrapper.emitted('submit'))).not.toMatch(/systemPrompt|toolPayload|apiKey|principalId|workspaceId/)
    wrapper.unmount()
  })

  it('fails closed for Team ownership without permission and traps Escape at the top dialog', async () => {
    const wrapper = mount(AgentCreateDialog, {
      attachTo: document.body,
      props: defaults({
        userTemplates: [template('coding-specialist', 'USER')],
        teamTemplates: [template('team-orchestrator', 'TEAM')],
        canManageTeamAgents: false,
        initialOwnershipType: 'TEAM',
      }),
    })
    const personalButton = wrapper.findAll('.ownership-picker button').find(button => button.text().includes('个人 Agent'))!
    const teamButton = wrapper.findAll('.ownership-picker button').find(button => button.text().includes('团队 Agent'))!
    expect(teamButton.attributes('disabled')).toBeDefined()
    expect(personalButton.classes()).toContain('active')
    await wrapper.get('[role="dialog"]').trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
  })

  it('opens on Team ownership from the dedicated Agent Center entry', () => {
    const wrapper = mount(AgentCreateDialog, {
      props: defaults({
        initialOwnershipType: 'TEAM',
        teamTemplates: [template('team-coordinator', 'TEAM')],
      }),
    })
    const ownershipButtons = wrapper.findAll('.ownership-picker button')
    const personalButton = ownershipButtons.find(button => button.text().includes('个人 Agent'))!
    const teamButton = ownershipButtons.find(button => button.text().includes('团队 Agent'))!

    expect(teamButton.classes()).toContain('active')
    expect(personalButton.classes()).not.toContain('active')
    expect(wrapper.get('.template-picker').text()).toContain('team-coordinator')
  })

  it('keeps input editable after a retryable failure so a changed request gets a new idempotency identity', async () => {
    const wrapper = mount(AgentCreateDialog, {
      props: defaults({
        userTemplates: [template('reviewer-specialist', 'USER')],
        retryable: true,
        errorMessage: '创建暂时失败',
      }),
    })
    const input = wrapper.get('input[placeholder*="Java Coding Agent"]')
    expect(input.attributes('disabled')).toBeUndefined()
    await input.setValue('Reviewer Agent')
    await nextTick()
    expect(wrapper.text()).toContain('使用原请求重试')
  })

  it('distinguishes a failed Template catalog from a valid empty catalog', async () => {
    const wrapper = mount(AgentCreateDialog, {
      props: defaults({ templateErrorMessage: 'Template Catalog 暂时不可用' }),
    })
    expect(wrapper.text()).toContain('批准模板暂时不可用')
    expect(wrapper.text()).not.toContain('没有可用模板')
    await wrapper.get('.state-panel button').trigger('click')
    expect(wrapper.emitted('retryTemplates')).toHaveLength(1)
  })

  it('hides the platform-managed Personal Assistant from the create choices', async () => {
    const wrapper = mount(AgentCreateDialog, {
      props: defaults({
        userTemplates: [
          { ...template('personal-assistant', 'USER'), runtimeRole: 'PERSONAL_ASSISTANT' },
          template('coding-specialist', 'USER'),
        ],
      }),
    })
    expect(wrapper.get('.template-picker').text()).toContain('coding-specialist')
    expect(wrapper.get('.template-picker').text()).not.toContain('personal-assistant')
  })
})

function defaults(overrides: Partial<InstanceType<typeof AgentCreateDialog>['$props']> = {}) {
  return {
    userTemplates: [], teamTemplates: [], loading: false, canManageTeamAgents: true,
    submitting: false, retryable: false, errorMessage: null, templateErrorMessage: null, ...overrides,
  }
}

function template(key: string, ownership: 'USER' | 'TEAM'): AgentTemplateSummary {
  return {
    publisherType: 'ORGANIZATION', publisherId: '00000000-0000-0000-0000-000000000001',
    key, version: 3, runtimeRole: 'SPECIALIST', allowedOwnershipTypes: [ownership],
    allowedExecutionScopes: ownership === 'USER' ? ['PERSONAL'] : ['TEAM'],
    declaredCapabilities: ['coding'], requiredModelCapabilities: ['TOOLS'],
    approvedSkillKeys: ['coding-baseline'], memberConfigurableSlots: ['MODEL_BINDING'],
    administratorConfigurableSlots: [], contentHash: 'a'.repeat(64), status: 'ACTIVE', lifecycleVersion: 1,
  }
}
