package io.crewscope.application.model;

import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;

/** Verifies current non-secret connection and credential availability before AgentScope is built. */
public interface ModelConnectionAvailabilityVerifier {

    void requireAvailable(
            ModelConnection connection, PrincipalId requestingPrincipalId, UtcTimestamp checkedAt);

    /** Invalidates cached availability after a connection or credential lifecycle mutation. */
    default void invalidate(OrganizationId organizationId, ModelConnectionId connectionId) {}

    /** Compatibility verifier for callers that already supply a current persisted connection. */
    static ModelConnectionAvailabilityVerifier persistedStateOnly() {
        return (connection, requestingPrincipalId, checkedAt) -> {};
    }
}
