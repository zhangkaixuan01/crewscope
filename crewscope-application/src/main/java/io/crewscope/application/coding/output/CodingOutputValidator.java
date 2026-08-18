package io.crewscope.application.coding.output;

import io.crewscope.domain.coding.AcceptanceResult;
import io.crewscope.domain.coding.CodingTargetSnapshot;
import io.crewscope.domain.coding.CommandEvidenceReference;
import io.crewscope.domain.coding.DiffArtifact;
import io.crewscope.domain.coding.DiffFileEntry;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.TestEvidence;
import io.crewscope.domain.coding.TestStatistics;
import io.crewscope.domain.coding.WorkspacePolicy;
import io.crewscope.domain.task.TaskFactHash;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Revalidates model claims against current server-owned Coding facts. */
public final class CodingOutputValidator {

    private final Validator validator;

    public CodingOutputValidator(Validator validator) {
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    public TaskFactHash validateRepositoryAnalysis(
            RepositoryAnalysisV1 output, CodingTargetSnapshot target) {
        RepositoryAnalysisV1 value = validateBean(output, "repositoryAnalysis");
        CodingTargetSnapshot authority = Objects.requireNonNull(target, "target");
        requireVersion(value.schemaVersion(), RepositoryAnalysisV1.SCHEMA_VERSION,
                "repositoryAnalysis.schemaVersion");
        requireTarget(value.codingTargetSnapshotId(), value.codingTargetRevision(),
                value.codingTargetHash(), authority, "repositoryAnalysis");
        requireUnique(value.modules(), "repositoryAnalysis.modules");
        requireUnique(value.buildEntries(), "repositoryAnalysis.buildEntries");
        requireUnique(value.relevantPaths(), "repositoryAnalysis.relevantPaths");
        value.buildEntries().forEach(path -> requireAllowed(path, authority,
                "repositoryAnalysis.buildEntries"));
        value.relevantPaths().forEach(path -> requireAllowed(path, authority,
                "repositoryAnalysis.relevantPaths"));
        return hashRepositoryAnalysis(value);
    }

    public void validateDiffManifest(
            DiffManifestV1 output,
            CodingTargetSnapshot target,
            ExecutionWorkspace workspace,
            DiffArtifact artifact) {
        DiffManifestV1 value = validateBean(output, "diffManifest");
        CodingTargetSnapshot requiredTarget = Objects.requireNonNull(target, "target");
        ExecutionWorkspace requiredWorkspace = Objects.requireNonNull(workspace, "workspace");
        DiffArtifact authority = Objects.requireNonNull(artifact, "artifact");
        requireVersion(value.schemaVersion(), DiffManifestV1.SCHEMA_VERSION,
                "diffManifest.schemaVersion");
        requireWorkspace(value.executionWorkspaceId(), value.workspaceFingerprint(),
                requiredWorkspace, "diffManifest");
        requireTarget(value.codingTargetSnapshotId(), value.codingTargetRevision(),
                value.codingTargetHash(), requiredTarget, "diffManifest");
        requireArtifactCoordinates(requiredTarget, requiredWorkspace, authority);
        require(value.diffGeneration() == authority.manifest().generation().value(),
                "diffManifest.diffGeneration", "must match the Git-authority generation");
        require(value.manifestHash().equals(authority.manifest().contentHash().toString()),
                "diffManifest.manifestHash", "must match the Git-authority manifest hash");
        require(value.fileCount() == authority.manifest().fileCount()
                        && value.additions() == authority.manifest().additions()
                        && value.deletions() == authority.manifest().deletions(),
                "diffManifest.statistics", "must match the Git-authority totals");
        require(value.files().size() == authority.manifest().files().size(),
                "diffManifest.files", "must match every Git-authority file");
        Set<String> paths = new HashSet<>();
        for (int index = 0; index < value.files().size(); index++) {
            DiffFileV1 candidate = value.files().get(index);
            require(paths.add(candidate.path()), "diffManifest.files", "must not repeat paths");
            requireAllowed(candidate.path(), requiredTarget, "diffManifest.files.path");
            if (!candidate.oldPath().isEmpty()) {
                requireAllowed(candidate.oldPath(), requiredTarget, "diffManifest.files.oldPath");
            }
            requireFile(candidate, authority.manifest().files().get(index), index);
        }
    }

    public void validateTestEvidence(
            TestEvidenceV1 output,
            CodingTargetSnapshot target,
            ExecutionWorkspace workspace,
            WorkspacePolicy policy,
            TestEvidence evidence) {
        TestEvidenceV1 value = validateBean(output, "testEvidence");
        CodingTargetSnapshot requiredTarget = Objects.requireNonNull(target, "target");
        ExecutionWorkspace requiredWorkspace = Objects.requireNonNull(workspace, "workspace");
        WorkspacePolicy requiredPolicy = Objects.requireNonNull(policy, "policy");
        TestEvidence authority = Objects.requireNonNull(evidence, "evidence");
        requireVersion(value.schemaVersion(), TestEvidenceV1.SCHEMA_VERSION,
                "testEvidence.schemaVersion");
        requireWorkspace(value.executionWorkspaceId(), value.workspaceFingerprint(),
                requiredWorkspace, "testEvidence");
        requireTarget(value.codingTargetSnapshotId(), value.codingTargetRevision(),
                value.codingTargetHash(), requiredTarget, "testEvidence");
        require(requiredPolicy.scope().equals(requiredWorkspace.scope())
                        && requiredPolicy.taskId().equals(requiredWorkspace.taskId())
                        && requiredPolicy.taskExecutionId().equals(requiredWorkspace.taskExecutionId())
                        && requiredPolicy.attempt() == requiredWorkspace.attempt()
                        && requiredPolicy.codingTarget().equals(requiredTarget.reference()),
                "testEvidence.workspacePolicy", "must belong to the same execution and target");
        require(authority.scope().equals(requiredWorkspace.scope())
                        && authority.taskId().equals(requiredWorkspace.taskId())
                        && authority.taskExecutionId().equals(requiredWorkspace.taskExecutionId())
                        && authority.attempt() == requiredWorkspace.attempt()
                        && authority.executionWorkspaceId().equals(requiredWorkspace.id())
                        && authority.workspaceFingerprint().equals(requiredWorkspace.fingerprint())
                        && authority.codingTarget().equals(requiredTarget.reference())
                        && authority.workspacePolicy().equals(requiredPolicy.reference()),
                "testEvidence.identity", "must close over the supplied authority facts");
        require(value.testEvidenceId().equals(authority.id().toString())
                        && value.evidenceHash().equals(authority.evidenceHash().toString())
                        && value.evidenceSequence() == authority.sequence().value(),
                "testEvidence.reference", "must match the immutable TestEvidence identity");
        require(value.diffGeneration() == authority.diffGeneration().value()
                        && value.diffManifestHash().equals(
                                authority.diffManifestHash().toString()),
                "testEvidence.diffManifest",
                "must match the exact Git-authority content tested by the Runner");
        require(value.workspacePolicyId().equals(requiredPolicy.id().toString())
                        && value.workspacePolicyHash().equals(requiredPolicy.policyHash().toString()),
                "testEvidence.workspacePolicy", "must match the immutable policy reference");
        requireCommands(value.commands(), authority.commands(), "testEvidence.commands");
        requireStatistics(value.statistics(), authority.statistics());
        require(value.acceptanceResults().size() == authority.acceptanceResults().size(),
                "testEvidence.acceptanceResults", "must match every acceptance result");
        for (int index = 0; index < value.acceptanceResults().size(); index++) {
            requireAcceptance(value.acceptanceResults().get(index),
                    authority.acceptanceResults().get(index), index);
        }
        require(value.summaryHash().equals(TaskFactHash.sha256(authority.summary().value()).toString()),
                "testEvidence.summaryHash", "must match the platform evidence summary");
    }

    /** Returns only after every final claim is closed over successful authority evidence. */
    public void validateCodeChangeResult(
            CodeChangeResultV1 output,
            RepositoryAnalysisV1 analysis,
            CodingTargetSnapshot target,
            ExecutionWorkspace workspace,
            DiffArtifact diffArtifact,
            TestEvidence testEvidence) {
        CodeChangeResultV1 value = validateBean(output, "codeChangeResult");
        TaskFactHash analysisHash = validateRepositoryAnalysis(analysis, target);
        requireVersion(value.schemaVersion(), CodeChangeResultV1.SCHEMA_VERSION,
                "codeChangeResult.schemaVersion");
        requireWorkspace(value.executionWorkspaceId(), value.workspaceFingerprint(), workspace,
                "codeChangeResult");
        requireTarget(value.codingTargetSnapshotId(), value.codingTargetRevision(),
                value.codingTargetHash(), target, "codeChangeResult");
        require(value.repositoryAnalysisHash().equals(analysisHash.toString()),
                "codeChangeResult.repositoryAnalysisHash", "must match the validated analysis");
        requireArtifactCoordinates(target, workspace, diffArtifact);
        require(value.diffArtifactId().equals(diffArtifact.id().toString())
                        && value.diffArtifactHash().equals(diffArtifact.finalHash().toString()),
                "codeChangeResult.diffArtifact", "must match the final DiffArtifact");
        require(testEvidence.scope().equals(workspace.scope())
                        && testEvidence.taskExecutionId().equals(workspace.taskExecutionId())
                        && testEvidence.executionWorkspaceId().equals(workspace.id())
                        && testEvidence.workspaceFingerprint().equals(workspace.fingerprint())
                        && testEvidence.codingTarget().equals(target.reference())
                        && testEvidence.diffGeneration().equals(
                                diffArtifact.manifest().generation())
                        && testEvidence.diffManifestHash().equals(
                                diffArtifact.manifest().contentHash()),
                "codeChangeResult.testEvidence",
                "must belong to the same execution and exact final Diff facts");
        require(value.testEvidenceId().equals(testEvidence.id().toString())
                        && value.testEvidenceHash().equals(testEvidence.evidenceHash().toString()),
                "codeChangeResult.testEvidence", "must match the immutable TestEvidence");
        require(testEvidence.succeeded(), "codeChangeResult.testEvidence",
                "must be successful according to platform-owned evidence");
    }

    public static TaskFactHash hashRepositoryAnalysis(RepositoryAnalysisV1 value) {
        RepositoryAnalysisV1 required = Objects.requireNonNull(value, "value");
        StringBuilder canonical = new StringBuilder("repository-analysis-v1");
        append(canonical, required.schemaVersion());
        append(canonical, required.codingTargetSnapshotId());
        append(canonical, Long.toString(required.codingTargetRevision()));
        append(canonical, required.codingTargetHash());
        appendList(canonical, required.modules());
        appendList(canonical, required.buildEntries());
        appendList(canonical, required.relevantPaths());
        appendList(canonical, required.risks());
        appendList(canonical, required.plan());
        return TaskFactHash.sha256(canonical.toString());
    }

    private <T> T validateBean(T value, String field) {
        T required = Objects.requireNonNull(value, field);
        Set<ConstraintViolation<T>> violations = validator.validate(required);
        if (!violations.isEmpty()) {
            ConstraintViolation<T> violation = violations.stream()
                    .sorted(java.util.Comparator.comparing(v -> v.getPropertyPath().toString()))
                    .findFirst().orElseThrow();
            throw invalid(field + "." + violation.getPropertyPath(), violation.getMessage());
        }
        return required;
    }

    private static void requireArtifactCoordinates(
            CodingTargetSnapshot target, ExecutionWorkspace workspace, DiffArtifact artifact) {
        require(workspace.scope().equals(target.scope())
                        && workspace.taskId().equals(target.taskId())
                        && workspace.codingTarget().equals(target.reference()),
                "codingOutput.workspace", "must belong to the supplied target");
        require(artifact.scope().equals(workspace.scope())
                        && artifact.taskId().equals(workspace.taskId())
                        && artifact.taskExecutionId().equals(workspace.taskExecutionId())
                        && artifact.attempt() == workspace.attempt()
                        && artifact.executionWorkspaceId().equals(workspace.id())
                        && artifact.codingTarget().equals(target.reference()),
                "codingOutput.diffArtifact", "must belong to the supplied workspace and target");
    }

    private static void requireFile(DiffFileV1 candidate, DiffFileEntry authority, int index) {
        require(candidate.path().equals(authority.path().value())
                        && candidate.oldPath().equals(authority.oldPath()
                                .map(path -> path.value()).orElse(""))
                        && candidate.kind().equals(authority.kind().name())
                        && candidate.additions() == authority.additions()
                        && candidate.deletions() == authority.deletions()
                        && candidate.binary() == authority.binary()
                        && candidate.patchTruncated() == authority.patchTruncated()
                        && candidate.patchSha256().equals(authority.patchSha256().toString()),
                "diffManifest.files[" + index + "]", "must match the sorted Git-authority entry");
    }

    private static void requireCommands(
            List<CommandEvidenceReferenceV1> candidates,
            List<CommandEvidenceReference> authorities,
            String field) {
        require(candidates.size() == authorities.size(), field,
                "must match every CommandEvidence reference");
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < candidates.size(); index++) {
            CommandEvidenceReferenceV1 candidate = candidates.get(index);
            CommandEvidenceReference authority = authorities.get(index);
            require(ids.add(candidate.commandEvidenceId()), field, "must not repeat evidence");
            require(candidate.commandEvidenceId().equals(authority.id().toString())
                            && candidate.sequence() == authority.sequence().value()
                            && candidate.evidenceHash().equals(authority.evidenceHash().toString()),
                    field + "[" + index + "]", "must match the ordered authority reference");
        }
    }

    private static void requireStatistics(TestStatisticsV1 candidate, TestStatistics authority) {
        require(candidate.total() == authority.total()
                        && candidate.passed() == authority.passed()
                        && candidate.failed() == authority.failed()
                        && candidate.errors() == authority.errors()
                        && candidate.skipped() == authority.skipped(),
                "testEvidence.statistics", "must match parser-observed counters");
    }

    private static void requireAcceptance(
            AcceptanceResultV1 candidate, AcceptanceResult authority, int index) {
        require(candidate.criterionIndex() == authority.criterionIndex()
                        && candidate.criterion().equals(authority.criterion())
                        && candidate.status().equals(authority.status().name())
                        && candidate.summaryHash().equals(
                                TaskFactHash.sha256(authority.summary().value()).toString()),
                "testEvidence.acceptanceResults[" + index + "]",
                "must match the authority acceptance verdict");
        requireCommands(candidate.evidence(), authority.evidence(),
                "testEvidence.acceptanceResults[" + index + "].evidence");
    }

    private static void requireTarget(
            String id, long revision, String hash, CodingTargetSnapshot target, String field) {
        require(id.equals(target.id().toString())
                        && revision == target.revision()
                        && hash.equals(target.snapshotHash().toString()),
                field + ".codingTarget", "must match the immutable target revision");
    }

    private static void requireWorkspace(
            String id, String fingerprint, ExecutionWorkspace workspace, String field) {
        require(id.equals(workspace.id().toString())
                        && fingerprint.equals(workspace.fingerprint().toString()),
                field + ".workspace", "must match the immutable logical workspace");
    }

    private static void requireAllowed(
            String path, CodingTargetSnapshot target, String field) {
        try {
            require(target.allowedPaths().allows(path), field,
                    "must be a canonical path inside CodingTarget allowed paths");
        } catch (RuntimeException exception) {
            if (exception instanceof CodingOutputValidationException validation) {
                throw validation;
            }
            throw invalid(field, "must be a canonical path inside CodingTarget allowed paths");
        }
    }

    private static void requireUnique(List<String> values, String field) {
        require(new HashSet<>(values).size() == values.size(), field, "must not contain duplicates");
    }

    private static void requireVersion(String actual, String expected, String field) {
        require(expected.equals(actual), field, "unsupported schema version");
    }

    private static void appendList(StringBuilder target, List<String> values) {
        append(target, Integer.toString(values.size()));
        values.forEach(value -> append(target, value));
    }

    private static void append(StringBuilder target, String value) {
        target.append('|').append(value.length()).append(':').append(value);
    }

    private static void require(boolean condition, String field, String message) {
        if (!condition) {
            throw invalid(field, message);
        }
    }

    private static CodingOutputValidationException invalid(String field, String message) {
        return new CodingOutputValidationException(field, message);
    }
}
