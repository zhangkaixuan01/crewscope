import { inject, reactive, readonly, type App, type InjectionKey } from 'vue'
import { isAbort, teamOpsError, type TeamOpsErrorState } from './errors'
import type {
  ConfirmLarkMappingInput,
  CreateLarkConnectionInput,
  NotificationDeliveryFilter,
  NotificationPreferenceInput,
  RotateLarkConnectionInput,
  TeamOpsGateway,
} from './gateway'
import type {
  ActivityFilter,
  ActivityItem,
  AdministratorDiagnostics,
  AuditEvent,
  AuditExport,
  AuditFilter,
  CommandReceipt,
  CorrelationGraph,
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

export type TeamOpsPhase = 'idle' | 'loading' | 'ready' | 'empty' | 'error'

export interface TeamOpsResource<T> {
  phase: TeamOpsPhase
  value: T | null
  error: TeamOpsErrorState | null
}

export interface TeamOpsCursorResource<T> extends TeamOpsResource<T[]> {
  nextCursor: string | null
  resumeCursor: string | null
  loadingMore: boolean
}

export interface TeamOpsCorrelationResource extends TeamOpsResource<CorrelationGraph> {
  nextCursor: string | null
  loadingMore: boolean
}

export interface TeamOpsCommandState {
  phase: 'idle' | 'pending' | 'success' | 'error' | 'conflict'
  operation: string | null
  targetId: string | null
  receipt: CommandReceipt | ProjectionCommandReceipt | RecoveryReceipt | null
  error: TeamOpsErrorState | null
}

export interface TeamOpsStoreState {
  teamActivity: TeamOpsCursorResource<ActivityItem>
  activityDetails: Record<string, TeamOpsResource<ActivityItem>>
  workItemActivity: Record<string, TeamOpsCursorResource<ActivityItem>>
  inbox: TeamOpsCursorResource<InboxItem>
  inboxCounts: TeamOpsResource<InboxCounts>
  inboxDetails: Record<string, TeamOpsResource<Etagged<InboxItem>>>
  inboxTargets: Record<string, TeamOpsResource<InboxTarget>>
  audit: TeamOpsCursorResource<AuditEvent>
  auditExport: TeamOpsResource<AuditExport>
  correlations: Record<string, TeamOpsCorrelationResource>
  larkConnections: TeamOpsResource<LarkConnection[]>
  larkConnectionDetails: Record<string, TeamOpsResource<Etagged<LarkConnection>>>
  larkPreflights: Record<string, TeamOpsResource<LarkPreflight>>
  larkHealth: Record<string, TeamOpsResource<LarkHealth>>
  larkMappings: TeamOpsCursorResource<LarkMapping>
  notificationTemplates: TeamOpsResource<NotificationTemplate[]>
  notificationPreferences: Record<string, TeamOpsResource<Etagged<NotificationPreference>>>
  notificationDeliveries: TeamOpsCursorResource<NotificationDelivery>
  notificationDeliveryDetails: Record<string, TeamOpsResource<Etagged<NotificationDelivery>>>
  operationsHealth: TeamOpsResource<OperationsHealthSummary>
  diagnostics: TeamOpsResource<AdministratorDiagnostics>
  command: TeamOpsCommandState
}

export interface TeamOpsStore {
  state: Readonly<TeamOpsStoreState>
  activateScope(scope: TeamOpsScope): void
  loadTeamActivity(filter?: ActivityFilter, more?: boolean, force?: boolean): Promise<void>
  ingestTeamActivity(scope: TeamOpsScope, item: ActivityItem, resumeCursor?: string | null): boolean
  loadActivityDetail(eventId: string, route?: WorkItemActivityRoute | null, force?: boolean): Promise<void>
  loadWorkItemActivity(route: WorkItemActivityRoute, filter?: ActivityFilter, more?: boolean, force?: boolean): Promise<void>
  loadInbox(filter?: InboxFilter, more?: boolean, force?: boolean): Promise<void>
  loadInboxCounts(force?: boolean): Promise<void>
  loadInboxDetail(itemId: string, force?: boolean): Promise<void>
  loadInboxTarget(itemId: string, force?: boolean): Promise<void>
  changeInboxDisposition(itemId: string, status: string, idempotencyKey: string): Promise<boolean>
  loadAudit(filter?: AuditFilter, more?: boolean, force?: boolean): Promise<void>
  exportAudit(filter: AuditFilter, maximumRows: number): Promise<void>
  loadCorrelation(correlationId: string, more?: boolean, force?: boolean): Promise<void>
  loadLarkConnections(force?: boolean): Promise<void>
  loadLarkConnection(connectionId: string, force?: boolean): Promise<void>
  loadLarkPreflight(bindingId: string, bindingVersion: number, force?: boolean): Promise<void>
  loadLarkHealth(bindingId: string, force?: boolean): Promise<void>
  createLarkConnection(expectedVersion: number, input: CreateLarkConnectionInput, idempotencyKey: string): Promise<boolean>
  rotateLarkConnection(connectionId: string, input: RotateLarkConnectionInput, idempotencyKey: string): Promise<boolean>
  revokeLarkConnection(connectionId: string, reason: string, idempotencyKey: string): Promise<boolean>
  verifyLarkMember(bindingId: string, bindingVersion: number, openId: string, idempotencyKey: string): Promise<boolean>
  confirmLarkMapping(input: ConfirmLarkMappingInput, idempotencyKey: string): Promise<boolean>
  loadLarkMappings(status?: string | null, more?: boolean, force?: boolean): Promise<void>
  revokeLarkMapping(mappingId: string, version: number, reason: string, idempotencyKey: string): Promise<boolean>
  loadNotificationTemplates(force?: boolean): Promise<void>
  loadNotificationPreference(memberId: string, force?: boolean): Promise<void>
  updateNotificationPreference(memberId: string, input: NotificationPreferenceInput, idempotencyKey: string): Promise<boolean>
  loadNotificationDeliveries(filter?: NotificationDeliveryFilter, more?: boolean, force?: boolean): Promise<void>
  loadNotificationDelivery(deliveryId: string, force?: boolean): Promise<void>
  redeliverNotification(deliveryId: string, idempotencyKey: string): Promise<boolean>
  loadOperationsHealth(force?: boolean): Promise<void>
  loadDiagnostics(force?: boolean): Promise<void>
  recover(target: RecoveryCandidate, confirmation: string, idempotencyKey: string): Promise<boolean>
  runProjectionCommand(command: ProjectionCommand, idempotencyKey: string): Promise<boolean>
  clearCommand(): void
  reset(): void
}

export const TEAM_OPS_STORE: InjectionKey<TeamOpsStore> = Symbol('crewscope-team-ops-store')
const PAGE_SIZE = 50

interface ActiveRequest {
  key: string
  version: number
  generation: number
  scopeKey: string
  controller: AbortController
}

/** Team collaboration data layer with abort plus generation guards against late Scope writes. */
export function createTeamOpsStore(gateway: TeamOpsGateway): TeamOpsStore {
  const state = reactive<TeamOpsStoreState>(initialState())
  let activeScope: TeamOpsScope | null = null
  let activeScopeKey: string | null = null
  let generation = 0
  let teamActivityFilterKey = ''
  let inboxFilterKey = ''
  let auditFilterKey = ''
  let mappingFilterKey = ''
  let deliveryFilterKey = ''
  const requests = new Map<string, ActiveRequest>()

  function activateScope(scope: TeamOpsScope): void {
    const nextKey = scopeKey(scope)
    if (nextKey === activeScopeKey) return
    activeScope = { ...scope }
    activeScopeKey = nextKey
    generation += 1
    abortRequests()
    resetFilterKeys()
    replaceState(initialState())
  }

  async function loadTeamActivity(filter: ActivityFilter = {}, more = false, force = false): Promise<void> {
    const scope = requireScope()
    const key = stableFilter(filter)
    if (key !== teamActivityFilterKey) {
      teamActivityFilterKey = key
      state.teamActivity = cursorResource<ActivityItem>()
    }
    await loadCursorPage(
      'team-activity', state.teamActivity, more, force,
      async (cursor, signal) => cursor
        ? gateway.teamActivity(scope, filter, cursor, PAGE_SIZE, signal)
        : gateway.teamActivitySnapshot(scope, filter, PAGE_SIZE, signal),
      item => item.eventId,
      page => 'snapshotCursor' in page && (typeof page.snapshotCursor === 'string' || page.snapshotCursor === null)
        ? page.snapshotCursor
        : state.teamActivity.resumeCursor,
    )
  }

  function ingestTeamActivity(scope: TeamOpsScope, item: ActivityItem, resumeCursor: string | null = null): boolean {
    if (scopeKey(scope) !== activeScopeKey) return false
    const existing = state.teamActivity.value ?? []
    state.teamActivity.value = [item, ...existing.filter(candidate => candidate.eventId !== item.eventId)]
    state.teamActivity.phase = 'ready'
    state.teamActivity.error = null
    if (resumeCursor) state.teamActivity.resumeCursor = resumeCursor
    return true
  }

  async function loadWorkItemActivity(route: WorkItemActivityRoute, filter: ActivityFilter = {}, more = false, force = false): Promise<void> {
    const scope = requireScope()
    const key = workItemActivityCacheKey(route, filter)
    if (!state.workItemActivity[key]) state.workItemActivity[key] = cursorResource<ActivityItem>()
    await loadCursorPage(
      `work-item-activity:${key}`, state.workItemActivity[key]!, more, force,
      async (cursor, signal) => cursor
        ? gateway.workItemActivity(scope, route, filter, cursor, PAGE_SIZE, signal)
        : gateway.workItemActivitySnapshot(scope, route, filter, PAGE_SIZE, signal),
      item => item.eventId,
      page => 'snapshotCursor' in page && (typeof page.snapshotCursor === 'string' || page.snapshotCursor === null)
        ? page.snapshotCursor
        : state.workItemActivity[key]!.resumeCursor,
    )
  }

  async function loadActivityDetail(eventId: string, route: WorkItemActivityRoute | null = null, force = false): Promise<void> {
    const scope = requireScope()
    const key = route ? `${route.projectId}:${route.workItemId}:${eventId}` : `team:${eventId}`
    if (!state.activityDetails[key]) state.activityDetails[key] = resource<ActivityItem>()
    await loadResource(`activity-detail:${key}`, state.activityDetails[key]!, force, signal => route
      ? gateway.workItemActivityDetail(scope, route, eventId, signal)
      : gateway.teamActivityDetail(scope, eventId, signal), '暂时无法加载 Activity 详情')
  }

  async function loadInbox(filter: InboxFilter = {}, more = false, force = false): Promise<void> {
    const scope = requireScope()
    const key = stableFilter(filter)
    if (key !== inboxFilterKey) {
      inboxFilterKey = key
      state.inbox = cursorResource<InboxItem>()
    }
    await loadCursorPage(
      'inbox', state.inbox, more, force,
      (cursor, signal) => gateway.inbox(scope, filter, cursor, PAGE_SIZE, signal),
      item => item.inboxItemId,
    )
  }

  async function loadInboxCounts(force = false): Promise<void> {
    await loadResource('inbox-counts', state.inboxCounts, force, signal => gateway.inboxCounts(requireScope(), signal), '暂时无法加载 Inbox 计数')
  }

  async function loadInboxDetail(itemId: string, force = false): Promise<void> {
    if (!state.inboxDetails[itemId]) state.inboxDetails[itemId] = resource<Etagged<InboxItem>>()
    await loadResource(`inbox-detail:${itemId}`, state.inboxDetails[itemId]!, force, signal => gateway.inboxDetail(requireScope(), itemId, signal), '暂时无法加载 Inbox 详情')
  }

  async function loadInboxTarget(itemId: string, force = false): Promise<void> {
    if (!state.inboxTargets[itemId]) state.inboxTargets[itemId] = resource<InboxTarget>()
    await loadResource(`inbox-target:${itemId}`, state.inboxTargets[itemId]!, force, signal => gateway.inboxTarget(requireScope(), itemId, signal), '暂时无法加载 Inbox 来源')
  }

  async function changeInboxDisposition(itemId: string, status: string, idempotencyKey: string): Promise<boolean> {
    if (state.inboxDetails[itemId]?.phase !== 'ready') await loadInboxDetail(itemId)
    const detail = state.inboxDetails[itemId]?.value
    if (!detail) return false
    return runCommand('inbox-disposition', itemId, () => gateway.changeInboxDisposition(requireScope(), itemId, status, detail.etag, idempotencyKey), () => {
      delete state.inboxDetails[itemId]
      state.inbox = cursorResource<InboxItem>()
      state.inboxCounts = resource<InboxCounts>()
    })
  }

  async function loadAudit(filter: AuditFilter = {}, more = false, force = false): Promise<void> {
    const scope = requireScope()
    const key = stableFilter(filter)
    if (key !== auditFilterKey) {
      auditFilterKey = key
      state.audit = cursorResource<AuditEvent>()
    }
    await loadCursorPage('audit', state.audit, more, force, (cursor, signal) => gateway.audit(scope, filter, cursor, PAGE_SIZE, signal), item => item.eventId)
  }

  async function exportAudit(filter: AuditFilter, maximumRows: number): Promise<void> {
    state.auditExport = resource<AuditExport>()
    await loadResource('audit-export', state.auditExport, true, signal => gateway.exportAudit(requireScope(), filter, maximumRows, signal), '暂时无法导出审计记录')
  }

  async function loadCorrelation(correlationId: string, more = false, force = false): Promise<void> {
    const scope = requireScope()
    if (!state.correlations[correlationId]) state.correlations[correlationId] = correlationResource()
    const target = state.correlations[correlationId]!
    if (more && !target.nextCursor) return
    if (!more && !force && (target.phase === 'ready' || target.phase === 'empty')) return
    const request = beginRequest(`correlation:${correlationId}`)
    target.loadingMore = more
    if (!more) target.phase = 'loading'
    target.error = null
    try {
      const page = await gateway.correlation(scope, correlationId, more ? target.nextCursor : null, PAGE_SIZE, request.controller.signal)
      if (!isCurrent(request)) return
      if (page.correlationId !== correlationId) throw new TypeError('Correlation response escaped the requested graph')
      target.value = mergeCorrelation(more ? target.value : null, page)
      target.nextCursor = page.nextCursor
      target.phase = target.value.events.length === 0 && target.value.objects.length === 0 ? 'empty' : 'ready'
    } catch (error) {
      if (!isAbort(error) && isCurrent(request)) {
        const classified = teamOpsError(error, '暂时无法加载 Correlation 链')
        if (classified.kind === 'cursor-expired') target.nextCursor = null
        target.error = classified
        target.phase = 'error'
      }
    } finally {
      if (isCurrent(request)) target.loadingMore = false
      finishRequest(request)
    }
  }

  async function loadLarkConnections(force = false): Promise<void> {
    await loadResource('lark-connections', state.larkConnections, force, signal => gateway.larkConnections(requireScope(), signal), '暂时无法加载飞书连接')
  }

  async function loadLarkConnection(connectionId: string, force = false): Promise<void> {
    if (!state.larkConnectionDetails[connectionId]) state.larkConnectionDetails[connectionId] = resource<Etagged<LarkConnection>>()
    await loadResource(`lark-connection:${connectionId}`, state.larkConnectionDetails[connectionId]!, force, signal => gateway.larkConnection(requireScope(), connectionId, signal), '暂时无法加载飞书连接')
  }

  async function loadLarkPreflight(bindingId: string, bindingVersion: number, force = false): Promise<void> {
    if (!state.larkPreflights[bindingId]) state.larkPreflights[bindingId] = resource<LarkPreflight>()
    await loadResource(`lark-preflight:${bindingId}`, state.larkPreflights[bindingId]!, force, signal => gateway.larkPreflight(requireScope(), bindingId, `"${bindingVersion}"`, signal), '飞书连接预检失败')
  }

  async function loadLarkHealth(bindingId: string, force = false): Promise<void> {
    if (!state.larkHealth[bindingId]) state.larkHealth[bindingId] = resource<LarkHealth>()
    await loadResource(`lark-health:${bindingId}`, state.larkHealth[bindingId]!, force, signal => gateway.larkHealth(requireScope(), bindingId, signal), '暂时无法加载飞书连接健康')
  }

  function createLarkConnection(expectedVersion: number, input: CreateLarkConnectionInput, idempotencyKey: string): Promise<boolean> {
    // App Secret and Idempotency-Key remain inside this stack frame and are never copied to state.
    return runCommand('lark-create', null, () => gateway.createLarkConnection(requireScope(), expectedVersion, input, idempotencyKey), invalidateLarkConnections)
  }

  async function rotateLarkConnection(connectionId: string, input: RotateLarkConnectionInput, idempotencyKey: string): Promise<boolean> {
    if (state.larkConnectionDetails[connectionId]?.phase !== 'ready') await loadLarkConnection(connectionId)
    const detail = state.larkConnectionDetails[connectionId]?.value
    if (!detail) return false
    return runCommand('lark-rotate', connectionId, () => gateway.rotateLarkConnection(requireScope(), connectionId, detail.etag, input, idempotencyKey), invalidateLarkConnections)
  }

  async function revokeLarkConnection(connectionId: string, reason: string, idempotencyKey: string): Promise<boolean> {
    if (state.larkConnectionDetails[connectionId]?.phase !== 'ready') await loadLarkConnection(connectionId)
    const detail = state.larkConnectionDetails[connectionId]?.value
    if (!detail) return false
    return runCommand('lark-revoke', connectionId, () => gateway.revokeLarkConnection(requireScope(), connectionId, detail.etag, reason, idempotencyKey), invalidateLarkConnections)
  }

  function verifyLarkMember(bindingId: string, bindingVersion: number, openId: string, idempotencyKey: string): Promise<boolean> {
    // The exact external open_id is one-way verification input, not a browser cache field.
    return runCommand('lark-member-verify', bindingId, () => gateway.verifyLarkMember(requireScope(), bindingId, `"${bindingVersion}"`, openId, idempotencyKey))
  }

  function confirmLarkMapping(input: ConfirmLarkMappingInput, idempotencyKey: string): Promise<boolean> {
    return runCommand('lark-mapping-confirm', input.memberId, () => gateway.confirmLarkMapping(requireScope(), input, idempotencyKey), invalidateMappings)
  }

  async function loadLarkMappings(status: string | null = null, more = false, force = false): Promise<void> {
    const scope = requireScope()
    const key = status ?? 'ALL'
    if (key !== mappingFilterKey) {
      mappingFilterKey = key
      state.larkMappings = cursorResource<LarkMapping>()
    }
    await loadCursorPage('lark-mappings', state.larkMappings, more, force, (cursor, signal) => gateway.larkMappings(scope, status, cursor, PAGE_SIZE, signal), item => item.mappingId)
  }

  function revokeLarkMapping(mappingId: string, version: number, reason: string, idempotencyKey: string): Promise<boolean> {
    return runCommand('lark-mapping-revoke', mappingId, () => gateway.revokeLarkMapping(requireScope(), mappingId, version, reason, idempotencyKey), invalidateMappings)
  }

  async function loadNotificationTemplates(force = false): Promise<void> {
    await loadResource('notification-templates', state.notificationTemplates, force, signal => gateway.notificationTemplates(requireScope(), signal), '暂时无法加载通知模板')
  }

  async function loadNotificationPreference(memberId: string, force = false): Promise<void> {
    if (!state.notificationPreferences[memberId]) state.notificationPreferences[memberId] = resource<Etagged<NotificationPreference>>()
    await loadResource(`notification-preference:${memberId}`, state.notificationPreferences[memberId]!, force, signal => gateway.notificationPreference(requireScope(), memberId, signal), '暂时无法加载通知偏好')
  }

  async function updateNotificationPreference(memberId: string, input: NotificationPreferenceInput, idempotencyKey: string): Promise<boolean> {
    if (state.notificationPreferences[memberId]?.phase !== 'ready') await loadNotificationPreference(memberId)
    const detail = state.notificationPreferences[memberId]?.value
    if (!detail) return false
    return runCommand('notification-preference', memberId, () => gateway.updateNotificationPreference(requireScope(), memberId, detail.etag, input, idempotencyKey), () => {
      delete state.notificationPreferences[memberId]
    })
  }

  async function loadNotificationDeliveries(filter: NotificationDeliveryFilter = {}, more = false, force = false): Promise<void> {
    const scope = requireScope()
    const key = stableFilter(filter)
    if (key !== deliveryFilterKey) {
      deliveryFilterKey = key
      state.notificationDeliveries = cursorResource<NotificationDelivery>()
    }
    await loadCursorPage('notification-deliveries', state.notificationDeliveries, more, force, (cursor, signal) => gateway.notificationDeliveries(scope, filter, cursor, PAGE_SIZE, signal), item => item.deliveryId, undefined, page => {
      page.items.forEach(item => {
        if (item.organizationId !== scope.organizationId || item.teamId !== scope.teamId) throw new TypeError('Notification delivery escaped the active Scope')
      })
    })
  }

  async function loadNotificationDelivery(deliveryId: string, force = false): Promise<void> {
    if (!state.notificationDeliveryDetails[deliveryId]) state.notificationDeliveryDetails[deliveryId] = resource<Etagged<NotificationDelivery>>()
    const scope = requireScope()
    await loadResource(`notification-delivery:${deliveryId}`, state.notificationDeliveryDetails[deliveryId]!, force, async signal => {
      const value = await gateway.notificationDelivery(scope, deliveryId, signal)
      if (value.value.organizationId !== scope.organizationId || value.value.teamId !== scope.teamId) throw new TypeError('Notification delivery escaped the active Scope')
      return value
    }, '暂时无法加载通知投递')
  }

  async function redeliverNotification(deliveryId: string, idempotencyKey: string): Promise<boolean> {
    if (state.notificationDeliveryDetails[deliveryId]?.phase !== 'ready') await loadNotificationDelivery(deliveryId)
    const detail = state.notificationDeliveryDetails[deliveryId]?.value
    if (!detail) return false
    return runCommand('notification-redeliver', deliveryId, () => gateway.redeliverNotification(requireScope(), deliveryId, detail.etag, idempotencyKey), invalidateDeliveries)
  }

  async function loadOperationsHealth(force = false): Promise<void> {
    await loadResource('operations-health', state.operationsHealth, force, signal => gateway.operationsHealth(requireScope(), signal), '暂时无法加载运行健康')
  }

  async function loadDiagnostics(force = false): Promise<void> {
    const scope = requireScope()
    await loadResource('operations-diagnostics', state.diagnostics, force, signal => gateway.administratorDiagnostics(scope.organizationId, signal), '暂时无法加载运维诊断')
  }

  function recover(target: RecoveryCandidate, confirmation: string, idempotencyKey: string): Promise<boolean> {
    const scope = requireScope()
    return runCommand('operations-recover', target.referenceHash, () => gateway.recover(scope.organizationId, target, confirmation, idempotencyKey), invalidateOperations)
  }

  function runProjectionCommand(command: ProjectionCommand, idempotencyKey: string): Promise<boolean> {
    const scope = requireScope()
    // Confirmation/body and Idempotency-Key are never retained or automatically replayed.
    return runCommand(`projection-${command.operation}`, command.projectionName, () => gateway.projectionCommand(scope.organizationId, command, idempotencyKey), invalidateOperations)
  }

  async function loadResource<T>(requestKey: string, target: TeamOpsResource<T>, force: boolean, loader: (signal: AbortSignal) => Promise<T>, fallback: string): Promise<void> {
    if (!force && (target.phase === 'ready' || target.phase === 'empty')) return
    const request = beginRequest(requestKey)
    target.phase = 'loading'
    target.error = null
    try {
      const value = await loader(request.controller.signal)
      if (!isCurrent(request)) return
      target.value = value
      target.phase = Array.isArray(value) && value.length === 0 ? 'empty' : 'ready'
    } catch (error) {
      if (!isAbort(error) && isCurrent(request)) setError(target, error, fallback)
    } finally {
      finishRequest(request)
    }
  }

  async function loadCursorPage<T, P extends { items: T[], nextCursor: string | null }>(
    requestKey: string,
    target: TeamOpsCursorResource<T>,
    more: boolean,
    force: boolean,
    loader: (cursor: string | null, signal: AbortSignal) => Promise<P>,
    identity: (item: T) => string,
    resume?: (page: P) => string | null,
    validate?: (page: P) => void,
  ): Promise<void> {
    if (more && !target.nextCursor) return
    if (!more && !force && (target.phase === 'ready' || target.phase === 'empty')) return
    const request = beginRequest(requestKey)
    target.loadingMore = more
    if (!more) target.phase = 'loading'
    target.error = null
    try {
      const page = await loader(more ? target.nextCursor : null, request.controller.signal)
      if (!isCurrent(request)) return
      validate?.(page)
      target.value = merge(more ? target.value ?? [] : [], page.items, identity)
      target.nextCursor = page.nextCursor
      target.resumeCursor = resume?.(page) ?? target.resumeCursor
      target.phase = target.value.length === 0 ? 'empty' : 'ready'
    } catch (error) {
      if (!isAbort(error) && isCurrent(request)) {
        const classified = teamOpsError(error, '暂时无法加载团队协作数据')
        if (classified.kind === 'cursor-expired') target.nextCursor = null
        target.error = classified
        target.phase = 'error'
      }
    } finally {
      if (isCurrent(request)) target.loadingMore = false
      finishRequest(request)
    }
  }

  async function runCommand(
    operation: string,
    targetId: string | null,
    execute: () => Promise<CommandReceipt | ProjectionCommandReceipt | RecoveryReceipt>,
    onSuccess?: () => void,
  ): Promise<boolean> {
    // One shared receipt/error slot permits one in-flight command across all Team operations.
    if (state.command.phase === 'pending') return false
    const commandGeneration = generation
    state.command = { phase: 'pending', operation, targetId, receipt: null, error: null }
    try {
      const receipt = await execute()
      if (commandGeneration !== generation) return false
      onSuccess?.()
      state.command.phase = 'success'
      state.command.receipt = receipt
      return true
    } catch (error) {
      if (commandGeneration !== generation) return false
      const classified = teamOpsError(error, '团队协作命令执行失败')
      state.command.phase = classified.kind === 'conflict' ? 'conflict' : 'error'
      state.command.error = classified
      return false
    }
  }

  function beginRequest(key: string): ActiveRequest {
    requests.get(key)?.controller.abort()
    const request: ActiveRequest = {
      key, version: (requests.get(key)?.version ?? 0) + 1, generation,
      scopeKey: activeScopeKey!, controller: new AbortController(),
    }
    requests.set(key, request)
    return request
  }

  function isCurrent(request: ActiveRequest): boolean {
    return generation === request.generation && activeScopeKey === request.scopeKey && requests.get(request.key) === request
  }

  function finishRequest(request: ActiveRequest): void {
    if (requests.get(request.key) === request) requests.delete(request.key)
  }

  function abortRequests(): void {
    requests.forEach(request => request.controller.abort())
    requests.clear()
  }

  function requireScope(): TeamOpsScope {
    if (!activeScope) throw new Error('Team operations Scope is not active')
    return { ...activeScope }
  }

  function invalidateLarkConnections(): void {
    state.larkConnections = resource<LarkConnection[]>()
    state.larkConnectionDetails = {}
  }

  function invalidateMappings(): void {
    state.larkMappings = cursorResource<LarkMapping>()
  }

  function invalidateDeliveries(): void {
    state.notificationDeliveries = cursorResource<NotificationDelivery>()
    state.notificationDeliveryDetails = {}
  }

  function invalidateOperations(): void {
    state.operationsHealth = resource<OperationsHealthSummary>()
    state.diagnostics = resource<AdministratorDiagnostics>()
  }

  function clearCommand(): void {
    state.command = commandState()
  }

  function reset(): void {
    generation += 1
    abortRequests()
    activeScope = null
    activeScopeKey = null
    resetFilterKeys()
    replaceState(initialState())
  }

  function resetFilterKeys(): void {
    teamActivityFilterKey = inboxFilterKey = auditFilterKey = mappingFilterKey = deliveryFilterKey = ''
  }

  function replaceState(next: TeamOpsStoreState): void {
    Object.assign(state, next)
  }

  return {
    state: readonly(state) as Readonly<TeamOpsStoreState>,
    activateScope,
    loadTeamActivity,
    ingestTeamActivity,
    loadActivityDetail,
    loadWorkItemActivity,
    loadInbox,
    loadInboxCounts,
    loadInboxDetail,
    loadInboxTarget,
    changeInboxDisposition,
    loadAudit,
    exportAudit,
    loadCorrelation,
    loadLarkConnections,
    loadLarkConnection,
    loadLarkPreflight,
    loadLarkHealth,
    createLarkConnection,
    rotateLarkConnection,
    revokeLarkConnection,
    verifyLarkMember,
    confirmLarkMapping,
    loadLarkMappings,
    revokeLarkMapping,
    loadNotificationTemplates,
    loadNotificationPreference,
    updateNotificationPreference,
    loadNotificationDeliveries,
    loadNotificationDelivery,
    redeliverNotification,
    loadOperationsHealth,
    loadDiagnostics,
    recover,
    runProjectionCommand,
    clearCommand,
    reset,
  }
}

export function installTeamOpsStore(app: App, gateway: TeamOpsGateway): TeamOpsStore {
  const store = createTeamOpsStore(gateway)
  app.provide(TEAM_OPS_STORE, store)
  return store
}

export function useTeamOpsStore(): TeamOpsStore {
  const store = inject(TEAM_OPS_STORE)
  if (!store) throw new Error('CrewScope Team Operations Store is not installed')
  return store
}

function initialState(): TeamOpsStoreState {
  return {
    teamActivity: cursorResource<ActivityItem>(),
    activityDetails: {},
    workItemActivity: {},
    inbox: cursorResource<InboxItem>(),
    inboxCounts: resource<InboxCounts>(),
    inboxDetails: {},
    inboxTargets: {},
    audit: cursorResource<AuditEvent>(),
    auditExport: resource<AuditExport>(),
    correlations: {},
    larkConnections: resource<LarkConnection[]>(),
    larkConnectionDetails: {},
    larkPreflights: {},
    larkHealth: {},
    larkMappings: cursorResource<LarkMapping>(),
    notificationTemplates: resource<NotificationTemplate[]>(),
    notificationPreferences: {},
    notificationDeliveries: cursorResource<NotificationDelivery>(),
    notificationDeliveryDetails: {},
    operationsHealth: resource<OperationsHealthSummary>(),
    diagnostics: resource<AdministratorDiagnostics>(),
    command: commandState(),
  }
}

function resource<T>(): TeamOpsResource<T> {
  return { phase: 'idle', value: null, error: null }
}

function cursorResource<T>(): TeamOpsCursorResource<T> {
  return { ...resource<T[]>(), nextCursor: null, resumeCursor: null, loadingMore: false }
}

function correlationResource(): TeamOpsCorrelationResource {
  return { ...resource<CorrelationGraph>(), nextCursor: null, loadingMore: false }
}

function commandState(): TeamOpsCommandState {
  return { phase: 'idle', operation: null, targetId: null, receipt: null, error: null }
}

function setError<T>(target: TeamOpsResource<T>, error: unknown, fallback: string): void {
  target.phase = 'error'
  // A refresh error changes freshness, not the last successfully authorized public fact.
  // Initial failures still keep null because the resource has never received a value.
  target.error = teamOpsError(error, fallback)
}

function merge<T>(existing: T[], incoming: T[], identity: (item: T) => string): T[] {
  const result = [...existing]
  const known = new Set(existing.map(identity))
  incoming.forEach(item => {
    const key = identity(item)
    if (!known.has(key)) {
      known.add(key)
      result.push(item)
    }
  })
  return result
}

function mergeCorrelation(existing: CorrelationGraph | null, incoming: CorrelationGraph): CorrelationGraph {
  if (!existing) return incoming
  const events = merge(existing.events, incoming.events, item => item.eventId)
  const objectsByKey = new Map(existing.objects.map(item => [`${item.type}:${item.id}`, { ...item, relatedEventIds: [...item.relatedEventIds] }]))
  incoming.objects.forEach(item => {
    const key = `${item.type}:${item.id}`
    const current = objectsByKey.get(key)
    objectsByKey.set(key, current
      ? { ...current, relatedEventIds: [...new Set([...current.relatedEventIds, ...item.relatedEventIds])] }
      : item)
  })
  return { ...incoming, events, objects: [...objectsByKey.values()] }
}

function scopeKey(scope: TeamOpsScope): string {
  if (!scope.organizationId || !scope.teamId) throw new TypeError('Team operations Scope is incomplete')
  return `${scope.organizationId}:${scope.teamId}`
}

function stableFilter(value: object): string {
  return JSON.stringify(Object.fromEntries(Object.entries(value)
    .filter(([, item]) => item != null)
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([key, item]) => [key, Array.isArray(item) ? [...item].sort() : item])))
}

/** Shared key used by WorkItem pages to select their scoped Activity resource. */
export function workItemActivityCacheKey(route: WorkItemActivityRoute, filter: ActivityFilter = {}): string {
  return `${route.projectId}:${route.workItemId}:${stableFilter(filter)}`
}
