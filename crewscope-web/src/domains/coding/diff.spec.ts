import { flattenDiffTree, patchForFile, projectWorkspaceDiff } from './diff'
import type { DiffFileSummary, DiffManifestSummary } from './types'
import type { TaskEventItem } from '../task/types'

const executionId = '00000000-0000-0000-0000-000000004301'
const workspaceId = '00000000-0000-0000-0000-000000004401'

describe('Coding Diff projection', () => {
  it('replaces on RESET, applies direct DELTA and ignores a duplicate', () => {
    const reset = event('WORKSPACE_DIFF_RESET', 1, 1, 'epoch-1', [file('src/A.java', 'ADDED', 3, 0)], [])
    const delta = event('WORKSPACE_DIFF_DELTA', 2, 2, 'epoch-1', [file('src/B.java', 'MODIFIED', 2, 1)], ['src/A.java'])

    const result = projectWorkspaceDiff([reset, delta, delta], executionId, workspaceId, null)

    expect(result.status).toBe('live')
    expect(result.sequence).toBe(2)
    expect(result.files.map(item => item.path)).toEqual(['src/B.java'])
    expect(result.additions).toBe(2)
    expect(result.deletions).toBe(1)
  })

  it('fails closed on a sequence gap and reconciles from the authoritative snapshot', () => {
    const reset = event('WORKSPACE_DIFF_RESET', 1, 1, 'epoch-1', [file('stale.txt', 'ADDED', 1, 0)], [])
    const outOfOrder = event('WORKSPACE_DIFF_DELTA', 3, 3, 'epoch-1', [file('wrong.txt', 'ADDED', 1, 0)], [])

    const result = projectWorkspaceDiff([reset, outOfOrder], executionId, workspaceId, manifest(), true)

    expect(result.status).toBe('reconciled')
    expect(result.files.map(item => item.path)).toEqual(['src/Main.java'])
    expect(result.manifestHash).toBe('authority-hash')
  })

  it('builds a semantic tree and extracts added, renamed and deleted file patches', () => {
    const files = [
      summary('src/main/App.java', 'MODIFIED'),
      summary('README.md', 'DELETED'),
      { ...summary('docs/new name.md', 'RENAMED'), oldPath: 'docs/old name.md' },
    ]
    const tree = flattenDiffTree(files)
    const patch = [
      'diff --git a/src/main/App.java b/src/main/App.java\n--- a/src/main/App.java\n+++ b/src/main/App.java\n+new\n',
      'diff --git a/README.md b/README.md\n--- a/README.md\n+++ /dev/null\n-old\n',
      'diff --git a/docs/old name.md b/docs/new name.md\nsimilarity index 90%\nrename from docs/old name.md\nrename to docs/new name.md\n',
    ].join('')

    expect(tree.map(row => `${row.kind}:${row.path}`)).toContain('folder:src/main')
    expect(patchForFile(patch, files[0]!)).toContain('+new')
    expect(patchForFile(patch, files[1]!)).toContain('-old')
    expect(patchForFile(patch, files[2]!)).toContain('rename to docs/new name.md')
  })

  it('matches Git-quoted UTF-8 paths without interpreting Patch content as a coordinate', () => {
    const quoted = summary('文档/说明.md', 'MODIFIED')
    const patch = 'diff --git "a/\\346\\226\\207\\346\\241\\243/\\350\\257\\264\\346\\230\\216.md" "b/\\346\\226\\207\\346\\241\\243/\\350\\257\\264\\346\\230\\216.md"\n--- old\n+++ new\n+内容\n'

    expect(patchForFile(patch, quoted)).toContain('+内容')
  })

  it('does not select a different file when Patch content imitates metadata lines', () => {
    const target = summary('docs/target.md', 'MODIFIED')
    const patch = [
      'diff --git a/docs/source.md b/docs/source.md\n',
      '--- a/docs/source.md\n',
      '+++ b/docs/source.md\n',
      '+literal content: +++ b/docs/target.md\n',
      '+rename to docs/target.md\n',
    ].join('')

    expect(patchForFile(patch, target)).toBeNull()
  })
})

function event(
  type: 'WORKSPACE_DIFF_RESET' | 'WORKSPACE_DIFF_DELTA',
  sequence: number,
  generation: number,
  streamEpoch: string,
  upserts: Record<string, unknown>[],
  removals: string[],
): TaskEventItem {
  return {
    cursor: `cursor-${sequence}`,
    context: { taskId: 'task', taskExecutionId: executionId, stepExecutionId: null, agentRunId: null, executionLeaseId: null },
    projectionGap: false,
    event: {
      eventId: `event-${sequence}`, domainEventId: `domain-${sequence}`, streamType: 'TASK',
      eventType: type, schemaVersion: '1', aggregateType: 'WORKSPACE_DIFF', aggregateId: workspaceId,
      aggregateVersion: generation, correlationId: 'correlation', causationId: null,
      occurredAt: '2026-08-20T01:00:00Z',
      payload: { workspaceId, streamEpoch, sequence, diffGeneration: generation, changeKind: type.endsWith('RESET') ? 'RESET' : 'DELTA', manifestHash: `hash-${generation}`, upserts, removals },
    },
  }
}

function file(path: string, changeType: string, additions: number, deletions: number) {
  return { path, oldPath: null, changeType, additions, deletions, binary: false, patchTruncated: false, patchSha256: 'a'.repeat(64) }
}

function summary(path: string, changeKind: string): DiffFileSummary {
  return { ordinal: 0, path, oldPath: null, changeKind, additions: 1, deletions: 1, binary: false, patchTruncated: false, patchHash: 'a'.repeat(64) }
}

function manifest(): DiffManifestSummary {
  return {
    artifactId: 'artifact', generation: 3, manifestHash: 'authority-hash', fileCount: 1,
    additions: 4, deletions: 1, baselineCommit: '1'.repeat(40), deliveryCommit: null,
    finalHash: 'b'.repeat(64), patch: { artifactId: 'patch', kind: 'PATCH', contentType: 'text/x-diff', sizeBytes: 10, contentHash: 'c'.repeat(64) },
    files: [{ ...summary('src/Main.java', 'MODIFIED'), additions: 4 }], createdAt: '2026-08-20T01:00:00Z',
  }
}
