import { mount } from '@vue/test-utils'
import KnowledgeIndexJobItem from './KnowledgeIndexJobItem.vue'
import type { KnowledgeIndexJob } from '../../domains/knowledge/types'

describe('KnowledgeIndexJobItem', () => {
  it('renders the repository target as binding and commit, with the chunk counter', () => {
    const wrapper = mount(KnowledgeIndexJobItem, { props: { job: repositoryJob(), canManage: false, cancelling: false } })

    expect(wrapper.text()).toContain('仓库')
    expect(wrapper.text()).toContain('嵌入中')
    expect(wrapper.text()).toContain('绑定 00000000 @ aaaaaaaa')
    expect(wrapper.text()).toContain('12/40')
  })

  it('renders the knowledge-entry target as the entry id prefix', () => {
    const wrapper = mount(KnowledgeIndexJobItem, { props: { job: job(), canManage: false, cancelling: false } })

    expect(wrapper.text()).toContain('知识条目')
    expect(wrapper.text()).toContain('条目 00000000')
  })

  it('shows the cancel control only for a QUEUED job and only to managers', () => {
    const queued = mount(KnowledgeIndexJobItem, { props: { job: job({ status: 'QUEUED' }), canManage: true, cancelling: false } })
    expect(queued.text()).toContain('取消作业')

    const embedded = mount(KnowledgeIndexJobItem, { props: { job: repositoryJob({ status: 'EMBEDDING' }), canManage: true, cancelling: false } })
    expect(embedded.text()).not.toContain('取消作业')

    const reader = mount(KnowledgeIndexJobItem, { props: { job: job({ status: 'QUEUED' }), canManage: false, cancelling: false } })
    expect(reader.text()).not.toContain('取消作业')
  })

  it('emits the job id on cancel and marks the pending row', async () => {
    const wrapper = mount(KnowledgeIndexJobItem, { props: { job: job({ status: 'QUEUED' }), canManage: true, cancelling: false } })
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('cancel')?.[0]).toEqual(['00000000-0000-0000-0000-000000007401'])

    await wrapper.setProps({ cancelling: true })
    expect(wrapper.get('button').attributes('aria-busy')).toBe('true')
  })

  it('expands the technical block for managers and keeps it hidden from readers', async () => {
    const manager = mount(KnowledgeIndexJobItem, { props: { job: repositoryJob(), canManage: true, cancelling: false } })
    await manager.get('.job-item__toggle').trigger('click')
    expect(manager.get('.job-item__toggle').attributes('aria-expanded')).toBe('true')
    // 责任人事实（租约持有者、创建者）只对管理者展开（计划 D5）。
    expect(manager.text()).toContain('持有租约')
    expect(manager.text()).toContain('knowledge-index-worker-1')
    expect(manager.text()).toContain('创建者')
    expect(manager.text()).toContain('text-embedding-v4@3')

    const reader = mount(KnowledgeIndexJobItem, { props: { job: repositoryJob(), canManage: false, cancelling: false } })
    await reader.get('.job-item__toggle').trigger('click')
    expect(reader.text()).toContain('技术详情仅对具有知识管理权限的成员展示。')
    expect(reader.text()).not.toContain('持有租约')
    expect(reader.text()).not.toContain('knowledge-index-worker-1')
    expect(reader.text()).not.toContain('创建者')
  })

  it('words the known failure codes and falls back to the raw code', () => {
    const known = mount(KnowledgeIndexJobItem, { props: { job: repositoryJob({ status: 'FAILED', failureCode: 'MODEL_DRIFT' }), canManage: false, cancelling: false } })
    expect(known.text()).toContain('嵌入模型与索引键冻结版本不一致')

    const health = mount(KnowledgeIndexJobItem, { props: { job: repositoryJob({ status: 'FAILED', failureCode: 'EMBEDDING_PROVIDER_HEALTH_RED' }), canManage: false, cancelling: false } })
    // 开放模型健康码兜底：原样显示，不崩溃。
    expect(health.text()).toContain('EMBEDDING_PROVIDER_HEALTH_RED')
  })
})

function job(overrides: Partial<KnowledgeIndexJob> = {}): KnowledgeIndexJob {
  return {
    id: '00000000-0000-0000-0000-000000007401', source: 'KNOWLEDGE_ENTRY', status: 'QUEUED',
    entryId: '00000000-0000-0000-0000-000000007101', projectId: null, indexKey: null,
    attempt: 0, chunksDone: 0, chunksTotal: 1, failureCode: null, generationBuildSequence: 1,
    claimedBy: null, leaseExpiresAt: null, createdBy: 'principal-1',
    createdAt: '2026-10-04T09:00:00Z', updatedAt: '2026-10-04T09:00:00Z',
    ...overrides,
  }
}

function repositoryJob(overrides: Partial<KnowledgeIndexJob> = {}): KnowledgeIndexJob {
  return job({
    id: '00000000-0000-0000-0000-000000007402', source: 'REPOSITORY', status: 'EMBEDDING',
    entryId: null, projectId: 'project-1',
    indexKey: { bindingId: '00000000-0000-0000-0000-000000007501', commit: 'a'.repeat(40), chunkPolicyHash: 'c'.repeat(64), modelKey: 'text-embedding-v4', modelRevision: 3 },
    attempt: 2, chunksDone: 12, chunksTotal: 40, claimedBy: 'knowledge-index-worker-1',
    leaseExpiresAt: '2026-10-04T09:30:00Z', createdBy: 'principal-9',
    ...overrides,
  })
}
