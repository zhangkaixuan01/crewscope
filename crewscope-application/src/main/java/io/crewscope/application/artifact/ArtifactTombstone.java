package io.crewscope.application.artifact;

import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/** Durable logical deletion fact retained before physical content cleanup. */
public record ArtifactTombstone(
        ArtifactTombstoneReason reason,
        Optional<String> detail,
        PrincipalId tombstonedBy,
        UtcTimestamp tombstonedAt) {

    public static final int MAX_DETAIL_LENGTH = 500;

    public ArtifactTombstone {
        Objects.requireNonNull(reason, "reason");
        detail = normalizeDetail(detail);
        Objects.requireNonNull(tombstonedBy, "tombstonedBy");
        Objects.requireNonNull(tombstonedAt, "tombstonedAt");
    }

    /** Supports an idempotent retry without changing the original actor or timestamp. */
    public boolean matches(ArtifactTombstoneReason requestedReason, Optional<String> requestedDetail) {
        return reason == Objects.requireNonNull(requestedReason, "requestedReason")
                && detail.equals(normalizeDetail(requestedDetail));
    }

    private static Optional<String> normalizeDetail(Optional<String> value) {
        Optional<String> required = Objects.requireNonNull(value, "detail");
        if (required.isEmpty()) {
            return required;
        }
        String normalized = required.orElseThrow().strip();
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        if (normalized.length() > MAX_DETAIL_LENGTH) {
            throw new IllegalArgumentException(
                    "Tombstone detail must contain at most " + MAX_DETAIL_LENGTH + " characters");
        }
        return Optional.of(normalized);
    }
}
