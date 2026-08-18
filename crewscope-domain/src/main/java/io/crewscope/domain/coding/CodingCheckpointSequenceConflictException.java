package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainException;
import java.util.Map;
import java.util.Objects;

/** Raised when a repository cannot append the next monotonic checkpoint sequence. */
public final class CodingCheckpointSequenceConflictException extends DomainException {

    public CodingCheckpointSequenceConflictException(
            ExecutionWorkspaceId workspaceId, long checkpointSequence) {
        super(new DomainError(
                DomainErrorCode.CODING_CHECKPOINT_SEQUENCE_CONFLICT,
                "CodingCheckpoint sequence already exists for this ExecutionWorkspace",
                Map.of(
                        "executionWorkspaceId",
                        Objects.requireNonNull(workspaceId, "workspaceId").toString(),
                        "checkpointSequence",
                        Long.toString(requireSequence(checkpointSequence)))));
    }

    private static long requireSequence(long value) {
        if (value < 1) {
            throw new IllegalArgumentException("checkpointSequence must be positive");
        }
        return value;
    }
}
