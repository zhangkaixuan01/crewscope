package io.crewscope.application.coding.query;

import io.crewscope.domain.task.TaskExecutionId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Public-safe durable Coding facts for one TaskExecution attempt. */
public record CodingAttemptProjection(
        TaskExecutionId executionId,
        int attempt,
        WorkspaceSummary workspace,
        Optional<SandboxSummary> sandbox,
        Optional<DiffManifestSummary> diffManifest,
        Optional<CodingResultSummary> codingResult,
        long commandEvidenceCount,
        long testEvidenceCount) {

    public CodingAttemptProjection {
        executionId = Objects.requireNonNull(executionId, "executionId");
        workspace = Objects.requireNonNull(workspace, "workspace");
        sandbox = Objects.requireNonNull(sandbox, "sandbox");
        diffManifest = Objects.requireNonNull(diffManifest, "diffManifest");
        codingResult = Objects.requireNonNull(codingResult, "codingResult");
    }

    public record WorkspaceSummary(
            UUID id,
            String repositoryKey,
            String baselineCommit,
            String managedBranch,
            String status,
            long recoveryGeneration,
            String completionReason,
            String failureCode,
            String fingerprint,
            long version,
            Instant retainUntil,
            Instant createdAt,
            Instant updatedAt) {}

    public record SandboxSummary(
            String networkMode,
            int cpuCount,
            int memoryMiB,
            int pids,
            int maxCommandDurationSeconds,
            long maxCommandOutputBytes,
            boolean readOnlyRootFilesystem,
            int maxCommandCalls,
            int maxChangedFiles,
            long maxSingleFileBytes,
            int maxWriteOperations,
            long maxWrittenBytes,
            long maxDiffBytes,
            int maxTestRepairRounds,
            String buildProfileKey,
            long buildProfileVersion) {}

    public record DiffManifestSummary(
            UUID artifactId,
            long generation,
            String manifestHash,
            int fileCount,
            long additions,
            long deletions,
            String baselineCommit,
            String deliveryCommit,
            String finalHash,
            ArtifactSummary patch,
            List<DiffFileSummary> files,
            Instant createdAt) {

        public DiffManifestSummary {
            files = List.copyOf(Objects.requireNonNull(files, "files"));
        }
    }

    public record DiffFileSummary(
            int ordinal,
            String path,
            String oldPath,
            String changeKind,
            long additions,
            long deletions,
            boolean binary,
            boolean patchTruncated,
            String patchHash) {}

    /** Result coordinates are synthesized only when a successful TestEvidence matches final Diff. */
    public record CodingResultSummary(
            String schemaVersion,
            UUID executionWorkspaceId,
            String workspaceFingerprint,
            UUID codingTargetSnapshotId,
            long codingTargetRevision,
            String codingTargetHash,
            UUID diffArtifactId,
            String diffArtifactHash,
            UUID testEvidenceId,
            String testEvidenceHash,
            Instant completedAt) {}

    public record ArtifactSummary(
            UUID artifactId, String kind, String contentType, long sizeBytes, String contentHash) {}
}
