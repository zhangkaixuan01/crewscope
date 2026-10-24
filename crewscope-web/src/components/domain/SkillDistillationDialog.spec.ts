import { mount } from '@vue/test-utils'
import SkillDistillationDialog from './SkillDistillationDialog.vue'
import { AUTH_PRINCIPAL } from '../../app/auth'
import { createTaskStore, TASK_STORE } from '../../domains/task/store'
import type { SkillDistillationReceipt } from '../../domains/skill/types'
import { bootstrapPrincipal } from '../../test/authFixtures'
import { FixtureTaskGateway } from '../../test/taskFixtures'

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

  it('enters the picked state directly from a preset execution and generates the key (R14)', async () => {
    const wrapper = mounted({ presetExecution: { taskExecutionId: executionId, objective: '建立团队看板' } })

    const inputs = wrapper.findAll('input[type="text"]')
    expect((inputs[0]!.element as HTMLInputElement).value).toBe(executionId)
    expect((inputs[1]!.element as HTMLInputElement).value).toBe(`skill-${executionId.slice(0, 8)}`)
    expect(wrapper.text()).toContain('建立团队看板')
    expect(wrapper.text()).toContain('将一次任务执行的过程与结论沉淀为团队 Skill 草稿')

    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('distill')![0]![0]).toMatchObject({ taskExecutionId: executionId })
  })
})

function mounted(overrides: Record<string, unknown> = {}) {
  // M13b-F03（A11）：选择器经 TaskStore 的只读快照查询拉最近执行。
  const taskStore = createTaskStore(new FixtureTaskGateway())
  return mount(SkillDistillationDialog, {
    props: { teamId: 'team-1', submitting: false, errorMessage: null, receipt: null, ...overrides },
    attachTo: document.body,
    global: {
      provide: { [TASK_STORE as symbol]: taskStore, [AUTH_PRINCIPAL as symbol]: bootstrapPrincipal },
      stubs: { teleport: true },
    },
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
