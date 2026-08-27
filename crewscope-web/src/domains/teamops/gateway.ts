import { apiClient, type CrewScopeApiClient } from '../../api/client'
import {
  auditActorTypes,
  auditEventCategories,
  auditOutcomes,
  auditRetentionLevels,
  correlationEventSources,
  correlationObjectTypes,
  inboxDispositionStatuses,
  inboxItemTypes,
  inboxPriorities,
  inboxSourceStatuses,
  inboxSourceTypes,
  inboxTargetKinds,
  larkConnectionStatuses,
  larkCredentialStatuses,
  larkHealthStatuses,
  larkMappingStatuses,
  larkMappingTerminalReasons,
  notificationDeliveryStatuses,
  notificationFailureCodes,
  notificationTemplateStatuses,
  notificationVariableTypes,
  operationsHealthComponents,
  operationsHealthLevels,
  operationsRecoveryActions,
  projectionGenerationStatuses,
  projectionRebuildStatuses,
} from './types'
import type {
  ActivityFilter,
  ActivityItem,
  ActivityPage,
  ActivitySnapshot,
  AdministratorDiagnostics,
  AuditEvent,
  AuditExport,
  AuditFilter,
  CommandReceipt,
  CorrelationGraph,
  CursorPage,
  Etagged,
  InboxCounts,
  InboxFilter,
  InboxItem,
  InboxTarget,
  LarkConnection,
  LarkHealth,
  LarkMapping,
  LarkPreflight,
  NotificationDelivery,
  NotificationPreference,
  NotificationTemplate,
  OperationsHealthSummary,
  ProjectionCommand,
  ProjectionCommandReceipt,
  RecoveryCandidate,
  RecoveryReceipt,
  TeamOpsScope,
  WorkItemActivityRoute,
} from './types'

export interface TeamOpsGateway {
  teamActivity(scope: TeamOpsScope, filter: ActivityFilter, after?: string | null, limit?: number, signal?: AbortSignal): Promise<ActivityPage>
  teamActivitySnapshot(scope: TeamOpsScope, filter: ActivityFilter, limit?: number, signal?: AbortSignal): Promise<ActivitySnapshot>
  teamActivityDetail(scope: TeamOpsScope, eventId: string, signal?: AbortSignal): Promise<ActivityItem>
  workItemActivity(scope: TeamOpsScope, route: WorkItemActivityRoute, filter: ActivityFilter, after?: string | null, limit?: number, signal?: AbortSignal): Promise<ActivityPage>
  workItemActivitySnapshot(scope: TeamOpsScope, route: WorkItemActivityRoute, filter: ActivityFilter, limit?: number, signal?: AbortSignal): Promise<ActivitySnapshot>
  workItemActivityDetail(scope: TeamOpsScope, route: WorkItemActivityRoute, eventId: string, signal?: AbortSignal): Promise<ActivityItem>
  openTeamActivity(scope: TeamOpsScope, filter: ActivityFilter, after?: string | null, signal?: AbortSignal): Promise<Response>

  inbox(scope: TeamOpsScope, filter: InboxFilter, after?: string | null, limit?: number, signal?: AbortSignal): Promise<CursorPage<InboxItem>>
  inboxCounts(scope: TeamOpsScope, signal?: AbortSignal): Promise<InboxCounts>
  inboxDetail(scope: TeamOpsScope, itemId: string, signal?: AbortSignal): Promise<Etagged<InboxItem>>
  inboxTarget(scope: TeamOpsScope, itemId: string, signal?: AbortSignal): Promise<InboxTarget>
  changeInboxDisposition(scope: TeamOpsScope, itemId: string, status: string, etag: string, idempotencyKey: string): Promise<CommandReceipt>

  audit(scope: TeamOpsScope, filter: AuditFilter, after?: string | null, limit?: number, signal?: AbortSignal): Promise<CursorPage<AuditEvent>>
  exportAudit(scope: TeamOpsScope, filter: AuditFilter, maximumRows: number, signal?: AbortSignal): Promise<AuditExport>
  correlation(scope: TeamOpsScope, correlationId: string, after?: string | null, limit?: number, signal?: AbortSignal): Promise<CorrelationGraph>

  larkConnections(scope: TeamOpsScope, signal?: AbortSignal): Promise<LarkConnection[]>
  larkConnection(scope: TeamOpsScope, connectionId: string, signal?: AbortSignal): Promise<Etagged<LarkConnection>>
  createLarkConnection(scope: TeamOpsScope, expectedVersion: number, input: CreateLarkConnectionInput, idempotencyKey: string): Promise<CommandReceipt>
  rotateLarkConnection(scope: TeamOpsScope, connectionId: string, etag: string, input: RotateLarkConnectionInput, idempotencyKey: string): Promise<CommandReceipt>
  revokeLarkConnection(scope: TeamOpsScope, connectionId: string, etag: string, reason: string, idempotencyKey: string): Promise<CommandReceipt>
  larkPreflight(scope: TeamOpsScope, bindingId: string, etag: string, signal?: AbortSignal): Promise<LarkPreflight>
  larkHealth(scope: TeamOpsScope, bindingId: string, signal?: AbortSignal): Promise<LarkHealth>
  verifyLarkMember(scope: TeamOpsScope, bindingId: string, etag: string, openId: string, idempotencyKey: string): Promise<CommandReceipt>
  confirmLarkMapping(scope: TeamOpsScope, input: ConfirmLarkMappingInput, idempotencyKey: string): Promise<CommandReceipt>
  larkMappings(scope: TeamOpsScope, status?: string | null, after?: string | null, limit?: number, signal?: AbortSignal): Promise<CursorPage<LarkMapping>>
  revokeLarkMapping(scope: TeamOpsScope, mappingId: string, version: number, reason: string, idempotencyKey: string): Promise<CommandReceipt>
  notificationTemplates(scope: TeamOpsScope, signal?: AbortSignal): Promise<NotificationTemplate[]>
  notificationPreference(scope: TeamOpsScope, memberId: string, signal?: AbortSignal): Promise<Etagged<NotificationPreference>>
  updateNotificationPreference(scope: TeamOpsScope, memberId: string, etag: string, input: NotificationPreferenceInput, idempotencyKey: string): Promise<CommandReceipt>
  notificationDeliveries(scope: TeamOpsScope, filter: NotificationDeliveryFilter, after?: string | null, limit?: number, signal?: AbortSignal): Promise<CursorPage<NotificationDelivery>>
  notificationDelivery(scope: TeamOpsScope, deliveryId: string, signal?: AbortSignal): Promise<Etagged<NotificationDelivery>>
  redeliverNotification(scope: TeamOpsScope, deliveryId: string, etag: string, idempotencyKey: string): Promise<CommandReceipt>

  operationsHealth(scope: TeamOpsScope, signal?: AbortSignal): Promise<OperationsHealthSummary>
  administratorDiagnostics(organizationId: string, signal?: AbortSignal): Promise<AdministratorDiagnostics>
  recover(organizationId: string, target: RecoveryCandidate, confirmation: string, idempotencyKey: string): Promise<RecoveryReceipt>
  projectionCommand(organizationId: string, command: ProjectionCommand, idempotencyKey: string): Promise<ProjectionCommandReceipt>
}

/** Secret-bearing values are intentionally accepted only as one-way Gateway arguments. */
export interface CreateLarkConnectionInput {
  tenantKey: string
  appId: string
  appSecret: string
  expiresAt: string | null
}

export interface RotateLarkConnectionInput {
  appId: string
  appSecret: string
}

export interface ConfirmLarkMappingInput {
  memberId: string
  providerBindingId: string
  proofId: string
}

export interface NotificationPreferenceInput {
  enabled: boolean
  enabledItemTypes: import('./types').InboxItemType[]
  mutedUntil: string | null
}

export interface NotificationDeliveryFilter {
  statuses?: import('./types').NotificationDeliveryStatus[]
  itemTypes?: import('./types').InboxItemType[]
  recipientMemberId?: string | null
}

/** HTTP adapter that reconstructs every browser-visible DTO through an explicit allowlist. */
export class HttpTeamOpsGateway implements TeamOpsGateway {
  constructor(private readonly client: CrewScopeApiClient = apiClient) {}

  async teamActivity(scope: TeamOpsScope, filter: ActivityFilter, after?: string | null, limit = 50, signal?: AbortSignal): Promise<ActivityPage> {
    return this.activityPage(`${teamRoot(scope)}/activity`, filter, after, limit, signal)
  }

  async teamActivitySnapshot(scope: TeamOpsScope, filter: ActivityFilter, limit = 50, signal?: AbortSignal): Promise<ActivitySnapshot> {
    return this.activitySnapshot(`${teamRoot(scope)}/activity/snapshot`, filter, limit, signal)
  }

  async teamActivityDetail(scope: TeamOpsScope, eventId: string, signal?: AbortSignal): Promise<ActivityItem> {
    return mapPublicActivity(await this.client.get(`${teamRoot(scope)}/activity/${segment(eventId)}`, { signal }))
  }

  async workItemActivity(scope: TeamOpsScope, route: WorkItemActivityRoute, filter: ActivityFilter, after?: string | null, limit = 50, signal?: AbortSignal): Promise<ActivityPage> {
    return this.activityPage(workItemActivityRoot(scope, route), filter, after, limit, signal)
  }

  async workItemActivitySnapshot(scope: TeamOpsScope, route: WorkItemActivityRoute, filter: ActivityFilter, limit = 50, signal?: AbortSignal): Promise<ActivitySnapshot> {
    return this.activitySnapshot(`${workItemActivityRoot(scope, route)}/snapshot`, filter, limit, signal)
  }

  async workItemActivityDetail(scope: TeamOpsScope, route: WorkItemActivityRoute, eventId: string, signal?: AbortSignal): Promise<ActivityItem> {
    return mapPublicActivity(await this.client.get(`${workItemActivityRoot(scope, route)}/${segment(eventId)}`, { signal }))
  }

  openTeamActivity(scope: TeamOpsScope, filter: ActivityFilter, after?: string | null, signal?: AbortSignal): Promise<Response> {
    const search = activitySearch(filter)
    if (after) search.set('after', after)
    return this.client.open(`${teamRoot(scope)}/activity/events?${search}`, { method: 'GET', signal }, 'text/event-stream')
  }

  async inbox(scope: TeamOpsScope, filter: InboxFilter, after?: string | null, limit = 50, signal?: AbortSignal): Promise<CursorPage<InboxItem>> {
    const search = new URLSearchParams({ limit: String(limit) })
    appendMany(search, 'itemTypes', filter.itemTypes)
    appendMany(search, 'sourceStatuses', filter.sourceStatuses)
    appendMany(search, 'dispositionStatuses', filter.dispositionStatuses)
    if (after) search.set('after', after)
    const value = asRecord(await this.client.get(`${teamRoot(scope)}/inbox?${search}`, { signal }))
    return { items: asArray(value.items).map(mapInboxItem), nextCursor: nullableString(value.nextCursor) }
  }

  async inboxCounts(scope: TeamOpsScope, signal?: AbortSignal): Promise<InboxCounts> {
    const value = asRecord(await this.client.get(`${teamRoot(scope)}/inbox/counts`, { signal }))
    const byType: Record<string, { total: number, unread: number }> = {}
    const rawCounts = asRecord(value.byType)
    for (const key of inboxItemTypes) {
      const item = asRecord(rawCounts[key])
      byType[key] = { total: nonNegativeInteger(item.total), unread: nonNegativeInteger(item.unread) }
    }
    return { total: nonNegativeInteger(value.total), unread: nonNegativeInteger(value.unread), byType }
  }

  async inboxDetail(scope: TeamOpsScope, itemId: string, signal?: AbortSignal): Promise<Etagged<InboxItem>> {
    const response = await this.client.open(`${teamRoot(scope)}/inbox/${segment(itemId)}`, { method: 'GET', signal })
    const value = mapInboxItem(await response.json())
    return { value, etag: requireMatchingEtag(response, value.dispositionVersion, value.etag) }
  }

  async inboxTarget(scope: TeamOpsScope, itemId: string, signal?: AbortSignal): Promise<InboxTarget> {
    const value = asRecord(await this.client.get(`${teamRoot(scope)}/inbox/${segment(itemId)}/target`, { signal }))
    return { kind: oneOf(value.kind, inboxTargetKinds), href: safeInternalTarget(value.href) }
  }

  async changeInboxDisposition(scope: TeamOpsScope, itemId: string, status: string, etag: string, idempotencyKey: string): Promise<CommandReceipt> {
    return mapReceipt(await this.client.request(`${teamRoot(scope)}/inbox/${segment(itemId)}/disposition`, {
      method: 'PUT', body: { status }, expectedVersion: etagVersion(etag), idempotencyKey,
    }))
  }

  async audit(scope: TeamOpsScope, filter: AuditFilter, after?: string | null, limit = 50, signal?: AbortSignal): Promise<CursorPage<AuditEvent>> {
    const search = auditSearch(filter)
    search.set('limit', String(limit))
    if (after) search.set('after', after)
    const value = asRecord(await this.client.get(`${teamRoot(scope)}/audit-events?${search}`, { signal }))
    return { items: asArray(value.items).map(mapAudit), nextCursor: nullableString(value.nextCursor) }
  }

  async exportAudit(scope: TeamOpsScope, filter: AuditFilter, maximumRows: number, signal?: AbortSignal): Promise<AuditExport> {
    const response = await this.client.open(`${teamRoot(scope)}/audit-events/export`, {
      method: 'POST', signal, body: { ...auditBody(filter), maximumRows },
    }, 'application/vnd.crewscope.audit-export+json')
    const value = asRecord(await response.json())
    const result = {
      generatedAt: string(value.generatedAt),
      rowCount: nonNegativeInteger(value.rowCount),
      maximumRows: nonNegativeInteger(value.maximumRows),
      events: asArray(value.events).map(mapAudit),
    }
    if (result.maximumRows > 10_000 || result.rowCount !== result.events.length || result.rowCount > result.maximumRows) {
      throw new TypeError('Audit export bounds are invalid')
    }
    return result
  }

  async correlation(scope: TeamOpsScope, correlationId: string, after?: string | null, limit = 50, signal?: AbortSignal): Promise<CorrelationGraph> {
    const search = new URLSearchParams({ limit: String(limit) })
    if (after) search.set('after', after)
    const value = asRecord(await this.client.get(`${teamRoot(scope)}/correlations/${segment(correlationId)}?${search}`, { signal }))
    const events = asArray(value.events).map(input => {
      const item = asRecord(input)
      return {
        eventId: string(item.eventId), source: oneOf(item.source, correlationEventSources), eventType: string(item.eventType),
        actorType: string(item.actorType), actorId: nullableString(item.actorId), outcome: nullableString(item.outcome),
        occurredAt: string(item.occurredAt), references: asArray(item.references).map(mapCorrelationReference),
      }
    })
    const objects = asArray(value.objects).map(input => {
      const item = asRecord(input)
      return { ...mapCorrelationReference(item), relatedEventIds: asArray(item.relatedEventIds).map(string) }
    })
    const hasMore = boolean(value.hasMore)
    const nextCursor = nullableString(value.nextCursor)
    if (hasMore !== Boolean(nextCursor)) throw new TypeError('Correlation continuation is inconsistent')
    return { correlationId: string(value.correlationId), events, objects, hasMore, nextCursor }
  }

  async larkConnections(scope: TeamOpsScope, signal?: AbortSignal): Promise<LarkConnection[]> {
    return asArray(await this.client.get(`${teamRoot(scope)}/lark/connections`, { signal })).map(mapLarkConnection)
  }

  async larkConnection(scope: TeamOpsScope, connectionId: string, signal?: AbortSignal): Promise<Etagged<LarkConnection>> {
    const response = await this.client.open(`${teamRoot(scope)}/lark/connections/${segment(connectionId)}`, { method: 'GET', signal })
    const value = mapLarkConnection(await response.json())
    return { value, etag: requireMatchingEtag(response, value.version) }
  }

  createLarkConnection(scope: TeamOpsScope, expectedVersion: number, input: CreateLarkConnectionInput, idempotencyKey: string): Promise<CommandReceipt> {
    return this.command(`${teamRoot(scope)}/lark/connections`, input, expectedVersion, idempotencyKey)
  }

  rotateLarkConnection(scope: TeamOpsScope, connectionId: string, etag: string, input: RotateLarkConnectionInput, idempotencyKey: string): Promise<CommandReceipt> {
    return this.command(`${teamRoot(scope)}/lark/connections/${segment(connectionId)}/rotate`, input, etagVersion(etag), idempotencyKey)
  }

  revokeLarkConnection(scope: TeamOpsScope, connectionId: string, etag: string, reason: string, idempotencyKey: string): Promise<CommandReceipt> {
    return this.command(`${teamRoot(scope)}/lark/connections/${segment(connectionId)}/revoke`, { reason }, etagVersion(etag), idempotencyKey)
  }

  async larkPreflight(scope: TeamOpsScope, bindingId: string, etag: string, signal?: AbortSignal): Promise<LarkPreflight> {
    const value = asRecord(await this.client.request(`${teamRoot(scope)}/lark/bindings/${segment(bindingId)}/preflight`, {
      method: 'POST', expectedVersion: etagVersion(etag), signal,
    }))
    return { providerBindingId: string(value.providerBindingId), version: number(value.version), checkedAt: string(value.checkedAt) }
  }

  async larkHealth(scope: TeamOpsScope, bindingId: string, signal?: AbortSignal): Promise<LarkHealth> {
    const value = asRecord(await this.client.get(`${teamRoot(scope)}/lark/bindings/${segment(bindingId)}/health`, { signal }))
    return {
      status: oneOf(value.status, larkHealthStatuses), retryable: boolean(value.retryable),
      retryAfterSeconds: nullableNonNegativeInteger(value.retryAfterSeconds), evidenceCode: evidenceCode(value.evidenceCode),
      checkedAt: string(value.checkedAt),
    }
  }

  verifyLarkMember(scope: TeamOpsScope, bindingId: string, etag: string, openId: string, idempotencyKey: string): Promise<CommandReceipt> {
    return this.command(`${teamRoot(scope)}/lark/member-verifications`, { providerBindingId: bindingId, openId }, etagVersion(etag), idempotencyKey)
  }

  confirmLarkMapping(scope: TeamOpsScope, input: ConfirmLarkMappingInput, idempotencyKey: string): Promise<CommandReceipt> {
    return this.command(`${teamRoot(scope)}/lark/member-mappings`, input, 0, idempotencyKey)
  }

  async larkMappings(scope: TeamOpsScope, status?: string | null, after?: string | null, limit = 50, signal?: AbortSignal): Promise<CursorPage<LarkMapping>> {
    const search = new URLSearchParams({ limit: String(limit) })
    if (status) search.set('status', status)
    if (after) search.set('after', after)
    const value = asRecord(await this.client.get(`${teamRoot(scope)}/lark/member-mappings?${search}`, { signal }))
    return { items: asArray(value.items).map(mapMapping), nextCursor: nullableString(value.nextCursor) }
  }

  revokeLarkMapping(scope: TeamOpsScope, mappingId: string, version: number, reason: string, idempotencyKey: string): Promise<CommandReceipt> {
    return this.command(`${teamRoot(scope)}/lark/member-mappings/${segment(mappingId)}/revoke`, { reason }, version, idempotencyKey)
  }

  async notificationTemplates(scope: TeamOpsScope, signal?: AbortSignal): Promise<NotificationTemplate[]> {
    return asArray(await this.client.get(`${teamRoot(scope)}/lark/notification-templates`, { signal })).map(mapTemplate)
  }

  async notificationPreference(scope: TeamOpsScope, memberId: string, signal?: AbortSignal): Promise<Etagged<NotificationPreference>> {
    const response = await this.client.open(`${teamRoot(scope)}/lark/notification-preferences/${segment(memberId)}`, { method: 'GET', signal })
    const value = mapPreference(await response.json())
    return { value, etag: requireMatchingEtag(response, value.version) }
  }

  updateNotificationPreference(scope: TeamOpsScope, memberId: string, etag: string, input: NotificationPreferenceInput, idempotencyKey: string): Promise<CommandReceipt> {
    return this.command(`${teamRoot(scope)}/lark/notification-preferences/${segment(memberId)}`, input, etagVersion(etag), idempotencyKey, 'PUT')
  }

  async notificationDeliveries(scope: TeamOpsScope, filter: NotificationDeliveryFilter, after?: string | null, limit = 50, signal?: AbortSignal): Promise<CursorPage<NotificationDelivery>> {
    const search = new URLSearchParams({ limit: String(limit) })
    appendMany(search, 'status', filter.statuses)
    appendMany(search, 'itemType', filter.itemTypes)
    if (filter.recipientMemberId) search.set('recipientMemberId', filter.recipientMemberId)
    if (after) search.set('after', after)
    const value = asRecord(await this.client.get(`${teamRoot(scope)}/lark/notification-deliveries?${search}`, { signal }))
    return { items: asArray(value.items).map(mapDelivery), nextCursor: nullableString(value.nextCursor) }
  }

  async notificationDelivery(scope: TeamOpsScope, deliveryId: string, signal?: AbortSignal): Promise<Etagged<NotificationDelivery>> {
    const response = await this.client.open(`${teamRoot(scope)}/lark/notification-deliveries/${segment(deliveryId)}`, { method: 'GET', signal })
    const value = mapDelivery(await response.json())
    return { value, etag: requireMatchingEtag(response, value.version) }
  }

  redeliverNotification(scope: TeamOpsScope, deliveryId: string, etag: string, idempotencyKey: string): Promise<CommandReceipt> {
    return this.command(`${teamRoot(scope)}/lark/notification-deliveries/${segment(deliveryId)}/redeliver`, undefined, etagVersion(etag), idempotencyKey)
  }

  async operationsHealth(scope: TeamOpsScope, signal?: AbortSignal): Promise<OperationsHealthSummary> {
    return mapHealth(await this.client.get(`${teamRoot(scope)}/operations/health`, { signal }))
  }

  async administratorDiagnostics(organizationId: string, signal?: AbortSignal): Promise<AdministratorDiagnostics> {
    const value = asRecord(await this.client.get(`${operationsRoot(organizationId)}/diagnostics`, { signal }))
    return {
      summary: mapHealth(value.summary),
      projections: asArray(value.projections).map(mapProjection),
      recoveryCandidates: asArray(value.recoveryCandidates).map(mapRecoveryCandidate),
    }
  }

  async recover(organizationId: string, target: RecoveryCandidate, confirmation: string, idempotencyKey: string): Promise<RecoveryReceipt> {
    const value = asRecord(await this.client.post(`${operationsRoot(organizationId)}/recoveries`, {
      target: recoveryRequestTarget(target), confirmation,
    }, { idempotencyKey }))
    return {
      commandId: uuid(value.commandId), action: oneOf(value.action, operationsRecoveryActions),
      targetReferenceHash: referenceHash(value.targetReferenceHash), status: oneOf(value.status, ['SCHEDULED'] as const), acceptedAt: timestamp(value.acceptedAt),
    }
  }

  async projectionCommand(organizationId: string, command: ProjectionCommand, idempotencyKey: string): Promise<ProjectionCommandReceipt> {
    const base = `${operationsRoot(organizationId)}/projections/${segment(command.projectionName)}`
    let path: string
    if (command.operation === 'start') path = `${base}/rebuilds`
    else if (command.operation === 'retry') path = `${base}/rebuilds/${segment(command.rebuildJobId)}/retry`
    else if (command.operation === 'validate' || command.operation === 'switch') path = `${base}/generations/${command.generation}/${command.operation}`
    else path = `${base}/generations/${command.generation}/rebuilds/${segment(command.rebuildJobId)}/${command.operation}`
    return mapProjectionReceipt(await this.client.post(path, projectionRequestBody(command), { idempotencyKey }))
  }

  private async activityPage(path: string, filter: ActivityFilter, after: string | null | undefined, limit: number, signal?: AbortSignal): Promise<ActivityPage> {
    const search = activitySearch(filter)
    search.set('limit', String(limit))
    if (after) search.set('after', after)
    const value = asRecord(await this.client.get(`${path}?${search}`, { signal }))
    return {
      items: asArray(value.items).map(mapPublicActivity), hasMore: boolean(value.hasMore),
      nextCursor: nullableString(value.nextCursor),
    }
  }

  private async activitySnapshot(path: string, filter: ActivityFilter, limit: number, signal?: AbortSignal): Promise<ActivitySnapshot> {
    const search = activitySearch(filter)
    search.set('limit', String(limit))
    const value = asRecord(await this.client.get(`${path}?${search}`, { signal }))
    return {
      items: asArray(value.items).map(mapPublicActivity), hasMore: boolean(value.hasMore),
      nextCursor: nullableString(value.nextCursor), snapshotCursor: nullableString(value.snapshotCursor),
    }
  }

  private async command(path: string, body: unknown, expectedVersion: number, idempotencyKey: string, method = 'POST'): Promise<CommandReceipt> {
    return mapReceipt(await this.client.request(path, { method, body, expectedVersion, idempotencyKey }))
  }
}

function teamRoot(scope: TeamOpsScope): string {
  return `/organizations/${segment(scope.organizationId)}/teams/${segment(scope.teamId)}`
}

function operationsRoot(organizationId: string): string {
  return `/organizations/${segment(organizationId)}/operations`
}

function workItemActivityRoot(scope: TeamOpsScope, route: WorkItemActivityRoute): string {
  return `${teamRoot(scope)}/work-projects/${segment(route.projectId)}/work-items/${segment(route.workItemId)}/activity`
}

function segment(value: string): string { return encodeURIComponent(value) }

function activitySearch(filter: ActivityFilter): URLSearchParams {
  const search = new URLSearchParams()
  if (filter.workItemId) search.set('workItemId', filter.workItemId)
  appendMany(search, 'categories', filter.categories)
  appendMany(search, 'eventTypes', filter.eventTypes)
  appendMany(search, 'actorPrincipalIds', filter.actorPrincipalIds)
  return search
}

function auditSearch(filter: AuditFilter): URLSearchParams {
  const search = new URLSearchParams()
  const body = auditBody(filter)
  for (const [key, value] of Object.entries(body)) {
    if (Array.isArray(value)) value.forEach(item => search.append(key, item))
    else if (value) search.set(key, String(value))
  }
  return search
}

function auditBody(filter: AuditFilter): Record<string, string | string[]> {
  const value: Record<string, string | string[]> = {}
  if (filter.from) value.occurredFrom = filter.from
  if (filter.to) value.occurredBefore = filter.to
  if (filter.categories?.length) value.categories = filter.categories
  if (filter.outcomes?.length) value.outcomes = filter.outcomes
  if (filter.initiatorIds?.length) value.initiatorIds = filter.initiatorIds
  if (filter.actorIds?.length) value.actorIds = filter.actorIds
  if (filter.agentPrincipalIds?.length) value.agentPrincipalIds = filter.agentPrincipalIds
  if (filter.subjectTypes?.[0]) value.subjectType = filter.subjectTypes[0]
  if (filter.subjectIds?.[0]) value.subjectId = filter.subjectIds[0]
  if (filter.providerBindingIds?.[0]) value.providerBindingId = filter.providerBindingIds[0]
  if (filter.correlationIds?.[0]) value.correlationId = filter.correlationIds[0]
  return value
}

function appendMany(search: URLSearchParams, key: string, values?: string[]): void {
  values?.forEach(value => search.append(key, value))
}

/** Decodes one SSE or JSON Activity fact through the same public DTO allowlist. */
export function mapPublicActivity(input: unknown): ActivityItem {
  const value = asRecord(input)
  const subject = asRecord(value.subject)
  const actor = asRecord(value.actor)
  const payload = asRecord(value.payload)
  const values: Record<string, string> = {}
  for (const [key, item] of Object.entries(asRecord(payload.values))) values[key] = string(item)
  return {
    eventId: string(value.eventId), domainEventId: string(value.domainEventId),
    teamSequence: number(value.teamSequence), eventType: string(value.eventType),
    category: string(value.category), visibility: string(value.visibility),
    subject: { type: string(subject.type), id: string(subject.id) },
    actor: { type: string(actor.type), principalId: nullableString(actor.principalId) },
    references: asArray(value.references).map(input => {
      const item = asRecord(input)
      return { type: string(item.type), id: string(item.id) }
    }),
    occurredAt: string(value.occurredAt),
    payload: { schemaName: string(payload.schemaName), schemaVersion: number(payload.schemaVersion), values },
  }
}

function mapInboxItem(input: unknown): InboxItem {
  const value = asRecord(input)
  const source = asRecord(value.source)
  return {
    inboxItemId: string(value.inboxItemId), itemType: oneOf(value.itemType, inboxItemTypes), priority: oneOf(value.priority, inboxPriorities),
    deadline: nullableString(value.deadline), openedAt: string(value.openedAt), sourceStatus: oneOf(value.sourceStatus, inboxSourceStatuses),
    closeReason: nullableString(value.closeReason), closedAt: nullableString(value.closedAt),
    dispositionStatus: oneOf(value.dispositionStatus, inboxDispositionStatuses), dispositionVersion: nonNegativeInteger(value.dispositionVersion), etag: string(value.etag),
    source: { type: oneOf(source.type, inboxSourceTypes), id: string(source.id), revision: nonNegativeInteger(source.revision) },
  }
}

function mapAudit(input: unknown): AuditEvent {
  const value = asRecord(input)
  const identity = asRecord(value.identity)
  const subject = asRecord(value.subject)
  const provider = value.provider == null ? null : asRecord(value.provider)
  const correlation = asRecord(value.correlation)
  const summary = mapAuditSummary(value.summary)
  return {
    eventId: string(value.eventId), eventType: string(value.eventType), sourceSchemaVersion: positiveInteger(value.sourceSchemaVersion),
    category: oneOf(value.category, auditEventCategories), outcome: oneOf(value.outcome, auditOutcomes), retentionLevel: oneOf(value.retentionLevel, auditRetentionLevels), occurredAt: string(value.occurredAt),
    identity: { initiatorId: nullableString(identity.initiatorId), actorType: oneOf(identity.actorType, auditActorTypes), actorId: nullableString(identity.actorId), agentPrincipalId: nullableString(identity.agentPrincipalId) },
    subject: { type: string(subject.type), id: string(subject.id) },
    provider: provider ? { providerBindingId: string(provider.providerBindingId), connectionId: string(provider.connectionId), externalOperationHash: nullableAuditHash(provider.externalOperationHash) } : null,
    correlation: { correlationId: string(correlation.correlationId), causationId: nullableString(correlation.causationId), domainEventId: nullableString(correlation.domainEventId) },
    summary,
  }
}

function mapAuditSummary(input: unknown): Record<string, string> {
  const entries = Object.entries(asRecord(input))
  if (entries.length > 32) throw new TypeError('Audit summary exceeds its public bounds')
  const summary: Record<string, string> = {}
  const sensitive = /(secret|token|credential|authorization|cookie|payload|prompt|endpoint|email|phone)/i
  for (const [key, raw] of entries) {
    const value = string(raw)
    if (!key || key.length > 64 || value.length > 512 || sensitive.test(key) || /[\u0000-\u001f\u007f]/.test(key + value)) {
      throw new TypeError('Audit summary contains an unsafe public field')
    }
    summary[key] = value
  }
  return summary
}

function nullableAuditHash(value: unknown): string | null {
  const parsed = nullableString(value)
  if (parsed !== null && !/^[a-f0-9]{64}$/.test(parsed)) throw new TypeError('Audit operation hash is invalid')
  return parsed
}

function mapCorrelationReference(input: unknown) {
  const value = asRecord(input)
  return { type: oneOf(value.type, correlationObjectTypes), id: string(value.id), href: safeCorrelationTarget(value.href) }
}

function mapLarkConnection(input: unknown): LarkConnection {
  const value = asRecord(input)
  const providerBindingId = nullableValueString(value.providerBindingId)
  const providerBindingVersion = nullableNonNegativeInteger(value.providerBindingVersion)
  if ((providerBindingId === null) !== (providerBindingVersion === null)) {
    throw new TypeError('Lark provider binding identity and version must be present together')
  }
  return {
    connectionId: valueString(value.connectionId), teamId: valueString(value.teamId), providerBindingId,
    providerBindingVersion,
    maskedAppId: string(value.maskedAppId), status: oneOf(value.status, larkConnectionStatuses), credentialStatus: oneOf(value.credentialStatus, larkCredentialStatuses),
    expiresAt: nullableValueString(value.expiresAt), createdAt: valueString(value.createdAt), updatedAt: valueString(value.updatedAt), version: nonNegativeInteger(value.version),
  }
}

function mapMapping(input: unknown): LarkMapping {
  const value = asRecord(input)
  return {
    mappingId: string(value.mappingId), memberId: string(value.memberId), providerBindingId: string(value.providerBindingId),
    status: oneOf(value.status, larkMappingStatuses), terminalReason: nullableOneOf(value.terminalReason, larkMappingTerminalReasons), verifiedAt: string(value.verifiedAt),
    updatedAt: string(value.updatedAt), version: nonNegativeInteger(value.version),
  }
}

function mapPreference(input: unknown): NotificationPreference {
  const value = asRecord(input)
  return {
    memberId: string(value.memberId), enabled: boolean(value.enabled), enabledItemTypes: asArray(value.enabledItemTypes).map(item => oneOf(item, inboxItemTypes)),
    mutedUntil: nullableString(value.mutedUntil), version: nonNegativeInteger(value.version),
  }
}

function mapTemplate(input: unknown): NotificationTemplate {
  const value = asRecord(input)
  const ref = asRecord(value.ref)
  return {
    ref: { templateId: valueString(ref.templateId), version: valueNumber(ref.version) },
    serverTemplateKey: string(value.serverTemplateKey), status: oneOf(value.status, notificationTemplateStatuses),
    variables: asArray(value.variables).map(input => {
      const item = asRecord(input)
      return { name: string(item.name), type: oneOf(item.type, notificationVariableTypes), maximumLength: positiveInteger(item.maximumLength) }
    }),
  }
}

function mapDelivery(input: unknown): NotificationDelivery {
  const value = asRecord(input)
  const template = asRecord(value.template)
  return {
    organizationId: valueString(value.organizationId), teamId: valueString(value.teamId), deliveryId: valueString(value.deliveryId),
    recipientMemberId: valueString(value.recipientMemberId), itemType: oneOf(value.itemType, inboxItemTypes),
    template: { templateId: valueString(template.templateId), version: valueNumber(template.version) },
    providerBindingId: valueString(value.providerBindingId), status: oneOf(value.status, notificationDeliveryStatuses), attemptCount: nonNegativeInteger(value.attemptCount),
    failureCode: nullableOneOf(value.failureCode, notificationFailureCodes), evidenceCode: nullableString(value.evidenceCode), redeliveryOf: nullableValueString(value.redeliveryOf),
    createdAt: valueString(value.createdAt), updatedAt: valueString(value.updatedAt), version: nonNegativeInteger(value.version),
  }
}

function mapHealth(input: unknown): OperationsHealthSummary {
  const value = asRecord(input)
  const components = asArray(value.components).map(input => {
      const item = asRecord(input)
      return {
        component: oneOf(item.component, operationsHealthComponents), health: oneOf(item.health, operationsHealthLevels), backlog: nonNegativeInteger(item.backlog), inFlight: nonNegativeInteger(item.inFlight),
        failures: nonNegativeInteger(item.failures), affected: nonNegativeInteger(item.affected), oldestOutstandingAgeSeconds: nonNegativeInteger(item.oldestOutstandingAgeSeconds), stale: boolean(item.stale),
      }
    })
  if (components.length !== operationsHealthComponents.length
    || new Set(components.map(item => item.component)).size !== operationsHealthComponents.length) {
    throw new TypeError('Operations health component set is incomplete')
  }
  return {
    observedAt: timestamp(value.observedAt), health: oneOf(value.health, operationsHealthLevels), components,
  }
}

function mapProjection(input: unknown) {
  const value = asRecord(input)
  const shadowGeneration = nullableNonNegativeInteger(value.shadowGeneration)
  const shadowStatus = nullableOneOf(value.shadowStatus, projectionGenerationStatuses)
  const shadowGenerationVersion = nullableNonNegativeInteger(value.shadowGenerationVersion)
  const rebuildJobId = nullableUuid(value.rebuildJobId)
  const rebuildJobVersion = nullableNonNegativeInteger(value.rebuildJobVersion)
  const shadowCoordinates = [shadowGeneration, shadowStatus, shadowGenerationVersion, rebuildJobId, rebuildJobVersion]
  if (!shadowCoordinates.every(item => item === null) && shadowCoordinates.some(item => item === null)) {
    throw new TypeError('Projection shadow coordinates must be present together')
  }
  if (shadowStatus !== null && !['BUILDING', 'VALIDATING'].includes(shadowStatus)) {
    throw new TypeError('Projection shadow status is invalid')
  }
  const activeGeneration = nonNegativeInteger(value.activeGeneration)
  if (shadowGeneration !== null && shadowGeneration <= activeGeneration) throw new TypeError('Projection shadow generation is invalid')
  const confirmation = (input: unknown) => input == null ? null : boundedText(input, 512)
  return {
    projectionName: projectionName(value.projectionName), definitionVersion: positiveInteger(value.definitionVersion), activeGeneration,
    pointerVersion: nonNegativeInteger(value.pointerVersion), activeGenerationVersion: nonNegativeInteger(value.activeGenerationVersion), shadowGeneration,
    shadowStatus, shadowGenerationVersion, rebuildJobId,
    rebuildJobVersion, lagSeconds: nonNegativeInteger(value.lagSeconds), gapCount: nonNegativeInteger(value.gapCount), deadLetterCount: nonNegativeInteger(value.deadLetterCount),
    latestFailureCode: nullableFailureCode(value.latestFailureCode), startConfirmation: boundedText(value.startConfirmation, 512), validateConfirmation: confirmation(value.validateConfirmation),
    switchConfirmation: confirmation(value.switchConfirmation), cancelConfirmation: confirmation(value.cancelConfirmation), failConfirmation: confirmation(value.failConfirmation),
  }
}

function mapRecoveryCandidate(input: unknown): RecoveryCandidate {
  const value = asRecord(input)
  const type = string(value.type)
  if (type === 'OUTBOX_DEAD_LETTER') return {
    type, action: oneOf(value.action, ['REPLAY_OUTBOX_DEAD_LETTER'] as const), outboxEventId: uuid(value.outboxEventId), domainEventId: uuid(value.domainEventId),
    expectedVersion: nonNegativeInteger(value.expectedVersion), referenceHash: referenceHash(value.referenceHash), confirmation: boundedText(value.confirmation, 512),
  }
  if (type === 'PROJECTION_DEAD_LETTER') return {
    type, action: oneOf(value.action, ['REPLAY_PROJECTION_DEAD_LETTER'] as const), projectionName: projectionName(value.projectionName), generation: nonNegativeInteger(value.generation),
    deadLetterId: uuid(value.deadLetterId), domainEventId: uuid(value.domainEventId), expectedGenerationVersion: nonNegativeInteger(value.expectedGenerationVersion),
    referenceHash: referenceHash(value.referenceHash), confirmation: boundedText(value.confirmation, 512),
  }
  if (type === 'NOTIFICATION_DELIVERY') return {
    type, action: oneOf(value.action, ['RETRY_NOTIFICATION_DELIVERY'] as const), deliveryId: uuid(value.deliveryId), expectedVersion: nonNegativeInteger(value.expectedVersion),
    referenceHash: referenceHash(value.referenceHash), confirmation: boundedText(value.confirmation, 512),
  }
  throw new TypeError('Operations recovery candidate type is invalid')
}

function mapReceipt(input: unknown): CommandReceipt {
  const value = asRecord(input)
  return { commandId: string(value.commandId), domainEventId: string(value.domainEventId), committedVersion: number(value.committedVersion), correlationId: string(value.correlationId) }
}

function mapProjectionReceipt(input: unknown): ProjectionCommandReceipt {
  const value = asRecord(input)
  return {
    commandId: uuid(value.commandId), projectionName: projectionName(value.projectionName), generation: nonNegativeInteger(value.generation), rebuildJobId: uuid(value.rebuildJobId),
    generationStatus: oneOf(value.generationStatus, projectionGenerationStatuses), rebuildStatus: oneOf(value.rebuildStatus, projectionRebuildStatuses), generationVersion: nonNegativeInteger(value.generationVersion),
    rebuildJobVersion: nonNegativeInteger(value.rebuildJobVersion), pointerVersion: nullableNonNegativeInteger(value.pointerVersion),
  }
}

function requireMatchingEtag(response: Response, version: number, bodyEtag?: string): string {
  const etag = response.headers.get('ETag')
  const expected = `"${version}"`
  if (!etag || etag.startsWith('W/') || etag !== expected || (bodyEtag !== undefined && bodyEtag !== etag)) {
    throw new TypeError('Versioned response strong ETag does not match its public version')
  }
  return etag
}

function etagVersion(etag: string): number {
  if (!/^"\d+"$/.test(etag)) throw new TypeError('Strong ETag is invalid')
  const value = Number(etag.slice(1, -1))
  if (!Number.isSafeInteger(value)) throw new TypeError('Strong ETag is invalid')
  return value
}

function asRecord(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new TypeError('Team operations response is invalid')
  return value as Record<string, unknown>
}

function asArray(value: unknown): unknown[] {
  if (!Array.isArray(value)) throw new TypeError('Team operations response array is invalid')
  return value
}

function string(value: unknown): string {
  if (typeof value !== 'string') throw new TypeError('Team operations response string is invalid')
  return value
}

function nullableString(value: unknown): string | null {
  return value == null ? null : string(value)
}

/** Application views may serialize single-value domain records as `{ value }`. */
function valueString(value: unknown): string {
  return typeof value === 'string' ? value : string(asRecord(value).value)
}

function nullableValueString(value: unknown): string | null {
  return value == null ? null : valueString(value)
}

function number(value: unknown): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) throw new TypeError('Team operations response number is invalid')
  return value
}

function nonNegativeInteger(value: unknown): number {
  const parsed = number(value)
  if (!Number.isSafeInteger(parsed) || parsed < 0) throw new TypeError('Team operations response integer is invalid')
  return parsed
}

function positiveInteger(value: unknown): number {
  const parsed = nonNegativeInteger(value)
  if (parsed === 0) throw new TypeError('Team operations response positive integer is invalid')
  return parsed
}

function oneOf<T extends string>(value: unknown, choices: readonly T[]): T {
  const parsed = string(value)
  if (!choices.includes(parsed as T)) throw new TypeError('Team operations response enum is invalid')
  return parsed as T
}

function nullableOneOf<T extends string>(value: unknown, choices: readonly T[]): T | null {
  return value == null ? null : oneOf(valueString(value), choices)
}

/** Accepts only server-generated CrewScope routes and rejects external or protocol-relative targets. */
function safeInternalTarget(value: unknown): string {
  const href = string(value)
  if (!href.startsWith('/') || href.startsWith('//')) throw new TypeError('Inbox target is not an internal route')
  const parsed = new URL(href, 'https://crewscope.invalid')
  if (parsed.origin !== 'https://crewscope.invalid'
    || !['/work', '/settings/integrations'].includes(parsed.pathname)
    || parsed.hash) {
    throw new TypeError('Inbox target is not an approved internal route')
  }
  return `${parsed.pathname}${parsed.search}`
}

/** Correlation links are server-generated and may only point back to the scoped Activity page. */
function safeCorrelationTarget(value: unknown): string {
  const href = string(value)
  if (!href.startsWith('/') || href.startsWith('//')) throw new TypeError('Correlation target is not an internal route')
  const parsed = new URL(href, 'https://crewscope.invalid')
  if (parsed.origin !== 'https://crewscope.invalid' || parsed.pathname !== '/activity' || parsed.hash) {
    throw new TypeError('Correlation target is not an approved internal route')
  }
  return `${parsed.pathname}${parsed.search}`
}

function nullableNumber(value: unknown): number | null {
  return value == null ? null : number(value)
}

function nullableNonNegativeInteger(value: unknown): number | null {
  return value == null ? null : nonNegativeInteger(value)
}

function boundedText(value: unknown, maximum: number): string {
  const parsed = string(value)
  if (!parsed.trim() || parsed.length > maximum || /[\u0000-\u001f\u007f]/.test(parsed)) throw new TypeError('Team operations response text is invalid')
  return parsed
}

function uuid(value: unknown): string {
  const parsed = string(value)
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(parsed)) throw new TypeError('Team operations response UUID is invalid')
  return parsed
}

function nullableUuid(value: unknown): string | null { return value == null ? null : uuid(value) }

function timestamp(value: unknown): string {
  const parsed = string(value)
  if (!Number.isFinite(Date.parse(parsed))) throw new TypeError('Team operations response timestamp is invalid')
  return parsed
}

function projectionName(value: unknown): string {
  const parsed = string(value)
  if (!/^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$/.test(parsed) || parsed.length > 180) throw new TypeError('Projection name is invalid')
  return parsed
}

function nullableFailureCode(value: unknown): string | null {
  if (value == null) return null
  const parsed = string(value)
  if (!/^[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*$/.test(parsed) || parsed.length > 80) throw new TypeError('Projection failure code is invalid')
  return parsed
}

function referenceHash(value: unknown): string {
  const parsed = string(value)
  if (!/^[a-f0-9]{64}$/.test(parsed)) throw new TypeError('Operations reference hash is invalid')
  return parsed
}

/** Removes diagnostics-only helpers before crossing the server's strict Recovery target boundary. */
function recoveryRequestTarget(target: RecoveryCandidate): Record<string, unknown> {
  if (target.type === 'OUTBOX_DEAD_LETTER') return {
    type: target.type, outboxEventId: target.outboxEventId, domainEventId: target.domainEventId, expectedVersion: target.expectedVersion,
  }
  if (target.type === 'PROJECTION_DEAD_LETTER') return {
    type: target.type, projectionName: target.projectionName, generation: target.generation,
    deadLetterId: target.deadLetterId, domainEventId: target.domainEventId,
    expectedGenerationVersion: target.expectedGenerationVersion,
  }
  return { type: target.type, deliveryId: target.deliveryId, expectedVersion: target.expectedVersion }
}

/** Rebuilds every dangerous command body so callers cannot smuggle unknown operation fields. */
function projectionRequestBody(command: ProjectionCommand): Record<string, unknown> {
  if (command.operation === 'start') return { expectedDefinitionVersion: command.body.expectedDefinitionVersion, expectedPointerVersion: command.body.expectedPointerVersion, confirmation: command.body.confirmation }
  if (command.operation === 'retry') return { expectedRetryOfJobVersion: command.body.expectedRetryOfJobVersion, expectedDefinitionVersion: command.body.expectedDefinitionVersion, expectedPointerVersion: command.body.expectedPointerVersion, confirmation: command.body.confirmation }
  if (command.operation === 'validate') return { expectedDefinitionVersion: command.body.expectedDefinitionVersion, rebuildJobId: command.body.rebuildJobId, expectedGenerationVersion: command.body.expectedGenerationVersion, expectedJobVersion: command.body.expectedJobVersion, confirmation: command.body.confirmation }
  if (command.operation === 'switch') return { expectedDefinitionVersion: command.body.expectedDefinitionVersion, previousActiveGeneration: command.body.previousActiveGeneration, rebuildJobId: command.body.rebuildJobId, expectedPointerVersion: command.body.expectedPointerVersion, expectedPreviousGenerationVersion: command.body.expectedPreviousGenerationVersion, expectedTargetGenerationVersion: command.body.expectedTargetGenerationVersion, expectedJobVersion: command.body.expectedJobVersion, confirmation: command.body.confirmation }
  if (command.operation === 'fail') return { expectedGenerationVersion: command.body.expectedGenerationVersion, expectedJobVersion: command.body.expectedJobVersion, failureCode: command.body.failureCode, confirmation: command.body.confirmation }
  return { expectedGenerationVersion: command.body.expectedGenerationVersion, expectedJobVersion: command.body.expectedJobVersion, confirmation: command.body.confirmation }
}

function evidenceCode(value: unknown): string {
  const parsed = string(value)
  if (!/^[A-Z][A-Z0-9_]{2,63}$/.test(parsed)) throw new TypeError('Team operations evidence code is invalid')
  return parsed
}

function valueNumber(value: unknown): number {
  return typeof value === 'number' ? value : number(asRecord(value).value)
}

function boolean(value: unknown): boolean {
  if (typeof value !== 'boolean') throw new TypeError('Team operations response boolean is invalid')
  return value
}
