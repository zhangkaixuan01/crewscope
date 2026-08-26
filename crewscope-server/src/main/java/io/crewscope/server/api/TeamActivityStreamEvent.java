package io.crewscope.server.api;

import io.crewscope.domain.activity.ActivityEvent;
import java.util.Objects;

/** Internal safe Team frame; M6-A01 maps it to the final public Activity DTO. */
public record TeamActivityStreamEvent(String streamType, ActivityEvent activity) {

  public static final String TEAM_STREAM = "TEAM";

  public TeamActivityStreamEvent {
    if (!TEAM_STREAM.equals(streamType)) {
      throw new IllegalArgumentException("Team Activity frame must use the TEAM stream type");
    }
    activity = Objects.requireNonNull(activity, "activity");
  }

  public static TeamActivityStreamEvent from(ActivityEvent activity) {
    return new TeamActivityStreamEvent(TEAM_STREAM, activity);
  }
}
