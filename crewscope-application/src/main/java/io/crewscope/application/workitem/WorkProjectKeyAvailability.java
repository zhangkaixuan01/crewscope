package io.crewscope.application.workitem;

import io.crewscope.domain.workitem.WorkProjectKey;
import java.util.Objects;

/** Membership-authorized availability result for a normalized WorkProject key. */
public record WorkProjectKeyAvailability(WorkProjectKey key, boolean available) {

  public WorkProjectKeyAvailability {
    key = Objects.requireNonNull(key, "key");
  }
}
