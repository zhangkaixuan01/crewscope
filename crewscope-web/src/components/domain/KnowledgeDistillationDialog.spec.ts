import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import KnowledgeDistillationDialog from './KnowledgeDistillationDialog.vue'
import type { KnowledgeDistillationReceipt } from '../../domains/knowledge/types'

const executionId = '00000000-0000-0000-0000-000000007201'

describe('KnowledgeDistillationDialog', () => {
  it('validates the execution UUID and entry key before emitting', async () => {
    const wrapper = await mounted()

    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('distill')).toBeUndefined()
    expect(wrapper.text()).toContain('任务执行 ID 需为有效的 UUID')

    await fill(wrapper, { taskExecutionId: 'not-a-uuid', entryKey: 'postmortem-cache' })
    await wrapper.get('form').trigger('submit')
    expect(wrapper.text()).toContain('任务执行 ID 需为有效的 UUID')

    await fill(wrapper, { taskExecutionId: executionId, entryKey: 'Bad Key' })
    await wrapper.get('form').trigger('submit')
    expect(wrapper.text()).toContain('条目 Key 需为小写字母、数字与连字符')
    expect(wrapper.emitted('distill')).toBeUndefined()
  })

  it('emits a distill without category by default and with one when chosen', async () => {
    const wrapper = await mounted()

    await fill(wrapper, { taskExecutionId: executionId, entryKey: 'postmortem-cache' })
    await wrapper.get('form').trigger('submit')
    const [input, key] = wrapper.emitted('distill')![0]! as [{ taskExecutionId: string, entryKey: string, category?: string }, string]
    expect(input).toEqual({ taskExecutionId: executionId, entryKey: 'postmortem-cache' })
    expect(input.category).toBeUndefined()
    expect(key).not.toBe('')

    await wrapper.get('select').setValue('RUNBOOK')
    await wrapper.get('form').trigger('submit')
    expect((wrapper.emitted('distill')![1]![0] as { category?: string }).category).toBe('RUNBOOK')
  })

  it('turns into the first-execution receipt with the authoritative PENDING wording', async () => {
    const wrapper = await mounted({ receipt: receipt() })

    const surface = wrapper.get('section[aria-label="蒸馏回执"]')
    expect(surface.text()).toContain('postmortem-cache')
    expect(surface.text()).toContain(executionId)
    expect(surface.text()).toContain('第 2 次尝试')
    expect(surface.text()).toContain('待索引')
    // The hint is the contract's single authoritative wording — it must never read as unsaved.
    expect(surface.text()).toContain('索引状态与保存状态无关：内容已保存，等待后台建立索引')
    expect(surface.text()).not.toContain('未保存')
    expect(wrapper.text()).not.toContain('幂等重放')

    await wrapper.findAll('button').find(button => button.text() === '打开条目')!.trigger('click')
    expect(wrapper.emitted('openEntry')?.[0]).toEqual(['00000000-0000-0000-0000-000000007101'])
  })

  it('describes an idempotent replay without the open-entry affordance', async () => {
    const wrapper = await mounted({ receipt: receipt({ replayed: true, entryId: null, origin: null, indexStatus: null }) })

    expect(wrapper.text()).toContain('幂等重放')
    expect(wrapper.text()).toContain('postmortem-cache')
    expect(wrapper.text()).not.toContain('打开条目')
    expect(wrapper.text()).not.toContain('执行 00000000')
  })

  it('returns to the form for another distillation', async () => {
    const wrapper = await mounted({ receipt: receipt() })
    expect(wrapper.find('section[aria-label="蒸馏回执"]').exists()).toBe(true)

    await wrapper.findAll('button').find(button => button.text() === '再蒸馏一条')!.trigger('click')

    expect(wrapper.find('section[aria-label="蒸馏回执"]').exists()).toBe(false)
    expect((wrapper.get('input[autocomplete="off"]').element as HTMLInputElement).value).toBe('')
    // The stored receipt prop is still set; the page clears it when closing the dialog.
    await wrapper.get('form').trigger('submit')
    expect(wrapper.text()).toContain('任务执行 ID 需为有效的 UUID')
  })

  it('surfaces the command error inline', async () => {
    const wrapper = await mounted({ errorMessage: '执行不存在或没有产物' })
    expect(wrapper.get('[role="alert"]').text()).toContain('执行不存在或没有产物')
  })
})

async function mounted(overrides: Record<string, unknown> = {}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/knowledge', name: 'knowledge-base', component: { template: '<div />' } }],
  })
  await router.push('/knowledge?team=team-1')
  await router.isReady()
  return mount(KnowledgeDistillationDialog, {
    props: { teamId: 'team-1', submitting: false, errorMessage: null, receipt: null, ...overrides },
    global: { plugins: [router] },
    attachTo: document.body,
  })
}

async function fill(wrapper: ReturnType<typeof mount>, values: { taskExecutionId?: string, entryKey?: string }) {
  const inputs = wrapper.findAll('input[type="text"]')
  if (values.taskExecutionId !== undefined) await inputs[0]!.setValue(values.taskExecutionId)
  if (values.entryKey !== undefined) await inputs[1]!.setValue(values.entryKey)
}

function receipt(overrides: Partial<KnowledgeDistillationReceipt> = {}): KnowledgeDistillationReceipt {
  return {
    commandId: '00000000-0000-0000-0000-000000007301',
    domainEventId: '00000000-0000-0000-0000-000000007302',
    committedVersion: 4,
    correlationId: '00000000-0000-0000-0000-000000007303',
    entryId: '00000000-0000-0000-0000-000000007101',
    entryKey: 'postmortem-cache',
    origin: { taskExecutionId: executionId, attempt: 2 },
    indexStatus: 'PENDING',
    replayed: false,
    ...overrides,
  }
}
