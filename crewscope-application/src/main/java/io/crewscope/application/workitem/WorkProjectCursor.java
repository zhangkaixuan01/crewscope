package io.crewscope.application.workitem;

import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Objects;

/** Stable keyset cursor ordered by update time and WorkProject ID in descending order. */
public record WorkProjectCursor(UtcTimestamp updatedAt, WorkProjectId id) {

  public WorkProjectCursor {
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    id = Objects.requireNonNull(id, "id");
  }
}
