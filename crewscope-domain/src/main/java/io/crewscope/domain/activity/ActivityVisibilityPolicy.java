package io.crewscope.domain.activity;

import java.util.Objects;

/** Fails closed on tenant scope before evaluating the event audience class. */
public final class ActivityVisibilityPolicy {

    public boolean canView(ActivityEvent event, ActivityViewer viewer) {
        ActivityEvent requiredEvent = Objects.requireNonNull(event, "event");
        ActivityViewer requiredViewer = Objects.requireNonNull(viewer, "viewer");
        if (!requiredEvent.organizationId().equals(requiredViewer.organizationId())
                || !requiredEvent.teamId().equals(requiredViewer.teamId())
                || !requiredViewer.activeTeamMember()) {
            return false;
        }
        return switch (requiredEvent.visibility()) {
            case TEAM_MEMBERS -> true;
            case TEAM_ADMINS -> requiredViewer.teamAdmin();
            case WORK_ITEM_PARTICIPANTS -> requiredViewer.teamAdmin()
                    || requiredEvent.restrictedWorkItemId()
                            .filter(requiredViewer.visibleWorkItems()::contains)
                            .isPresent();
        };
    }
}
