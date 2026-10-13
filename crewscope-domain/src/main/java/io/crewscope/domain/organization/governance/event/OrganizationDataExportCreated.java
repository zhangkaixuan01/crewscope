package io.crewscope.domain.organization.governance.event;

import io.crewscope.domain.shared.DomainEvent;
import java.util.Objects;
import java.util.UUID;

/** Fact emitted after a data export job was queued with its pinned batches. */
public record OrganizationDataExportCreated(
        UUID organizationId, UUID jobId, boolean includeVectors, int itemCount)
        implements DomainEvent {

    public OrganizationDataExportCreated {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        jobId = Objects.requireNonNull(jobId, "jobId");
    }
}
