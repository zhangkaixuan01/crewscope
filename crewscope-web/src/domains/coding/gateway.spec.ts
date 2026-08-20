import { CrewScopeApiClient, CrewScopeApiError } from '../../api/client'
import { fixtureIds } from '../../test/scopeFixtures'
import { HttpCodingGateway } from './gateway'
import type { CodingScope } from './types'

const scope: CodingScope = {
  organizationId: fixtureIds.organization,
  teamId: fixtureIds.teamPlatform,
  projectId: fixtureIds.projectCrewScope,
}
const bindingId = '00000000-0000-0000-0000-000000004101'
const taskId = '00000000-0000-0000-0000-000000004201'
const executionId = '00000000-0000-0000-0000-000000004301'
const workspaceId = '00000000-0000-0000-0000-000000004401'

describe('HttpCodingGateway', () => {
  it('maps Repository and BuildProfile responses through explicit public whitelists', async () => {
    const fetcher = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith('/repository-bindings')) return json({ items: [{
        id: bindingId,
        organizationId: scope.organizationId,
        teamId: scope.teamId,
        workspaceId: fixtureIds.workspacePlatform,
        projectId: scope.projectId,
        kind: 'LOCAL_MANAGED',
        repositoryKey: 'crewscope-java',
        defaultBranch: 'main',
        status: 'ACTIVE',
        version: 2,
        createdAt: '2026-08-20T01:00:00Z',
        createdByPrincipalId: fixtureIds.principal,
        updatedAt: '2026-08-20T02:00:00Z',
        updatedByPrincipalId: fixtureIds.principal,
        canonicalPath: '/private/host/repository',
      }] })
      return json({ items: [{
        key: 'maven-java-17',
        version: 1,
        profileHash: 'a'.repeat(64),
        buildTool: 'MAVEN',
        javaRelease: 17,
        commandKinds: ['TEST'],
        sandboxImage: 'private-image',
        commandCatalog: { argv: ['mvn', 'test'] },
      }] })
    })
    const gateway = new HttpCodingGateway(new CrewScopeApiClient('/api/v1', fetcher as unknown as typeof fetch))

    const repositories = await gateway.listRepositoryBindings(scope)
    const profiles = await gateway.listBuildProfiles(scope, '00000000-0000-0000-0000-000000004501')

    expect(repositories[0]).not.toHaveProperty('canonicalPath')
    expect(profiles[0]).not.toHaveProperty('sandboxImage')
    expect(profiles[0]).not.toHaveProperty('commandCatalog')
    expect(JSON.stringify({ repositories, profiles })).not.toContain('/private/host')
  })

  it('reads Repository Catalog options without retaining managed host details', async () => {
    const fetcher = vi.fn(async (_input: RequestInfo | URL) => json({ items: [{
      repositoryKey: 'crewscope-java', availability: 'AVAILABLE', suggestedDefaultBranch: 'main',
      canonicalPath: '/private/host/repository', managedRoot: '/private/host', owner: 'worker-user',
    }] }))
    const gateway = new HttpCodingGateway(new CrewScopeApiClient('/api/v1', fetcher as unknown as typeof fetch))

    const catalog = await gateway.listRepositoryCatalog(scope)

    expect(catalog).toEqual([{
      repositoryKey: 'crewscope-java', availability: 'AVAILABLE', suggestedDefaultBranch: 'main',
    }])
    expect(JSON.stringify(catalog)).not.toContain('/private/host')
    expect(String(fetcher.mock.calls[0]?.[0])).toContain('/repository-catalog')
  })

  it('keeps Workspace, Diff, Command and Test facts inside the browser disclosure boundary', async () => {
    const fetcher = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/coding/commands')) return json({ items: [commandPayload()], nextCursor: 'command-cursor' })
      if (url.includes('/coding/test-evidence')) return json({ items: [testPayload()], nextCursor: null })
      return json({ taskId, currentAttempt: attemptPayload() })
    })
    const gateway = new HttpCodingGateway(new CrewScopeApiClient('/api/v1', fetcher as unknown as typeof fetch))

    const current = await gateway.getCurrentAttempt(scope, taskId)
    const commands = await gateway.listCommands(scope, taskId, executionId, 'opaque-cursor', 25)
    const tests = await gateway.listTestEvidence(scope, taskId, executionId)
    const browserState = JSON.stringify({ current, commands, tests })

    expect(current.currentAttempt?.details?.workspace.id).toBe(workspaceId)
    expect(commands.nextCursor).toBe('command-cursor')
    expect(browserState).not.toContain('containerId')
    expect(browserState).not.toContain('hostPath')
    expect(browserState).not.toContain('typedArgv')
    expect(browserState).not.toContain('storageUri')
    expect(browserState).not.toContain('taskToken')
  })

  it('forwards opaque Cursors and strong command metadata without interpreting them', async () => {
    const fetcher = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'POST') return json({
        commandId: crypto.randomUUID(), domainEventId: crypto.randomUUID(),
        committedVersion: 3, correlationId: crypto.randomUUID(), internalResult: 'private',
      }, 202)
      return json({ items: [], nextCursor: null })
    })
    const gateway = new HttpCodingGateway(new CrewScopeApiClient('/api/v1', fetcher as unknown as typeof fetch))

    await gateway.listCommands(scope, taskId, executionId, 'signed+/cursor=', 17)
    const receipt = await gateway.transitionRepositoryBinding(scope, bindingId, 'disable', 2, 'same-key')

    expect(String(fetcher.mock.calls[0]?.[0])).toContain('after=signed%2B%2Fcursor%3D')
    expect(String(fetcher.mock.calls[0]?.[0])).toContain('limit=17')
    const headers = new Headers(fetcher.mock.calls[1]?.[1]?.headers)
    expect(headers.get('Idempotency-Key')).toBe('same-key')
    expect(headers.get('If-Match')).toBe('"2"')
    expect(receipt).not.toHaveProperty('internalResult')
  })

  it('reads one authorized Patch byte page and validates its transport coordinates', async () => {
    const patch = new TextEncoder().encode('diff --git a/a.txt b/a.txt\n+hello\n')
    const fetcher = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => new Response(patch, {
      status: 206,
      headers: {
        'Content-Type': 'text/x-diff;charset=utf-8',
        'Content-Range': `bytes 0-${patch.byteLength - 1}/${patch.byteLength}`,
        ETag: '"patch-hash"',
      },
    }))
    const gateway = new HttpCodingGateway(new CrewScopeApiClient('/api/v1', fetcher as unknown as typeof fetch))

    const page = await gateway.readPatchPage(scope, taskId, executionId, 0, 262_144)

    expect(new TextDecoder().decode(page.bytes)).toContain('+hello')
    expect(page).toMatchObject({ offset: 0, length: patch.byteLength, totalSize: patch.byteLength, etag: '"patch-hash"' })
    expect(String(fetcher.mock.calls[0]?.[0])).toContain('offset=0&limit=262144')
    expect(new Headers(fetcher.mock.calls[0]?.[1]?.headers).get('Accept')).toBe('text/x-diff')
  })

  it('reads purpose-bound command logs and test reports with server download names', async () => {
    const fetcher = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
      const report = String(input).includes('/test-evidence/')
      const bytes = new TextEncoder().encode(report ? '{"passed":1}\n' : 'token=[REDACTED]\n')
      return new Response(bytes, { status: 206, headers: {
        'Content-Type': report ? 'application/json;charset=utf-8' : 'text/plain;charset=utf-8',
        'Content-Range': `bytes 0-${bytes.byteLength - 1}/${bytes.byteLength}`,
        'Content-Disposition': report
          ? "attachment; filename*=UTF-8''crewscope-report.json"
          : 'attachment; filename="crewscope-command.log"',
        ETag: '"stable"',
      } })
    })
    const gateway = new HttpCodingGateway(new CrewScopeApiClient('/api/v1', fetcher as unknown as typeof fetch))

    const log = await gateway.readCommandLogPage(scope, taskId, executionId, 'command-1', 0, 65_536)
    const report = await gateway.readTestReportPage(scope, taskId, executionId, 'test-1', 0, 65_536)

    expect(log.filename).toBe('crewscope-command.log')
    expect(report.filename).toBe('crewscope-report.json')
    expect(String(fetcher.mock.calls[0]?.[0])).toContain('/commands/command-1/log?offset=0&limit=65536')
    expect(String(fetcher.mock.calls[1]?.[0])).toContain('/test-evidence/test-1/report?offset=0&limit=65536')
    expect(new Headers(fetcher.mock.calls[1]?.[1]?.headers).get('Accept')).toContain('application/json')
  })

  it('rejects a server download name that decodes to control characters', async () => {
    const bytes = new TextEncoder().encode('safe body')
    const fetcher = vi.fn(async () => new Response(bytes, { status: 206, headers: {
      'Content-Type': 'text/plain;charset=utf-8',
      'Content-Range': `bytes 0-${bytes.byteLength - 1}/${bytes.byteLength}`,
      'Content-Disposition': "attachment; filename*=UTF-8''command%0A.log",
      ETag: '"stable"',
    } }))
    const gateway = new HttpCodingGateway(new CrewScopeApiClient('/api/v1', fetcher as unknown as typeof fetch))

    await expect(gateway.readCommandLogPage(scope, taskId, executionId, 'command-1', 0, 65_536))
      .rejects.toThrow('Coding Artifact download filename is missing')
  })

  it('preserves the stable API error envelope', async () => {
    const fetcher = vi.fn(async () => json({
      code: 'coding_attempt_not_found',
      message: 'Coding attempt 不存在',
      correlationId: 'correlation-safe',
      retryable: false,
      currentVersion: null,
      details: {},
    }, 404)) as unknown as typeof fetch
    const gateway = new HttpCodingGateway(new CrewScopeApiClient('/api/v1', fetcher))

    await expect(gateway.getAttempt(scope, taskId, executionId)).rejects.toEqual(expect.objectContaining({
      status: 404,
      envelope: expect.objectContaining({ code: 'coding_attempt_not_found', correlationId: 'correlation-safe' }),
    } satisfies Partial<CrewScopeApiError>))
  })
})

function attemptPayload(): Record<string, unknown> {
  return {
    executionId,
    attempt: 1,
    executionStatus: 'RUNNING',
    current: true,
    coding: true,
    details: {
      executionId,
      attempt: 1,
      workspace: {
        id: workspaceId,
        repositoryKey: 'crewscope-java',
        baselineCommit: '1'.repeat(40),
        managedBranch: 'crewscope/tasks/task/attempt-1',
        status: 'ACTIVE',
        recoveryGeneration: 0,
        completionReason: null,
        failureCode: null,
        fingerprint: '2'.repeat(64),
        version: 2,
        retainUntil: '2026-09-20T01:00:00Z',
        createdAt: '2026-08-20T01:00:00Z',
        updatedAt: '2026-08-20T01:01:00Z',
        hostPath: '/private/worktree',
      },
      sandbox: {
        networkMode: 'NONE', cpuCount: 2, memoryMiB: 2048, pids: 256,
        maxCommandDurationSeconds: 300, maxCommandOutputBytes: 1_000_000,
        readOnlyRootFilesystem: true, maxCommandCalls: 20, maxChangedFiles: 100,
        maxSingleFileBytes: 1_000_000, maxWriteOperations: 200, maxWrittenBytes: 5_000_000,
        maxDiffBytes: 10_000_000, maxTestRepairRounds: 3, buildProfileKey: 'maven-java-17',
        buildProfileVersion: 1, containerId: 'private-container', taskToken: 'private-token',
      },
      diffManifest: {
        artifactId: crypto.randomUUID(), generation: 1, manifestHash: '7'.repeat(64), fileCount: 1,
        additions: 3, deletions: 1, baselineCommit: '1'.repeat(40), deliveryCommit: null,
        finalHash: '8'.repeat(64), patch: artifact('PATCH', { storageUri: 'file:///private/patch' }),
        files: [{
          ordinal: 0, path: 'src/Main.java', oldPath: null, changeKind: 'MODIFIED', additions: 3,
          deletions: 1, binary: false, patchTruncated: false, patchHash: '9'.repeat(64),
          absolutePath: '/private/worktree/src/Main.java',
        }],
        createdAt: '2026-08-20T01:01:00Z', containerId: 'private-container',
      },
      codingResult: {
        schemaVersion: '1', executionWorkspaceId: workspaceId, workspaceFingerprint: '2'.repeat(64),
        codingTargetSnapshotId: crypto.randomUUID(), codingTargetRevision: 1,
        codingTargetHash: 'a'.repeat(64), diffArtifactId: crypto.randomUUID(),
        diffArtifactHash: 'b'.repeat(64), testEvidenceId: crypto.randomUUID(),
        testEvidenceHash: 'c'.repeat(64), completedAt: '2026-08-20T01:02:00Z',
        taskToken: 'private-token',
      },
      commandEvidenceCount: 1,
      testEvidenceCount: 1,
    },
  }
}

function commandPayload(): Record<string, unknown> {
  return {
    id: crypto.randomUUID(), sequence: 1, commandKind: 'TEST', toolKey: 'coding.maven.test',
    timeoutSeconds: 60, startedAt: '2026-08-20T01:00:00Z', finishedAt: '2026-08-20T01:01:00Z',
    termination: 'EXITED', exitCode: 0, summary: 'passed', failureClassification: null,
    evidenceHash: '3'.repeat(64), typedArgv: ['mvn', 'test'],
    commandLog: artifact('COMMAND_LOG', { storageUri: 'file:///private/log' }),
  }
}

function testPayload(): Record<string, unknown> {
  return {
    id: crypto.randomUUID(), sequence: 1, diffGeneration: 1, diffManifestHash: '4'.repeat(64),
    total: 10, passed: 10, failed: 0, errors: 0, skipped: 0, summary: 'passed',
    failureClassification: null, evidenceHash: '5'.repeat(64), commandEvidenceIds: [],
    acceptance: [{ criterionIndex: 0, criterion: 'tests pass', status: 'PASSED', summary: 'passed', commandEvidenceIds: [] }],
    testReport: artifact('TEST_REPORT', { storageUri: 'file:///private/report' }),
    createdAt: '2026-08-20T01:01:00Z',
  }
}

function artifact(kind: string, extra: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    artifactId: crypto.randomUUID(), kind, contentType: 'text/plain', sizeBytes: 10,
    contentHash: '6'.repeat(64), ...extra,
  }
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}
