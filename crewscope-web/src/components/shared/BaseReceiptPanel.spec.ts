import { mount } from '@vue/test-utils'
import BaseReceiptPanel from './BaseReceiptPanel.vue'

/**
 * Shell contract tests (the distillation twins keep their own specs for the fact rows):
 * the replay banner, the open/another actions and the optional hint line.
 */
describe('BaseReceiptPanel', () => {
  it('shows the replay banner only for idempotent replays', () => {
    const replayed = mounted({ replayed: true })
    expect(replayed.get('[role="status"]').text()).toContain('幂等重放')

    const fresh = mounted()
    expect(fresh.find('[role="status"]').exists()).toBe(false)
  })

  it('renders the domain fact rows through the facts slot', () => {
    const wrapper = mounted({ slots: { facts: '<div><dt>条目 Key</dt><dd class="base-receipt__mono">runbook</dd></div>' } })
    expect(wrapper.get('dl').text()).toContain('runbook')
  })

  it('gates the primary open action on openAvailable', async () => {
    const withoutId = mounted()
    expect(withoutId.findAll('button').map(button => button.text())).toEqual(['再蒸馏一条'])

    const wrapper = mounted({ openAvailable: true })
    await wrapper.findAll('button').find(button => button.text() === '打开条目')!.trigger('click')
    expect(wrapper.emitted('open')).toHaveLength(1)
  })

  it('emits another for the follow-up action', async () => {
    const wrapper = mounted()
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('another')).toHaveLength(1)
  })

  it('carries the optional hint line only when provided', () => {
    const hinted = mounted({ hint: '索引完成后条目进入可检索状态。' })
    expect(hinted.text()).toContain('索引完成后条目进入可检索状态。')

    const bare = mounted()
    expect(bare.find('.base-receipt__hint').exists()).toBe(false)
  })
})

function mounted(overrides: Record<string, unknown> = {}) {
  const { slots, ...props } = overrides
  return mount(BaseReceiptPanel, {
    props: {
      label: '蒸馏回执',
      replayed: false,
      replayedNote: '此蒸馏命令此前已确认过，本次为幂等重放，没有重复创建内容。',
      openLabel: '打开条目',
      openAvailable: false,
      ...props,
    },
    slots: slots as Record<string, string> | undefined,
  })
}
