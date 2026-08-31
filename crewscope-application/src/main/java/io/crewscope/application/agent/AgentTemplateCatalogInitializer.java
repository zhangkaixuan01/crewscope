package io.crewscope.application.agent;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;

/** Ensures the platform-owned Agent template catalog exists for one Organization. */
@FunctionalInterface
public interface AgentTemplateCatalogInitializer {

    /** Idempotently restores missing built-in templates without changing existing versions. */
    void initialize(OrganizationId organizationId, PrincipalId actor, UtcTimestamp occurredAt);
}
