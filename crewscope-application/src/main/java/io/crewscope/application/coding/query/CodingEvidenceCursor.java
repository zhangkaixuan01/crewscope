package io.crewscope.application.coding.query;

import java.util.Objects;
import java.util.UUID;

/** Stable keyset position shared by the immutable Command and TestEvidence streams. */
public record CodingEvidenceCursor(long sequence, UUID id) {

    public CodingEvidenceCursor {
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        id = Objects.requireNonNull(id, "id");
    }
}
