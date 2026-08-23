package io.crewscope.domain.review;

import io.crewscope.domain.coding.AcceptanceResult;
import io.crewscope.domain.coding.CommandEvidenceReference;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskFactHash;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable, minimal and hash-closed Reviewer authority context. */
public final class ContextPackage {

    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_DIFF_HUNKS = 128;
    public static final int MAX_PATCH_BYTES = 512 * 1024;
    public static final int MAX_COMMAND_EVIDENCE = 64;
    public static final int MAX_ACCEPTANCE_RESULTS = 100;

    private final ContextPackageId id;
    private final long version;
    private final Optional<ContextPackageId> parentPackageId;
    private final ReviewSubjectReference subject;
    private final io.crewscope.domain.workitem.WorkItemScope scope;
    private final io.crewscope.domain.task.TaskId taskId;
    private final io.crewscope.domain.task.TaskExecutionId taskExecutionId;
    private final int attempt;
    private final ReviewDiffReference diff;
    private final ReviewTestEvidenceReference testEvidence;
    private final List<ReviewDiffHunk> hunks;
    private final ReviewerExecutionReference reviewer;
    private final TaskFactHash contextHash;
    private final AuditMetadata audit;

    private ContextPackage(
            ContextPackageId id,
            long version,
            Optional<ContextPackageId> parentPackageId,
            ReviewSubject subject,
            ReviewDiffReference diff,
            ReviewTestEvidenceReference testEvidence,
            List<ReviewDiffHunk> hunks,
            ReviewerExecutionReference reviewer,
            Optional<TaskFactHash> expectedHash,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        if (version < 1) {
            throw new DomainValidationException("contextPackage.version", "must be positive");
        }
        this.version = version;
        this.parentPackageId = requireParent(id, version, parentPackageId);
        ReviewSubject requiredSubject = Objects.requireNonNull(subject, "subject");
        this.subject = requiredSubject.reference();
        this.scope = requiredSubject.scope();
        this.taskId = requiredSubject.taskId();
        this.taskExecutionId = requiredSubject.taskExecutionId();
        this.attempt = requiredSubject.attempt();
        this.diff = Objects.requireNonNull(diff, "diff");
        this.testEvidence = Objects.requireNonNull(testEvidence, "testEvidence");
        this.hunks = requireHunks(hunks, this.diff);
        this.reviewer = Objects.requireNonNull(reviewer, "reviewer");
        requireLineage(requiredSubject, this.diff, this.testEvidence, this.reviewer);
        this.audit = Objects.requireNonNull(audit, "audit");
        this.contextHash = calculateHash();
        Objects.requireNonNull(expectedHash, "expectedHash").ifPresent(expected -> {
            if (!expected.equals(this.contextHash)) {
                throw new DomainValidationException(
                        "contextPackage.contextHash", "must match every bounded authority fact");
            }
        });
    }

    public static ContextPackage initial(
            ContextPackageId id,
            ReviewSubject subject,
            ReviewDiffReference diff,
            ReviewTestEvidenceReference testEvidence,
            List<ReviewDiffHunk> hunks,
            ReviewerExecutionReference reviewer,
            Principal actor,
            UtcTimestamp createdAt) {
        ReviewSubject requiredSubject = Objects.requireNonNull(subject, "subject");
        PrincipalId actorId = ReviewActorPolicy.requireActiveInScope(
                actor, requiredSubject.scope(), "contextPackage.createdByPrincipalId");
        return new ContextPackage(
                id,
                1,
                Optional.empty(),
                requiredSubject,
                diff,
                testEvidence,
                hunks,
                reviewer,
                Optional.empty(),
                AuditMetadata.createdBy(actorId, createdAt));
    }

    /** Creates a direct successor after any immutable Review authority changes. */
    public static ContextPackage successor(
            ContextPackageId id,
            ContextPackage parent,
            ReviewSubject subject,
            ReviewDiffReference diff,
            ReviewTestEvidenceReference testEvidence,
            List<ReviewDiffHunk> hunks,
            ReviewerExecutionReference reviewer,
            Principal actor,
            UtcTimestamp createdAt) {
        ContextPackage requiredParent = Objects.requireNonNull(parent, "parent");
        ReviewSubject requiredSubject = Objects.requireNonNull(subject, "subject");
        if (!requiredParent.scope.equals(requiredSubject.scope())
                || !requiredParent.taskId.equals(requiredSubject.taskId())
                || !requiredParent.taskExecutionId.equals(requiredSubject.taskExecutionId())
                || requiredParent.attempt != requiredSubject.attempt()) {
            throw new DomainValidationException(
                    "contextPackage.parentPackageId", "must remain in the same Review lineage");
        }
        PrincipalId actorId = ReviewActorPolicy.requireActiveInScope(
                actor, requiredSubject.scope(), "contextPackage.createdByPrincipalId");
        ContextPackage next = new ContextPackage(
                id,
                Math.addExact(requiredParent.version, 1),
                Optional.of(requiredParent.id),
                requiredSubject,
                diff,
                testEvidence,
                hunks,
                reviewer,
                Optional.empty(),
                AuditMetadata.createdBy(actorId, createdAt));
        if (next.sameAuthority(requiredParent)) {
            throw new DomainValidationException(
                    "contextPackage", "a successor must change an authority coordinate or Hunk");
        }
        return next;
    }

    public static ContextPackage reconstitute(
            ContextPackageId id,
            long version,
            Optional<ContextPackageId> parentPackageId,
            ReviewSubject subject,
            ReviewDiffReference diff,
            ReviewTestEvidenceReference testEvidence,
            List<ReviewDiffHunk> hunks,
            ReviewerExecutionReference reviewer,
            TaskFactHash contextHash,
            AuditMetadata audit) {
        return new ContextPackage(
                id,
                version,
                parentPackageId,
                subject,
                diff,
                testEvidence,
                hunks,
                reviewer,
                Optional.of(Objects.requireNonNull(contextHash, "contextHash")),
                audit);
    }

    private static Optional<ContextPackageId> requireParent(
            ContextPackageId id, long version, Optional<ContextPackageId> parent) {
        Optional<ContextPackageId> required = Objects.requireNonNull(parent, "parentPackageId");
        if ((version == 1) == required.isPresent() || required.filter(id::equals).isPresent()) {
            throw new DomainValidationException(
                    "contextPackage.parentPackageId",
                    "must be absent for version one and identify another package afterwards");
        }
        return required;
    }

    private static List<ReviewDiffHunk> requireHunks(
            List<ReviewDiffHunk> values, ReviewDiffReference diff) {
        List<ReviewDiffHunk> required = List.copyOf(Objects.requireNonNull(values, "hunks"));
        if (required.isEmpty() || required.size() > MAX_DIFF_HUNKS) {
            throw new DomainValidationException(
                    "contextPackage.hunks", "must contain 1 to 128 changed Hunks");
        }
        Set<io.crewscope.domain.coding.DiffPath> changed = Set.copyOf(diff.changedPaths());
        Set<String> uniqueRanges = new HashSet<>();
        long patchBytes = 0;
        for (ReviewDiffHunk hunk : required) {
            ReviewDiffHunk value = Objects.requireNonNull(hunk, "reviewDiffHunk");
            if (!changed.contains(value.path())
                    || !uniqueRanges.add(value.path() + ":" + value.startLine() + ":" + value.endLine())) {
                throw new DomainValidationException(
                        "contextPackage.hunks",
                        "must use unique ranges from the exact Diff Manifest");
            }
            patchBytes = Math.addExact(patchBytes, value.patchBytes());
        }
        if (patchBytes > MAX_PATCH_BYTES) {
            throw new DomainValidationException(
                    "contextPackage.hunks", "Patch content exceeds 512 KiB");
        }
        return required.stream()
                .sorted(Comparator.comparing(ReviewDiffHunk::path)
                        .thenComparingInt(ReviewDiffHunk::startLine)
                        .thenComparingInt(ReviewDiffHunk::endLine))
                .toList();
    }

    private static void requireLineage(
            ReviewSubject subject,
            ReviewDiffReference diff,
            ReviewTestEvidenceReference test,
            ReviewerExecutionReference reviewer) {
        boolean mismatch = !subject.diff().equals(diff)
                || !subject.scope().equals(test.scope())
                || !subject.taskId().equals(test.taskId())
                || !subject.taskExecutionId().equals(test.taskExecutionId())
                || subject.attempt() != test.attempt()
                || !diff.codingTarget().equals(test.codingTarget())
                || !diff.generation().equals(test.diffGeneration())
                || !diff.manifestHash().equals(test.diffManifestHash())
                || !subject.scope().equals(reviewer.scope())
                || !subject.taskId().equals(reviewer.taskId())
                || !subject.taskExecutionId().equals(reviewer.taskExecutionId());
        if (mismatch) {
            throw new DomainValidationException(
                    "contextPackage", "all facts must share exact Scope, Task, attempt and Diff lineage");
        }
    }

    private TaskFactHash calculateHash() {
        StringBuilder canonical = new StringBuilder("review-context-package-v1");
        ReviewSubject.append(canonical, Integer.toString(SCHEMA_VERSION));
        ReviewSubject.append(canonical, scope.organizationId().toString());
        ReviewSubject.append(canonical, scope.teamId().toString());
        ReviewSubject.append(canonical, scope.workspaceId().toString());
        ReviewSubject.append(canonical, scope.projectId().toString());
        ReviewSubject.append(canonical, taskId.toString());
        ReviewSubject.append(canonical, taskExecutionId.toString());
        ReviewSubject.append(canonical, Integer.toString(attempt));
        ReviewSubject.append(canonical, subject.id().toString());
        ReviewSubject.append(canonical, subject.type().name());
        ReviewSubject.append(canonical, subject.subjectHash().toString());
        appendDiff(canonical);
        for (ReviewDiffHunk hunk : hunks) {
            ReviewSubject.append(canonical, hunk.path().value());
            ReviewSubject.append(canonical, Integer.toString(hunk.startLine()));
            ReviewSubject.append(canonical, Integer.toString(hunk.endLine()));
            ReviewSubject.append(canonical, hunk.patchHash().toString());
        }
        appendTestEvidence(canonical);
        appendReviewer(canonical);
        return TaskFactHash.sha256(canonical.toString());
    }

    private void appendDiff(StringBuilder canonical) {
        ReviewSubject.append(canonical, diff.codingTarget().snapshotId().toString());
        ReviewSubject.append(canonical, Long.toString(diff.codingTarget().revision()));
        ReviewSubject.append(canonical, diff.codingTarget().snapshotHash().toString());
        ReviewSubject.append(canonical, diff.artifact().id().toString());
        ReviewSubject.append(canonical, diff.artifact().finalHash().toString());
        ReviewSubject.append(canonical, diff.baselineCommit().value());
        ReviewSubject.append(canonical, diff.deliveryCommit().value());
        ReviewSubject.append(canonical, diff.generation().toString());
        ReviewSubject.append(canonical, diff.manifestHash().toString());
        ReviewSubject.append(canonical, diff.patchArtifact().artifactId().toString());
        ReviewSubject.append(canonical, Long.toString(diff.patchArtifact().sizeBytes()));
        ReviewSubject.append(canonical, diff.patchArtifact().patchSha256().toString());
    }

    private void appendTestEvidence(StringBuilder canonical) {
        ReviewSubject.append(canonical, testEvidence.id().toString());
        ReviewSubject.append(canonical, testEvidence.evidenceHash().toString());
        ReviewSubject.append(canonical, testEvidence.diffGeneration().toString());
        ReviewSubject.append(canonical, testEvidence.diffManifestHash().toString());
        for (ReviewCommandEvidenceReference command : testEvidence.commands()) {
            ReviewSubject.append(canonical, command.evidence().id().toString());
            ReviewSubject.append(canonical, command.evidence().sequence().toString());
            ReviewSubject.append(canonical, command.evidence().evidenceHash().toString());
            ReviewSubject.append(canonical, command.commandKind().name());
            ReviewSubject.append(canonical, command.termination().name());
            ReviewSubject.append(
                    canonical, command.exitCode().map(Object::toString).orElse("none"));
            ReviewSubject.append(canonical, command.summary().value());
        }
        for (AcceptanceResult acceptance : testEvidence.acceptanceResults()) {
            ReviewSubject.append(canonical, Integer.toString(acceptance.criterionIndex()));
            ReviewSubject.append(canonical, acceptance.criterion());
            ReviewSubject.append(canonical, acceptance.status().name());
            ReviewSubject.append(canonical, acceptance.summary().value());
            for (CommandEvidenceReference evidence : acceptance.evidence()) {
                ReviewSubject.append(canonical, evidence.id().toString());
                ReviewSubject.append(canonical, evidence.evidenceHash().toString());
            }
        }
    }

    private void appendReviewer(StringBuilder canonical) {
        ReviewSubject.append(canonical, reviewer.agentProfileId().toString());
        ReviewSubject.append(canonical, Long.toString(reviewer.agentProfileVersion()));
        ReviewSubject.append(canonical, reviewer.agentPrincipalId().toString());
        ReviewSubject.append(canonical, reviewer.relationship().name());
        ReviewSubject.append(canonical, reviewer.templateVersion().toString());
        ReviewSubject.append(canonical, reviewer.templateHash().toString());
        ReviewSubject.append(canonical, reviewer.configurationRevision().toString());
        ReviewSubject.append(canonical, reviewer.configurationHash().toString());
        ReviewSubject.append(canonical, reviewer.policySnapshotId().toString());
        ReviewSubject.append(canonical, Long.toString(reviewer.policySnapshotRevision()));
        ReviewSubject.append(canonical, reviewer.policySnapshotHash().toString());
    }

    private boolean sameAuthority(ContextPackage other) {
        return subject.equals(other.subject)
                && diff.equals(other.diff)
                && testEvidence.equals(other.testEvidence)
                && hunks.equals(other.hunks)
                && reviewer.equals(other.reviewer);
    }

    public ContextPackageReference reference() {
        return new ContextPackageReference(id, version, contextHash);
    }

    public ContextPackageId id() { return id; }
    public long version() { return version; }
    public Optional<ContextPackageId> parentPackageId() { return parentPackageId; }
    public ReviewSubjectReference subject() { return subject; }
    public io.crewscope.domain.workitem.WorkItemScope scope() { return scope; }
    public io.crewscope.domain.task.TaskId taskId() { return taskId; }
    public io.crewscope.domain.task.TaskExecutionId taskExecutionId() { return taskExecutionId; }
    public int attempt() { return attempt; }
    public ReviewDiffReference diff() { return diff; }
    public ReviewTestEvidenceReference testEvidence() { return testEvidence; }
    public List<ReviewDiffHunk> hunks() { return hunks; }
    public ReviewerExecutionReference reviewer() { return reviewer; }
    public TaskFactHash contextHash() { return contextHash; }
    public AuditMetadata audit() { return audit; }
}
