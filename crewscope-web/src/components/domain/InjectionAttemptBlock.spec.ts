import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { InjectionAttempt, InjectionFeedbackInput, InjectionReference, InjectionReferenceKey } from '../../domains/injection/types'
import InjectionAttemptBlock from './InjectionAttemptBlock.vue'

// RouterLink resolves the skill deep link during render; unknown names throw, so register the target.
const testRouter = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/work', name: 'work', component: { template: '<div />' } },
    { path: '/skills', name: 'skill-catalog', component: { template: '<div />' } },
  ],
})

beforeAll(async () => {
  await testRouter.push('/work?team=t-1')
  await testRouter.isReady()
})

function mountBlock(props: { attempt: InjectionAttempt, pendingReference: InjectionFeedbackInput | null, online?: boolean }) {
  return mount(InjectionAttemptBlock, {
    props: { online: true, ...props },
    global: { plugins: [testRouter] },
  })
}

describe('InjectionAttemptBlock', () => {
  it('renders the three zones with budget, degradations and trims as facts of the assembly', () => {
    const wrapper = mountBlock({
      attempt: attempt({
        degradations: ['RETRIEVAL_DISABLED'],
        references: [reference({ stage: 'INJECTED' }), reference({ stage: 'CANDIDATE', sourceId: 'chunk-9' })],
        claimed: null,
      }),
      pendingReference: null,
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
    const none = mountBlock({ attempt: attempt({ claimed: null }), pendingReference: null })
    expect(none.text()).toContain('模型尚未提交引用回执')

    const zero = mountBlock({ attempt: attempt({ claimed: [] }), pendingReference: null })
    expect(zero.text()).toContain('已提交回执：零声明')
    expect(zero.text()).not.toContain('模型尚未提交引用回执')

    const claimed = mountBlock({ attempt: attempt({ claimed: [key({})] }), pendingReference: null })
    expect(claimed.text()).toContain('模型声明引用')
    expect(claimed.text()).toContain('entry-1')
  })

  it('shows the marked row as final and never offers a second judgement', () => {
    const wrapper = mountBlock({
      attempt: attempt({
        references: [reference({ stage: 'INJECTED', notApplicable: true }), reference({ stage: 'INJECTED', sourceId: 'entry-2' })],
      }),
      pendingReference: null,
    })

    expect(wrapper.text()).toContain('你已标记不适用（不可撤销）')
    const markButtons = wrapper.findAll('button').filter(item => item.text().includes('标记不适用'))
    expect(markButtons).toHaveLength(1)
  })

  it('rides the pending quadruple as its row spinner and emits the exact input', async () => {
    const pending = { type: 'KNOWLEDGE_ENTRY', sourceId: 'entry-1', version: 3, contentHash: 'a'.repeat(64) } as const
    const pendingWrapper = mountBlock({ attempt: attempt(), pendingReference: { ...pending } })

    const markButtons = pendingWrapper.findAll('button').filter(item => item.text().includes('标记不适用'))
    expect(markButtons).toHaveLength(1)
    // BaseButton renders its pending state as disabled + aria-busy, not as new wording.
    expect(markButtons[0]!.attributes('disabled')).toBeDefined()
    expect(markButtons[0]!.attributes('aria-busy')).toBe('true')

    const wrapper = mountBlock({ attempt: attempt(), pendingReference: null })
    await wrapper.findAll('button').find(item => item.text().includes('标记不适用'))!.trigger('click')
    expect(wrapper.emitted('mark')).toEqual([[{ ...pending }]])
  })

  it('deep-links every SKILL_INSTRUCTION row to the catalog version tab by key, teams only', () => {
    // Manifest rows carry skillKey·revision, never a skill id; the built-in source is excluded
    // before sealing, so every skill row is a Team skill and says so next to its badge.
    const wrapper = mountBlock({
      attempt: attempt({
        references: [
          reference({ type: 'SKILL_INSTRUCTION', sourceId: 'deploy-runbook', version: 2 }),
          reference({ stage: 'CANDIDATE', type: 'SKILL_INSTRUCTION', sourceId: 'legacy-key', version: 1 }),
        ],
        claimed: [key({ type: 'SKILL_INSTRUCTION', sourceId: 'deploy-runbook', version: 2 })],
      }),
      pendingReference: null,
    })

    const links = wrapper.findAll('a')
    // The injected and claimed zones deep-link deploy-runbook; the candidate zone links its own key.
    const deployLinks = links.filter(item => item.text() === 'deploy-runbook')
    expect(deployLinks).toHaveLength(2)
    for (const link of deployLinks) {
      expect(link.attributes('href')).toBe(`/skills?team=t-1&skillKey=deploy-runbook&revision=2&tab=versions`)
    }
    expect(links.filter(item => item.text() === 'legacy-key')[0]!.attributes('href'))
      .toBe(`/skills?team=t-1&skillKey=legacy-key&revision=1&tab=versions`)
    expect(wrapper.text()).toContain('团队 Skill')
    expect(wrapper.text()).not.toContain('团队 Skillentry-1')

    // Non-skill rows keep their plain identity — no link, no team wording.
    const plain = mountBlock({ attempt: attempt(), pendingReference: null })
    expect(plain.findAll('a')).toHaveLength(0)
    expect(plain.text()).not.toContain('团队 Skill')
  })

  it('renders the built-in bundle row plainly — its id shape never enters the key alphabet', () => {
    // The hard-retained bundle row is SKILL_INSTRUCTION too, but its source id is `name_source`
    // (CodingSpecialistSkillBundle.SKILL_ID) — the underscore alone rules it out of the skill key
    // pattern, so it stays unlinked and unlabelled instead of deep-linking into a guaranteed miss.
    const wrapper = mountBlock({
      attempt: attempt({
        references: [reference({ type: 'SKILL_INSTRUCTION', sourceId: 'java-spring-v1_crewscope-java-spring-v1', version: 1 })],
        claimed: [key({ type: 'SKILL_INSTRUCTION', sourceId: 'java-spring-v1_crewscope-java-spring-v1', version: 1 })],
      }),
      pendingReference: null,
    })

    expect(wrapper.findAll('a')).toHaveLength(0)
    expect(wrapper.text()).toContain('java-spring-v1_crewscope-java-spring-v1')
    expect(wrapper.text()).not.toContain('团队 Skill')
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

function reference(extra: Partial<InjectionReference> = {}): InjectionReference {
  return {
    type: 'KNOWLEDGE_ENTRY', sourceId: 'entry-1', version: 3, contentHash: 'a'.repeat(64),
    stage: 'INJECTED', notApplicable: false, ...extra,
  }
}

function key(extra: Partial<InjectionReferenceKey> = {}): InjectionReferenceKey {
  return { type: 'KNOWLEDGE_ENTRY', sourceId: 'entry-1', version: 3, contentHash: 'a'.repeat(64), ...extra }
}
