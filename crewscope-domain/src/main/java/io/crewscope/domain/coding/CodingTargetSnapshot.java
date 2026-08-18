package io.crewscope.domain.coding;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.task.TaskStatus;
import io.crewscope.domain.workitem.WorkItemScope;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable, hash-closed repository target selected for one durable Coding Task. */
public final class CodingTargetSnapshot {

    private final CodingTargetSnapshotId id;
    private final WorkItemScope scope;
    private final TaskId taskId;
    private final TaskFactHash taskBriefHash;
    private final long revision;
    private final Optional<CodingTargetSnapshotId> parentSnapshotId;
    private final CodingTargetSnapshotChangeReason changeReason;
    private final RepositoryBindingId repositoryBindingId;
    private final long repositoryBindingVersion;
    private final RepositoryKind repositoryKind;
    private final RepositoryKey repositoryKey;
    private final RepositoryBranchName baselineRef;
    private final RepositoryCommitId baselineCommit;
    private final CodingTargetAllowedPaths allowedPaths;
    private final BuildProfileReference buildProfile;
    private final List<String> acceptanceCriteria;
    private final TaskFactHash snapshotHash;
    private final PrincipalId createdByPrincipalId;
    private final UtcTimestamp createdAt;

    private CodingTargetSnapshot(
            CodingTargetSnapshotId id,
            WorkItemScope scope,
            TaskId taskId,
            TaskFactHash taskBriefHash,
            long revision,
            Optional<CodingTargetSnapshotId> parentSnapshotId,
            CodingTargetSnapshotChangeReason changeReason,
            RepositoryBindingId repositoryBindingId,
            long repositoryBindingVersion,
            RepositoryKind repositoryKind,
            RepositoryKey repositoryKey,
            RepositoryBranchName baselineRef,
            RepositoryCommitId baselineCommit,
            CodingTargetAllowedPaths allowedPaths,
            BuildProfileReference buildProfile,
            List<String> acceptanceCriteria,
            Optional<TaskFactHash> expectedHash,
            PrincipalId createdByPrincipalId,
            UtcTimestamp createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.taskBriefHash = Objects.requireNonNull(taskBriefHash, "taskBriefHash");
        this.revision = requireRevision(revision);
        this.parentSnapshotId = requireParent(id, this.revision, parentSnapshotId);
        this.changeReason = requireChangeReason(this.revision, changeReason);
        this.repositoryBindingId = Objects.requireNonNull(repositoryBindingId, "repositoryBindingId");
        this.repositoryBindingVersion = requireBindingVersion(repositoryBindingVersion);
        this.repositoryKind = Objects.requireNonNull(repositoryKind, "repositoryKind");
        this.repositoryKey = Objects.requireNonNull(repositoryKey, "repositoryKey");
        this.baselineRef = Objects.requireNonNull(baselineRef, "baselineRef");
        this.baselineCommit = Objects.requireNonNull(baselineCommit, "baselineCommit");
        this.allowedPaths = Objects.requireNonNull(allowedPaths, "allowedPaths");
        this.buildProfile = Objects.requireNonNull(buildProfile, "buildProfile");
        this.acceptanceCriteria = requireAcceptanceCriteria(acceptanceCriteria);
        this.createdByPrincipalId = Objects.requireNonNull(
                createdByPrincipalId, "createdByPrincipalId");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        TaskFactHash calculatedHash = calculateHash();
        Objects.requireNonNull(expectedHash, "expectedHash").ifPresent(expected -> {
            if (!expected.equals(calculatedHash)) {
                throw new DomainValidationException(
                        "codingTargetSnapshot.snapshotHash",
                        "must match the canonical immutable target facts");
            }
        });
        this.snapshotHash = calculatedHash;
    }

    /** Captures revision one after binding authorization and baseline Preflight have succeeded. */
    public static CodingTargetSnapshot initial(
            CodingTargetSnapshotId id,
            Task task,
            RepositoryBinding repositoryBinding,
            RepositoryBranchName baselineRef,
            RepositoryCommitId baselineCommit,
            CodingTargetAllowedPaths allowedPaths,
            BuildProfileReference buildProfile,
            Principal actor,
            UtcTimestamp createdAt) {
        Task requiredTask = requireInitialTask(task);
        RepositoryBinding requiredBinding = requireBinding(requiredTask, repositoryBinding);
        PrincipalId actorId = CodingTargetActorPolicy.requireActiveInScope(
                actor, requiredTask.scope(), "codingTargetSnapshot.createdByPrincipalId");
        return new CodingTargetSnapshot(
                id,
                requiredTask.scope(),
                requiredTask.id(),
                requiredTask.brief().contentHash(),
                1,
                Optional.empty(),
                CodingTargetSnapshotChangeReason.TASK_CREATED,
                requiredBinding.id(),
                requiredBinding.version(),
                requiredBinding.kind(),
                requiredBinding.repositoryKey(),
                baselineRef,
                baselineCommit,
                allowedPaths,
                buildProfile,
                requiredTask.brief().acceptanceCriteria(),
                Optional.empty(),
                actorId,
                createdAt);
    }

    /**
     * Creates a new retry target revision while preventing the retry from expanding path access.
     */
    public static CodingTargetSnapshot supersedeForRetry(
            CodingTargetSnapshotId id,
            CodingTargetSnapshot parent,
            Task failedTask,
            RepositoryBinding repositoryBinding,
            RepositoryBranchName baselineRef,
            RepositoryCommitId baselineCommit,
            CodingTargetAllowedPaths allowedPaths,
            BuildProfileReference buildProfile,
            Principal actor,
            UtcTimestamp createdAt) {
        CodingTargetSnapshot requiredParent = Objects.requireNonNull(parent, "parent");
        Task requiredTask = requireFailedTask(requiredParent, failedTask);
        RepositoryBinding requiredBinding = requireBinding(requiredTask, repositoryBinding);
        CodingTargetAllowedPaths requiredPaths = Objects.requireNonNull(allowedPaths, "allowedPaths");
        if (!requiredParent.allowedPaths.containsAll(requiredPaths)) {
            throw new DomainValidationException(
                    "codingTargetSnapshot.allowedPaths",
                    "retry target paths must preserve or narrow the parent authorization");
        }
        PrincipalId actorId = CodingTargetActorPolicy.requireActiveInScope(
                actor, requiredTask.scope(), "codingTargetSnapshot.createdByPrincipalId");
        CodingTargetSnapshot replacement = new CodingTargetSnapshot(
                id,
                requiredTask.scope(),
                requiredTask.id(),
                requiredTask.brief().contentHash(),
                requiredParent.revision + 1,
                Optional.of(requiredParent.id),
                CodingTargetSnapshotChangeReason.RETRY_TARGET_UPDATED,
                requiredBinding.id(),
                requiredBinding.version(),
                requiredBinding.kind(),
                requiredBinding.repositoryKey(),
                baselineRef,
                baselineCommit,
                requiredPaths,
                buildProfile,
                requiredTask.brief().acceptanceCriteria(),
                Optional.empty(),
                actorId,
                createdAt);
        if (replacement.sameEffectiveTarget(requiredParent)) {
            throw new DomainValidationException(
                    "codingTargetSnapshot", "retry target revision must change an effective fact");
        }
        return replacement;
    }

    /** Reconstitutes persisted immutable facts and verifies the supplied canonical hash. */
    public static CodingTargetSnapshot reconstitute(
            CodingTargetSnapshotId id,
            WorkItemScope scope,
            TaskId taskId,
            TaskFactHash taskBriefHash,
            long revision,
            Optional<CodingTargetSnapshotId> parentSnapshotId,
            CodingTargetSnapshotChangeReason changeReason,
            RepositoryBindingId repositoryBindingId,
            long repositoryBindingVersion,
            RepositoryKind repositoryKind,
            RepositoryKey repositoryKey,
            RepositoryBranchName baselineRef,
            RepositoryCommitId baselineCommit,
            CodingTargetAllowedPaths allowedPaths,
            BuildProfileReference buildProfile,
            List<String> acceptanceCriteria,
            TaskFactHash snapshotHash,
            PrincipalId createdByPrincipalId,
            UtcTimestamp createdAt) {
        return new CodingTargetSnapshot(
                id,
                scope,
                taskId,
                taskBriefHash,
                revision,
                parentSnapshotId,
                changeReason,
                repositoryBindingId,
                repositoryBindingVersion,
                repositoryKind,
                repositoryKey,
                baselineRef,
                baselineCommit,
                allowedPaths,
                buildProfile,
                acceptanceCriteria,
                Optional.of(Objects.requireNonNull(snapshotHash, "snapshotHash")),
                createdByPrincipalId,
                createdAt);
    }

    /** Returns the exact same immutable revision for a failed Task retry. */
    public CodingTargetSnapshotReference reuseForRetry(Task failedTask) {
        requireFailedTask(this, failedTask);
        return reference();
    }

    public CodingTargetSnapshotReference reference() {
        return new CodingTargetSnapshotReference(id, revision, snapshotHash);
    }

    private boolean sameEffectiveTarget(CodingTargetSnapshot other) {
        return repositoryBindingId.equals(other.repositoryBindingId)
                && repositoryBindingVersion == other.repositoryBindingVersion
                && repositoryKind == other.repositoryKind
                && repositoryKey.equals(other.repositoryKey)
                && baselineRef.equals(other.baselineRef)
                && baselineCommit.equals(other.baselineCommit)
                && allowedPaths.equals(other.allowedPaths)
                && buildProfile.equals(other.buildProfile)
                && taskBriefHash.equals(other.taskBriefHash)
                && acceptanceCriteria.equals(other.acceptanceCriteria);
    }

    private TaskFactHash calculateHash() {
        MessageDigest digest = sha256();
        update(digest, id.toString());
        update(digest, scope.organizationId().toString());
        update(digest, scope.teamId().toString());
        update(digest, scope.workspaceId().toString());
        update(digest, scope.projectId().toString());
        update(digest, taskId.toString());
        update(digest, taskBriefHash.toString());
        update(digest, Long.toString(revision));
        update(digest, parentSnapshotId.map(Object::toString).orElse("-"));
        update(digest, changeReason.name());
        update(digest, repositoryBindingId.toString());
        update(digest, Long.toString(repositoryBindingVersion));
        update(digest, repositoryKind.name());
        update(digest, repositoryKey.value());
        update(digest, baselineRef.value());
        update(digest, baselineCommit.value());
        update(digest, Integer.toString(allowedPaths.values().size()));
        allowedPaths.values().forEach(value -> update(digest, value));
        update(digest, buildProfile.key());
        update(digest, Long.toString(buildProfile.version()));
        update(digest, buildProfile.profileHash().toString());
        update(digest, Integer.toString(acceptanceCriteria.size()));
        acceptanceCriteria.forEach(value -> update(digest, value));
        update(digest, createdByPrincipalId.toString());
        update(digest, createdAt.toString());
        return new TaskFactHash(HexFormat.of().formatHex(digest.digest()));
    }

    private static Task requireInitialTask(Task task) {
        Task required = Objects.requireNonNull(task, "task");
        if (required.status() != TaskStatus.CREATED || required.currentExecutionId().isPresent()) {
            throw new DomainValidationException(
                    "codingTargetSnapshot.taskId",
                    "initial target must be captured before Task execution starts");
        }
        return required;
    }

    private static Task requireFailedTask(CodingTargetSnapshot parent, Task task) {
        Task required = Objects.requireNonNull(task, "failedTask");
        if (required.status() != TaskStatus.FAILED
                || !required.scope().equals(parent.scope)
                || !required.id().equals(parent.taskId)
                || !required.brief().contentHash().equals(parent.taskBriefHash)) {
            throw new DomainValidationException(
                    "codingTargetSnapshot.taskId",
                    "retry target must belong to the same failed Task and immutable brief");
        }
        return required;
    }

    private static RepositoryBinding requireBinding(Task task, RepositoryBinding binding) {
        RepositoryBinding required = Objects.requireNonNull(binding, "repositoryBinding");
        boolean scopeMatches = required.scope().organizationId().equals(task.scope().organizationId())
                && required.scope().teamId().equals(task.scope().teamId())
                && required.scope().workspaceId().equals(task.scope().workspaceId())
                && required.scope().workProjectId().equals(task.scope().projectId());
        if (!scopeMatches || !required.acceptsNewTargets()) {
            throw new DomainValidationException(
                    "codingTargetSnapshot.repositoryBindingId",
                    "must reference an active RepositoryBinding in the complete Task scope");
        }
        return required;
    }

    private static List<String> requireAcceptanceCriteria(List<String> values) {
        List<String> required = List.copyOf(Objects.requireNonNull(values, "acceptanceCriteria"));
        if (required.isEmpty()) {
            throw new DomainValidationException(
                    "codingTargetSnapshot.acceptanceCriteria",
                    "must contain at least one Task acceptance criterion");
        }
        return required;
    }

    private static long requireRevision(long value) {
        if (value < 1) {
            throw new DomainValidationException(
                    "codingTargetSnapshot.revision", "must be positive");
        }
        return value;
    }

    private static long requireBindingVersion(long value) {
        if (value < 0) {
            throw new DomainValidationException(
                    "codingTargetSnapshot.repositoryBindingVersion", "must not be negative");
        }
        return value;
    }

    private static Optional<CodingTargetSnapshotId> requireParent(
            CodingTargetSnapshotId id,
            long revision,
            Optional<CodingTargetSnapshotId> parentSnapshotId) {
        Optional<CodingTargetSnapshotId> required = Objects.requireNonNull(
                parentSnapshotId, "parentSnapshotId");
        if ((revision == 1) == required.isPresent()) {
            throw new DomainValidationException(
                    "codingTargetSnapshot.parentSnapshotId",
                    revision == 1
                            ? "must be empty for revision one"
                            : "is required after revision one");
        }
        if (required.filter(id::equals).isPresent()) {
            throw new DomainValidationException(
                    "codingTargetSnapshot.parentSnapshotId", "must not reference itself");
        }
        return required;
    }

    private static CodingTargetSnapshotChangeReason requireChangeReason(
            long revision, CodingTargetSnapshotChangeReason reason) {
        CodingTargetSnapshotChangeReason required = Objects.requireNonNull(reason, "changeReason");
        if ((revision == 1) != (required == CodingTargetSnapshotChangeReason.TASK_CREATED)) {
            throw new DomainValidationException(
                    "codingTargetSnapshot.changeReason",
                    "must match initial or retry target revision");
        }
        return required;
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public CodingTargetSnapshotId id() {
        return id;
    }

    public WorkItemScope scope() {
        return scope;
    }

    public TaskId taskId() {
        return taskId;
    }

    public TaskFactHash taskBriefHash() {
        return taskBriefHash;
    }

    public long revision() {
        return revision;
    }

    public Optional<CodingTargetSnapshotId> parentSnapshotId() {
        return parentSnapshotId;
    }

    public CodingTargetSnapshotChangeReason changeReason() {
        return changeReason;
    }

    public RepositoryBindingId repositoryBindingId() {
        return repositoryBindingId;
    }

    public long repositoryBindingVersion() {
        return repositoryBindingVersion;
    }

    public RepositoryKind repositoryKind() {
        return repositoryKind;
    }

    public RepositoryKey repositoryKey() {
        return repositoryKey;
    }

    public RepositoryBranchName baselineRef() {
        return baselineRef;
    }

    public RepositoryCommitId baselineCommit() {
        return baselineCommit;
    }

    public CodingTargetAllowedPaths allowedPaths() {
        return allowedPaths;
    }

    public BuildProfileReference buildProfile() {
        return buildProfile;
    }

    public List<String> acceptanceCriteria() {
        return acceptanceCriteria;
    }

    public TaskFactHash snapshotHash() {
        return snapshotHash;
    }

    public PrincipalId createdByPrincipalId() {
        return createdByPrincipalId;
    }

    public UtcTimestamp createdAt() {
        return createdAt;
    }
}
