import { inject, reactive, readonly, type App, type InjectionKey } from 'vue'
import { CrewScopeApiError } from '../../api/client'
import type { TaskIntentGateway } from './taskIntentGateway'
import type {
  ConversationMessageScope,
  TaskIntent,
  TaskIntentRevisionInput,
} from './types'

export type TaskIntentPhase = 'idle' | 'loading' | 'ready' | 'error'
export type TaskIntentCommand = 'revise' | 'reject' | 'confirm'

interface TaskIntentState {
  phase: TaskIntentPhase
  taskIntentId: string | null
  intent: TaskIntent | null
  etag: string | null
  errorMessage: string | null
  errorStatus: number | null
  commandPending: TaskIntentCommand | null
  commandErrorMessage: string | null
  commandErrorStatus: number | null
  versionConflict: boolean
}

export interface TaskIntentStore {
  state: Readonly<TaskIntentState>
  synchronize(scope: ConversationMessageScope, taskIntentId: string | null): Promise<void>
  load(scope: ConversationMessageScope, taskIntentId: string, force?: boolean): Promise<void>
  revise(input: TaskIntentRevisionInput): Promise<boolean>
  reject(reason: string): Promise<boolean>
  confirm(): Promise<boolean>
  reset(): void
}

export const TASK_INTENT_STORE: InjectionKey<TaskIntentStore> = Symbol('crewscope-task-intent-store')

export function createTaskIntentStore(gateway: TaskIntentGateway): TaskIntentStore {
  const state = reactive<TaskIntentState>({
    phase: 'idle',
    taskIntentId: null,
    intent: null,
    etag: null,
    errorMessage: null,
    errorStatus: null,
    commandPending: null,
    commandErrorMessage: null,
    commandErrorStatus: null,
    versionConflict: false,
  })
  let activeScope: ConversationMessageScope | null = null
  let activeKey: string | null = null
  let requestVersion = 0

  async function synchronize(scope: ConversationMessageScope, taskIntentId: string | null): Promise<void> {
    if (!taskIntentId) {
      reset()
      activeScope = { ...scope }
      return
    }
    await load(scope, taskIntentId)
  }

  async function load(scope: ConversationMessageScope, taskIntentId: string, force = false): Promise<void> {
    const key = resourceKey(scope, taskIntentId)
    if (!force && key === activeKey && state.phase === 'ready') return
    const version = ++requestVersion
    const changed = key !== activeKey
    activeScope = { ...scope }
    activeKey = key
    state.taskIntentId = taskIntentId
    state.phase = 'loading'
    state.errorMessage = null
    state.errorStatus = null
    if (changed) {
      state.intent = null
      state.etag = null
      clearCommandState()
    }
    try {
      const resource = await gateway.get(scope, taskIntentId)
      if (version !== requestVersion || activeKey !== key) return
      state.intent = resource.value
      state.etag = resource.etag
      state.phase = 'ready'
    } catch (error) {
      if (version !== requestVersion || activeKey !== key) return
      state.phase = 'error'
      state.errorMessage = presentError(error, '暂时无法加载任务提案')
      state.errorStatus = statusOf(error)
    }
  }

  async function revise(input: TaskIntentRevisionInput): Promise<boolean> {
    return command('revise', async context => {
      await gateway.revise(
        context.scope, context.intent.id, input, context.intent.version, crypto.randomUUID(),
      )
    })
  }

  async function reject(reason: string): Promise<boolean> {
    const normalized = reason.trim()
    if (!normalized || normalized.length > 1_000) {
      state.commandErrorMessage = '拒绝原因需包含 1–1000 个字符'
      return false
    }
    return command('reject', async context => {
      await gateway.reject(
        context.scope, context.intent.id, normalized, context.intent.version, crypto.randomUUID(),
      )
    })
  }

  async function confirm(): Promise<boolean> {
    return command('confirm', async context => {
      const preview = await gateway.previewConfirmation(
        context.scope, context.intent.id, context.intent.version,
      )
      if (!preview.value.confirmable
        || preview.value.taskIntentId !== context.intent.id
        || preview.value.version !== context.intent.version
        || preview.etag !== context.etag
        || preview.value.proposalRevision !== context.intent.proposalRevision
        || !sameProposal(preview.value.proposal, context.intent.proposal)) {
        throw new TaskIntentPreviewMismatchError()
      }
      await gateway.confirm(
        context.scope, context.intent.id, context.intent.version, crypto.randomUUID(),
      )
    })
  }

  async function command(
    name: TaskIntentCommand,
    action: (context: CommandContext) => Promise<void>,
  ): Promise<boolean> {
    if (state.commandPending || !activeScope || !state.intent || !state.etag) return false
    const context: CommandContext = {
      scope: { ...activeScope },
      key: activeKey!,
      intent: state.intent,
      etag: state.etag,
    }
    state.commandPending = name
    state.commandErrorMessage = null
    state.commandErrorStatus = null
    state.versionConflict = false
    try {
      await action(context)
      if (activeKey === context.key) await load(context.scope, context.intent.id, true)
      return activeKey === context.key && state.phase === 'ready'
    } catch (error) {
      if (activeKey !== context.key) return false
      const status = statusOf(error)
      state.commandErrorStatus = status
      if (error instanceof TaskIntentPreviewMismatchError || status === 409 || status === 412) {
        state.versionConflict = true
        state.commandErrorMessage = '任务提案已发生变化，已刷新为最新事实，请重新检查后操作'
        await load(context.scope, context.intent.id, true)
      } else {
        state.commandErrorMessage = presentError(error, '暂时无法更新任务提案')
      }
      return false
    } finally {
      if (activeKey === context.key) state.commandPending = null
    }
  }

  function reset(): void {
    requestVersion += 1
    activeScope = null
    activeKey = null
    state.phase = 'idle'
    state.taskIntentId = null
    state.intent = null
    state.etag = null
    state.errorMessage = null
    state.errorStatus = null
    clearCommandState()
  }

  function clearCommandState(): void {
    state.commandPending = null
    state.commandErrorMessage = null
    state.commandErrorStatus = null
    state.versionConflict = false
  }

  return { state: readonly(state) as Readonly<TaskIntentState>, synchronize, load, revise, reject, confirm, reset }
}

export function installTaskIntentStore(app: App, gateway: TaskIntentGateway): TaskIntentStore {
  const store = createTaskIntentStore(gateway)
  app.provide(TASK_INTENT_STORE, store)
  return store
}

export function useTaskIntentStore(): TaskIntentStore {
  const store = inject(TASK_INTENT_STORE)
  if (!store) throw new Error('CrewScope TaskIntent Store is not installed')
  return store
}

interface CommandContext {
  scope: ConversationMessageScope
  key: string
  intent: TaskIntent
  etag: string
}

class TaskIntentPreviewMismatchError extends Error {}

function resourceKey(scope: ConversationMessageScope, taskIntentId: string): string {
  return `${scope.organizationId}:${scope.teamId}:${scope.conversationId}:${taskIntentId}`
}

function sameProposal(left: TaskIntent['proposal'], right: TaskIntent['proposal']): boolean {
  return left.workProjectId === right.workProjectId
    && left.objective === right.objective
    && left.acceptanceCriteria.length === right.acceptanceCriteria.length
    && left.acceptanceCriteria.every((criterion, index) => criterion === right.acceptanceCriteria[index])
    && sameResponsibility(left.owner, right.owner)
    && sameResponsibility(left.executor, right.executor)
    && sameResponsibility(left.gateReviewer, right.gateReviewer)
}

function sameResponsibility(
  left: TaskIntent['proposal']['owner'] | null,
  right: TaskIntent['proposal']['owner'] | null,
): boolean {
  return left === null || right === null
    ? left === right
    : left.role === right.role
      && left.principalId === right.principalId
      && left.principalType === right.principalType
      && left.teamMemberId === right.teamMemberId
}

function statusOf(error: unknown): number | null {
  return error instanceof CrewScopeApiError ? error.status : null
}

function presentError(error: unknown, fallback: string): string {
  return error instanceof CrewScopeApiError ? error.envelope.message : fallback
}
