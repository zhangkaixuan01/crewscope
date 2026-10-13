package io.crewscope.domain.organization.governance.event;

import io.crewscope.domain.shared.DomainEvent;
import java.util.Objects;
import java.util.UUID;

/** Fact emitted after every deletion coordination step reached a terminal state. */
public record OrganizationAccountDeletionCompleted(UUID organizationId, UUID jobId)
        implements DomainEvent {

    public OrganizationAccountDeletionCompleted {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        jobId = Objects.requireNonNull(jobId, "jobId");
    }
}
