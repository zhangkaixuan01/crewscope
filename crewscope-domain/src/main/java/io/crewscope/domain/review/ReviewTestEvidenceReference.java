package io.crewscope.domain.review;

import io.crewscope.domain.coding.AcceptanceResult;
import io.crewscope.domain.coding.CodingTargetSnapshotReference;
import io.crewscope.domain.coding.CommandEvidenceReference;
import io.crewscope.domain.coding.DiffGeneration;
import io.crewscope.domain.coding.TestEvidence;
import io.crewscope.domain.coding.TestEvidenceId;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Comparator;

/** Exact TestEvidence authority plus bounded command and complete acceptance facts. */
public record ReviewTestEvidenceReference(
        WorkItemScope scope,
        TaskId taskId,
        TaskExecutionId taskExecutionId,
        int attempt,
        CodingTargetSnapshotReference codingTarget,
        TestEvidenceId id,
        TaskFactHash evidenceHash,
        DiffGeneration diffGeneration,
        RuntimeContentHash diffManifestHash,
        List<ReviewCommandEvidenceReference> commands,
        List<AcceptanceResult> acceptanceResults) {

    public ReviewTestEvidenceReference {
        scope = Objects.requireNonNull(scope, "scope");
        taskId = Objects.requireNonNull(taskId, "taskId");
        taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        if (attempt < 1) {
            throw new DomainValidationException("contextPackage.attempt", "must be positive");
        }
        codingTarget = Objects.requireNonNull(codingTarget, "codingTarget");
        id = Objects.requireNonNull(id, "id");
        evidenceHash = Objects.requireNonNull(evidenceHash, "evidenceHash");
        diffGeneration = Objects.requireNonNull(diffGeneration, "diffGeneration");
        diffManifestHash = Objects.requireNonNull(diffManifestHash, "diffManifestHash");
        commands = List.copyOf(Objects.requireNonNull(commands, "commands"));
        acceptanceResults = List.copyOf(
                Objects.requireNonNull(acceptanceResults, "acceptanceResults"));
        if (commands.isEmpty() || commands.size() > ContextPackage.MAX_COMMAND_EVIDENCE) {
            throw new DomainValidationException(
                    "contextPackage.commands", "must contain 1 to 64 command evidence entries");
        }
        if (acceptanceResults.isEmpty()
                || acceptanceResults.size() > ContextPackage.MAX_ACCEPTANCE_RESULTS) {
            throw new DomainValidationException(
                    "contextPackage.acceptanceResults", "must contain 1 to 100 complete results");
        }
        Set<CommandEvidenceReference> available = new HashSet<>();
        commands.forEach(command -> available.add(command.evidence()));
        if (!commands.equals(commands.stream()
                .sorted(Comparator.comparing(command -> command.evidence().sequence()))
                .toList())) {
            throw new DomainValidationException(
                    "contextPackage.commands", "must use ascending EvidenceSequence order");
        }
        Set<Integer> criterionIndexes = new HashSet<>();
        acceptanceResults.forEach(result -> criterionIndexes.add(result.criterionIndex()));
        if (available.size() != commands.size()
                || criterionIndexes.size() != acceptanceResults.size()
                || !acceptanceResults.equals(acceptanceResults.stream()
                        .sorted(Comparator.comparingInt(AcceptanceResult::criterionIndex))
                        .toList())
                || acceptanceResults.stream()
                        .flatMap(result -> result.evidence().stream())
                        .anyMatch(reference -> !available.contains(reference))) {
            throw new DomainValidationException(
                    "contextPackage.acceptanceResults",
                    "must be ordered, unique and reference only supplied CommandEvidence");
        }
    }

    public static ReviewTestEvidenceReference from(
            TestEvidence evidence, List<ReviewCommandEvidenceReference> commands) {
        TestEvidence required = Objects.requireNonNull(evidence, "evidence");
        List<ReviewCommandEvidenceReference> supplied = List.copyOf(
                Objects.requireNonNull(commands, "commands"));
        if (!required.commands().equals(supplied.stream()
                .map(ReviewCommandEvidenceReference::evidence)
                .toList())) {
            throw new DomainValidationException(
                    "contextPackage.commands", "must exactly match TestEvidence order and hashes");
        }
        return new ReviewTestEvidenceReference(
                required.scope(),
                required.taskId(),
                required.taskExecutionId(),
                required.attempt(),
                required.codingTarget(),
                required.id(),
                required.evidenceHash(),
                required.diffGeneration(),
                required.diffManifestHash(),
                supplied,
                required.acceptanceResults());
    }
}
