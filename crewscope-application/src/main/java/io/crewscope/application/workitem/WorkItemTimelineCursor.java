package io.crewscope.application.workitem;

import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.UUID;

/** Stable keyset position in the unified WorkItem event stream. */
public record WorkItemTimelineCursor(UtcTimestamp occurredAt, UUID canonicalEventId) {

  public WorkItemTimelineCursor {
    occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    canonicalEventId =
        AggregateId.requireValue(canonicalEventId, "WorkItemTimelineCursor.canonicalEventId");
  }
}
