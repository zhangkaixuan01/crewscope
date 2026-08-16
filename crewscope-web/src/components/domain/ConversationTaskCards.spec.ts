import { mount } from '@vue/test-utils'
import { fixtureIds } from '../../test/scopeFixtures'
import { fixtureTasks } from '../../test/taskFixtures'
import type { TaskAssociationSummary } from '../../domains/task/types'
import ConversationTaskCards from './ConversationTaskCards.vue'

describe('ConversationTaskCards', () => {
  it('renders multiple durable Task facts, waiting reason and independent live phases', () => {
    const associations = fixtures()
    const wrapper = mount(ConversationTaskCards, {
      props: {
        phase: 'ready', associations, currentPrincipalId: fixtureIds.principal, errorMessage: null,
        liveTasks: {
          [associations[0]!.task.id]: { phase: 'connected', errorMessage: null, projectionGap: false },
          [associations[1]!.task.id]: { phase: 'reconnecting', errorMessage: '连接中断', projectionGap: true },
        },
      },
    })

    expect(wrapper.findAll('.conversation-task-card')).toHaveLength(2)
    expect(wrapper.text()).toContain('执行中')
    expect(wrapper.text()).toContain('等待中')
    expect(wrapper.text()).toContain('等待原因 · WAITING_APPROVAL')
    expect(wrapper.text()).toContain('实时')
    expect(wrapper.text()).toContain('正在重连')
    expect(wrapper.text()).not.toMatch(/claimToken|taskToken|credential|reasoning/i)
  })

  it('delegates Task and WorkItem navigation with the complete association fact', async () => {
    const association = fixtures()[0]!
    const wrapper = mount(ConversationTaskCards, {
      props: {
        phase: 'ready', associations: [association], currentPrincipalId: fixtureIds.principal,
        errorMessage: null, liveTasks: {},
      },
    })

    const buttons = wrapper.findAll('.conversation-task-card__actions button')
    await buttons[0]!.trigger('click')
    await buttons[1]!.trigger('click')

    expect(wrapper.emitted('openTask')).toEqual([[association]])
    expect(wrapper.emitted('openWorkItem')).toEqual([[association]])
  })

  it('keeps loading and stale-error feedback outside the message list', async () => {
    const loading = mount(ConversationTaskCards, {
      props: { phase: 'loading', associations: [], currentPrincipalId: fixtureIds.principal, errorMessage: null, liveTasks: {} },
    })
    expect(loading.text()).toContain('正在恢复关联 Task')
    expect(loading.find('ol').exists()).toBe(false)

    const association = fixtures()[0]!
    const stale = mount(ConversationTaskCards, {
      props: {
        phase: 'error', associations: [association], currentPrincipalId: fixtureIds.principal,
        errorMessage: '实时事实暂时无法同步', liveTasks: {},
      },
    })
    await stale.get('.conversation-tasks__error button').trigger('click')
    expect(stale.text()).toContain(association.task.objective)
    expect(stale.emitted('retry')).toBeTruthy()
  })
})

function fixtures(): TaskAssociationSummary[] {
  const [first, second] = structuredClone(fixtureTasks[fixtureIds.teamPlatform]!)
  second!.status = 'WAITING'
  second!.currentExecutionStatus = 'WAITING'
  second!.currentWaitingReason = 'WAITING_APPROVAL'
  return [first!, second!].map((task, index) => ({
    origin: index === 0 ? 'CONVERSATION_SOURCE' : 'WORK_ITEM_ROOT',
    associatedAt: task.createdAt,
    task: { ...task, href: `/work?task=${task.id}` },
  }))
}
