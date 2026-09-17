package io.crewscope.application.workitem;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable keyset page returned by {@link WorkItemQueryService}.
 *
 * <p>Distinct from {@link WorkItemPage}, which is the Repository Port's page of bare aggregates: this
 * one has the availability verdict attached, which only the application layer is allowed to compute.
 */
public record WorkItemListPage(List<WorkItemListRow> items, Optional<WorkItemCursor> nextCursor) {

  public WorkItemListPage {
    items = List.copyOf(Objects.requireNonNull(items, "items"));
    nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
  }
}
