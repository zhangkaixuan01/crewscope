package io.crewscope.domain.organization.governance.event;

import io.crewscope.domain.shared.DomainEvent;
import java.util.Objects;
import java.util.UUID;

/** Fact emitted after a deletion job was queued in PENDING_CONFIRMATION. */
public record OrganizationAccountDeletionRequested(
        UUID organizationId, UUID jobId, UUID accountId, UUID targetPrincipalId)
        implements DomainEvent {

    public OrganizationAccountDeletionRequested {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        jobId = Objects.requireNonNull(jobId, "jobId");
        accountId = Objects.requireNonNull(accountId, "accountId");
        targetPrincipalId = Objects.requireNonNull(targetPrincipalId, "targetPrincipalId");
    }
}
