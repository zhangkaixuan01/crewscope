package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainException;
import java.util.Map;
import java.util.Objects;

/** Reports a duplicate CommandEvidence sequence within one ExecutionWorkspace. */
public final class CommandEvidenceSequenceConflictException extends DomainException {

    public CommandEvidenceSequenceConflictException(
            ExecutionWorkspaceId workspaceId, EvidenceSequence sequence) {
        super(new DomainError(
                DomainErrorCode.COMMAND_EVIDENCE_SEQUENCE_CONFLICT,
                "CommandEvidence sequence already exists for this ExecutionWorkspace",
                Map.of(
                        "executionWorkspaceId",
                        Objects.requireNonNull(workspaceId, "workspaceId").toString(),
                        "evidenceSequence",
                        Objects.requireNonNull(sequence, "sequence").toString())));
    }
}
