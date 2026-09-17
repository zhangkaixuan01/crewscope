import type {
  AcceptanceStatus,
  ExecutionWorkspaceCompletionReason,
  SandboxNetworkMode,
  RepositoryBindingStatus,
  RepositoryKind,
  CodingTodoStatus,
  CommandKind,
  CommandTermination,
  DiffFileKind,
  EvidenceFailureClassification,
  ExecutionWorkspaceStatus,
} from './types'

/**
 * User-facing text for the Coding execution and evidence enums.
 *
 * Evidence is read as proof, so every one of these values has to say what actually happened rather
 * than echo a Java constant: `COMMAND_OUTPUT_LIMIT_EXCEEDED` and `TESTS_FAILED` are two very
 * different verdicts about the same attempt. The maps are typed `Record<Enum, string>` so a new
 * backend classification fails typechecking instead of surfacing raw.
 */
/** A binding is the only way CrewScope reaches a repository, so its two states are read as capability. */
export const repositoryBindingStatusLabels: Record<RepositoryBindingStatus, string> = {
  ACTIVE: '已启用',
  DISABLED: '已停用',
}

/** `RepositoryKind` — today the platform only manages its own local mirror. */
export const repositoryKindLabels: Record<RepositoryKind, string> = {
  LOCAL_MANAGED: '平台托管镜像',
}

export const executionWorkspaceStatusLabels: Record<ExecutionWorkspaceStatus, string> = {
  PENDING: '待创建',
  PROVISIONING: '创建中',
  READY: '已就绪',
  ACTIVE: '使用中',
  FINALIZING: '收尾中',
  COMPLETED: '已完成',
  RECOVERING: '恢复中',
  FAILED: '已失败',
  ARCHIVED: '已归档',
}

/** Sandbox network reach, worded as the capability it grants rather than the flag it sets. */
export const sandboxNetworkModeLabels: Record<SandboxNetworkMode, string> = {
  NONE: '完全禁网',
  LOOPBACK_ONLY: '仅本机回环',
  RESTRICTED_EGRESS: '受限出网',
}

/** How a Workspace reached its completed state. */
export const executionWorkspaceCompletionReasonLabels: Record<ExecutionWorkspaceCompletionReason, string> = {
  SUCCEEDED: '正常完成',
  CANCELLED: '已被取消',
}

export const codingTodoStatusLabels: Record<CodingTodoStatus, string> = {
  PENDING: '待开始',
  IN_PROGRESS: '进行中',
  COMPLETED: '已完成',
  BLOCKED: '已阻塞',
}

export const commandKindLabels: Record<CommandKind, string> = {
  COMPILE: '编译',
  TEST: '测试',
  VERIFY: '校验',
  FORMAT_CHECK: '格式检查',
  ACCEPTANCE: '验收',
}

/** How a sandboxed command ended. `EXITED` still needs the exit code to be read alongside it. */
export const commandTerminationLabels: Record<CommandTermination, string> = {
  EXITED: '正常退出',
  TIMED_OUT: '执行超时',
  START_FAILED: '启动失败',
  OUTPUT_LIMIT_EXCEEDED: '输出超过上限',
  SANDBOX_POLICY_VIOLATION: '违反沙箱策略',
  CANCELLED: '已取消',
}

/** Mirrors `EvidenceFailureClassification`, shared by command evidence and test evidence. */
export const evidenceFailureClassificationLabels: Record<EvidenceFailureClassification, string> = {
  COMMAND_START_FAILED: '命令未能启动',
  COMMAND_TIMED_OUT: '命令执行超时',
  COMMAND_OUTPUT_LIMIT_EXCEEDED: '命令输出超过上限',
  COMMAND_SANDBOX_POLICY_VIOLATION: '命令违反沙箱策略',
  COMMAND_CANCELLED: '命令已取消',
  COMMAND_NON_ZERO_EXIT: '命令以非零码退出',
  TEST_REPORT_MISSING: '缺少测试报告',
  NO_TESTS_EXECUTED: '没有执行任何测试',
  TESTS_FAILED: '存在失败的测试',
  ACCEPTANCE_INCOMPLETE: '验收条目未覆盖完整',
  ACCEPTANCE_FAILED: '验收未通过',
}

export const acceptanceStatusLabels: Record<AcceptanceStatus, string> = {
  PASSED: '已通过',
  FAILED: '未通过',
  NOT_EVALUATED: '未评估',
}

/**
 * Single-letter badge for the diff file list, where a full word does not fit. It is always paired
 * with {@link diffFileKindLabels} as the accessible name, so the letter never has to stand alone.
 */
export const diffFileKindAbbreviations: Record<DiffFileKind, string> = {
  ADDED: 'A',
  MODIFIED: 'M',
  DELETED: 'D',
  RENAMED: 'R',
  COPIED: 'C',
  TYPE_CHANGED: 'T',
}

export const diffFileKindLabels: Record<DiffFileKind, string> = {
  ADDED: '新增',
  MODIFIED: '修改',
  DELETED: '删除',
  RENAMED: '重命名',
  COPIED: '复制',
  TYPE_CHANGED: '类型变更',
}
