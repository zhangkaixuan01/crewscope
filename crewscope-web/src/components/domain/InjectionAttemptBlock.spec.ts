import { mount } from '@vue/test-utils'
import type { InjectionAttempt, InjectionReference, InjectionReferenceKey } from '../../domains/injection/types'
import InjectionAttemptBlock from './InjectionAttemptBlock.vue'

describe('InjectionAttemptBlock', () => {
  it('renders the three zones with budget, degradations and trims as facts of the assembly', () => {
    const wrapper = mount(InjectionAttemptBlock, {
      props: { attempt: attempt({
        degradations: ['RETRIEVAL_DISABLED'],
        references: [reference({ stage: 'INJECTED' }), reference({ stage: 'CANDIDATE', sourceId: 'chunk-9' })],
        claimed: null,
      }), pendingReference: null, online: true },
    })

    expect(wrapper.text()).toContain('第 1 次尝试')
    expect(wrapper.text()).toContain('8,192 token')
    expect(wrapper.text()).toContain('检索开关未开启')
    expect(wrapper.text()).toContain('超出总预算')
    expect(wrapper.text()).toContain('已注入（1）')
    expect(wrapper.text()).toContain('候选（预算裁剪，1）')
    // Candidates never reached the model — no wording could mistake them for judgeable content.
    expect(wrapper.text()).toContain('从未送入模型')
    expect(wrapper.text()).toContain('模型尚未提交引用回执')
    const markButtons = wrapper.findAll('button').filter(item => item.text().includes('标记不适用'))
    expect(markButtons).toHaveLength(1)
  })

  it('keeps claimed null, zero-claim and a claimed list three distinct wordings', () => {
    const none = mount(InjectionAttemptBlock, { props: { attempt: attempt({ claimed: null }), pendingReference: null, online: true } })
    expect(none.text()).toContain('模型尚未提交引用回执')

    const zero = mount(InjectionAttemptBlock, { props: { attempt: attempt({ claimed: [] }), pendingReference: null, online: true } })
    expect(zero.text()).toContain('已提交回执：零声明')
    expect(zero.text()).not.toContain('模型尚未提交引用回执')

    const claimed = mount(InjectionAttemptBlock, { props: { attempt: attempt({ claimed: [key({})] }), pendingReference: null, online: true } })
    expect(claimed.text()).toContain('模型声明引用')
    expect(claimed.text()).toContain('entry-1')
  })

  it('shows the marked row as final and never offers a second judgement', () => {
    const wrapper = mount(InjectionAttemptBlock, {
      props: { attempt: attempt({
        references: [reference({ stage: 'INJECTED', notApplicable: true }), reference({ stage: 'INJECTED', sourceId: 'entry-2' })],
      }), pendingReference: null, online: true },
    })

    expect(wrapper.text()).toContain('你已标记不适用（不可撤销）')
    const markButtons = wrapper.findAll('button').filter(item => item.text().includes('标记不适用'))
    expect(markButtons).toHaveLength(1)
  })

  it('rides the pending quadruple as its row spinner and emits the exact input', async () => {
    const pending = { type: 'KNOWLEDGE_ENTRY', sourceId: 'entry-1', version: 3, contentHash: 'a'.repeat(64) } as const
    const pendingWrapper = mount(InjectionAttemptBlock, {
      props: { attempt: attempt(), pendingReference: { ...pending }, online: true },
    })

    const markButtons = pendingWrapper.findAll('button').filter(item => item.text().includes('标记不适用'))
    expect(markButtons).toHaveLength(1)
    // BaseButton renders its pending state as disabled + aria-busy, not as new wording.
    expect(markButtons[0]!.attributes('disabled')).toBeDefined()
    expect(markButtons[0]!.attributes('aria-busy')).toBe('true')

    const wrapper = mount(InjectionAttemptBlock, {
      props: { attempt: attempt(), pendingReference: null, online: true },
    })
    await wrapper.findAll('button').find(item => item.text().includes('标记不适用'))!.trigger('click')
    expect(wrapper.emitted('mark')).toEqual([[{ ...pending }]])
  })
})

function attempt(extra: Partial<InjectionAttempt> = {}): InjectionAttempt {
  return {
    manifestId: '00000000-0000-0000-0000-000000006411',
    attempt: 1,
    createdAt: '2026-10-04T09:00:00Z',
    budget: { totalTokens: 8192, knowledgeTokens: 3072, chunkTokens: 4096, memoryTokens: 1024 },
    degradations: [],
    trims: [{ layer: 'REPOSITORY_CHUNK', trimmedCount: 2, reason: 'total budget exceeded' }],
    references: [reference({ stage: 'INJECTED' })],
    claimed: null,
    ...extra,
  }
}

function reference(extra: Partial<InjectionAttempt['references'][number]> = {}): InjectionAttempt['references'][number] {
  return {
    type: 'KNOWLEDGE_ENTRY', sourceId: 'entry-1', version: 3, contentHash: 'a'.repeat(64),
    stage: 'INJECTED', notApplicable: false, ...extra,
  }
}

function key(extra: Partial<InjectionReferenceKey> = {}): InjectionReferenceKey {
  return { type: 'KNOWLEDGE_ENTRY', sourceId: 'entry-1', version: 3, contentHash: 'a'.repeat(64), ...extra }
}
