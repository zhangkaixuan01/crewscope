package io.crewscope.domain.organization.governance.event;

import io.crewscope.domain.shared.DomainEvent;
import java.util.Objects;
import java.util.UUID;

/** Fact emitted after an export job was stopped; already DONE artifacts stay for audit. */
public record OrganizationDataExportCancelled(UUID organizationId, UUID jobId)
        implements DomainEvent {

    public OrganizationDataExportCancelled {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        jobId = Objects.requireNonNull(jobId, "jobId");
    }
}
