package io.crewscope.application.operations;

import io.crewscope.domain.shared.id.OrganizationId;

/** Fixed-query persistence Port for safe operations health and administrator diagnostics. */
public interface OperationsHealthQueryPort {

    OperationsHealthSnapshot observe(OrganizationId organizationId);
}
