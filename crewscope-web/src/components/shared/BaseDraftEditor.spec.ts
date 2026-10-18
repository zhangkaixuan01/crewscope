import { mount } from '@vue/test-utils'
import BaseDraftEditor from './BaseDraftEditor.vue'

/**
 * Shell contract tests: the domain twins keep their own specs for field sets and copy.
 * Here we pin the injection seams — permission gate, newDraft seed, fromServer restore and
 * the round-trip confirmation through isSynced.
 */
interface DraftForm {
  content: string
}

function mounted(overrides: Record<string, unknown> = {}) {
  return mount(BaseDraftEditor, {
    props: {
      serverDraft: { content: 'a' },
      entryId: 'entry-1',
      scope: { accountId: 'acc', principalId: 'p', organizationId: 'o', teamId: 't' },
      canManage: true,
      saving: false,
      managePermissionReason: '需要管理权限',
      emptyDescription: '静态说明',
      newDraft: () => ({ content: 'seeded' }),
      fromServer: (draft: DraftForm | null) => ({ content: draft?.content ?? '' }),
      isSynced: (draft: DraftForm, form: DraftForm) => draft.content === form.content,
      validate: () => null,
      ...overrides,
    },
    slots: {
      fields: '<input data-probe v-model="snapshot.content" />',
    },
  })
}

describe('BaseDraftEditor', () => {
  it('shows the rest state without a server draft and seeds a new draft through newDraft', async () => {
    const wrapper = mounted({ serverDraft: null })
    expect(wrapper.text()).toContain('当前没有未保存的草稿')
    expect(wrapper.text()).toContain('静态说明')

    await wrapper.findAll('button').filter(b => b.text() === '开始新草稿')[0]!.trigger('click')
    expect((wrapper.get('input[data-probe]').element as HTMLInputElement).value).toBe('seeded')
  })

  it('edits an existing server draft through the fields slot snapshot', () => {
    const wrapper = mounted({ serverDraft: { content: '服务器草稿' } })
    expect((wrapper.get('input[data-probe]').element as HTMLInputElement).value).toBe('服务器草稿')
  })

  it('gates saving on the domain validate function and the permission copy', () => {
    const blocked = mounted({ serverDraft: { content: '' }, validate: () => '正文不能为空' })
    expect(blocked.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    expect(blocked.text()).toContain('正文不能为空')

    const readonly = mounted({ canManage: false })
    expect(readonly.text()).toContain('需要管理权限')
    expect(readonly.get('button[type="submit"]').attributes('disabled')).toBeDefined()
  })

  it('marks the round trip clean when isSynced confirms the refreshed server draft', async () => {
    const wrapper = mounted()
    await wrapper.get('input[data-probe]').setValue('b')
    expect(wrapper.vm.isDirty).toBe(true)

    await wrapper.setProps({ serverDraft: { content: 'b' } })
    expect(wrapper.vm.isDirty).toBe(false)
  })

  it('restores the form from the head draft through the exposed applyServerDraft', async () => {
    const wrapper = mounted()
    await wrapper.get('input[data-probe]').setValue('本地修改')
    wrapper.vm.applyServerDraft()
    await wrapper.vm.$nextTick()
    expect((wrapper.get('input[data-probe]').element as HTMLInputElement).value).toBe('a')
  })
})
