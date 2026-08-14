package io.crewscope.domain.task;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Monotonic durable Step checkpoint; large state remains in later RuntimeArtifact references. */
public record StepCheckpoint(
        long sequence,
        String code,
        TaskFactHash payloadHash,
        PrincipalId recordedByPrincipalId,
        UtcTimestamp recordedAt) {

    private static final String CODE_PATTERN = "[A-Z][A-Z0-9_]{0,63}";

    public StepCheckpoint {
        if (sequence < 1) {
            throw new DomainValidationException("stepCheckpoint.sequence", "must be positive");
        }
        if (code == null || !code.matches(CODE_PATTERN)) {
            throw new DomainValidationException(
                    "stepCheckpoint.code", "must be a stable uppercase code");
        }
        payloadHash = Objects.requireNonNull(payloadHash, "payloadHash");
        recordedByPrincipalId = Objects.requireNonNull(
                recordedByPrincipalId, "recordedByPrincipalId");
        recordedAt = Objects.requireNonNull(recordedAt, "recordedAt");
    }
}
