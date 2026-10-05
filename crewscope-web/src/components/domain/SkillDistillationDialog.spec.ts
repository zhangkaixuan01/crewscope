import { mount } from '@vue/test-utils'
import SkillDistillationDialog from './SkillDistillationDialog.vue'
import type { SkillDistillationReceipt } from '../../domains/skill/types'

const executionId = '00000000-0000-0000-0000-000000008201'

describe('SkillDistillationDialog', () => {
  it('validates the execution UUID and the skillKey pattern before submitting', async () => {
    const wrapper = mounted()

    await wrapper.get('form').trigger('submit')

    expect(wrapper.text()).toContain('任务执行 ID 需为有效的 UUID')
    expect(wrapper.emitted('distill')).toBeUndefined()
  })

  it('states that only the execution creator may distill', () => {
    const wrapper = mounted()

    expect(wrapper.text()).toContain('仅任务执行创建者本人可提炼该执行')
  })

  it('emits the distill command with the trimmed execution id', async () => {
    const wrapper = mounted()
    await wrapper.findAll('input')[0]!.setValue(` ${executionId} `)
    await wrapper.findAll('input')[1]!.setValue('postmortem-cache')

    await wrapper.get('form').trigger('submit')

    const [input, key] = wrapper.emitted('distill')![0]! as [{ taskExecutionId: string, skillKey: string }, string]
    expect(input).toEqual({ taskExecutionId: executionId, skillKey: 'postmortem-cache' })
    expect(typeof key).toBe('string')
  })

  it('turns into the receipt surface for a first execution', async () => {
    const wrapper = mounted()
    await wrapper.setProps({ receipt: receipt({ skillId: 'skill-9' }) })

    expect(wrapper.text()).toContain('postmortem-cache')
    expect(wrapper.text()).toContain('执行 00000000-0000-0000-0000-000000008201')
    expect(wrapper.text()).toContain('草稿')
    expect(wrapper.text()).toContain('打开 Skill')

    await wrapper.findAll('button').find(button => button.text() === '打开 Skill')!.trigger('click')
    expect(wrapper.emitted('openSkill')?.[0]).toEqual(['skill-9'])
  })

  it('hides the open-skill jump on an idempotent replay but keeps the skillKey', async () => {
    const wrapper = mounted()
    await wrapper.setProps({ receipt: receipt({ skillId: null, origin: null, replayed: true }) })

    expect(wrapper.text()).toContain('幂等重放')
    expect(wrapper.text()).toContain('postmortem-cache')
    expect(wrapper.text()).not.toContain('打开 Skill')
  })

  it('returns to the form on “再蒸馏一条”', async () => {
    const wrapper = mounted()
    await wrapper.setProps({ receipt: receipt({}) })

    await wrapper.findAll('button').find(button => button.text() === '再蒸馏一条')!.trigger('click')

    expect(wrapper.text()).toContain('任务执行 ID')
    expect(wrapper.find('input').exists()).toBe(true)
  })
})

function mounted() {
  return mount(SkillDistillationDialog, {
    props: { teamId: 'team-1', submitting: false, errorMessage: null, receipt: null },
    attachTo: document.body,
  })
}

function receipt(overrides: Partial<SkillDistillationReceipt>): SkillDistillationReceipt {
  return {
    commandId: '00000000-0000-0000-0000-000000008301',
    domainEventId: '00000000-0000-0000-0000-000000008302',
    committedVersion: 0,
    correlationId: '00000000-0000-0000-0000-000000008303',
    skillId: 'skill-1',
    skillKey: 'postmortem-cache',
    status: 'DRAFT',
    origin: { taskExecutionId: '00000000-0000-0000-0000-000000008201', attempt: 2 },
    replayed: false,
    ...overrides,
  }
}
