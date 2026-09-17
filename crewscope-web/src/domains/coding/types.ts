import type { CommandReceipt } from '../scope/types'

/** Complete WorkProject boundary for Repository and CodingTarget resources. */
export interface CodingScope {
  organizationId: string
  teamId: string
  projectId: string
}

/**
 * `RepositoryKind` and `RepositoryBindingStatus`. `RepositoryBinding` keeps both fields typed
 * `string` because the binding projection is a pass-through, but the unions type the label maps so
 * a new repository kind cannot silently reach the settings page as a raw constant.
 */
export const repositoryKinds = ['LOCAL_MANAGED'] as const
export type RepositoryKind = typeof repositoryKinds[number]
export const repositoryBindingStatuses = ['ACTIVE', 'DISABLED'] as const
export type RepositoryBindingStatus = typeof repositoryBindingStatuses[number]

export interface RepositoryBinding {
  id: string
  organizationId: string
  teamId: string
  workspaceId: string
  projectId: string
  kind: string
  repositoryKey: string
  defaultBranch: string
  status: string
  version: number
  createdAt: string
  createdByPrincipalId: string | null
  updatedAt: string
  updatedByPrincipalId: string | null
}

export interface RepositoryBindingInput {
  repositoryKey: string
  defaultBranch: string
}

/** Path-free repository option returned by the trusted server catalog. */
export interface RepositoryCatalogItem {
  repositoryKey: string
  availability: 'AVAILABLE' | 'UNAVAILABLE'
  suggestedDefaultBranch: string | null
}

export interface RepositoryPreflight {
  ready: boolean
  repositoryKey: string
  baselineRef: string
  baselineCommit: string
}

export interface BuildProfileSummary {
  key: string
  version: number
  profileHash: string
  buildTool: string
  javaRelease: number
  commandKinds: string[]
}

/** Exact immutable BuildProfile reference submitted with a CodingTarget. */
export interface BuildProfileSelection {
  key: string
  version: number
  profileHash: string
}

export interface CodingTargetSelection {
  repositoryBindingId: string
  baselineRef: string
  allowedPaths: string[]
  buildProfile: BuildProfileSelection
}

export interface ArtifactSummary {
  artifactId: string
  kind: string
  contentType: string
  sizeBytes: number
  contentHash: string
}

export interface CodingWorkspaceSummary {
  id: string
  repositoryKey: string
  baselineCommit: string
  managedBranch: string
  status: string
  recoveryGeneration: number
  completionReason: string | null
  failureCode: string | null
  fingerprint: string
  version: number
  retainUntil: string
  createdAt: string
  updatedAt: string
}

/**
 * `SandboxNetworkMode`. The sandbox's network reach is the single most load-bearing safety fact on
 * the execution surface, so it is never shown as a constant: a member has to be able to tell
 * "no network at all" from "restricted egress" at a glance.
 */
export const sandboxNetworkModes = ['NONE', 'LOOPBACK_ONLY', 'RESTRICTED_EGRESS'] as const
export type SandboxNetworkMode = typeof sandboxNetworkModes[number]

/** `ExecutionWorkspaceCompletionReason` — a workspace that completed either finished or was stopped. */
export const executionWorkspaceCompletionReasons = ['SUCCEEDED', 'CANCELLED'] as const
export type ExecutionWorkspaceCompletionReason = typeof executionWorkspaceCompletionReasons[number]

export interface CodingSandboxSummary {
  networkMode: string
  cpuCount: number
  memoryMiB: number
  pids: number
  maxCommandDurationSeconds: number
  maxCommandOutputBytes: number
  readOnlyRootFilesystem: boolean
  maxCommandCalls: number
  maxChangedFiles: number
  maxSingleFileBytes: number
  maxWriteOperations: number
  maxWrittenBytes: number
  maxDiffBytes: number
  maxTestRepairRounds: number
  buildProfileKey: string
  buildProfileVersion: number
}

export interface DiffFileSummary {
  ordinal: number
  path: string
  oldPath: string | null
  changeKind: string
  additions: number
  deletions: number
  binary: boolean
  patchTruncated: boolean
  patchHash: string
}

export interface DiffManifestSummary {
  artifactId: string
  generation: number
  manifestHash: string
  fileCount: number
  additions: number
  deletions: number
  baselineCommit: string
  deliveryCommit: string | null
  finalHash: string
  patch: ArtifactSummary
  files: DiffFileSummary[]
  createdAt: string
}

/** One bounded byte page from a purpose-bound Coding Artifact API. */
export interface ArtifactBytePage {
  bytes: Uint8Array
  offset: number
  length: number
  totalSize: number
  etag: string | null
  contentType: string
  filename: string | null
}

export type PatchBytePage = ArtifactBytePage

/** Complete UTF-8 Patch retained only after every bounded page has been verified. */
export interface CodingPatchDocument {
  content: string
  sizeBytes: number
  etag: string | null
}

/** Incrementally assembled text Artifact. Raw bytes preserve UTF-8 boundaries between pages. */
export interface ArtifactTextDocument {
  bytes: Uint8Array
  content: string
  loadedBytes: number
  totalSize: number
  complete: boolean
  etag: string | null
  contentType: string
  filename: string | null
}

export interface CodingResultSummary {
  schemaVersion: string
  executionWorkspaceId: string
  workspaceFingerprint: string
  codingTargetSnapshotId: string
  codingTargetRevision: number
  codingTargetHash: string
  diffArtifactId: string
  diffArtifactHash: string
  testEvidenceId: string
  testEvidenceHash: string
  completedAt: string
}

export interface CodingAttemptDetails {
  executionId: string
  attempt: number
  workspace: CodingWorkspaceSummary
  sandbox: CodingSandboxSummary | null
  diffManifest: DiffManifestSummary | null
  codingResult: CodingResultSummary | null
  commandEvidenceCount: number
  testEvidenceCount: number
}

export interface CodingAttemptSummary {
  executionId: string
  attempt: number
  executionStatus: string
  current: boolean
  coding: boolean
  details: CodingAttemptDetails | null
}

export interface CurrentCodingAttempt {
  taskId: string
  currentAttempt: CodingAttemptSummary | null
}

/**
 * Coding execution and evidence enums exactly as the server serialises them.
 *
 * The evidence interfaces keep these fields typed as `string` because the Coding gateway is a
 * pass-through adapter; the unions exist so `coding/labels.ts` can be typed against them.
 */
export const executionWorkspaceStatuses = [
  'PENDING', 'PROVISIONING', 'READY', 'ACTIVE', 'FINALIZING', 'COMPLETED', 'RECOVERING', 'FAILED',
  'ARCHIVED',
] as const
export const codingTodoStatuses = ['PENDING', 'IN_PROGRESS', 'COMPLETED', 'BLOCKED'] as const
export const commandKinds = ['COMPILE', 'TEST', 'VERIFY', 'FORMAT_CHECK', 'ACCEPTANCE'] as const
export const commandTerminations = [
  'EXITED', 'TIMED_OUT', 'START_FAILED', 'OUTPUT_LIMIT_EXCEEDED', 'SANDBOX_POLICY_VIOLATION',
  'CANCELLED',
] as const
export const evidenceFailureClassifications = [
  'COMMAND_START_FAILED', 'COMMAND_TIMED_OUT', 'COMMAND_OUTPUT_LIMIT_EXCEEDED',
  'COMMAND_SANDBOX_POLICY_VIOLATION', 'COMMAND_CANCELLED', 'COMMAND_NON_ZERO_EXIT',
  'TEST_REPORT_MISSING', 'NO_TESTS_EXECUTED', 'TESTS_FAILED', 'ACCEPTANCE_INCOMPLETE',
  'ACCEPTANCE_FAILED',
] as const
export const acceptanceStatuses = ['PASSED', 'FAILED', 'NOT_EVALUATED'] as const
export const diffFileKinds = ['ADDED', 'MODIFIED', 'DELETED', 'RENAMED', 'COPIED', 'TYPE_CHANGED'] as const

export type ExecutionWorkspaceStatus = typeof executionWorkspaceStatuses[number]
export type CodingTodoStatus = typeof codingTodoStatuses[number]
export type CommandKind = typeof commandKinds[number]
export type CommandTermination = typeof commandTerminations[number]
export type EvidenceFailureClassification = typeof evidenceFailureClassifications[number]
export type AcceptanceStatus = typeof acceptanceStatuses[number]
export type DiffFileKind = typeof diffFileKinds[number]

export interface CommandEvidenceSummary {
  id: string
  sequence: number
  commandKind: string
  toolKey: string
  timeoutSeconds: number
  startedAt: string
  finishedAt: string
  termination: string
  exitCode: number | null
  summary: string
  failureClassification: string | null
  evidenceHash: string
  commandLog: ArtifactSummary
}

export interface AcceptanceEvidenceSummary {
  criterionIndex: number
  criterion: string
  status: string
  summary: string
  commandEvidenceIds: string[]
}

export interface TestEvidenceSummary {
  id: string
  sequence: number
  diffGeneration: number
  diffManifestHash: string
  total: number
  passed: number
  failed: number
  errors: number
  skipped: number
  summary: string
  failureClassification: string | null
  evidenceHash: string
  commandEvidenceIds: string[]
  acceptance: AcceptanceEvidenceSummary[]
  testReport: ArtifactSummary | null
  createdAt: string
}

export interface EvidencePage<T> {
  items: T[]
  nextCursor: string | null
}

export type RepositoryCommandReceipt = CommandReceipt
