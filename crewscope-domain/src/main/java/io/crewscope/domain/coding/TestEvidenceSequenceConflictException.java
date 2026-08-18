package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainException;
import java.util.Map;
import java.util.Objects;

/** Reports a duplicate TestEvidence sequence within one ExecutionWorkspace. */
public final class TestEvidenceSequenceConflictException extends DomainException {

    public TestEvidenceSequenceConflictException(
            ExecutionWorkspaceId workspaceId, EvidenceSequence sequence) {
        super(new DomainError(
                DomainErrorCode.TEST_EVIDENCE_SEQUENCE_CONFLICT,
                "TestEvidence sequence already exists for this ExecutionWorkspace",
                Map.of(
                        "executionWorkspaceId",
                        Objects.requireNonNull(workspaceId, "workspaceId").toString(),
                        "evidenceSequence",
                        Objects.requireNonNull(sequence, "sequence").toString())));
    }
}
