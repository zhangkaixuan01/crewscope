package io.crewscope.infrastructure.runtime;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Objects;

/** Stable tenant, environment and service actor used by the Task Token boundary. */
public record TaskTokenServiceSpec(
        OrganizationId organizationId, RuntimeEnvironment environment, Principal actor) {
    public TaskTokenServiceSpec {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        environment = Objects.requireNonNull(environment, "environment");
        actor = Objects.requireNonNull(actor, "actor");
        if (!actor.canAct()) {
            throw new IllegalArgumentException("Task Token actor must be active");
        }
        if (!actor.scope().organizationId().equals(organizationId)) {
            throw new IllegalArgumentException("Task Token actor must belong to the Organization");
        }
    }
}
