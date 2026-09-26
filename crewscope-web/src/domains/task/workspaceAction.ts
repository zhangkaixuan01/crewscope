import type {
  MemberTaskCommandOperation,
  TaskExecutionStatus,
  TaskExecutionWaitReason,
  TaskStatus,
} from './types'

/**
 * The nine decision levels of the workspace primary action (contract §4.7).
 *
 * The levels are ordered exactly as the contract states them; `resolveWorkspaceAction` walks them
 * top-down and the first matching level wins, so a stronger verdict can never be masked by a weaker
 * one (a version conflict is reported even while an execution happens to be running).
 */
export type WorkspaceActionState =
  | 'FORBIDDEN'              // 1 无权限
  | 'HISTORY_VIEW'           // 2 历史视图
  | 'COMMAND_PENDING'        // 3 命令提交中
  | 'VERSION_CONFLICT'       // 4 版本冲突
  | 'SELECTION_REQUIRED'     // 5 多 Task 未选（WorkItem 级）
  | 'CONFIGURATION_MISSING'  // 6 配置缺失
  | 'EXECUTING'              // 7 正在执行
  | 'AWAITING_DECISION'      // 8 等待审查 / 要求修改 / 外部交付确认
  | 'IDLE'                   // 9 未启动 / 已暂停 / 终态

export interface WorkspaceActionInput {
  canControl: boolean
  /** The selected execution is not one the Task currently has: history evidence, no live control. */
  historyView: boolean
  commandPending: boolean
  versionConflict: boolean
  /** WorkItem level: several Tasks run in parallel and no Task was chosen. */
  selectionRequired: boolean
  /** The coding target or delegation preflight the Task needs is missing. */
  configurationMissing: boolean
  executionStatus: TaskExecutionStatus | null
  waitingReason: TaskExecutionWaitReason | null
  /** An OPEN / IN_PROGRESS gate review or a CHANGES_REQUESTED round is pending on this execution. */
  reviewAwaited: boolean
  /** A delivery dispatch is in UNKNOWN / RECONCILING / MANUAL_REVIEW and awaits confirmation. */
  deliveryAwaited: boolean
  taskStatus: TaskStatus | null
}

/** One primary button and at most one secondary link — nothing else joins the action strip. */
export interface WorkspaceAction {
  state: WorkspaceActionState
  /** One sentence naming what the workspace is waiting on. */
  headline: string
  /** Why, in the server's own vocabulary; never a synthesized green verdict. */
  detail: string | null
  /** The single primary operation, offered through the existing control flow. */
  operation: MemberTaskCommandOperation | null
  /** Where the secondary link lands, if the level has one. */
  anchor: 'ws-execution' | 'ws-review' | null
}

const WAITING_ON_EXECUTION: ReadonlySet<TaskExecutionWaitReason> = new Set(['RUNTIME', 'EVENT', 'MANUAL'])
const WAITING_ON_PEOPLE: ReadonlySet<TaskExecutionWaitReason> = new Set([
  'REVIEW', 'CONFIRMATION', 'USER_INPUT', 'COLLABORATION', 'EXTERNAL_EXECUTION',
])
const LIVE_STATUSES: ReadonlySet<TaskExecutionStatus> = new Set([
  'CLAIMED', 'PREPARING', 'RUNNING', 'RECOVERING', 'PAUSE_REQUESTED', 'CANCEL_REQUESTED',
])
const TERMINAL_STATUSES: ReadonlySet<TaskExecutionStatus> = new Set(['COMPLETED', 'FAILED', 'CANCELLED'])

export function resolveWorkspaceAction(input: WorkspaceActionInput): WorkspaceAction {
  if (!input.canControl) {
    return {
      state: 'FORBIDDEN',
      headline: '只读视图',
      detail: '当前成员没有这个 Task 的 Owner 或 Executor 控制责任，事实与证据照常可见。',
      operation: null,
      anchor: null,
    }
  }
  if (input.historyView) {
    return {
      state: 'HISTORY_VIEW',
      headline: '历史执行视图',
      detail: '正在查看的执行已不在当前执行列表中，按历史证据只读呈现，控制入口已收起。',
      operation: null,
      anchor: null,
    }
  }
  if (input.commandPending) {
    return {
      state: 'COMMAND_PENDING',
      headline: '命令提交中',
      detail: '上一条控制命令仍在等待服务端结论，完成前不接受新的写动作。',
      operation: null,
      anchor: 'ws-execution',
    }
  }
  if (input.versionConflict) {
    return {
      state: 'VERSION_CONFLICT',
      headline: '执行事实已变化',
      detail: '命令基于的版本已过期，页面已刷新为服务端当前事实，请核对后重新提交。',
      operation: null,
      anchor: 'ws-execution',
    }
  }
  if (input.selectionRequired) {
    return {
      state: 'SELECTION_REQUIRED',
      headline: '多个 Task 并行，先选择目标',
      detail: '此工作项下有多条并行 Task；写动作需要一个明确的目标，不会按列表顺序猜测。',
      operation: null,
      anchor: null,
    }
  }
  if (input.configurationMissing) {
    return {
      state: 'CONFIGURATION_MISSING',
      headline: '执行前置配置缺失',
      detail: '启动所需的编码目标或 Agent 配置尚未就绪，完成配置后这里会给出启动入口。',
      operation: null,
      anchor: null,
    }
  }
  if (input.executionStatus && (LIVE_STATUSES.has(input.executionStatus)
    || (input.executionStatus === 'WAITING' && (input.waitingReason === null || WAITING_ON_EXECUTION.has(input.waitingReason))))) {
    return {
      state: 'EXECUTING',
      headline: input.executionStatus === 'WAITING' ? '执行等待中' : '正在执行',
      detail: input.executionStatus === 'WAITING' && input.waitingReason
        ? `执行在等待 ${waitReasonText(input.waitingReason)}，恢复后自动继续。`
        : 'Agent 正在当前执行上工作，进展以 Timeline 与 Runtime 事实为准。',
      // CANCEL_REQUESTED already has a cancellation in flight — the control region correctly
      // offers no second CANCEL there, and the strip must not advertise one either.
      operation: input.executionStatus === 'CANCEL_REQUESTED' ? null : 'CANCEL',
      anchor: 'ws-execution',
    }
  }
  if (input.reviewAwaited || input.deliveryAwaited
    || (input.executionStatus === 'WAITING' && input.waitingReason !== null && WAITING_ON_PEOPLE.has(input.waitingReason))) {
    return {
      state: 'AWAITING_DECISION',
      headline: input.reviewAwaited ? '等待人工审查' : input.deliveryAwaited ? '等待交付确认' : '等待成员决定',
      detail: '当前阶段的处理入口与固定版本证据在「审查与交付」区，结论以服务端回执为准。',
      operation: null,
      anchor: 'ws-review',
    }
  }
  if (input.executionStatus === 'PAUSED') {
    return {
      state: 'IDLE',
      headline: '执行已暂停',
      detail: '执行保留在暂停点，恢复会从同一 AgentRun 继续，不创建新的执行。',
      operation: 'RESUME',
      anchor: 'ws-execution',
    }
  }
  if (input.executionStatus && TERMINAL_STATUSES.has(input.executionStatus)) {
    return {
      state: 'IDLE',
      headline: input.executionStatus === 'COMPLETED' ? '执行已完成' : input.executionStatus === 'FAILED' ? '执行失败' : '执行已取消',
      detail: '结论以固定版本证据为准；测试与人审结果在对应分区分别呈现。',
      operation: null,
      anchor: null,
    }
  }
  return {
    state: 'IDLE',
    headline: input.taskStatus === 'CREATED' ? '尚未启动' : '空闲',
    detail: '当前没有进行中的执行；启动入口在所属工作项的委托流程中。',
    operation: null,
    anchor: null,
  }
}

/**
 * The operations the server-side attempt state admits — the same rule TaskControlPanel applies, as a
 * pure function so the primary action strip and the control panel can never drift apart.
 */
export function taskControlOperations(attempt: {
  status: TaskExecutionStatus
  attempt: number
  maxAttempts: number
  terminal: { failureClass: string | null } | null
}): MemberTaskCommandOperation[] {
  const available: MemberTaskCommandOperation[] = []
  if (attempt.status === 'RUNNING') available.push('PAUSE')
  if (attempt.status === 'PAUSED') available.push('RESUME')
  if (CANCELLABLE_STATUSES.has(attempt.status)) available.push('CANCEL')
  if (attempt.status === 'FAILED'
    && attempt.attempt < attempt.maxAttempts
    && attempt.terminal?.failureClass
    && RETRYABLE_FAILURE_CLASSES.has(attempt.terminal.failureClass)) available.push('RETRY')
  return available
}

const CANCELLABLE_STATUSES: ReadonlySet<TaskExecutionStatus> = new Set([
  'CREATED', 'READY', 'CLAIMED', 'PREPARING', 'RUNNING', 'WAITING',
  'PAUSE_REQUESTED', 'PAUSED', 'RECOVERING', 'MANUAL_TAKEOVER',
])
const RETRYABLE_FAILURE_CLASSES: ReadonlySet<string> = new Set([
  'TRANSIENT', 'RATE_LIMITED', 'TIMEOUT', 'RUNTIME_UNAVAILABLE', 'MODEL_UNAVAILABLE',
  'TOOL_UNAVAILABLE', 'RESOURCE_EXHAUSTED', 'RECOVERY_INTERRUPTED',
])

function waitReasonText(reason: TaskExecutionWaitReason): string {
  return ({
    RUNTIME: 'Runtime 容量',
    COLLABORATION: '协作输入',
    REVIEW: '人工审查',
    CONFIRMATION: '交付确认',
    USER_INPUT: '成员输入',
    EXTERNAL_EXECUTION: '外部执行',
    EVENT: '外部事件',
    MANUAL: '人工调度',
  } as Record<TaskExecutionWaitReason, string>)[reason]
}
