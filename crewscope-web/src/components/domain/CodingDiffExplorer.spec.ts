import { flushPromises, mount } from '@vue/test-utils'
import type { CodingAttemptSummary, CodingPatchDocument } from '../../domains/coding/types'
import type { TaskEventItem, TaskEventPage } from '../../domains/task/types'
import CodingDiffExplorer from './CodingDiffExplorer.vue'

const executionId = '00000000-0000-0000-0000-000000004301'
const workspaceId = '00000000-0000-0000-0000-000000004401'

describe('CodingDiffExplorer', () => {
  beforeEach(() => {
    // Each case owns its initial view; the production preference remains persisted across mounts.
    localStorage.removeItem('cs.pref.diff.view-mode.v1')
    localStorage.removeItem('cs.pref.diff.viewed-files.v1')
  })

  it('shows the live file tree, accumulated statistics and single-file Patch', async () => {
    const onLoadPatch = vi.fn()
    const wrapper = mount(CodingDiffExplorer, { props: props({ onLoadPatch }) })

    expect(wrapper.text()).toContain('Diff Explorer')
    expect(wrapper.text()).toContain('src')
    expect(wrapper.text()).toContain('Main.java')
    expect(wrapper.text()).toContain('+8')
    expect(wrapper.text()).toContain('-2')
    expect(wrapper.text()).toContain('Diff 已同步')

    const main = wrapper.findAll<HTMLButtonElement>('.diff-tree__file').find(button => button.text().includes('Main.java'))!
    await main.trigger('click')
    await wrapper.get('.patch-message button').trigger('click')
    expect(onLoadPatch).toHaveBeenCalledOnce()
    await wrapper.setProps({ patchPhase: 'ready', patch: patchDocument() })
    expect(wrapper.get('.patch-code').text()).toContain('+public class Main')
    expect(wrapper.html()).not.toContain('/private/worktree')
  })

  it('switches renamed and binary files without presenting binary content', async () => {
    const wrapper = mount(CodingDiffExplorer, { props: props({ patchPhase: 'ready', patch: patchDocument() }) })
    const renamed = wrapper.findAll<HTMLButtonElement>('.diff-tree__file').find(button => button.text().includes('Guide.md'))!
    await renamed.trigger('click')
    expect(wrapper.get('.patch-view header').text()).toContain('from docs/README.md')
    expect(wrapper.get('.patch-code').text()).toContain('rename to docs/Guide.md')

    const binary = wrapper.findAll<HTMLButtonElement>('.diff-tree__file').find(button => button.text().includes('logo.png'))!
    await binary.trigger('click')
    expect(wrapper.text()).toContain('Binary 变更')
    expect(wrapper.find('.patch-code').exists()).toBe(false)
  })

  it('fails closed on an out-of-order DELTA and requests authoritative reconcile', async () => {
    const onReconcile = vi.fn()
    const page = eventPage()
    page.items.push(diffEvent('WORKSPACE_DIFF_DELTA', 4, 3, [], []))
    const attemptValue = attempt()
    attemptValue.details!.diffManifest = null
    const wrapper = mount(CodingDiffExplorer, {
      props: props({ attempt: attemptValue, eventPage: page, onReconcile }),
    })

    expect(wrapper.text()).toContain('实时 Diff 序列无法安全续接')
    await wrapper.get('.diff-gap button').trigger('click')
    expect(onReconcile).toHaveBeenCalledOnce()
  })

  it('bounds a large tree and preserves path filtering', async () => {
    const value = attempt()
    value.details!.diffManifest!.files = Array.from({ length: 405 }, (_, index) => ({
      ordinal: index, path: `generated/file-${String(index).padStart(3, '0')}.txt`, oldPath: null,
      changeKind: 'ADDED', additions: 1, deletions: 0, binary: false, patchTruncated: false,
      patchHash: 'a'.repeat(64),
    }))
    value.details!.diffManifest!.fileCount = 405
    value.details!.diffManifest!.additions = 405
    const wrapper = mount(CodingDiffExplorer, { props: props({ attempt: value, eventPage: { ...eventPage(), items: [] } }) })

    expect(wrapper.text()).toContain('已显示前 400 / 405')
    await wrapper.get<HTMLInputElement>('.diff-search input').setValue('file-404')
    expect(wrapper.text()).toContain('file-404.txt')
    expect(wrapper.text()).not.toContain('已显示前 400')
  }, 15_000)

  it('supports persisted view controls, line comments and safe markdown rendering', async () => {
    const onAddComment = vi.fn().mockResolvedValue({
      id: 'comment-1', reviewRequestId: 'review-1', taskExecutionId: executionId, filePath: 'src/Main.java',
      side: 'NEW', lineNumber: 2, hunkHeader: '@@ -1 +1 @@', lineContentHash: 'a'.repeat(64), diffGeneration: 2,
      content: '**safe** <script>alert(1)</script>', authorPrincipalId: 'member-1', anchorState: 'ACTIVE', deleted: false,
      version: 1, createdAt: '2026-08-20T01:00:00Z', updatedAt: '2026-08-20T01:00:00Z',
    })
    const wrapper = mount(CodingDiffExplorer, { props: props({ patchPhase: 'ready', patch: patchDocument(), onAddComment }) })
    await wrapper.findAll<HTMLButtonElement>('.diff-tree__file').find(button => button.text().includes('Main.java'))!.trigger('click')
    expect(wrapper.text()).toContain('Java')
    await wrapper.get('.patch-action').trigger('click')
    expect(wrapper.text()).toContain('Unified')
    await wrapper.get('[aria-label="评论第 1 行"]').trigger('click')
    await wrapper.get('[data-testid="review-comment-input"]').setValue('Please fix this')
    await wrapper.get('.review-comment-form').trigger('submit')
    await vi.waitFor(() => expect(onAddComment).toHaveBeenCalledOnce())
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('safe')
    expect(wrapper.html()).not.toContain('<script>')
  })

  it('walks the changed lines from the keyboard', async () => {
    // 重写行渲染就动到了这条路径的落点：焦点是靠行 id 找的，而行 id 又是行内分片的键。
    const wrapper = mount(CodingDiffExplorer, {
      props: propsFor('src/Main.java', '@@ -1,2 +1,2 @@\n-old first\n+new first\n context\n-old second\n+new second\n'),
    })
    await flushPromises()

    const region = wrapper.get('.patch-code')
    const focused = () => wrapper.get('.patch-line.focused').text()

    await region.trigger('keydown', { key: 'n' })
    expect(focused()).toContain('old first')
    await region.trigger('keydown', { key: 'n' })
    expect(focused()).toContain('new first')
    // 未改动的上下文行不是停靠点。
    await region.trigger('keydown', { key: 'n' })
    expect(focused()).toContain('old second')
    await region.trigger('keydown', { key: 'p' })
    expect(focused()).toContain('new first')
    // 往回走过第一处会绕到最后一行，而不是卡住。
    await region.trigger('keydown', { key: 'p' })
    await region.trigger('keydown', { key: 'p' })
    expect(focused()).toContain('new second')
    expect(wrapper.findAll('.patch-line.focused')).toHaveLength(1)
  })

  it('highlights the patch with the grammar for the file it is reading', async () => {
    const wrapper = mount(CodingDiffExplorer, {
      props: propsFor('src/Main.java', '@@ -1 +1 @@\n-old\n+public class Main {}\n'),
    })

    // 语法包是异步来的，所以等到强调色真的出现在 DOM 里才算数——这正是「包加载成功了」的证据。
    await vi.waitFor(() => expect(wrapper.findAll('.patch-code .syntax-keyword').length).toBeGreaterThan(0))

    expect(wrapper.findAll('.patch-code .syntax-keyword').map(span => span.text())).toContain('public')
    expect(wrapper.findAll('.patch-code .syntax-type').map(span => span.text()).join(' ')).toContain('Main')
    // 高亮只分片、不改字：渲染出来的行与 Patch 原文逐字符一致。
    expect(wrapper.get('.patch-code').text()).toContain('+public class Main {}')
  })

  it('renders a file type it has no grammar for as plain text rather than refusing it', async () => {
    const wrapper = mount(CodingDiffExplorer, {
      props: propsFor('design/logo.svg', ['<svg width="1">', '-  <rect />', '+  <circle r="1" />', '</svg>'].join('\n')),
    })
    await flushPromises()
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('纯文本')
    const spans = wrapper.findAll('.patch-code .patch-line > b > span')
    expect(spans.length).toBeGreaterThan(0)
    expect(spans.every(span => span.classes().includes('syntax-plain'))).toBe(true)
    // 没有语法也要能读：整行文字照旧渲染，不是空白。
    expect(wrapper.get('.patch-code').text()).toContain('+  <circle r="1" />')
  })

  it('leaves a patch it only holds part of as plain text', async () => {
    // 与上面同一种语言、同一份文件，唯一的差别是这份 Patch 没有人完整拿着：渲染上限之外的
    // 行根本画不出来。两条用例的对比才是这条规则在起作用，而不是断言了一句恒真的话。
    const overflowing = Array.from({ length: 2_100 }, (_, index) => `+    private final int counters${index} = ${index};`).join('\n')
    const wrapper = mount(CodingDiffExplorer, {
      props: propsFor('src/Main.java', `@@ -1 +1,2101 @@\n-public class Main {}\n${overflowing}`),
    })
    await flushPromises()
    await wrapper.vm.$nextTick()

    expect(wrapper.get('.patch-code').text()).toContain('-public class Main {}')
    const spans = wrapper.findAll('.patch-code .patch-line > b > span')
    expect(spans.length).toBeGreaterThan(0)
    expect(spans.every(span => span.classes().includes('syntax-plain'))).toBe(true)
  })
})

/**
 * A world with exactly one file, so a case can choose the language it is testing.
 *
 * The file list comes from the event stream unless the authoritative manifest is *newer* than it, so
 * the reset event below carries the manifest's own generation and hash — generation 2, hash-2. That
 * keeps the projection authoritative over the snapshot instead of letting the default three-file
 * manifest quietly decide which file is selected.
 */
function propsFor(path: string, content: string, changeKind = 'MODIFIED') {
  return props({
    eventPage: {
      items: [diffEvent('WORKSPACE_DIFF_RESET', 1, 2, [rawFile(path, null, changeKind, 1, 1)], [])],
      hasMore: false, taskTerminal: false, nextCursor: null,
    },
    patchPhase: 'ready' as const,
    patch: {
      sizeBytes: content.length, etag: '"patch"',
      content: `diff --git a/${path} b/${path}\n--- a/${path}\n+++ b/${path}\n${content}`,
    } satisfies CodingPatchDocument,
  })
}

function javaPatch(): string {
  return '@@ -1 +1 @@\n-old\n+public class Main {}\n'
}

function props(overrides: Record<string, unknown> = {}) {
  return {
    attempt: attempt(), eventPage: eventPage(),
    liveState: { phase: 'connected' as const, errorMessage: null, projectionGap: false },
    patchPhase: 'idle' as const, patch: null, patchErrorMessage: null,
    onLoadPatch: vi.fn(), onReconcile: vi.fn(), ...overrides,
  }
}

function attempt(): CodingAttemptSummary {
  const files = [
    file('src/Main.java', null, 'MODIFIED', 8, 2),
    file('docs/Guide.md', 'docs/README.md', 'RENAMED', 0, 0),
    { ...file('assets/logo.png', null, 'ADDED', 0, 0), binary: true, patchTruncated: true },
  ]
  return {
    executionId, attempt: 2, executionStatus: 'COMPLETED', current: true, coding: true,
    details: {
      executionId, attempt: 2,
      workspace: {
        id: workspaceId, repositoryKey: 'crewscope-java', baselineCommit: '1'.repeat(40), managedBranch: 'crewscope/task/2',
        status: 'COMPLETED', recoveryGeneration: 0, completionReason: 'DELIVERED', failureCode: null,
        fingerprint: '2'.repeat(64), version: 3, retainUntil: '2026-09-20T01:00:00Z',
        createdAt: '2026-08-20T01:00:00Z', updatedAt: '2026-08-20T01:10:00Z',
      },
      sandbox: null,
      diffManifest: {
        artifactId: 'artifact', generation: 2, manifestHash: 'hash-2', fileCount: 3, additions: 8, deletions: 2,
        baselineCommit: '1'.repeat(40), deliveryCommit: '2'.repeat(40), finalHash: '3'.repeat(64),
        patch: { artifactId: 'patch', kind: 'PATCH', contentType: 'text/x-diff;charset=utf-8', sizeBytes: 300, contentHash: '4'.repeat(64) },
        files, createdAt: '2026-08-20T01:10:00Z',
      },
      codingResult: null, commandEvidenceCount: 1, testEvidenceCount: 1,
    },
  }
}

function eventPage(): TaskEventPage {
  return {
    items: [diffEvent('WORKSPACE_DIFF_RESET', 1, 1, [
      rawFile('src/Main.java', null, 'MODIFIED', 3, 1),
      rawFile('docs/Guide.md', 'docs/README.md', 'RENAMED', 0, 0),
      { ...rawFile('assets/logo.png', null, 'ADDED', 0, 0), binary: true, patchTruncated: true },
    ], []), diffEvent('WORKSPACE_DIFF_DELTA', 2, 2, [rawFile('src/Main.java', null, 'MODIFIED', 8, 2)], [])],
    hasMore: false, taskTerminal: false, nextCursor: 'cursor-2',
  }
}

function diffEvent(
  type: 'WORKSPACE_DIFF_RESET' | 'WORKSPACE_DIFF_DELTA',
  sequence: number,
  generation: number,
  upserts: Record<string, unknown>[],
  removals: string[],
): TaskEventItem {
  return {
    cursor: `cursor-${sequence}`, projectionGap: false,
    context: { taskId: 'task', taskExecutionId: executionId, stepExecutionId: null, agentRunId: null, executionLeaseId: null },
    event: {
      eventId: `event-${sequence}`, domainEventId: `domain-${sequence}`, streamType: 'TASK', eventType: type,
      schemaVersion: '1', aggregateType: 'WORKSPACE_DIFF', aggregateId: workspaceId, aggregateVersion: generation,
      correlationId: 'correlation', causationId: null, occurredAt: '2026-08-20T01:00:00Z',
      payload: { workspaceId, streamEpoch: 'epoch-1', sequence, diffGeneration: generation, changeKind: type.endsWith('RESET') ? 'RESET' : 'DELTA', manifestHash: `hash-${generation}`, upserts, removals },
    },
  }
}

function rawFile(path: string, oldPath: string | null, changeType: string, additions: number, deletions: number) {
  return { path, oldPath, changeType, additions, deletions, binary: false, patchTruncated: false, patchSha256: 'a'.repeat(64) }
}

function file(path: string, oldPath: string | null, changeKind: string, additions: number, deletions: number) {
  return { ordinal: 0, path, oldPath, changeKind, additions, deletions, binary: false, patchTruncated: false, patchHash: 'a'.repeat(64) }
}

function patchDocument(): CodingPatchDocument {
  return {
    sizeBytes: 300, etag: '"patch"',
    content: [
      'diff --git a/src/Main.java b/src/Main.java\n--- a/src/Main.java\n+++ b/src/Main.java\n@@ -1 +1 @@\n-old\n+public class Main {}\n',
      'diff --git a/docs/README.md b/docs/Guide.md\nsimilarity index 100%\nrename from docs/README.md\nrename to docs/Guide.md\n',
    ].join(''),
  }
}
