package io.crewscope.application.task;

import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.task.ExecutionLeaseId;
import io.crewscope.domain.task.LeaseOwnership;
import java.util.Objects;

/** Complete trusted coordinates used to fence one Worker Lease command. */
public record LeaseCommandScope(
        OrganizationId organizationId,
        RuntimeEnvironment environment,
        ExecutionLeaseId leaseId,
        LeaseOwnership ownership) {

    public LeaseCommandScope {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(leaseId, "leaseId");
        Objects.requireNonNull(ownership, "ownership");
    }
}
