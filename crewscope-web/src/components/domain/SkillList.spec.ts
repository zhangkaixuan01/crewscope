import { mount } from '@vue/test-utils'
import { RouterLink, createMemoryHistory, createRouter } from 'vue-router'
import SkillList from './SkillList.vue'
import type { SkillResourcePhase } from '../../domains/skill/store'
import type { SkillSummary } from '../../domains/skill/types'

describe('SkillList', () => {
  it('lists skills with status badges and revision facts, and emits selection', async () => {
    const wrapper = await mounted({ items: [skill('deploy-runbook-v2', { effectiveRevision: 2, latestRevision: 3 })] })

    const row = wrapper.get('button.skill-list__row')
    expect(row.text()).toContain('deploy-runbook-v2')
    expect(row.text()).toContain('已发布')
    expect(row.text()).toContain('生效 r2')
    expect(row.text()).toContain('最新 r3')

    await row.trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual(['00000000-0000-0000-0000-000000008101'])
  })

  it('always states the provenance boundary: built-in skills ride templates, not this catalog', async () => {
    const wrapper = await mounted({ items: [skill('deploy-runbook-v2')] })
    const empty = await mounted({ items: [] })

    for (const view of [wrapper, empty]) {
      expect(view.get('.skill-list__provenance').text())
        .toContain('本目录只收录团队创建与提炼的 Skill')
      expect(view.get('.skill-list__provenance').text()).toContain('java-spring-v1')
    }
  })

  it('emits status filter changes', async () => {
    const wrapper = await mounted({})
    await wrapper.get('select').setValue('DISABLED')
    expect(wrapper.emitted('changeStatus')?.[0]).toEqual(['DISABLED'])
  })

  it('renders the empty state with create and distill actions for managers', async () => {
    const wrapper = await mounted({ items: [], canManage: true })

    expect(wrapper.text()).toContain('暂无 Skill')
    const actions = wrapper.findAll('button').map(button => button.text())
    expect(actions).toContain('创建 Skill')
    expect(actions).toContain('从执行蒸馏')

    await wrapper.findAll('button').find(button => button.text() === '创建 Skill')!.trigger('click')
    expect(wrapper.emitted('create')).toHaveLength(1)
  })

  it('hides create from members without skill:manage but keeps the distillation entry', async () => {
    const wrapper = await mounted({ items: [], canManage: false })

    const actions = wrapper.findAll('button').map(button => button.text())
    expect(actions).not.toContain('创建 Skill')
    // Distillation is the execution creator's own right (contract §8), not skill:manage.
    expect(actions).toContain('从执行蒸馏')
  })

  it.each([
    ['forbidden', { phase: 'error', errorStatus: 403 } as Partial<{ phase: SkillResourcePhase, errorStatus: number | null }>, '无权读取 Skill 目录'],
    ['error', { phase: 'error', errorStatus: 503, errorMessage: '目录暂时无法读取' }, '目录暂时无法读取'],
    ['loading', { phase: 'loading' }, '正在读取 Skill 目录'],
    ['offline-empty', {}, '离线时没有可用 Skill'],
  ] as const)('renders the %s state', async (_name, overrides, expected) => {
    const wrapper = await mounted({
      items: [],
      online: _name === 'offline-empty' ? false : true,
      ...overrides,
    })
    expect(wrapper.text()).toContain(expected)
  })

  it('keeps the load-more footer only while a skill-key cursor remains', async () => {
    const paging = await mounted({ items: [skill('deploy-runbook-v2')], nextAfter: 'deploy-runbook-v2' })
    await paging.get('.skill-list__more button').trigger('click')
    expect(paging.emitted('loadMore')).toHaveLength(1)

    const done = await mounted({ items: [skill('deploy-runbook-v2')], nextAfter: null })
    expect(done.find('.skill-list__more').exists()).toBe(false)
  })

  it('offers the permission explanation link on a 403', async () => {
    const wrapper = await mounted({ items: [], phase: 'error', errorStatus: 403 })

    expect(wrapper.findComponent(RouterLink).props('to')).toEqual({
      name: 'access-denied',
      query: { requiredPermission: 'scope:read' },
    })
  })
})

async function mounted(overrides: Record<string, unknown>) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/skills', name: 'skill-catalog', component: { template: '<div />' } },
      // 权限不足状态的动作是跳转到权限说明页，因此路由表里要有这个具名路由。
      { path: '/access-denied', name: 'access-denied', component: { template: '<div />' } },
    ],
  })
  await router.push('/skills?team=team-1')
  await router.isReady()
  return mount(SkillList, {
    props: {
      phase: 'ready',
      items: [],
      nextAfter: null,
      loadingMore: false,
      errorMessage: null,
      errorStatus: null,
      online: true,
      statusFilter: 'ALL',
      canManage: true,
      selectedSkillId: null,
      ...overrides,
    },
    global: { plugins: [router] },
  })
}

function skill(skillKey: string, overrides: Partial<SkillSummary> = {}): SkillSummary {
  return {
    id: '00000000-0000-0000-0000-000000008101', skillKey, status: 'PUBLISHED',
    effectiveRevision: 1, latestRevision: 1, draft: null, disableReason: null, version: 3,
    createdAt: '2026-10-01T01:00:00Z', updatedAt: '2026-10-02T01:00:00Z',
    createdBy: 'principal-1', updatedBy: 'principal-1', origin: null,
    ...overrides,
  }
}
