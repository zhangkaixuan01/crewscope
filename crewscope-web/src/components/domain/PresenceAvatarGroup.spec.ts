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
})
