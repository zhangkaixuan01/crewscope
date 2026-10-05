import { apiClient, CrewScopeApiError, type CrewScopeApiClient } from '../../api/client'
import type { Etagged, SettingsScope } from '../settings/types'
import { skillStatuses as skillStatusValues } from './types'
import type {
  CreateSkillInput,
  DistillSkillInput,
  SkillCommandReceipt,
  SkillDistillationReceipt,
  SkillDraft,
  SkillFilter,
  SkillOrigin,
  SkillPage,
  SkillStatus,
  SkillSummary,
  SkillVersion,
  SkillVersionPage,
  UpdateSkillDraftInput,
} from './types'

export interface SkillGateway {
  listSkills(scope: SettingsScope, filter: SkillFilter, after?: string | null, limit?: number, signal?: AbortSignal): Promise<SkillPage>
  getSkill(scope: SettingsScope, skillId: string, signal?: AbortSignal): Promise<Etagged<SkillSummary>>
  listVersions(scope: SettingsScope, skillId: string, after?: number | null, limit?: number, signal?: AbortSignal): Promise<SkillVersionPage>
  getVersion(scope: SettingsScope, skillId: string, revision: number, signal?: AbortSignal): Promise<Etagged<SkillVersion>>
  /** Resolves null when no revision is effective (DRAFT/DISABLED head — §7's uniform 404 is an answer). */
  getEffectiveVersion(scope: SettingsScope, skillId: string, signal?: AbortSignal): Promise<Etagged<SkillVersion> | null>
  createSkill(scope: SettingsScope, input: CreateSkillInput, idempotencyKey: string): Promise<SkillCommandReceipt>
  saveDraft(scope: SettingsScope, skillId: string, input: UpdateSkillDraftInput, etag: string, idempotencyKey: string): Promise<SkillCommandReceipt>
  publishSkill(scope: SettingsScope, skillId: string, etag: string, idempotencyKey: string): Promise<SkillCommandReceipt>
  disableSkill(scope: SettingsScope, skillId: string, reason: string | null, etag: string, idempotencyKey: string): Promise<SkillCommandReceipt>
  rollbackSkill(scope: SettingsScope, skillId: string, toRevision: number, etag: string, idempotencyKey: string): Promise<SkillCommandReceipt>
  /** Reads `Idempotency-Replayed` so the receipt panel can distinguish a replay (contract §11). */
  distill(scope: SettingsScope, input: DistillSkillInput, idempotencyKey: string): Promise<SkillDistillationReceipt>
}

/** M10-A03 HTTP adapter that admits only the contract's closed DTO whitelist. */
export class HttpSkillGateway implements SkillGateway {
  constructor(private readonly client: CrewScopeApiClient = apiClient) {}

  async listSkills(
    scope: SettingsScope,
    filter: SkillFilter,
    after?: string | null,
    limit = 50,
    signal?: AbortSignal,
  ): Promise<SkillPage> {
    const search = new URLSearchParams({ limit: String(limit) })
    if (filter.status) search.set('status', filter.status)
    if (after) search.set('after', after)
    const value = await this.client.get<{ items: unknown[]; nextAfter: string | null }>(
      `${root(scope)}?${search}`,
      { signal },
    )
    return { items: value.items.map(mapSkill), nextAfter: value.nextAfter ?? null }
  }

  async getSkill(scope: SettingsScope, skillId: string, signal?: AbortSignal): Promise<Etagged<SkillSummary>> {
    const response = await this.client.open(`${root(scope)}/${segment(skillId)}`, { method: 'GET', signal })
    return { value: mapSkill(await response.json()), etag: requireStrongEtag(response, 'Skill') }
  }

  async listVersions(
    scope: SettingsScope,
    skillId: string,
    after?: number | null,
    limit = 50,
    signal?: AbortSignal,
  ): Promise<SkillVersionPage> {
    const search = new URLSearchParams({ limit: String(limit) })
    if (after != null && after >= 1) search.set('after', String(after))
    const value = await this.client.get<{ items: unknown[]; nextAfter: number | null }>(
      `${root(scope)}/${segment(skillId)}/versions?${search}`,
      { signal },
    )
    return { items: value.items.map(mapVersion), nextAfter: value.nextAfter ?? null }
  }

  async getVersion(scope: SettingsScope, skillId: string, revision: number, signal?: AbortSignal): Promise<Etagged<SkillVersion>> {
    const response = await this.client.open(
      `${root(scope)}/${segment(skillId)}/versions/${revision}`,
      { method: 'GET', signal },
    )
    return { value: mapVersion(await response.json()), etag: requireStrongEtag(response, 'Skill Version') }
  }

  async getEffectiveVersion(scope: SettingsScope, skillId: string, signal?: AbortSignal): Promise<Etagged<SkillVersion> | null> {
    try {
      const response = await this.client.open(
        `${root(scope)}/${segment(skillId)}/effective-version`,
        { method: 'GET', signal },
      )
      return { value: mapVersion(await response.json()), etag: requireStrongEtag(response, 'Skill Effective Version') }
    } catch (error) {
      // A shape-preserving 404 means "resolved: no effective revision" (DRAFT/DISABLED head) —
      // an answer, not a failure.
      if (error instanceof CrewScopeApiError && error.status === 404) return null
      throw error
    }
  }

  async createSkill(
    scope: SettingsScope,
    input: CreateSkillInput,
    idempotencyKey: string,
  ): Promise<SkillCommandReceipt> {
    const value = await this.client.post<SkillCommandReceipt>(root(scope), { skillKey: input.skillKey, content: input.content }, { idempotencyKey })
    return mapReceipt(value)
  }

  async saveDraft(
    scope: SettingsScope,
    skillId: string,
    input: UpdateSkillDraftInput,
    etag: string,
    idempotencyKey: string,
  ): Promise<SkillCommandReceipt> {
    const value = await this.client.request<SkillCommandReceipt>(
      `${root(scope)}/${segment(skillId)}`,
      { method: 'PATCH', body: { content: input.content }, expectedVersion: etagVersion(etag), idempotencyKey },
    )
    return mapReceipt(value)
  }

  async publishSkill(scope: SettingsScope, skillId: string, etag: string, idempotencyKey: string): Promise<SkillCommandReceipt> {
    const value = await this.client.post<SkillCommandReceipt>(
      `${root(scope)}/${segment(skillId)}/publish`,
      undefined,
      { expectedVersion: etagVersion(etag), idempotencyKey },
    )
    return mapReceipt(value)
  }

  async disableSkill(
    scope: SettingsScope,
    skillId: string,
    reason: string | null,
    etag: string,
    idempotencyKey: string,
  ): Promise<SkillCommandReceipt> {
    // Contract §2: the disable body is optional and only carries `reason` when present.
    const value = await this.client.post<SkillCommandReceipt>(
      `${root(scope)}/${segment(skillId)}/disable`,
      reason ? { reason } : undefined,
      { expectedVersion: etagVersion(etag), idempotencyKey },
    )
    return mapReceipt(value)
  }

  async rollbackSkill(
    scope: SettingsScope,
    skillId: string,
    toRevision: number,
    etag: string,
    idempotencyKey: string,
  ): Promise<SkillCommandReceipt> {
    const value = await this.client.post<SkillCommandReceipt>(
      `${root(scope)}/${segment(skillId)}/rollback`,
      { toRevision },
      { expectedVersion: etagVersion(etag), idempotencyKey },
    )
    return mapReceipt(value)
  }

  async distill(
    scope: SettingsScope,
    input: DistillSkillInput,
    idempotencyKey: string,
  ): Promise<SkillDistillationReceipt> {
    const response = await this.client.open(distillationsRoot(scope), {
      method: 'POST',
      body: { taskExecutionId: input.taskExecutionId, skillKey: input.skillKey },
      idempotencyKey,
    })
    const value = await response.json() as Partial<SkillDistillationReceipt>
    // On a replay the stored envelope has no result body (contract §11): skillId/status/origin
    // stay absent and only the echoed skillKey locates the skill.
    return {
      ...mapReceipt(value as SkillCommandReceipt),
      skillId: typeof value.skillId === 'string' ? value.skillId : null,
      skillKey: input.skillKey,
      status: value.status != null ? skillStatus(value.status) : null,
      origin: value.origin ? mapOrigin(value.origin) : null,
      replayed: response.headers.get('Idempotency-Replayed') === 'true',
    }
  }
}

function root(scope: SettingsScope): string {
  return `/organizations/${segment(scope.organizationId)}/teams/${segment(scope.teamId)}/skills`
}

/** Contract §1: distillation commands live under the skills collection root, beside the items. */
function distillationsRoot(scope: SettingsScope): string {
  return `/organizations/${segment(scope.organizationId)}/teams/${segment(scope.teamId)}/skills/distillations`
}

function segment(value: string): string {
  return encodeURIComponent(value)
}

function mapSkill(value: unknown): SkillSummary {
  const skill = value as SkillSummary
  return {
    ...pick(skill, [
      'id', 'skillKey', 'effectiveRevision', 'latestRevision', 'disableReason', 'version',
      'createdAt', 'updatedAt', 'createdBy', 'updatedBy',
    ]),
    status: skillStatus(skill.status),
    draft: skill.draft ? mapDraft(skill.draft) : null,
    origin: skill.origin ? mapOrigin(skill.origin) : null,
  }
}

function mapVersion(value: unknown): SkillVersion {
  // No indexStatus here by design: skills never enter the knowledge index (A03 §10).
  const version = value as SkillVersion
  return {
    ...pick(version, [
      'skillId', 'revision', 'previousRevision', 'content', 'contentHash', 'createdAt', 'createdBy',
    ]),
  }
}

function mapDraft(value: SkillDraft): SkillDraft {
  return { ...pick(value, ['name', 'description', 'content']) }
}

function mapOrigin(value: SkillOrigin): SkillOrigin {
  return { ...pick(value, ['taskExecutionId', 'attempt']) }
}

function mapReceipt(value: SkillCommandReceipt): SkillCommandReceipt {
  return { ...pick(value, ['commandId', 'domainEventId', 'committedVersion', 'correlationId']) }
}

function skillStatus(value: string): SkillStatus {
  if ((skillStatusValues as readonly string[]).includes(value)) return value as SkillStatus
  throw new TypeError('Skill status is invalid')
}

function requireStrongEtag(response: Response, resource: string): string {
  const etag = response.headers.get('ETag')
  if (!etag || etag.startsWith('W/') || !/^"[^"]+"$/.test(etag)) {
    throw new TypeError(`${resource} strong ETag is missing`)
  }
  return etag
}

function etagVersion(etag: string): number {
  if (!/^"\d+"$/.test(etag)) throw new TypeError('Skill response ETag is invalid')
  const version = Number(etag.slice(1, -1))
  if (!Number.isSafeInteger(version)) throw new TypeError('Skill response ETag is invalid')
  return version
}

function pick<T extends object, K extends keyof T>(value: T, keys: readonly K[]): Pick<T, K> {
  return Object.fromEntries(keys.map(key => [key, value[key]])) as Pick<T, K>
}
