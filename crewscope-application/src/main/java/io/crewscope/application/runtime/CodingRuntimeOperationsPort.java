package io.crewscope.application.runtime;

import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Optional;

/** Worker-local Port for safe Coding health and idempotent maintenance operations. */
public interface CodingRuntimeOperationsPort {

    Optional<CodingRuntimeSnapshot> observe(
            OrganizationId organizationId, RuntimeEnvironment environment);

    CodingRuntimeMaintenanceOutcome maintain(
            OrganizationId organizationId,
            RuntimeEnvironment environment,
            CodingRuntimeMaintenanceOperation operation);
}
