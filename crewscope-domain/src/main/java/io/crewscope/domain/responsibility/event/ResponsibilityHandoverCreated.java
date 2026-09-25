package io.crewscope.domain.responsibility.event;

import io.crewscope.domain.shared.DomainEvent;
import java.util.Objects;
import java.util.UUID;

/** Fact emitted after a responsibility handover job was queued for one member and role. */
public record ResponsibilityHandoverCreated(
        UUID jobId,
        UUID sourceMemberId,
        UUID targetPrincipalId,
        String role,
        int itemCount,
        long sourceAuthorizationVersion)
        implements DomainEvent {

    public ResponsibilityHandoverCreated {
        jobId = Objects.requireNonNull(jobId, "jobId");
        sourceMemberId = Objects.requireNonNull(sourceMemberId, "sourceMemberId");
        targetPrincipalId = Objects.requireNonNull(targetPrincipalId, "targetPrincipalId");
        role = Objects.requireNonNull(role, "role");
        if (itemCount < 1) {
            throw new IllegalArgumentException("itemCount must be positive");
        }
    }
}
