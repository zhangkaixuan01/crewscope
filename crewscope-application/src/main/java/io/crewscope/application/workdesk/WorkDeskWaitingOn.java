package io.crewscope.application.workdesk;

import java.util.Objects;
import java.util.Optional;

/**
 * Whom one WorkDesk row is waiting on (M9b-A06): a principal whose action unblocks it.
 *
 * <p>The display name is what the adapter could read in the same query; it is empty when the row
 * carries only the ID and the surface resolves the name from its own member list.
 */
public record WorkDeskWaitingOn(
    String principalId, Optional<String> displayName, Optional<String> role) {

    public WorkDeskWaitingOn {
        if (principalId == null || principalId.isBlank()) {
            throw new IllegalArgumentException("principalId must not be blank");
        }
        displayName = Objects.requireNonNull(displayName, "displayName");
        role = Objects.requireNonNull(role, "role");
    }
}
