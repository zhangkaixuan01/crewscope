import { mount } from '@vue/test-utils'
import { fixtureIds } from '../../test/scopeFixtures'
import { fixtureTaskIntent } from '../../test/taskIntentFixtures'
import TaskIntentCard from './TaskIntentCard.vue'

describe('TaskIntentCard', () => {
  it('shows structured responsibility facts and confirms through an explicit review action', async () => {
    const wrapper = mount(TaskIntentCard, { props: { intent: fixtureTaskIntent(), currentPrincipalId: fixtureIds.principal } })

    expect(wrapper.text()).toContain('GitHub Provider')
    expect(wrapper.text()).toContain('关键操作进入审计记录')
    await wrapper.findAll('button').find(button => button.text().includes('预检并确认'))!.trigger('click')
    expect(wrapper.emitted('confirm')).toHaveLength(1)
  })

  it('emits a complete replacement revision and hides review actions from non-owners', async () => {
    const wrapper = mount(TaskIntentCard, { props: { intent: fixtureTaskIntent(), currentPrincipalId: fixtureIds.principal } })
    await wrapper.findAll('button').find(button => button.text().includes('修订'))!.trigger('click')
    await wrapper.get('form').trigger('submit')

    expect(wrapper.emitted('revise')?.[0]?.[0]).toEqual(expect.objectContaining({
      schemaVersion: '1',
      ownerMemberId: expect.any(String),
      acceptanceCriteria: ['能够读取仓库元数据', '关键操作进入审计记录'],
    }))

    await wrapper.setProps({ currentPrincipalId: fixtureIds.secondPrincipal })
    expect(wrapper.text()).toContain('只有提案 Owner')
    expect(wrapper.text()).not.toContain('预检并确认')
  })

  it('requires a bounded rejection reason', async () => {
    const wrapper = mount(TaskIntentCard, { props: { intent: fixtureTaskIntent(), currentPrincipalId: fixtureIds.principal } })
    await wrapper.findAll('button').find(button => button.text().includes('拒绝'))!.trigger('click')
    await wrapper.findAll('button').find(button => button.text().includes('确认拒绝'))!.trigger('click')
    expect(wrapper.get('[role="alert"]').text()).toContain('1–1000')
    await wrapper.get('.reject-form textarea').setValue('目标已经改变')
    await wrapper.findAll('button').find(button => button.text().includes('确认拒绝'))!.trigger('click')
    expect(wrapper.emitted('reject')?.[0]).toEqual(['目标已经改变'])
  })
})
