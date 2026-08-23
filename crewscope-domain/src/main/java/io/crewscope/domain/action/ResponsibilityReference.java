package io.crewscope.domain.action;

import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.Objects;

/** Exact active responsibility that makes the member accountable for the delivery. */
public record ResponsibilityReference(
        ResponsibilityAssignmentId id,
        long version,
        ResponsibilityRole role,
        PrincipalId actorPrincipalId) {

    public ResponsibilityReference {
        id = Objects.requireNonNull(id, "id");
        if (version < 0) {
            throw new IllegalArgumentException("responsibility version must not be negative");
        }
        role = Objects.requireNonNull(role, "role");
        actorPrincipalId = Objects.requireNonNull(actorPrincipalId, "actorPrincipalId");
    }
}
