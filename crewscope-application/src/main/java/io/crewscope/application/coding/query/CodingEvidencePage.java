package io.crewscope.application.coding.query;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One immutable keyset page returned without a count query. */
public record CodingEvidencePage<T>(List<T> items, Optional<CodingEvidenceCursor> nextCursor) {

    public CodingEvidencePage {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
    }
}
