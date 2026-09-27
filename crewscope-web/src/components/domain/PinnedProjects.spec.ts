import { mount } from '@vue/test-utils'
import { activateF05Identity, clearF05UserData } from '../../app/f05Storage'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import PinnedProjects from './PinnedProjects.vue'
import { readPinnedProjects, type SavedViewOwner } from '../../domains/views/savedViews'

const owner: SavedViewOwner = { accountId: 'account-1', principalId: 'principal-1', organizationId: 'org-1', teamId: 'team-1' }
const projects = [
  { id: 'project-1', key: 'CRW', name: 'CrewScope' },
  { id: 'project-2', key: 'OPS', name: 'Operations' },
]

beforeEach(() => {
  activateF05Identity('account-1')
})

afterEach(() => {
  clearF05UserData()
  localStorage.clear()
  sessionStorage.clear()
})

function mountRow(activeProjectId: string | null = 'project-1') {
  return mount(PinnedProjects, { props: { owner, projects, activeProjectId } })
}

describe('PinnedProjects', () => {
  it('pins the focused project, opens it and unpins it again', async () => {
    const wrapper = mountRow()

    await wrapper.get('button[aria-label="固定当前项目 CrewScope"]').trigger('click')
    expect(wrapper.text()).toContain('CRW · CrewScope')
    expect(readPinnedProjects(owner).map(pin => pin.projectId)).toEqual(['project-1'])

    await wrapper.get('.pinned-projects__open').trigger('click')
    expect(wrapper.emitted('select')).toEqual([['project-1']])

    await wrapper.get('button[aria-label="取消固定 CrewScope"]').trigger('click')
    expect(readPinnedProjects(owner)).toEqual([])
    expect(wrapper.text()).not.toContain('CRW · CrewScope')
  })

  it('resolves names through the current authorization, never from the pin itself', async () => {
    const wrapper = mountRow()
    await wrapper.get('button[aria-label="固定当前项目 CrewScope"]').trigger('click')
    // The authorization stops returning the project (revoked or archived).
    await wrapper.setProps({ projects: [projects[1]!] })

    expect(wrapper.text()).toContain('项目已不可见')
    expect(wrapper.text()).not.toContain('CrewScope')

    await wrapper.get('button[aria-label="取消固定 已不可见的项目"]').trigger('click')
    expect(readPinnedProjects(owner)).toEqual([])
  })

  it('reorders by hand through the stored order', async () => {
    const wrapper = mountRow('project-1')
    await wrapper.get('button[aria-label="固定当前项目 CrewScope"]').trigger('click')
    await wrapper.setProps({ activeProjectId: 'project-2' })
    await wrapper.get('button[aria-label="固定当前项目 Operations"]').trigger('click')
    // Pinning puts the newest first; the member's hand is the authority after that.
    expect(wrapper.findAll('.pinned-projects__open').map(chip => chip.text())).toEqual(['OPS · Operations', 'CRW · CrewScope'])

    const chips = wrapper.findAll('li')
    await chips[0]!.trigger('dragstart')
    await chips[1]!.trigger('dragover')
    await chips[1]!.trigger('drop')

    expect(wrapper.findAll('.pinned-projects__open').map(chip => chip.text())).toEqual(['CRW · CrewScope', 'OPS · Operations'])
    expect(readPinnedProjects(owner).map(pin => pin.projectId)).toEqual(['project-1', 'project-2'])
  })

  it('offers nothing without a focused project or a signed-in owner', () => {
    const all = mountRow(null)
    expect(all.find('.pinned-projects__pin').exists()).toBe(false)

    const signedOut = mount(PinnedProjects, { props: { owner: null, projects, activeProjectId: 'project-1' } })
    expect(signedOut.find('ul').exists()).toBe(false)
  })
})
