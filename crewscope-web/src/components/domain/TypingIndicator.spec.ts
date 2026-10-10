import { mount } from '@vue/test-utils'
import TypingIndicator from './TypingIndicator.vue'
import type { TypingEntry } from '../../domains/collaboration/store'

describe('TypingIndicator', () => {
  it('announces one typer politely with their display name', () => {
    const wrapper = mount(TypingIndicator, {
      props: { typers: [{ principalId: 'account:bob', displayName: 'Bob' }] },
    })

    expect(wrapper.attributes('aria-live')).toBe('polite')
    expect(wrapper.text()).toContain('Bob 正在输入…')
    expect(wrapper.find('.typing-indicator__dots').attributes('aria-hidden')).toBe('true')
  })

  it('names two typers explicitly and collapses three or more to a count', () => {
    const two = mount(TypingIndicator, {
      props: {
        typers: [
          { principalId: 'account:bob', displayName: 'Bob' },
          { principalId: 'account:carol', displayName: 'Carol' },
        ],
      },
    })
    const many = mount(TypingIndicator, {
      props: {
        typers: ['Bob', 'Carol', 'Dave'].map(name => ({
          principalId: `account:${name.toLowerCase()}`,
          displayName: name,
        })),
      },
    })

    expect(two.text()).toBe('Bob 和 Carol 正在输入…')
    expect(many.text()).toBe('3 人正在输入…')
  })

  it('renders nothing without typers and the degraded placeholder when the channel is off', () => {
    const empty = mount(TypingIndicator, { props: { typers: [] } })
    const degraded = mount(TypingIndicator, {
      props: { typers: [{ principalId: 'account:bob', displayName: 'Bob' }], degraded: true },
    })

    expect(empty.text()).toBe('')
    expect(empty.find('[aria-live]').exists()).toBe(false)
    expect(degraded.text()).toBe('实时协作暂不可用')
  })
})
