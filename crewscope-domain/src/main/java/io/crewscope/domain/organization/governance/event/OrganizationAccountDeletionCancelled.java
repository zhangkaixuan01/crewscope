package io.crewscope.domain.organization.governance.event;

import io.crewscope.domain.shared.DomainEvent;
import java.util.Objects;
import java.util.UUID;

/** Fact emitted after an unconfirmed or partially processed deletion job was cancelled. */
public record OrganizationAccountDeletionCancelled(UUID organizationId, UUID jobId)
        implements DomainEvent {

    public OrganizationAccountDeletionCancelled {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        jobId = Objects.requireNonNull(jobId, "jobId");
    }
}
