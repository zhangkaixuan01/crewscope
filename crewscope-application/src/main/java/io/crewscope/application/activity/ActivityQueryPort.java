package io.crewscope.application.activity;

import io.crewscope.domain.activity.ActivityEvent;
import io.crewscope.domain.activity.ActivityEventId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Optional;

/** Persistence boundary returning canonical Activity events without view-specific remapping. */
public interface ActivityQueryPort {

    ActivityPage find(ActivityQuery query);

    /** Reads one event only from the current active projection generation and exact tenant scope. */
    Optional<ActivityEvent> findCurrentById(
            OrganizationId organizationId, TeamId teamId, ActivityEventId eventId);
}
