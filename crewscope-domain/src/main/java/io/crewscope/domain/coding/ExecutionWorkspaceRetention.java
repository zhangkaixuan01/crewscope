package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Immutable retention boundary after which local delivery resources may be archived. */
public record ExecutionWorkspaceRetention(UtcTimestamp retainUntil) {

    public ExecutionWorkspaceRetention {
        retainUntil = Objects.requireNonNull(retainUntil, "retainUntil");
    }

    public void validateAfter(UtcTimestamp createdAt) {
        if (retainUntil.compareTo(Objects.requireNonNull(createdAt, "createdAt")) <= 0) {
            throw new DomainValidationException(
                    "executionWorkspace.retention.retainUntil", "must be after creation time");
        }
    }

    public boolean isDue(UtcTimestamp authoritativeNow) {
        return Objects.requireNonNull(authoritativeNow, "authoritativeNow")
                        .compareTo(retainUntil)
                >= 0;
    }
}
