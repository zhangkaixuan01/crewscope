import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import KnowledgeCreateDialog from './KnowledgeCreateDialog.vue'

describe('KnowledgeCreateDialog', () => {
  it('validates the entry key, title and content before emitting', async () => {
    const wrapper = await mounted()

    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('create')).toBeUndefined()
    expect(wrapper.text()).toContain('条目 Key 需为小写字母、数字与连字符')
    expect(wrapper.text()).toContain('标题不能为空。')

    await fill(wrapper, { entryKey: 'Deploy Runbook', title: '部署手册' })
    await wrapper.get('form').trigger('submit')
    expect(wrapper.text()).toContain('条目 Key 需为小写字母、数字与连字符')
    expect(wrapper.emitted('create')).toBeUndefined()
  })

  it('emits a create with a fresh idempotency key per logical input', async () => {
    const wrapper = await mounted()

    await fill(wrapper, { entryKey: 'deploy-runbook', title: '部署手册', content: '先排干连接池。' })
    await wrapper.get('form').trigger('submit')
    const first = wrapper.emitted('create')![0]!
    expect(first[0]).toEqual({ entryKey: 'deploy-runbook', category: 'CONVENTION', title: '部署手册', content: '先排干连接池。' })
    expect(typeof first[1]).toBe('string')
    expect(first[1]).not.toBe('')

    await wrapper.get('select').setValue('RUNBOOK')
    await wrapper.get('form').trigger('submit')
    const second = wrapper.emitted('create')![1]!
    expect(second[0]).toMatchObject({ category: 'RUNBOOK' })
    expect(second[1]).not.toBe(first[1])
  })

  it('surfaces the command error without losing the form', async () => {
    const wrapper = await mounted({ errorMessage: 'entryKey 已存在' })

    expect(wrapper.get('[role="alert"]').text()).toContain('entryKey 已存在')
    expect((wrapper.get('input[autocomplete="off"]').element as HTMLInputElement).disabled).toBe(false)
  })

  it('closes on Escape while clean and keeps the guard while dirty', async () => {
    const clean = await mounted()
    await clean.get('form').trigger('keydown', { key: 'Escape' })
    expect(clean.emitted('close')).toHaveLength(1)

    const dirty = await mounted()
    await fill(dirty, { entryKey: 'deploy-runbook', title: '部署手册' })
    await dirty.get('form').trigger('keydown', { key: 'Escape' })
    expect(dirty.emitted('close')).toBeUndefined()
  })
})

async function mounted(overrides: Record<string, unknown> = {}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/knowledge', name: 'knowledge-base', component: { template: '<div />' } }],
  })
  await router.push('/knowledge?team=team-1')
  await router.isReady()
  return mount(KnowledgeCreateDialog, {
    props: { teamId: 'team-1', submitting: false, errorMessage: null, ...overrides },
    global: { plugins: [router] },
    attachTo: document.body,
  })
}

async function fill(wrapper: ReturnType<typeof mount>, values: { entryKey?: string, title?: string, content?: string }) {
  const inputs = wrapper.findAll('input[type="text"]')
  if (values.entryKey !== undefined) await inputs[0]!.setValue(values.entryKey)
  if (values.title !== undefined) await inputs[1]!.setValue(values.title)
  if (values.content !== undefined) await wrapper.get('textarea').setValue(values.content)
}
