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

/** Immutable platform-observed result of one authorized command process. */
public final class CommandEvidence {

    private final CommandEvidenceId id;
    private final WorkItemScope scope;
    private final TaskId taskId;
    private final TaskExecutionId taskExecutionId;
    private final int attempt;
    private final ExecutionWorkspaceId executionWorkspaceId;
    private final ExecutionWorkspaceFingerprint workspaceFingerprint;
    private final CodingTargetSnapshotReference codingTarget;
    private final EvidenceSequence sequence;
    private final WorkspacePolicyReference workspacePolicy;
    private final CommandSpec commandSpec;
    private final UtcTimestamp startedAt;
    private final UtcTimestamp finishedAt;
    private final CommandTermination termination;
    private final Optional<Integer> exitCode;
    private final EvidenceSummary summary;
    private final EvidenceArtifactReference commandLog;
    private final Optional<EvidenceFailureClassification> failureClassification;
    private final TaskFactHash evidenceHash;
    private final AuditMetadata audit;

    private CommandEvidence(
            CommandEvidenceId id,
            WorkItemScope scope,
            TaskId taskId,
            TaskExecutionId taskExecutionId,
            int attempt,
            ExecutionWorkspaceId executionWorkspaceId,
            ExecutionWorkspaceFingerprint workspaceFingerprint,
            CodingTargetSnapshotReference codingTarget,
            EvidenceSequence sequence,
            WorkspacePolicyReference workspacePolicy,
            CommandSpec commandSpec,
            UtcTimestamp startedAt,
            UtcTimestamp finishedAt,
            CommandTermination termination,
            Optional<Integer> exitCode,
            EvidenceSummary summary,
            EvidenceArtifactReference commandLog,
            Optional<TaskFactHash> expectedEvidenceHash,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        if (attempt < 1) {
            throw new DomainValidationException("commandEvidence.attempt", "must be positive");
        }
        this.attempt = attempt;
        this.executionWorkspaceId = Objects.requireNonNull(
                executionWorkspaceId, "executionWorkspaceId");
        this.workspaceFingerprint = Objects.requireNonNull(
                workspaceFingerprint, "workspaceFingerprint");
        this.codingTarget = Objects.requireNonNull(codingTarget, "codingTarget");
        this.sequence = Objects.requireNonNull(sequence, "sequence");
        this.workspacePolicy = Objects.requireNonNull(workspacePolicy, "workspacePolicy");
        this.commandSpec = requireCommandSpec(commandSpec, this.workspacePolicy);
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.finishedAt = requireFinishedAt(this.startedAt, finishedAt);
        this.termination = Objects.requireNonNull(termination, "termination");
        this.exitCode = requireExitCode(this.termination, exitCode);
        this.summary = Objects.requireNonNull(summary, "summary");
        this.commandLog = requireCommandLog(commandLog);
        this.failureClassification = classify(this.termination, this.exitCode);
        this.audit = requireAudit(audit, this.finishedAt);
        this.evidenceHash = calculateHash();
        Objects.requireNonNull(expectedEvidenceHash, "expectedEvidenceHash").ifPresent(expected -> {
            if (!expected.equals(this.evidenceHash)) {
                throw new DomainValidationException(
                        "commandEvidence.evidenceHash",
                        "must match the immutable platform-observed command facts");
            }
        });
    }

    /** Records trusted runner facts while the exact ExecutionWorkspace is active. */
    public static CommandEvidence record(
            CommandEvidenceId id,
            ExecutionWorkspace workspace,
            WorkspacePolicy policy,
            EvidenceSequence sequence,
            CommandSpec commandSpec,
            UtcTimestamp startedAt,
            UtcTimestamp finishedAt,
            CommandTermination termination,
            Optional<Integer> exitCode,
            EvidenceSummary summary,
            EvidenceArtifactReference commandLog,
            Principal actor,
            UtcTimestamp recordedAt) {
        ExecutionWorkspace requiredWorkspace = Objects.requireNonNull(workspace, "workspace");
        if (requiredWorkspace.status() != ExecutionWorkspaceStatus.ACTIVE) {
            throw new DomainValidationException(
                    "commandEvidence.executionWorkspaceId", "Workspace must be ACTIVE");
        }
        WorkspacePolicy requiredPolicy = requirePolicy(requiredWorkspace, policy);
        PrincipalId actorId = CodingTargetActorPolicy.requireActiveInScope(
                actor, requiredWorkspace.scope(), "commandEvidence.createdByPrincipalId");
        return new CommandEvidence(
                id,
                requiredWorkspace.scope(),
                requiredWorkspace.taskId(),
                requiredWorkspace.taskExecutionId(),
                requiredWorkspace.attempt(),
                requiredWorkspace.id(),
                requiredWorkspace.fingerprint(),
                requiredWorkspace.codingTarget(),
                sequence,
                requiredPolicy.reference(),
                commandSpec,
                startedAt,
                finishedAt,
                termination,
                exitCode,
                summary,
                commandLog,
                Optional.empty(),
                AuditMetadata.createdBy(actorId, recordedAt));
    }

    /** Reconstitutes persisted facts and rejects shape, classification or Hash tampering. */
    public static CommandEvidence reconstitute(
            CommandEvidenceId id,
            WorkItemScope scope,
            TaskId taskId,
            TaskExecutionId taskExecutionId,
            int attempt,
            ExecutionWorkspaceId executionWorkspaceId,
            ExecutionWorkspaceFingerprint workspaceFingerprint,
            CodingTargetSnapshotReference codingTarget,
            EvidenceSequence sequence,
            WorkspacePolicyReference workspacePolicy,
            CommandSpec commandSpec,
            UtcTimestamp startedAt,
            UtcTimestamp finishedAt,
            CommandTermination termination,
            Optional<Integer> exitCode,
            EvidenceSummary summary,
            EvidenceArtifactReference commandLog,
            Optional<EvidenceFailureClassification> failureClassification,
            TaskFactHash evidenceHash,
            AuditMetadata audit) {
        CommandEvidence restored = new CommandEvidence(
                id,
                scope,
                taskId,
                taskExecutionId,
                attempt,
                executionWorkspaceId,
                workspaceFingerprint,
                codingTarget,
                sequence,
                workspacePolicy,
                commandSpec,
                startedAt,
                finishedAt,
                termination,
                exitCode,
                summary,
                commandLog,
                Optional.of(Objects.requireNonNull(evidenceHash, "evidenceHash")),
                audit);
        if (!restored.failureClassification.equals(
                Objects.requireNonNull(failureClassification, "failureClassification"))) {
            throw new DomainValidationException(
                    "commandEvidence.failureClassification",
                    "must be derived from the platform-observed termination and exit code");
        }
        return restored;
    }

    private static WorkspacePolicy requirePolicy(
            ExecutionWorkspace workspace, WorkspacePolicy policy) {
        WorkspacePolicy required = Objects.requireNonNull(policy, "workspacePolicy");
        if (!workspace.scope().equals(required.scope())
                || !workspace.taskId().equals(required.taskId())
                || !workspace.taskExecutionId().equals(required.taskExecutionId())
                || workspace.attempt() != required.attempt()
                || !workspace.codingTarget().equals(required.codingTarget())) {
            throw new DomainValidationException(
                    "commandEvidence.workspacePolicyId",
                    "must match the Workspace complete scope, execution attempt and CodingTarget");
        }
        return required;
    }

    private static CommandSpec requireCommandSpec(
            CommandSpec commandSpec, WorkspacePolicyReference workspacePolicy) {
        CommandSpec required = Objects.requireNonNull(commandSpec, "commandSpec");
        if (!required.workspacePolicy().equals(workspacePolicy)) {
            throw new DomainValidationException(
                    "commandEvidence.commandSpec", "must reference the exact WorkspacePolicy");
        }
        return required;
    }

    private static UtcTimestamp requireFinishedAt(
            UtcTimestamp startedAt, UtcTimestamp finishedAt) {
        UtcTimestamp required = Objects.requireNonNull(finishedAt, "finishedAt");
        if (required.compareTo(startedAt) < 0) {
            throw new DomainValidationException(
                    "commandEvidence.finishedAt", "must not be before startedAt");
        }
        return required;
    }

    private static Optional<Integer> requireExitCode(
            CommandTermination termination, Optional<Integer> exitCode) {
        Optional<Integer> required = Objects.requireNonNull(exitCode, "exitCode");
        if ((termination == CommandTermination.EXITED) != required.isPresent()) {
            throw new DomainValidationException(
                    "commandEvidence.exitCode",
                    "must exist only when the command process exited");
        }
        return required;
    }

    private static EvidenceArtifactReference requireCommandLog(
            EvidenceArtifactReference commandLog) {
        EvidenceArtifactReference required = Objects.requireNonNull(commandLog, "commandLog");
        if (required.kind() != EvidenceArtifactKind.COMMAND_LOG) {
            throw new DomainValidationException(
                    "commandEvidence.commandLog", "must reference a COMMAND_LOG Artifact");
        }
        return required;
    }

    private static AuditMetadata requireAudit(AuditMetadata audit, UtcTimestamp finishedAt) {
        AuditMetadata required = Objects.requireNonNull(audit, "audit");
        if (required.createdAt().compareTo(finishedAt) < 0) {
            throw new DomainValidationException(
                    "commandEvidence.audit.createdAt", "must not be before finishedAt");
        }
        return required;
    }

    private static Optional<EvidenceFailureClassification> classify(
            CommandTermination termination, Optional<Integer> exitCode) {
        return switch (termination) {
            case EXITED -> exitCode.orElseThrow() == 0
                    ? Optional.empty()
                    : Optional.of(EvidenceFailureClassification.COMMAND_NON_ZERO_EXIT);
            case TIMED_OUT -> Optional.of(EvidenceFailureClassification.COMMAND_TIMED_OUT);
            case START_FAILED -> Optional.of(EvidenceFailureClassification.COMMAND_START_FAILED);
            case OUTPUT_LIMIT_EXCEEDED -> Optional.of(
                    EvidenceFailureClassification.COMMAND_OUTPUT_LIMIT_EXCEEDED);
            case SANDBOX_POLICY_VIOLATION -> Optional.of(
                    EvidenceFailureClassification.COMMAND_SANDBOX_POLICY_VIOLATION);
            case CANCELLED -> Optional.of(EvidenceFailureClassification.COMMAND_CANCELLED);
        };
    }

    private TaskFactHash calculateHash() {
        StringBuilder canonical = new StringBuilder("command-evidence-v1");
        append(canonical, id.toString());
        append(canonical, scope.organizationId().toString());
        append(canonical, scope.teamId().toString());
        append(canonical, scope.workspaceId().toString());
        append(canonical, scope.projectId().toString());
        append(canonical, taskId.toString());
        append(canonical, taskExecutionId.toString());
        append(canonical, Integer.toString(attempt));
        append(canonical, executionWorkspaceId.toString());
        append(canonical, workspaceFingerprint.toString());
        append(canonical, codingTarget.snapshotId().toString());
        append(canonical, Long.toString(codingTarget.revision()));
        append(canonical, codingTarget.snapshotHash().toString());
        append(canonical, sequence.toString());
        append(canonical, workspacePolicy.id().toString());
        append(canonical, workspacePolicy.policyHash().toString());
        append(canonical, commandSpec.specHash().toString());
        append(canonical, startedAt.toString());
        append(canonical, finishedAt.toString());
        append(canonical, termination.name());
        append(canonical, exitCode.map(String::valueOf).orElse(""));
        append(canonical, summary.value());
        append(canonical, commandLog.artifactId().toString());
        append(canonical, commandLog.kind().name());
        append(canonical, commandLog.contentType());
        append(canonical, Long.toString(commandLog.sizeBytes()));
        append(canonical, commandLog.contentHash().toString());
        append(canonical, failureClassification.map(Enum::name).orElse(""));
        append(canonical, audit.createdBy().map(Object::toString).orElse(""));
        append(canonical, audit.createdAt().toString());
        return TaskFactHash.sha256(canonical.toString());
    }

    private static void append(StringBuilder target, String value) {
        target.append('|').append(value.length()).append(':').append(value);
    }

    public CommandEvidenceReference reference() {
        return new CommandEvidenceReference(id, sequence, evidenceHash, failureClassification);
    }

    public boolean succeeded() { return failureClassification.isEmpty(); }

    public CommandEvidenceId id() { return id; }
    public WorkItemScope scope() { return scope; }
    public TaskId taskId() { return taskId; }
    public TaskExecutionId taskExecutionId() { return taskExecutionId; }
    public int attempt() { return attempt; }
    public ExecutionWorkspaceId executionWorkspaceId() { return executionWorkspaceId; }
    public ExecutionWorkspaceFingerprint workspaceFingerprint() { return workspaceFingerprint; }
    public CodingTargetSnapshotReference codingTarget() { return codingTarget; }
    public EvidenceSequence sequence() { return sequence; }
    public WorkspacePolicyReference workspacePolicy() { return workspacePolicy; }
    public CommandSpec commandSpec() { return commandSpec; }
    public UtcTimestamp startedAt() { return startedAt; }
    public UtcTimestamp finishedAt() { return finishedAt; }
    public CommandTermination termination() { return termination; }
    public Optional<Integer> exitCode() { return exitCode; }
    public EvidenceSummary summary() { return summary; }
    public EvidenceArtifactReference commandLog() { return commandLog; }
    public Optional<EvidenceFailureClassification> failureClassification() {
        return failureClassification;
    }
    public TaskFactHash evidenceHash() { return evidenceHash; }
    public AuditMetadata audit() { return audit; }
}
