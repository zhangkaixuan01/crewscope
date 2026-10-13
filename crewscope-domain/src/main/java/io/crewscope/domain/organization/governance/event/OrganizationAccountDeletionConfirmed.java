package io.crewscope.domain.organization.governance.event;

import io.crewscope.domain.shared.DomainEvent;
import java.util.Objects;
import java.util.UUID;

/** Fact emitted after the username-echo token confirmation moved a deletion job to CONFIRMED. */
public record OrganizationAccountDeletionConfirmed(UUID organizationId, UUID jobId)
        implements DomainEvent {

    public OrganizationAccountDeletionConfirmed {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        jobId = Objects.requireNonNull(jobId, "jobId");
    }
}
