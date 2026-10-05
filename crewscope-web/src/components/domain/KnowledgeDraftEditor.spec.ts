import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import KnowledgeDraftEditor from './KnowledgeDraftEditor.vue'
import {
  KNOWLEDGE_CONTENT_MAX,
  KNOWLEDGE_TITLE_MAX,
  type KnowledgeEntrySummary,
  type UpdateKnowledgeDraftInput,
} from '../../domains/knowledge/types'

describe('KnowledgeDraftEditor', () => {
  it('prefills from the head draft and emits a save with category', async () => {
    const wrapper = await mounted({ entry: entry({ draft: { title: '部署手册', content: '先排干连接池。' } }) })

    expect((wrapper.get('input[type="text"]').element as HTMLInputElement).value).toBe('部署手册')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('save')?.[0]?.[0]).toEqual({
      title: '部署手册', content: '先排干连接池。', category: 'RUNBOOK',
    } satisfies UpdateKnowledgeDraftInput)
  })

  it('blocks saving with a spoken reason for blank titles and over-limit fields', async () => {
    const wrapper = await mounted({ entry: entry({ draft: { title: '', content: 'x' } }) })
    const save = wrapper.get('button[type="submit"]')

    expect(save.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('标题不能为空')

    const long = '长'.repeat(KNOWLEDGE_TITLE_MAX + 1)
    await wrapper.get('input[type="text"]').setValue(long)
    expect(wrapper.text()).toContain(`标题超出 ${KNOWLEDGE_TITLE_MAX} 字上限`)

    await wrapper.get('input[type="text"]').setValue('标题')
    await wrapper.get('textarea').setValue('文'.repeat(KNOWLEDGE_CONTENT_MAX + 1))
    expect(wrapper.text()).toContain(`正文超出 ${KNOWLEDGE_CONTENT_MAX} 字上限`)
    expect(save.attributes('disabled')).toBeDefined()
  })

  it('renders a read-only rest state without a draft and starts one on demand', async () => {
    const wrapper = await mounted({ entry: entry({ draft: null }) })

    expect(wrapper.text()).toContain('当前没有未保存的草稿')
    expect(wrapper.find('form').exists()).toBe(false)

    await wrapper.get('button').trigger('click')
    expect(wrapper.find('form').exists()).toBe(true)
    await wrapper.get('input[type="text"]').setValue('新标题')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('save')?.[0]?.[0]).toMatchObject({ title: '新标题', content: '', category: 'RUNBOOK' })
  })

  it('resets the form when the selected entry changes', async () => {
    const wrapper = await mounted({ entry: entry({ draft: { title: '旧草稿', content: '旧内容' } }) })

    await wrapper.setProps({ entry: entry({ id: 'entry-2', entryKey: 'release-notes', draft: { title: '新草稿', content: '新内容' } }) })

    expect((wrapper.get('input[type="text"]').element as HTMLInputElement).value).toBe('新草稿')
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('新内容')
  })

  it('keeps local edits when a 409 reload brings a different server draft (D4 layer three)', async () => {
    const wrapper = await mounted({ entry: entry({ draft: { title: '服务端旧草稿', content: '旧' } }) })
    await wrapper.get('input[type="text"]').setValue('成员正在编辑的标题')
    await wrapper.get('textarea').setValue('成员正在编辑的正文')

    await wrapper.setProps({ entry: entry({ draft: { title: '冲突后的服务端草稿', content: '新' }, version: 7 }) })

    expect((wrapper.get('input[type="text"]').element as HTMLInputElement).value).toBe('成员正在编辑的标题')
    expect(wrapper.vm.isDirty).toBe(true)
  })

  it('marks the form clean again when a refreshed head confirms the saved draft', async () => {
    const wrapper = await mounted({ entry: entry({ draft: { title: '草稿', content: '内容' } }) })
    await wrapper.get('input[type="text"]').setValue('修订后的草稿')
    expect(wrapper.vm.isDirty).toBe(true)

    await wrapper.setProps({ entry: entry({ draft: { title: '修订后的草稿', content: '内容' }, version: 4 }) })

    expect(wrapper.vm.isDirty).toBe(false)
  })

  it('overwrites the form from the head draft only through the exposed applyServerDraft', async () => {
    const wrapper = await mounted({ entry: entry({ draft: { title: '服务端', content: '服务端内容' } }) })
    await wrapper.get('input[type="text"]').setValue('本地未保存修改')

    wrapper.vm.applyServerDraft()
    await wrapper.vm.$nextTick()

    expect((wrapper.get('input[type="text"]').element as HTMLInputElement).value).toBe('服务端')
  })

  it('keeps the read-only rest state for readers without knowledge:manage', async () => {
    const wrapper = await mounted({ canManage: false, entry: entry({ draft: null }) })

    expect(wrapper.find('form').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('开始新草稿')
    expect(wrapper.text()).not.toContain('保存草稿')
  })
})

async function mounted(overrides: Record<string, unknown>) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/knowledge', name: 'knowledge-base', component: { template: '<div />' } }],
  })
  await router.push('/knowledge?team=team-1')
  await router.isReady()
  return mount(KnowledgeDraftEditor, {
    props: {
      entry: entry(),
      scope: { accountId: 'account-1', principalId: 'principal-1', organizationId: 'org-1', teamId: 'team-1' },
      canManage: true,
      saving: false,
      ...overrides,
    },
    global: { plugins: [router] },
  })
}

function entry(overrides: Partial<KnowledgeEntrySummary> = {}): KnowledgeEntrySummary {
  return {
    id: 'entry-1', entryKey: 'deploy-runbook', category: 'RUNBOOK', status: 'PUBLISHED', indexStatus: 'INDEXED',
    effectiveRevision: 2, latestRevision: 2, draft: { title: '草稿', content: '内容' }, version: 3,
    createdAt: '2026-10-01T01:00:00Z', updatedAt: '2026-10-02T01:00:00Z',
    createdBy: 'principal-1', updatedBy: 'principal-1', origin: null,
    ...overrides,
  }
}
