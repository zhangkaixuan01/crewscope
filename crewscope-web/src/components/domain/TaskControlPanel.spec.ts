import { flushPromises, mount } from '@vue/test-utils'
import { execution } from '../../test/taskFixtures'
import type { TaskExecution } from '../../domains/task/types'
import TaskControlPanel from './TaskControlPanel.vue'

describe('TaskControlPanel', () => {
  it('explains Pause impact, validates the reason and submits only server command coordinates', async () => {
    const onCommand = vi.fn().mockResolvedValue(undefined)
    const wrapper = mount(TaskControlPanel, { props: props({ onCommand }) })

    expect(wrapper.find('[aria-label="暂停当前 Task"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="取消当前 Task"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="恢复当前 Task"]').exists()).toBe(false)
    await wrapper.get('[aria-label="暂停当前 Task"]').trigger('click')
    expect(wrapper.get('[role="dialog"]').text()).toContain('AgentScope 的下一个安全点')

    await wrapper.get('form').trigger('submit')
    expect(wrapper.text()).toContain('请输入 1–500 个不含控制字符的原因')
    expect(onCommand).not.toHaveBeenCalled()
    await wrapper.get('textarea').setValue('等待团队审查\u0007')
    await wrapper.get('form').trigger('submit')
    expect(onCommand).not.toHaveBeenCalled()
    await wrapper.get('textarea').setValue('等待团队审查')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(onCommand).toHaveBeenCalledWith('PAUSE', '等待团队审查')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  })

  it('discovers Resume and allows Retry to pin an explicitly switched configuration', async () => {
    const paused = executionWith({ status: 'PAUSED' })
    const wrapper = mount(TaskControlPanel, { props: props({ attempt: paused }) })
    expect(wrapper.find('[aria-label="恢复当前 Task"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="暂停当前 Task"]').exists()).toBe(false)

    const failed = executionWith({
      status: 'FAILED',
      terminal: {
        status: 'FAILED', decidedByPrincipalId: crypto.randomUUID(), decidedAt: '2026-08-16T01:00:00Z',
        failureClass: 'TRANSIENT', failureCode: 'WORKER_LOST',
      },
    })
    const onCommand = vi.fn().mockResolvedValue(undefined)
    await wrapper.setProps({ attempt: failed, onCommand })
    await wrapper.get('[aria-label="重试当前 Task"]').trigger('click')
    expect(wrapper.get('[role="dialog"]').text()).toContain('留空会沿用父 attempt 固定配置')
    await wrapper.get('input[type="number"]').setValue('4')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(onCommand).toHaveBeenCalledWith('RETRY', undefined, 4)

    failed.terminal!.failureClass = 'VALIDATION'
    await wrapper.setProps({ attempt: structuredClone(failed) })
    expect(wrapper.find('[aria-label="重试当前 Task"]').exists()).toBe(false)
  })

  it('hides command controls from read-only members and disables submission while offline', async () => {
    const readOnly = mount(TaskControlPanel, { props: props({ canControl: false }) })
    expect(readOnly.text()).toContain('没有这个 Task 的 Owner 或 Executor 控制责任')
    expect(readOnly.findAll('.task-control-actions button')).toHaveLength(0)

    const offline = mount(TaskControlPanel, { props: props({ online: false }) })
    expect(offline.text()).toContain('当前离线')
    expect(offline.get('[aria-label="暂停当前 Task"]').attributes('disabled')).toBeDefined()
  })

  it('shows refreshed conflict facts and retries the exact failed command when online', async () => {
    const onRetry = vi.fn().mockResolvedValue(undefined)
    const wrapper = mount(TaskControlPanel, {
      props: props({
        errorMessage: 'Task 已被其他执行者更新',
        retryable: true,
        versionConflict: { operation: 'PAUSE', attemptedVersion: 2, currentVersion: 3 },
        onRetry,
      }),
    })

    expect(wrapper.text()).toContain('服务端当前版本为 v3')
    await wrapper.get('.task-control-error button').trigger('click')
    expect(onRetry).toHaveBeenCalledTimes(1)
  })

  it('restores focus to the Cancel trigger when confirmation is dismissed', async () => {
    const wrapper = mount(TaskControlPanel, { attachTo: document.body, props: props() })
    const trigger = wrapper.get<HTMLButtonElement>('[aria-label="取消当前 Task"]')
    await trigger.trigger('click')
    await wrapper.get('[aria-label="关闭 Task 控制确认"]').trigger('click')
    await flushPromises()

    expect(document.activeElement).toBe(trigger.element)
    wrapper.unmount()
  })
})

function props(overrides: Record<string, unknown> = {}) {
  return {
    attempt: execution(),
    canControl: true,
    online: true,
    pending: null,
    errorMessage: null,
    retryable: false,
    versionConflict: null,
    onCommand: vi.fn().mockResolvedValue(undefined),
    onRetry: vi.fn().mockResolvedValue(undefined),
    onClearFeedback: vi.fn(),
    ...overrides,
  }
}

function executionWith(overrides: Partial<TaskExecution>): TaskExecution {
  return { ...execution(), ...overrides }
}
