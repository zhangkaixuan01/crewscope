import { apiClient, type CrewScopeApiClient } from '../../api/client'
import type {
  AcceptanceEvidenceSummary,
  ArtifactBytePage,
  ArtifactSummary,
  BuildProfileSummary,
  CodingAttemptDetails,
  CodingAttemptSummary,
  PatchBytePage,
  CodingResultSummary,
  CodingSandboxSummary,
  CodingScope,
  CodingWorkspaceSummary,
  CommandEvidenceSummary,
  CurrentCodingAttempt,
  DiffManifestSummary,
  EvidencePage,
  RepositoryBinding,
  RepositoryBindingInput,
  RepositoryCatalogItem,
  RepositoryCommandReceipt,
  RepositoryPreflight,
  TestEvidenceSummary,
} from './types'

export type RepositoryTransition = 'activate' | 'disable'

export interface CodingGateway {
  listRepositoryCatalog(scope: CodingScope, signal?: AbortSignal): Promise<RepositoryCatalogItem[]>
  listRepositoryBindings(scope: CodingScope, signal?: AbortSignal): Promise<RepositoryBinding[]>
  getRepositoryBinding(scope: CodingScope, bindingId: string, signal?: AbortSignal): Promise<RepositoryBinding>
  createRepositoryBinding(
    scope: CodingScope,
    input: RepositoryBindingInput,
    idempotencyKey: string,
  ): Promise<RepositoryCommandReceipt>
  preflightRepositoryDraft(
    scope: CodingScope,
    input: RepositoryBindingInput,
    signal?: AbortSignal,
  ): Promise<RepositoryPreflight>
  preflightRepositoryBinding(
    scope: CodingScope,
    bindingId: string,
    signal?: AbortSignal,
  ): Promise<RepositoryPreflight>
  transitionRepositoryBinding(
    scope: CodingScope,
    bindingId: string,
    transition: RepositoryTransition,
    expectedVersion: number,
    idempotencyKey: string,
  ): Promise<RepositoryCommandReceipt>
  listBuildProfiles(scope: CodingScope, workItemId: string, signal?: AbortSignal): Promise<BuildProfileSummary[]>
  preflightCodingTarget(
    scope: CodingScope,
    workItemId: string,
    bindingId: string,
    baselineRef: string,
    signal?: AbortSignal,
  ): Promise<RepositoryPreflight>
  getCurrentAttempt(scope: CodingScope, taskId: string, signal?: AbortSignal): Promise<CurrentCodingAttempt>
  listAttempts(scope: CodingScope, taskId: string, signal?: AbortSignal): Promise<CodingAttemptSummary[]>
  getAttempt(
    scope: CodingScope,
    taskId: string,
    executionId: string,
    signal?: AbortSignal,
  ): Promise<CodingAttemptSummary>
  listCommands(
    scope: CodingScope,
    taskId: string,
    executionId: string,
    after?: string,
    limit?: number,
    signal?: AbortSignal,
  ): Promise<EvidencePage<CommandEvidenceSummary>>
  listTestEvidence(
    scope: CodingScope,
    taskId: string,
    executionId: string,
    after?: string,
    limit?: number,
    signal?: AbortSignal,
  ): Promise<EvidencePage<TestEvidenceSummary>>
  readPatchPage(
    scope: CodingScope,
    taskId: string,
    executionId: string,
    offset: number,
    limit: number,
    signal?: AbortSignal,
  ): Promise<PatchBytePage>
  readCommandLogPage(
    scope: CodingScope,
    taskId: string,
    executionId: string,
    commandEvidenceId: string,
    offset: number,
    limit: number,
    signal?: AbortSignal,
  ): Promise<ArtifactBytePage>
  readTestReportPage(
    scope: CodingScope,
    taskId: string,
    executionId: string,
    testEvidenceId: string,
    offset: number,
    limit: number,
    signal?: AbortSignal,
  ): Promise<ArtifactBytePage>
}

/** Member-facing Coding HTTP adapter with an explicit browser disclosure whitelist. */
export class HttpCodingGateway implements CodingGateway {
  constructor(private readonly client: CrewScopeApiClient = apiClient) {}

  async listRepositoryCatalog(scope: CodingScope, signal?: AbortSignal): Promise<RepositoryCatalogItem[]> {
    const value = await this.client.get<{ items: RepositoryCatalogItem[] }>(repositoryCatalogRoot(scope), { signal })
    return value.items.map(item => ({ ...pick(item, [
      'repositoryKey', 'availability', 'suggestedDefaultBranch',
    ]) }))
  }

  async listRepositoryBindings(scope: CodingScope, signal?: AbortSignal): Promise<RepositoryBinding[]> {
    const value = await this.client.get<{ items: RepositoryBinding[] }>(repositoryRoot(scope), { signal })
    return value.items.map(mapRepositoryBinding)
  }

  async getRepositoryBinding(
    scope: CodingScope,
    bindingId: string,
    signal?: AbortSignal,
  ): Promise<RepositoryBinding> {
    const value = await this.client.get<RepositoryBinding>(
      `${repositoryRoot(scope)}/${segment(bindingId)}`,
      { signal },
    )
    return mapRepositoryBinding(value)
  }

  async createRepositoryBinding(
    scope: CodingScope,
    input: RepositoryBindingInput,
    idempotencyKey: string,
  ): Promise<RepositoryCommandReceipt> {
    const value = await this.client.post<RepositoryCommandReceipt>(repositoryRoot(scope), input, { idempotencyKey })
    return mapReceipt(value)
  }

  async preflightRepositoryDraft(
    scope: CodingScope,
    input: RepositoryBindingInput,
    signal?: AbortSignal,
  ): Promise<RepositoryPreflight> {
    const value = await this.client.post<RepositoryPreflight>(
      `${repositoryRoot(scope)}/preflight`,
      input,
      { signal },
    )
    return mapPreflight(value)
  }

  async preflightRepositoryBinding(
    scope: CodingScope,
    bindingId: string,
    signal?: AbortSignal,
  ): Promise<RepositoryPreflight> {
    const value = await this.client.post<RepositoryPreflight>(
      `${repositoryRoot(scope)}/${segment(bindingId)}/preflight`,
      undefined,
      { signal },
    )
    return mapPreflight(value)
  }

  async transitionRepositoryBinding(
    scope: CodingScope,
    bindingId: string,
    transition: RepositoryTransition,
    expectedVersion: number,
    idempotencyKey: string,
  ): Promise<RepositoryCommandReceipt> {
    const value = await this.client.post<RepositoryCommandReceipt>(
      `${repositoryRoot(scope)}/${segment(bindingId)}/${transition}`,
      undefined,
      { expectedVersion, idempotencyKey },
    )
    return mapReceipt(value)
  }

  async listBuildProfiles(
    scope: CodingScope,
    workItemId: string,
    signal?: AbortSignal,
  ): Promise<BuildProfileSummary[]> {
    const value = await this.client.get<{ items: BuildProfileSummary[] }>(
      `${codingTargetRoot(scope, workItemId)}/build-profiles`,
      { signal },
    )
    return value.items.map(item => ({
      ...pick(item, ['key', 'version', 'profileHash', 'buildTool', 'javaRelease']),
      commandKinds: [...item.commandKinds],
    }))
  }

  async preflightCodingTarget(
    scope: CodingScope,
    workItemId: string,
    bindingId: string,
    baselineRef: string,
    signal?: AbortSignal,
  ): Promise<RepositoryPreflight> {
    const value = await this.client.post<RepositoryPreflight>(
      `${codingTargetRoot(scope, workItemId)}/preflight`,
      { repositoryBindingId: bindingId, baselineRef },
      { signal },
    )
    return mapPreflight(value)
  }

  async getCurrentAttempt(scope: CodingScope, taskId: string, signal?: AbortSignal): Promise<CurrentCodingAttempt> {
    const value = await this.client.get<CurrentCodingAttempt>(`${taskRoot(scope, taskId)}/coding`, { signal })
    return {
      taskId: value.taskId,
      currentAttempt: value.currentAttempt ? mapAttempt(value.currentAttempt) : null,
    }
  }

  async listAttempts(scope: CodingScope, taskId: string, signal?: AbortSignal): Promise<CodingAttemptSummary[]> {
    const value = await this.client.get<CodingAttemptSummary[]>(
      `${taskRoot(scope, taskId)}/coding-attempts`,
      { signal },
    )
    return value.map(mapAttempt)
  }

  async getAttempt(
    scope: CodingScope,
    taskId: string,
    executionId: string,
    signal?: AbortSignal,
  ): Promise<CodingAttemptSummary> {
    const value = await this.client.get<CodingAttemptSummary>(
      `${taskRoot(scope, taskId)}/attempts/${segment(executionId)}/coding`,
      { signal },
    )
    return mapAttempt(value)
  }

  async listCommands(
    scope: CodingScope,
    taskId: string,
    executionId: string,
    after?: string,
    limit = 50,
    signal?: AbortSignal,
  ): Promise<EvidencePage<CommandEvidenceSummary>> {
    const value = await this.client.get<EvidencePage<CommandEvidenceSummary>>(
      `${taskRoot(scope, taskId)}/attempts/${segment(executionId)}/coding/commands?${pageSearch(after, limit)}`,
      { signal },
    )
    return { items: value.items.map(mapCommandEvidence), nextCursor: value.nextCursor }
  }

  async listTestEvidence(
    scope: CodingScope,
    taskId: string,
    executionId: string,
    after?: string,
    limit = 50,
    signal?: AbortSignal,
  ): Promise<EvidencePage<TestEvidenceSummary>> {
    const value = await this.client.get<EvidencePage<TestEvidenceSummary>>(
      `${taskRoot(scope, taskId)}/attempts/${segment(executionId)}/coding/test-evidence?${pageSearch(after, limit)}`,
      { signal },
    )
    return { items: value.items.map(mapTestEvidence), nextCursor: value.nextCursor }
  }

  async readPatchPage(
    scope: CodingScope,
    taskId: string,
    executionId: string,
    offset: number,
    limit: number,
    signal?: AbortSignal,
  ): Promise<PatchBytePage> {
    return this.readArtifactPage(
      `${taskRoot(scope, taskId)}/attempts/${segment(executionId)}/coding/artifacts/patch`,
      offset,
      limit,
      ['text/x-diff'],
      signal,
    )
  }

  async readCommandLogPage(
    scope: CodingScope,
    taskId: string,
    executionId: string,
    commandEvidenceId: string,
    offset: number,
    limit: number,
    signal?: AbortSignal,
  ): Promise<ArtifactBytePage> {
    const page = await this.readArtifactPage(
      `${taskRoot(scope, taskId)}/attempts/${segment(executionId)}/coding/commands/${segment(commandEvidenceId)}/log`,
      offset,
      limit,
      ['text/plain'],
      signal,
    )
    if (!page.filename) throw new TypeError('Coding Artifact download filename is missing')
    return page
  }

  async readTestReportPage(
    scope: CodingScope,
    taskId: string,
    executionId: string,
    testEvidenceId: string,
    offset: number,
    limit: number,
    signal?: AbortSignal,
  ): Promise<ArtifactBytePage> {
    const page = await this.readArtifactPage(
      `${taskRoot(scope, taskId)}/attempts/${segment(executionId)}/coding/test-evidence/${segment(testEvidenceId)}/report`,
      offset,
      limit,
      ['text/plain', 'application/json', 'application/xml', 'text/xml'],
      signal,
    )
    if (!page.filename) throw new TypeError('Coding Artifact download filename is missing')
    return page
  }

  private async readArtifactPage(
    path: string,
    offset: number,
    limit: number,
    acceptedContentTypes: string[],
    signal?: AbortSignal,
  ): Promise<ArtifactBytePage> {
    if (!Number.isSafeInteger(offset) || offset < 0 || !Number.isSafeInteger(limit) || limit < 1) {
      throw new TypeError('Artifact byte page coordinates are invalid')
    }
    const search = new URLSearchParams({ offset: String(offset), limit: String(limit) })
    const response = await this.client.open(`${path}?${search}`, { signal }, acceptedContentTypes.join(', '))
    const contentType = response.headers.get('Content-Type')?.toLowerCase() ?? ''
    if (!acceptedContentTypes.some(value => contentType.startsWith(value))) {
      throw new TypeError('Invalid Coding Artifact content type')
    }
    const bytes = new Uint8Array(await response.arrayBuffer())
    const range = parseContentRange(response.headers.get('Content-Range'), response.status, offset, bytes.byteLength)
    if (range.length !== bytes.byteLength) throw new TypeError('Coding Artifact byte range length does not match its body')
    return {
      bytes,
      ...range,
      etag: response.headers.get('ETag'),
      contentType,
      filename: dispositionFilename(response.headers.get('Content-Disposition')),
    }
  }
}

function repositoryRoot(scope: CodingScope): string {
  return `/organizations/${segment(scope.organizationId)}/teams/${segment(scope.teamId)}`
    + `/work-projects/${segment(scope.projectId)}/repository-bindings`
}

function repositoryCatalogRoot(scope: CodingScope): string {
  return `/organizations/${segment(scope.organizationId)}/teams/${segment(scope.teamId)}`
    + `/work-projects/${segment(scope.projectId)}/repository-catalog`
}

function codingTargetRoot(scope: CodingScope, workItemId: string): string {
  return `/organizations/${segment(scope.organizationId)}/teams/${segment(scope.teamId)}`
    + `/work-projects/${segment(scope.projectId)}/work-items/${segment(workItemId)}/coding-target`
}

function taskRoot(scope: CodingScope, taskId: string): string {
  return `/organizations/${segment(scope.organizationId)}/teams/${segment(scope.teamId)}/tasks/${segment(taskId)}`
}

function pageSearch(after?: string, limit = 50): URLSearchParams {
  const search = new URLSearchParams({ limit: String(limit) })
  if (after) search.set('after', after)
  return search
}

function segment(value: string): string {
  return encodeURIComponent(value)
}

function parseContentRange(
  value: string | null,
  status: number,
  requestedOffset: number,
  bodyLength: number,
): Pick<ArtifactBytePage, 'offset' | 'length' | 'totalSize'> {
  if (status === 200 && requestedOffset === 0) {
    return { offset: 0, length: bodyLength, totalSize: bodyLength }
  }
  const match = /^bytes (\d+)-(\d+)\/(\d+)$/.exec(value ?? '')
  if (status !== 206 || !match) throw new TypeError('Invalid Coding Patch Content-Range')
  const start = Number(match[1])
  const end = Number(match[2])
  const totalSize = Number(match[3])
  if (![start, end, totalSize].every(Number.isSafeInteger)
    || start !== requestedOffset || end < start || end >= totalSize) {
    throw new TypeError('Invalid Coding Patch byte coordinates')
  }
  return { offset: start, length: end - start + 1, totalSize }
}

/** Accept only the server-provided attachment filename and discard all path components. */
function dispositionFilename(value: string | null): string | null {
  if (!value) return null
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(value)?.[1]
  const quoted = /filename="([^"]+)"/i.exec(value)?.[1]
  let filename = encoded ?? quoted ?? null
  if (!filename) return null
  try { filename = decodeURIComponent(filename) } catch { return null }
  filename = filename.split(/[\\/]/).at(-1)?.trim() ?? ''
  if (!filename || filename.length > 255 || ['.', '..'].includes(filename)
    || [...filename].some(character => {
      const point = character.codePointAt(0)!
      return point < 0x20 || point === 0x7f
    })) return null
  return filename
}

function mapRepositoryBinding(value: RepositoryBinding): RepositoryBinding {
  return { ...pick(value, [
    'id', 'organizationId', 'teamId', 'workspaceId', 'projectId', 'kind', 'repositoryKey',
    'defaultBranch', 'status', 'version', 'createdAt', 'createdByPrincipalId', 'updatedAt',
    'updatedByPrincipalId',
  ]) }
}

function mapPreflight(value: RepositoryPreflight): RepositoryPreflight {
  return { ...pick(value, ['ready', 'repositoryKey', 'baselineRef', 'baselineCommit']) }
}

function mapReceipt(value: RepositoryCommandReceipt): RepositoryCommandReceipt {
  return { ...pick(value, ['commandId', 'domainEventId', 'committedVersion', 'correlationId']) }
}

function mapAttempt(value: CodingAttemptSummary): CodingAttemptSummary {
  return {
    ...pick(value, ['executionId', 'attempt', 'executionStatus', 'current', 'coding']),
    details: value.details ? mapDetails(value.details) : null,
  }
}

function mapDetails(value: CodingAttemptDetails): CodingAttemptDetails {
  return {
    ...pick(value, ['executionId', 'attempt', 'commandEvidenceCount', 'testEvidenceCount']),
    workspace: mapWorkspace(value.workspace),
    sandbox: value.sandbox ? mapSandbox(value.sandbox) : null,
    diffManifest: value.diffManifest ? mapDiff(value.diffManifest) : null,
    codingResult: value.codingResult ? mapResult(value.codingResult) : null,
  }
}

function mapWorkspace(value: CodingWorkspaceSummary): CodingWorkspaceSummary {
  return { ...pick(value, [
    'id', 'repositoryKey', 'baselineCommit', 'managedBranch', 'status', 'recoveryGeneration',
    'completionReason', 'failureCode', 'fingerprint', 'version', 'retainUntil', 'createdAt', 'updatedAt',
  ]) }
}

function mapSandbox(value: CodingSandboxSummary): CodingSandboxSummary {
  return { ...pick(value, [
    'networkMode', 'cpuCount', 'memoryMiB', 'pids', 'maxCommandDurationSeconds',
    'maxCommandOutputBytes', 'readOnlyRootFilesystem', 'maxCommandCalls', 'maxChangedFiles',
    'maxSingleFileBytes', 'maxWriteOperations', 'maxWrittenBytes', 'maxDiffBytes',
    'maxTestRepairRounds', 'buildProfileKey', 'buildProfileVersion',
  ]) }
}

function mapDiff(value: DiffManifestSummary): DiffManifestSummary {
  return {
    ...pick(value, [
      'artifactId', 'generation', 'manifestHash', 'fileCount', 'additions', 'deletions',
      'baselineCommit', 'deliveryCommit', 'finalHash', 'createdAt',
    ]),
    patch: mapArtifact(value.patch),
    files: value.files.map(item => ({ ...pick(item, [
      'ordinal', 'path', 'oldPath', 'changeKind', 'additions', 'deletions', 'binary',
      'patchTruncated', 'patchHash',
    ]) })),
  }
}

function mapResult(value: CodingResultSummary): CodingResultSummary {
  return { ...pick(value, [
    'schemaVersion', 'executionWorkspaceId', 'workspaceFingerprint', 'codingTargetSnapshotId',
    'codingTargetRevision', 'codingTargetHash', 'diffArtifactId', 'diffArtifactHash',
    'testEvidenceId', 'testEvidenceHash', 'completedAt',
  ]) }
}

function mapArtifact(value: ArtifactSummary): ArtifactSummary {
  return { ...pick(value, ['artifactId', 'kind', 'contentType', 'sizeBytes', 'contentHash']) }
}

function mapCommandEvidence(value: CommandEvidenceSummary): CommandEvidenceSummary {
  return {
    ...pick(value, [
      'id', 'sequence', 'commandKind', 'toolKey', 'timeoutSeconds', 'startedAt', 'finishedAt',
      'termination', 'exitCode', 'summary', 'failureClassification', 'evidenceHash',
    ]),
    commandLog: mapArtifact(value.commandLog),
  }
}

function mapTestEvidence(value: TestEvidenceSummary): TestEvidenceSummary {
  return {
    ...pick(value, [
      'id', 'sequence', 'diffGeneration', 'diffManifestHash', 'total', 'passed', 'failed',
      'errors', 'skipped', 'summary', 'failureClassification', 'evidenceHash', 'createdAt',
    ]),
    commandEvidenceIds: [...value.commandEvidenceIds],
    acceptance: value.acceptance.map(mapAcceptance),
    testReport: value.testReport ? mapArtifact(value.testReport) : null,
  }
}

function mapAcceptance(value: AcceptanceEvidenceSummary): AcceptanceEvidenceSummary {
  return {
    ...pick(value, ['criterionIndex', 'criterion', 'status', 'summary']),
    commandEvidenceIds: [...value.commandEvidenceIds],
  }
}

function pick<T extends object, K extends keyof T>(value: T, keys: readonly K[]): Pick<T, K> {
  return Object.fromEntries(keys.map(key => [key, value[key]])) as Pick<T, K>
}
