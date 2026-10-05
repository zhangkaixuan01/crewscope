import { mount } from '@vue/test-utils'
import SkillDraftEditor from './SkillDraftEditor.vue'
import type { SkillDraftScope } from './SkillDraftEditor.vue'
import type { SkillSummary } from '../../domains/skill/types'

const scope: SkillDraftScope = { accountId: 'account-1', principalId: 'principal-1', organizationId: 'org-1', teamId: 'team-1' }

describe('SkillDraftEditor', () => {
  it('shows the rest state without a draft and the DISABLED revival path wording', async () => {
    const published = mounted({ skill: skill({ status: 'PUBLISHED', draft: null }) })
    expect(published.text()).toContain('当前没有未保存的草稿')
    expect(published.text()).toContain('已发布的内容全部生效')

    const disabled = mounted({ skill: skill({ status: 'DISABLED', draft: null }) })
    expect(disabled.text()).toContain('此 Skill 已禁用')
    expect(disabled.text()).toContain('修改草稿并再次发布即可产出新修订、恢复生效')

    await disabled.get('button').trigger('click')
    expect(disabled.find('textarea').exists()).toBe(true)
  })

  it('seeds a new draft with a frontmatter template whose name equals the skillKey', async () => {
    const wrapper = mounted({ skill: skill({ draft: null }) })

    await wrapper.get('button').trigger('click')

    const textarea = wrapper.get('textarea')
    expect((textarea.element as HTMLTextAreaElement).value).toContain('name: deploy-runbook-v2')
    expect((textarea.element as HTMLTextAreaElement).value).toContain('---')
  })

  it('blocks saving on a document contract violation with the contract-ordered wording', () => {
    const drifted = `---\nname: other-key\ndescription: d\n---\n\nbody`
    const wrapper = mounted({ skill: skill({ draft: { name: 'deploy-runbook-v2', description: 'd', content: drifted } }) })

    expect(wrapper.text()).toContain('frontmatter 的 name 必须与 skillKey 完全一致')
    const submit = wrapper.get('form button[type="submit"]')
    expect(submit.attributes('disabled')).toBeDefined()
  })

  it('previews the derived frontmatter fields beside the editor', () => {
    const content = `---\nname: deploy-runbook-v2\ndescription: 部署手册\n---\n\nbody`
    const wrapper = mounted({ skill: skill({ draft: { name: 'deploy-runbook-v2', description: '部署手册', content } }) })

    const preview = wrapper.get('.draft-editor__preview')
    expect(preview.text()).toContain('deploy-runbook-v2')
    expect(preview.text()).toContain('部署手册')
  })

  it('renders the diff against the baseline once the document is valid and changed', () => {
    const wrapper = mounted({
      skill: skill({ draft: { name: 'deploy-runbook-v2', description: 'd', content: document('draft') } }),
      baselineContent: document('effective'),
    })

    expect(wrapper.text()).toContain('未保存草稿')
    expect(wrapper.text()).toContain('已保存草稿')

    const identical = mounted({
      skill: skill({ draft: { name: 'deploy-runbook-v2', description: 'd', content: document('same') } }),
      baselineContent: document('same'),
    })
    expect(identical.text()).not.toContain('未保存草稿')
  })

  it('locks the editor for members without skill:manage and ties the reason to the control', () => {
    const wrapper = mounted({ skill: skill({}), canManage: false })

    expect(wrapper.get('textarea').attributes('disabled')).toBeDefined()
    expect(wrapper.get('#skill-1-draft-readonly').text()).toContain('需要 Skill 管理权限')
  })

  it('exposes applyServerDraft so the conflict panel can reload without clobbering', async () => {
    const wrapper = mounted({ skill: skill({}) })
    await wrapper.get('textarea').setValue(document('local edit'))

    wrapper.vm.$.exposed?.applyServerDraft()
    await wrapper.vm.$nextTick()

    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe(document('draft'))
  })
})

function mounted(overrides: Record<string, unknown>) {
  return mount(SkillDraftEditor, {
    props: {
      skill: skill({}),
      scope,
      canManage: true,
      saving: false,
      baselineContent: document('effective'),
      ...overrides,
    },
  })
}

function skill(overrides: Partial<SkillSummary>): SkillSummary {
  return {
    id: 'skill-1', skillKey: 'deploy-runbook-v2', status: 'PUBLISHED',
    effectiveRevision: 1, latestRevision: 1,
    draft: { name: 'deploy-runbook-v2', description: 'd', content: document('draft') },
    disableReason: null, version: 3,
    createdAt: '2026-10-01T01:00:00Z', updatedAt: '2026-10-02T01:00:00Z',
    createdBy: 'principal-1', updatedBy: 'principal-1', origin: null,
    ...overrides,
  }
}

function document(marker: string) {
  return `---\nname: deploy-runbook-v2\ndescription: Marker ${marker}.\n---\n\nBody ${marker}.`
}
