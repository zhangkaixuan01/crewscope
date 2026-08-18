package io.crewscope.domain.coding;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.Objects;
import java.util.Optional;

/** Terminal immutable Diff facts recomputed from exact baseline and delivery commits. */
public final class DiffArtifact {

    private final DiffArtifactId id;
    private final WorkItemScope scope;
    private final TaskId taskId;
    private final TaskExecutionId taskExecutionId;
    private final int attempt;
    private final ExecutionWorkspaceId executionWorkspaceId;
    private final CodingTargetSnapshotReference codingTarget;
    private final RepositoryCommitId baselineCommit;
    private final RepositoryCommitId deliveryCommit;
    private final DiffManifest manifest;
    private final PatchArtifactReference patchArtifact;
    private final TaskFactHash finalHash;
    private final AuditMetadata audit;

    private DiffArtifact(
            DiffArtifactId id,
            WorkItemScope scope,
            TaskId taskId,
            TaskExecutionId taskExecutionId,
            int attempt,
            ExecutionWorkspaceId executionWorkspaceId,
            CodingTargetSnapshotReference codingTarget,
            RepositoryCommitId baselineCommit,
            RepositoryCommitId deliveryCommit,
            DiffManifest manifest,
            PatchArtifactReference patchArtifact,
            Optional<TaskFactHash> expectedFinalHash,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        if (attempt < 1) {
            throw new DomainValidationException("diffArtifact.attempt", "must be positive");
        }
        this.attempt = attempt;
        this.executionWorkspaceId = Objects.requireNonNull(
                executionWorkspaceId, "executionWorkspaceId");
        this.codingTarget = Objects.requireNonNull(codingTarget, "codingTarget");
        this.baselineCommit = Objects.requireNonNull(baselineCommit, "baselineCommit");
        this.deliveryCommit = Objects.requireNonNull(deliveryCommit, "deliveryCommit");
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.patchArtifact = requirePatchShape(this.manifest, patchArtifact);
        this.audit = Objects.requireNonNull(audit, "audit");
        this.finalHash = calculateFinalHash();
        Objects.requireNonNull(expectedFinalHash, "expectedFinalHash").ifPresent(expected -> {
            if (!expected.equals(this.finalHash)) {
                throw new DomainValidationException(
                        "diffArtifact.finalHash", "must match the immutable final Diff facts");
            }
        });
    }

    /** Publishes one final artifact only after the Workspace enters FINALIZING. */
    public static DiffArtifact publishFinal(
            DiffArtifactId id,
            ExecutionWorkspace workspace,
            CodingTargetSnapshot codingTarget,
            RepositoryCommitId deliveryCommit,
            DiffManifest manifest,
            PatchArtifactReference patchArtifact,
            Principal actor,
            UtcTimestamp finalizedAt) {
        ExecutionWorkspace requiredWorkspace = Objects.requireNonNull(workspace, "workspace");
        if (requiredWorkspace.status() != ExecutionWorkspaceStatus.FINALIZING) {
            throw new DomainValidationException(
                    "diffArtifact.executionWorkspaceId", "Workspace must be FINALIZING");
        }
        CodingTargetSnapshot target = requireTarget(requiredWorkspace, codingTarget);
        DiffManifest requiredManifest = Objects.requireNonNull(manifest, "manifest");
        requireAllowedPaths(target, requiredManifest);
        PrincipalId actorId = CodingTargetActorPolicy.requireActiveInScope(
                actor, requiredWorkspace.scope(), "diffArtifact.createdByPrincipalId");
        return new DiffArtifact(
                id,
                requiredWorkspace.scope(),
                requiredWorkspace.taskId(),
                requiredWorkspace.taskExecutionId(),
                requiredWorkspace.attempt(),
                requiredWorkspace.id(),
                requiredWorkspace.codingTarget(),
                requiredWorkspace.baselineCommit(),
                deliveryCommit,
                requiredManifest,
                patchArtifact,
                Optional.empty(),
                AuditMetadata.createdBy(actorId, finalizedAt));
    }

    /** Reconstitutes terminal facts and rejects any final Hash mismatch. */
    public static DiffArtifact reconstitute(
            DiffArtifactId id,
            WorkItemScope scope,
            TaskId taskId,
            TaskExecutionId taskExecutionId,
            int attempt,
            ExecutionWorkspaceId executionWorkspaceId,
            CodingTargetSnapshotReference codingTarget,
            RepositoryCommitId baselineCommit,
            RepositoryCommitId deliveryCommit,
            DiffManifest manifest,
            PatchArtifactReference patchArtifact,
            TaskFactHash finalHash,
            AuditMetadata audit) {
        return new DiffArtifact(
                id,
                scope,
                taskId,
                taskExecutionId,
                attempt,
                executionWorkspaceId,
                codingTarget,
                baselineCommit,
                deliveryCommit,
                manifest,
                patchArtifact,
                Optional.of(Objects.requireNonNull(finalHash, "finalHash")),
                audit);
    }

    private static CodingTargetSnapshot requireTarget(
            ExecutionWorkspace workspace, CodingTargetSnapshot codingTarget) {
        CodingTargetSnapshot target = Objects.requireNonNull(codingTarget, "codingTarget");
        if (!workspace.scope().equals(target.scope())
                || !workspace.taskId().equals(target.taskId())
                || !workspace.codingTarget().equals(target.reference())
                || !workspace.baselineCommit().equals(target.baselineCommit())) {
            throw new DomainValidationException(
                    "diffArtifact.codingTargetSnapshotId",
                    "must match the Workspace target, scope and baseline commit");
        }
        return target;
    }

    private static void requireAllowedPaths(
            CodingTargetSnapshot target, DiffManifest manifest) {
        for (DiffFileEntry file : manifest.files()) {
            if (!file.path().isWithin(target.allowedPaths())
                    || file.oldPath().filter(path -> !path.isWithin(target.allowedPaths())).isPresent()) {
                throw new DomainValidationException(
                        "diffArtifact.manifest.files", "all current and old paths must be authorized");
            }
        }
    }

    private static PatchArtifactReference requirePatchShape(
            DiffManifest manifest, PatchArtifactReference patchArtifact) {
        PatchArtifactReference required = Objects.requireNonNull(patchArtifact, "patchArtifact");
        if ((manifest.fileCount() == 0) != (required.sizeBytes() == 0)) {
            throw new DomainValidationException(
                    "diffArtifact.patchArtifact",
                    "Patch emptiness must match the final Manifest");
        }
        return required;
    }

    private TaskFactHash calculateFinalHash() {
        StringBuilder canonical = new StringBuilder("final-diff-artifact-v1");
        append(canonical, executionWorkspaceId.toString());
        append(canonical, baselineCommit.value());
        append(canonical, deliveryCommit.value());
        append(canonical, manifest.generation().toString());
        append(canonical, manifest.contentHash().toString());
        append(canonical, patchArtifact.patchSha256().toString());
        return TaskFactHash.sha256(canonical.toString());
    }

    private static void append(StringBuilder target, String value) {
        target.append('|').append(value.length()).append(':').append(value);
    }

    public DiffArtifactReference reference() { return new DiffArtifactReference(id, finalHash); }

    public DiffArtifactId id() { return id; }

    public WorkItemScope scope() { return scope; }

    public TaskId taskId() { return taskId; }

    public TaskExecutionId taskExecutionId() { return taskExecutionId; }

    public int attempt() { return attempt; }

    public ExecutionWorkspaceId executionWorkspaceId() { return executionWorkspaceId; }

    public CodingTargetSnapshotReference codingTarget() { return codingTarget; }

    public RepositoryCommitId baselineCommit() { return baselineCommit; }

    public RepositoryCommitId deliveryCommit() { return deliveryCommit; }

    public DiffManifest manifest() { return manifest; }

    public PatchArtifactReference patchArtifact() { return patchArtifact; }

    public TaskFactHash finalHash() { return finalHash; }

    public AuditMetadata audit() { return audit; }
}
