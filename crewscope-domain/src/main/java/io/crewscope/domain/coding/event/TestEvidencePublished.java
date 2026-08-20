package io.crewscope.domain.coding.event;

import io.crewscope.domain.coding.AcceptanceStatus;
import io.crewscope.domain.coding.TestEvidence;
import io.crewscope.domain.shared.DomainEvent;
import java.util.Objects;
import java.util.UUID;

/** Public verification summary without command input, logs or Artifact locations. */
public record TestEvidencePublished(
        UUID testEvidenceId,
        UUID workspaceId,
        UUID taskExecutionId,
        int attempt,
        long evidenceSequence,
        long diffGeneration,
        String manifestHash,
        boolean succeeded,
        long total,
        long passed,
        long failed,
        long errors,
        long skipped,
        long acceptancePassed,
        long acceptanceFailed,
        long acceptanceNotEvaluated,
        String evidenceHash) implements DomainEvent {

    public TestEvidencePublished {
        testEvidenceId = Objects.requireNonNull(testEvidenceId, "testEvidenceId");
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        if (attempt < 1 || evidenceSequence < 1 || diffGeneration < 1) {
            throw new IllegalArgumentException("Test Evidence counters must be positive");
        }
        manifestHash = Objects.requireNonNull(manifestHash, "manifestHash");
        evidenceHash = Objects.requireNonNull(evidenceHash, "evidenceHash");
    }

    public static TestEvidencePublished from(TestEvidence evidence) {
        TestEvidence value = Objects.requireNonNull(evidence, "evidence");
        return new TestEvidencePublished(
                value.id().value(),
                value.executionWorkspaceId().value(),
                value.taskExecutionId().value(),
                value.attempt(),
                value.sequence().value(),
                value.diffGeneration().value(),
                value.diffManifestHash().value(),
                value.succeeded(),
                value.statistics().total(),
                value.statistics().passed(),
                value.statistics().failed(),
                value.statistics().errors(),
                value.statistics().skipped(),
                count(value, AcceptanceStatus.PASSED),
                count(value, AcceptanceStatus.FAILED),
                count(value, AcceptanceStatus.NOT_EVALUATED),
                value.evidenceHash().value());
    }

    private static long count(TestEvidence evidence, AcceptanceStatus status) {
        return evidence.acceptanceResults().stream()
                .filter(result -> result.status() == status)
                .count();
    }
}
