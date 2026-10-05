import { mount } from '@vue/test-utils'
import SkillActionConfirmDialog, { type SkillActionIntent } from './SkillActionConfirmDialog.vue'

describe('SkillActionConfirmDialog', () => {
  it('explains the unconditional disclosure scan before publishing', async () => {
    const wrapper = mounted({ kind: 'publish', skillKey: 'deploy-runbook-v2', nextRevision: 2 })

    expect(wrapper.get('[aria-describedby="skill-confirm-impact"]').text()).toContain('披露扫描')
    expect(wrapper.text()).toContain('r2')

    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('confirm')?.[0]).toEqual([null])
  })

  it('offers an optional disable reason and forwards it trimmed', async () => {
    const wrapper = mounted({ kind: 'disable', skillKey: 'deploy-runbook-v2' })

    expect(wrapper.text()).toContain('禁用即下线')
    expect(wrapper.text()).toContain('没有删除操作')

    await wrapper.get('textarea').setValue('  弃用，改用 v3  ')
    await wrapper.get('form').trigger('submit')

    expect(wrapper.emitted('confirm')?.[0]).toEqual(['弃用，改用 v3'])
  })

  it('sends a null reason when the disable reason is left blank', async () => {
    const wrapper = mounted({ kind: 'disable', skillKey: 'deploy-runbook-v2' })

    await wrapper.get('form').trigger('submit')

    expect(wrapper.emitted('confirm')?.[0]).toEqual([null])
  })

  it('blocks an over-limit disable reason', async () => {
    const wrapper = mounted({ kind: 'disable', skillKey: 'deploy-runbook-v2' })

    await wrapper.get('textarea').setValue('长'.repeat(201))
    await wrapper.get('form').trigger('submit')

    expect(wrapper.emitted('confirm')).toBeUndefined()
  })

  it('explains that a rollback appends an identical-hash revision, not a deletion', async () => {
    const wrapper = mounted({ kind: 'rollback', skillKey: 'deploy-runbook-v2', toRevision: 1, effectiveRevision: 3 })

    expect(wrapper.text()).toContain('回滚不是删除')
    expect(wrapper.text()).toContain('相邻修订同哈希属正常形态')

    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('confirm')?.[0]).toEqual([null])
  })

  it('emits cancel on Escape through the topmost-modal guard', async () => {
    const wrapper = mounted({ kind: 'publish', skillKey: 'k', nextRevision: 2 })

    await wrapper.get('form').trigger('keydown', { key: 'Escape' })

    expect(wrapper.emitted('cancel')).toHaveLength(1)
  })
})

function mounted(intent: SkillActionIntent) {
  return mount(SkillActionConfirmDialog, {
    props: { intent, submitting: false, errorMessage: null },
    attachTo: document.body,
  })
}
