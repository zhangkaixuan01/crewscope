import { mount } from '@vue/test-utils'
import PresenceAvatarGroup from './PresenceAvatarGroup.vue'
import type { PresenceEntry } from '../../domains/collaboration/store'

const entries: PresenceEntry[] = [
  { principalId: 'account:alice', displayName: 'Alice', connections: 1 },
  { principalId: 'account:bob', displayName: 'Bob', connections: 2 },
  { principalId: 'account:carol', displayName: 'Carol', connections: 1 },
]

describe('PresenceAvatarGroup', () => {
  it('renders one accessible roster with initials and multi-tab connection badges', () => {
    const wrapper = mount(PresenceAvatarGroup, { props: { entries } })

    expect(wrapper.attributes('aria-label')).toBe('在场：Alice、Bob、Carol')
    expect(wrapper.attributes('role')).toBe('group')
    expect(wrapper.findAll('.base-avatar').map(avatar => avatar.text())).toEqual(['A', 'B', 'C'])
    expect(wrapper.findAll('.presence-group__badge')).toHaveLength(1)
    expect(wrapper.get('.presence-group__badge').text()).toBe('2')
  })

  it('collapses beyond the visible maximum into a +N overflow mark', () => {
    const wrapper = mount(PresenceAvatarGroup, { props: { entries, max: 2 } })

    expect(wrapper.findAll('.base-avatar')).toHaveLength(2)
    expect(wrapper.get('.presence-group__more').text()).toBe('+1')
  })

  it('renders the degraded placeholder instead of stale presence when the channel is off', () => {
    const wrapper = mount(PresenceAvatarGroup, { props: { entries, degraded: true } })

    expect(wrapper.text()).toBe('实时协作暂不可用')
    expect(wrapper.find('.base-avatar').exists()).toBe(false)
  })

  it('renders nothing when nobody else is present', () => {
    const wrapper = mount(PresenceAvatarGroup, { props: { entries: [] } })

    expect(wrapper.text()).toBe('')
    expect(wrapper.find('[role="group"]').exists()).toBe(false)
  })

  // M13b-F05（R36）: hover titles are desktop-only — touch and keyboard reach the full roster
  // through the toggle: every name (overflowed included) and every multi-tab count.
  it('opens the full roster on the toggle and closes it with Escape (R36)', async () => {
    const wrapper = mount(PresenceAvatarGroup, { props: { entries, max: 2 } })

    const toggle = wrapper.get('.presence-group__toggle')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('.presence-group__roster').exists()).toBe(false)

    await toggle.trigger('click')
    expect(toggle.attributes('aria-expanded')).toBe('true')
    const roster = wrapper.get('.presence-group__roster')
    expect(roster.attributes('aria-label')).toBe('在场名单')
    // Three names even though the avatar row only shows two — the +1 overflow is reachable here.
    expect(roster.findAll('.presence-group__roster-name').map(name => name.text())).toEqual(['Alice', 'Bob', 'Carol'])
    expect(roster.text()).toContain('2 个标签页')

    await toggle.trigger('keydown.esc', { key: 'Escape' })
    expect(wrapper.find('.presence-group__roster').exists()).toBe(false)
    expect(toggle.attributes('aria-expanded')).toBe('false')
  })
})
