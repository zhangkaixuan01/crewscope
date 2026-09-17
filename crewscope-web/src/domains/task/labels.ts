import type {
  AgentInterruptKind,
  AgentInterruptStatus,
  AgentRunContinuityGapReason,
  AgentRunSegmentKind,
  AgentRunSegmentStatus,
  AgentRunStatus,
  AgentRuntimeSessionStatus,
  AgentSessionPurpose,
  AgentStateSnapshotStatus,
  ExecutionLeasePhase,
  ExecutionLeaseReleaseReason,
  ExecutionLeaseStatus,
  PlanChangeReason,
  PlanStepType,
  RuntimeFleetHealth,
  RuntimeWaitCause,
  StepExecutionStatus,
  StepWaitReason,
  TaskExecutionControlRequestType,
  TaskExecutionFailureClass,
  TaskExecutionStatus,
  TaskExecutionWaitReason,
  TaskSourceType,
  TaskStatus,
  TodoStatus,
} from './types'

/**
 * User-facing labels for the server-owned Task and runtime enums.
 *
 * Runtime facts are the most enum-dense surface in the product: a single Task drawer renders
 * execution, step, run, segment, interrupt, snapshot and lease states side by side. Every map here
 * is typed `Record<Enum, string>` against the union that mirrors the Java constant names, so a new
 * backend value is a compile error rather than a raw `FAILED_RETRYABLE` on screen.
 */
export const taskStatusLabels: Record<TaskStatus, string> = {
  CREATED: '已创建',
  ACTIVE: '进行中',
  WAITING: '等待中',
  COMPLETED: '已完成',
  FAILED: '已失败',
  CANCELLED: '已取消',
}

export const taskExecutionStatusLabels: Record<TaskExecutionStatus, string> = {
  CREATED: '已创建',
  READY: '待调度',
  CLAIMED: '已领取',
  PREPARING: '准备中',
  RUNNING: '执行中',
  WAITING: '等待中',
  PAUSE_REQUESTED: '暂停中',
  PAUSED: '已暂停',
  RECOVERING: '恢复中',
  CANCEL_REQUESTED: '取消中',
  MANUAL_TAKEOVER: '人工接管',
  COMPLETED: '已完成',
  FAILED: '已失败',
  CANCELLED: '已取消',
}

/** Why an execution is parked. Mirrors `TaskExecutionWaitReason`. */
export const taskExecutionWaitReasonLabels: Record<TaskExecutionWaitReason, string> = {
  RUNTIME: '等待运行时资源',
  COLLABORATION: '等待协作方',
  REVIEW: '等待评审',
  CONFIRMATION: '等待人工确认',
  USER_INPUT: '等待成员补充信息',
  EXTERNAL_EXECUTION: '等待外部执行',
  EVENT: '等待外部事件',
  MANUAL: '等待人工处理',
}

export const taskControlRequestTypeLabels: Record<TaskExecutionControlRequestType, string> = {
  PAUSE: '暂停请求',
  CANCEL: '取消请求',
}

/** Mirrors `TaskExecutionFailureClass`; the wording says what a member can do next. */
export const taskFailureClassLabels: Record<TaskExecutionFailureClass, string> = {
  TRANSIENT: '瞬时故障（可重试）',
  RATE_LIMITED: '触发限流',
  TIMEOUT: '执行超时',
  RUNTIME_UNAVAILABLE: '运行时不可用',
  MODEL_UNAVAILABLE: '模型不可用',
  TOOL_UNAVAILABLE: '工具不可用',
  RESOURCE_EXHAUSTED: '资源已耗尽',
  RECOVERY_INTERRUPTED: '恢复过程被中断',
  VALIDATION: '入参校验失败',
  AUTHENTICATION: '凭据认证失败',
  AUTHORIZATION: '权限不足',
  POLICY_VIOLATION: '违反策略',
  CAPABILITY_UNSUPPORTED: '运行时不支持该能力',
  NOT_FOUND: '目标不存在',
  CONFLICT: '版本冲突',
  INTERNAL: '内部错误',
}

export const taskSourceTypeLabels: Record<TaskSourceType, string> = {
  WORK_ITEM: '工作项',
  CONVERSATION: '对话',
}

export const planStepTypeLabels: Record<PlanStepType, string> = {
  ANALYSIS: '分析',
  IMPLEMENTATION: '实现',
  VALIDATION: '验证',
  REVIEW: '评审',
  DELIVERY: '交付',
}

export const planChangeReasonLabels: Record<PlanChangeReason, string> = {
  INITIAL_PLAN: '初始计划',
  REQUIREMENTS_CHANGED: '需求变更',
  POLICY_CHANGED: '策略变更',
  RECOVERY_REPLAN: '恢复重规划',
  REVIEW_FEEDBACK: '评审反馈',
  MANUAL_REVISION: '人工修订',
}

/** Mirrors `TodoStatus` on the plan todo summary. */
export const todoStatusLabels: Record<TodoStatus, string> = {
  PENDING: '待开始',
  IN_PROGRESS: '进行中',
  COMPLETED: '已完成',
}

export const stepExecutionStatusLabels: Record<StepExecutionStatus, string> = {
  PENDING: '待开始',
  READY: '待调度',
  RUNNING: '执行中',
  WAITING: '等待中',
  SUCCEEDED: '已成功',
  FAILED_RETRYABLE: '失败（可重试）',
  FAILED_FINAL: '失败（终止）',
  SKIPPED: '已跳过',
  CANCELLED: '已取消',
}

/**
 * Mirrors `StepWaitReason`. It overlaps `TaskExecutionWaitReason` but is not the same enum — a step
 * can additionally be waiting on an Agent interrupt, a handoff or a takeover — so it gets its own
 * map rather than sharing one and silently falling through.
 */
export const stepWaitReasonLabels: Record<StepWaitReason, string> = {
  AGENT_INTERRUPT: '等待 Agent 中断处理',
  COLLABORATION: '等待协作方',
  REVIEW: '等待评审',
  HANDOFF: '等待交接',
  TAKEOVER: '等待人工接管',
  CONFIRMATION: '等待人工确认',
  EXTERNAL_EXECUTION: '等待外部执行',
  EVENT: '等待外部事件',
  USER_INPUT: '等待成员补充信息',
  MANUAL: '等待人工处理',
}

export const agentSessionPurposeLabels: Record<AgentSessionPurpose, string> = {
  TASK: '任务级会话',
  STEP: '步骤级会话',
  SPECIALIST: 'Specialist 会话',
}

export const agentRuntimeSessionStatusLabels: Record<AgentRuntimeSessionStatus, string> = {
  ACTIVE: '活跃',
  DISABLED: '已停用',
  ARCHIVED: '已归档',
}

export const agentRunStatusLabels: Record<AgentRunStatus, string> = {
  RUNNING: '执行中',
  INTERRUPTED: '已中断',
  COMPLETED: '已完成',
  FAILED: '已失败',
  CANCELLED: '已取消',
}

export const agentRunSegmentKindLabels: Record<AgentRunSegmentKind, string> = {
  INVOKE: '首次调用',
  RESUME: '中断恢复',
  RECOVERY: '故障恢复',
}

export const agentRunSegmentStatusLabels: Record<AgentRunSegmentStatus, string> = {
  ACTIVE: '进行中',
  INTERRUPTED: '已中断',
  COMPLETED: '已完成',
  FAILED: '已失败',
  CANCELLED: '已取消',
}

export const agentInterruptKindLabels: Record<AgentInterruptKind, string> = {
  CLARIFICATION: '澄清提问',
  PERMISSION: '权限申请',
  APPROVAL: '人工审批',
  PAUSE: '暂停等待',
}

export const agentInterruptStatusLabels: Record<AgentInterruptStatus, string> = {
  PENDING: '待处理',
  RESOLVED: '已处理',
  CANCELLED: '已取消',
  EXPIRED: '已过期',
}

/**
 * Mirrors `AgentRunContinuityGapReason`. A gap means the run could not be resumed from its own
 * state, so the wording says what was lost rather than that something merely failed — the member's
 * next decision (replay, re-plan, take over) depends on which of the five it was.
 */
export const agentRunContinuityGapReasonLabels: Record<AgentRunContinuityGapReason, string> = {
  SNAPSHOT_MISSING: '状态快照缺失',
  SNAPSHOT_CORRUPT: '状态快照损坏',
  SNAPSHOT_IDENTITY_MISMATCH: '状态快照身份不匹配',
  REDIS_STATE_LOST: 'Redis 运行态已丢失',
  UNSAFE_CHECKPOINT: 'Checkpoint 不可安全恢复',
}

export const agentStateSnapshotStatusLabels: Record<AgentStateSnapshotStatus, string> = {
  CURRENT: '当前快照',
  SUPERSEDED: '已被替代',
  INVALID: '已失效',
}

export const executionLeasePhaseLabels: Record<ExecutionLeasePhase, string> = {
  PREPARE: '准备阶段',
  RUN: '执行阶段',
}

/**
 * The server derives this from `ExecutionLease.release()` presence rather than from a domain enum,
 * so the two values here are the complete set the API can emit.
 */
export const executionLeaseStatusLabels: Record<ExecutionLeaseStatus, string> = {
  ACTIVE: '持有中',
  RELEASED: '已释放',
}

export const runtimeFleetHealthLabels: Record<RuntimeFleetHealth, string> = {
  HEALTHY: '健康',
  DEGRADED: '降级',
  UNAVAILABLE: '不可用',
}

/** Mirrors `RuntimeWaitCause`: each value is an actionable diagnosis, so the wording names the fix. */
export const runtimeWaitCauseLabels: Record<RuntimeWaitCause, string> = {
  CAPABILITY_UNAVAILABLE: '无匹配能力的运行时',
  NO_ACTIVE_WORKER: '没有在线 Worker',
  HEARTBEAT_STALE: 'Worker 心跳过期',
  DRAINING: 'Worker 正在排空',
  CAPACITY_EXHAUSTED: '并发容量已满',
  REQUEUE_PENDING: '等待重新入队',
}

export const executionLeaseReleaseReasonLabels: Record<ExecutionLeaseReleaseReason, string> = {
  COMPLETED: '执行完成',
  FAILED: '执行失败',
  CANCELLED: '执行取消',
  PAUSED: '执行暂停',
  WAITING: '进入等待',
  EXPIRED: '租约过期',
  MANUAL_TAKEOVER: '人工接管',
  WORKER_SHUTDOWN: 'Worker 下线',
}
