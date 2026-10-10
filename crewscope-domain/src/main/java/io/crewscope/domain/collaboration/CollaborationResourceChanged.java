package io.crewscope.domain.collaboration;

import java.util.Objects;
import java.util.Set;

/**
 * The A01 change-notification fact (ADR-032): one resource moved to a new version, plus
 * the subscription scopes the signal must reach. The type carries coordinates and a
 * version number only — titles, content and every other business payload are structurally
 * absent, which is what lets the transport deliver a frame before its authorization
 * revalidation completes without ever leaking more than the fact that "something changed".
 */
public record CollaborationResourceChanged(
        CollaborationResourceScope resource, long version, Set<CollaborationResourceScope> audience) {

    public CollaborationResourceChanged {
        resource = Objects.requireNonNull(resource, "resource");
        if (resource instanceof TeamScope) {
            // A Team-wide scope is a subscription granularity, never a changed resource:
            // the fact must name the concrete resource (or project) that moved.
            throw new IllegalArgumentException(
                    "resource must be a ResourceScope or WorkProjectScope, not a TeamScope");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        Objects.requireNonNull(audience, "audience");
        if (audience.isEmpty()) {
            throw new IllegalArgumentException("audience must not be empty");
        }
        if (!audience.contains(resource)) {
            throw new IllegalArgumentException("audience must contain the changed resource");
        }
        audience = Set.copyOf(audience);
    }
}
