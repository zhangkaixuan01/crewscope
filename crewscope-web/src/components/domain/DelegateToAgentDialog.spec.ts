import { flushPromises, mount, type MountingOptions } from '@vue/test-utils'
import { defineComponent, onMounted } from 'vue'
import { fixtureResponsibilities, fixtureWorkItemDetails } from '../../test/workItemFixtures'
import { fixtureIds } from '../../test/scopeFixtures'
import DelegateToAgentDialog from './DelegateToAgentDialog.vue'

describe('DelegateToAgentDialog', () => {
  it('previews responsibility and submits the selected server-authored AgentProfile identity', async () => {
    const responsibilities = structuredClone(fixtureResponsibilities)
    responsibilities[1] = {
      ...responsibilities[1]!,
      actorPrincipalId: '00000000-0000-0000-0000-000000000201',
      actorType: 'PERSONAL_AGENT',
      actorMemberId: null,
      actorDisplayName: '张凯旋的 Personal Agent',
      actorAgentProfileId: '00000000-0000-0000-0000-000000000301',
    }
    const onSubmit = vi.fn().mockResolvedValue(undefined)
    const conversationSource = { conversationId: crypto.randomUUID(), messageId: crypto.randomUUID() }
    const wrapper = mountDialog({ responsibilities, onSubmit, conversationSource })

    expect(wrapper.text()).toContain('Owner · 张凯旋')
    expect(wrapper.text()).toContain('Executor · 张凯旋的 Personal Agent')
    expect(wrapper.text()).toContain('来源保留为当前 Conversation 消息')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({
      objective: fixtureWorkItemDetails.workItem.title,
      executorAgentProfileId: '00000000-0000-0000-0000-000000000301',
      providerBindingIds: [],
      conversationSource,
      codingTarget: {
        repositoryBindingId: '00000000-0000-4000-8000-00000000f302',
        baselineRef: 'main',
        allowedPaths: ['.'],
        buildProfile: { key: 'maven-java-17', version: 1, profileHash: 'a'.repeat(64) },
      },
    }))
  })

  it('fails closed without an Agent Executor and offers exact-request retry after failure', async () => {
    const unavailable = mountDialog()
    expect(unavailable.text()).toContain('请先在责任链中分配 Agent')
    expect(unavailable.get('button[type="submit"]').attributes('disabled')).toBeDefined()

    const onRetry = vi.fn().mockResolvedValue(undefined)
    const retry = mountDialog({ retryable: true, errorMessage: '网络中断', onRetry })
    const retryButton = retry.findAll('button').find(button => button.text().includes('使用原请求重试'))!
    await retryButton.trigger('click')
    expect(retry.text()).toContain('网络中断')
    expect(onRetry).toHaveBeenCalled()
  })

  it('keeps keyboard focus inside the topmost delegation Modal and closes only that layer', async () => {
    const responsibilities = structuredClone(fixtureResponsibilities)
    responsibilities[1] = {
      ...responsibilities[1]!,
      actorType: 'PERSONAL_AGENT',
      actorMemberId: null,
      actorAgentProfileId: crypto.randomUUID(),
    }
    const wrapper = mountDialog({ responsibilities }, { attachTo: document.body })
    await flushPromises()

    expect(document.activeElement).toBe(wrapper.get('input').element)
    const controls = wrapper.get('form').findAll('button:not(:disabled), input:not(:disabled), textarea:not(:disabled)')
    const first = controls[0]!.element as HTMLElement
    const last = controls.at(-1)!.element as HTMLElement
    last.focus()
    await wrapper.get('form').trigger('keydown', { key: 'Tab' })
    expect(document.activeElement).toBe(first)
    await wrapper.get('form').trigger('keydown', { key: 'Tab', shiftKey: true })
    expect(document.activeElement).toBe(last)

    await wrapper.get('form').trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
  })

  it('focuses the Modal itself when delegation has no eligible Agent Executor', async () => {
    const wrapper = mountDialog({}, { attachTo: document.body })
    await flushPromises()

    expect(document.activeElement).toBe(wrapper.get('[role="dialog"]').element)
    wrapper.unmount()
  })
})

function props(overrides: Record<string, unknown> = {}) {
  return {
    workItem: structuredClone(fixtureWorkItemDetails.workItem),
    codingScope: {
      organizationId: fixtureIds.organization,
      teamId: fixtureIds.teamPlatform,
      projectId: fixtureIds.projectCrewScope,
    },
    responsibilities: structuredClone(fixtureResponsibilities),
    submitting: false,
    retryable: false,
    errorMessage: null,
    onSubmit: vi.fn().mockResolvedValue(undefined),
    onRetry: vi.fn().mockResolvedValue(undefined),
    ...overrides,
  }
}

function mountDialog(
  overrides: Record<string, unknown> = {},
  options: Pick<MountingOptions<typeof DelegateToAgentDialog>, 'attachTo'> = {},
) {
  return mount(DelegateToAgentDialog, {
    ...options,
    props: props(overrides),
    global: {
      stubs: {
        CodingTargetFormSection: defineComponent({
          emits: ['change'],
          setup(_, { emit }) {
            onMounted(() => emit('change', {
              repositoryBindingId: '00000000-0000-4000-8000-00000000f302',
              baselineRef: 'main',
              allowedPaths: ['.'],
              buildProfile: { key: 'maven-java-17', version: 1, profileHash: 'a'.repeat(64) },
            }, true))
            return {}
          },
          template: '<div data-test="coding-target-stub" />',
        }),
      },
    },
  })
}
