package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainException;
import io.crewscope.domain.task.TaskId;
import java.util.Map;
import java.util.Objects;

/** Reports an immutable CodingTarget revision already reserved for the same Task. */
public final class CodingTargetSnapshotRevisionConflictException extends DomainException {

    public CodingTargetSnapshotRevisionConflictException(TaskId taskId, long revision) {
        super(new DomainError(
                DomainErrorCode.CODING_TARGET_SNAPSHOT_REVISION_CONFLICT,
                "Coding target snapshot revision already exists for this Task",
                Map.of(
                        "taskId", Objects.requireNonNull(taskId, "taskId").toString(),
                        "revision", Long.toString(requireRevision(revision)))));
    }

    private static long requireRevision(long value) {
        if (value < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        return value;
    }
}
