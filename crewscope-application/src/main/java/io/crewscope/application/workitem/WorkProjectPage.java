package io.crewscope.application.workitem;

import io.crewscope.domain.workitem.WorkProject;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable keyset page returned by the WorkProject Repository Port. */
public record WorkProjectPage(List<WorkProject> items, Optional<WorkProjectCursor> nextCursor) {

  public WorkProjectPage {
    items = List.copyOf(Objects.requireNonNull(items, "items"));
    nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
  }
}
