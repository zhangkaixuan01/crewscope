import type { CommandReceipt } from '../scope/types'

/** Complete WorkProject boundary for Repository and CodingTarget resources. */
export interface CodingScope {
  organizationId: string
  teamId: string
  projectId: string
}

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
