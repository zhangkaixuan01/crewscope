package io.crewscope.domain.coding;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable platform-derived test statistics and ordered acceptance evidence. */
public final class TestEvidence {

    private static final Set<CommandKind> VERIFICATION_COMMANDS = Set.of(
            CommandKind.TEST, CommandKind.VERIFY, CommandKind.ACCEPTANCE);

    private final TestEvidenceId id;
    private final WorkItemScope scope;
    private final TaskId taskId;
    private final TaskExecutionId taskExecutionId;
    private final int attempt;
    private final ExecutionWorkspaceId executionWorkspaceId;
    private final ExecutionWorkspaceFingerprint workspaceFingerprint;
    private final CodingTargetSnapshotReference codingTarget;
    private final DiffGeneration diffGeneration;
    private final RuntimeContentHash diffManifestHash;
    private final EvidenceSequence sequence;
    private final WorkspacePolicyReference workspacePolicy;
    private final List<CommandEvidenceReference> commands;
    private final TestStatistics statistics;
    private final List<AcceptanceResult> acceptanceResults;
    private final Optional<EvidenceArtifactReference> testReport;
    private final EvidenceSummary summary;
    private final Optional<EvidenceFailureClassification> failureClassification;
    private final TaskFactHash evidenceHash;
    private final AuditMetadata audit;

    private TestEvidence(
            TestEvidenceId id,
            WorkItemScope scope,
            TaskId taskId,
            TaskExecutionId taskExecutionId,
            int attempt,
            ExecutionWorkspaceId executionWorkspaceId,
            ExecutionWorkspaceFingerprint workspaceFingerprint,
            CodingTargetSnapshotReference codingTarget,
            DiffGeneration diffGeneration,
            RuntimeContentHash diffManifestHash,
            EvidenceSequence sequence,
            WorkspacePolicyReference workspacePolicy,
            List<CommandEvidenceReference> commands,
            TestStatistics statistics,
            List<AcceptanceResult> acceptanceResults,
            Optional<EvidenceArtifactReference> testReport,
            EvidenceSummary summary,
            Optional<TaskFactHash> expectedEvidenceHash,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        if (attempt < 1) {
            throw new DomainValidationException("testEvidence.attempt", "must be positive");
        }
        this.attempt = attempt;
        this.executionWorkspaceId = Objects.requireNonNull(
                executionWorkspaceId, "executionWorkspaceId");
        this.workspaceFingerprint = Objects.requireNonNull(
                workspaceFingerprint, "workspaceFingerprint");
        this.codingTarget = Objects.requireNonNull(codingTarget, "codingTarget");
        this.diffGeneration = Objects.requireNonNull(diffGeneration, "diffGeneration");
        this.diffManifestHash = Objects.requireNonNull(diffManifestHash, "diffManifestHash");
        this.sequence = Objects.requireNonNull(sequence, "sequence");
        this.workspacePolicy = Objects.requireNonNull(workspacePolicy, "workspacePolicy");
        this.commands = requireOrderedCommands(commands);
        this.statistics = Objects.requireNonNull(statistics, "statistics");
        this.acceptanceResults = requireAcceptanceReferences(
                acceptanceResults, this.commands);
        this.testReport = requireTestReport(testReport);
        this.summary = Objects.requireNonNull(summary, "summary");
        this.failureClassification = classify(
                this.commands, this.statistics, this.acceptanceResults, this.testReport);
        this.audit = Objects.requireNonNull(audit, "audit");
        this.evidenceHash = calculateHash();
        Objects.requireNonNull(expectedEvidenceHash, "expectedEvidenceHash").ifPresent(expected -> {
            if (!expected.equals(this.evidenceHash)) {
                throw new DomainValidationException(
                        "testEvidence.evidenceHash",
                        "must match the immutable test and acceptance facts");
            }
        });
    }

    /** Publishes a verdict derived only from closed Workspace and CommandEvidence facts. */
    public static TestEvidence publish(
            TestEvidenceId id,
            ExecutionWorkspace workspace,
            CodingTargetSnapshot codingTarget,
            WorkspacePolicy policy,
            DiffManifest diffManifest,
            EvidenceSequence sequence,
            List<CommandEvidence> commands,
            TestStatistics statistics,
            List<AcceptanceResult> acceptanceResults,
            Optional<EvidenceArtifactReference> testReport,
            EvidenceSummary summary,
            Principal actor,
            UtcTimestamp publishedAt) {
        ExecutionWorkspace requiredWorkspace = Objects.requireNonNull(workspace, "workspace");
        if (requiredWorkspace.status() != ExecutionWorkspaceStatus.ACTIVE
                && requiredWorkspace.status() != ExecutionWorkspaceStatus.FINALIZING) {
            throw new DomainValidationException(
                    "testEvidence.executionWorkspaceId",
                    "Workspace must be ACTIVE or FINALIZING");
        }
        CodingTargetSnapshot target = requireTarget(requiredWorkspace, codingTarget);
        WorkspacePolicy requiredPolicy = requirePolicy(requiredWorkspace, policy);
        DiffManifest requiredDiff = Objects.requireNonNull(diffManifest, "diffManifest");
        List<CommandEvidence> requiredCommands = List.copyOf(
                Objects.requireNonNull(commands, "commands"));
        requireCommandLineage(requiredWorkspace, requiredPolicy, requiredCommands);
        requireVerificationCommand(requiredCommands);
        requirePublicationTime(requiredCommands, publishedAt);
        List<AcceptanceResult> requiredResults = requireAcceptanceCriteria(
                target, acceptanceResults);
        PrincipalId actorId = CodingTargetActorPolicy.requireActiveInScope(
                actor, requiredWorkspace.scope(), "testEvidence.createdByPrincipalId");
        return new TestEvidence(
                id,
                requiredWorkspace.scope(),
                requiredWorkspace.taskId(),
                requiredWorkspace.taskExecutionId(),
                requiredWorkspace.attempt(),
                requiredWorkspace.id(),
                requiredWorkspace.fingerprint(),
                requiredWorkspace.codingTarget(),
                requiredDiff.generation(),
                requiredDiff.contentHash(),
                sequence,
                requiredPolicy.reference(),
                requiredCommands.stream().map(CommandEvidence::reference).toList(),
                statistics,
                requiredResults,
                testReport,
                summary,
                Optional.empty(),
                AuditMetadata.createdBy(actorId, publishedAt));
    }

    /** Reconstitutes persisted evidence while re-deriving its classification and Hash. */
    public static TestEvidence reconstitute(
            TestEvidenceId id,
            WorkItemScope scope,
            TaskId taskId,
            TaskExecutionId taskExecutionId,
            int attempt,
            ExecutionWorkspaceId executionWorkspaceId,
            ExecutionWorkspaceFingerprint workspaceFingerprint,
            CodingTargetSnapshotReference codingTarget,
            DiffGeneration diffGeneration,
            RuntimeContentHash diffManifestHash,
            EvidenceSequence sequence,
            WorkspacePolicyReference workspacePolicy,
            List<CommandEvidenceReference> commands,
            TestStatistics statistics,
            List<AcceptanceResult> acceptanceResults,
            Optional<EvidenceArtifactReference> testReport,
            EvidenceSummary summary,
            Optional<EvidenceFailureClassification> failureClassification,
            TaskFactHash evidenceHash,
            AuditMetadata audit) {
        Optional<EvidenceFailureClassification> expected = Objects.requireNonNull(
                failureClassification, "failureClassification");
        TestEvidence restored = new TestEvidence(
                id,
                scope,
                taskId,
                taskExecutionId,
                attempt,
                executionWorkspaceId,
                workspaceFingerprint,
                codingTarget,
                diffGeneration,
                diffManifestHash,
                sequence,
                workspacePolicy,
                commands,
                statistics,
                acceptanceResults,
                testReport,
                summary,
                Optional.of(Objects.requireNonNull(evidenceHash, "evidenceHash")),
                audit);
        if (!restored.failureClassification.equals(expected)) {
            throw new DomainValidationException(
                    "testEvidence.failureClassification",
                    "must be derived from command, report, test and acceptance facts");
        }
        return restored;
    }

    private static CodingTargetSnapshot requireTarget(
            ExecutionWorkspace workspace, CodingTargetSnapshot codingTarget) {
        CodingTargetSnapshot target = Objects.requireNonNull(codingTarget, "codingTarget");
        if (!workspace.scope().equals(target.scope())
                || !workspace.taskId().equals(target.taskId())
                || !workspace.codingTarget().equals(target.reference())) {
            throw new DomainValidationException(
                    "testEvidence.codingTargetSnapshotId",
                    "must match the Workspace complete scope and exact CodingTarget");
        }
        return target;
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
                    "testEvidence.workspacePolicyId",
                    "must match the Workspace complete scope, execution attempt and CodingTarget");
        }
        return required;
    }

    private static void requireCommandLineage(
            ExecutionWorkspace workspace,
            WorkspacePolicy policy,
            List<CommandEvidence> commands) {
        if (commands.isEmpty()) {
            throw new DomainValidationException(
                    "testEvidence.commands", "must contain at least one CommandEvidence");
        }
        for (CommandEvidence command : commands) {
            CommandEvidence required = Objects.requireNonNull(command, "commandEvidence");
            if (!workspace.scope().equals(required.scope())
                    || !workspace.taskId().equals(required.taskId())
                    || !workspace.taskExecutionId().equals(required.taskExecutionId())
                    || workspace.attempt() != required.attempt()
                    || !workspace.id().equals(required.executionWorkspaceId())
                    || !workspace.fingerprint().equals(required.workspaceFingerprint())
                    || !workspace.codingTarget().equals(required.codingTarget())
                    || !policy.reference().equals(required.workspacePolicy())) {
                throw new DomainValidationException(
                        "testEvidence.commands",
                        "all commands must belong to the exact Workspace, Policy and attempt");
            }
        }
    }

    private static void requireVerificationCommand(List<CommandEvidence> commands) {
        if (commands.stream()
                .map(CommandEvidence::commandSpec)
                .map(CommandSpec::commandKind)
                .noneMatch(VERIFICATION_COMMANDS::contains)) {
            throw new DomainValidationException(
                    "testEvidence.commands",
                    "must include a TEST, VERIFY or ACCEPTANCE command");
        }
    }

    private static void requirePublicationTime(
            List<CommandEvidence> commands, UtcTimestamp publishedAt) {
        UtcTimestamp required = Objects.requireNonNull(publishedAt, "publishedAt");
        if (commands.stream().anyMatch(command -> required.compareTo(command.finishedAt()) < 0)) {
            throw new DomainValidationException(
                    "testEvidence.audit.createdAt",
                    "must not be before any referenced command finishedAt");
        }
    }

    private static List<AcceptanceResult> requireAcceptanceCriteria(
            CodingTargetSnapshot target, List<AcceptanceResult> results) {
        List<AcceptanceResult> required = List.copyOf(
                Objects.requireNonNull(results, "acceptanceResults"));
        if (required.size() != target.acceptanceCriteria().size()) {
            throw new DomainValidationException(
                    "testEvidence.acceptanceResults",
                    "must cover every captured acceptance criterion exactly once and in order");
        }
        for (int index = 0; index < required.size(); index++) {
            AcceptanceResult result = required.get(index);
            if (result.criterionIndex() != index + 1
                    || !result.criterion().equals(target.acceptanceCriteria().get(index))) {
                throw new DomainValidationException(
                        "testEvidence.acceptanceResults",
                        "must preserve captured criterion indexes, text and order");
            }
        }
        return required;
    }

    private static List<CommandEvidenceReference> requireOrderedCommands(
            List<CommandEvidenceReference> commands) {
        List<CommandEvidenceReference> required = List.copyOf(
                Objects.requireNonNull(commands, "commands"));
        if (required.isEmpty()) {
            throw new DomainValidationException(
                    "testEvidence.commands", "must contain at least one CommandEvidence");
        }
        EvidenceSequence previous = null;
        Set<CommandEvidenceId> ids = new HashSet<>();
        for (CommandEvidenceReference command : required) {
            CommandEvidenceReference current = Objects.requireNonNull(command, "commandEvidence");
            if (!ids.add(current.id())
                    || (previous != null && current.sequence().compareTo(previous) <= 0)) {
                throw new DomainValidationException(
                        "testEvidence.commands",
                        "must contain unique CommandEvidence in strictly increasing sequence order");
            }
            previous = current.sequence();
        }
        return required;
    }

    private static List<AcceptanceResult> requireAcceptanceReferences(
            List<AcceptanceResult> results, List<CommandEvidenceReference> commands) {
        List<AcceptanceResult> required = List.copyOf(
                Objects.requireNonNull(results, "acceptanceResults"));
        Set<CommandEvidenceReference> available = Set.copyOf(commands);
        if (required.stream()
                .flatMap(result -> result.evidence().stream())
                .anyMatch(reference -> !available.contains(reference))) {
            throw new DomainValidationException(
                    "testEvidence.acceptanceResults.evidence",
                    "must reference only CommandEvidence contained by this TestEvidence");
        }
        return required;
    }

    private static Optional<EvidenceArtifactReference> requireTestReport(
            Optional<EvidenceArtifactReference> testReport) {
        Optional<EvidenceArtifactReference> required = Objects.requireNonNull(
                testReport, "testReport");
        required.ifPresent(report -> {
            if (report.kind() != EvidenceArtifactKind.TEST_REPORT) {
                throw new DomainValidationException(
                        "testEvidence.testReport", "must reference a TEST_REPORT Artifact");
            }
        });
        return required;
    }

    private static Optional<EvidenceFailureClassification> classify(
            List<CommandEvidenceReference> commands,
            TestStatistics statistics,
            List<AcceptanceResult> acceptanceResults,
            Optional<EvidenceArtifactReference> testReport) {
        Optional<EvidenceFailureClassification> commandFailure = commands.stream()
                .map(CommandEvidenceReference::failureClassification)
                .flatMap(Optional::stream)
                .findFirst();
        if (commandFailure.isPresent()) {
            return commandFailure;
        }
        if (testReport.isEmpty()) {
            return Optional.of(EvidenceFailureClassification.TEST_REPORT_MISSING);
        }
        if (statistics.total() == 0) {
            return Optional.of(EvidenceFailureClassification.NO_TESTS_EXECUTED);
        }
        if (statistics.hasFailures()) {
            return Optional.of(EvidenceFailureClassification.TESTS_FAILED);
        }
        if (acceptanceResults.stream()
                .anyMatch(result -> result.status() == AcceptanceStatus.NOT_EVALUATED)) {
            return Optional.of(EvidenceFailureClassification.ACCEPTANCE_INCOMPLETE);
        }
        if (acceptanceResults.stream()
                .anyMatch(result -> result.status() == AcceptanceStatus.FAILED)) {
            return Optional.of(EvidenceFailureClassification.ACCEPTANCE_FAILED);
        }
        return Optional.empty();
    }

    private TaskFactHash calculateHash() {
        StringBuilder canonical = new StringBuilder("test-evidence-v1");
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
        append(canonical, diffGeneration.toString());
        append(canonical, diffManifestHash.toString());
        append(canonical, sequence.toString());
        append(canonical, workspacePolicy.id().toString());
        append(canonical, workspacePolicy.policyHash().toString());
        append(canonical, Integer.toString(commands.size()));
        commands.forEach(command -> {
            append(canonical, command.id().toString());
            append(canonical, command.sequence().toString());
            append(canonical, command.evidenceHash().toString());
            append(canonical, command.failureClassification().map(Enum::name).orElse(""));
        });
        append(canonical, Long.toString(statistics.total()));
        append(canonical, Long.toString(statistics.passed()));
        append(canonical, Long.toString(statistics.failed()));
        append(canonical, Long.toString(statistics.errors()));
        append(canonical, Long.toString(statistics.skipped()));
        append(canonical, Integer.toString(acceptanceResults.size()));
        acceptanceResults.forEach(result -> {
            append(canonical, Integer.toString(result.criterionIndex()));
            append(canonical, result.criterion());
            append(canonical, result.status().name());
            append(canonical, result.summary().value());
            append(canonical, Integer.toString(result.evidence().size()));
            result.evidence().forEach(reference -> append(canonical, reference.evidenceHash().toString()));
        });
        append(canonical, testReport.map(value -> value.artifactId().toString()).orElse(""));
        append(canonical, testReport.map(value -> value.kind().name()).orElse(""));
        append(canonical, testReport.map(EvidenceArtifactReference::contentType).orElse(""));
        append(canonical, testReport.map(value -> Long.toString(value.sizeBytes())).orElse(""));
        append(canonical, testReport.map(value -> value.contentHash().toString()).orElse(""));
        append(canonical, summary.value());
        append(canonical, failureClassification.map(Enum::name).orElse(""));
        append(canonical, audit.createdBy().map(Object::toString).orElse(""));
        append(canonical, audit.createdAt().toString());
        return TaskFactHash.sha256(canonical.toString());
    }

    private static void append(StringBuilder target, String value) {
        target.append('|').append(value.length()).append(':').append(value);
    }

    public boolean succeeded() { return failureClassification.isEmpty(); }

    public TestEvidenceId id() { return id; }
    public WorkItemScope scope() { return scope; }
    public TaskId taskId() { return taskId; }
    public TaskExecutionId taskExecutionId() { return taskExecutionId; }
    public int attempt() { return attempt; }
    public ExecutionWorkspaceId executionWorkspaceId() { return executionWorkspaceId; }
    public ExecutionWorkspaceFingerprint workspaceFingerprint() { return workspaceFingerprint; }
    public CodingTargetSnapshotReference codingTarget() { return codingTarget; }
    public DiffGeneration diffGeneration() { return diffGeneration; }
    public RuntimeContentHash diffManifestHash() { return diffManifestHash; }
    public EvidenceSequence sequence() { return sequence; }
    public WorkspacePolicyReference workspacePolicy() { return workspacePolicy; }
    public List<CommandEvidenceReference> commands() { return commands; }
    public TestStatistics statistics() { return statistics; }
    public List<AcceptanceResult> acceptanceResults() { return acceptanceResults; }
    public Optional<EvidenceArtifactReference> testReport() { return testReport; }
    public EvidenceSummary summary() { return summary; }
    public Optional<EvidenceFailureClassification> failureClassification() {
        return failureClassification;
    }
    public TaskFactHash evidenceHash() { return evidenceHash; }
    public AuditMetadata audit() { return audit; }
}
