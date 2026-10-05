import { apiClient, CrewScopeApiError, type CrewScopeApiClient } from '../../api/client'
import type { Etagged, SettingsScope } from '../settings/types'
import {
  knowledgeCategories as knowledgeCategoryValues,
  knowledgeEntryStatuses as knowledgeEntryStatusValues,
  knowledgeIndexJobSources as knowledgeIndexJobSourceValues,
  knowledgeIndexJobStatuses as knowledgeIndexJobStatusValues,
  knowledgeIndexStatuses as knowledgeIndexStatusValues,
} from './types'
import type {
  CreateKnowledgeEntryInput,
  DistillKnowledgeInput,
  KnowledgeCategory,
  KnowledgeCommandReceipt,
  KnowledgeDistillationReceipt,
  KnowledgeDraft,
  KnowledgeEntryFilter,
  KnowledgeEntryPage,
  KnowledgeEntryStatus,
  KnowledgeEntrySummary,
  KnowledgeIndexJob,
  KnowledgeIndexJobFilter,
  KnowledgeIndexJobPage,
  KnowledgeIndexJobSource,
  KnowledgeIndexJobStatus,
  KnowledgeIndexStatus,
  KnowledgeOrigin,
  KnowledgeVersion,
  KnowledgeVersionPage,
  RebuildAccepted,
  RepositoryBuildAccepted,
  RepositoryBuildInput,
  UpdateKnowledgeDraftInput,
} from './types'

export interface KnowledgeGateway {
  listEntries(scope: SettingsScope, filter: KnowledgeEntryFilter, after?: string | null, limit?: number, signal?: AbortSignal): Promise<KnowledgeEntryPage>
  getEntry(scope: SettingsScope, entryId: string, signal?: AbortSignal): Promise<Etagged<KnowledgeEntrySummary>>
  listVersions(scope: SettingsScope, entryId: string, after?: number | null, limit?: number, signal?: AbortSignal): Promise<KnowledgeVersionPage>
  getVersion(scope: SettingsScope, entryId: string, revision: number, signal?: AbortSignal): Promise<Etagged<KnowledgeVersion>>
  /** Resolves null when the head has no effective version (DRAFT/RETIRED/DELETED — §7's uniform 404). */
  getEffectiveVersion(scope: SettingsScope, entryId: string, signal?: AbortSignal): Promise<Etagged<KnowledgeVersion> | null>
  createEntry(scope: SettingsScope, input: CreateKnowledgeEntryInput, idempotencyKey: string): Promise<KnowledgeCommandReceipt>
  saveDraft(scope: SettingsScope, entryId: string, input: UpdateKnowledgeDraftInput, etag: string, idempotencyKey: string): Promise<KnowledgeCommandReceipt>
  publishEntry(scope: SettingsScope, entryId: string, etag: string, idempotencyKey: string): Promise<KnowledgeCommandReceipt>
  retireEntry(scope: SettingsScope, entryId: string, etag: string, idempotencyKey: string): Promise<KnowledgeCommandReceipt>
  deleteEntry(scope: SettingsScope, entryId: string, etag: string, idempotencyKey: string): Promise<KnowledgeCommandReceipt>
  /** Reads `Idempotency-Replayed` so the receipt panel can distinguish a replay (contract §11.1). */
  distill(scope: SettingsScope, input: DistillKnowledgeInput, idempotencyKey: string): Promise<KnowledgeDistillationReceipt>
  // ---------------------------------------------------------------- M10-I01c job control plane
  listJobs(scope: SettingsScope, filter: KnowledgeIndexJobFilter, after?: string | null, limit?: number, signal?: AbortSignal): Promise<KnowledgeIndexJobPage>
  getJob(scope: SettingsScope, jobId: string, signal?: AbortSignal): Promise<KnowledgeIndexJob>
  /** Structural idempotency (contract §2): no Idempotency-Key, no If-Match. */
  cancelJob(scope: SettingsScope, jobId: string): Promise<KnowledgeIndexJob>
  rebuild(scope: SettingsScope): Promise<RebuildAccepted>
  enqueueRepositoryBuild(scope: SettingsScope, input: RepositoryBuildInput): Promise<RepositoryBuildAccepted>
}

/** M10-A02 HTTP adapter that admits only the contract's closed DTO whitelist (§7). */
export class HttpKnowledgeGateway implements KnowledgeGateway {
  constructor(private readonly client: CrewScopeApiClient = apiClient) {}

  async listEntries(
    scope: SettingsScope,
    filter: KnowledgeEntryFilter,
    after?: string | null,
    limit = 50,
    signal?: AbortSignal,
  ): Promise<KnowledgeEntryPage> {
    const search = new URLSearchParams({ limit: String(limit) })
    if (filter.status) search.set('status', filter.status)
    if (filter.category) search.set('category', filter.category)
    if (after) search.set('after', after)
    const value = await this.client.get<{ items: unknown[]; nextAfter: string | null }>(
      `${root(scope)}?${search}`,
      { signal },
    )
    return { items: value.items.map(mapEntry), nextAfter: value.nextAfter ?? null }
  }

  async getEntry(scope: SettingsScope, entryId: string, signal?: AbortSignal): Promise<Etagged<KnowledgeEntrySummary>> {
    const response = await this.client.open(`${root(scope)}/${segment(entryId)}`, { method: 'GET', signal })
    return { value: mapEntry(await response.json()), etag: requireStrongEtag(response, 'Knowledge Entry') }
  }

  async listVersions(
    scope: SettingsScope,
    entryId: string,
    after?: number | null,
    limit = 50,
    signal?: AbortSignal,
  ): Promise<KnowledgeVersionPage> {
    const search = new URLSearchParams({ limit: String(limit) })
    if (after != null && after >= 1) search.set('after', String(after))
    const value = await this.client.get<{ items: unknown[]; nextAfter: number | null }>(
      `${root(scope)}/${segment(entryId)}/versions?${search}`,
      { signal },
    )
    return { items: value.items.map(mapVersion), nextAfter: value.nextAfter ?? null }
  }

  async getVersion(scope: SettingsScope, entryId: string, revision: number, signal?: AbortSignal): Promise<Etagged<KnowledgeVersion>> {
    const response = await this.client.open(
      `${root(scope)}/${segment(entryId)}/versions/${revision}`,
      { method: 'GET', signal },
    )
    return { value: mapVersion(await response.json()), etag: requireStrongEtag(response, 'Knowledge Version') }
  }

  async getEffectiveVersion(scope: SettingsScope, entryId: string, signal?: AbortSignal): Promise<Etagged<KnowledgeVersion> | null> {
    try {
      const response = await this.client.open(
        `${root(scope)}/${segment(entryId)}/effective-version`,
        { method: 'GET', signal },
      )
      return { value: mapVersion(await response.json()), etag: requireStrongEtag(response, 'Knowledge Effective Version') }
    } catch (error) {
      // A shape-preserving 404 means "resolved: no effective version" (§7) — an answer, not a failure.
      if (error instanceof CrewScopeApiError && error.status === 404) return null
      throw error
    }
  }

  async createEntry(
    scope: SettingsScope,
    input: CreateKnowledgeEntryInput,
    idempotencyKey: string,
  ): Promise<KnowledgeCommandReceipt> {
    const value = await this.client.post<KnowledgeCommandReceipt>(root(scope), entryBody(input), { idempotencyKey })
    return mapReceipt(value)
  }

  async saveDraft(
    scope: SettingsScope,
    entryId: string,
    input: UpdateKnowledgeDraftInput,
    etag: string,
    idempotencyKey: string,
  ): Promise<KnowledgeCommandReceipt> {
    const value = await this.client.request<KnowledgeCommandReceipt>(
      `${root(scope)}/${segment(entryId)}`,
      { method: 'PATCH', body: draftBody(input), expectedVersion: etagVersion(etag), idempotencyKey },
    )
    return mapReceipt(value)
  }

  async publishEntry(scope: SettingsScope, entryId: string, etag: string, idempotencyKey: string): Promise<KnowledgeCommandReceipt> {
    const value = await this.client.post<KnowledgeCommandReceipt>(
      `${root(scope)}/${segment(entryId)}/publish`,
      undefined,
      { expectedVersion: etagVersion(etag), idempotencyKey },
    )
    return mapReceipt(value)
  }

  async retireEntry(scope: SettingsScope, entryId: string, etag: string, idempotencyKey: string): Promise<KnowledgeCommandReceipt> {
    const value = await this.client.post<KnowledgeCommandReceipt>(
      `${root(scope)}/${segment(entryId)}/retire`,
      undefined,
      { expectedVersion: etagVersion(etag), idempotencyKey },
    )
    return mapReceipt(value)
  }

  async deleteEntry(scope: SettingsScope, entryId: string, etag: string, idempotencyKey: string): Promise<KnowledgeCommandReceipt> {
    const value = await this.client.request<KnowledgeCommandReceipt>(
      `${root(scope)}/${segment(entryId)}`,
      { method: 'DELETE', expectedVersion: etagVersion(etag), idempotencyKey },
    )
    return mapReceipt(value)
  }

  async distill(
    scope: SettingsScope,
    input: DistillKnowledgeInput,
    idempotencyKey: string,
  ): Promise<KnowledgeDistillationReceipt> {
    const body: Record<string, unknown> = { taskExecutionId: input.taskExecutionId, entryKey: input.entryKey }
    if (input.category) body.category = input.category
    const response = await this.client.open(distillationsRoot(scope), {
      method: 'POST',
      body,
      idempotencyKey,
    })
    const value = await response.json() as Partial<KnowledgeDistillationReceipt>
    // On a replay the stored envelope has no result body (§11.1): entryId/origin/indexStatus stay
    // absent and only the echoed entryKey locates the entry.
    return {
      ...mapReceipt(value as KnowledgeCommandReceipt),
      entryId: typeof value.entryId === 'string' ? value.entryId : null,
      entryKey: input.entryKey,
      origin: value.origin ? mapOrigin(value.origin) : null,
      indexStatus: value.indexStatus != null ? indexStatus(value.indexStatus) : null,
      replayed: response.headers.get('Idempotency-Replayed') === 'true',
    }
  }

  async listJobs(
    scope: SettingsScope,
    filter: KnowledgeIndexJobFilter,
    after?: string | null,
    limit = 50,
    signal?: AbortSignal,
  ): Promise<KnowledgeIndexJobPage> {
    const search = new URLSearchParams({ limit: String(limit) })
    if (filter.source) search.set('source', filter.source)
    if (filter.status) search.set('status', filter.status)
    if (after) search.set('after', after)
    const value = await this.client.get<{ items: unknown[]; nextAfter: string | null }>(
      `${indexRoot(scope)}/jobs?${search}`,
      { signal },
    )
    return { items: value.items.map(mapJob), nextAfter: value.nextAfter ?? null }
  }

  async getJob(scope: SettingsScope, jobId: string, signal?: AbortSignal): Promise<KnowledgeIndexJob> {
    const value = await this.client.get<unknown>(`${indexRoot(scope)}/jobs/${segment(jobId)}`, { signal })
    return mapJob(value)
  }

  async cancelJob(scope: SettingsScope, jobId: string): Promise<KnowledgeIndexJob> {
    const value = await this.client.post<unknown>(`${indexRoot(scope)}/jobs/${segment(jobId)}/cancel`, undefined)
    return mapJob(value)
  }

  async rebuild(scope: SettingsScope): Promise<RebuildAccepted> {
    return this.client.post<RebuildAccepted>(`${indexRoot(scope)}/rebuilds`, undefined)
  }

  async enqueueRepositoryBuild(scope: SettingsScope, input: RepositoryBuildInput): Promise<RepositoryBuildAccepted> {
    const value = await this.client.post<{ enqueued: number; job: unknown }>(
      `${indexRoot(scope)}/repository-builds`,
      { projectId: input.projectId, bindingId: input.bindingId, commit: input.commit },
    )
    return { enqueued: value.enqueued, job: value.job ? mapJob(value.job) : null }
  }
}

function root(scope: SettingsScope): string {
  return `/organizations/${segment(scope.organizationId)}/teams/${segment(scope.teamId)}/knowledge/entries`
}

/** Contract §1: distillation commands live beside the entries collection, not under it. */
function distillationsRoot(scope: SettingsScope): string {
  return `/organizations/${segment(scope.organizationId)}/teams/${segment(scope.teamId)}/knowledge/distillations`
}

/** M10-I01c control plane base: /knowledge/index, beside (never under) the entries collection. */
function indexRoot(scope: SettingsScope): string {
  return `/organizations/${segment(scope.organizationId)}/teams/${segment(scope.teamId)}/knowledge/index`
}

function segment(value: string): string {
  return encodeURIComponent(value)
}

function entryBody(input: CreateKnowledgeEntryInput): Record<string, unknown> {
  return { entryKey: input.entryKey, category: input.category, title: input.title, content: input.content }
}

function draftBody(input: UpdateKnowledgeDraftInput): Record<string, unknown> {
  const body: Record<string, unknown> = { title: input.title, content: input.content }
  if (input.category) body.category = input.category
  return body
}

function mapEntry(value: unknown): KnowledgeEntrySummary {
  const entry = value as KnowledgeEntrySummary
  return {
    ...pick(entry, [
      'id', 'entryKey', 'effectiveRevision', 'latestRevision', 'version',
      'createdAt', 'updatedAt', 'createdBy', 'updatedBy',
    ]),
    category: category(entry.category),
    status: entryStatus(entry.status),
    indexStatus: indexStatus(entry.indexStatus),
    draft: entry.draft ? mapDraft(entry.draft) : null,
    origin: entry.origin ? mapOrigin(entry.origin) : null,
  }
}

function mapVersion(value: unknown): KnowledgeVersion {
  const version = value as KnowledgeVersion
  return {
    ...pick(version, [
      'entryId', 'revision', 'previousRevision', 'title', 'content', 'contentHash', 'createdAt', 'createdBy',
    ]),
    indexStatus: indexStatus(version.indexStatus),
  }
}

function mapJob(value: unknown): KnowledgeIndexJob {
  const job = value as KnowledgeIndexJob
  return {
    ...pick(job, [
      'id', 'entryId', 'projectId', 'attempt', 'chunksDone', 'chunksTotal', 'failureCode',
      'generationBuildSequence', 'claimedBy', 'leaseExpiresAt', 'createdBy', 'createdAt', 'updatedAt',
    ]),
    source: jobSource(job.source),
    status: jobStatus(job.status),
    indexKey: job.indexKey
      ? { ...pick(job.indexKey, ['bindingId', 'commit', 'chunkPolicyHash', 'modelKey', 'modelRevision']) }
      : null,
  }
}

function jobSource(value: string): KnowledgeIndexJobSource {
  if ((knowledgeIndexJobSourceValues as readonly string[]).includes(value)) return value as KnowledgeIndexJobSource
  throw new TypeError('Knowledge index job source is invalid')
}

function jobStatus(value: string): KnowledgeIndexJobStatus {
  if ((knowledgeIndexJobStatusValues as readonly string[]).includes(value)) return value as KnowledgeIndexJobStatus
  throw new TypeError('Knowledge index job status is invalid')
}

function mapDraft(value: KnowledgeDraft): KnowledgeDraft {
  return { ...pick(value, ['title', 'content']) }
}

function mapOrigin(value: KnowledgeOrigin): KnowledgeOrigin {
  return { ...pick(value, ['taskExecutionId', 'attempt']) }
}

function mapReceipt(value: KnowledgeCommandReceipt): KnowledgeCommandReceipt {
  return { ...pick(value, ['commandId', 'domainEventId', 'committedVersion', 'correlationId']) }
}

function category(value: string): KnowledgeCategory {
  if ((knowledgeCategoryValues as readonly string[]).includes(value)) return value as KnowledgeCategory
  throw new TypeError('Knowledge category is invalid')
}

function entryStatus(value: string): KnowledgeEntryStatus {
  if ((knowledgeEntryStatusValues as readonly string[]).includes(value)) return value as KnowledgeEntryStatus
  throw new TypeError('Knowledge entry status is invalid')
}

function indexStatus(value: string): KnowledgeIndexStatus {
  if ((knowledgeIndexStatusValues as readonly string[]).includes(value)) return value as KnowledgeIndexStatus
  throw new TypeError('Knowledge index status is invalid')
}

function requireStrongEtag(response: Response, resource: string): string {
  const etag = response.headers.get('ETag')
  if (!etag || etag.startsWith('W/') || !/^"[^"]+"$/.test(etag)) {
    throw new TypeError(`${resource} strong ETag is missing`)
  }
  return etag
}

function etagVersion(etag: string): number {
  if (!/^"\d+"$/.test(etag)) throw new TypeError('Knowledge response ETag is invalid')
  const version = Number(etag.slice(1, -1))
  if (!Number.isSafeInteger(version)) throw new TypeError('Knowledge response ETag is invalid')
  return version
}

function pick<T extends object, K extends keyof T>(value: T, keys: readonly K[]): Pick<T, K> {
  return Object.fromEntries(keys.map(key => [key, value[key]])) as Pick<T, K>
}
