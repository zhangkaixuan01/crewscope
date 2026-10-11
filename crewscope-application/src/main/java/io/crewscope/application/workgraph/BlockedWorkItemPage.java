package io.crewscope.application.workgraph;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One keyset page of the derived "currently blocked" aggregation (ADR-033). {@code nextPosition}
 * is present exactly when more rows follow the page and restarts the next page exclusively.
 */
public record BlockedWorkItemPage(
        List<BlockedWorkItemRow> items, Optional<BlockedItemPosition> nextPosition) {

    public BlockedWorkItemPage {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        Objects.requireNonNull(nextPosition, "nextPosition");
    }
}
